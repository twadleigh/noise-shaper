package com.example.noise.loopplayer;

/**
 * Holds the interleaved float PCM loop handed over from JavaScript, in chunks, until the service takes it.
 * Kept in a static so the (large) buffer never has to travel through an Intent.
 */
final class LoopStore {
    private LoopStore() {}

    static float[] data;        // interleaved samples
    static int frames;
    static int channels;
    static int sampleRate;
    private static int written; // floats appended so far

    static synchronized void begin(int frames, int channels, int sampleRate) {
        LoopStore.frames = frames;
        LoopStore.channels = channels;
        LoopStore.sampleRate = sampleRate;
        data = new float[frames * channels];
        written = 0;
    }

    /** Appends little-endian float32 bytes; returns floats written so far. */
    static synchronized int append(byte[] bytes) {
        if (data == null) throw new IllegalStateException("begin() not called");
        int n = bytes.length / 4;
        if (written + n > data.length) throw new IllegalStateException("too much data");
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(data, written, n);
        written += n;
        return written;
    }

    static synchronized boolean complete() {
        return data != null && written == data.length;
    }

    static synchronized void clear() {
        data = null;
        written = 0;
    }
}
