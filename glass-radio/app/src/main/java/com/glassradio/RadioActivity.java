package com.glassradio;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class RadioActivity extends Activity {

    private static final String TAG = "GlassRadio";

    // Gesture detection thresholds
    private static final float SWIPE_THRESHOLD = 100f;
    private static final long TAP_TIMEOUT_MS = 300;
    private static final long LONG_PRESS_THRESHOLD_MS = 500;

    private List<RadioStation> stations;
    private int selectedIndex = 0;
    private int playingIndex = -1;

    private HorizontalScrollView scrollView;
    private LinearLayout stationContainer;
    private TextView statusText;
    private TextView emptyText;

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    // Gesture state
    private float downX;
    private float downY;
    private long downTime;
    private int pointerCount;
    private boolean gestureHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_radio);

        scrollView = (HorizontalScrollView) findViewById(R.id.scroll_view);
        stationContainer = (LinearLayout) findViewById(R.id.station_container);
        statusText = (TextView) findViewById(R.id.status_text);
        emptyText = (TextView) findViewById(R.id.empty_text);

        stations = StationManager.loadStations();

        if (stations.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
        } else {
            populateStationCards();
            highlightSelected();
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassRadio::Playback");
    }

    private void populateStationCards() {
        stationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < stations.size(); i++) {
            RadioStation station = stations.get(i);
            View card = inflater.inflate(R.layout.item_station, stationContainer, false);

            TextView nameView = (TextView) card.findViewById(R.id.station_name);
            TextView genreView = (TextView) card.findViewById(R.id.station_genre);

            nameView.setText(station.name);
            genreView.setText(station.genre);

            card.setTag(i);
            stationContainer.addView(card);
        }
    }

    private void highlightSelected() {
        for (int i = 0; i < stationContainer.getChildCount(); i++) {
            View card = stationContainer.getChildAt(i);
            TextView indicator = (TextView) card.findViewById(R.id.playing_indicator);

            if (i == selectedIndex) {
                card.setBackgroundResource(R.drawable.bg_station_selected);
            } else {
                card.setBackgroundResource(R.drawable.bg_station_card);
            }

            if (i == playingIndex) {
                indicator.setVisibility(View.VISIBLE);
            } else {
                indicator.setVisibility(View.GONE);
            }
        }

        // Scroll to center the selected card
        View selected = stationContainer.getChildAt(selectedIndex);
        if (selected != null) {
            selected.post(new Runnable() {
                @Override
                public void run() {
                    View sel = stationContainer.getChildAt(selectedIndex);
                    if (sel != null) {
                        int scrollX = sel.getLeft() - (scrollView.getWidth() / 2) + (sel.getWidth() / 2);
                        scrollView.smoothScrollTo(scrollX, 0);
                    }
                }
            });
        }
    }

    // --- Navigation ---

    private void navigateNext() {
        if (stations.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % stations.size();
        highlightSelected();
    }

    private void navigatePrev() {
        if (stations.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + stations.size()) % stations.size();
        highlightSelected();
    }

    // --- Playback ---

    private void togglePlayback() {
        if (stations.isEmpty()) return;

        if (playingIndex == selectedIndex) {
            stopPlayback();
        } else {
            playStation(selectedIndex);
        }
    }

    private void playStation(int index) {
        stopPlayback();

        RadioStation station = stations.get(index);
        Log.d(TAG, "Playing: " + station.name + " (" + station.url + ")");

        statusText.setText(getString(R.string.status_buffering));
        statusText.setTextColor(getResources().getColor(R.color.status_stopped));

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.start();
                playingIndex = index;
                statusText.setText(getString(R.string.status_playing) + ": " + station.name);
                statusText.setTextColor(getResources().getColor(R.color.status_playing));
                highlightSelected();
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                stopPlayback();
                // Auto-reconnect after error
                statusText.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "Auto-reconnecting to " + station.name);
                        playStation(index);
                    }
                });
                return true;
            }
        });

        try {
            mediaPlayer.setDataSource(station.url);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to set data source", e);
            statusText.setText(getString(R.string.status_stopped));
            statusText.setTextColor(getResources().getColor(R.color.status_stopped));
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaPlayer", e);
            }
            mediaPlayer = null;
        }

        playingIndex = -1;
        statusText.setText(getString(R.string.status_stopped));
        statusText.setTextColor(getResources().getColor(R.color.status_stopped));
        highlightSelected();

        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // --- Gesture handling (inline, from GlassGestureHandler pattern) ---

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (handleMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleMotionEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean handleMotionEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                pointerCount = 1;
                gestureHandled = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                pointerCount = Math.max(pointerCount, event.getPointerCount());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!gestureHandled && (event.getEventTime() - downTime) > LONG_PRESS_THRESHOLD_MS) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < SWIPE_THRESHOLD) {
                        gestureHandled = true;
                        // Long press — reserved, no-op
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (gestureHandled) {
                    return true;
                }

                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long duration = event.getEventTime() - downTime;

                // Swipe detection
                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) {
                            navigateNext();
                        } else {
                            navigatePrev();
                        }
                    } else {
                        if (dy > 0) {
                            // Swipe down — stop + exit
                            stopPlayback();
                            finish();
                        }
                    }
                    return true;
                }

                // Tap
                if (duration < TAP_TIMEOUT_MS) {
                    togglePlayback();
                    return true;
                }

                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                navigateNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                navigatePrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePlayback();
                return true;
            case KeyEvent.KEYCODE_BACK:
                stopPlayback();
                finish();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // --- Lifecycle ---

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }
}
