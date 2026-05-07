package com.glassfollowwindow;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Polls dashd's /preview endpoint and hands the most-recent terminal_preview
 * line array to the listener. Same shape iOS Buddy renders.
 *
 * Single thread, configurable interval. Stop is best-effort: blocks until the
 * worker exits its current sleep+request cycle. Listener callbacks come from
 * the worker thread; caller hops to UI as needed.
 */
public class TerminalClient {

    public interface Listener {
        void onLines(List<String> lines);
        void onTerminalStatus(String message);
    }

    private static final String TAG = "GlassFollowTerm";

    private final String url;
    private final long pollMs;
    private final Listener listener;
    private Thread thread;
    private volatile boolean running;

    public TerminalClient(String url, long pollMs, Listener listener) {
        this.url = url;
        this.pollMs = pollMs;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(loop, "glass-follow-term");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            while (running) {
                try {
                    URL u = new URL(url);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestProperty("Accept", "application/json");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setUseCaches(false);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        listener.onTerminalStatus("term HTTP " + code);
                        c.disconnect();
                    } else {
                        StringBuilder sb = new StringBuilder();
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        r.close();
                        c.disconnect();
                        JSONObject obj = new JSONObject(sb.toString());
                        JSONArray arr = obj.optJSONArray("terminal_preview");
                        List<String> lines = new ArrayList<>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                lines.add(arr.optString(i, ""));
                            }
                        }
                        listener.onLines(lines);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "preview fetch: " + e.getMessage());
                    listener.onTerminalStatus("term: " + e.getMessage());
                }
                try { Thread.sleep(pollMs); } catch (InterruptedException ignored) {}
            }
        }
    };
}
