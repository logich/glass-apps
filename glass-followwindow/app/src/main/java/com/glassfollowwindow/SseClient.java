package com.glassfollowwindow;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal Server-Sent Events client for glass-followwindow's /stream.
 *
 * Single background thread, exponential backoff on connection failure.
 * Hands every parsed JSON event to the listener (always called off the UI
 * thread; caller is expected to hop back to the main looper).
 */
public class SseClient {

    public interface Listener {
        void onEvent(JSONObject evt);
        void onStatus(String message);
    }

    private static final String TAG = "GlassFollowSSE";

    private final String url;
    private final Listener listener;
    private Thread thread;
    private volatile boolean running;
    private volatile HttpURLConnection conn;

    public SseClient(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(loop, "glass-follow-sse");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (conn != null) conn.disconnect();
        } catch (Exception ignored) {}
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            long backoffMs = 1000;
            while (running) {
                try {
                    listener.onStatus("connecting");
                    URL u = new URL(url);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    conn = c;
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(0); // long-lived stream
                    c.setUseCaches(false);
                    int status = c.getResponseCode();
                    if (status != 200) {
                        listener.onStatus("HTTP " + status);
                        c.disconnect();
                        sleep(backoffMs);
                        backoffMs = Math.min(backoffMs * 2, 15000);
                        continue;
                    }
                    listener.onStatus("connected");
                    backoffMs = 1000;

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder dataBuf = new StringBuilder();
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        if (line.length() == 0) {
                            // event boundary
                            if (dataBuf.length() > 0) {
                                dispatch(dataBuf.toString());
                                dataBuf.setLength(0);
                            }
                            continue;
                        }
                        if (line.startsWith("data: ")) {
                            if (dataBuf.length() > 0) dataBuf.append('\n');
                            dataBuf.append(line.substring(6));
                        } else if (line.startsWith("data:")) {
                            if (dataBuf.length() > 0) dataBuf.append('\n');
                            dataBuf.append(line.substring(5));
                        }
                        // ignore comments (": keepalive") and other fields
                    }
                    listener.onStatus("disconnected");
                } catch (Exception e) {
                    Log.w(TAG, "stream error: " + e.getMessage());
                    listener.onStatus("retry " + (backoffMs / 1000) + "s");
                } finally {
                    try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                    conn = null;
                }
                if (!running) break;
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 15000);
            }
        }
    };

    private void dispatch(String payload) {
        try {
            JSONObject obj = new JSONObject(payload);
            listener.onEvent(obj);
        } catch (JSONException e) {
            Log.w(TAG, "bad json: " + payload);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
