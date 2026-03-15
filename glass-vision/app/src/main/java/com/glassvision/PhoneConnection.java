package com.glassvision;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP client connecting to the phone's VisionServerService.
 * Bidirectional: sends queries (image+audio), reads responses.
 * Same length-prefixed JSON wire format as glass-notify (4 bytes big-endian + UTF-8 JSON).
 * Adapted from glass-notify-client NotificationForwardService.
 */
public class PhoneConnection {

    private static final String TAG = "PhoneConnection";
    private static final int PORT = 9877;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final int MAX_MESSAGE_SIZE = 2 * 1024 * 1024; // 2MB

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onResponse(String json);
    }

    private final String host;
    private final Listener listener;
    private final LinkedBlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread writerThread;
    private Thread readerThread;

    public PhoneConnection(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    public void start() {
        running = true;
        writerThread = new Thread(this::connectionLoop, "PhoneConnection-Writer");
        writerThread.start();
    }

    public void stop() {
        running = false;
        if (writerThread != null) writerThread.interrupt();
        if (readerThread != null) readerThread.interrupt();
    }

    /** Enqueue a JSON message for sending. */
    public void send(String json) {
        try {
            byte[] data = json.getBytes("UTF-8");
            sendQueue.offer(data);
        } catch (IOException e) {
            Log.e(TAG, "Error encoding message", e);
        }
    }

    private void connectionLoop() {
        long backoff = 1000;

        while (running) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);

                backoff = 1000;
                listener.onConnected();

                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                final DataInputStream in = new DataInputStream(socket.getInputStream());

                // Start reader thread for incoming responses
                final Socket finalSocket = socket;
                readerThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        readLoop(in, finalSocket);
                    }
                }, "PhoneConnection-Reader");
                readerThread.start();

                // Writer loop: dequeue and send messages + heartbeats
                long lastSend = System.currentTimeMillis();
                while (running && !socket.isClosed()) {
                    byte[] data = sendQueue.poll(1, TimeUnit.SECONDS);

                    if (data != null) {
                        out.writeInt(data.length);
                        out.write(data);
                        out.flush();
                        lastSend = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - lastSend > HEARTBEAT_INTERVAL_MS) {
                        // Send heartbeat (empty JSON object)
                        byte[] heartbeat = "{\"type\":\"heartbeat\"}".getBytes("UTF-8");
                        out.writeInt(heartbeat.length);
                        out.write(heartbeat);
                        out.flush();
                        lastSend = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                Log.w(TAG, "Connection error: " + e.getMessage());
            } finally {
                if (readerThread != null) readerThread.interrupt();
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }

            if (!running) break;

            listener.onDisconnected();

            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private void readLoop(DataInputStream in, Socket socket) {
        try {
            while (running && !socket.isClosed()) {
                int length = in.readInt();
                if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                    Log.w(TAG, "Invalid message length: " + length);
                    break;
                }

                byte[] data = new byte[length];
                in.readFully(data);
                String json = new String(data, "UTF-8");
                listener.onResponse(json);
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "Read error: " + e.getMessage());
            }
        }
    }
}
