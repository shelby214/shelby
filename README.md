# Shelby for Android 2.0

Shelby combines native Android playback controls with YouTube and YouTube Music in a retained WebView. Version 2.0 separates the player's lifetime from the Activity, saves playback sessions, and updates video presentation, Shorts routing, and SponsorBlock integration.

The floating section switch, media notification, mini-player controls, and picture-in-picture integration are native. YouTube still supplies the page and media engine through Android WebView; provider changes, network failures, and Android process management can affect playback.

## Controls

- Use the floating **♫ Music / ← YouTube** switch to change sections. Its **⋮** button opens Playback settings. Each section has its own saved page and playback snapshot, and a thin progress indicator appears during page loads.
- Leave Shelby while a YouTube video is playing to use Android picture-in-picture. PiP offers play/pause. Music uses background audio without opening a video PiP window.
- Swipe downward over a playing video to enter the in-app mini player. Android Back also opens it while a regular video is playing. A separate browsing WebView opens underneath, preserving the playing page while you browse.
- Drag the mini player to reposition it within the screen. Drop it on the red **×** target to dismiss playback. Use **Ⅱ / ▶** to pause/resume, **↗** or the video itself to expand, and the small **×** to dismiss. Selecting another video underneath sends it to the retained main player.
- Use the Android media notification or lock-screen controls for play, pause, and stop. Deliberate pauses remain paused when audio focus returns. Removing Shelby from Recents ends playback and clears the playback session, so the next launch opens fresh in Music. Locking the screen or returning to the same task keeps the session. Stop ends playback.
- Open **⋮ → Playback settings** for the SponsorBlock switch and attribution/privacy information.

## Playback and state architecture

`PlaybackSession` owns the main WebView for the lifetime of the app process. Activities attach and detach that same surface using a `MutableContextWrapper`; Activity recreation therefore does not replace the active document. Background transitions avoid `WebView.onPause()` and `pauseTimers()` while the WebView is serving as the media engine.

Snapshots belong to the current Android task and are discarded when the task is removed or a new task starts. Sign-in cookies and settings are retained. The session saves the selected section and separate YouTube/Music snapshots in `SharedPreferences`: page URL, media URL and ID, position, duration, playback intent, and scroll position. It does not put full Chromium WebView history into the Activity state Bundle. This avoids the large saved-state transactions that can fail during locking and Activity recreation.

On a new process or renderer recovery, the session opens the saved section. `playback.js` waits for the player to report the exact saved media ID and a seekable timeline before restoring the position. It excludes ad/navigation transitions, distinguishes user pauses from interruptions, and bounds restoration/recovery attempts. A paused checkpoint remains protected against late provider timeline resets; explicit Play retries the saved seek if recovery cannot finish. Home feed previews do not replace the saved browsing route with a random watch page. A snapshot is a recovery checkpoint; it cannot preserve a killed process's live decoder or guarantee uninterrupted playback after process death.

`MediaPlaybackService` supplies the foreground media notification and `MediaSession`. Commands go directly to the retained playback controller. Chromium owns audio focus for WebView playback; the service does not compete for a second focus grant. Platform pauses and deliberate pauses remain authoritative, and headphone disconnection pauses playback. A separate focus policy supports controllers that do not manage their own focus. A bounded, renewed partial wake lock supports active/requested playback and is released when playback stops. A missing player does not start a service that pretends to play audio.

## Video presentation and Shorts

