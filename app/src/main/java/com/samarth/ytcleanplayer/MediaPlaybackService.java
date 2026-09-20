package com.samarth.ytcleanplayer;

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
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Owns Android playback resources; the retained playback session owns the live WebView. */
public class MediaPlaybackService extends Service {
    public static final String ACTION_UPDATE = "com.samarth.ytcleanplayer.UPDATE_MEDIA";
    public static final String ACTION_PLAY = "com.samarth.ytcleanplayer.PLAY";
    public static final String ACTION_PAUSE = "com.samarth.ytcleanplayer.PAUSE";
    public static final String ACTION_STOP = "com.samarth.ytcleanplayer.STOP";
    public static final String ACTION_USER_PAUSE = "com.samarth.ytcleanplayer.USER_PAUSE";
    public static final String ACTION_MEDIA_COMMAND = "com.samarth.ytcleanplayer.MEDIA_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String COMMAND_PLAY = "play";
    public static final String COMMAND_PAUSE = "pause";
    public static final String COMMAND_STOP = "stop";
    public static final String COMMAND_SEEK = "seek";
    public static final String COMMAND_SUSPEND = "suspend";
    public static final String COMMAND_RESUME = "resume";
    public static final String COMMAND_DUCK = "duck";
    public static final String COMMAND_UNDUCK = "unduck";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PLAYING = "playing";
    private static final String EXTRA_WANTS_PLAY = "wants_play";
    private static final String EXTRA_DURATION_MS = "duration_ms";
    private static final String CHANNEL_ID = "youtube_playback";
    private static final int NOTIFICATION_ID = 41;
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long RESOURCE_CHECK_MS = 30_000L;
    private static volatile PlaybackController playbackController;
    private static volatile boolean running;
    private static volatile boolean explicitlyStopped;

    /** Implement in the retained session, never an Activity. Callbacks run on the main thread. */
    public interface PlaybackController {
        boolean isAvailable();
        void onMediaCommand(String command, long positionMs);
        /** True when the media engine already requests and responds to Android audio focus. */
        default boolean handlesAudioFocus() { return false; }
    }

    public static void setPlaybackController(PlaybackController controller) {
        if (playbackController != controller) explicitlyStopped = false;
        playbackController = controller;
    }

    public static void clearPlaybackController(PlaybackController expected) {
        if (playbackController == expected) playbackController = null;
    }

    public static boolean isRunning() { return running; }

    public static Intent updateIntent(Context context, String title, boolean playing) {
        return updateIntent(context, title, playing, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0L);
    }

    public static Intent updateIntent(Context context, String title, boolean playing,
                                      long positionMs, long durationMs) {
        return updateIntent(context, title, playing, positionMs, durationMs, playing);
    }

