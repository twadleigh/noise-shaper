package com.example.noise.loopplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Foreground service that loops a pre-rendered noise buffer. The loop is handed to a static-mode AudioTrack
 * (no app thread wakes up to feed it, so it is cheap on battery); if the device refuses a buffer that large,
 * a streaming track fed by a small thread is used instead. Also owns the sleep timer, so it works with the
 * screen off, where WebView timers may be throttled.
 */
public class LoopPlayerService extends Service {
    static final String ACTION_PLAY = "com.example.noise.loopplayer.PLAY";
    static final String ACTION_STOP = "com.example.noise.loopplayer.STOP";
    static final String EXTRA_GAIN = "gain";
    static final String EXTRA_SLEEP_SECONDS = "sleepSeconds";
    static final String EXTRA_CROSSFADE_MS = "crossfadeMs";

    // A channel's importance can't be changed after creation, so a new id is needed when it changes.
    private static final String CHANNEL_ID = "noise_playback_v2";
    private static final int NOTIFICATION_ID = 1;
    private static final long SLEEP_FADE_MS = 20000;

    interface Listener { void onStopped(); }

    /** The running service, or null. Only touched from the main thread. */
    static volatile LoopPlayerService instance;
    static volatile Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Voice current;
    private float gain = 1f;
    private boolean pausedForFocus = false;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private long sleepEndAt = 0;

    static boolean isRunning() { return instance != null && instance.current != null; }

