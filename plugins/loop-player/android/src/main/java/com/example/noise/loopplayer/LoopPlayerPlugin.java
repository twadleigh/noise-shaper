package com.example.noise.loopplayer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * JS API (window.Capacitor.Plugins.LoopPlayer):
 *   begin({frames, channels, sampleRate})          start receiving a new loop
 *   append({data})                                 base64 little-endian float32, interleaved; call repeatedly
 *   play({gain, sleepSeconds, crossfadeMs})        start (or crossfade to) the received loop
 *   setGain({gain}) / setSleep({seconds})
 *   stop({fadeMs})
 *   isPlaying() -> {playing}
 * Event: "stopped" (sleep timer ended, notification Stop pressed, audio focus lost, headphones unplugged).
 */
@CapacitorPlugin(
        name = "LoopPlayer",
        permissions = @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
)
public class LoopPlayerPlugin extends Plugin {

    @Override public void load() {
        LoopPlayerService.listener = () -> notifyListeners("stopped", new JSObject());
    }

    @Override protected void handleOnDestroy() {
        LoopPlayerService.listener = null;
    }

    @PluginMethod
    public void begin(PluginCall call) {
        Integer frames = call.getInt("frames"), channels = call.getInt("channels"), rate = call.getInt("sampleRate");
        if (frames == null || channels == null || rate == null || channels < 1 || channels > 2 || frames < 1) {
            call.reject("frames, channels (1-2) and sampleRate are required");
            return;
        }
        try {
            LoopStore.begin(frames, channels, rate);
            call.resolve();
        } catch (OutOfMemoryError e) {
            call.reject("out of memory allocating loop buffer");
        }
    }

    @PluginMethod
    public void append(PluginCall call) {
        String b64 = call.getString("data");
        if (b64 == null) { call.reject("data required"); return; }
        try {
            LoopStore.append(Base64.decode(b64, Base64.DEFAULT));
            call.resolve();
        } catch (RuntimeException e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Ask for the notification permission up front (Android 13+), so the playback notification can show. */
    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notifRequestResult");
            return;
        }
        JSObject r = new JSObject();
        r.put("granted", true);
        call.resolve(r);
    }

    @PermissionCallback
    private void notifRequestResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void play(PluginCall call) {
        if (!LoopStore.complete()) { call.reject("loop buffer incomplete"); return; }
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notifPermResult");   // proceeds either way
            return;
        }
        doPlay(call);
    }

    @PermissionCallback
    private void notifPermResult(PluginCall call) {
        doPlay(call);   // playback works without the notification; it just won't be visible
    }

    private void doPlay(PluginCall call) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, LoopPlayerService.class)
                .setAction(LoopPlayerService.ACTION_PLAY)
                .putExtra(LoopPlayerService.EXTRA_GAIN, call.getFloat("gain", 1f))
                .putExtra(LoopPlayerService.EXTRA_SLEEP_SECONDS, call.getInt("sleepSeconds", 0))
                .putExtra(LoopPlayerService.EXTRA_CROSSFADE_MS, call.getInt("crossfadeMs", 0));
        try {
            ContextCompat.startForegroundService(ctx, i);
            call.resolve();
        } catch (RuntimeException e) {
            call.reject("could not start service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setGain(PluginCall call) {
        LoopPlayerService s = LoopPlayerService.instance;
        if (s != null) getBridge().executeOnMainThread(() -> s.setGain(call.getFloat("gain", 1f)));
        call.resolve();
    }

    @PluginMethod
    public void setSleep(PluginCall call) {
        LoopPlayerService s = LoopPlayerService.instance;
        if (s != null) getBridge().executeOnMainThread(() -> s.setSleep(call.getInt("seconds", 0)));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        LoopPlayerService s = LoopPlayerService.instance;
        if (s != null) getBridge().executeOnMainThread(() -> s.fadeOutAndStop(call.getInt("fadeMs", 400)));
        call.resolve();
    }

    @PluginMethod
    public void isPlaying(PluginCall call) {
        JSObject r = new JSObject();
        r.put("playing", LoopPlayerService.isRunning());
        call.resolve(r);
    }
}
