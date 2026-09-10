package com.samarth.ytcleanplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

public class MediaPlaybackService extends Service {
    public static final String ACTION_UPDATE = "com.samarth.ytcleanplayer.UPDATE_MEDIA";
    public static final String ACTION_PLAY = "com.samarth.ytcleanplayer.PLAY";
    public static final String ACTION_PAUSE = "com.samarth.ytcleanplayer.PAUSE";
    public static final String ACTION_STOP = "com.samarth.ytcleanplayer.STOP";
    public static final String ACTION_MEDIA_COMMAND =
            "com.samarth.ytcleanplayer.MEDIA_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String COMMAND_PLAY = "play";
    public static final String COMMAND_PAUSE = "pause";
    public static final String COMMAND_STOP = "stop";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PLAYING = "playing";
    private static final String CHANNEL_ID = "youtube_playback";
    private static final int NOTIFICATION_ID = 41;

    private MediaSession mediaSession;
    private boolean playing;
    private String title = "YouTube playback";

    public static Intent updateIntent(Context context, String title, boolean playing) {
        return new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "YouTube playback",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        mediaSession = new MediaSession(this, "Shelby");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                sendCommand(COMMAND_PLAY);
            }

            @Override
            public void onPause() {
                sendCommand(COMMAND_PAUSE);
            }

            @Override
            public void onStop() {
                sendCommand(COMMAND_STOP);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_UPDATE.equals(action)) {
            title = intent.getStringExtra(EXTRA_TITLE);
            if (title == null || title.trim().isEmpty()) title = "YouTube playback";
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false);
        } else if (ACTION_PLAY.equals(action)) {
            playing = true;
            sendCommand(COMMAND_PLAY);
        } else if (ACTION_PAUSE.equals(action)) {
            playing = false;
            sendCommand(COMMAND_PAUSE);
        } else if (ACTION_STOP.equals(action)) {
            sendCommand(COMMAND_STOP);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        updateMediaSession();
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    private void updateMediaSession() {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Shelby")
                .build());
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                10,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String toggleAction = playing ? ACTION_PAUSE : ACTION_PLAY;
        int toggleIcon = playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        String toggleLabel = playing ? "Pause" : "Play";
        PendingIntent toggleIntent = serviceAction(toggleAction, 11);
        PendingIntent stopIntent = serviceAction(ACTION_STOP, 12);

        Notification.Action toggle = new Notification.Action.Builder(
                toggleIcon,
                toggleLabel,
                toggleIntent
        ).build();
        Notification.Action stop = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
        ).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("Playing from Shelby")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(toggle)
                .addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class).setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(ACTION_MEDIA_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
