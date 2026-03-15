package com.glassvision.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation history for Claude API calls.
 * Stores last 10 turns (user text + assistant text).
 * Past turns are text-only — no base64 images to save memory.
 */
public class ConversationHistory {

    private static final int MAX_TURNS = 10;

    private static class Turn {
        final String userText;
        final String assistantText;

        Turn(String userText, String assistantText) {
            this.userText = userText;
            this.assistantText = assistantText;
        }
    }

    private final List<Turn> turns = new ArrayList<>();

    /** Add a completed turn to history. */
    public synchronized void addTurn(String userText, String assistantText) {
        turns.add(new Turn(userText, assistantText));
        while (turns.size() > MAX_TURNS) {
            turns.remove(0);
        }
    }

    /**
     * Build the messages array for the Claude API request.
     * Past turns are text-only. The current multimodal content is NOT included here —
     * the caller appends it separately.
     */
    public synchronized JSONArray buildHistoryMessages() throws JSONException {
        JSONArray messages = new JSONArray();
        for (Turn turn : turns) {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", turn.userText);
            messages.put(userMsg);

            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", turn.assistantText);
            messages.put(assistantMsg);
        }
        return messages;
    }

    public synchronized void clear() {
        turns.clear();
    }

    public synchronized int size() {
        return turns.size();
    }
}
