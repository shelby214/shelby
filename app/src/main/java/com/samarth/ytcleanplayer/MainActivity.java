package com.samarth.ytcleanplayer;

import android.Manifest;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.PathInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Rational;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import org.json.JSONObject;
import java.util.Collections;

public class MainActivity extends Activity implements PlaybackSession.Listener {
    private PlaybackSession session;
    private WebView webView, browser;
    private FrameLayout root;
    private LinearLayout sectionPill;
    private Button sectionToggle, miniClose, miniExpand, miniPlay;
    private Button miniDismiss;
    private float miniX = Float.NaN, miniY = Float.NaN;
    private float miniTouchX, miniTouchY, miniStartX, miniStartY;
    private boolean miniDragging;
    private ProgressBar progress;
    private Button fullscreenSettings;
    private View fullScreen;
    private int orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private int left, top, right, bottom;
    private boolean mini, pipPrepared, notificationAsked;
    private ValueAnimator miniAnimation;
    private final Rect videoBounds = new Rect();
    private final OnBackInvokedCallback back = this::navigateBack;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) session.setBackground(true);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0f0f0f);
        setContentView(root);
        PlaybackSession.prepareTask(this, getTaskId());
        session = PlaybackSession.obtain(this);
        buildSectionPill();
        buildMiniControls();
        webView = session.attach(this, this);
        webView.setOnTouchListener(this::onMiniTouch);
        root.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = bars.left; top = bars.top; right = bars.right;
            bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            layoutShell();
            return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (mini && (r-l != or-ol || b-t != ob-ot)) layoutShell();
            updatePipParams();
        });
        root.requestApplyInsets();
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, float radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private Button button(String label, String description) {
        Button button = new Button(this);
        button.setText(label); button.setContentDescription(description);
        button.setTextColor(0xffeaeaea); button.setTextSize(13); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x337f8fff), shape(0xff202024, 20), null));
        return button;
    }
    private void buildSectionPill() {
        sectionPill = new LinearLayout(this);
        sectionPill.setGravity(Gravity.CENTER_VERTICAL);
        sectionPill.setElevation(dp(6));
        sectionPill.setBackground(shape(0xee272727,24));
        sectionToggle = button("← YouTube", "Switch to YouTube");
        sectionToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        sectionToggle.setOnClickListener(v -> switchSection(!PlaybackSession.isMusic(session.url())));
        sectionPill.addView(sectionToggle,new LinearLayout.LayoutParams(dp(112),dp(48)));
        Button settings = button("⋮", "Playback settings");
        settings.setTextSize(24);
        settings.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        settings.setOnClickListener(v -> showSettings());
        sectionPill.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(sectionPill,new FrameLayout.LayoutParams(-2,dp(48)));
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(0xffb4a1ff));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff202024));
        progress.setVisibility(View.GONE);
        root.addView(progress,new FrameLayout.LayoutParams(-1,dp(2)));
    }
    private void switchSection(boolean music) {
        if (mini) {
            if (miniAnimation != null) miniAnimation.cancel();
            mini = false;
            for (Button button : new Button[]{miniPlay, miniExpand, miniClose}) button.setVisibility(View.GONE);
            session.evaluate("window.__shelbySurface?.exit();");
            webView.setClipToOutline(false); webView.setElevation(0);
            destroyBrowser(); layoutShell();
        }
        session.switchSection(music);
    }
    private AlertDialog settingsSheet(String title, String[] labels, android.content.DialogInterface.OnClickListener click) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setItems(labels, click)
                .setNegativeButton("Close", null).create();
        dialog.show();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        dialog.getWindow().setBackgroundDrawable(shape(0xff202020, 20));
        dialog.getWindow().setDimAmount(.35f);
        dialog.getWindow().setGravity(Gravity.BOTTOM | (landscape ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL));
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(420)), -2);
        return dialog;
    }
    private void showSettings() {
        webView.evaluateJavascript("window.__shelbyPlayback?.playbackOptions() || {}", raw -> {
            if (isFinishing() || isDestroyed()) return;
            JSONObject options;
            try { options = new JSONObject(raw); } catch (Exception ignored) { options = new JSONObject(); }
            final JSONObject current = options;
            String speed = options.optDouble("speed", 1) + "×";
            settingsSheet("Playback settings", new String[]{
                    "Playback speed · " + speed,
                    "Quality · " + qualityLabel(options.optString("quality", "auto")),
                    "Audio quality & details",
                    "SponsorBlock · " + (session.sponsorEnabled() ? "On" : "Off")}, (dialog, which) -> {
                if (which == 0) {
                    String[] labels = {"0.25×", "0.5×", "0.75×", "Normal · 1×", "1.25×", "1.5×", "1.75×", "2×"};
                    double[] speeds = {.25,.5,.75,1,1.25,1.5,1.75,2};
                    settingsSheet("Playback speed", labels, (d, index) ->
                            applyPlayerSetting("setSpeed(" + speeds[index] + ")"));
                } else if (which == 1) {
                    org.json.JSONArray qualities = current.optJSONArray("qualities");
                    if (qualities == null || qualities.length() == 0) {
                        Toast.makeText(this,"Quality options appear once the video loads",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] labels = new String[qualities.length()];
                    for (int i=0;i<labels.length;i++) labels[i]=qualityLabel(qualities.optString(i));
                    settingsSheet("Video quality", labels, (d,index) ->
                            applyPlayerSetting("setQuality(" + JSONObject.quote(qualities.optString(index)) + ")"));
                } else if (which == 2) {
                    showAudioDetails();
                } else {
                    settingsSheet("SponsorBlock", new String[]{"On · skip sponsored segments", "Off", "About SponsorBlock"}, (d,index) -> {
                        if (index < 2) session.setSponsorEnabled(index == 0);
                        else new AlertDialog.Builder(this).setTitle("SponsorBlock")
                                .setMessage("Skips community-submitted sponsor segments. Undo appears after each skip. When enabled, video IDs are sent to sponsor.ajay.app. Videos without submitted segments play normally.")
                                .setPositiveButton("OK",null).show();
                    });
                }
            });
        });
    }
    private void showAudioDetails() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView details = new TextView(this);
        details.setTextColor(0xffeeeeee);
        details.setTextSize(15);
        details.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        details.setLinkTextColor(0xffb4a1ff);
        details.setLineSpacing(dp(4), 1);
        details.setPadding(dp(24), dp(12), dp(24), dp(16));
        details.setText("Reading the current audio stream…");
        scroll.addView(details);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Audio quality & details")
                .setView(scroll).setPositiveButton("Done", null)
                .setNeutralButton("Phone sound settings", (d, which) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)); }
                    catch (Exception ignored) { Toast.makeText(this,"Phone sound settings are unavailable",Toast.LENGTH_SHORT).show(); }
                }).create();
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(shape(0xff202020, 20));
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(440)),
                Math.min(getResources().getDisplayMetrics().heightPixels - dp(60), dp(650)));
        android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                webView.evaluateJavascript("window.__shelbyPlayback?.audioDetails() || {}", raw -> {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                    try { details.setText(formatAudioDetails(new JSONObject(raw))); }
                    catch (Exception ignored) { details.setText("Audio details are unavailable. Start a track and try again."); }
                    refresh.postDelayed(this, 2000);
                });
            }
        };
        dialog.setOnDismissListener(d -> refresh.removeCallbacksAndMessages(null));
        refresh.post(update);
    }
    private String formatAudioDetails(JSONObject data) {
        StringBuilder text = new StringBuilder();
        String title = data.optString("title");
        if (!title.isEmpty()) text.append(title).append("\n").append(data.optString("artist")).append("\n\n");
        text.append("NOW PLAYING\n").append(data.optString("status", "Unavailable")).append("\n");
        JSONObject stream = data.optJSONObject("active");
        String codec = stream == null ? data.optString("codecDisplay", "Unavailable") : stream.optString("codec", "Unavailable");
        text.append("Codec: ").append("mp4a.40.2".equals(codec) ? "AAC-LC" : codec).append("\n");
        if (stream != null) {
            text.append("Container: ").append(stream.optString("container")).append("\n");
            text.append("Average bitrate: ").append(audioNumber(stream,"averageBitrate",1000," kbps")).append("\n");
            text.append("Advertised bitrate: ").append(audioNumber(stream,"bitrate",1000," kbps")).append("\n");
            text.append("Sample rate: ").append(audioNumber(stream,"sampleRate",1000," kHz")).append("\n");
            int channels = stream.optInt("channels",0);
            text.append("Channels: ").append(channels==2?"Stereo · 2":channels==1?"Mono · 1":channels>0?String.valueOf(channels):"Unavailable").append("\n");
            text.append("Stream quality: ").append(stream.optString("quality","Unavailable")).append("\n");
            text.append("Format ID: ").append(stream.optInt("formatId")).append("\n");
        } else text.append("Bitrate / sample rate: unavailable\n");
        text.append(data.optString("source")).append("\n\nPLAYBACK\n");
        text.append("Speed: ").append(data.optDouble("speed",1)).append("×\n");
        text.append("Player volume: ").append(data.optBoolean("muted")?"Muted":audioNumber(data,"volumePercent",1,"%" )).append("\n");
        android.media.AudioManager audio = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        text.append("Phone media volume: ").append(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                .append(" / ").append(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).append("\n");
        text.append("Buffered ahead: ").append(audioNumber(data,"bufferedSeconds",1," s")).append("\n");
        if (!data.isNull("networkEstimate")) text.append("Network estimate: ").append(data.optString("networkEstimate")).append(" (not audio bitrate)\n");
        if (!data.isNull("normalization")) text.append("Provider volume / normalization: ").append(data.optString("normalization")).append("\n");
        if (!data.isNull("loudnessLkfs")) text.append("Track loudness: ").append(audioNumber(data,"loudnessLkfs",1," LKFS")).append("\n");
        if (!data.isNull("targetLkfs")) text.append("Provider loudness target: ").append(audioNumber(data,"targetLkfs",1," LKFS")).append("\n");
        org.json.JSONArray formats = data.optJSONArray("formats");
        text.append("\nAVAILABLE AUDIO STREAMS\n");
        if (formats == null || formats.length()==0) text.append("Not exposed by this player.\n");
        else for (int i=0;i<formats.length();i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String name = format.optString("codec", "Unknown");
            text.append("• ").append("mp4a.40.2".equals(name)?"AAC-LC":name).append(" · ")
                    .append(audioNumber(format,"averageBitrate",1000," kbps average")).append(" · ")
                    .append(audioNumber(format,"sampleRate",1000," kHz")).append("\n");
        }
        text.append("\nGETTING THE BEST SOUND\n");
        text.append(data.optBoolean("highQualityAvailable")?"A High-quality audio stream is exposed for this track. Check the active stream above to see what is actually playing.\n":
                "No High-quality audio stream is exposed for this track right now. This does not establish your subscription status.\n");
        text.append("YouTube Music Premium offers up to 256 kbps AAC/Opus. Choose High in YouTube Music’s audio-quality settings where available; access depends on the account and track.\n\n")
                .append("Video resolution does not guarantee a higher audio bitrate. Variable-bitrate streams change with the music; the average above comes from the current track’s metadata.\n\n")
                .append("Shelby plays the source audio without extra sound processing. Equalizers change the tonal balance, but cannot recover details lost during compression. Phone sound settings may offer device-specific effects.\n\n")
                .append("Details refresh every 2 seconds while this panel is open. Unavailable means the player did not expose verified data.\n\nYouTube Music audio-quality guide:\nhttps://support.google.com/youtubemusic/answer/9076559");
        return text.toString();
    }
    private static String audioNumber(JSONObject object, String key, double divisor, String suffix) {
        if (object.isNull(key) || !object.has(key)) return "Unavailable";
        double value = object.optDouble(key, Double.NaN) / divisor;
        return Double.isFinite(value) ? String.format(java.util.Locale.US, "%.1f", value) + suffix : "Unavailable";
    }
    private void applyPlayerSetting(String call) {
        webView.evaluateJavascript("window.__shelbyPlayback?." + call, result -> {
            if (!"true".equals(result)) Toast.makeText(this,"This setting is unavailable for the current video",Toast.LENGTH_SHORT).show();
        });
    }
    private static String qualityLabel(String quality) {
        return switch (quality) {
            case "tiny" -> "144p"; case "small" -> "240p"; case "medium" -> "360p";
            case "large" -> "480p"; case "hd720" -> "720p"; case "hd1080" -> "1080p";
            case "hd1440" -> "1440p"; case "hd2160" -> "2160p · 4K";
            case "highres" -> "Highest available"; case "auto", "default" -> "Auto";
            default -> quality;
        };
    }
    private void layoutShell() {
        if (webView == null) return;
        boolean pip = pipPrepared || isInPictureInPictureMode();
        FrameLayout.LayoutParams pill = (FrameLayout.LayoutParams) sectionPill.getLayoutParams();
        pill.gravity=Gravity.BOTTOM|Gravity.LEFT;
        pill.setMargins(left+dp(12),0,right+dp(12),bottom+dp(88));
        sectionPill.setLayoutParams(pill);
        FrameLayout.LayoutParams loading = (FrameLayout.LayoutParams) progress.getLayoutParams();
        loading.setMargins(left,top,right,0); progress.setLayoutParams(loading);
        sectionPill.setVisibility(pip || fullScreen != null || mini ? View.GONE : View.VISIBLE);
        if (pip || fullScreen != null) progress.setVisibility(View.GONE);
        if (browser != null) browser.setLayoutParams(contentLayout());
        if (mini) {
            if (miniAnimation == null || !miniAnimation.isRunning()) webView.setLayoutParams(miniLayout());
            layoutMiniControls();
        } else {
            miniDismiss.setVisibility(View.GONE); miniDragging=false;
            webView.setLayoutParams(pip ? new FrameLayout.LayoutParams(-1,-1) : contentLayout());
        }
        sectionPill.bringToFront(); progress.bringToFront();
    }
    private FrameLayout.LayoutParams contentLayout() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1,-1);
        params.setMargins(left,top,right,bottom);
        return params;
    }
    private FrameLayout.LayoutParams miniLayout() {
        int available = Math.max(dp(240),root.getWidth()-left-right);
        int width = Math.min(dp(240),Math.round(available*.58f));
        double ratio = aspectRatio();
        int height = Math.min(dp(280),Math.max(dp(110),(int)(width/ratio)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width,height);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        float x = Float.isNaN(miniX) ? root.getWidth()-right-width-dp(12) : miniX;
        float y = Float.isNaN(miniY) ? root.getHeight()-bottom-height-dp(78) : miniY;
        params.leftMargin = Math.round(Math.max(left,Math.min(x,root.getWidth()-right-width)));
        params.topMargin = Math.round(Math.max(top,Math.min(y,root.getHeight()-bottom-height)));
        return params;
    }
    private void buildMiniControls() {
        miniClose=button("×","Close mini player");
        miniExpand=button("↗","Expand mini player");
        miniPlay=button("Ⅱ","Pause playback");
        for (Button control : new Button[]{miniClose,miniExpand,miniPlay}) {
            // The platform Button animator otherwise lowers these below the raised player.
            control.setStateListAnimator(null);
            control.setTextSize(20); control.setPadding(0,0,0,0); control.setElevation(dp(14));
            control.setVisibility(View.GONE);
            control.setOnTouchListener(this::onMiniTouch);
            root.addView(control,new FrameLayout.LayoutParams(dp(48),dp(48)));
        }
        miniDismiss=button("×","Dismiss floating player");
        miniDismiss.setStateListAnimator(null);
        miniDismiss.setTextSize(32); miniDismiss.setElevation(dp(16));
        miniDismiss.setBackground(shape(0xffa52b38,32));
        miniDismiss.setVisibility(View.GONE);
        root.addView(miniDismiss,new FrameLayout.LayoutParams(dp(64),dp(64)));
        miniDismiss.setOnClickListener(v -> dismissMini());
        miniClose.setOnClickListener(v -> dismissMini());
        miniExpand.setOnClickListener(v -> finishMini(true));
        miniPlay.setOnClickListener(v -> session.command(session.isPlaying()?"pause":"play",0));
    }
    private void dismissMini() {
        if (!mini) return;
        session.command(MediaPlaybackService.COMMAND_STOP,0);
        finishMini(false);
    }
    private boolean onMiniTouch(View view, MotionEvent event) {
        if (!mini) return false;
        if (miniAnimation != null && miniAnimation.isRunning()) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                miniTouchX=event.getRawX(); miniTouchY=event.getRawY();
                FrameLayout.LayoutParams start=miniLayout();
                miniStartX=start.leftMargin; miniStartY=start.topMargin;
                miniDragging=false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx=event.getRawX()-miniTouchX, dy=event.getRawY()-miniTouchY;
                if (!miniDragging && Math.hypot(dx,dy)>ViewConfiguration.get(this).getScaledTouchSlop()) {
                    miniDragging=true;
                    // Cancel the original click only after this gesture becomes a drag.
                    MotionEvent cancel=MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.onTouchEvent(cancel);
                    cancel.recycle();
                    view.setPressed(false);
                    FrameLayout.LayoutParams target=new FrameLayout.LayoutParams(dp(64),dp(64));
                    target.leftMargin=(root.getWidth()-dp(64))/2;
                    target.topMargin=root.getHeight()-bottom-dp(80);
                    miniDismiss.setLayoutParams(target);
                    miniDismiss.setVisibility(View.VISIBLE);
                    miniDismiss.bringToFront();
                }
                if (miniDragging) {
                    miniX=miniStartX+dx; miniY=miniStartY+dy;
                    FrameLayout.LayoutParams position=miniLayout();
                    webView.setLayoutParams(position);
                    layoutMiniControls();
                    boolean over=overMiniDismiss(event);
                    miniDismiss.setScaleX(over?1.15f:1f); miniDismiss.setScaleY(over?1.15f:1f);
                }
                return miniDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean dragged=miniDragging;
                boolean dismiss=dragged && event.getActionMasked()==MotionEvent.ACTION_UP && overMiniDismiss(event);
                miniDragging=false; miniDismiss.setVisibility(View.GONE);
                miniDismiss.setScaleX(1); miniDismiss.setScaleY(1);
                if (dragged) {
                    view.setPressed(false);
                    FrameLayout.LayoutParams position=miniLayout();
                    miniX=position.leftMargin; miniY=position.topMargin;
                }
                if (dismiss) dismissMini();
                return dragged;
            default: return miniDragging;
        }
    }
    private boolean overMiniDismiss(MotionEvent event) {
        int[] location=new int[2]; miniDismiss.getLocationOnScreen(location);
        int padding=dp(16);
        return event.getRawX()>=location[0]-padding && event.getRawX()<=location[0]+miniDismiss.getWidth()+padding
                && event.getRawY()>=location[1]-padding && event.getRawY()<=location[1]+miniDismiss.getHeight()+padding;
    }
    private void layoutMiniControls() {
        if (!mini) return;
        FrameLayout.LayoutParams video = miniLayout();
        Button[] controls = {miniPlay,miniExpand,miniClose};
        for (int i=0;i<controls.length;i++) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48),dp(48));
            p.leftMargin=video.leftMargin+video.width-dp(48)*(3-i);
            p.topMargin=video.topMargin+video.height-dp(48);
            controls[i].setLayoutParams(p); controls[i].bringToFront();
        }
    }
    @Override public void onMiniRequested(boolean expand) {
        if (expand) finishMini(true); else showMini();
    }
    private void showMini() {
        if (mini || pipPrepared || !session.isPlaying() || PlaybackSession.isMusic(session.url())) return;
        hideFullScreen();
        Rect from = actualVideoBounds();
        mini=true;
        sectionPill.setVisibility(View.GONE);
        browser = new WebView(this);
        PlaybackSession.configureSettings(browser,true);
        session.installEarlyScripts(browser,true);
        browser.addJavascriptInterface(new BrowserBridge(),"AndroidBrowser");
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view,String url) {
                session.inject(view,url,true);
                view.evaluateJavascript("(() => {if(window.__shelbyBrowser)return;window.__shelbyBrowser=true;window.addEventListener('click',e=>{const a=e.target.closest?.('a[href]');if(!a)return;const u=new URL(a.href,location.href);if((u.hostname==='youtube.com'||u.hostname.endsWith('.youtube.com'))&&u.pathname==='/watch'){e.preventDefault();e.stopImmediatePropagation();window.AndroidBrowser.openVideo(u.href);}},true);})();",null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                String url=request.getUrl().toString();
                if(request.isForMainFrame()&&PlaybackSession.isVideo(url)){openFromBrowser(url);return true;}
                return !PlaybackSession.trusted(url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return PlaybackSession.blocked(request.getUrl());}
            @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){destroyBrowser();return true;}
        });
        root.addView(browser,0,contentLayout());
        browser.loadUrl(PlaybackSession.HOME);
        session.evaluate("window.__shelbySurface?.enter('mini');");
        webView.setElevation(dp(12));
        webView.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(16));}});
        webView.setClipToOutline(true);
        FrameLayout.LayoutParams target=miniLayout();
        animatePlayer(from,new Rect(target.leftMargin,target.topMargin,target.leftMargin+target.width,target.topMargin+target.height),() -> {
            for(Button button:new Button[]{miniPlay,miniExpand,miniClose}){button.setAlpha(0);button.setVisibility(View.VISIBLE);button.animate().alpha(1).setDuration(140).start();}
            layoutMiniControls();
        });
    }
    private void animatePlayer(Rect start,Rect end,Runnable finished) {
        if(miniAnimation!=null)miniAnimation.cancel();
        miniAnimation=ValueAnimator.ofFloat(0,1); miniAnimation.setDuration(280);
        miniAnimation.setInterpolator(new PathInterpolator(.2f,0,0,1));
        miniAnimation.addUpdateListener(a->{
            float t=(float)a.getAnimatedValue();
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.round(start.width()+(end.width()-start.width())*t),Math.round(start.height()+(end.height()-start.height())*t));
            p.leftMargin=Math.round(start.left+(end.left-start.left)*t);p.topMargin=Math.round(start.top+(end.top-start.top)*t);
            webView.setLayoutParams(p);
        });
        miniAnimation.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) { if (!cancelled) finished.run(); }
        });
        miniAnimation.start();
    }
    private void finishMini(boolean expand) {
        if(!mini)return;
        miniDismiss.setVisibility(View.GONE); miniDragging=false;
        if(miniAnimation!=null)miniAnimation.cancel();
        String browsing=browser==null?PlaybackSession.HOME:browser.getUrl();
        Rect from=new Rect(webView.getLeft(),webView.getTop(),webView.getRight(),webView.getBottom());
        mini=false;
        for(Button button:new Button[]{miniPlay,miniExpand,miniClose})button.setVisibility(View.GONE);
        Rect end=new Rect(left,top,root.getWidth()-right,root.getHeight()-bottom);
        animatePlayer(from,end,()->{
            session.evaluate("window.__shelbySurface?.exit();");
            webView.setClipToOutline(false);webView.setElevation(0);
            destroyBrowser();layoutShell();
            if(!expand)session.open(browsing==null?PlaybackSession.HOME:browsing);
        });
    }
    private void openFromBrowser(String url) {
        if(!mini)return;
        mini=false;
        if(miniAnimation!=null)miniAnimation.cancel();
        for(Button button:new Button[]{miniPlay,miniExpand,miniClose})button.setVisibility(View.GONE);
        session.evaluate("window.__shelbySurface?.exit();");
        webView.setClipToOutline(false);webView.setElevation(0);
        destroyBrowser();layoutShell();session.open(PlaybackSession.canonical(Uri.parse(url)));
    }
    private final class BrowserBridge {
        @JavascriptInterface public void openVideo(String url){if(PlaybackSession.isVideo(url))runOnUiThread(()->openFromBrowser(url));}
    }
    private void destroyBrowser(){
        if(browser==null)return;
        root.removeView(browser);browser.stopLoading();browser.removeJavascriptInterface("AndroidBrowser");
        browser.setWebViewClient(null);browser.destroy();browser=null;
    }

    @Override public void onPlaybackChanged(JSONObject state) {
        if(webView==null)return;
        JSONObject rect=state.optJSONObject("rect");
        if(rect!=null&&!pipPrepared&&!mini) {
            float scale=getResources().getDisplayMetrics().density;
            videoBounds.set(Math.round((float)rect.optDouble("x")*scale),Math.round((float)rect.optDouble("y")*scale),
                    Math.round((float)(rect.optDouble("x")+rect.optDouble("width"))*scale),Math.round((float)(rect.optDouble("y")+rect.optDouble("height"))*scale));
        }
        miniPlay.setText(session.isPlaying()?"Ⅱ":"▶");
        miniPlay.setContentDescription(session.isPlaying()?"Pause playback":"Resume playback");
        if(session.isPlaying()&&!notificationAsked&&hasWindowFocus()&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            notificationAsked=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1002);
        }
        updateTabs();updatePipParams();
        getWindow().getDecorView().setKeepScreenOn(session.isPlaying()&&!PlaybackSession.isMusic(session.url()));
    }
    @Override public void onPageChanged(String url,int value) {
        if(progress==null)return;
        progress.setProgress(value,true);
        progress.setVisibility(value<100&&!pipPrepared&&fullScreen==null?View.VISIBLE:View.GONE);
        updateTabs();
    }
    private void updateTabs(){
        boolean music=session!=null&&PlaybackSession.isMusic(session.url());
        sectionToggle.setText(music ? "← YouTube" : "♫ Music");
        sectionToggle.setContentDescription(music ? "Switch to YouTube" : "Switch to YouTube Music");
    }
    private double aspectRatio(){JSONObject s=session.snapshot();return Math.max(1/2.39,Math.min(2.39,s.optDouble("width",16)/Math.max(1,s.optDouble("height",9))));}
    private Rect actualVideoBounds(){
        if(fullScreen!=null){Rect r=new Rect();fullScreen.getGlobalVisibleRect(r);return r;}
        if(mini)return new Rect(webView.getLeft(),webView.getTop(),webView.getRight(),webView.getBottom());
        Rect result=new Rect(videoBounds);result.offset(webView.getLeft(),webView.getTop());
        Rect content=new Rect(webView.getLeft(),webView.getTop(),webView.getRight(),webView.getBottom());
        if(result.isEmpty()||!result.intersect(content))return content;
        return result;
    }
    private void updatePipParams(){
        if(webView==null||!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))return;
        boolean playing=session.isPlaying();
        boolean allowed=playing&&!PlaybackSession.isMusic(session.url());
        int icon=playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play;
        String action=playing?MediaPlaybackService.ACTION_PAUSE:MediaPlaybackService.ACTION_PLAY;
        Intent command=new Intent(this,MediaPlaybackService.class).setAction(action);
        PendingIntent pending=playing
                ? PendingIntent.getService(this,43,command,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getForegroundService(this,43,command,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        RemoteAction toggle=new RemoteAction(Icon.createWithResource(this,icon),playing?"Pause":"Play",playing?"Pause playback":"Resume playback",pending);
        PictureInPictureParams.Builder builder=new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational((int)(aspectRatio()*1000),1000))
                .setAutoEnterEnabled(allowed).setSeamlessResizeEnabled(false)
                .setActions(Collections.singletonList(toggle)).setTitle(session.snapshot().optString("title","Shelby"));
        if(!pipPrepared){Rect bounds=actualVideoBounds();if(!bounds.isEmpty())builder.setSourceRectHint(bounds);}
        try{setPictureInPictureParams(builder.build());}catch(IllegalArgumentException ignored){}
    }
    private void preparePip(){
        if(pipPrepared)return;
        pipPrepared=true;
        if(miniAnimation!=null)miniAnimation.cancel();
        if(fullScreen!=null)hideFullScreen();
        mini=false;destroyBrowser();
        for(Button button:new Button[]{miniPlay,miniExpand,miniClose})button.setVisibility(View.GONE);
        webView.setClipToOutline(false);webView.setElevation(0);
        session.setBackground(true);
        session.evaluate("window.__shelbySurface?.enter('pip');");
        layoutShell();
    }
    @Override public void onPictureInPictureUiStateChanged(PictureInPictureUiState state){super.onPictureInPictureUiStateChanged(state);if(state.isTransitioningToPip())preparePip();}
    @Override public void onPictureInPictureModeChanged(boolean inPip,Configuration config){
        super.onPictureInPictureModeChanged(inPip,config);
        if(inPip)preparePip();else{pipPrepared=false;session.evaluate("window.__shelbySurface?.exit();");layoutShell();}
    }
    @Override public void onFullScreen(View view,WebChromeClient.CustomViewCallback callback){
        if(fullScreen!=null){callback.onCustomViewHidden();return;}
        orientationBeforeFullscreen=getRequestedOrientation();
        fullScreen=view;fullScreenCallback=callback;webView.setVisibility(View.INVISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        fullscreenSettings=button("⚙", "Playback settings");
        fullscreenSettings.setTextSize(22);
        fullscreenSettings.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams settingsPosition=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);
        settingsPosition.setMargins(dp(20),dp(12),dp(36),0);
        root.addView(fullscreenSettings,settingsPosition);
        fullscreenSettings.setElevation(dp(24));
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());layoutShell();
    }
    @Override public void onExitFullScreen(){hideFullScreen();}
    private void hideFullScreen(){
        if(fullScreen==null)return;
        if(fullscreenSettings!=null){root.removeView(fullscreenSettings);fullscreenSettings=null;}
        root.removeView(fullScreen);fullScreen=null;webView.setVisibility(View.VISIBLE);
        setRequestedOrientation(orientationBeforeFullscreen);
        WebChromeClient.CustomViewCallback callback=fullScreenCallback;fullScreenCallback=null;if(callback!=null)callback.onCustomViewHidden();
        getWindow().getInsetsController().show(WindowInsets.Type.systemBars());layoutShell();
    }
    @Override public void onPlayerError(){Toast.makeText(this,"Restoring your playback…",Toast.LENGTH_SHORT).show();recreate();}
    @Override public void onExternalLink(Uri uri){try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){Toast.makeText(this,"Unable to open this link",Toast.LENGTH_SHORT).show();}}
    private void navigateBack(){
        if(fullScreen!=null)hideFullScreen();
        else if(mini){if(browser!=null&&browser.canGoBack())browser.goBack();else finishMini(true);}
        else if(session.isPlaying()&&!PlaybackSession.isMusic(session.url()))showMini();
        else if(webView.canGoBack())webView.goBack();
        else moveTaskToBack(true);
    }
    @Override protected void onResume(){super.onResume();if(session!=null)session.setBackground(false);}
    @Override protected void onPause(){if(session!=null)session.setBackground(true);super.onPause();}
    @Override protected void onStop(){
        if(session!=null){
            // System PiP dismissal finishes the Activity; locking the screen does not.
            if(isFinishing())session.command(MediaPlaybackService.COMMAND_STOP,0);
            else session.setBackground(true);
        }
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){session.evaluate("window.__shelbyPlayback?.send(true);");super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){
        if(miniAnimation!=null)miniAnimation.cancel();
        if(webView!=null)webView.setOnTouchListener(null);
        hideFullScreen();destroyBrowser();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);unregisterReceiver(screen);
        if(session!=null){
            session.evaluate("window.__shelbySurface?.exit();");
            session.detach(this);
            if(isFinishing() && session.isAvailable()){
                session.command(MediaPlaybackService.COMMAND_STOP,0);
                PlaybackSession.discard(this);
            }
        }
        super.onDestroy();
    }
}