    // ------------------------------------------------------------------ lifecycle

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Must call startForeground promptly after startForegroundService().
        startInForeground();
        if (intent == null) { stopAndNotify(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAndNotify();
        } else if (ACTION_PLAY.equals(action)) {
            gain = intent.getFloatExtra(EXTRA_GAIN, 1f);
            setSleep(intent.getIntExtra(EXTRA_SLEEP_SECONDS, 0));
            playLoop(intent.getIntExtra(EXTRA_CROSSFADE_MS, 0));
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(noisyReceiver); } catch (RuntimeException ignored) {}
        if (current != null) { current.release(); current = null; }
        abandonFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        LoopStore.clear();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ control (main thread)

    void setGain(float g) {
        gain = g;
        if (current != null && !pausedForFocus) current.rampTo(g, 50);
    }

    /** seconds <= 0 cancels. Fades out over the last 20 s (or the whole time if shorter), then stops. */
    void setSleep(int seconds) {
        main.removeCallbacks(sleepFade);
        main.removeCallbacks(sleepStop);
        if (seconds <= 0) { sleepEndAt = 0; return; }
        long ms = seconds * 1000L;
        sleepEndAt = System.currentTimeMillis() + ms;
        main.postDelayed(sleepFade, Math.max(0, ms - SLEEP_FADE_MS));
        main.postDelayed(sleepStop, ms);
    }

    private final Runnable sleepFade = () -> {
        if (current != null) current.rampTo(0f, (int) Math.min(SLEEP_FADE_MS, Math.max(1, sleepEndAt - System.currentTimeMillis())));
    };
    private final Runnable sleepStop = this::stopAndNotify;

    private void playLoop(int crossfadeMs) {
        if (!LoopStore.complete()) { stopAndNotify(); return; }
        if (!requestFocus()) { stopAndNotify(); return; }
        acquireWakeLock();
        Voice old = current;
        Voice next;
        try {
            next = new Voice(LoopStore.data, LoopStore.frames, LoopStore.channels, LoopStore.sampleRate);
        } catch (RuntimeException e) {
            stopAndNotify();
            return;
        }
        current = next;
        pausedForFocus = false;
        if (old != null && crossfadeMs > 0) {
            next.setVolume(0f);
            next.start();
            next.rampTo(gain, crossfadeMs);
            old.rampTo(0f, crossfadeMs);
            main.postDelayed(old::release, crossfadeMs + 100L);
        } else {
            if (old != null) old.release();
            next.setVolume(0f);
            next.start();
            next.rampTo(gain, 400);      // soft start
        }
    }

    void stopAndNotify() {
        main.removeCallbacks(sleepFade);
        main.removeCallbacks(sleepStop);
        Voice v = current;
        current = null;
        if (v != null) v.release();
        Listener l = listener;
        if (l != null) l.onStopped();
        stopForeground(true);
        stopSelf();
    }

    /** Fade out, then stop. Called by the plugin's stop(). */
    void fadeOutAndStop(int ms) {
        if (current == null) { stopAndNotify(); return; }
        main.removeCallbacks(sleepFade);
        main.removeCallbacks(sleepStop);
        current.rampTo(0f, ms);
        main.postDelayed(this::stopAndNotify, ms + 50L);
    }

    // ------------------------------------------------------------------ audio focus / noisy

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                stopAndNotify();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (current != null) { pausedForFocus = true; current.rampTo(0f, 150); }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (current != null && pausedForFocus) { pausedForFocus = false; current.rampTo(gain, 800); }
                break;
            default:
        }
    };

    private boolean requestFocus() {
        if (audioManager == null) return true;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener, main).build();
            r = audioManager.requestAudioFocus(focusRequest);
        } else {
            r = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stopAndNotify(); }   // headphones unplugged
    };

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noise:loop");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(12 * 60 * 60 * 1000L);   // safety cap: 12 h
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        // DEFAULT importance so the icon shows in the status bar; no sound or vibration.
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Noise playback", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void startInForeground() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, LoopPlayerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Noise")
                .setContentText("Playing — tap to open")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle())
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    // ------------------------------------------------------------------ one looping track

    /** A looping AudioTrack with a volume ramp. Static mode if possible, else a streaming writer thread. */
    private static final class Voice {
        private final AudioTrack track;
        private final Thread feeder;        // null in static mode
        private volatile boolean released;
        private final Handler h = new Handler(Looper.getMainLooper());
        private Runnable ramp;
        private float vol = 1f;

        Voice(float[] data, int frames, int channels, int sampleRate) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build();
            AudioTrack t = null;
            try {
                t = new AudioTrack(attrs, fmt, data.length * 4, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
                if (t.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("static init failed");
                if (t.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING) != data.length) throw new IllegalStateException("static write short");
                if (t.setLoopPoints(0, frames, -1) != AudioTrack.SUCCESS) throw new IllegalStateException("loop points");
            } catch (RuntimeException e) {
                if (t != null) t.release();
                t = null;
            }
            if (t != null) {
                track = t;
                feeder = null;
            } else {
                int min = AudioTrack.getMinBufferSize(sampleRate,
                        channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
                final int chunk = 8192 * channels;
                AudioTrack s = new AudioTrack(attrs, fmt, Math.max(min * 4, chunk * 4 * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                track = s;
                feeder = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    int pos = 0;
                    while (!released) {
                        int n = Math.min(chunk, data.length - pos);
                        int w = s.write(data, pos, n, AudioTrack.WRITE_BLOCKING);
                        if (w < 0) break;
                        pos = (pos + w) % data.length;
                    }
                }, "noise-feeder");
            }
        }

        void start() { track.play(); if (feeder != null) feeder.start(); }

        void setVolume(float v) { vol = v; track.setVolume(v); }

        /** Linear ramp of the track volume to target over ms. */
        void rampTo(float target, int ms) {
            if (released) return;
            if (ramp != null) h.removeCallbacks(ramp);
            final float from = vol;
            final long t0 = System.currentTimeMillis();
            final int dur = Math.max(1, ms);
            ramp = new Runnable() {
                @Override public void run() {
                    if (released) return;
                    float f = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                    setVolume(from + (target - from) * f);
                    if (f < 1f) h.postDelayed(this, 20);
                }
            };
            ramp.run();
        }

        void release() {
            if (released) return;
            released = true;
            h.removeCallbacksAndMessages(null);
            try { track.stop(); } catch (RuntimeException ignored) {}
            track.release();
        }
    }
}
