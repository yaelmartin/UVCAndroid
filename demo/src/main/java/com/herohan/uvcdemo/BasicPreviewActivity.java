package com.herohan.uvcdemo;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class BasicPreviewActivity extends AppCompatActivity implements View.OnClickListener {

    private static final boolean DEBUG = true;
    private static final String TAG = BasicPreviewActivity.class.getSimpleName();

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    private ICameraHelper mCameraHelper;
    private AspectRatioSurfaceView mCameraViewMain;

    // WebSocket streaming server
    private ThermalWebSocketServer webSocketServer;
    private Button btnStartStream;
    private Button btnStopStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_preview);
        setTitle(R.string.entry_basic_preview);

        initViews();
        initWebSocketServer();
    }

    private void initViews() {
        mCameraViewMain = findViewById(R.id.svCameraViewMain);
        mCameraViewMain.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        mCameraViewMain.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.addSurface(holder.getSurface(), false);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.removeSurface(holder.getSurface());
                }
            }
        });

        Button btnOpenCamera = findViewById(R.id.btnOpenCamera);
        btnOpenCamera.setOnClickListener(this);
        Button btnCloseCamera = findViewById(R.id.btnCloseCamera);
        btnCloseCamera.setOnClickListener(this);

        // Stream control buttons
        btnStartStream = findViewById(R.id.btnStartStream);
        if (btnStartStream != null) {
            btnStartStream.setOnClickListener(this);
        }
        btnStopStream = findViewById(R.id.btnStopStream);
        if (btnStopStream != null) {
            btnStopStream.setOnClickListener(this);
        }
    }

    private void initWebSocketServer() {
        webSocketServer = new ThermalWebSocketServer(new ThermalWebSocketServer.StreamCallback() {
            @Override
            public void onServerStarted(String url) {
                runOnUiThread(() -> {
                    Toast.makeText(BasicPreviewActivity.this,
                            "WebSocket Server Started!\n" + url,
                            Toast.LENGTH_LONG).show();
                    Log.i(TAG, "WebSocket URL: " + url);

                    if (btnStartStream != null) btnStartStream.setEnabled(true);
                    if (btnStopStream != null) btnStopStream.setEnabled(false);
                });
            }

            @Override
            public void onServerError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(BasicPreviewActivity.this,
                            "WebSocket Error: " + error,
                            Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "WebSocket Error: " + error);
                });
            }

            @Override
            public void onClientConnected(int clientCount) {
                runOnUiThread(() -> {
                    String msg = "Client connected! Total: " + clientCount;
                    Toast.makeText(BasicPreviewActivity.this, msg, Toast.LENGTH_SHORT).show();
                    Log.i(TAG, msg);
                });
            }

            @Override
            public void onClientDisconnected(int clientCount) {
                runOnUiThread(() -> {
                    String msg = "Client disconnected. Remaining: " + clientCount;
                    Toast.makeText(BasicPreviewActivity.this, msg, Toast.LENGTH_SHORT).show();
                    Log.i(TAG, msg);
                });
            }
        });

        // Start the WebSocket server
        try {
            webSocketServer.start();
            Log.i(TAG, "WebSocket server starting...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebSocket server", e);
            Toast.makeText(this, "Failed to start WebSocket server: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        initCameraHelper();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStreaming();
        clearCameraHelper();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        shutdownWebSocketServer();
    }

    public void initCameraHelper() {
        if (DEBUG) Log.d(TAG, "initCameraHelper:");
        if (mCameraHelper == null) {
            mCameraHelper = new CameraHelper();
            mCameraHelper.setStateCallback(mStateListener);
        }
    }

    private void clearCameraHelper() {
        if (DEBUG) Log.d(TAG, "clearCameraHelper:");
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
    }

    private void selectDevice(final UsbDevice device) {
        if (DEBUG) Log.v(TAG, "selectDevice:device=" + device.getDeviceName());
        mCameraHelper.selectDevice(device);
    }

    private void startStreaming() {
        if (mCameraHelper == null) {
            Toast.makeText(this, "Open camera first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (webSocketServer == null) {
            Toast.makeText(this, "WebSocket server not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        if (webSocketServer.isStreaming()) {
            Toast.makeText(this, "Already streaming", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            mCameraHelper.setFrameCallback(new IFrameCallback() {
                @Override
                public void onFrame(ByteBuffer frame) {

                    final int fullWidth = 256;
                    final int fullHeight = 384; // full camera frame
                    final int cropHeight = 192; // bottom half
                    final int width = 256;

                    // allocate output buffer for 16-bit thermal values
                    ByteBuffer thermalBuffer = ByteBuffer.allocate(width * cropHeight * 2);
                    thermalBuffer.order(ByteOrder.LITTLE_ENDIAN);

                    frame.rewind();

                    // skip top half
                    frame.position(width * (fullHeight - cropHeight) * 2); // 2 bytes per pixel

                    for (int y = 0; y < cropHeight; y++) {
                        for (int x = 0; x < width; x++) {
                            short raw16 = frame.getShort();
                            thermalBuffer.putShort(raw16);
                        }
                    }

                    // send cropped bottom half to WebSocket
                    if (webSocketServer.isStreaming()) {
                        webSocketServer.broadcast(thermalBuffer.array());
                    }
                }
            }, UVCCamera.PIXEL_FORMAT_RAW);



            // Start streaming frames to connected clients
            webSocketServer.startStreaming();

            if (btnStartStream != null) btnStartStream.setEnabled(false);
            if (btnStopStream != null) btnStopStream.setEnabled(true);

            Toast.makeText(this, "Streaming started! Clients can connect now.", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "WebSocket streaming started");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start streaming", e);
            Toast.makeText(this, "Failed to start streaming: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopStreaming() {
        if (webSocketServer != null && webSocketServer.isStreaming()) {
            webSocketServer.stopStreaming();

            // Remove frame callback
            if (mCameraHelper != null) {
                mCameraHelper.setFrameCallback(null, 0);
            }

            runOnUiThread(() -> {
                if (btnStartStream != null) btnStartStream.setEnabled(true);
                if (btnStopStream != null) btnStopStream.setEnabled(false);
                Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show();
            });

            Log.i(TAG, "WebSocket streaming stopped");
        }
    }

    private void shutdownWebSocketServer() {
        if (webSocketServer != null) {
            try {
                webSocketServer.shutdown();
                webSocketServer = null;
                Log.i(TAG, "WebSocket server shutdown");
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down WebSocket server", e);
            }
        }
    }

    private final ICameraHelper.StateCallback mStateListener = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:");
            selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (DEBUG) Log.v(TAG, "onDeviceOpen:");
            mCameraHelper.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraOpen:");

            mCameraHelper.startPreview();

            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                int width = size.width;
                int height = size.height;
                //auto aspect ratio
                mCameraViewMain.setAspectRatio(width, height);
            }

            mCameraHelper.addSurface(mCameraViewMain.getHolder().getSurface(), false);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraClose:");

            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(mCameraViewMain.getHolder().getSurface());
            }

            // Stop streaming when camera closes
            stopStreaming();
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDeviceClose:");
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:");
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:");
        }

    };

    @Override
    public void onClick(View v) {
        int id = v.getId();

        if (id == R.id.btnOpenCamera) {
            // select a uvc device
            if (mCameraHelper != null) {
                final List<UsbDevice> list = mCameraHelper.getDeviceList();
                if (list != null && list.size() > 0) {
                    mCameraHelper.selectDevice(list.get(0));
                }
            }
        } else if (id == R.id.btnCloseCamera) {
            // close camera
            if (mCameraHelper != null) {
                mCameraHelper.closeCamera();
            }
        } else if (id == R.id.btnStartStream) {
            startStreaming();
        } else if (id == R.id.btnStopStream) {
            stopStreaming();
        }
    }
}