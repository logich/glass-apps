package com.glassvision.client;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Calls Anthropic Messages API via HttpURLConnection.
 * Supports image (vision) + audio content blocks.
 */
public class ClaudeClient {

    private static final String TAG = "ClaudeClient";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 120000;

    private static final String SYSTEM_PROMPT =
            "You are a vision assistant on Google Glass. Keep responses concise "
            + "(2-3 sentences) for the small heads-up display.";

    private final String apiKey;
    private final ConversationHistory history;

    public ClaudeClient(String apiKey, ConversationHistory history) {
        this.apiKey = apiKey;
        this.history = history;
    }

    /**
     * Send a query with image and audio to Claude.
     * @param imageBase64 JPEG image data (base64), may be empty
     * @param audioBase64 WAV audio data (base64), may be empty
     * @return Claude's text response
     * @throws IOException on network errors
     * @throws JSONException on JSON parse errors
     */
    public String query(String imageBase64, String audioBase64) throws IOException, JSONException {
        // Build the current user message with multimodal content
        JSONArray contentBlocks = new JSONArray();

        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JSONObject imageSource = new JSONObject();
            imageSource.put("type", "base64");
            imageSource.put("media_type", "image/jpeg");
            imageSource.put("data", imageBase64);

            JSONObject imageBlock = new JSONObject();
            imageBlock.put("type", "image");
            imageBlock.put("source", imageSource);
            contentBlocks.put(imageBlock);
        }

        if (audioBase64 != null && !audioBase64.isEmpty()) {
            JSONObject audioSource = new JSONObject();
            audioSource.put("type", "base64");
            audioSource.put("media_type", "audio/wav");
            audioSource.put("data", audioBase64);

            JSONObject audioBlock = new JSONObject();
            audioBlock.put("type", "input_audio");
            audioBlock.put("source", audioSource);
            contentBlocks.put(audioBlock);
        }

        // If no content blocks, add a text placeholder
        if (contentBlocks.length() == 0) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", "What do you see?");
            contentBlocks.put(textBlock);
        }

        // Build messages: history + current
        JSONArray messages = history.buildHistoryMessages();
        JSONObject currentUserMsg = new JSONObject();
        currentUserMsg.put("role", "user");
        currentUserMsg.put("content", contentBlocks);
        messages.put(currentUserMsg);

        // Build request body
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("system", SYSTEM_PROMPT);
        requestBody.put("messages", messages);

        // Make HTTP request
        String responseBody = doPost(requestBody.toString());

        // Parse response
        JSONObject response = new JSONObject(responseBody);
        StringBuilder assistantText = new StringBuilder();
        JSONArray content = response.getJSONArray("content");
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) {
                assistantText.append(block.getString("text"));
            }
        }

        String result = assistantText.toString();

        // Build a text summary of what was sent for history
        String userSummary = "[image + audio query]";
        history.addTurn(userSummary, result);

        return result;
    }

    private String doPost(String body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
            is.close();

            String responseBody = buf.toString("UTF-8");

            if (responseCode < 200 || responseCode >= 300) {
                Log.e(TAG, "API error " + responseCode + ": " + responseBody);
                throw new IOException("API error " + responseCode + ": " + responseBody);
            }

            return responseBody;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