    public static Intent updateIntent(Context context, String title, boolean playing,
                                      long positionMs, long durationMs, boolean wantsPlay) {
        return new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_WANTS_PLAY, wantsPlay)
                .putExtra(EXTRA_POSITION_MS, positionMs)
                .putExtra(EXTRA_DURATION_MS, durationMs);
    }

    /** Explicit page/UI pause must cancel any pending resume after an interruption. */
    public static Intent userPauseIntent(Context context) {
        return new Intent(context, MediaPlaybackService.class).setAction(ACTION_USER_PAUSE);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaybackFocusPolicy focusPolicy = new PlaybackFocusPolicy();
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private boolean playing;
    private boolean playPending;
    private boolean foreground;
    private boolean stopped;
    private boolean focusRequested;
    private boolean focusGranted;
    private boolean ducked;
    private boolean noisyReceiverRegistered;
    private boolean idleTimeoutScheduled;
    private String title = "Shelby playback";
    private long positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    private long positionUpdatedAt;
    private long durationMs;
    private String metadataTitle;
    private long metadataDuration = -1L;
    private String notificationTitle;
    private Boolean notificationActive;

    private final Runnable idleTimeout = () -> {
        idleTimeoutScheduled = false;
        if (!playing) stopPlayback(false);
    };

    private final Runnable playTimeout = () -> {
        if (playPending && !playing) pausePlayback(true);
    };

    private final Runnable maintainResources = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            if (!hasPlayer()) {
                stopPlayback(false);
                return;
            }
            updateWakeLock();
            handler.postDelayed(this, RESOURCE_CHECK_MS);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && (playing || playPending || focusPolicy.isWaitingForFocus())) {
                pausePlayback(true);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW));
        audioManager = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                // Android ramps volume smoothly for notifications and navigation prompts.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::onAudioFocusChanged, handler)
                .build();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":media-playback");
        wakeLock.setReferenceCounted(false);
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED);
        noisyReceiverRegistered = true;
        mediaSession = new MediaSession(this, "Shelby");
        mediaSession.setSessionActivity(openAppIntent());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { playPlayback(); }
            @Override public void onPause() { pausePlayback(true); }
            @Override public void onStop() { stopPlayback(true); }
            @Override public void onSeekTo(long position) { seekTo(position); }
            @Override public void onFastForward() { seekTo(currentPosition() + 10_000L); }
            @Override public void onRewind() { seekTo(currentPosition() - 10_000L); }
        }, handler);
        mediaSession.setActive(true);
        handler.postDelayed(maintainResources, RESOURCE_CHECK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A dead process has no WebView: a service restart cannot restore live playback.
        if (intent == null || !hasPlayer()) {
            stopPlayback(false);
            return START_NOT_STICKY;
        }
        if (stopped) return START_NOT_STICKY;
        String action = intent.getAction();
        if (explicitlyStopped && !ACTION_PLAY.equals(action)
                && !(ACTION_UPDATE.equals(action) && intent.getBooleanExtra(EXTRA_PLAYING, false))) {
            // A queued pause/update can arrive after STOP destroyed the service. It must
            // not recreate a media session or notification for an already dismissed player.
            stopPlayback(false);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) stopPlayback(true);
        else if (ACTION_PLAY.equals(action)) playPlayback();
        else if (ACTION_PAUSE.equals(action)) pausePlayback(true);
        else if (ACTION_USER_PAUSE.equals(action)) pausePlayback(false);
        else if (ACTION_UPDATE.equals(action)) updateFromPlayer(intent);
        return START_NOT_STICKY;
    }

    private void updateFromPlayer(Intent intent) {
        String reportedTitle = intent.getStringExtra(EXTRA_TITLE);
        title = reportedTitle == null || reportedTitle.trim().isEmpty()
                ? "Shelby playback" : reportedTitle.trim();
        durationMs = Math.max(0L, intent.getLongExtra(EXTRA_DURATION_MS, 0L));
        positionMs = clampPosition(intent.getLongExtra(EXTRA_POSITION_MS,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN));
        positionUpdatedAt = SystemClock.elapsedRealtime();
        boolean reportedPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false);
        boolean wantsPlay = intent.getBooleanExtra(EXTRA_WANTS_PLAY, reportedPlaying);
        if (reportedPlaying) {
            explicitlyStopped = false;
            clearPendingPlay();
            if (focusPolicy.isWaitingForFocus()) {
                // A delayed DOM callback/website autoplay cannot override an interruption.
                sendCommand(COMMAND_SUSPEND, 0L);
                playing = false;
            } else {
                focusPolicy.playRequested();
                promoteToForeground();
                playing = acquireAudioFocus();
                if (!playing) sendCommand(COMMAND_SUSPEND, 0L);
            }
        } else {
            playing = false;
            if (!focusPolicy.isWaitingForFocus() && wantsPlay) {
                // Screen-off/buffering can pause the DOM briefly without changing user intent.
                // Preserve the native resources for a bounded recovery window, not indefinitely.
                focusPolicy.playRequested();
                if (!playPending) markPlayPending();
                promoteToForeground();
                if (!acquireAudioFocus()) {
                    clearPendingPlay();
                    sendCommand(COMMAND_SUSPEND, 0L);
                }
            } else if (!focusPolicy.isWaitingForFocus()) {
                // A restore/platform interruption can retract desired playback while a
                // Play command is pending. Release resources without echoing a DOM pause.
                clearPendingPlay();
                focusPolicy.pausedByUser();
                abandonAudioFocus();
            }
        }
        publishState();
    }

    private void playPlayback() {
        if (!hasPlayer() || stopped) {
            stopPlayback(false);
            return;
        }
        explicitlyStopped = false;
        focusPolicy.playRequested();
        markPlayPending();
        // Android 15+ requires a visible activity or FGS before requesting focus.
        promoteToForeground();
        if (acquireAudioFocus()) sendCommand(COMMAND_PLAY, 0L);
        else {
            playing = false;
            clearPendingPlay();
            sendCommand(COMMAND_SUSPEND, 0L);
        }
        publishState();
    }

    private void markPlayPending() {
        playPending = true;
        handler.removeCallbacks(playTimeout);
        handler.postDelayed(playTimeout, 15_000L);
    }

    private void clearPendingPlay() {
        playPending = false;
        handler.removeCallbacks(playTimeout);
    }

    private void pausePlayback(boolean tellPlayer) {
        if (stopped) return;
        positionMs = currentPosition();
        positionUpdatedAt = SystemClock.elapsedRealtime();
        playing = false;
        clearPendingPlay();
        focusPolicy.pausedByUser();
        if (tellPlayer) sendCommand(COMMAND_PAUSE, 0L);
        abandonAudioFocus();
        publishState();
    }

    private boolean acquireAudioFocus() {
        PlaybackController controller = playbackController;
        if (controller != null && controller.handlesAudioFocus()) {
            // Chromium owns focus for WebView's actual audio output. A second GAIN request
            // from this service makes the two clients interrupt each other, even in one UID.
            // The service still owns foreground lifetime, transport controls and route safety.
            if (focusRequested) abandonAudioFocus();
            return true;
        }
        if (focusGranted) return true;
        int result = audioManager.requestAudioFocus(focusRequest);
        focusRequested = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            focusPolicy.temporarilyInterrupted();
        } else if (!focusGranted) {
            focusPolicy.permanentlyInterrupted();
        }
        return focusGranted;
    }

    private void onAudioFocusChanged(int change) {
        if (stopped || !focusRequested) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusGranted = true;
            restoreVolume();
            if (focusPolicy.focusGained()) {
                markPlayPending();
                promoteToForeground();
                sendCommand(COMMAND_RESUME, 0L);
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // Normally Android ducks for us; keep a fallback for callback-delivering routes.
            if (playing && !ducked) {
                ducked = true;
                sendCommand(COMMAND_DUCK, 0L);
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            focusGranted = false;
            focusPolicy.temporarilyInterrupted();
            positionMs = currentPosition();
            positionUpdatedAt = SystemClock.elapsedRealtime();
            playing = false;
            clearPendingPlay();
            sendCommand(COMMAND_SUSPEND, 0L);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            // Another media app owns playback; the next resume must be a user action.
            focusGranted = false;
            focusPolicy.permanentlyInterrupted();
            pausePlayback(true);
            return;
        }
        publishState();
    }

    private void abandonAudioFocus() {
        boolean shouldAbandon = focusRequested;
        focusRequested = false;
        focusGranted = false;
        if (shouldAbandon) audioManager.abandonAudioFocusRequest(focusRequest);
        restoreVolume();
    }

    private void restoreVolume() {
        if (ducked) {
            ducked = false;
            sendCommand(COMMAND_UNDUCK, 0L);
        }
    }

    private void seekTo(long position) {
        if (durationMs <= 0L || !hasPlayer() || stopped) return;
        positionMs = Math.min(durationMs, Math.max(0L, position));
        positionUpdatedAt = SystemClock.elapsedRealtime();
        sendCommand(COMMAND_SEEK, positionMs);
        updateMediaSession();
    }

    private long currentPosition() {
        if (positionMs < 0L) return 0L;
        long elapsed = playing ? SystemClock.elapsedRealtime() - positionUpdatedAt : 0L;
        return clampPosition(positionMs + Math.max(0L, elapsed));
    }

    private long clampPosition(long position) {
        if (position < 0L) return PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        return durationMs > 0L ? Math.min(position, durationMs) : position;
    }

    private void publishState() {
        if (stopped) return;
        updateMediaSession();
        updateWakeLock();
        if (playing || playPending || focusPolicy.isWaitingForFocus()) {
            promoteToForeground();
        } else if (foreground) {
            stopForeground(STOP_FOREGROUND_DETACH);
            foreground = false;
        }
        updateNotification();
        if (playing || playPending) {
            handler.removeCallbacks(idleTimeout);
            idleTimeoutScheduled = false;
        } else if (!idleTimeoutScheduled) {
            idleTimeoutScheduled = true;
            handler.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
        }
    }

    private void updateMediaSession() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (durationMs > 0L) {
            actions |= PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND;
        }
        int state = stopped ? PlaybackState.STATE_STOPPED : playing ? PlaybackState.STATE_PLAYING
                : playPending ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs, playing ? 1f : 0f, positionUpdatedAt)
                .build());
        if (!title.equals(metadataTitle) || durationMs != metadataDuration) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Shelby")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build());
            metadataTitle = title;
            metadataDuration = durationMs;
        }
    }

    private void promoteToForeground() {
        if (foreground || stopped) return;
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        foreground = true;
        notificationTitle = title;
        notificationActive = playing || playPending;
    }

    private void updateNotification() {
        boolean active = playing || playPending;
        if (!title.equals(notificationTitle) || notificationActive == null
                || notificationActive != active) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
            notificationTitle = title;
            notificationActive = active;
        }
    }

    private Notification buildNotification() {
        boolean active = playing || playPending;
        Notification.Action toggle = new Notification.Action.Builder(
                active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                active ? "Pause" : "Play",
                serviceAction(active ? ACTION_PAUSE : ACTION_PLAY, 11)).build();
        Notification.Action stop = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                serviceAction(ACTION_STOP, 12)).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(active ? "Playing · Shelby" : "Paused · Shelby")
                .setContentIntent(openAppIntent())
                .setDeleteIntent(serviceAction(ACTION_STOP, 12))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(active)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(toggle)
                .addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 10, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return ACTION_PLAY.equals(action)
                ? PendingIntent.getForegroundService(this, requestCode, intent, flags)
                : PendingIntent.getService(this, requestCode, intent, flags);
    }

    private boolean hasPlayer() {
        PlaybackController controller = playbackController;
        return controller != null && controller.isAvailable();
    }

    private void sendCommand(String command, long position) {
        PlaybackController controller = playbackController;
        if (controller != null && controller.isAvailable()) {
            controller.onMediaCommand(command, position);
        }
    }

    private void updateWakeLock() {
        if ((playing || playPending) && !stopped && hasPlayer()) {
            // Actual playback or the bounded 15-second recovery window; never a user pause.
            wakeLock.acquire(2 * RESOURCE_CHECK_MS);
        } else if (wakeLock.isHeld()) wakeLock.release();
    }

    private void stopPlayback(boolean tellPlayer) {
        if (stopped) return;
        if (tellPlayer) explicitlyStopped = true;
        stopped = true;
        running = false;
        positionMs = currentPosition();
        positionUpdatedAt = SystemClock.elapsedRealtime();
        playing = false;
        clearPendingPlay();
        focusPolicy.pausedByUser();
        if (tellPlayer) sendCommand(COMMAND_STOP, 0L);
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
        updateWakeLock();
        updateMediaSession();
        mediaSession.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        notificationManager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback(true);
        PlaybackSession.discard(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        boolean interrupted = !stopped && (playing || playPending);
        running = false;
        stopped = true;
        playing = false;
        handler.removeCallbacksAndMessages(null);
        if (interrupted) sendCommand(COMMAND_SUSPEND, 0L);
        abandonAudioFocus();
        updateWakeLock();
        if (noisyReceiverRegistered) unregisterReceiver(noisyReceiver);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        notificationManager.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
