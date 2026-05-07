package com.glassfollowwindow;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Glass HUD with two modes:
 *
 *   WINDOW   — title / class / PID streamed from glass-followwindow /stream
 *   TERMINAL — terminal_preview lines polled from dashd /preview
 *
 * Touchpad tap (single-tap motion or KEYCODE_DPAD_CENTER / ENTER) toggles
 * between them. Default is WINDOW; the active mode is persisted across
 * launches. Only the active mode's client polls — switching modes stops the
 * other so we never waste capture cycles or terminal noise.
 *
 *   adb shell am start -n com.glassfollowwindow/.MainActivity \
 *     --es host 10.26.209.148 \
 *     --ei follow_port 8089 --ei dash_port 7778
 */
public class MainActivity extends Activity
        implements SseClient.Listener, TerminalClient.Listener {

    private static final String TAG = "GlassFollow";
    private static final String PREFS = "glass_followwindow";
    private static final String KEY_HOST = "host";
    private static final String KEY_FOLLOW_PORT = "follow_port";
    private static final String KEY_DASH_PORT = "dash_port";
    private static final String KEY_MODE = "mode";
    private static final int DEFAULT_FOLLOW_PORT = 8089;
    private static final int DEFAULT_DASH_PORT = 7778;
    private static final long TERM_POLL_MS = 2000;

    private static final int MODE_WINDOW = 0;
    private static final int MODE_TERMINAL = 1;
    private static final int MODE_COUNT = 2;

    private View windowLayout;
    private View terminalLayout;
    private TextView titleView;
    private TextView classView;
    private TextView statusView;
    private TextView terminalText;
    private TextView terminalStatus;

    private SseClient sseClient;
    private TerminalClient termClient;

    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int mode = MODE_WINDOW;
    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        windowLayout   = findViewById(R.id.window_layout);
        terminalLayout = findViewById(R.id.terminal_layout);
        titleView      = (TextView) findViewById(R.id.title);
        classView      = (TextView) findViewById(R.id.wm_class);
        statusView     = (TextView) findViewById(R.id.status);
        terminalText   = (TextView) findViewById(R.id.terminal_text);
        terminalStatus = (TextView) findViewById(R.id.terminal_status);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG);

        Intent intent = getIntent();
        String host = intent != null ? intent.getStringExtra("host") : null;
        int followPort = intent != null ? intent.getIntExtra("follow_port", -1) : -1;
        int dashPort   = intent != null ? intent.getIntExtra("dash_port", -1) : -1;

        if (TextUtils.isEmpty(host)) {
            host = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HOST, "localhost");
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, host).apply();
        }
        if (followPort <= 0) {
            followPort = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FOLLOW_PORT, DEFAULT_FOLLOW_PORT);
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FOLLOW_PORT, followPort).apply();
        }
        if (dashPort <= 0) {
            dashPort = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DASH_PORT, DEFAULT_DASH_PORT);
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_DASH_PORT, dashPort).apply();
        }
        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_MODE, MODE_WINDOW);
        // Old persisted modes from a 3-mode build can be out of range; clamp.
        if (mode < 0 || mode >= MODE_COUNT) mode = MODE_WINDOW;

        String streamUrl  = "http://" + host + ":" + followPort + "/stream";
        String previewUrl = "http://" + host + ":" + dashPort + "/preview";
        statusView.setText(streamUrl);
        terminalStatus.setText(previewUrl);

        sseClient  = new SseClient(streamUrl, this);
        termClient = new TerminalClient(previewUrl, TERM_POLL_MS, this);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Log.i(TAG, "tap → toggle");
                toggleMode();
                return true;
            }
        });

        applyMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        startActiveClient();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sseClient.stop();
        termClient.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.i(TAG, "onKeyDown code=" + keyCode);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE) {
            toggleMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gestures != null && gestures.onTouchEvent(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestures != null && gestures.onTouchEvent(event)) return true;
        return super.onTouchEvent(event);
    }

    private void toggleMode() {
        mode = (mode + 1) % MODE_COUNT;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply();
        applyMode();
        startActiveClient();
    }

    private void applyMode() {
        windowLayout.setVisibility  (mode == MODE_WINDOW   ? View.VISIBLE : View.GONE);
        terminalLayout.setVisibility(mode == MODE_TERMINAL ? View.VISIBLE : View.GONE);
    }

    /** Start the client matching the current mode and stop the other. The host
     * pays a tmux capture-pane per /preview tick, so we don't poll while the
     * user is on window mode. */
    private void startActiveClient() {
        if (mode == MODE_WINDOW) {
            termClient.stop();
            sseClient.start();
        } else {
            sseClient.stop();
            termClient.start();
        }
    }

    // ----- SseClient.Listener (window mode) -----

    @Override
    public void onEvent(final JSONObject evt) {
        ui.post(new Runnable() {
            public void run() {
                String title = evt.optString("title", "");
                String wmClass = evt.optString("wm_class", "");
                if (TextUtils.isEmpty(title)) title = getString(R.string.no_window);
                titleView.setText(title);
                classView.setText(wmClass);
                statusView.setText("live");
            }
        });
    }

    @Override
    public void onStatus(final String message) {
        Log.i(TAG, "follow: " + message);
        ui.post(new Runnable() {
            public void run() { statusView.setText(message); }
        });
    }

    // ----- TerminalClient.Listener (terminal mode) -----

    @Override
    public void onLines(final List<String> lines) {
        ui.post(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, lines.size() - 30);
                for (int i = start; i < lines.size(); i++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(lines.get(i));
                }
                terminalText.setText(sb.toString());
                terminalStatus.setText("term · " + lines.size() + " lines");
            }
        });
    }

    @Override
    public void onTerminalStatus(final String message) {
        Log.i(TAG, "term: " + message);
        ui.post(new Runnable() {
            public void run() { terminalStatus.setText(message); }
        });
    }
}
