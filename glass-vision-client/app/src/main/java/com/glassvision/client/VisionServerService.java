package com.glassvision.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Foreground service running TCP server on port 9877.
 * Accepts connections from Glass, receives query messages (image+audio),
 * calls Claude API, sends back responses.
 */
public class VisionServerService extends Service {

    private static final String TAG = "VisionServer";
    private static final String CHANNEL_ID = "glass_vision_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9877;
    private static final int MAX_MESSAGE_SIZE = 2 * 1024 * 1024; // 2MB

    public static final String ACTION_STATUS = "com.glassvision.client.STATUS";
    public static final String ACTION_LOG = "com.glassvision.client.LOG";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_LOG = "log";
    public static final String EXTRA_API_KEY = "api_key";

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String apiKey;
    private ConversationHistory conversationHistory;
    private ClaudeClient claudeClient;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        conversationHistory = new ConversationHistory();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            apiKey = intent.getStringExtra(EXTRA_API_KEY);
        }

        if (apiKey == null || apiKey.isEmpty()) {
            broadcastStatus("No API key");
            stopSelf();
            return START_NOT_STICKY;
        }

        claudeClient = new ClaudeClient(apiKey, conversationHistory);

        Notification notification = buildForegroundNotification("Starting server...");
        startForeground(NOTIFICATION_ID, notification);

        running = true;
        serverThread = new Thread(this::serverLoop, "VisionServer");
        serverThread.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        broadcastStatus("Server stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void clearHistory() {
        if (conversationHistory != null) {
            conversationHistory.clear();
            broadcastLog("Conversation history cleared");
        }
    }

    // Accessible from MainActivity via static reference
    static VisionServerService instance;

    private void serverLoop() {
        instance = this;
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            broadcastStatus("Listening on port " + PORT);
            updateForegroundNotification("Listening on port " + PORT);
            broadcastLog("Server started on port " + PORT);

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (SocketException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server error", e);
            broadcastStatus("Server error: " + e.getMessage());
        } finally {
            instance = null;
        }
    }

    private void handleClient(Socket client) {
        String addr = client.getInetAddress().getHostAddress();
        broadcastStatus("Glass connected: " + addr);
        updateForegroundNotification("Glass connected: " + addr);
        broadcastLog("Client connected: " + addr);

        try {
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            while (running && !client.isClosed()) {
                int length = in.readInt();
                if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                    Log.w(TAG, "Invalid message length: " + length);
                    break;
                }

                byte[] data = new byte[length];
                in.readFully(data);
                String json = new String(data, "UTF-8");

                try {
                    JSONObject msg = new JSONObject(json);
                    String type = msg.optString("type", "");

                    if ("heartbeat".equals(type)) {
                        continue;
                    }

                    if ("query".equals(type)) {
                        String imageBase64 = msg.optString("image", "");
                        String audioBase64 = msg.optString("audio", "");
                        long queryTime = msg.optLong("time", 0);

                        broadcastLog("Query received (image: "
                                + (imageBase64.isEmpty() ? "no" : imageBase64.length() + " chars")
                                + ", audio: "
                                + (audioBase64.isEmpty() ? "no" : audioBase64.length() + " chars")
                                + ")");

                        try {
                            String answer = claudeClient.query(imageBase64, audioBase64);
                            broadcastLog("Response: " + truncate(answer, 80));

                            JSONObject response = new JSONObject();
                            response.put("type", "response");
                            response.put("text", answer);
                            response.put("time", queryTime);
                            sendMessage(out, response.toString());

                        } catch (Exception e) {
                            Log.e(TAG, "Claude API error", e);
                            broadcastLog("Error: " + e.getMessage());

                            JSONObject error = new JSONObject();
                            error.put("type", "error");
                            error.put("text", e.getMessage());
                            error.put("time", queryTime);
                            sendMessage(out, error.toString());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error processing message", e);
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "Client disconnected: " + e.getMessage());
            }
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            broadcastStatus("Glass disconnected");
            updateForegroundNotification("Listening on port " + PORT);
            broadcastLog("Client disconnected: " + addr);
        }
    }

    private void sendMessage(DataOutputStream out, String json) throws IOException {
        byte[] data = json.getBytes("UTF-8");
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastLog(String message) {
        Intent intent = new Intent(ACTION_LOG);
        intent.putExtra(EXTRA_LOG, message);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildForegroundNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Glass Vision")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundNotification(String text) {
        Notification notification = buildForegroundNotification(text);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
