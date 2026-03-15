package com.glassvision;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Main activity: state machine, camera SurfaceView, gesture handling, response display.
 *
 * State Machine:
 *   IDLE -> RECORDING -> PROCESSING -> RESPONSE -> RECORDING -> ...
 *
 * Tap / camera button advances the state machine.
 * Swipe down: exit. Long press (800ms): exit.
 */
public class VisionActivity extends Activity
        implements SurfaceHolder.Callback, AudioRecorder.Listener, PhoneConnection.Listener {

    private static final String TAG = "VisionActivity";

    // *** CHANGE THIS to your phone's IP address ***
    private static final String PHONE_HOST = "10.26.209.137";

    private enum State { IDLE, RECORDING, PROCESSING, RESPONSE }

    private State currentState = State.IDLE;

    // UI
    private SurfaceView surfaceView;
    private ScrollView responseScroll;
    private TextView responseText;
    private TextView stateText;
    private View recordingIndicator;
    private TextView statusText;

    // Components
    private CameraManager cameraManager;
    private AudioRecorder audioRecorder;
    private PhoneConnection phoneConnection;
    private Handler handler;

    // Locks
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Gesture tracking
    private float touchStartX, touchStartY;
    private long touchStartTime;

    // Snapshot captured at start of recording
    private byte[] pendingSnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vision);

        handler = new Handler();

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        responseScroll = (ScrollView) findViewById(R.id.response_scroll);
        responseText = (TextView) findViewById(R.id.response_text);
        stateText = (TextView) findViewById(R.id.state_text);
        recordingIndicator = findViewById(R.id.recording_indicator);
        statusText = (TextView) findViewById(R.id.status_text);

        cameraManager = new CameraManager();
        audioRecorder = new AudioRecorder(this);
        phoneConnection = new PhoneConnection(PHONE_HOST, this);

        surfaceView.getHolder().addCallback(this);
        acquireLocks();

        setState(State.IDLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioRecorder.release();
        phoneConnection.stop();
        releaseLocks();
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        cameraManager.start(holder);
        phoneConnection.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        cameraManager.stop();
    }

    // --- State Machine ---

    private void setState(State newState) {
        currentState = newState;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateUI();
            }
        });
    }

    private void updateUI() {
        switch (currentState) {
            case IDLE:
                stateText.setText(R.string.state_idle);
                stateText.setVisibility(View.VISIBLE);
                recordingIndicator.setVisibility(View.GONE);
                responseScroll.setVisibility(View.GONE);
                break;
            case RECORDING:
                stateText.setText(R.string.state_recording);
                stateText.setVisibility(View.VISIBLE);
                recordingIndicator.setVisibility(View.VISIBLE);
                responseScroll.setVisibility(View.GONE);
                break;
            case PROCESSING:
                stateText.setText(R.string.state_processing);
                stateText.setVisibility(View.VISIBLE);
                recordingIndicator.setVisibility(View.GONE);
                responseScroll.setVisibility(View.GONE);
                break;
            case RESPONSE:
                stateText.setText(R.string.state_response);
                stateText.setVisibility(View.VISIBLE);
                recordingIndicator.setVisibility(View.GONE);
                responseScroll.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void advanceState() {
        switch (currentState) {
            case IDLE:
            case RESPONSE:
                startCapture();
                break;
            case RECORDING:
                stopCapture();
                break;
            case PROCESSING:
                // Ignore taps while processing
                break;
        }
    }

    private void startCapture() {
        // Capture snapshot at the start of recording
        pendingSnapshot = cameraManager.captureSnapshot();
        setState(State.RECORDING);
        audioRecorder.start();
    }

    private void stopCapture() {
        audioRecorder.stop();
        // onRecordingComplete callback will handle sending
    }

    // --- AudioRecorder.Listener ---

    @Override
    public void onRecordingComplete(final byte[] wavData) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                sendQuery(wavData);
            }
        });
    }

    @Override
    public void onRecordingTooShort() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setState(State.IDLE);
            }
        });
    }

    @Override
    public void onRecordingError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "Recording error: " + message);
                setState(State.IDLE);
            }
        });
    }

    // --- Send query to phone ---

    private void sendQuery(byte[] wavData) {
        setState(State.PROCESSING);

        String imageBase64 = "";
        if (pendingSnapshot != null) {
            imageBase64 = Base64.encodeToString(pendingSnapshot, Base64.NO_WRAP);
        }
        String audioBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP);

        String json = "{\"type\":\"query\","
                + "\"image\":\"" + imageBase64 + "\","
                + "\"audio\":\"" + audioBase64 + "\","
                + "\"time\":" + System.currentTimeMillis() + "}";

        phoneConnection.send(json);
        pendingSnapshot = null;
    }

    // --- PhoneConnection.Listener ---

    @Override
    public void onConnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(R.string.status_connected);
                statusText.setTextColor(0xFF4CAF50);
            }
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(R.string.status_disconnected);
                statusText.setTextColor(0xFFFFAB40);
            }
        });
    }

    @Override
    public void onResponse(String json) {
        // Parse response JSON manually (same pattern as glass-clawd)
        final String type = extractJsonString(json, "type");
        final String text = extractJsonString(json, "text");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if ("error".equals(type)) {
                    Log.e(TAG, "Error from phone: " + text);
                    responseText.setText(text != null ? text : "Error");
                    setState(State.RESPONSE);
                } else if ("response".equals(type)) {
                    responseText.setText(text != null ? text : "");
                    setState(State.RESPONSE);
                    // Scroll to top
                    responseScroll.scrollTo(0, 0);
                }
            }
        });
    }

    // --- Input handling ---

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                long dt = System.currentTimeMillis() - touchStartTime;

                // Long press: exit
                if (dt > 800) {
                    finish();
                    return true;
                }

                // Swipe down: exit
                if (dy > 100 && Math.abs(dy) > Math.abs(dx)) {
                    finish();
                    return true;
                }

                // Swipe up on response: scroll
                if (currentState == State.RESPONSE && Math.abs(dy) > 50
                        && Math.abs(dy) > Math.abs(dx)) {
                    responseScroll.scrollBy(0, (int) (dy * 2));
                    return true;
                }

                // Tap: advance state
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    advanceState();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                advanceState();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- Utility ---

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx + search.length());
        if (idx < 0) return null;
        idx = json.indexOf("\"", idx + 1);
        if (idx < 0) return null;
        int start = idx + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassvision:wakelock");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glassvision:wifilock");
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
