package com.samarth.ytcleanplayer;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device tests use a blocked network surface, never a live YouTube account or remote fixture. */
@SuppressWarnings("deprecation")
public final class PlaybackLifecycleTest extends InstrumentationTestCase {
    private static final String MUSIC_TRACK = "https://music.youtube.com/watch?v=abcdefghijk";
    private static final String MUSIC_SNAPSHOT = "{\"url\":\"" + MUSIC_TRACK
            + "\",\"mediaUrl\":\"" + MUSIC_TRACK + "\",\"videoId\":\"abcdefghijk\","
            + "\"playing\":false,\"pausedByUser\":true,\"position\":42.5,\"duration\":180}";
    private int previousTaskId;
    private Context app;
    private PlaybackSession session;
    private Activity currentActivity;
    private SharedPreferences preferences;
    private final Map<String, String> previousPreferences = new HashMap<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        app = getInstrumentation().getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
        previousTaskId = preferences.getInt("task_id", -1);
        for (String key : new String[]{"selected", "music", "music_url", "youtube", "youtube_url"}) {
            previousPreferences.put(key, preferences.getString(key, null));
        }
        getInstrumentation().runOnMainSync(() -> PlaybackSession.obtain(app).release());
        preferences.edit().putString("selected", "music").putString("music", MUSIC_SNAPSHOT)
                .putString("music_url", MUSIC_TRACK).commit();
        getInstrumentation().runOnMainSync(() -> {
            session = PlaybackSession.obtain(app);
            playerOf(session).getSettings().setBlockNetworkLoads(true);
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            getInstrumentation().runOnMainSync(() -> {
                if (currentActivity != null && !currentActivity.isDestroyed()) currentActivity.finish();
                if (session != null) session.release();
            });
            getInstrumentation().waitForIdleSync();
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, String> entry : previousPreferences.entrySet()) {
                if (entry.getValue() == null) editor.remove(entry.getKey());
                else editor.putString(entry.getKey(), entry.getValue());
            }
            if (previousTaskId == -1) editor.remove("task_id");
            else editor.putInt("task_id", previousTaskId);
            editor.commit();
        } finally {
            super.tearDown();
        }
    }

    public void testRecreationRetainsPlayerAndDocumentState() throws Exception {
        launchActivity();
        WebView original = playerOf(session);
        assertSame("Activity must attach the process-owned media surface", original,
                field(currentActivity, "webView"));
        assertEquals("Saved Music section must not start on the YouTube homepage", MUSIC_TRACK, session.url());
        getInstrumentation().runOnMainSync(original::stopLoading);
        assertEquals("true", javascript(original,
                "window.__shelbyLifecycleFixture={position:42.5,section:'music'};true;"));

        Activity oldActivity = currentActivity;
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Activity> replacement = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new EmptyLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity && activity != oldActivity) {
                    replacement.set(activity);
                    resumed.countDown();
                }
            }
        };
        Application application = (Application) app;
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            getInstrumentation().runOnMainSync(oldActivity::recreate);
            assertTrue("Recreated Activity did not resume within 10 seconds", resumed.await(10, TimeUnit.SECONDS));
            currentActivity = replacement.get();
            getInstrumentation().waitForIdleSync();
            assertNotSame(oldActivity, currentActivity);
            assertSame("Recreation must keep the live WebView", original, field(currentActivity, "webView"));
            assertSame("Recreation must keep the same playback owner", session, field(currentActivity, "session"));
            assertEquals("\"42.5:music\"", javascript(original,
                    "window.__shelbyLifecycleFixture.position+':'+window.__shelbyLifecycleFixture.section"));
            assertEquals(MUSIC_TRACK, session.url());
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    public void testColdSessionSelectsSavedMusicTrackWithoutStartingPausedMedia() {
        launchActivity();
        assertEquals(MUSIC_TRACK, session.url());
        assertFalse("Paused saved media must not start a foreground playback service", MediaPlaybackService.isRunning());
        assertFalse(session.isPlaying());
    }

    public void testMusicRedirectKeepsTargetButNewTrackCancelsIt() throws Exception {
        launchActivity();
        JSONObject saved = new JSONObject(MUSIC_SNAPSHOT);
        String nextTrack = "https://music.youtube.com/watch?v=lmnopqrstuv";
        getInstrumentation().runOnMainSync(() -> {
            invoke(session, "beginRestore", new Class<?>[]{JSONObject.class}, saved);
            WebView player = playerOf(session);
            // Model the provider's SPA callbacks without a remote page or media request.
            player.getWebViewClient().doUpdateVisitedHistory(player, PlaybackSession.MUSIC, false);
            assertSame("A temporary Music landing redirect must preserve the seek target",
                    saved, field(session, "pendingRestore"));
            assertEquals("Redirect must not replace the durable watch URL", MUSIC_TRACK,
                    preferences.getString("music_url", null));
            player.getWebViewClient().doUpdateVisitedHistory(player, nextTrack, false);
            assertNull("Choosing a new track must release the old restore gate", field(session, "pendingRestore"));
            assertEquals(nextTrack, preferences.getString("music_url", null));
        });
        getInstrumentation().waitForIdleSync();
        assertNull("An old asynchronous restore callback must not reinstate the target",
                field(session, "pendingRestore"));
    }

    public void testNativeRestoreTimeoutReleasesGateWithoutErasingPosition() throws Exception {
        launchActivity();
        JSONObject saved = new JSONObject(MUSIC_SNAPSHOT);
        getInstrumentation().runOnMainSync(() -> {
            invoke(session, "beginRestore", new Class<?>[]{JSONObject.class}, saved);
            // Advance the scheduled deadline deterministically; do not wait a minute on device.
            ((Runnable) field(session, "restoreDeadline")).run();
            assertNull(field(session, "pendingRestore"));
            assertSame(saved, field(session, "failedNativeRestore"));
            assertTrue(session.snapshot().optBoolean("restoreFailed"));
            session.command("play", 0);
            JSONObject retry = (JSONObject) field(session, "pendingRestore");
            assertNotNull("Retry must retain the saved seek until JavaScript accepts it", retry);
            assertEquals(42.5, retry.optDouble("position"), 0.001);
            assertTrue(retry.optBoolean("playing"));
        });
        assertEquals(42.5, new JSONObject(preferences.getString("music", "{}"))
                .getDouble("position"), 0.001);
    }

    public void testServiceCommandsUseRetainedControllerAndStopWithoutAPlayer() throws Exception {
        launchActivity();
        RecordingController controller = new RecordingController();
        try {
            getInstrumentation().runOnMainSync(() -> {
                MediaPlaybackService.setPlaybackController(controller);
                app.startService(MediaPlaybackService.updateIntent(app, "Offline fixture", false, 42_500, 180_000));
            });
            awaitServiceState(true);
            getInstrumentation().runOnMainSync(() -> app.startService(
                    new Intent(app, MediaPlaybackService.class).setAction(MediaPlaybackService.ACTION_PAUSE)));
            assertEquals("pause", controller.commands.poll(5, TimeUnit.SECONDS));
            assertNull("A media command must reach the retained controller exactly once", controller.commands.poll());

            controller.available = false;
            getInstrumentation().runOnMainSync(() -> app.startService(
                    MediaPlaybackService.updateIntent(app, "Unavailable player", true, 42_500, 180_000)));
            awaitServiceState(false);
            assertNull("Missing player must not receive a hopeful Play command", controller.commands.poll());
        } finally {
            getInstrumentation().runOnMainSync(() -> {
                app.stopService(new Intent(app, MediaPlaybackService.class));
                MediaPlaybackService.clearPlaybackController(controller);
                if (session.isAvailable()) MediaPlaybackService.setPlaybackController(session);
            });
        }
    }

    public void testSessionStopRemovesServiceAndRejectsLatePausedSnapshots() {
        launchActivity();
        getInstrumentation().runOnMainSync(() -> app.startService(
                MediaPlaybackService.updateIntent(app, "Dismiss fixture", false, 42_500, 180_000)));
        awaitServiceState(true);
        getInstrumentation().runOnMainSync(() -> session.command(MediaPlaybackService.COMMAND_STOP, 0));
        getInstrumentation().waitForIdleSync();
        awaitServiceState(false);
        getInstrumentation().runOnMainSync(() -> {
            // These model bridge updates already queued when the floating player was closed.
            app.startService(MediaPlaybackService.updateIntent(app, "Late pause", false, 42_500, 180_000));
            app.startService(MediaPlaybackService.userPauseIntent(app));
        });
        getInstrumentation().waitForIdleSync();
        awaitServiceState(false);
        for (android.service.notification.StatusBarNotification notification
                : app.getSystemService(NotificationManager.class).getActiveNotifications()) {
            assertFalse("Dismissed playback must not retain a media notification", notification.getId() == 41);
        }
    }

    public void testNewTaskDiscardsPlaybackButSameTaskRetainsIt() throws Exception {
        launchActivity();
        getInstrumentation().runOnMainSync(() -> {
            int task = currentActivity.getTaskId();
            PlaybackSession.prepareTask(app, task);
            assertSame(session, PlaybackSession.obtain(app));
            assertTrue(preferences.contains("music"));
            PlaybackSession.prepareTask(app, task + 10000);
            assertFalse(preferences.contains("music"));
            assertFalse(preferences.contains("selected"));
            assertNotSame(session, PlaybackSession.obtain(app));
            PlaybackSession.discard(app);
            assertTrue(preferences.getAll().isEmpty());
        });
    }

    public void testFullscreenRequestsLandscapeAndRestoresOrientation() throws Exception {
        launchActivity();
        getInstrumentation().runOnMainSync(() -> {
            MainActivity activity = (MainActivity) currentActivity;
            int original = activity.getRequestedOrientation();
            activity.onFullScreen(new android.widget.FrameLayout(activity), () -> {});
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    activity.getRequestedOrientation());
            activity.onExitFullScreen();
            assertEquals(original, activity.getRequestedOrientation());
            assertSame(session, field(activity, "session"));
        });
    }

    private void launchActivity() {
        Intent launch = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Seed a same-task restore fixture before MainActivity reads its task identity.
        Application application = (Application) app;
        Application.ActivityLifecycleCallbacks seed = new EmptyLifecycleCallbacks() {
            @Override public void onActivityPreCreated(Activity activity, Bundle saved) {
                preferences.edit().putInt("task_id", activity.getTaskId()).commit();
            }
        };
        application.registerActivityLifecycleCallbacks(seed);
        try { currentActivity = getInstrumentation().startActivitySync(launch); }
        finally { application.unregisterActivityLifecycleCallbacks(seed); }
        getInstrumentation().waitForIdleSync();
    }

    private String javascript(WebView view, String expression) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(expression, value -> {
            result.set(value);
            finished.countDown();
        }));
        assertTrue("WebView JavaScript callback timed out", finished.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private void awaitServiceState(boolean running) {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (MediaPlaybackService.isRunning() != running && SystemClock.uptimeMillis() < deadline) {
            getInstrumentation().waitForIdleSync();
            SystemClock.sleep(25);
        }
        assertEquals("Service lifecycle did not settle", running, MediaPlaybackService.isRunning());
    }

    private static WebView playerOf(PlaybackSession session) { return (WebView) field(session, "webView"); }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object invoke(Object owner, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(owner, arguments);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingController implements MediaPlaybackService.PlaybackController {
        final LinkedBlockingQueue<String> commands = new LinkedBlockingQueue<>();
        volatile boolean available = true;
        @Override public boolean isAvailable() { return available; }
        @Override public void onMediaCommand(String command, long positionMs) { commands.add(command); }
    }

    private abstract static class EmptyLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
