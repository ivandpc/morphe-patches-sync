package app.morphe.extension.music.sync;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.OkHttpClient;

/**
 * Spotify Connect-style sync client. Hooked into the existing
 * VideoInformation pipeline by PlaybackSyncPatch (video-id + ~1s time hooks).
 *
 * V1: pushes state; applies incoming seek + playVideo (track switch) commands.
 * Play/pause remote control needs a player-controller hook (TODO).
 */
public class PlaybackSync {
    private static final String TAG = "MorpheSync";
    private static final long PUSH_THROTTLE_MS = 2000;

    private static WebSocket ws;
    private static OkHttpClient client;
    private static final String deviceId = "android-" + UUID.randomUUID().toString().substring(0, 8);
    private static long lastPush = 0;
    private static boolean applyingRemote = false;
    private static String lastVideoId = "";

    /** Injection point: called with the new video id on track change. */
    public static void onVideoId(String videoId) {
        lastVideoId = videoId;
        ensureConnected();
        pushState(true);
    }

    /** Injection point: called ~every 1000ms with current position (ms). */
    public static void onVideoTime(long positionMs) {
        pushState(false);
    }

    private static boolean isEnabled() {
        try {
            return Settings.PLAYBACK_SYNC_ENABLED.get();
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureConnected() {
        if (ws != null || !isEnabled()) return;
        try {
            String url = Settings.PLAYBACK_SYNC_SERVER_URL.get();
            String room = Settings.PLAYBACK_SYNC_ROOM.get();
            if (url == null || url.isEmpty() || room == null || room.isEmpty()) {
                Log.w(TAG, "sync not configured (url/room empty)");
                return;
            }
            String name = "Android " + android.os.Build.MODEL;
            client = new OkHttpClient();
            Request req = new Request.Builder().url(url.replace("http", "ws")).build();
            final String fRoom = room, fName = name;
            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response r) {
                    try {
                        w.send(new JSONObject().put("type", "hello").put("room", fRoom)
                                .put("deviceId", deviceId).put("name", fName).toString());
                    } catch (Exception e) { Log.e(TAG, "hello", e); }
                }
                @Override public void onMessage(WebSocket w, String text) {
                    handleMessage(text);
                }
                @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                    Log.e(TAG, "ws failure", t);
                    ws = null;
                }
                @Override public void onClosed(WebSocket w, int code, String reason) {
                    ws = null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "connect", e);
            ws = null;
        }
    }

    private static void pushState(boolean force) {
        if (!isEnabled() || applyingRemote) return;
        ensureConnected();
        if (ws == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPush < PUSH_THROTTLE_MS) return;
        lastPush = now;
        try {
            String videoId = VideoInformation.getVideoId();
            long position = VideoInformation.getVideoTime();
            long duration = VideoInformation.getVideoLength();
            JSONObject state = new JSONObject()
                    .put("videoId", videoId)
                    .put("position", position / 1000.0)
                    .put("duration", duration / 1000.0)
                    .put("playing", position >= 0)
                    .put("updatedAt", now);
            ws.send(new JSONObject().put("type", "state")
                    .put("room", Settings.PLAYBACK_SYNC_ROOM.get())
                    .put("deviceId", deviceId).put("state", state).toString());
        } catch (Exception e) { Log.e(TAG, "push", e); }
    }

    private static void handleMessage(String text) {
        try {
            JSONObject m = new JSONObject(text);
            if (!"command".equals(m.optString("type"))) return;
            JSONObject cmd = m.getJSONObject("cmd");
            String action = cmd.optString("action");
            if ("seek".equals(action)) {
                final long ms = (long) (cmd.optDouble("position", 0) * 1000);
                runOnMain(() -> {
                    applyingRemote = true;
                    try { VideoInformation.seekTo(ms); }
                    finally {
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> applyingRemote = false, 500);
                    }
                });
            } else if ("playVideo".equals(action)) {
                // TODO: needs a loadVideoById bridge on the player controller.
                Log.i(TAG, "playVideo command ignored (no bridge yet): " + cmd);
            } else {
                // TODO: play/pause need a player-controller hook.
                Log.i(TAG, "command ignored (no bridge yet): " + cmd);
            }
        } catch (Exception e) { Log.e(TAG, "handle", e); }
    }

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    /** Manual remote control from in-app UI (TODO). */
    public static void sendCommand(String targetId, String action, String videoId, double position) {
        if (ws == null || !isEnabled()) return;
        try {
            JSONObject cmd = new JSONObject().put("action", action).put("position", position);
            if (videoId != null) cmd.put("videoId", videoId);
            JSONObject msg = new JSONObject().put("type", "command")
                    .put("room", Settings.PLAYBACK_SYNC_ROOM.get()).put("cmd", cmd);
            if (targetId != null) msg.put("targetId", targetId);
            ws.send(msg.toString());
        } catch (Exception e) { Log.e(TAG, "cmd", e); }
    }
}
