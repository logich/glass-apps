package com.glassvision;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Standalone audio recorder: 16kHz/mono/16-bit PCM -> WAV.
 * Extracted from glass-clawd's monolithic MainActivity.
 */
public class AudioRecorder {

    private static final String TAG = "AudioRecorder";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MAX_RECORD_SECONDS = 30;
    private static final int MIN_PCM_BYTES = 1600; // ~50ms

    public interface Listener {
        void onRecordingComplete(byte[] wavData);
        void onRecordingTooShort();
        void onRecordingError(String message);
    }

    private AudioRecord audioRecord;
    private volatile boolean recording;
    private Thread recordingThread;
    private ByteArrayOutputStream pcmBuffer;
    private final Listener listener;

    public AudioRecorder(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    @SuppressWarnings("MissingPermission")
    public void start() {
        if (recording) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: " + bufferSize);
            listener.onRecordingError("Mic error");
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create AudioRecord", e);
            listener.onRecordingError("Mic error");
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            audioRecord.release();
            audioRecord = null;
            listener.onRecordingError("Mic error");
            return;
        }

        pcmBuffer = new ByteArrayOutputStream();
        recording = true;
        audioRecord.startRecording();

        final int readBufSize = bufferSize;
        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[readBufSize];
                int maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS;
                int totalBytes = 0;

                while (recording && totalBytes < maxBytes) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmBuffer.write(buffer, 0, read);
                        totalBytes += read;
                    }
                }

                if (recording && totalBytes >= maxBytes) {
                    Log.d(TAG, "Max recording duration reached");
                    recording = false;
                    finishRecording();
                }
            }
        }, "AudioRecorder");
        recordingThread.start();
        Log.d(TAG, "Recording started");
    }

    public void stop() {
        if (!recording) return;
        recording = false;
        finishRecording();
    }

    private void finishRecording() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted waiting for recording thread", e);
            }
            recordingThread = null;
        }

        byte[] pcmData = pcmBuffer.toByteArray();
        pcmBuffer = null;

        if (pcmData.length < MIN_PCM_BYTES) {
            Log.d(TAG, "Recording too short (" + pcmData.length + " bytes)");
            listener.onRecordingTooShort();
            return;
        }

        Log.d(TAG, "Recording complete: " + pcmData.length + " bytes PCM");
        byte[] wavData = buildWav(pcmData);
        listener.onRecordingComplete(wavData);
    }

    private byte[] buildWav(byte[] pcmData) {
        int dataLen = pcmData.length;
        int fileLen = 36 + dataLen;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        try {
            DataOutputStream w = new DataOutputStream(out);
            w.writeBytes("RIFF");
            w.writeInt(Integer.reverseBytes(fileLen));
            w.writeBytes("WAVE");
            w.writeBytes("fmt ");
            w.writeInt(Integer.reverseBytes(16));
            w.writeShort(Short.reverseBytes((short) 1));   // PCM
            w.writeShort(Short.reverseBytes((short) 1));   // mono
            w.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            w.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2)); // byte rate
            w.writeShort(Short.reverseBytes((short) 2));   // block align
            w.writeShort(Short.reverseBytes((short) 16));  // bits per sample
            w.writeBytes("data");
            w.writeInt(Integer.reverseBytes(dataLen));
            w.write(pcmData);
            w.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error building WAV", e);
        }
        return out.toByteArray();
    }

    public void release() {
        recording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception e) { /* ignore */ }
            audioRecord.release();
            audioRecord = null;
        }
    }
}
