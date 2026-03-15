package com.glassvision;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Manages Camera API v1: opens camera, configures preview, captures JPEG snapshots.
 * Adapted from glass-stream CameraManager — removed FrameBuffer dependency,
 * replaced with volatile latestJpeg + captureSnapshot().
 */
@SuppressWarnings("deprecation")
public class CameraManager implements Camera.PreviewCallback {

    private static final String TAG = "CameraManager";
    private static final int NUM_BUFFERS = 3;
    private static final int JPEG_QUALITY = 70;

    private Camera camera;
    private volatile boolean running;
    private int previewWidth;
    private int previewHeight;
    private final ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
    private volatile byte[] latestJpeg;

    public void start(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();

            Camera.Size bestSize = selectPreviewSize(params.getSupportedPreviewSizes());
            previewWidth = bestSize.width;
            previewHeight = bestSize.height;
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(holder);

            int bufferSize = previewWidth * previewHeight
                    * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < NUM_BUFFERS; i++) {
                camera.addCallbackBuffer(new byte[bufferSize]);
            }
            camera.setPreviewCallbackWithBuffer(this);

            running = true;
            camera.startPreview();
            Log.i(TAG, "Camera started: " + previewWidth + "x" + previewHeight);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start camera", e);
        }
    }

    public void stop() {
        running = false;
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    /** Returns the most recent JPEG frame, or null if none captured yet. */
    public byte[] captureSnapshot() {
        return latestJpeg;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!running || data == null) {
            if (camera != null && data != null) {
                camera.addCallbackBuffer(data);
            }
            return;
        }

        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21,
                previewWidth, previewHeight, null);
        jpegStream.reset();
        yuvImage.compressToJpeg(
                new Rect(0, 0, previewWidth, previewHeight), JPEG_QUALITY, jpegStream);
        latestJpeg = jpegStream.toByteArray();

        camera.addCallbackBuffer(data);
    }

    private Camera.Size selectPreviewSize(List<Camera.Size> sizes) {
        for (Camera.Size size : sizes) {
            if (size.width == 1280 && size.height == 720) {
                return size;
            }
        }
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (size.width * size.height > best.width * best.height) {
                best = size;
            }
        }
        return best;
    }
}
