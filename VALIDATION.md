# Shelby 2.0 Preview validation

Tested September 10, 2026 on a connected Pixel 9, Android 16 / API 36, with Android System WebView 151.0.7922.202. The project was opened in Android Studio. Gradle builds used Temurin 21 and Android Build Tools 36.0.0.

The device's original Shelby signing key differs from this workspace's debug key. With the user's approval, testing used `com.samarth.ytcleanplayer.preview` (Shelby Preview). The original app was left installed with its data intact during validation. Afterwards, the user explicitly requested its removal; the original app and temporary instrumentation package were uninstalled, leaving the latest Shelby Preview installed.

## Findings and fixes

- The old app's crash log contained `TransactionTooLargeException` with Chromium saved-state payloads around 1.2–1.38 MB. The new Activity no longer serializes WebView history into its state Bundle. A retained session owns the player, and small per-section checkpoints survive process restarts.
- Native and Chromium audio-focus requests competed, causing playback to pause almost immediately. Chromium now owns focus for WebView playback; the service supplies the notification and session controls without a competing focus request.
- Provider layout changes could pause the active media during mini/PiP transitions. Presentation now protects established playback while retaining the provider's video DOM, and still respects explicit user and platform pauses.
- Navigating to another video during a pending restore could leave the native player state stuck on the previous video. Restore targets are now reconciled against navigation, stale document callbacks are rejected, and waits are bounded without overwriting the saved checkpoint.

## Live playback checks

| Check | Observed result |
| --- | --- |
| YouTube screen off | Unmuted playback advanced from approximately 77.8 to 99.0 seconds while the device was dozing; foreground media service and partial wake lock were present. |
| Music screen off | Unmuted playback advanced from approximately 17.4 to 40.0 seconds while locked. |
| System media controls | Pause held its timestamp; Play resumed Music. |
| Music process restart | The saved Music section, track and approximately 70-second checkpoint restored. After preroll/loading, playback was unmuted with current-frame data and advanced beyond 73 seconds. |
| Portrait PiP | Android reported pinned mode; the video rendered in the floating window, and playback continued unmuted. Returning to Shelby retained the player. |
| Native mini player | The video continued while browsing underneath. Visible native controls paused at 22.21 seconds and resumed beyond 30 seconds; PiP entry also worked from this session. |
| Paused Music checkpoint | Switching back to Music restored 91.34 seconds, unmuted and paused, respecting the deliberate pause. |
| Shorts | Clicking an actual rewritten Shorts card opened `/watch?v=2NNvq9y5cA4`. Later checks at 12 and 26 seconds showed one unmuted, playing video and `shorts-player=false`, without a second reel route. |
| SponsorBlock lookup | The native bridge returned an empty community response for the Blender test video without interrupting playback. |
| SponsorBlock skip and Undo | A local, controlled sponsor interval skipped 124.19 → 132.02 seconds; the notice's Undo button returned to 124.19 seconds with playback running and unmuted. The original response was restored afterwards. No segment was submitted to SponsorBlock. |

## Automated checks

JavaScript fixtures cover intent, buffering, pause/focus handling, exact-media restoration, timeout/retry, stale routes, surface restoration, Shorts taps, sponsor skipping/Undo, toggle races and ad mute/speed cleanup. Native instrumentation uses controlled offline fixtures for Activity/session lifecycle and service delivery; it complements the real playback checks above.

- `assemblePreview`, `assembleDebug`, `assemblePreviewAndroidTest`, and `lintPreview`: passed. Lint reports warnings but no errors.
- JavaScript regression suite: **71 passed**.
- Standalone Java audio-focus policy: **6 checks passed**.
- Pixel instrumentation: **6 passed** via `adb shell am instrument -w com.samarth.ytcleanplayer.preview.test/android.test.InstrumentationTestRunner`. These cover retained document state through Activity recreation, paused Music cold selection, Music redirect/new-route reconciliation, bounded native restore/retry, service command delivery, and explicit dismissal without notification resurrection.
- `git diff --check`: passed.
- Installed Preview reports version **2.0.0-preview**, version code **32**.

