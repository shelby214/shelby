# Shelby for Android

A focused Android WebView app that opens YouTube and injects its bundled ad-removal and productive-feed scripts. It also filters common advertising request endpoints.

## Change the blocker script in source

Edit `app/src/main/assets/blocker.js`, then rebuild and reinstall the APK. The app no longer shows a JS settings icon or loads previously selected external script files.

## Configure the productive feed

Edit `app/src/main/assets/productive-filter.js`. Its `allowedKeywords`, `alwaysAllowChannels`, and `blockedKeywords` lists control which videos appear. Strict mode is enabled by default, so unmatched videos are hidden from Home, search results, and related recommendations. Classification uses the title, channel, description, and accessibility labels visible on each YouTube card; it is local and keyword-based rather than a perfect semantic classifier.

## Native playback features

- Automatic video-only picture-in-picture when you leave the app during playback.
- PiP playback is kept alive through Android's background transition.
- Hold the video briefly and swipe downward to use the in-app mini player. A separate YouTube browsing layer opens underneath, so Home, search, and recommendations cannot destroy the playing video. Tap the mini-video itself or **↗** to expand it; use **×** to stop and close it.
- Background audio while the app is in the background or the screen is locked.
- Android media notification and lock-screen play, pause, and stop controls.
- Native MediaSession integration for Android system playback controls.
- Fullscreen video hides the system bars.
- Android's native back button and back gesture navigate through YouTube history before closing the app.
- Shorts shelves/cards, Community posts, polls, image posts, and the bottom Shorts shortcut are removed from Home. Shorts opened from search or direct links are converted to the normal single-video watch player, so there is no swipe-to-next Shorts feed.
- YouTube's Home filter pills remain interactive; hidden Shorts and posts are filtered without removing nodes owned by YouTube's live page renderer.
- A configurable strict productive-feed script keeps technology, education, career, productivity, self-improvement, and motivational videos while hiding unmatched or entertainment-focused recommendations.
- Google sign-in cookies are explicitly saved after navigation and whenever the app moves to the background, so login persists across normal app restarts and upgrades.
- A floating **Music** switch opens YouTube Music in the same WebView. A dedicated built-in Music blocker hides promotions, clicks skip controls, and fast-forwards detected audio/video ads.
- The Music blocker is isolated in `app/src/main/assets/music-blocker.js`. It throttles page observation, avoids broad button clicks, closes premium upsells through their own controls, and clears only stale invisible backdrops so Music remains touch-responsive.
- YouTube-only Shorts and productive-feed observers are never injected into YouTube Music, avoiding unnecessary full-page scans while songs are playing.
- YouTube Music supports background and lock-screen playback through the foreground media service, including notification play/pause/stop controls. Music remains audio-only in the background instead of opening video PiP.
- Screen-off is handled explicitly for both YouTube and YouTube Music. A shared visibility/pause guard, recent-playback grace window, foreground media service, and partial wake lock keep playback alive when the power button locks the phone.
- Regular YouTube is forced into a consistent dark interface. The compact floating switch is two-way: **Music** opens YouTube Music, and **← YouTube** returns directly to YouTube without depending on navigation history. Neither control occupies YouTube's bottom navigation.

## Build

Open this folder in Android Studio and select **Build > Build APK(s)**, or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- Minimum Android version: Android 16 (API 36). It is forward-compatible with Android 17.
- Normal browsing respects status-bar, navigation-bar, and display-cutout safe areas; video fullscreen remains immersive.
- YouTube can change its player and advertising implementation, so future script updates may be necessary.
- This app does not bypass memberships, purchases, sign-in, age gates, geographic restrictions, or other access controls.
- This is an unofficial personal project and is not affiliated with YouTube or Google.
