package com.herohan.uvcdemo;

import android.util.Log;

import com.serenegiant.usb.IFrameCallback;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket server for streaming raw YUV422 thermal camera data
 */
public class ThermalWebSocketServer extends WebSocketServer {
    private static final String TAG = "ThermalWebSocketServer";
    private static final int PORT = 8080;

    // Thermal camera dimensions
    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 192; // Cropped from 384
    private static final int ORIGINAL_HEIGHT = 384;
    private static final int BYTES_PER_FRAME = FRAME_WIDTH * FRAME_HEIGHT * 2; // YUV422 = 2 bytes/pixel

    private final CopyOnWriteArraySet<WebSocket> clients = new CopyOnWriteArraySet<>();
    private StreamCallback callback;

    // Latest frame buffer
    private byte[] latestFrame = new byte[BYTES_PER_FRAME];
    private final Object frameLock = new Object();
    private volatile boolean hasNewFrame = false;
    private volatile boolean isStreaming = false;

    public interface StreamCallback {
        void onServerStarted(String url);
        void onServerError(String error);
        void onClientConnected(int clientCount);
        void onClientDisconnected(int clientCount);
    }

    public ThermalWebSocketServer(StreamCallback callback) {
        super(new InetSocketAddress("0.0.0.0", PORT));
        this.callback = callback;
        setReuseAddr(true);
        setTcpNoDelay(true);
    }

    @Override
    public void onStart() {
        // Get the actual port that was assigned
        String localIp = getLocalIpAddress();
        String url = "ws://" + localIp + ":" + PORT + "/thermal";
        Log.i(TAG, "✅ WebSocket Server started on " + url);

        if (callback != null) {
            callback.onServerStarted(url);
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        Log.i(TAG, "🔗 Client connected: " + conn.getRemoteSocketAddress() + " | Total: " + clients.size());

        // Send welcome message with stream info
        String welcome = String.format(
                "{\"type\":\"welcome\",\"width\":%d,\"height\":%d,\"format\":\"YUV422\",\"bytesPerFrame\":%d}",
                FRAME_WIDTH, FRAME_HEIGHT, BYTES_PER_FRAME
        );
        conn.send(welcome);

        if (callback != null) {
            callback.onClientConnected(clients.size());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        Log.i(TAG, "❌ Client disconnected: " + conn.getRemoteSocketAddress() +
                " | Reason: " + reason + " | Remaining: " + clients.size());

        if (callback != null) {
            callback.onClientDisconnected(clients.size());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "📩 Received message: " + message);

        // Handle client commands
        switch (message.trim().toUpperCase()) {
            case "PING":
                conn.send("{\"type\":\"pong\"}");
                break;
            case "GET_INFO":
                String info = String.format(
                        "{\"type\":\"info\",\"width\":%d,\"height\":%d,\"format\":\"YUV422\",\"clients\":%d,\"port\":%d}",
                        FRAME_WIDTH, FRAME_HEIGHT, clients.size(), PORT
                );
                conn.send(info);
                break;
            default:
                Log.d(TAG, "Unknown command: " + message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "⚠️ WebSocket error: " + ex.getMessage(), ex);
        if (callback != null) {
            callback.onServerError(ex.getMessage());
        }
    }

    /**
     * Start streaming frames to connected clients
     */
    public void startStreaming() {
        if (isStreaming) {
            Log.w(TAG, "Already streaming");
            return;
        }

        isStreaming = true;

        // Start frame broadcasting thread
        new Thread(() -> {
            Log.i(TAG, "Frame broadcaster thread started");
            byte[] frameBuffer = new byte[BYTES_PER_FRAME];

            while (isStreaming) {
                try {
                    // Wait for new frame
                    synchronized (frameLock) {
                        while (!hasNewFrame && isStreaming) {
                            frameLock.wait(100);
                        }

                        if (!isStreaming) break;

                        // Copy frame
                        System.arraycopy(latestFrame, 0, frameBuffer, 0, BYTES_PER_FRAME);
                        hasNewFrame = false;
                    }

                    // Broadcast to all connected clients
                    if (!clients.isEmpty()) {
                        ByteBuffer frameData = ByteBuffer.wrap(frameBuffer);

                        for (WebSocket client : clients) {
                            try {
                                if (client.isOpen()) {
                                    // Send binary frame data
                                    client.send(frameData.array());
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to send frame to client: " + e.getMessage());
                            }
                        }
                    }

                } catch (InterruptedException e) {
                    Log.d(TAG, "Frame broadcaster interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in frame broadcaster: " + e.getMessage(), e);
                }
            }

            Log.i(TAG, "Frame broadcaster thread stopped");
        }, "FrameBroadcaster").start();
    }

    /**
     * Stop streaming frames
     */
    public void stopStreaming() {
        isStreaming = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    /**
     * Get frame callback for UVC camera
     * Use with: mCameraHelper.setFrameCallback(server.getFrameCallback(), UVCCamera.PIXEL_FORMAT_YUV422SP);
     */
    public IFrameCallback getFrameCallback() {
        return new IFrameCallback() {
            @Override
            public void onFrame(ByteBuffer frame) {
                if (!isStreaming || clients.isEmpty()) {
                    return;
                }

                synchronized (frameLock) {
                    try {
                        int frameSize = frame.remaining();

                        // Handle cropping if needed
                        if (frameSize >= FRAME_WIDTH * ORIGINAL_HEIGHT * 2) {
                            // Crop: take only the bottom half (192 rows from 384)
                            int bytesPerRow = FRAME_WIDTH * 2;
                            int skipBytes = (ORIGINAL_HEIGHT / 2) * bytesPerRow;

                            frame.position(skipBytes);
                            frame.get(latestFrame, 0, BYTES_PER_FRAME);
                        } else if (frameSize >= BYTES_PER_FRAME) {
                            // Already cropped or correct size
                            frame.get(latestFrame, 0, BYTES_PER_FRAME);
                        } else {
                            // Frame too small, skip
                            Log.w(TAG, "Frame too small: " + frameSize + " bytes");
                            return;
                        }

                        hasNewFrame = true;
                        frameLock.notifyAll();

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing frame: " + e.getMessage());
                    }
                }
            }
        };
    }

    /**
     * Broadcast a message to all connected clients
     */
    public void broadcastMessage(String message) {
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) {
                    client.send(message);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send message to client: " + e.getMessage());
            }
        }
    }

    /**
     * Get local IP address
     */
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
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

    /**
     * Shutdown the server
     */
    public void shutdown() {
        try {
            stopStreaming();

            // Notify all clients
            broadcastMessage("{\"type\":\"shutdown\",\"message\":\"Server shutting down\"}");

            // Close all connections
            for (WebSocket client : clients) {
                try {
                    client.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing client connection", e);
                }
            }
            clients.clear();

            // Stop the server
            try {
                stop(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping server", e);
            }

            Log.i(TAG, "Server shutdown complete");
        } catch (Exception e) {
            Log.e(TAG, "Error shutting down server", e);
        }
    }

    public int getClientCount() {
        return clients.size();
    }

    public boolean isStreaming() {
        return isStreaming;
    }
}