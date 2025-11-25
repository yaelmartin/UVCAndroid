package com.herohan.uvcdemo;

import android.util.Log;

import com.serenegiant.usb.IFrameCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams raw YUV422 thermal camera data over HTTP with MJPEG-style framing
 * Clients can connect via: http://[device-ip]:8080/stream
 */
public class ThermalStreamServer {
    private static final String TAG = "ThermalStreamServer";
    private static final int PORT = 8080;
    private static final int MAX_CLIENTS = 5;

    // Thermal camera dimensions (adjust if different)
    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 192; // Cropped from 384
    private static final int BYTES_PER_FRAME = FRAME_WIDTH * FRAME_HEIGHT * 2; // YUV422 = 2 bytes/pixel

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private ConcurrentLinkedQueue<ClientHandler> clients = new ConcurrentLinkedQueue<>();

    // Latest frame buffer
    private byte[] latestFrame = new byte[BYTES_PER_FRAME];
    private final Object frameLock = new Object();
    private volatile boolean hasNewFrame = false;

    public interface StreamCallback {
        void onServerStarted(String url);
        void onServerError(String error);
        void onClientConnected(int clientCount);
        void onClientDisconnected(int clientCount);
    }

    private StreamCallback callback;

    public ThermalStreamServer(StreamCallback callback) {
        this.callback = callback;
    }

    /**
     * Start the HTTP streaming server
     */
    public void start() {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running");
            return;
        }

        executorService = Executors.newCachedThreadPool();

        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning.set(true);

                String localIp = getLocalIpAddress();
                String url = "http://" + localIp + ":" + PORT + "/stream";
                Log.i(TAG, "Server started on " + url);

                if (callback != null) {
                    callback.onServerStarted(url);
                }

                // Accept client connections
                while (isRunning.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();

                        if (clients.size() >= MAX_CLIENTS) {
                            Log.w(TAG, "Max clients reached, rejecting connection");
                            clientSocket.close();
                            continue;
                        }

                        ClientHandler handler = new ClientHandler(clientSocket);
                        clients.add(handler);
                        executorService.execute(handler);

                        Log.i(TAG, "Client connected: " + clientSocket.getInetAddress());
                        if (callback != null) {
                            callback.onClientConnected(clients.size());
                        }

                    } catch (IOException e) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting client", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error", e);
                if (callback != null) {
                    callback.onServerError(e.getMessage());
                }
            }
        });
    }

    /**
     * Stop the server and disconnect all clients
     */
    public void stop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        // Close all client connections
        for (ClientHandler client : clients) {
            client.close();
        }
        clients.clear();

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }

        // Shutdown executor
        if (executorService != null) {
            executorService.shutdownNow();
        }

        Log.i(TAG, "Server stopped");
    }

    /**
     * Get frame callback for UVC camera
     * Use with: mCameraHelper.setFrameCallback(streamServer.getFrameCallback(), UVCCamera.PIXEL_FORMAT_YUV422SP);
     */
    public IFrameCallback getFrameCallback() {
        return new IFrameCallback() {
            @Override
            public void onFrame(ByteBuffer frame) {
                synchronized (frameLock) {
                    if (frame.remaining() >= BYTES_PER_FRAME) {
                        // Crop: take only the bottom half (192 rows from 384)
                        int originalHeight = 384;
                        int bytesPerRow = FRAME_WIDTH * 2;
                        int skipBytes = (originalHeight / 2) * bytesPerRow;

                        frame.position(skipBytes);
                        frame.get(latestFrame, 0, BYTES_PER_FRAME);
                        hasNewFrame = true;
                        frameLock.notifyAll();
                    }
                }
            }
        };
    }

    /**
     * Client handler for streaming to individual connections
     */
    private class ClientHandler implements Runnable {
        private Socket socket;
        private OutputStream outputStream;
        private volatile boolean active = true;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                outputStream = socket.getOutputStream();

                // Send HTTP header for multipart stream
                String header =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Connection: close\r\n" +
                                "\r\n";

                outputStream.write(header.getBytes());
                outputStream.flush();

                // Stream frames
                byte[] frameBuffer = new byte[BYTES_PER_FRAME];

                while (active && isRunning.get()) {
                    // Wait for new frame
                    synchronized (frameLock) {
                        while (!hasNewFrame && active) {
                            try {
                                frameLock.wait(1000); // Timeout to check active state
                            } catch (InterruptedException e) {
                                break;
                            }
                        }

                        if (!active) break;

                        // Copy frame
                        System.arraycopy(latestFrame, 0, frameBuffer, 0, BYTES_PER_FRAME);
                        hasNewFrame = false;
                    }

                    try {
                        // Send frame with multipart boundary
                        String frameHeader =
                                "--frame\r\n" +
                                        "Content-Type: application/octet-stream\r\n" +
                                        "Content-Length: " + BYTES_PER_FRAME + "\r\n" +
                                        "X-Frame-Width: " + FRAME_WIDTH + "\r\n" +
                                        "X-Frame-Height: " + FRAME_HEIGHT + "\r\n" +
                                        "X-Pixel-Format: YUV422\r\n" +
                                        "\r\n";

                        outputStream.write(frameHeader.getBytes());
                        outputStream.write(frameBuffer);
                        outputStream.write("\r\n".getBytes());
                        outputStream.flush();

                    } catch (IOException e) {
                        Log.w(TAG, "Error sending frame to client", e);
                        break;
                    }
                }

            } catch (IOException e) {
                Log.e(TAG, "Client handler error", e);
            } finally {
                close();
                clients.remove(this);

                Log.i(TAG, "Client disconnected");
                if (callback != null) {
                    callback.onClientDisconnected(clients.size());
                }
            }
        }

        void close() {
            active = false;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    /**
     * Get local IP address
     */
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();

                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();

                    // Get IPv4 address
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "localhost";
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getClientCount() {
        return clients.size();
    }
}