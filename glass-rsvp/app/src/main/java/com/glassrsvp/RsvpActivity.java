package com.glassrsvp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RSVP speed reading activity.
 *
 * Controls:
 *   Tap          — pause / resume
 *   Swipe fwd    — increase WPM (+50)
 *   Swipe back   — decrease WPM (-50)
 *   Swipe down   — exit
 *   Long press   — restart from beginning
 */
public class RsvpActivity extends Activity {

    private static final int WPM_MIN = 100;
    private static final int WPM_MAX = 1000;
    private static final int WPM_STEP = 50;
    private static final int WPM_DEFAULT = 300;

    private RsvpView rsvpView;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private String[] words;
    private int wordIndex = 0;
    private int wpm = WPM_DEFAULT;
    private boolean paused = true;
    private boolean loaded = false;

    private String filename = "";

    // Gesture tracking
    private float downX, downY;
    private long downTime;
    private boolean longPressHandled = false;
    private static final float SWIPE_THRESHOLD = 100f;
    private static final long LONG_PRESS_MS = 600;

    private final Runnable advanceRunnable = new Runnable() {
        @Override
        public void run() {
            if (paused || !loaded || words == null) return;

            if (wordIndex >= words.length) {
                paused = true;
                rsvpView.setPaused(true);
                return;
            }

            String word = words[wordIndex];
            rsvpView.setWord(word, wordIndex, words.length);
            wordIndex++;

            int delay = rsvpView.calcWordDelay(word);
            handler.postDelayed(advanceRunnable, delay);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler(Looper.getMainLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glassrsvp:reading");

        rsvpView = new RsvpView(this);
        setContentView(rsvpView);

        String path = getIntent().getStringExtra("pdf_path");
        if (path == null) {
            finish();
            return;
        }

        File file = new File(path);
        filename = file.getName();

        // Restore state
        SharedPreferences prefs = getPrefs();
        String key = sanitize(filename);
        wpm = prefs.getInt(key + "_wpm", WPM_DEFAULT);
        wordIndex = prefs.getInt(key + "_pos", 0);
        rsvpView.setWpm(wpm);

        rsvpView.setLoading("Loading...");

        if (filename.toLowerCase().endsWith(".txt")) {
            loadTextFile(file);
        } else {
            PdfTextExtractor.extract(this, file, new PdfTextExtractor.Callback() {
                @Override
                public void onSuccess(String text) {
                    onTextLoaded(text);
                }

                @Override
                public void onError(String message) {
                    rsvpView.setLoading("Error: " + message);
                }

                @Override
                public void onProgress(int page, int totalPages) {
                    rsvpView.setLoading("Extracting " + page + "/" + totalPages + "...");
            }
        });
        }
    }

    private void onTextLoaded(String text) {
        words = tokenize(text);
        loaded = true;

        if (wordIndex >= words.length) {
            wordIndex = 0;
        }

        if (words.length == 0) {
            rsvpView.setLoading("No text found");
            return;
        }

        rsvpView.setWord(words[wordIndex], wordIndex, words.length);
        rsvpView.setPaused(true);
    }

    private void loadTextFile(final File file) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.FileReader(file));
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[8192];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        sb.append(buf, 0, n);
                    }
                    reader.close();
                    final String text = sb.toString();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onTextLoaded(text);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            rsvpView.setLoading("Error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Tokenize extracted text into words, stripping empty tokens.
     */
    private String[] tokenize(String text) {
        String[] raw = text.split("\\s+");
        List<String> cleaned = new ArrayList<>();
        for (String w : raw) {
            String trimmed = w.trim();
            if (trimmed.length() > 0) {
                cleaned.add(trimmed);
            }
        }
        return cleaned.toArray(new String[0]);
    }

    private void togglePause() {
        if (!loaded || words == null || words.length == 0) return;

        paused = !paused;
        rsvpView.setPaused(paused);

        if (!paused) {
            // If at end, restart
            if (wordIndex >= words.length) {
                wordIndex = 0;
            }
            handler.post(advanceRunnable);
        } else {
            handler.removeCallbacks(advanceRunnable);
        }
    }

    private void adjustWpm(int delta) {
        wpm = Math.max(WPM_MIN, Math.min(WPM_MAX, wpm + delta));
        rsvpView.setWpm(wpm);
    }

    private void restart() {
        handler.removeCallbacks(advanceRunnable);
        wordIndex = 0;
        paused = true;
        if (words != null && words.length > 0) {
            rsvpView.setWord(words[0], 0, words.length);
        }
        rsvpView.setPaused(true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                longPressHandled = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!longPressHandled && System.currentTimeMillis() - downTime > LONG_PRESS_MS) {
                    longPressHandled = true;
                    restart();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (longPressHandled) return true;

                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx < 0) {
                            adjustWpm(WPM_STEP);
                        } else {
                            adjustWpm(-WPM_STEP);
                        }
                    } else if (dy > 0) {
                        finish();
                    }
                } else {
                    long elapsed = System.currentTimeMillis() - downTime;
                    if (elapsed < 300) {
                        togglePause();
                    }
                }
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_SPACE:
                    togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    adjustWpm(WPM_STEP);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    adjustWpm(-WPM_STEP);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    adjustWpm(WPM_STEP);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    adjustWpm(-WPM_STEP);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(advanceRunnable);
        paused = true;
        saveState();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(advanceRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void saveState() {
        if (!loaded) return;
        SharedPreferences.Editor editor = getPrefs().edit();
        String key = sanitize(filename);
        editor.putInt(key + "_wpm", wpm);
        editor.putInt(key + "_pos", wordIndex);
        editor.apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("glass_rsvp_state", MODE_PRIVATE);
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
