package com.samarth.ytcleanplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Read-only SponsorBlock lookup, off the UI thread. Only public video IDs leave the device. */
final class SponsorBlockClient {
    private final SharedPreferences preferences;
    private final Consumer<String> evaluate;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, JSONArray> cache = new LinkedHashMap<String, JSONArray>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> entry) { return size() > 64; }
    };
    private final Set<String> pending = new HashSet<>();
    private volatile boolean closed;
    SponsorBlockClient(Context context, Consumer<String> evaluate) {
        preferences = context.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
        this.evaluate = evaluate;
    }
    @JavascriptInterface public boolean isEnabled() { return preferences.getBoolean("sponsor_block", true); }
    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("sponsor_block", enabled).apply();
        evaluate.accept("window.__shelbySponsorBlock?.setEnabled(" + enabled + ");");
    }
    @JavascriptInterface public void requestSegments(String id) {
        if (closed || !isEnabled() || id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        main.post(() -> {
            if (closed || !isEnabled()) return;
            if (cache.containsKey(id)) { deliver(id, cache.get(id)); return; }
            if (!pending.add(id)) return;
            worker.execute(() -> {
                JSONArray segments = new JSONArray();
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL("https://sponsor.ajay.app/api/skipSegments?videoID="
                            + id + "&category=sponsor&actionType=skip").openConnection();
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "Shelby/2.0 (SponsorBlock client)");
                    if (connection.getResponseCode() == 200) {
                        try (InputStream input = connection.getInputStream()) {
                            byte[] bytes = input.readNBytes(262145);
                            if (bytes.length <= 262144) segments = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {
                    // A failed community lookup must never interrupt video playback.
                } finally { if (connection != null) connection.disconnect(); }
                JSONArray result = segments;
                main.post(() -> {
                    pending.remove(id);
                    if (closed) return;
                    cache.put(id, result);
                    deliver(id, result);
                });
            });
        });
    }
    private void deliver(String id, JSONArray segments) {
        if (!closed && isEnabled()) evaluate.accept("window.__shelbySponsorBlock?.setSegments("
                + JSONObject.quote(id) + "," + segments + ");");
    }
    void close() { closed = true; worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
