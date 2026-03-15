package com.glassrsvp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * RSVP (Rapid Serial Visual Presentation) view.
 *
 * Displays words one at a time centered on screen with an anchor point
 * at the optimal recognition position (ORP) — typically 1/3 into the word.
 * The ORP letter is highlighted in a different color for fixation.
 */
public class RsvpView extends View {

    // Word display
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String currentWord = "";
    private int orpIndex = 0;

    // State
    private String statusText = "";
    private int wpm = 300;
    private int wordIndex = 0;
    private int totalWords = 0;
    private boolean paused = true;
    private boolean loading = false;
    private String loadingText = "Loading...";

    RsvpView(Context context) {
        super(context);

        wordPaint.setTextSize(80f);
        wordPaint.setColor(0xFFFFFFFF);
        wordPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        orpPaint.setTextSize(80f);
        orpPaint.setColor(0xFF00E5FF);  // Cyan accent for ORP letter
        orpPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        statusPaint.setTextSize(22f);
        statusPaint.setColor(0xFF808080);

        dimPaint.setTextSize(18f);
        dimPaint.setColor(0xFF505050);

        tickPaint.setColor(0xFF00E5FF);
        tickPaint.setStrokeWidth(3f);
    }

    void setLoading(String text) {
        loading = true;
        loadingText = text;
        invalidate();
    }

    void setWord(String word, int index, int total) {
        loading = false;
        currentWord = word;
        wordIndex = index;
        totalWords = total;
        orpIndex = calcOrpIndex(word);
        invalidate();
    }

    void setWpm(int wpm) {
        this.wpm = wpm;
        invalidate();
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        invalidate();
    }

    void setStatusText(String text) {
        this.statusText = text;
        invalidate();
    }

    /**
     * Calculate the Optimal Recognition Point index.
     * Based on word length — roughly 1/3 into the word, biased left.
     */
    private int calcOrpIndex(String word) {
        int len = word.length();
        if (len <= 1) return 0;
        if (len <= 3) return 0;
        if (len <= 5) return 1;
        if (len <= 9) return 2;
        return 3;
    }

    /**
     * Calculate display delay for a word in milliseconds.
     * Longer words and words with punctuation get extra time.
     */
    int calcWordDelay(String word) {
        int baseDelay = 60000 / wpm;
        int delay = baseDelay;

        // Extra time for long words
        if (word.length() > 8) {
            delay += baseDelay / 4;
        }

        // Extra time for punctuation (sentence endings)
        if (word.length() > 0) {
            char last = word.charAt(word.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                delay += baseDelay;  // Full extra beat for sentence end
            } else if (last == ',' || last == ';' || last == ':') {
                delay += baseDelay / 2;  // Half beat for clause break
            }
        }

        return delay;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        int w = getWidth();
        int h = getHeight();

        if (loading) {
            drawLoading(canvas, w, h);
            return;
        }

        if (currentWord.isEmpty()) return;

        // Draw the anchor tick mark at center
        float centerX = w / 2f;
        float topTickY = h / 2f - 50f;
        float botTickY = h / 2f + 40f;
        canvas.drawLine(centerX, topTickY - 10f, centerX, topTickY, tickPaint);
        canvas.drawLine(centerX, botTickY, centerX, botTickY + 10f, tickPaint);

        // Calculate ORP-anchored position
        // The ORP letter should be at centerX
        String before = currentWord.substring(0, orpIndex);
        String orpChar = currentWord.substring(orpIndex, orpIndex + 1);
        String after = currentWord.substring(orpIndex + 1);

        float beforeWidth = wordPaint.measureText(before);
        float orpCharWidth = orpPaint.measureText(orpChar);

        // Anchor ORP letter center at screen center
        float orpStartX = centerX - orpCharWidth / 2f;
        float beforeStartX = orpStartX - beforeWidth;

        Paint.FontMetrics fm = wordPaint.getFontMetrics();
        float textY = h / 2f - (fm.top + fm.bottom) / 2f;

        // Draw: before (white) + ORP letter (cyan) + after (white)
        if (before.length() > 0) {
            canvas.drawText(before, beforeStartX, textY, wordPaint);
        }
        canvas.drawText(orpChar, orpStartX, textY, orpPaint);
        if (after.length() > 0) {
            canvas.drawText(after, orpStartX + orpCharWidth, textY, wordPaint);
        }

        // Status bar — bottom
        drawStatus(canvas, w, h);
    }

    private void drawLoading(Canvas canvas, int w, int h) {
        float textWidth = statusPaint.measureText(loadingText);
        Paint.FontMetrics fm = statusPaint.getFontMetrics();
        float textY = h / 2f - (fm.top + fm.bottom) / 2f;
        statusPaint.setColor(0xFF808080);
        canvas.drawText(loadingText, (w - textWidth) / 2f, textY, statusPaint);
    }

    private void drawStatus(Canvas canvas, int w, int h) {
        float y = h - 8f;

        // Left: WPM
        String wpmText = wpm + " wpm";
        dimPaint.setColor(0xFF505050);
        canvas.drawText(wpmText, 12f, y, dimPaint);

        // Center: progress
        if (totalWords > 0) {
            int pct = (int) ((wordIndex * 100L) / totalWords);
            String progress = pct + "%";
            float pw = dimPaint.measureText(progress);
            canvas.drawText(progress, (w - pw) / 2f, y, dimPaint);
        }

        // Right: state
        String state = paused ? "PAUSED" : "READING";
        float sw = dimPaint.measureText(state);
        dimPaint.setColor(paused ? 0xFF505050 : 0xFF00E5FF);
        canvas.drawText(state, w - sw - 12f, y, dimPaint);
    }
}
