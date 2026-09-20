package com.samarth.ytcleanplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Process-owned player. Activities attach a surface; they do not own the media lifetime. */
final class PlaybackSession implements MediaPlaybackService.PlaybackController {
    static final String HOME = "https://m.youtube.com/";
    static final String MUSIC = "https://music.youtube.com/";
    private static PlaybackSession instance;
    private static final Set<String> ORIGINS = new HashSet<>(Arrays.asList(
            "https://youtube.com", "https://*.youtube.com"));
    interface Listener {
        void onPlaybackChanged(JSONObject snapshot);
        void onPageChanged(String url, int progress);
        void onFullScreen(View view, WebChromeClient.CustomViewCallback callback);
        void onExitFullScreen();
        void onMiniRequested(boolean expand);
        void onPlayerError();
        void onExternalLink(Uri uri);
    }
    private final Context app;
    private final SharedPreferences preferences;
    private final MutableContextWrapper context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SponsorBlockClient sponsorBlock;
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private PlayerWebView webView;
    private JSONObject snapshot = new JSONObject();
    private JSONObject pendingRestore;
    private JSONObject failedNativeRestore;
    private boolean restoringDocument;
    private boolean userRequestedRestore;
    private volatile long documentGeneration;
    private String documentId;
    private long restoreRevision;
    private Runnable restoreDeadline;
    private boolean background;
    private boolean destroyed;
    private boolean manuallyPaused;
    private final java.util.Map<String, String> scripts = new java.util.HashMap<>();
    private String loadedUrl;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (background && wantsPlayback()) evaluate("window.__shelbyPlayback?.send();");
            handler.postDelayed(this, 2500);
        }
    };

    static void prepareTask(Context context, int taskId) {
        SharedPreferences saved = context.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
        if (saved.getInt("task_id", -1) != taskId) {
            discard(context);
            saved.edit().putInt("task_id", taskId).commit();
        }
    }

    static void discard(Context context) {
        if (instance != null) instance.release();
        context.getSharedPreferences("playback_session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    static PlaybackSession obtain(Context context) {
        if (instance == null || instance.destroyed) instance = new PlaybackSession(context);
        return instance;
    }

    private PlaybackSession(Context owner) {
        app = owner.getApplicationContext();
        preferences = app.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
        context = new MutableContextWrapper(owner);
        sponsorBlock = new SponsorBlockClient(app, this::evaluate);
        MediaPlaybackService.setPlaybackController(this);
        createWebView();
        handler.post(heartbeat);
    }

    WebView attach(Activity activity, Listener callback) {
        context.setBaseContext(activity);
        listener = new WeakReference<>(callback);
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
        if (loadedUrl == null) {
            String selected = preferences.getString("selected", "music");
            JSONObject saved = readSnapshot(selected);
            // Fresh browsing starts in Music; interrupted playback keeps its section.
            boolean resume = (saved.optBoolean("playing") || saved.optBoolean("wantsPlay"))
                    && !saved.optBoolean("pausedByUser") && !saved.optBoolean("ended");
            if (!resume && !"music".equals(selected)) {
                selected = "music";
                saved = readSnapshot(selected);
            }
            beginRestore(saved);
            loadedUrl = restoreUrl(pendingRestore, "music".equals(selected) ? MUSIC : HOME);
            webView.loadUrl(loadedUrl);
        }
        callback.onPlaybackChanged(snapshot);
        callback.onPageChanged(loadedUrl, webView.getProgress());
        return webView;
    }

    void detach(Listener callback) {
        if (destroyed) return;
        if (listener.get() != callback) return;
        listener.clear();
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
        context.setBaseContext(app);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new PlayerWebView(context);
        configureSettings(webView, false);
        webView.addJavascriptInterface(new MediaBridge(), "AndroidMedia");
        webView.addJavascriptInterface(sponsorBlock, "AndroidSponsorBlock");
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        installEarlyScripts(webView, false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                documentGeneration++;
                documentId = null;
                restoringDocument = false;
                loadedUrl = url;
                recordNavigation(url);
                notifyPage(url, 0);
            }
            @Override public void onPageCommitVisible(WebView view, String url) { inject(view, url, false); }
            @Override public void onPageFinished(WebView view, String url) {
                inject(view, url, false);
                restoreIfNeeded();
                CookieManager.getInstance().flush();
                notifyPage(url, 100);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                loadedUrl = url;
                recordNavigation(url);
                notifyPage(url, view.getProgress());
                restoreIfNeeded();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && isShorts(uri)) {
                    open(canonical(uri));
                    return true;
                }
                if ("https".equals(uri.getScheme()) && (trusted(uri.toString())
                        || "accounts.google.com".equals(uri.getHost()))) return false;
                if (request.isForMainFrame() && listener.get() != null) listener.get().onExternalLink(uri);
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return blocked(request.getUrl());
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // A renderer crash invalidates every WebView using it; recover from the last durable snapshot.
                Listener callback = listener.get();
                release();
                if (callback != null) callback.onPlayerError();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) { notifyPage(view.getUrl(), progress); }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                Listener owner = listener.get();
                if (owner == null) callback.onCustomViewHidden(); else owner.onFullScreen(view, callback);
            }
            @Override public void onHideCustomView() {
                if (listener.get() != null) listener.get().onExitFullScreen();
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configureSettings(WebView view, boolean browsing) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(browsing);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAlgorithmicDarkeningAllowed(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        view.setBackgroundColor(0xff0f0f0f);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
    }

    void installEarlyScripts(WebView view, boolean browsing) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        WebViewCompat.addDocumentStartJavaScript(view, asset("shorts-policy.js"), ORIGINS);
        if (!browsing) WebViewCompat.addDocumentStartJavaScript(view, asset("playback.js"), ORIGINS);
    }

    void inject(WebView view, String url, boolean browsing) {
        if (!trusted(url)) return;
        if (!isMusic(url)) {
            view.evaluateJavascript(asset("shorts-policy.js"), null);
            view.evaluateJavascript(asset("blocker.js"), null);
            view.evaluateJavascript(asset("productive-filter.js"), null);
        } else view.evaluateJavascript(asset("music-blocker.js"), null);
        view.evaluateJavascript(asset("shell.js"), null);
        if (!browsing) {
            evaluate(asset("playback.js"));
            evaluate(asset("player-surface.js"));
            evaluate(asset("sponsor-block.js"));
            evaluate("window.__shelbyPlayback?.setBackground(" + background + ");");
        }
    }

    private void restoreIfNeeded() {
        if (pendingRestore == null || restoringDocument || (background && !userRequestedRestore) || !trusted(webView.getUrl())) return;
        String expected = restoreUrl(pendingRestore, "");
        if (!sameVideoOrPage(expected, webView.getUrl()) && !isMusicLandingRedirect(expected, webView.getUrl())) return;
        JSONObject saved = pendingRestore;
        long revision = restoreRevision;
        long generation = documentGeneration;
        restoringDocument = true;
        webView.evaluateJavascript("window.__shelbyPlayback?.restore(" + saved + ");", accepted -> {
            if (destroyed || revision != restoreRevision || generation != documentGeneration) return;
            restoringDocument = false;
            if (pendingRestore == saved && "true".equals(accepted)) {
                pendingRestore = null;
                if (restoreDeadline != null) handler.removeCallbacks(restoreDeadline);
            }
        });
    }

    private void beginRestore(JSONObject saved) {
        clearNativeRestore();
        pendingRestore = saved;
        long revision = restoreRevision;
        restoreDeadline = () -> {
            if (destroyed || revision != restoreRevision || pendingRestore != saved) return;
            pendingRestore = null;
            restoringDocument = false;
            failedNativeRestore = saved;
            try {
                JSONObject failure = new JSONObject(snapshot.toString());
                failure.put("url", url()).put("playing", false).put("wantsPlay", false)
                        .put("restoring", false).put("restoreFailed", true)
                        .put("restoreFailureReason", "navigation").put("restoreTarget", saved);
                snapshot = failure;
                reportService(failure, false);
                if (listener.get() != null) listener.get().onPlaybackChanged(failure);
            } catch (org.json.JSONException ignored) { }
        };
        handler.postDelayed(restoreDeadline, 60_000L);
    }

    private void clearNativeRestore() {
        restoreRevision++;
        if (restoreDeadline != null) handler.removeCallbacks(restoreDeadline);
        restoreDeadline = null;
        pendingRestore = null;
        failedNativeRestore = null;
        restoringDocument = false;
        userRequestedRestore = false;
    }

    private boolean retryNativeRestore() {
        JSONObject target = pendingRestore != null ? pendingRestore : failedNativeRestore;
        if (target == null) return false;
        try {
            JSONObject saved = new JSONObject(target.toString()).put("playing", true)
                    .put("wantsPlay", true).put("pausedByUser", false).put("ended", false);
            String expected = restoreUrl(saved, "");
            if (!trusted(expected)) return false;
            beginRestore(saved);
            userRequestedRestore = true;
            if (sameVideoOrPage(expected, webView.getUrl())) {
                restoreIfNeeded();
            } else webView.loadUrl(expected);
            return true;
        } catch (org.json.JSONException ignored) { return false; }
    }

    private static boolean isMusicLandingRedirect(String expected, String current) {
        if (!isMusic(expected) || !isMusic(current)) return false;
        Uri uri = Uri.parse(current);
        return ("/".equals(uri.getPath()) || "".equals(uri.getPath())) && uri.getQuery() == null;
    }

    private void recordNavigation(String url) {
        if (!trusted(url)) return;
        JSONObject target = pendingRestore != null ? pendingRestore : failedNativeRestore;
        if (target != null) {
            String expected = restoreUrl(target, "");
            if (!sameVideoOrPage(expected, url)) {
                // Music briefly routes through its landing page while rebuilding a queue.
                // Keep that redirect from replacing the durable watch URL; a different
                // track/search/page is a new navigation and must release the old restore gate.
                if (isMusicLandingRedirect(expected, url)) return;
                clearNativeRestore();
            }
        }
        saveSelection(url);
    }

    void open(String url) {
        if (!trusted(url) || destroyed) return;
        clearNativeRestore();
        pauseService();
        evaluate("window.__shelbyPlayback?.prepareNavigation();window.__shelbySurface?.exit();");
        webView.loadUrl(canonical(Uri.parse(url)));
    }

    void switchSection(boolean music) {
        if (music == isMusic(url())) return;
        JSONObject saved = readSnapshot(music ? "music" : "youtube");
        command("pause", 0);
        beginRestore(saved);
        loadedUrl = restoreUrl(saved, music ? MUSIC : HOME);
        webView.loadUrl(loadedUrl);
    }

    void setBackground(boolean value) {
        if (destroyed) return;
        if (value && !background && isPlaying() && !MediaPlaybackService.isRunning()) {
            app.startForegroundService(MediaPlaybackService.updateIntent(app, snapshot.optString("title", "Shelby"), true,
                    (long) (snapshot.optDouble("position", 0) * 1000), (long) (snapshot.optDouble("duration", 0) * 1000)));
        }
        background = value;
        webView.keepMediaVisible = value && wantsPlayback();
        // Do not call WebView.onPause()/pauseTimers(): this surface is also the media engine.
        webView.onResume();
        evaluate("window.__shelbyPlayback?.setBackground(" + value + ");window.__shelbyPlayback?.send(true);");
        if (!value && webView.getProgress() == 100) restoreIfNeeded();
        CookieManager.getInstance().flush();
    }

    void command(String command, double value) {
        if (destroyed) return;
        if ("play".equals(command) && retryNativeRestore()) return;
        if ("play".equals(command) || "pause".equals(command) || "stop".equals(command)) clearNativeRestore();
        evaluate("window.__shelbyPlayback?.command(" + JSONObject.quote(command) + "," + value + ");");
        if ("pause".equals(command) || "stop".equals(command)) markPaused("stop".equals(command));
    }

    private void markPaused(boolean stopped) {
        try { snapshot.put("playing", false).put("wantsPlay", false).put("pausedByUser", true); } catch (Exception ignored) { }
        if (!transientSnapshot(snapshot)) persist(snapshot);
        if (stopped) {
            // Service-originated STOP sets isRunning=false before calling us, so this
            // only dispatches for a native UI dismissal and cannot echo back indefinitely.
            if (MediaPlaybackService.isRunning()) app.startService(new Intent(app, MediaPlaybackService.class)
                    .setAction(MediaPlaybackService.ACTION_STOP));
        } else pauseService();
    }

    private void pauseService() {
        if (MediaPlaybackService.isRunning()) app.startService(MediaPlaybackService.userPauseIntent(app));
    }

    @Override public boolean isAvailable() { return !destroyed && webView != null; }
    @Override public boolean handlesAudioFocus() { return true; }
    @Override public void onMediaCommand(String command, long positionMs) {
        command(command, "seek".equals(command) ? positionMs / 1000.0 : 0);
        if ("stop".equals(command)) {
            setBackground(false);
            if (listener.get() == null) release();
        }
    }

    void evaluate(String js) { if (!destroyed && webView != null) webView.evaluateJavascript(js, null); }
    String url() { return loadedUrl == null ? HOME : loadedUrl; }
    boolean isPlaying() { return snapshot.optBoolean("playing") && !snapshot.optBoolean("pausedByUser"); }
    private boolean wantsPlayback() {
        return (snapshot.optBoolean("wantsPlay") || snapshot.optBoolean("playing"))
                && !snapshot.optBoolean("pausedByUser") && !snapshot.optBoolean("ended") && !snapshot.optBoolean("suspended");
    }
    JSONObject snapshot() { return snapshot; }
    boolean sponsorEnabled() { return sponsorBlock.isEnabled(); }
    void setSponsorEnabled(boolean enabled) { sponsorBlock.setEnabled(enabled); }

    private void receiveSnapshot(String json) {
        try {
            JSONObject update = new JSONObject(json);
            String url = update.optString("url");
            if (!trusted(url) || !trusted(webView.getUrl()) || !sameVideoOrPage(url, webView.getUrl())) return;
            String incomingDocument = update.optString("documentId");
            if (!incomingDocument.isEmpty()) {
                if (documentId == null) {
                    long generation = documentGeneration;
                    webView.evaluateJavascript("window.__shelbyPlayback?.documentId", current -> {
                        if (!destroyed && generation == documentGeneration
                                && JSONObject.quote(incomingDocument).equals(current)) {
                            documentId = incomingDocument;
                            receiveSnapshot(json);
                        }
                    });
                    return;
                }
                if (!documentId.equals(incomingDocument)) return;
            }
            recordNavigation(url);
            if (pendingRestore != null) restoreIfNeeded();
            if (failedNativeRestore != null) {
                update.put("restoreFailed", true).put("restoreFailureReason", "navigation")
                        .put("restoreTarget", failedNativeRestore);
            }
            boolean transientState = pendingRestore != null || failedNativeRestore != null || transientSnapshot(update);
            if (transientState) {
                // A loading/ad/restore snapshot must still release stale native PLAYING state.
                // It must not replace the durable content position with a preroll or zero.
                snapshot = update;
                reportService(update, false);
                if (listener.get() != null) listener.get().onPlaybackChanged(update);
                return;
            }
            boolean userPaused = update.optBoolean("pausedByUser");
            if (userPaused && !manuallyPaused && MediaPlaybackService.isRunning()) {
                app.startService(MediaPlaybackService.userPauseIntent(app));
            }
            manuallyPaused = userPaused;
            snapshot = update;
            loadedUrl = url;
            webView.keepMediaVisible = background && wantsPlayback();
            persist(update);
            reportService(update, true);
            if (listener.get() != null) listener.get().onPlaybackChanged(update);
        } catch (RuntimeException | org.json.JSONException ignored) { }
    }

    private static boolean transientSnapshot(JSONObject state) {
        return state.optBoolean("restoring") || state.optBoolean("restoreFailed")
                || state.optBoolean("ad") || state.optBoolean("navigating");
    }

    private void reportService(JSONObject state, boolean contentReady) {
        boolean playing = contentReady && state.optBoolean("playing") && !state.optBoolean("pausedByUser");
        boolean wanted = contentReady && wantsPlayback() && !state.optBoolean("platformPaused");
        Intent intent = MediaPlaybackService.updateIntent(app, state.optString("title", "Shelby"), playing,
                Math.max(0, (long) (state.optDouble("position", 0) * 1000)),
                Math.max(0, (long) (state.optDouble("duration", 0) * 1000)), wanted);
        if (MediaPlaybackService.isRunning()) app.startService(intent);
        else if (playing && listener.get() != null && !background) app.startForegroundService(intent);
    }

    private void persist(JSONObject state) {
        String url = state.optString("url");
        if (!trusted(url)) return;
        String section = isMusic(url) ? "music" : "youtube";
        preferences.edit().putString(section, state.toString()).putString("selected", section).apply();
    }

    private void saveSelection(String url) {
        String section = isMusic(url) ? "music" : "youtube";
        preferences.edit().putString("selected", section).putString(section + "_url", url).apply();
    }

    private JSONObject readSnapshot(String section) {
        try {
            String json = preferences.getString(section, null);
            if (json != null) {
                JSONObject saved = new JSONObject(json);
                String latest = preferences.getString(section + "_url", saved.optString("url"));
                if (trusted(latest) && !sameVideoOrPage(latest, saved.optString("url"))
                        && !sameVideoOrPage(latest, saved.optString("mediaUrl"))) return new JSONObject().put("url", latest);
                return saved;
            }
            return new JSONObject().put("url", preferences.getString(section + "_url", "music".equals(section) ? MUSIC : HOME));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static String restoreUrl(JSONObject state, String fallback) {
        String original = state.optString("url");
        String videoId = state.optString("videoId");
        // Preserve the active playlist/queue when the saved page is already this track.
        String url = videoId.isEmpty() || (trusted(original)
                && videoId.equals(Uri.parse(original).getQueryParameter("v"))) ? original
                : state.optString("mediaUrl", original);
        return trusted(url) ? canonical(Uri.parse(url)) : fallback;
    }

    private void notifyPage(String url, int progress) {
        if (listener.get() != null) listener.get().onPageChanged(url, progress);
    }

    void release() {
        if (destroyed) return;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        sponsorBlock.close();
        MediaPlaybackService.clearPlaybackController(this);
        app.stopService(new Intent(app, MediaPlaybackService.class));
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.removeJavascriptInterface("AndroidMedia");
        webView.removeJavascriptInterface("AndroidSponsorBlock");
        webView.destroy();
        context.setBaseContext(app);
        listener.clear();
        if (instance == this) instance = null;
    }

    private final class MediaBridge {
        @JavascriptInterface public void onUserPlaybackIntent(boolean play) {
            long generation = documentGeneration;
            handler.post(() -> {
                if (destroyed || generation != documentGeneration || !trusted(webView.getUrl())) return;
                if (!play) clearNativeRestore();
                if (play && listener.get() != null && !background) {
                    app.startForegroundService(new Intent(app, MediaPlaybackService.class).setAction(MediaPlaybackService.ACTION_PLAY));
                } else if (!play && MediaPlaybackService.isRunning()) {
                    app.startService(MediaPlaybackService.userPauseIntent(app));
                }
            });
        }
        @JavascriptInterface public void onSnapshot(String json) {
            long generation = documentGeneration;
            if (json != null && json.length() < 32768) handler.post(() -> {
                if (!destroyed && generation == documentGeneration) receiveSnapshot(json);
            });
        }
        @JavascriptInterface public void onMiniPlayerRequested() {
            handler.post(() -> { if (listener.get() != null) listener.get().onMiniRequested(false); });
        }
        @JavascriptInterface public void onMiniPlayerExpandRequested() {
            handler.post(() -> { if (listener.get() != null) listener.get().onMiniRequested(true); });
        }
    }

    String asset(String file) {
        if (scripts.containsKey(file)) return scripts.get(file);
        try (InputStream stream = app.getAssets().open(file)) {
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            scripts.put(file, script);
            return script;
        } catch (Exception ignored) { return ""; }
    }

    static boolean trusted(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null
                && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
    }
    static boolean isMusic(String url) { return url != null && "music.youtube.com".equals(Uri.parse(url).getHost()); }
    static boolean isShorts(Uri uri) { return trusted(uri.toString()) && uri.getPath() != null && uri.getPath().startsWith("/shorts/"); }
    static boolean isVideo(String url) { return trusted(url) && ("/watch".equals(Uri.parse(url).getPath()) || isShorts(Uri.parse(url))); }
    static String canonical(Uri uri) {
        if (!isShorts(uri)) return uri.toString();
        String id = uri.getPathSegments().size() > 1 ? uri.getPathSegments().get(1) : "";
        return id.matches("[a-zA-Z0-9_-]{11}") ? HOME + "watch?v=" + id : HOME;
    }
    private static boolean sameVideoOrPage(String first, String second) {
        if (!trusted(first) || !trusted(second)) return false;
        Uri a = Uri.parse(first), b = Uri.parse(second);
        return a.getHost().equals(b.getHost()) && a.getPath().equals(b.getPath())
                && (a.getQueryParameter("v") != null
                    ? java.util.Objects.equals(a.getQueryParameter("v"), b.getQueryParameter("v"))
                    : java.util.Objects.equals(a.getQuery(), b.getQuery()));
    }

    static WebResourceResponse blocked(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.equals("doubleclick.net") || host.endsWith(".doubleclick.net")
                || host.equals("googleadservices.com") || host.endsWith(".googleadservices.com")
                || host.endsWith(".googlesyndication.com") || path.contains("/pagead/")
                || path.contains("/api/stats/ads") || path.contains("/get_midroll_info")) {
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    private static final class PlayerWebView extends WebView {
        boolean keepMediaVisible;
        PlayerWebView(Context context) { super(context); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            // Chromium otherwise suspends the renderer when the screen locks despite the foreground service.
            super.onWindowVisibilityChanged(keepMediaVisible ? View.VISIBLE : visibility);
        }
    }
}
