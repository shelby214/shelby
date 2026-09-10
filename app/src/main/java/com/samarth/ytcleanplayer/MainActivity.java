package com.samarth.ytcleanplayer;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://m.youtube.com/";
    private static final String MUSIC_URL = "https://music.youtube.com/";
    private static final int PICK_SCRIPT_REQUEST = 1001;
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final String PREFS_NAME = "blocker_settings";
    private static final String PREF_SCRIPT_URI = "script_uri";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;
    private static final String MEDIA_MONITOR_SCRIPT =
            "(() => {" +
            "if (!document.documentElement) return;" +
            "if (window.__ytCleanMediaMonitor) { window.__ytCleanMediaMonitor.send(); return; }" +
            "const monitor = { last: '', send() {" +
            "const videos=[...document.querySelectorAll('video,audio')];" +
            "const video=videos.find(v=>!v.paused&&!v.ended)||videos[0];" +
            "if (!video || !window.AndroidMedia) return;" +
            "const playing = !video.paused && !video.ended && video.readyState > 1;" +
            "const title = document.title.replace(/\\s+-\\s+YouTube(?: Music)?$/, '');" +
            "const state = [playing, video.videoWidth, video.videoHeight, title].join('|');" +
            "if (state === this.last) return; this.last = state;" +
            "window.AndroidMedia.onPlaybackState(playing, video.videoWidth || 16, video.videoHeight || 9, title);" +
            "}}; window.__ytCleanMediaMonitor = monitor;" +
            "['play','pause','ended','loadedmetadata','durationchange'].forEach(type => " +
            "document.addEventListener(type, () => monitor.send(), true));" +
            "let miniGesture=null;" +
            "document.addEventListener('touchstart',event=>{" +
            "if(event.touches.length!==1)return;const touch=event.touches[0];" +
            "const videos=[...document.querySelectorAll('video')];" +
            "const video=videos.find(v=>{const r=v.getBoundingClientRect();return touch.clientX>=r.left&&touch.clientX<=r.right&&touch.clientY>=r.top&&touch.clientY<=r.bottom;});" +
            "miniGesture=video?{x:touch.clientX,y:touch.clientY,time:Date.now()}:null;},true);" +
            "document.addEventListener('touchend',event=>{" +
            "if(!miniGesture||!event.changedTouches.length)return;const touch=event.changedTouches[0];" +
            "const down=touch.clientY-miniGesture.y;const sideways=Math.abs(touch.clientX-miniGesture.x);" +
            "const held=Date.now()-miniGesture.time;miniGesture=null;" +
            "if(held>=250&&down>=70&&sideways<160&&window.AndroidMedia)window.AndroidMedia.onMiniPlayerRequested();},true);" +
            "new MutationObserver(() => {monitor.send();if(document.documentElement.classList.contains('yt-clean-mini'))window.__ytCleanApplyMini?.();}).observe(document.documentElement, {childList:true,subtree:true});" +
            "setInterval(() => monitor.send(), 1000); monitor.send();" +
            "})();";
    private static final String ENTER_MINI_PLAYER_SCRIPT =
            "(() => {const videos=[...document.querySelectorAll('video')];const video=videos.find(v=>!v.paused&&!v.ended)||videos[0];if(!video)return;" +
            "window.__ytCleanAllowMiniPause=false;if(!video.__ytCleanMiniOriginalPause){video.__ytCleanMiniOriginalPause=video.pause;" +
            "video.pause=function(){if(document.documentElement.classList.contains('yt-clean-mini')&&!window.__ytCleanAllowMiniPause)return;return video.__ytCleanMiniOriginalPause.apply(this,arguments);};}" +
            "document.documentElement.classList.add('yt-clean-mini');window.__ytCleanMiniVideo=video;" +
            "window.__ytCleanMiniPlaceholder=document.createComment('yt-clean-mini');video.parentNode?.insertBefore(window.__ytCleanMiniPlaceholder,video);document.body.appendChild(video);" +
            "window.__ytCleanMiniEnteredAt=Date.now();window.__ytCleanMiniClick=event=>{if(Date.now()-window.__ytCleanMiniEnteredAt<700)return;event.preventDefault();event.stopImmediatePropagation();window.AndroidMedia?.onMiniPlayerExpandRequested();};video.addEventListener('click',window.__ytCleanMiniClick,true);" +
            "let style=document.getElementById('yt-clean-mini-style');if(!style){style=document.createElement('style');style.id='yt-clean-mini-style';" +
            "style.textContent='html.yt-clean-mini video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;object-fit:contain!important;background:#000!important;z-index:2147483647!important;visibility:visible!important}html.yt-clean-mini body{background:#000!important;overflow:hidden!important}';document.documentElement.appendChild(style);}" +
            "window.__ytCleanExitMini=(_,pause)=>{const active=window.__ytCleanMiniVideo;if(pause&&active){window.__ytCleanAllowMiniPause=true;(active.__ytCleanMiniOriginalPause||active.pause).call(active);}document.documentElement.classList.remove('yt-clean-mini');" +
            "if(active&&window.__ytCleanMiniClick)active.removeEventListener('click',window.__ytCleanMiniClick,true);delete window.__ytCleanMiniClick;delete window.__ytCleanMiniEnteredAt;" +
            "if(active&&window.__ytCleanMiniPlaceholder?.parentNode){window.__ytCleanMiniPlaceholder.parentNode.insertBefore(active,window.__ytCleanMiniPlaceholder);window.__ytCleanMiniPlaceholder.remove();}" +
            "if(active?.__ytCleanMiniOriginalPause){active.pause=active.__ytCleanMiniOriginalPause;delete active.__ytCleanMiniOriginalPause;}delete window.__ytCleanAllowMiniPause;" +
            "delete window.__ytCleanMiniVideo;delete window.__ytCleanMiniPlaceholder;};video.play().catch(()=>{});})();";
    private static final String BROWSER_LINK_SCRIPT =
            "(() => {if(window.__ytCleanBrowserLinks)return;window.__ytCleanBrowserLinks=true;document.addEventListener('click',event=>{" +
            "const link=event.target.closest?.('a[href]');if(!link)return;try{const url=new URL(link.href,location.href);" +
            "if(url.pathname.startsWith('/shorts/')){const id=url.pathname.split('/')[2];url.pathname='/watch';url.search='?v='+encodeURIComponent(id);}" +
            "if((url.hostname.endsWith('youtube.com'))&&url.pathname==='/watch'){event.preventDefault();event.stopImmediatePropagation();window.AndroidBrowser?.openVideo(url.href);}}catch(_){ }},true);})();";
    private static final String SHORTS_POLICY_SCRIPT =
            "(() => {if(window.__ytCleanShortsPolicy){window.__ytCleanShortsPolicy.run();return;}" +
            "const normalUrl=href=>{try{const url=new URL(href,location.href);if(!url.pathname.startsWith('/shorts/'))return null;" +
            "const id=url.pathname.split('/')[2];if(!id)return null;url.pathname='/watch';url.search='?v='+encodeURIComponent(id);return url.href;}catch(_){return null;}};" +
            "const style=document.createElement('style');style.id='yt-clean-feed-policy-style';style.textContent='.yt-clean-feed-hidden{display:none!important}';document.documentElement.appendChild(style);" +
            "const hide=node=>{if(node)node.classList.add('yt-clean-feed-hidden');};" +
            "const policy={pending:false,run(){this.pending=false;const home=location.pathname==='/'||location.pathname==='/feed/recommended';" +
            "if(location.pathname.startsWith('/shorts/')){const target=normalUrl(location.href);if(target){location.replace(target);return;}}" +
            "document.querySelectorAll('ytm-pivot-bar-item-renderer').forEach(item=>{const link=item.querySelector('a[href]');const path=link?new URL(link.href,location.href).pathname:'';if(path.startsWith('/shorts')||item.textContent.trim()==='Shorts')hide(item);});" +
            "document.querySelectorAll('ytd-mini-guide-entry-renderer a[href^=\"/shorts\"],ytd-guide-entry-renderer a[href^=\"/shorts\"]').forEach(link=>hide(link.closest('ytd-mini-guide-entry-renderer,ytd-guide-entry-renderer')));" +
            "if(home){document.querySelectorAll('ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,ytd-rich-shelf-renderer[is-shorts],ytm-rich-section-renderer:has(a[href*=\"/shorts/\"]),ytd-rich-section-renderer:has(a[href*=\"/shorts/\"])').forEach(hide);" +
            "document.querySelectorAll('a[href*=\"/shorts/\"]').forEach(link=>{const card=link.closest('ytm-shorts-lockup-view-model,ytm-reel-item-renderer,ytd-reel-item-renderer,ytm-rich-item-renderer,ytd-rich-item-renderer,ytm-video-with-context-renderer');hide(card);});" +
            "document.querySelectorAll('ytm-post-renderer,ytm-backstage-post-renderer,ytm-backstage-post-thread-renderer,ytd-post-renderer,ytd-backstage-post-renderer,ytd-backstage-post-thread-renderer').forEach(post=>hide(post.closest('ytm-rich-item-renderer,ytd-rich-item-renderer')||post));" +
            "document.querySelectorAll('a[href^=\"/post/\"],a[href*=\"youtube.com/post/\"]').forEach(link=>hide(link.closest('ytm-rich-item-renderer,ytd-rich-item-renderer,ytm-post-renderer,ytm-backstage-post-renderer,ytd-post-renderer,ytd-backstage-post-renderer')));}" +
            "document.querySelectorAll('a[href*=\"/shorts/\"]').forEach(link=>{const target=normalUrl(link.href);if(target)link.href=target;});}," +
            "schedule(){if(this.pending)return;this.pending=true;requestAnimationFrame(()=>this.run());}};window.__ytCleanShortsPolicy=policy;" +
            "document.addEventListener('click',event=>{const link=event.target.closest?.('a[href]');if(!link)return;const target=normalUrl(link.href);" +
            "if(target){event.preventDefault();event.stopImmediatePropagation();location.href=target;}},true);" +
            "new MutationObserver(()=>policy.schedule()).observe(document.documentElement,{childList:true,subtree:true});" +
            "document.addEventListener('yt-navigate-finish',()=>policy.schedule());policy.run();})();";
    private static final String DARK_THEME_SCRIPT =
            "(() => {if(location.hostname==='music.youtube.com')return;if(!(location.hostname==='youtube.com'||location.hostname.endsWith('.youtube.com')))return;" +
            "const apply=()=>{const html=document.documentElement;html.setAttribute('dark','');html.setAttribute('darker-dark-theme','');document.body?.setAttribute('dark','');document.querySelector('ytm-app')?.setAttribute('dark','');" +
            "let meta=document.querySelector('meta[name=\"color-scheme\"]');if(!meta){meta=document.createElement('meta');meta.name='color-scheme';document.head?.appendChild(meta);}meta.content='dark';" +
            "if(!document.getElementById('yt-clean-dark-style')){const style=document.createElement('style');style.id='yt-clean-dark-style';style.textContent=':root{color-scheme:dark!important;--yt-spec-base-background:#0f0f0f!important;--yt-spec-raised-background:#212121!important;--yt-spec-menu-background:#282828!important;--yt-spec-text-primary:#f1f1f1!important;--yt-spec-text-secondary:#aaa!important;--yt-spec-icon-active-other:#fff!important;--yt-spec-icon-inactive:#aaa!important;--yt-spec-brand-background-solid:#0f0f0f!important}html,body,ytm-app,ytm-browse,ytm-search,ytm-watch,ytm-item-section-renderer,ytm-rich-grid-renderer,ytm-section-list-renderer{background:#0f0f0f!important;color:#f1f1f1!important}ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer,ytm-pivot-bar-item-renderer{background:#0f0f0f!important;color:#f1f1f1!important;border-color:#272727!important}ytm-chip-cloud-chip-renderer button,.chip-container{background:#272727!important;color:#f1f1f1!important;border-color:#3f3f3f!important}ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-rich-item-renderer{background:#0f0f0f!important;color:#f1f1f1!important}a,button,h1,h2,h3,.title{color:inherit}';document.documentElement.appendChild(style);}};" +
            "if(!window.__ytCleanDarkObserver){window.__ytCleanDarkObserver=new MutationObserver(()=>requestAnimationFrame(apply));window.__ytCleanDarkObserver.observe(document.documentElement,{childList:true,subtree:true});document.addEventListener('yt-navigate-finish',apply);}apply();})();";
    private static final String RESUME_PLAYBACK_SCRIPT =
            "(() => {const media=[...document.querySelectorAll('video,audio')];const active=media.find(item=>!item.ended)||media[0];if(active&&active.paused&&!active.ended)active.play().catch(()=>{});})();";
    private static final String BACKGROUND_PLAYBACK_GUARD_SCRIPT =
            "(() => {if(window.__shelbyBackgroundGuard){window.__shelbyApplyBackgroundMedia?.();return;}window.__shelbyBackgroundGuard=true;window.__shelbyBackgroundPlayback=false;window.__shelbyAllowBackgroundPause=false;" +
            "try{Object.defineProperty(document,'hidden',{configurable:true,get:()=>false});Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'visible'});Object.defineProperty(document,'webkitHidden',{configurable:true,get:()=>false});Object.defineProperty(document,'webkitVisibilityState',{configurable:true,get:()=>'visible'});document.hasFocus=()=>true;}catch(_){}" +
            "const keepVisible=event=>{if(window.__shelbyBackgroundPlayback){event.preventDefault?.();event.stopImmediatePropagation?.();}};['visibilitychange','webkitvisibilitychange','pagehide','freeze'].forEach(type=>{document.addEventListener(type,keepVisible,true);window.addEventListener(type,keepVisible,true);});" +
            "const apply=()=>document.querySelectorAll('video,audio').forEach(media=>{if(media.__shelbyOriginalPause)return;media.__shelbyOriginalPause=media.pause;media.pause=function(){if(window.__shelbyBackgroundPlayback&&!window.__shelbyAllowBackgroundPause&&!media.ended){media.play().catch(()=>{});return;}return media.__shelbyOriginalPause.apply(media,arguments);};});" +
            "window.__shelbyApplyBackgroundMedia=apply;if(!window.__shelbyBackgroundObserver){window.__shelbyBackgroundObserver=new MutationObserver(apply);window.__shelbyBackgroundObserver.observe(document.documentElement,{childList:true,subtree:true});}" +
            "window.__shelbyEnterBackground=()=>{window.__shelbyBackgroundPlayback=true;window.__shelbyAllowBackgroundPause=false;apply();const active=[...document.querySelectorAll('video,audio')].find(media=>!media.ended);active?.play().catch(()=>{});};apply();})();";
    private static final String ENTER_BACKGROUND_PLAYBACK_SCRIPT =
            BACKGROUND_PLAYBACK_GUARD_SCRIPT + ";window.__shelbyEnterBackground?.();";
    private static final String ENTER_PIP_SCRIPT =
            "(() => {window.__ytCleanExitMini?.(false,false);const videos=[...document.querySelectorAll('video')];const video=videos.find(v=>!v.paused&&!v.ended)||videos[0];if(!video)return;" +
            "window.__ytCleanAllowPipPause=false;if(!video.__ytCleanOriginalPause){video.__ytCleanOriginalPause=video.pause;" +
            "video.pause=function(){if(document.documentElement.classList.contains('yt-clean-pip')&&!window.__ytCleanAllowPipPause)return;return video.__ytCleanOriginalPause.apply(this,arguments);};}" +
            "window.__ytCleanPipVideo=video;window.__ytCleanPipPlaceholder=document.createComment('yt-clean-pip');" +
            "video.parentNode?.insertBefore(window.__ytCleanPipPlaceholder,video);document.body.appendChild(video);" +
            "window.__ytCleanPipAncestors=[];" +
            "for(let node=video.parentElement;node&&node!==document.documentElement;node=node.parentElement){" +
            "window.__ytCleanPipAncestors.push([node,node.getAttribute('style')]);" +
            "node.style.setProperty('transform','none','important');" +
            "node.style.setProperty('overflow','visible','important');" +
            "node.style.setProperty('contain','none','important');" +
            "node.style.setProperty('clip-path','none','important');" +
            "node.style.setProperty('filter','none','important');" +
            "node.style.setProperty('perspective','none','important');}" +
            "let style=document.getElementById('yt-clean-pip-style');" +
            "if(!style){style=document.createElement('style');style.id='yt-clean-pip-style';" +
            "style.textContent='html.yt-clean-pip video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;object-fit:contain!important;background:#000!important;z-index:2147483647!important;visibility:visible!important}html.yt-clean-pip body{background:#000!important;overflow:hidden!important}';" +
            "document.documentElement.appendChild(style);}document.documentElement.classList.add('yt-clean-pip');})();";
    private static final String EXIT_PIP_SCRIPT =
            "(() => {document.documentElement.classList.remove('yt-clean-pip');" +
            "if(window.__ytCleanPipVideo&&window.__ytCleanPipPlaceholder?.parentNode){window.__ytCleanPipPlaceholder.parentNode.insertBefore(window.__ytCleanPipVideo,window.__ytCleanPipPlaceholder);window.__ytCleanPipPlaceholder.remove();}" +
            "const videos=[...document.querySelectorAll('video')];videos.forEach(video=>{if(video.__ytCleanOriginalPause){video.pause=video.__ytCleanOriginalPause;delete video.__ytCleanOriginalPause;}});delete window.__ytCleanAllowPipPause;" +
            "(window.__ytCleanPipAncestors||[]).forEach(([node,style])=>{" +
            "if(style===null)node.removeAttribute('style');else node.setAttribute('style',style);});" +
            "delete window.__ytCleanPipAncestors;delete window.__ytCleanPipVideo;delete window.__ytCleanPipPlaceholder;})();";
    private static final Set<String> BLOCKED_HOST_PARTS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "adservice.google."
    ));
    private static final String[] BLOCKED_PATH_PARTS = {
            "/pagead/", "/ptracking", "/api/stats/ads", "/get_midroll_info"
    };

    private FrameLayout root;
    private WebView webView;
    private WebView browseWebView;
    private Button scriptMenu;
    private Button musicButton;
    private Button miniClose;
    private Button miniExpand;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String blockerScript;
    private String productiveFilterScript;
    private String musicBlockerScript;
    private boolean mediaServiceStarted;
    private boolean videoPlaying;
    private int videoWidth = 16;
    private int videoHeight = 9;
    private boolean receiverRegistered;
    private boolean resumePlaybackForPip;
    private boolean playbackPausedByUser;
    private boolean inAppMiniPlayer;
    private boolean appInBackground;
    private boolean backgroundPlayback;
    private long lastPlayingAt;
    private PowerManager.WakeLock backgroundWakeLock;
    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable pipPlaybackKeepAlive = new Runnable() {
        @Override
        public void run() {
            if (!isInPictureInPictureMode() || !resumePlaybackForPip || playbackPausedByUser) {
                return;
            }
            webView.evaluateJavascript(ENTER_BACKGROUND_PLAYBACK_SCRIPT, null);
            playbackHandler.postDelayed(this, 750);
        }
    };
    private final Runnable miniPlaybackKeepAlive = new Runnable() {
        @Override
        public void run() {
            if (!inAppMiniPlayer || playbackPausedByUser) return;
            webView.evaluateJavascript(RESUME_PLAYBACK_SCRIPT, null);
            playbackHandler.postDelayed(this, 750);
        }
    };
    private final Runnable backgroundPlaybackKeepAlive = new Runnable() {
        @Override
        public void run() {
            if (!appInBackground || !backgroundPlayback || playbackPausedByUser) return;
            webView.evaluateJavascript(RESUME_PLAYBACK_SCRIPT, null);
            playbackHandler.postDelayed(this, 750);
        }
    };
    private final OnBackInvokedCallback backCallback = this::handleBackNavigation;

    private final BroadcastReceiver mediaCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                runOnUiThread(MainActivity.this::enterBackgroundPlaybackIfNeeded);
                return;
            }
            String command = intent.getStringExtra(MediaPlaybackService.EXTRA_COMMAND);
            if (MediaPlaybackService.COMMAND_PLAY.equals(command)) {
                playbackPausedByUser = false;
                if (isInPictureInPictureMode()) resumePlaybackForPip = true;
                webView.evaluateJavascript(
                        "window.__ytCleanAllowPipPause=false;window.__ytCleanAllowMusicPause=false;document.querySelector('video,audio')?.play().catch(()=>{});", null
                );
                if (isInPictureInPictureMode()) schedulePictureInPictureResume();
                if (inAppMiniPlayer) scheduleMiniPlayerResume();
                if (appInBackground) scheduleBackgroundPlaybackResume();
            } else if (MediaPlaybackService.COMMAND_PAUSE.equals(command)) {
                playbackPausedByUser = true;
                resumePlaybackForPip = false;
                playbackHandler.removeCallbacks(pipPlaybackKeepAlive);
                playbackHandler.removeCallbacks(miniPlaybackKeepAlive);
                playbackHandler.removeCallbacks(backgroundPlaybackKeepAlive);
                releaseBackgroundWakeLock();
                webView.evaluateJavascript(
                        "(() => {window.__ytCleanAllowPipPause=true;window.__shelbyAllowBackgroundPause=true;const v=document.querySelector('video,audio');if(v)(v.__shelbyOriginalPause||v.__ytCleanOriginalPause||v.pause).call(v);})();",
                        null
                );
            } else if (MediaPlaybackService.COMMAND_STOP.equals(command)) {
                webView.evaluateJavascript("window.__shelbyAllowBackgroundPause=true;document.querySelector('video,audio')?.pause();", null);
                releaseBackgroundWakeLock();
                finishAndRemoveTask();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        webView = findViewById(R.id.web_view);
        scriptMenu = findViewById(R.id.script_menu);
        scriptMenu.setVisibility(View.GONE);
        configureMusicButton();
        configureMiniPlayerControls();
        blockerScript = loadConfiguredScript();
        productiveFilterScript = readAsset("productive-filter.js");
        musicBlockerScript = readAsset("music-blocker.js");
        webView.addJavascriptInterface(new MediaBridge(), "AndroidMedia");
        configureWebView();
        configureSafeAreas();
        IntentFilter playbackIntentFilter =
                new IntentFilter(MediaPlaybackService.ACTION_MEDIA_COMMAND);
        playbackIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mediaCommandReceiver, playbackIntentFilter, RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback
        );

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAlgorithmicDarkeningAllowed(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                updateMusicButton(url);
                injectBlocker(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                updateMusicButton(url);
                injectBlocker(view, url);
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && isYouTubeShortsUrl(uri)) {
                    view.loadUrl(normalizeYouTubeVideoUrl(uri));
                    return true;
                }
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

                for (String hostPart : BLOCKED_HOST_PARTS) {
                    if (host.contains(hostPart)) return emptyResponse();
                }
                for (String pathPart : BLOCKED_PATH_PARTS) {
                    if (path.contains(pathPart)) return emptyResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                scriptMenu.setVisibility(View.GONE);
                musicButton.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                enterImmersiveVideo();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureMiniBrowser(WebView browser) {
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAlgorithmicDarkeningAllowed(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.addJavascriptInterface(new BrowserBridge(), "AndroidBrowser");
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                injectBlockerOnly(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectBlockerOnly(view, url);
                view.evaluateJavascript(BROWSER_LINK_SCRIPT, null);
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && isYouTubeVideoUrl(uri)) {
                    openVideoFromMiniBrowser(normalizeYouTubeVideoUrl(uri));
                    return true;
                }
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
            ) {
                return interceptAdvertisingRequest(view, request);
            }
        });
    }

    private final class BrowserBridge {
        @JavascriptInterface
        public void openVideo(String url) {
            runOnUiThread(() -> {
                Uri uri = Uri.parse(url);
                if (isYouTubeVideoUrl(uri)) {
                    openVideoFromMiniBrowser(normalizeYouTubeVideoUrl(uri));
                }
            });
        }
    }

    private boolean isYouTubeVideoUrl(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return (host.equals("youtube.com") || host.endsWith(".youtube.com"))
                && (path.equals("/watch") || path.startsWith("/shorts/"));
    }

    private boolean isYouTubeShortsUrl(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return (host.equals("youtube.com") || host.endsWith(".youtube.com"))
                && path.startsWith("/shorts/");
    }

    private String normalizeYouTubeVideoUrl(Uri uri) {
        if (!isYouTubeShortsUrl(uri)) return uri.toString();
        String path = uri.getPath();
        String videoId = path == null ? "" : path.substring("/shorts/".length()).split("/")[0];
        if (videoId.isEmpty()) return HOME_URL;
        return "https://m.youtube.com/watch?v=" + Uri.encode(videoId);
    }

    private WebResourceResponse interceptAdvertisingRequest(
            WebView view,
            WebResourceRequest request
    ) {
        Uri uri = request.getUrl();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        for (String hostPart : BLOCKED_HOST_PARTS) {
            if (host.contains(hostPart)) return emptyResponse();
        }
        for (String pathPart : BLOCKED_PATH_PARTS) {
            if (path.contains(pathPart)) return emptyResponse();
        }
        return superRequest(view, request);
    }

    private WebResourceResponse superRequest(WebView view, WebResourceRequest request) {
        return null;
    }

    private void configureMusicButton() {
        float density = getResources().getDisplayMetrics().density;
        musicButton = new Button(this);
        musicButton.setText("♫\nMusic");
        musicButton.setContentDescription("Open YouTube Music");
        musicButton.setAllCaps(false);
        musicButton.setTextColor(Color.WHITE);
        musicButton.setTextSize(12);
        musicButton.setGravity(Gravity.CENTER);
        musicButton.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F0F0F);
        background.setShape(GradientDrawable.RECTANGLE);
        musicButton.setBackground(background);
        musicButton.setElevation(1 * density);
        musicButton.setVisibility(View.GONE);
        musicButton.setOnClickListener(view -> webView.loadUrl(isMusicPage() ? HOME_URL : MUSIC_URL));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round(92 * density),
                Math.round(76 * density)
        );
        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        root.addView(musicButton, params);
    }

    private void updateMusicButton(String url) {
        if (musicButton == null) return;
        Uri uri = Uri.parse(url == null ? "" : url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean youtubePage = host.equals("youtube.com") || host.endsWith(".youtube.com");
        boolean musicPage = host.equals("music.youtube.com");
        boolean visible = youtubePage && customView == null
                && !inAppMiniPlayer && !isInPictureInPictureMode();
        float density = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) musicButton.getLayoutParams();
        GradientDrawable background = new GradientDrawable();
        if (musicPage) {
            musicButton.setText("← YouTube");
            musicButton.setContentDescription("Back to YouTube");
        } else {
            musicButton.setText("♫ Music");
            musicButton.setContentDescription("Open YouTube Music");
        }
        musicButton.setTextSize(13);
        params.width = Math.round(112 * density);
        params.height = Math.round(46 * density);
        params.gravity = Gravity.START | Gravity.BOTTOM;
        params.leftMargin = Math.round(12 * density) + insetLeft;
        params.rightMargin = 0;
        params.bottomMargin = Math.round(88 * density) + insetBottom;
        background.setColor(0xEE272727);
        background.setCornerRadius(Math.round(23 * density));
        background.setStroke(Math.max(1, Math.round(density)), 0xFF555555);
        musicButton.setBackground(background);
        musicButton.setLayoutParams(params);
        musicButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) musicButton.bringToFront();
    }

    private void configureSafeAreas() {
        final float density = getResources().getDisplayMetrics().density;
        final int menuSideMargin = Math.round(16 * density);
        final int menuBottomMargin = Math.round(96 * density);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            insetLeft = bars.left;
            insetTop = bars.top;
            insetRight = bars.right;
            insetBottom = bars.bottom;

            if (inAppMiniPlayer) {
                applyBrowsingWebViewInsets();
                applyPrimaryMiniPlayerLayout();
            } else {
                restorePrimaryWebViewLayout();
            }

            FrameLayout.LayoutParams menuParams =
                    (FrameLayout.LayoutParams) scriptMenu.getLayoutParams();
            menuParams.rightMargin = menuSideMargin + bars.right;
            menuParams.bottomMargin = menuBottomMargin + bars.bottom;
            scriptMenu.setLayoutParams(menuParams);

            updateMusicButton(webView.getUrl());
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void applyBrowsingWebViewInsets() {
        if (browseWebView == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) browseWebView.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.NO_GRAVITY;
        params.setMargins(insetLeft, insetTop, insetRight, insetBottom);
        browseWebView.setLayoutParams(params);
    }

    private void restorePrimaryWebViewLayout() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) webView.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.NO_GRAVITY;
        params.setMargins(insetLeft, insetTop, insetRight, insetBottom);
        webView.setLayoutParams(params);
    }

    private void applyPrimaryMiniPlayerLayout() {
        float density = getResources().getDisplayMetrics().density;
        int availableWidth = root.getWidth() - insetLeft - insetRight;
        if (availableWidth <= 0) availableWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.round(availableWidth * 0.54f);
        int height = Math.round(width * ((float) videoHeight / (float) videoWidth));
        int minHeight = Math.round(width / 2.39f);
        int maxHeight = Math.round(width * 2.39f);
        height = Math.max(minHeight, Math.min(maxHeight, height));
        int miniBottom = Math.round(86 * density) + insetBottom;
        int side = Math.round(10 * density) + insetRight;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) webView.getLayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.END | Gravity.BOTTOM;
        params.rightMargin = side;
        params.bottomMargin = miniBottom;
        params.leftMargin = 0;
        params.topMargin = 0;
        webView.setLayoutParams(params);
        webView.setElevation(10 * density);

        int controlSize = Math.round(38 * density);
        int controlBottom = miniBottom + height - controlSize - Math.round(4 * density);
        FrameLayout.LayoutParams closeParams = (FrameLayout.LayoutParams) miniClose.getLayoutParams();
        closeParams.rightMargin = side;
        closeParams.bottomMargin = controlBottom;
        miniClose.setLayoutParams(closeParams);
        FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams) miniExpand.getLayoutParams();
        expandParams.rightMargin = side + controlSize + Math.round(8 * density);
        expandParams.bottomMargin = controlBottom;
        miniExpand.setLayoutParams(expandParams);
    }

    private void configureMiniPlayerControls() {
        final float density = getResources().getDisplayMetrics().density;
        int size = Math.round(38 * density);
        int bottom = Math.round(171 * density);
        int gap = Math.round(8 * density);
        int side = Math.round(12 * density);

        miniClose = createMiniControl("×");
        miniExpand = createMiniControl("↗");
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(size, size);
        closeParams.gravity = Gravity.END | Gravity.BOTTOM;
        closeParams.rightMargin = side;
        closeParams.bottomMargin = bottom;
        FrameLayout.LayoutParams expandParams = new FrameLayout.LayoutParams(size, size);
        expandParams.gravity = Gravity.END | Gravity.BOTTOM;
        expandParams.rightMargin = side + size + gap;
        expandParams.bottomMargin = bottom;
        root.addView(miniClose, closeParams);
        root.addView(miniExpand, expandParams);
        miniClose.setOnClickListener(view -> closeInAppMiniPlayer(false, true));
        miniExpand.setOnClickListener(view -> closeInAppMiniPlayer(true, false));
    }

    private Button createMiniControl(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setContentDescription(text.equals("×") ? "Close mini player" : "Expand mini player");
        button.setTextColor(Color.WHITE);
        button.setTextSize(19);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6111111);
        background.setShape(GradientDrawable.OVAL);
        button.setBackground(background);
        button.setElevation(100 * getResources().getDisplayMetrics().density);
        button.setTranslationZ(100 * getResources().getDisplayMetrics().density);
        button.setVisibility(View.GONE);
        return button;
    }

    private void showInAppMiniPlayer() {
        if (!videoPlaying || inAppMiniPlayer) return;
        inAppMiniPlayer = true;
        browseWebView = new WebView(this);
        configureMiniBrowser(browseWebView);
        root.addView(browseWebView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        applyBrowsingWebViewInsets();
        applyPrimaryMiniPlayerLayout();
        scriptMenu.setVisibility(View.GONE);
        musicButton.setVisibility(View.GONE);
        showNativeMiniControls();
        playbackHandler.postDelayed(() -> {
            if (inAppMiniPlayer) showNativeMiniControls();
        }, 350);
        playbackPausedByUser = false;
        webView.evaluateJavascript(ENTER_MINI_PLAYER_SCRIPT, null);
        browseWebView.loadUrl(HOME_URL);
        scheduleMiniPlayerResume();
    }

    private void showNativeMiniControls() {
        miniClose.setAlpha(1f);
        miniExpand.setAlpha(1f);
        miniClose.setVisibility(View.VISIBLE);
        miniExpand.setVisibility(View.VISIBLE);
        miniClose.setEnabled(true);
        miniExpand.setEnabled(true);
        root.bringChildToFront(miniClose);
        root.bringChildToFront(miniExpand);
        miniClose.invalidate();
        miniExpand.invalidate();
    }

    private void closeInAppMiniPlayer(boolean expand, boolean pause) {
        if (!inAppMiniPlayer) return;
        String browsingUrl = browseWebView == null ? HOME_URL : browseWebView.getUrl();
        String script = "window.__ytCleanExitMini?.(" + expand + "," + pause + ");";
        inAppMiniPlayer = false;
        playbackHandler.removeCallbacks(miniPlaybackKeepAlive);
        miniClose.setVisibility(View.GONE);
        miniExpand.setVisibility(View.GONE);
        webView.evaluateJavascript(script, ignored -> runOnUiThread(() -> {
            finishMiniPlayerUiExit();
            if (expand) {
                webView.evaluateJavascript(
                        "(() => {const v=[...document.querySelectorAll('video')].find(v=>!v.ended);if(v){v.scrollIntoView({block:'start'});v.play().catch(()=>{});}})();",
                        null
                );
            } else if (pause) {
                String destination = browsingUrl == null ? HOME_URL : browsingUrl;
                playbackHandler.postDelayed(() -> webView.loadUrl(destination), 100);
            }
        }));
    }

    private void openVideoFromMiniBrowser(String url) {
        if (!inAppMiniPlayer || !isYouTubeVideoUrl(Uri.parse(url))) return;
        inAppMiniPlayer = false;
        playbackHandler.removeCallbacks(miniPlaybackKeepAlive);
        miniClose.setVisibility(View.GONE);
        miniExpand.setVisibility(View.GONE);
        webView.evaluateJavascript("window.__ytCleanExitMini?.(false,true);", ignored ->
                runOnUiThread(() -> {
                    finishMiniPlayerUiExit();
                    playbackHandler.postDelayed(() -> webView.loadUrl(url), 100);
                })
        );
    }

    private void finishMiniPlayerUiExit() {
        destroyMiniBrowser();
        restorePrimaryWebViewLayout();
        webView.setElevation(0);
        webView.setVisibility(View.VISIBLE);
        webView.requestLayout();
        webView.invalidate();
        scriptMenu.setVisibility(View.GONE);
        updateMusicButton(webView.getUrl());
        scriptMenu.bringToFront();
    }

    private void destroyMiniBrowser() {
        if (browseWebView == null) return;
        root.removeView(browseWebView);
        browseWebView.stopLoading();
        browseWebView.setWebChromeClient(null);
        browseWebView.setWebViewClient(null);
        browseWebView.removeJavascriptInterface("AndroidBrowser");
        browseWebView.destroy();
        browseWebView = null;
    }

    private void scheduleMiniPlayerResume() {
        if (!inAppMiniPlayer || playbackPausedByUser) return;
        playbackHandler.removeCallbacks(miniPlaybackKeepAlive);
        playbackHandler.post(miniPlaybackKeepAlive);
    }

    private void enterImmersiveVideo() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        controller.hide(WindowInsets.Type.systemBars());
    }

    private void leaveImmersiveVideo() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) controller.show(WindowInsets.Type.systemBars());
        root.requestApplyInsets();
    }

    private void injectBlocker(WebView view, String destinationUrl) {
        blockerScript = loadConfiguredScript();
        if (!isMusicUrl(destinationUrl)
                && blockerScript != null && !blockerScript.trim().isEmpty()) {
            view.evaluateJavascript(blockerScript, null);
        }
        if (!isMusicUrl(destinationUrl)
                && productiveFilterScript != null && !productiveFilterScript.trim().isEmpty()) {
            view.evaluateJavascript(productiveFilterScript, null);
        }
        view.evaluateJavascript(MEDIA_MONITOR_SCRIPT, null);
        if (!isMusicUrl(destinationUrl)) {
            view.evaluateJavascript(SHORTS_POLICY_SCRIPT, null);
        }
        if (isMusicUrl(destinationUrl)
                && musicBlockerScript != null && !musicBlockerScript.trim().isEmpty()) {
            view.evaluateJavascript(musicBlockerScript, null);
        }
        view.evaluateJavascript(BACKGROUND_PLAYBACK_GUARD_SCRIPT, null);
        view.evaluateJavascript(DARK_THEME_SCRIPT, null);
    }

    private void injectBlockerOnly(WebView view, String destinationUrl) {
        blockerScript = loadConfiguredScript();
        if (!isMusicUrl(destinationUrl)
                && blockerScript != null && !blockerScript.trim().isEmpty()) {
            view.evaluateJavascript(blockerScript, null);
        }
        if (!isMusicUrl(destinationUrl)
                && productiveFilterScript != null && !productiveFilterScript.trim().isEmpty()) {
            view.evaluateJavascript(productiveFilterScript, null);
        }
        if (!isMusicUrl(destinationUrl)) {
            view.evaluateJavascript(SHORTS_POLICY_SCRIPT, null);
        }
        if (isMusicUrl(destinationUrl)
                && musicBlockerScript != null && !musicBlockerScript.trim().isEmpty()) {
            view.evaluateJavascript(musicBlockerScript, null);
        }
        view.evaluateJavascript(BACKGROUND_PLAYBACK_GUARD_SCRIPT, null);
        view.evaluateJavascript(DARK_THEME_SCRIPT, null);
    }

    private final class MediaBridge {
        @JavascriptInterface
        public void onPlaybackState(boolean playing, int width, int height, String title) {
            runOnUiThread(() -> updateNativePlayback(playing, width, height, title));
        }

        @JavascriptInterface
        public void onMiniPlayerRequested() {
            runOnUiThread(() -> showInAppMiniPlayer());
        }

        @JavascriptInterface
        public void onMiniPlayerExpandRequested() {
            runOnUiThread(() -> closeInAppMiniPlayer(true, false));
        }
    }

    private void updateNativePlayback(boolean playing, int width, int height, String title) {
        if (!playing && resumePlaybackForPip && isInPictureInPictureMode()) {
            schedulePictureInPictureResume();
            return;
        }
        if (!playing && inAppMiniPlayer && !playbackPausedByUser) {
            scheduleMiniPlayerResume();
            return;
        }
        if (!playing && appInBackground && backgroundPlayback && !playbackPausedByUser) {
            scheduleBackgroundPlaybackResume();
            return;
        }
        videoPlaying = playing;
        if (playing) lastPlayingAt = SystemClock.elapsedRealtime();
        videoWidth = Math.max(1, width);
        videoHeight = Math.max(1, height);
        updatePictureInPictureParams();

        if (playing) {
            ensureNotificationPermission();
            Intent service = MediaPlaybackService.updateIntent(this, title, true);
            startForegroundService(service);
            mediaServiceStarted = true;
        } else if (mediaServiceStarted) {
            startService(MediaPlaybackService.updateIntent(this, title, false));
        }
    }

    private void ensureNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void updatePictureInPictureParams() {
        double ratio = (double) videoWidth / (double) videoHeight;
        if (ratio < (1.0 / 2.39) || ratio > 2.39) {
            videoWidth = 16;
            videoHeight = 9;
        }
        Rect source = new Rect();
        webView.getGlobalVisibleRect(source);
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(videoWidth, videoHeight))
                .setSourceRectHint(source)
                .setAutoEnterEnabled(videoPlaying && !isMusicPage())
                .setSeamlessResizeEnabled(true)
                .build();
        setPictureInPictureParams(params);
    }

    private boolean isMusicPage() {
        String url = webView == null ? null : webView.getUrl();
        return isMusicUrl(url);
    }

    private boolean isMusicUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("music.youtube.com");
    }

    private void preparePictureInPictureUi(boolean entering) {
        if (entering) {
            if (inAppMiniPlayer) {
                webView.evaluateJavascript("window.__ytCleanExitMini?.(false,false);", null);
                inAppMiniPlayer = false;
                playbackHandler.removeCallbacks(miniPlaybackKeepAlive);
                destroyMiniBrowser();
                restorePrimaryWebViewLayout();
                webView.setElevation(0);
                miniClose.setVisibility(View.GONE);
                miniExpand.setVisibility(View.GONE);
            }
            scriptMenu.setVisibility(View.GONE);
            musicButton.setVisibility(View.GONE);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) webView.getLayoutParams();
            params.setMargins(0, 0, 0, 0);
            webView.setLayoutParams(params);
            webView.evaluateJavascript(ENTER_PIP_SCRIPT, null);
            schedulePictureInPictureResume();
        } else {
            resumePlaybackForPip = false;
            playbackHandler.removeCallbacks(pipPlaybackKeepAlive);
            webView.evaluateJavascript(EXIT_PIP_SCRIPT, null);
            scriptMenu.setVisibility(View.GONE);
            updateMusicButton(webView.getUrl());
            root.requestApplyInsets();
        }
    }

    @Override
    public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {
        super.onPictureInPictureUiStateChanged(pipState);
        if (pipState.isTransitioningToPip()) preparePictureInPictureUi(true);
    }

    @Override
    public void onPictureInPictureModeChanged(
            boolean isInPictureInPictureMode,
            Configuration newConfig
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        preparePictureInPictureUi(isInPictureInPictureMode);
    }

    @Override
    protected void onUserLeaveHint() {
        if (playbackLikelyActive() && !playbackPausedByUser) {
            playbackPausedByUser = false;
            enterBackgroundPlaybackIfNeeded();
            if (!isMusicPage()) {
                webView.evaluateJavascript(RESUME_PLAYBACK_SCRIPT, null);
                resumePlaybackForPip = true;
            }
        }
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        enterBackgroundPlaybackIfNeeded();
        super.onPause();
    }

    @Override
    protected void onStop() {
        CookieManager.getInstance().flush();
        enterBackgroundPlaybackIfNeeded();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInBackground = false;
        backgroundPlayback = false;
        playbackHandler.removeCallbacks(backgroundPlaybackKeepAlive);
        releaseBackgroundWakeLock();
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__shelbyBackgroundPlayback=false;window.__shelbyAllowBackgroundPause=false;",
                    null
            );
        }
    }

    private void schedulePictureInPictureResume() {
        if ((!resumePlaybackForPip && !videoPlaying) || playbackPausedByUser) return;
        resumePlaybackForPip = true;
        playbackHandler.removeCallbacks(pipPlaybackKeepAlive);
        playbackHandler.post(pipPlaybackKeepAlive);
    }

    private boolean playbackLikelyActive() {
        return videoPlaying
                || SystemClock.elapsedRealtime() - lastPlayingAt < 8_000L;
    }

    private void enterBackgroundPlaybackIfNeeded() {
        if (!playbackLikelyActive() || playbackPausedByUser || webView == null) return;
        appInBackground = true;
        backgroundPlayback = true;
        acquireBackgroundWakeLock();
        webView.evaluateJavascript(ENTER_BACKGROUND_PLAYBACK_SCRIPT, null);
        scheduleBackgroundPlaybackResume();
    }

    private void scheduleBackgroundPlaybackResume() {
        if (!appInBackground || !backgroundPlayback || playbackPausedByUser) return;
        playbackHandler.removeCallbacks(backgroundPlaybackKeepAlive);
        playbackHandler.post(backgroundPlaybackKeepAlive);
    }

    private void acquireBackgroundWakeLock() {
        if (backgroundWakeLock == null) {
            PowerManager powerManager = getSystemService(PowerManager.class);
            backgroundWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":background-playback"
            );
            backgroundWakeLock.setReferenceCounted(false);
        }
        if (!backgroundWakeLock.isHeld()) {
            backgroundWakeLock.acquire(4 * 60 * 60 * 1000L);
        }
    }

    private void releaseBackgroundWakeLock() {
        if (backgroundWakeLock != null && backgroundWakeLock.isHeld()) {
            backgroundWakeLock.release();
        }
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0])
        );
    }

    private String readAsset(String name) {
        try (InputStream input = getAssets().open(name)) {
            return readStream(input);
        } catch (IOException error) {
            return "";
        }
    }

    private String loadConfiguredScript() {
        return readAsset("blocker.js");
    }

    private String readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_SCRIPT_BYTES) throw new IOException("Script is larger than 2 MB");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void showScriptMenu() {
        String customUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_SCRIPT_URI, null);
        String source = customUri == null ? "Bundled script active" : "Custom file active";
        String[] actions = {
                "Choose script file",
                "Reload script and YouTube",
                "Use bundled script",
                "Cancel"
        };

        new AlertDialog.Builder(this)
                .setTitle("Blocker settings\n" + source)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) chooseScriptFile();
                    if (which == 1) reloadScriptAndPage();
                    if (which == 2) useBundledScript();
                    dialog.dismiss();
                })
                .show();
    }

    private void chooseScriptFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/javascript", "text/javascript", "text/plain"
        });
        startActivityForResult(intent, PICK_SCRIPT_REQUEST);
    }

    private void reloadScriptAndPage() {
        blockerScript = loadConfiguredScript();
        webView.reload();
        Toast.makeText(this, "Script reloaded", Toast.LENGTH_SHORT).show();
    }

    private void useBundledScript() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(PREF_SCRIPT_URI)
                .apply();
        blockerScript = readAsset("blocker.js");
        webView.reload();
        Toast.makeText(this, "Bundled script restored", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SCRIPT_REQUEST || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null || readStream(input).trim().isEmpty()) {
                    Toast.makeText(this, "That script file is empty", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_SCRIPT_URI, uri.toString())
                    .apply();
            reloadScriptAndPage();
            Toast.makeText(this, "Custom script selected", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not read that script file", Toast.LENGTH_LONG).show();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        scriptMenu.setVisibility(View.GONE);
        updateMusicButton(webView.getUrl());
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        leaveImmersiveVideo();
    }

    private void handleBackNavigation() {
        if (customView != null) {
            hideCustomView();
        } else if (inAppMiniPlayer) {
            if (browseWebView != null && browseWebView.canGoBack()) {
                browseWebView.goBack();
            } else {
                closeInAppMiniPlayer(true, false);
            }
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        playbackHandler.removeCallbacksAndMessages(null);
        releaseBackgroundWakeLock();
        destroyMiniBrowser();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (receiverRegistered) {
            unregisterReceiver(mediaCommandReceiver);
            receiverRegistered = false;
        }
        if (isFinishing()) {
            stopService(new Intent(this, MediaPlaybackService.class));
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