The final asset-only cleanup removes empty Shorts shelf headings on Home. On the installed build, both Shorts shelves measured zero height with `display:none` while 47 ordinary feed item nodes remained. The final build also guards paused Music checkpoints against a late provider reset to zero and excludes Home feed previews from saved playback sessions. The full JavaScript suite, lint, and six native instrumentation tests passed on the final build.

## Scope

These checks establish the specific behavior observed on this Pixel. They do not establish hour-long endurance, every OEM's battery policy, all network transitions, phone-call interruption, every account/region, or landscape PiP behavior. SponsorBlock skips submitted `sponsor` segments; the controlled skip test is not evidence that every real sponsorship has a community segment. Shelby still uses WebView for provider content and media.

## Side-chat changes

The user requested a separate check of the floating section switch, draggable mini player and drag-to-dismiss. The current UI replaces the earlier top toolbar with a floating **♫ Music / ← YouTube** switch.

- The switch navigated between the YouTube and Music sections. It remained accessible above the keyboard when search was open.
- Dragging moved the player from `(422,1804)` to `(21,873)` without pausing playback. A further drag clamped its right edge at screen width 1080, keeping the full 626-pixel player visible.
- Dropping on the red **×** removed the video and stopped its audio.
- Testing caught two regressions: the drag listener consumed ordinary video/Undo taps, and dismissal left an active paused media session. The touch listener now cancels a click only after a drag crosses the system touch threshold. Native STOP now removes the service and media controls, and rejects late paused updates after dismissal.

Final device retest: a video tap expanded the mini player while playback continued past 15 seconds. A drag moved it to `(21,871)` with playback continuing beyond 23 seconds. Dropping on the red target returned to YouTube Home; `MediaPlaybackService`, the Shelby media session and notification ID 41 were all absent afterwards. No fatal exception appeared in the checked device log. All three requested side-chat behaviors passed after these two regression fixes.

## September 20, 2026 — playback controls and fresh tasks

Build 33 / 2.0.1-preview:

- JavaScript regression suite: 77 passing tests, including 10/20-second seeks, slider and mobile overlay seek intent, pause/resume, quality changes, speed validation.
- Pixel 9: eight Android instrumentation tests passed, including same-task retention/new-task reset and fullscreen orientation restoration.
- Live YouTube on Pixel 9: 10/20-second native skips continued playback; paused seek stayed paused and explicit Play resumed. Mobile Play video control and slider scrubbing also worked.
- Fullscreen entered landscape and Back restored portrait while playback continued. The temporary Fill option was subsequently removed at the user’s request; original aspect-ratio sizing is retained.
- The live provider accepted 720p (decoded video changed to 1280×720) and 1.5× speed while playback continued. Fullscreen settings were visually inspected on the phone.
- Swiping the playing app out of Recents cleared playback_session preferences. The next launch opened Music with no restored track or playback position.
- Preview/debug APK builds and Preview lint passed. These checks cover this Pixel and the tested videos; quality options depend on the streams YouTube exposes for each video.

## September 20, 2026 — audio details

Build 34 / 2.0.2-preview: 79 JavaScript tests passed; Preview/debug APK builds and Preview lint passed. Regression cases cover active format matching, stale statistics and metadata, ambiguous audio-format IDs, advertisements, unavailable methods, and omission of playback-session identifiers.

Installed on the connected Pixel 9. The live Music track reported active Opus format 251, 141.083 kbps average / 156.569 kbps advertised bitrate, 48 kHz, stereo. The native panel matched those values, refreshed buffering while playing, and preserved its scroll position during refresh. Both the settings entry and the detailed panel were visually inspected. No High-quality format was exposed for this test track/account; no audio-quality upgrade is claimed.
