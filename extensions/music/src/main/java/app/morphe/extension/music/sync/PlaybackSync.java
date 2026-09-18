package app.morphe.extension.music.sync;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;

/**
 * Spotify Connect-style sync client. Hooked into the existing
 * VideoInformation pipeline by PlaybackSyncPatch (video-id + ~1s time hooks).
 *
 * Dependency-free: raw RFC 6455 WebSocket over java.net.Socket, because the
 * host app does not ship okhttp. All networking runs off the main thread.
 *
 * V1: pushes state; applies incoming seek commands. Play/pause need a
 * player-controller hook (TODO).
 */
public class PlaybackSync {
    private static final String TAG = "MorpheSync";
    private static final long PUSH_THROTTLE_MS = 2000;

    private static final String deviceId =
            "android-" + UUID.randomUUID().toString().substring(0, 8);

    private static volatile Socket socket;
    private static volatile OutputStream out;
    private static volatile boolean running;
    private static volatile long lastPush;
    private static volatile boolean applyingRemote;
    private static volatile Thread readerThread;

    /** Injection point: called with the new video id on track change. */
    public static void onVideoId(String videoId) {
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

    private static synchronized void ensureConnected() {
        if (running || !isEnabled()) return;
        String url;
        String room;
        try {
            url = Settings.PLAYBACK_SYNC_SERVER_URL.get();
            room = Settings.PLAYBACK_SYNC_ROOM.get();
        } catch (Exception e) {
            Log.e(TAG, "settings", e);
            return;
        }
        if (url == null || url.isEmpty() || room == null || room.isEmpty()) {
            Log.w(TAG, "sync not configured (url/room empty)");
            return;
        }
        running = true;
        final String fUrl = url, fRoom = room;
        new Thread(() -> connectLoop(fUrl, fRoom), "MorpheSync").start();
    }

    private static void connectLoop(String url, String room) {
        long backoff = 1000;
        while (running && isEnabled()) {
            try {
                handshake(url, room);
                backoff = 1000;
                readLoop(room);
            } catch (Exception e) {
                Log.e(TAG, "connection lost, retry in " + backoff + "ms", e);
                closeQuietly();
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ignored) {
                break;
            }
            backoff = Math.min(backoff * 2, 30000);
        }
        running = false;
    }

    private static void handshake(String urlStr, String room) throws Exception {
        URI uri = new URI(urlStr);
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

        Socket s = tls
                ? SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
        s.setSoTimeout(0);
        s.setTcpNoDelay(true);
        OutputStream o = s.getOutputStream();
        InputStream in = s.getInputStream();

        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String key = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        o.write(req.getBytes(StandardCharsets.US_ASCII));
        o.flush();

        // Read status line; require 101.
        String status = readHttpLine(in);
        if (status == null || !status.contains("101")) {
            s.close();
            throw new java.io.IOException("WS handshake failed: " + status);
        }
        // Drain headers.
        String line;
        do {
            line = readHttpLine(in);
        } while (line != null && !line.isEmpty());

        socket = s;
        out = o;
        String name = "Android " + android.os.Build.MODEL;
        send(new JSONObject().put("type", "hello").put("room", room)
                .put("deviceId", deviceId).put("name", name).toString());
        Log.i(TAG, "connected to " + host + " room " + room);
    }

    private static String readHttpLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) return buf.size() == 0 ? null : buf.toString("US-ASCII");
            if (b == '\n') break;
            if (prev == '\r') { /* handled below */ }
            if (b != '\r') buf.write(b);
            prev = b;
        }
        return buf.toString("US-ASCII");
    }

    private static void readLoop(String room) throws Exception {
        InputStream in = socket.getInputStream();
        while (running) {
            String text = readTextFrame(in);
            if (text == null) throw new java.io.IOException("WS closed");
            handleMessage(text);
        }
    }

    /** Reads one frame; replies to ping, returns text payload or null. */
    private static String readTextFrame(InputStream in) throws Exception {
        int b1 = in.read();
        if (b1 == -1) return null;
        int b2 = in.read();
        if (b2 == -1) return null;
        int opcode = b1 & 0x0F;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        byte[] mask = null;
        if ((b2 & 0x80) != 0) {
            mask = new byte[4];
            readFully(in, mask, 4);
        }
        if (len > 8 * 1024 * 1024) throw new java.io.IOException("frame too large");
        byte[] payload = new byte[(int) len];
        readFully(in, payload, (int) len);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x9) { // ping -> pong
            sendFrame(0xA, payload);
            return readTextFrame(in);
        }
        if (opcode == 0x8) return null; // close
        if (opcode != 0x1) return readTextFrame(in); // skip non-text
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws Exception {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new java.io.IOException("eof");
            off += r;
        }
    }

    private static synchronized void send(String text) {
        OutputStream o = out;
        if (o == null) return;
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, data);
        } catch (Exception e) {
            Log.e(TAG, "send", e);
        }
    }

    private static void sendFrame(int opcode, byte[] data) throws Exception {
        OutputStream o = out;
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(0x80 | opcode);
        // Client frames MUST be masked.
        byte[] mask = new byte[4];
        new java.security.SecureRandom().nextBytes(mask);
        int len = data.length;
        if (len < 126) {
            h.write(0x80 | len);
        } else if (len < 65536) {
            h.write(0x80 | 126);
            h.write((len >> 8) & 0xFF);
            h.write(len & 0xFF);
        } else {
            h.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) h.write((len >> (8 * i)) & 0xFF);
        }
        h.write(mask);
        byte[] masked = data.clone();
        for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
        o.write(h.toByteArray());
        o.write(masked);
        o.flush();
    }

    private static void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        out = null;
    }

    private static void pushState(boolean force) {
        if (!isEnabled() || applyingRemote || out == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPush < PUSH_THROTTLE_MS) return;
        lastPush = now;
        // Snapshot on caller thread (main); cheap getters only.
        final String videoId;
        final long position;
        final long duration;
        try {
            videoId = VideoInformation.getVideoId();
            position = VideoInformation.getVideoTime();
            duration = VideoInformation.getVideoLength();
        } catch (Exception e) {
            Log.e(TAG, "snapshot", e);
            return;
        }
        new Thread(() -> {
            try {
                JSONObject state = new JSONObject()
                        .put("videoId", videoId)
                        .put("position", position / 1000.0)
                        .put("duration", duration / 1000.0)
                        .put("playing", position >= 0)
                        .put("updatedAt", now);
                send(new JSONObject().put("type", "state")
                        .put("room", Settings.PLAYBACK_SYNC_ROOM.get())
                        .put("deviceId", deviceId).put("state", state).toString());
            } catch (Exception e) {
                Log.e(TAG, "push", e);
            }
        }).start();
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
                    try {
                        VideoInformation.seekTo(ms);
                    } finally {
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> applyingRemote = false, 500);
                    }
                });
            } else {
                // TODO: play/pause/playVideo need a player-controller hook.
                Log.i(TAG, "command ignored (no bridge yet): " + cmd);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle", e);
        }
    }

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    /** Manual remote control from in-app UI (TODO). */
    public static void sendCommand(String targetId, String action,
                                   String videoId, double position) {
        if (out == null || !isEnabled()) return;
        try {
            JSONObject cmd = new JSONObject().put("action", action).put("position", position);
            if (videoId != null) cmd.put("videoId", videoId);
            JSONObject msg = new JSONObject().put("type", "command")
                    .put("room", Settings.PLAYBACK_SYNC_ROOM.get()).put("cmd", cmd);
            if (targetId != null) msg.put("targetId", targetId);
            send(msg.toString());
        } catch (Exception e) {
            Log.e(TAG, "cmd", e);
        }
    }
}