Playback settings offer 0.25×–2× speed and the current video’s available resolutions. A gear button keeps these settings accessible in fullscreen. Video sizing preserves the original aspect ratio. The fullscreen button requests sensor landscape orientation, even when auto-rotate is off; leaving fullscreen restores the previous orientation policy. PiP uses the current video's screen bounds as `sourceRectHint`, updates its aspect ratio, and hides the floating section switch and browsing layer during transition. Seamless resizing is disabled so Android can use its crossfade behavior for the WebView surface. The in-app mini player animates the native view's bounds over 280 ms and fades in its controls. `player-surface.js` presents only the video while preserving its original DOM ancestry, then restores the touched styles and attributes on exit. These mechanisms follow the relevant [Android PiP guidance](https://developer.android.com/develop/ui/views/picture-in-picture); portrait entry/exit and continuous playback were checked on a Pixel 9; animation timing across other devices and orientations still needs broader testing.

`shorts-policy.js` intercepts Shorts taps at document start where WebView supports it, with page-load reinjection as a fallback. Rewritten links retain their Shorts identity, touch swipes are distinguished from taps, and SPA history navigation is normalized to `/watch?v=…`. Reel playback is suppressed without muting the reused media element. Home Shorts shelves/cards and the Shorts shortcut remain hidden; Shorts found elsewhere open in the ordinary watch player.

The shell adds consistent dark styling and Android status-bar, keyboard, navigation-bar, and display-cutout insets. Fullscreen video remains immersive.

## SponsorBlock

**Skip sponsored segments · SponsorBlock** is enabled by default and can be changed in Playback settings. Shelby requests community-submitted `sponsor` segments with the `skip` action. Other categories, such as intros and self-promotion, are not automatically skipped. A brief **SponsorBlock · Sponsor skipped** notice offers **Undo**, which restores the skipped position and suppresses that segment for the current viewing session.

Lookups run in `SponsorBlockClient` off the UI thread. Responses are cached, overlapping segments are merged, stale video responses are ignored, and malformed/outdated timings are rejected. Missing segments, API errors, and timeouts leave playback unchanged. This uses submitted community timestamps; it does not detect every sponsorship automatically. See the [official SponsorBlock API documentation](https://wiki.sponsor.ajay.app/w/API_Docs).

When enabled, the current public YouTube **video ID is sent to `sponsor.ajay.app`** over HTTPS. The server also receives ordinary connection metadata, including the source IP address and Shelby user agent. The client does not attach Google sign-in cookies, account identifiers, or playback history to those requests. Disabling SponsorBlock stops new lookups and automatic skips; a request already in flight may finish. Attribution belongs to [SponsorBlock and its contributors](https://sponsor.ajay.app/).

## Bundled filters

Edit the scripts under `app/src/main/assets`, then rebuild to change their behavior:

| File | Purpose |
| --- | --- |
| `blocker.js` | YouTube ad-response filtering and current-player ad cleanup. |
| `music-blocker.js` | Music ad cleanup and stale overlay handling using the live player's ad state, rather than queued ad metadata. |
| `productive-filter.js` | Local keyword/channel classification for productive feeds. |
| `shorts-policy.js` | Route Shorts directly to the normal player. |
| `sponsor-block.js` | Apply native-fetched sponsor segments and display Undo. |
| `playback.js` | Playback intent, snapshots, restoration, and background coordination. |
| `player-surface.js` | Video-only mini/PiP presentation without moving the media node. |
| `shell.js` | Shared dark page styling and touch presentation. |

The ad blockers restore the original mute state and playback speed after a detected ad or media replacement. They do not resume deliberately paused media. Reinjection reuses their existing controller and listeners.

Productive-feed **strict mode is enabled by default**. Adjust `allowedKeywords`, `alwaysAllowChannels`, and `blockedKeywords` in `productive-filter.js`. Unmatched cards can be hidden from Home, search, and recommendations. Classification uses page text and accessibility labels, so it can misclassify content. YouTube-only feed policies are not injected into Music. Native request filtering also blocks configured advertising endpoints.

## Build and install

Requirements: Android Studio, Android SDK 36 with Build Tools 36.0.0, and a compatible JDK for the Gradle/Android plugin. The app currently requires **Android 16 / API 36 or later**. Set the SDK location through Android Studio or `ANDROID_HOME`.

Open this repository in Android Studio, sync Gradle, and select a build variant. To build a separate preview installation:

```sh
./gradlew assemblePreview
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

The launcher name is **Shelby Preview**, and its application ID is `com.samarth.ytcleanplayer.preview`. It can be installed alongside the existing `com.samarth.ytcleanplayer` app. Preview has its own cookies, sign-in, settings, and saved sessions; it does not migrate the installed app's state.

For the regular debug application:

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Installing that APK updates the regular package only when its signing certificate matches the installed version. The preview variant is useful for evaluating 2.0 before replacing an existing installation.

## Verification

Run the deterministic JavaScript regressions with Node.js:

```sh
node --test tests/*.test.cjs
```

These cover playback intent/restoration, presentation restoration, Shorts routing, sponsor skipping and Undo, stale responses, ad mute/speed cleanup, and script reinjection. They use controlled DOM/media fixtures and do not establish real YouTube playback or animation performance.

Run the standalone Java audio-focus policy checks with a JDK:

```sh
mkdir -p /tmp/shelby-focus-tests
javac -d /tmp/shelby-focus-tests \
  app/src/main/java/com/samarth/ytcleanplayer/PlaybackFocusPolicy.java \
  tests/PlaybackFocusPolicyTest.java
java -cp /tmp/shelby-focus-tests com.samarth.ytcleanplayer.PlaybackFocusPolicyTest
```

Android instrumentation is provided in `app/src/androidTest/java/com/samarth/ytcleanplayer/PlaybackLifecycleTest.java`. It exercises Activity recreation with the same WebView, saved Music selection, and service command delivery using blocked network access and offline fixtures. With an API 36+ emulator/device connected, target the separate Preview package:

```sh
./gradlew -PtestBuildType=preview connectedPreviewAndroidTest
```

The default test build type is `debug`; use `./gradlew connectedDebugAndroidTest` only when intentionally testing that package. `testBuildType` selects the instrumentation target, so the preview command keeps the regular installed app separate.

**Device validation:** the Preview build was tested on a Pixel 9 running Android 16 with WebView 151.0.7922.202. Live checks covered screen-off YouTube and Music playback, media-session pause/resume, Music checkpoint recovery after a process restart, portrait PiP, and a real Shorts card opening in the regular player unmuted. Native SponsorBlock lookup and a controlled segment skip/Undo were also exercised. See [VALIDATION.md](VALIDATION.md) for exact scope and remaining checks. Validation used the separate Preview package. After testing, the original installed Shelby app was removed at the user’s request; the latest Preview remains installed.

## References and limits

The requested [ytvancedx reference](https://github.com/laynz28/ytvancedx) describes a Vanced distribution and explicitly states that the repository does not hold Vanced+ source code. It informed the feature reference; this change does not copy its application code, APKs, or proprietary YouTube UI source.

Shelby remains an unofficial personal project and is not affiliated with YouTube or Google. Sign-in cookies are flushed after navigation/background transitions. The app does not bypass memberships, purchases, sign-in requirements, age gates, or geographic restrictions. Updated YouTube pages, WebView behavior, OEM power management, and network conditions may require further compatibility fixes; the retained session and foreground service reduce specific failure modes without guaranteeing perfect background reliability.

### Audio quality and details

Playback settings → **Audio quality & details** shows verified active-stream codec, container, average/advertised bitrate, sample rate, channel count, format ID, and quality tier. It also lists available streams, buffering, volume, provider loudness information, and the separately labelled network estimate. Values refresh every two seconds only while the panel is open.

Metadata is matched to the current video and the active format reported by the player. Stale, ambiguous, ad, or unavailable data is not presented as the current stream. Stream URLs and playback-session identifiers are not included. The panel includes a phone sound-settings shortcut and the official YouTube Music audio-quality guide. Higher-quality audio remains dependent on what YouTube exposes for the account and track; Shelby does not claim to enable Premium audio or add an equalizer.
