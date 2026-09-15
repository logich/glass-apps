package com.glassstocks;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

    private static final String TAG = "Stocks";
    private static final long CYCLE_INTERVAL_MS = 10000;
    // SharpCharts data is delayed, and a sweep is one request per ticker, so
    // there is nothing to gain from refreshing faster than this.
    private static final long REFRESH_INTERVAL_MS = 900000;
    private static final String BASE_URL = "https://stockcharts.com/c-sc/sc";
    // StockCharts answers the default Java User-Agent with a 404 rather than a
    // 403, so an unset UA looks exactly like a dead URL.
    private static final String USER_AGENT =
        "Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/124.0 Safari/537.36";
    // An unknown symbol comes back as HTTP 200 with a ~2.4 KB "invalid symbol"
    // placeholder; a real chart is ~20 KB. Size is the only way to tell.
    private static final int MIN_BYTES = 5000;
    // Optional one-symbol-per-line override, pushed with:
    //   adb push tickers.txt /sdcard/glass-stocks-tickers.txt
    private static final String TICKER_FILE = "/sdcard/glass-stocks-tickers.txt";
    private static final String[] DEFAULT_TICKERS = {
        "$SPX", "$COMPQ", "$VIX", "SPY", "QQQ", "IWM", "TLT",
        "GLD", "USO", "XLE", "XLF", "XLK", "NVDA", "AAPL"
    };

    private ImageView chartImage;
    private TextView counterText;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private String[] tickers = DEFAULT_TICKERS;
    private Bitmap[] bitmaps = new Bitmap[DEFAULT_TICKERS.length];
    private int currentIndex = 0;
    private boolean running = false;

    private static final int ZOOM_FIT = 0;
    private static final int ZOOM_FILL = 1;
    private static final int ZOOM_CLOSE = 2;
    private int zoomLevel = ZOOM_FIT;
    private float touchDownX, touchDownY;
    private long touchDownTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocks);

        chartImage = (ImageView) findViewById(R.id.chart_image);
        counterText = (TextView) findViewById(R.id.counter_text);
        handler = new Handler(Looper.getMainLooper());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG + ":wl");

        tickers = loadTickers();
        bitmaps = new Bitmap[tickers.length];

        fetchAllImages();
    }

    private String[] loadTickers() {
        File file = new File(TICKER_FILE);
        if (!file.canRead()) {
            return DEFAULT_TICKERS;
        }
        ArrayList<String> found = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    found.add(line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ticker file unreadable, using defaults", e);
            return DEFAULT_TICKERS;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { }
            }
        }
        if (found.isEmpty()) {
            return DEFAULT_TICKERS;
        }
        Log.d(TAG, "Loaded " + found.size() + " tickers from " + TICKER_FILE);
        return found.toArray(new String[found.size()]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        wakeLock.acquire();
        startSlideshow();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void fetchAllImages() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TrustManager[] trustAll = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String type) {}
                            public void checkServerTrusted(X509Certificate[] certs, String type) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAll, new java.security.SecureRandom());

                    long timestamp = System.currentTimeMillis();
                    for (int i = 0; i < tickers.length; i++) {
                        // r= busts StockCharts' cache; without it a refresh can
                        // hand back the same image for hours.
                        String urlStr = BASE_URL
                            + "?s=" + URLEncoder.encode(tickers[i], "UTF-8")
                            + "&p=D&b=5&g=0&i=0&r=" + timestamp;
                        URL url = new URL(urlStr);
                        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                        conn.setSSLSocketFactory(sc.getSocketFactory());
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);

                        try {
                            byte[] body = readBody(conn.getInputStream());
                            if (body.length < MIN_BYTES) {
                                // Keep whatever chart this slot already holds.
                                Log.w(TAG, "Rejected " + tickers[i] + " (" + body.length
                                    + "B, looks like an error image)");
                                continue;
                            }
                            Bitmap bmp = BitmapFactory.decodeByteArray(body, 0, body.length);
                            if (bmp != null) {
                                bitmaps[i] = bmp;
                                Log.d(TAG, "Fetched " + tickers[i]);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Fetch failed: " + tickers[i], e);
                        } finally {
                            conn.disconnect();
                        }
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showCurrentImage();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Image fetch failed", e);
                }
            }
        }).start();
    }

    private static byte[] readBody(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        try {
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private void showCurrentImage() {
        Bitmap bmp = bitmaps[currentIndex];
        if (bmp != null) {
            chartImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            chartImage.setImageBitmap(bmp);
            zoomLevel = ZOOM_FIT;
        }
        counterText.setText(tickers[currentIndex] + "   "
            + (currentIndex + 1) + " / " + tickers.length);
    }

    private void advanceSlideshow() {
        currentIndex = (currentIndex + 1) % tickers.length;
        showCurrentImage();
    }

    private void previousImage() {
        currentIndex = (currentIndex - 1 + tickers.length) % tickers.length;
        showCurrentImage();
    }

    private void nextImage() {
        currentIndex = (currentIndex + 1) % tickers.length;
        showCurrentImage();
    }

    private final Runnable slideshowRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            advanceSlideshow();
            handler.postDelayed(this, CYCLE_INTERVAL_MS);
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            fetchAllImages();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void startSlideshow() {
        handler.removeCallbacks(slideshowRunnable);
        handler.postDelayed(slideshowRunnable, CYCLE_INTERVAL_MS);
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                long dt = System.currentTimeMillis() - touchDownTime;

                if (Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                    finish();
                    return true;
                }
                if (dt > 800) {
                    finish();
                    return true;
                }
                if (Math.abs(dx) > 50) {
                    // Swipe left/right
                    if (dx > 0) {
                        previousImage();
                    } else {
                        nextImage();
                    }
                    // Reset slideshow timer after manual navigation
                    startSlideshow();
                    return true;
                }
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap = toggle zoom
                    toggleZoom();
                    return true;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void toggleZoom() {
        zoomLevel = (zoomLevel + 1) % 3;
        applyZoom();
    }

    private void applyZoom() {
        switch (zoomLevel) {
            case ZOOM_FIT:
                chartImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                startSlideshow();
                break;
            case ZOOM_FILL:
                chartImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                handler.removeCallbacks(slideshowRunnable);
                break;
            case ZOOM_CLOSE:
                // 2.5x zoom anchored top-left to read the symbol
                Bitmap bmp = bitmaps[currentIndex];
                if (bmp != null) {
                    float scale = 2.5f;
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    chartImage.setScaleType(ImageView.ScaleType.MATRIX);
                    chartImage.setImageMatrix(m);
                }
                handler.removeCallbacks(slideshowRunnable);
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
