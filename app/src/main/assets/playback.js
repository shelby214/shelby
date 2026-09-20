// Shelby's page-side playback controller. Inject at document start in the main WebView.
// Native owns the foreground service, audio focus, WebView lifetime and PiP geometry.
(() => {
  'use strict';

  if (window !== window.top || location.protocol !== 'https:' ||
      !['youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com'].includes(location.hostname)) return;
  if (window.__shelbyPlayback) {
    window.__shelbyPlayback.send();
    return;
  }
  const documentId = String(window.performance?.timeOrigin || Date.now()) + ':' + Math.random().toString(36).slice(2);

  const nativePause = HTMLMediaElement.prototype.pause;
  const nativePlay = HTMLMediaElement.prototype.play;
  const mediaRecords = new WeakMap();
  const internalPauseEvents = new WeakMap();
  const providerPauseEvents = new WeakMap();
  const state = {
    seekIntent: null, media: null, wantsPlay: false, pausedByUser: false, nativeBackground: false, presentation: false,
    suspended: false, platformPaused: false, resumeAfterFocus: false, internalPause: 0,
    navigating: false, intentVideoId: '', intentSource: '', endedVideoId: '',
    lastUrl: location.href, lastSnapshot: null, pendingRestore: null, restoreFailure: null, restoreCheckpoint: null,
    restoreTimer: 0, mutationTimer: 0, lastRecovery: 0,
    ducked: false, duckMedia: null, savedVolume: 1, trustedPlayUntil: 0,
    lastUserEventAt: 0, lastUserEventPlay: null
  };

  const finite = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;
  const videoIdFromUrl = href => {
    try {
      const url = new URL(href, location.href);
      return url.searchParams.get('v') || (url.pathname.match(/^\/(?:shorts|embed)\/([\w-]+)/) || [])[1] || '';
    } catch (_) { return ''; }
  };
  const safeId = value => typeof value === 'string' && /^[\w-]{11}$/.test(value) ? value : '';
  const routeId = () => safeId(videoIdFromUrl(location.href));
  const isAd = media => Boolean(
    media?.closest?.('.ad-showing,.ad-interrupting') ||
    document.querySelector('#movie_player.ad-showing,#movie_player.ad-interrupting,ytmusic-player[ad-showing]')
  );

  function providerId(media) {
    // Never use a stale global ytInitialPlayerResponse: it survives SPA navigation.
    const container = media?.closest?.('.html5-video-player');
    const music = document.querySelector('ytmusic-player');
    const player = document.getElementById('movie_player');
    const candidates = [container, music?.playerApi, music?.player, player];
    for (const api of candidates) {
      if (!api) continue;
      // A global player must actually contain this media; otherwise it could be a preview.
      if (api === player && typeof api.contains === 'function' && !api.contains(media)) continue;
      try {
        const id = safeId(api.getVideoData?.()?.video_id) || safeId(videoIdFromUrl(api.getVideoUrl?.() || ''));
        if (id) return id;
      } catch (_) { /* The provider can replace its player during navigation. */ }
    }
    return safeId(media?.getAttribute?.('data-video-id'));
  }

  function activeMedia() {
    const all = [...document.querySelectorAll('video,audio')].filter(media => {
      if (media.isConnected === false) return false;
      if (location.hostname === 'music.youtube.com' || (location.pathname === '/watch' && routeId())) return true;
      // Home/feed inline previews are not a playback session. Only a full watch player
      // that was already established may continue playing while browsing in this document.
      const record = mediaRecords.get(media);
      return Boolean(record?.establishedWatch && !media.paused && !media.ended &&
        providerId(media) === record.id && (media.currentSrc || media.src || '') === record.source);
    });
    if (!all.length) { state.media = null; return null; }
    const main = all.filter(media => media.matches?.('.html5-main-video') ||
      media.closest?.('#movie_player,ytmusic-player,ytm-player'));
    const pool = main.length ? main : all;
    const playing = pool.filter(media => !media.paused && !media.ended);
    const matching = playing.find(media => providerId(media) === routeId() && routeId());
    // Prefer a newly started main player if YouTube replaces the old element during navigation.
    // Otherwise retain a buffering player instead of jumping to an autoplay feed preview.
    if (matching && (state.media?.paused || state.media?.ended || !pool.includes(state.media))) {
      state.media = matching;
    } else if (playing.length && (!state.media || state.media.paused || state.media.ended || !pool.includes(state.media))) {
      state.media = playing[0];
    }
    if (state.media && pool.includes(state.media) && !state.media.ended) return state.media;
    state.media = playing[0] || pool.find(media => {
      const rect = media.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    }) || pool[0];
    return state.media;
  }

  // Read actual visibility before providing the foreground view expected by YouTube's player.
  let hiddenGetter;
  for (let proto = document; proto && !hiddenGetter; proto = Object.getPrototypeOf(proto)) {
    hiddenGetter = Object.getOwnPropertyDescriptor(proto, 'hidden')?.get;
  }
  const realHidden = () => {
    try { return hiddenGetter ? Boolean(hiddenGetter.call(document)) : false; }
    catch (_) { return false; }
  };
  const inBackground = () => state.nativeBackground || realHidden();
  const foregroundForPlayback = () => inBackground() && state.wantsPlay &&
    !state.pausedByUser && !state.suspended;
  for (const [name, hidden] of [['hidden', true], ['webkitHidden', true],
    ['visibilityState', false], ['webkitVisibilityState', false]]) {
    try {
      Object.defineProperty(document, name, {
        configurable: true,
        get: () => hidden ? (!foregroundForPlayback() && realHidden()) :
          (foregroundForPlayback() || !realHidden() ? 'visible' : 'hidden')
      });
    } catch (_) { /* Older WebViews may not allow a visibility override. */ }
  }
  const nativeHasFocus = document.hasFocus?.bind(document);
  document.hasFocus = () => foregroundForPlayback() || Boolean(nativeHasFocus?.());

  function pauseInternally(media) {
    if (!media) return;
    // HTMLMediaElement dispatches pause asynchronously, after the call stack unwinds.
    if (!media.paused) internalPauseEvents.set(media, (internalPauseEvents.get(media) || 0) + 1);
    state.internalPause++;
    try { nativePause.call(media); }
    finally { state.internalPause--; }
  }

  function playSafely(media) {
    if (!media || state.suspended || state.platformPaused || state.pausedByUser || state.restoreFailure) return;
    try { Promise.resolve(nativePlay.call(media)).catch(() => {}); }
    catch (_) { /* A detached or unavailable media element is retried on canplay. */ }
  }

  function matchesIntent(media) {
    if (!media || media !== state.media || media.ended || state.navigating || isAd(media)) return false;
    const id = providerId(media);
    const expected = routeId();
    if (id && expected && id !== expected) return false;
    if (id && state.intentVideoId && id !== state.intentVideoId) return false;
    const source = media.currentSrc || media.src || '';
    return Boolean((id && id === state.intentVideoId) || (source && source === state.intentSource));
  }

  function shouldKeepPlaying(media) {
    return (inBackground() || state.presentation) && state.wantsPlay && !state.pausedByUser && !state.suspended && !state.platformPaused &&
      !state.pendingRestore && !state.restoreFailure && matchesIntent(media);
  }

  function recoverPlayback() {
    const media = activeMedia();
    if (!shouldKeepPlaying(media) || !media.paused || media.readyState < 2 || media.error) return;
    // A rejection (network, focus, provider error) must not create a hot retry loop.
    if (Date.now() - state.lastRecovery < 1500) return;
    state.lastRecovery = Date.now();
    playSafely(media);
  }

  HTMLMediaElement.prototype.pause = function (...args) {
    if (!state.internalPause && shouldKeepPlaying(this)) return;
    // Calls originating in the page can be distinguished from Chromium's own audio-focus
    // pauses, which dispatch a media event without invoking this JavaScript method.
    if (!this.paused) providerPauseEvents.set(this, (providerPauseEvents.get(this) || 0) + 1);
    return nativePause.apply(this, args);
  };

  function rememberPlayback(media) {
    const id = providerId(media) || routeId();
    const expected = routeId();
    if (providerId(media) && expected && providerId(media) !== expected) return false;
    state.intentVideoId = id;
    state.intentSource = media.currentSrc || media.src || '';
    const previous = mediaRecords.get(media);
    const url = expected === id && location.pathname === '/watch' ? location.href :
      (previous?.id === id ? previous.url : '') || new URL('/watch?v=' + encodeURIComponent(id), location.origin).href;
    mediaRecords.set(media, { id, source: state.intentSource, url,
      establishedWatch: (location.pathname === '/watch' && expected === id) || Boolean(previous?.establishedWatch && previous.id === id) });
    return true;
  }

  function snapshot() {
    const media = activeMedia();
    const suppliedId = media && !isAd(media) ? providerId(media) : '';
    const expectedId = routeId();
    const mismatched = suppliedId && expectedId && suppliedId !== expectedId;
    const validMedia = media && !isAd(media) && !mismatched;
    const id = validMedia ? suppliedId || expectedId || mediaRecords.get(media)?.id || '' : '';
    const rect = validMedia ? media.getBoundingClientRect() : null;
    const record = media && mediaRecords.get(media);
    const mediaUrl = id ? (expectedId === id && location.pathname === '/watch' ? location.href :
      (record?.id === id ? record.url : '') || new URL('/watch?v=' + encodeURIComponent(id), location.origin).href) : '';
    return {
      documentId,
      url: location.href,
      mediaUrl,
      title: (document.title || 'Shelby').replace(/\s*[-–]\s*YouTube(?: Music)?\s*$/, '').trim(),
      playing: Boolean(validMedia && !media.paused && !media.ended && media.readyState >= 2 && !state.suspended && !state.platformPaused),
      buffering: Boolean(validMedia && !media.paused && !media.ended && media.readyState < 2 &&
        state.wantsPlay && !state.pausedByUser && !state.suspended && !state.platformPaused && !state.restoreFailure),
      readyState: validMedia ? finite(media.readyState) : 0,
      pausedByUser: state.pausedByUser,
      wantsPlay: Boolean(validMedia && state.wantsPlay),
      ended: Boolean(validMedia && media.ended),
      position: validMedia ? Math.max(0, finite(media.currentTime)) : 0,
      duration: validMedia ? Math.max(0, finite(media.duration)) : 0,
      width: validMedia ? Math.max(1, finite(media.videoWidth, 16) || 16) : 16,
      height: validMedia ? Math.max(1, finite(media.videoHeight, 9) || 9) : 9,
      videoId: id,
      mediaIdVerified: Boolean(suppliedId && !mismatched),
      restoring: Boolean(state.pendingRestore || state.restoreCheckpoint?.repairing),
      restoreCheckpoint: state.restoreCheckpoint ? { videoId: state.restoreCheckpoint.id, position: state.restoreCheckpoint.position } : null,
      restoreFailed: Boolean(state.restoreFailure),
      restoreFailureReason: state.restoreFailure?.reason || '',
      restoreTarget: state.restoreFailure?.saved || null,
      suspended: state.suspended,
      platformPaused: state.platformPaused,
      navigating: state.navigating || Boolean(mismatched),
      ad: Boolean(media && isAd(media)),
      scrollX: Math.max(0, finite(window.scrollX)),
      scrollY: Math.max(0, finite(window.scrollY)),
      rect: rect ? {
        x: Math.round(finite(rect.left)), y: Math.round(finite(rect.top)),
        width: Math.max(0, Math.round(finite(rect.width))), height: Math.max(0, Math.round(finite(rect.height)))
      } : { x: 0, y: 0, width: 0, height: 0 }
    };
  }

  function send(force = false, includeProgress = true) {
    checkRestoreCheckpoint();
    const current = snapshot();
    const comparable = { ...current, position: Math.floor(current.position) };
    if (!includeProgress && state.lastSnapshot) {
      comparable.position = state.lastSnapshot.position;
      comparable.scrollX = state.lastSnapshot.scrollX;
      comparable.scrollY = state.lastSnapshot.scrollY;
    }
    const key = JSON.stringify(comparable);
    if (!force && key === JSON.stringify(state.lastSnapshot)) return current;
    if (window.AndroidMedia?.onSnapshot) {
      try { window.AndroidMedia.onSnapshot(JSON.stringify(current)); state.lastSnapshot = comparable; }
      catch (_) { /* Native may detach the bridge while destroying the Activity. */ }
    }
    return current;
  }

  function cancelRestore() {
    clearTimeout(state.restoreTimer);
    state.restoreTimer = 0;
    state.pendingRestore = null;
  }

  function checkRestoreCheckpoint() {
    const checkpoint = state.restoreCheckpoint;
    if (!checkpoint || state.pendingRestore || state.restoreFailure) return;
    const media = activeMedia();
    if (!media || routeId() !== checkpoint.id || providerId(media) !== checkpoint.id || isAd(media)) return;
    const position = finite(media.currentTime);
    if (media.ended || (!media.paused && !media.seeking && media.readyState >= 2 &&
        state.wantsPlay && !state.pausedByUser && position > checkpoint.position + 0.5)) {
      state.restoreCheckpoint = null; // Real intended playback has advanced past the saved seek.
      return;
    }
    if (Math.abs(position - checkpoint.position) <= 0.75 && !media.seeking && media.readyState >= 2) {
      if (checkpoint.repairing) {
        checkpoint.repairing = false; checkpoint.seekIssued = false; checkpoint.started = 0;
        if (checkpoint.resume && state.wantsPlay && !state.pausedByUser) playSafely(media);
      }
      return;
    }
    if (!checkpoint.repairing) {
      checkpoint.repairing = true;
      checkpoint.started = Date.now();
      checkpoint.resume = state.wantsPlay && !state.pausedByUser && !state.platformPaused && !state.suspended;
    }
    const fail = () => {
      state.restoreFailure = { reason: 'timeline-reset', saved: { ...checkpoint.saved, position: checkpoint.position } };
      state.restoreCheckpoint = null;
    };
    if (Date.now() - checkpoint.started > 5000) { fail(); return; }
    if (checkpoint.seekIssued || media.seeking || media.readyState < 1 || !media.seekable?.length) return;
    if (checkpoint.attempts >= 2) { fail(); return; }
    let reachable = false;
    for (let index = 0; index < media.seekable.length; index++) {
      if (checkpoint.position >= media.seekable.start(index) && checkpoint.position <= media.seekable.end(index)) reachable = true;
    }
    if (!reachable) return;
    checkpoint.attempts++;
    checkpoint.seekIssued = true;
    pauseInternally(media);
    try { media.currentTime = checkpoint.position; }
    catch (_) { fail(); }
  }

  function attemptRestore() {
    clearTimeout(state.restoreTimer);
    state.restoreTimer = 0;
    const pending = state.pendingRestore;
    if (!pending) return;
    if (location.origin !== pending.origin || (routeId() && routeId() !== pending.id)) {
      cancelRestore(); send(true); return;
    }
    if (Date.now() > pending.deadline) {
      // A finite attempt must not overwrite the durable timestamp with a stalled
      // preroll/loading position. Native retains this target and offers explicit retry.
      const saved = { ...pending.saved };
      if (pending.id) saved.videoId = pending.id;
      delete saved.restoreTarget;
      delete saved.restoreFailed;
      delete saved.restoreFailureReason;
      state.restoreFailure = { reason: 'timeout', saved };
      cancelRestore(); send(true); return;
    }
    if (!pending.id) {
      if (document.readyState === 'loading') {
        state.restoreTimer = setTimeout(attemptRestore, 200); return;
      }
      window.scrollTo(finite(pending.saved.scrollX), finite(pending.saved.scrollY));
      cancelRestore(); send(true); return;
    }
    const media = activeMedia();
    // URL equality alone is insufficient: YouTube reuses a player containing the old video.
    // Wait for the provider to identify the exact saved content before any seek or play.
    if (!media || isAd(media) || providerId(media) !== pending.id ||
        routeId() !== pending.id || media.readyState < 1 || !media.seekable?.length) {
      state.restoreTimer = setTimeout(attemptRestore, 250); return;
    }
    const duration = finite(media.duration);
    const target = Math.max(0, Math.min(finite(pending.saved.position), duration > 0 ? Math.max(0, duration - 0.25) : Infinity));
    let seekable = false;
    for (let index = 0; index < media.seekable.length; index++) {
      if (target >= media.seekable.start(index) && target <= media.seekable.end(index)) seekable = true;
    }
    if (!seekable) { state.restoreTimer = setTimeout(attemptRestore, 250); return; }
    if (!pending.sought || pending.media !== media) {
      pauseInternally(media);
      pending.media = media;
      pending.sought = true;
      if (Math.abs(finite(media.currentTime) - target) > 0.5) {
        try { media.currentTime = target; }
        catch (_) { pending.sought = false; }
      }
    }
    if (!pending.sought || media.seeking || media.readyState < 2 ||
        Math.abs(finite(media.currentTime) - target) > 0.75) {
      state.restoreTimer = setTimeout(attemptRestore, 250); return;
    }
    rememberPlayback(media);
    state.wantsPlay = Boolean((pending.saved.wantsPlay ?? pending.saved.playing) && !pending.saved.pausedByUser && !pending.saved.ended);
    state.pausedByUser = Boolean(pending.saved.pausedByUser);
    const shouldPlay = state.wantsPlay;
    state.restoreCheckpoint = target > 0 ? {
      id: pending.id, position: target, saved: { ...pending.saved, videoId: pending.id, position: target },
      attempts: 0, repairing: false, seekIssued: false, started: 0, resume: false
    } : null;
    cancelRestore();
    if (shouldPlay) playSafely(media);
    else pauseInternally(media);
    send(true);
  }

  function restore(saved) {
    if (!saved || typeof saved !== 'object') return false;
    let url;
    try { url = new URL(saved.mediaUrl || saved.url); } catch (_) { return false; }
    if (url.protocol !== 'https:' || url.origin !== location.origin) return false;
    const id = safeId(saved.videoId) || safeId(videoIdFromUrl(url.href));
    if (id && routeId() !== id) return false;
    if (!id && (url.pathname !== location.pathname || url.search !== location.search)) return false;
    cancelRestore();
    state.restoreCheckpoint = null;
    state.restoreFailure = null;
    state.platformPaused = false;
    state.pendingRestore = { id, origin: url.origin, saved: { ...saved }, deadline: Date.now() + 45000, sought: false };
    state.pausedByUser = Boolean(saved.pausedByUser);
    state.wantsPlay = Boolean((saved.wantsPlay ?? saved.playing) && !saved.pausedByUser && !saved.ended);
    attemptRestore();
    return true;
  }

  function retryRestore(play = true) {
    if (!state.restoreFailure) return false;
    const saved = { ...state.restoreFailure.saved, playing: Boolean(play), wantsPlay: Boolean(play), pausedByUser: !play, ended: false };
    return restore(saved);
  }

  // Provider controls temporarily pause while scrubbing. Keep the pre-seek intent,
  // but never override a subsequent explicit pause or a Chromium focus interruption.
  function beginUserSeek(media) {
    if (!media) return;
    const previous = state.seekIntent;
    state.seekIntent = { media, until: Date.now() + 5000,
      resume: previous?.media === media && previous.until >= Date.now() ? previous.resume :
        (!state.pausedByUser && !state.platformPaused && (!media.paused || state.wantsPlay)) };
    state.restoreCheckpoint = null;
    state.restoreFailure = null;
    cancelRestore();
  }

  function seekingWithIntent(media) {
    return state.seekIntent?.media === media && state.seekIntent.until >= Date.now();
  }

  function seek(media, seconds) {
    if (!media || !media.seekable?.length || !Number.isFinite(Number(seconds))) return false;
    const desired = Math.max(0, Number(seconds));
    const end = finite(media.duration, Infinity);
    const target = Math.min(desired, end);
    for (let index = 0; index < media.seekable.length; index++) {
      if (target >= media.seekable.start(index) && target <= media.seekable.end(index)) {
        try { beginUserSeek(media); media.currentTime = target; state.restoreCheckpoint = null; state.restoreFailure = null; send(true); return true; } catch (_) { return false; }
      }
    }
    return false;
  }

  function command(name, value) {
    const media = activeMedia();
    switch (name) {
      case 'toggle': return command(media && !media.paused && !media.ended ? 'pause' : 'play');
      case 'play':
        // Explicit play retries the original saved seek, rather than accepting the
        // stalled loading position as the user's new progress.
        if (state.restoreFailure) {
          state.suspended = false;
          return retryRestore(true);
        }
        if (state.restoreCheckpoint) {
          state.suspended = false;
          return restore({ ...state.restoreCheckpoint.saved, position: state.restoreCheckpoint.position,
            playing: true, wantsPlay: true, pausedByUser: false, ended: false });
        }
        if (state.pendingRestore) {
          state.suspended = false; state.platformPaused = false;
          state.pausedByUser = false; state.wantsPlay = true;
          Object.assign(state.pendingRestore.saved, { playing: true, wantsPlay: true, pausedByUser: false, ended: false });
          attemptRestore(); send(true);
          return true;
        }
        cancelRestore();
        state.pausedByUser = false; state.wantsPlay = true;
        // Native sends play only after the service has granted audio focus.
        state.suspended = false; state.platformPaused = false; state.resumeAfterFocus = false;
        state.trustedPlayUntil = Date.now() + 1500;
        if (media) {
          rememberPlayback(media);
          if (media.ended) seek(media, 0);
          playSafely(media);
        }
        break;
      case 'pause':
      case 'stop':
        state.seekIntent = null;
        cancelRestore();
        state.pausedByUser = true; state.wantsPlay = false; state.platformPaused = false; state.resumeAfterFocus = false;
        if (state.restoreFailure) Object.assign(state.restoreFailure.saved, { playing: false, wantsPlay: false, pausedByUser: true });
        pauseInternally(media);
        break;
      case 'seek': cancelRestore(); return seek(media, value);
      case 'seekBy': cancelRestore(); return seek(media, finite(media?.currentTime) + finite(value));
      case 'suspend':
        state.seekIntent = null;
        if (!state.suspended) state.resumeAfterFocus = state.wantsPlay && !state.pausedByUser;
        state.suspended = true;
        pauseInternally(media);
        break;
      case 'resume': {
        const resume = state.suspended && state.resumeAfterFocus && state.wantsPlay && !state.pausedByUser;
        state.suspended = false; state.resumeAfterFocus = false;
        if (resume && matchesIntent(media)) playSafely(media);
        break;
      }
      case 'duck':
        if (media && !state.ducked) {
          state.ducked = true; state.duckMedia = media;
          state.savedVolume = finite(media.volume, 1);
          media.volume = state.savedVolume * 0.2;
        }
        break;
      case 'unduck':
        if (state.ducked && state.duckMedia) state.duckMedia.volume = state.savedVolume;
        state.ducked = false; state.duckMedia = null;
        break;
      case 'next':
      case 'previous': {
        const selector = name === 'next' ?
          'ytmusic-player-bar .next-button,.ytp-next-button' :
          'ytmusic-player-bar .previous-button,.ytp-prev-button';
        const button = document.querySelector(selector);
        if (!button) return false;
        prepareNavigation();
        button.click();
        break;
      }
      default: return false;
    }
    send(true);
    return true;
  }

  function setBackground(background) {
    state.nativeBackground = Boolean(background);
    if (background) {
      const media = activeMedia();
      // Capture a currently playing track when the native lifecycle wins the event race.
      if (media && !media.paused && !media.ended && !state.pausedByUser && !state.pendingRestore && !isAd(media)) {
        state.wantsPlay = true; rememberPlayback(media);
      }
      recoverPlayback();
    }
    send(true);
  }

  function setPresentation(active) {
    // Resizing the existing player into native mini/PiP can trigger provider layout
    // pauses even while the Activity is foreground. Explicit and platform pauses win.
    state.presentation = Boolean(active);
  }

  function checkNavigation() {
    if (state.lastUrl === location.href) return;
    const oldId = safeId(videoIdFromUrl(state.lastUrl));
    state.lastUrl = location.href;
    if (state.pendingRestore && routeId() !== state.pendingRestore.id) cancelRestore();
    if (state.restoreCheckpoint && routeId() !== state.restoreCheckpoint.id) state.restoreCheckpoint = null;
    if (state.restoreFailure && routeId() !== safeId(state.restoreFailure.saved.videoId)) state.restoreFailure = null;
    if (routeId() && routeId() !== oldId) {
      state.navigating = true;
      state.pausedByUser = false;
      state.intentSource = '';
    }
  }

  function prepareNavigation() {
    cancelRestore();
    state.restoreCheckpoint = null;
    state.restoreFailure = null;
    state.navigating = true;
    send(true);
  }

  function onMediaEvent(event) {
    const media = event.target;
    if (!(media instanceof HTMLMediaElement)) return;
    const internalPause = event.type === 'pause' && (internalPauseEvents.get(media) || 0) > 0;
    if (internalPause) internalPauseEvents.set(media, internalPauseEvents.get(media) - 1);
    const providerPause = event.type === 'pause' && !internalPause && (providerPauseEvents.get(media) || 0) > 0;
    if (providerPause) providerPauseEvents.set(media, providerPauseEvents.get(media) - 1);
    checkNavigation();
    const selected = activeMedia();
    if (media !== selected) return;
    const id = providerId(media);
    const expected = routeId();
    if (id && expected && id !== expected) { send(false, false); return; }
    if (isAd(media)) { send(false, false); return; }
    if (['loadedmetadata', 'canplay', 'playing'].includes(event.type) && (!expected || id === expected)) {
      state.navigating = false;
    }
    if (event.type === 'pause' && !internalPause && !providerPause && !state.suspended &&
        !state.pausedByUser && !media.ended && (state.pendingRestore || !state.navigating)) {
      // Focus can be lost while metadata/seek restoration is still pending. Record it
      // before attemptRestore so completing a seek cannot force playback over a call.
      state.platformPaused = true;
    }
    if ((event.type === 'play' || event.type === 'playing') && !state.suspended && !state.pausedByUser) {
      state.platformPaused = false;
    }
    if (state.pendingRestore) { attemptRestore(); send(false, false); return; }
    if (event.type === 'play' || event.type === 'playing') {
      if (state.suspended || (state.pausedByUser && Date.now() > state.trustedPlayUntil &&
          (!id || id === state.intentVideoId))) {
        pauseInternally(media); send(true); return;
      }
      // Chromium may resume on audio-focus gain. Preserve that native decision instead
      // of treating the earlier interruption as a deliberate user pause.
      state.platformPaused = false;
      state.pausedByUser = false;
      state.wantsPlay = true;
      state.endedVideoId = '';
      rememberPlayback(media);
    } else if (event.type === 'pause') {
      // Chromium owns audio focus. A raw pause may mean a call or another media app;
      // never fight it with a play watchdog. Only an actual later play event or explicit
      // user play clears this interruption. Page visibility pauses are handled above.
      if (!internalPause && !state.internalPause && !state.suspended && !state.navigating && !media.ended) {
        if (!providerPause && !state.pausedByUser) {
          state.platformPaused = true;
        } else if (providerPause && !inBackground() && !seekingWithIntent(media)) {
          state.pausedByUser = true; state.wantsPlay = false;
          state.intentVideoId = id || expected;
        }
      }
    } else if (['seeked', 'canplay'].includes(event.type) && seekingWithIntent(media)) {
      if (state.seekIntent.resume) playSafely(media);
    } else if (event.type === 'ended') {
      // Preserve queue intent, but never restart the ended track from a watchdog.
      state.endedVideoId = id || state.intentVideoId;
    } else if (event.type === 'canplay' && state.endedVideoId && id && id !== state.endedVideoId && id === expected) {
      rememberPlayback(media);
      state.endedVideoId = '';
      recoverPlayback();
    }
    send(false, false);
  }

  function settingsPlayer() {
    const music = document.querySelector('ytmusic-player');
    return music?.playerApi || music?.player || document.getElementById('movie_player');
  }
  function audioDetails() {
    const media = activeMedia(), player = settingsPlayer();
    const read = name => { try { return player?.[name]?.(); } catch (_) { return null; } };
    const stats = read('getStatsForNerds') || {};
    const response = read('getPlayerResponse') || {};
    const id = providerId(media);
    const verified = Boolean(media && id && id === response.videoDetails?.videoId && !isAd(media));
    // Stats identify the currently selected format. Available formats alone cannot
    // tell us which audio stream is playing, and may belong to an earlier SPA track.
    const statsId = String(stats.video_id_and_cpn || '').split(' / ')[0].trim();
    const currentStats = verified && statsId === id;
    const audioCodec = currentStats ? String(stats.codecs || '').split(' / ').at(-1) : '';
    const formatId = audioCodec.match(/\((\d+)\)\s*$/)?.[1];
    const formats = verified && Array.isArray(response.streamingData?.adaptiveFormats)
      ? response.streamingData.adaptiveFormats.filter(f => /^audio\//.test(f.mimeType || '')) : [];
    const matches = formats.filter(f => String(f.itag) === formatId);
    const active = matches.length === 1 ? matches[0] : null;
    const positive = value => Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : null;
    const describe = format => ({
      formatId: format.itag,
      codec: format.mimeType?.match(/codecs="([^"]+)"/)?.[1] || null,
      container: format.mimeType?.split(';')[0] || null,
      averageBitrate: positive(format.averageBitrate), bitrate: positive(format.bitrate),
      sampleRate: positive(format.audioSampleRate), channels: positive(format.audioChannels),
      quality: { AUDIO_QUALITY_LOW: 'Low', AUDIO_QUALITY_MEDIUM: 'Normal', AUDIO_QUALITY_HIGH: 'High' }[format.audioQuality] || null
    });
    let bufferedSeconds = null;
    try {
      for (let i=0; i<media.buffered.length; i++) {
        if (media.currentTime >= media.buffered.start(i) && media.currentTime <= media.buffered.end(i)) {
          bufferedSeconds = Math.max(0, media.buffered.end(i) - media.currentTime); break;
        }
      }
    } catch (_) {}
    const config = verified ? response.playerConfig?.audioConfig || {} : {};
    return {
      title: verified ? response.videoDetails?.title || '' : '',
      artist: verified ? response.videoDetails?.author || '' : '',
      status: !media ? 'No active track' : isAd(media) ? 'Advertisement' : media.paused ? 'Paused' : media.readyState < 3 ? 'Buffering' : 'Playing',
      source: active ? 'Matched to the active audio stream' : 'Detailed stream metadata unavailable',
      active: active ? describe(active) : null,
      codecDisplay: currentStats ? audioCodec.replace(/\s*\(\d+\)\s*$/, '') : null,
      formats: formats.map(describe),
      highQualityAvailable: formats.some(f => f.audioQuality === 'AUDIO_QUALITY_HIGH'),
      speed: media?.playbackRate || 1,
      muted: Boolean(media?.muted), volumePercent: media ? Math.round(media.volume * 100) : null,
      bufferedSeconds,
      networkEstimate: currentStats && typeof stats.bandwidth_kbps === 'string' ? stats.bandwidth_kbps.slice(0,64) : null,
      normalization: currentStats && typeof stats.volume === 'string' ? stats.volume.slice(0,120) : null,
      loudnessLkfs: Number.isFinite(config.trackAbsoluteLoudnessLkfs) ? config.trackAbsoluteLoudnessLkfs : null,
      targetLkfs: Number.isFinite(config.loudnessTargetLkfs) ? config.loudnessTargetLkfs : null
    };
  }
  function playbackOptions() {
    const player = settingsPlayer();
    let qualities = [], quality = 'auto';
    try { qualities = player?.getAvailableQualityLevels?.() || []; quality = player?.getPlaybackQuality?.() || 'auto'; } catch (_) {}
    return { speed: activeMedia()?.playbackRate || 1, qualities, quality, available: Boolean(activeMedia()) };
  }
  function setSpeed(value) {
    const speed = Number(value), media = activeMedia();
    if (!media || ![0.25,0.5,0.75,1,1.25,1.5,1.75,2].includes(speed)) return false;
    try { settingsPlayer()?.setPlaybackRate?.(speed); media.playbackRate = speed; return true; } catch (_) { return false; }
  }
  function setQuality(value) {
    const player = settingsPlayer();
    if (!player || !playbackOptions().qualities.includes(value)) return false;
    try {
      // Quality changes can replace the stream and temporarily pause it like a seek.
      beginUserSeek(activeMedia());
      player.setPlaybackQualityRange?.(value);
      player.setPlaybackQuality(value);
      return true;
    } catch (_) { return false; }
  }
  const controller = { audioDetails, playbackOptions, setSpeed, setQuality, documentId, command, setBackground, setPresentation, restore, retryRestore, send, activeMedia, prepareNavigation };
  window.__shelbyPlayback = controller;
  window.__ytCleanMediaMonitor = controller;
  window.__shelbyEnterBackground = () => setBackground(true);

  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'canplay',
    'seeking', 'seeked', 'timeupdate', 'emptied', 'waiting', 'stalled', 'error'].forEach(type => document.addEventListener(type, onMediaEvent, true));

  function visibilityEvent(event) {
    send(true);
    if (foregroundForPlayback()) event.stopImmediatePropagation();
    if (inBackground()) recoverPlayback();
  }
  ['visibilitychange', 'webkitvisibilitychange', 'freeze'].forEach(type => {
    window.addEventListener(type, visibilityEvent, true);
    document.addEventListener(type, visibilityEvent, true);
  });
  window.addEventListener('pagehide', () => send(true), true);
  window.addEventListener('pageshow', () => { send(true); recoverPlayback(); }, true);

  // Capture intent before YouTube's handlers. A deliberate pause must also work in PiP
  // and while a visibility transition is in flight.
  function userIntent(play) {
    state.seekIntent = null;
    state.pausedByUser = !play;
    state.wantsPlay = play;
    state.platformPaused = false;
    if (play) {
      state.trustedPlayUntil = Date.now() + 1500;
      if (state.suspended) state.resumeAfterFocus = true;
    } else state.resumeAfterFocus = false;
    if (play && state.pendingRestore) {
      Object.assign(state.pendingRestore.saved, { playing: true, wantsPlay: true, pausedByUser: false, ended: false });
      attemptRestore();
    } else cancelRestore();
    // Only trusted page events use this bridge. Native command() never echoes back,
    // which avoids a service -> WebView -> service transport recursion.
    if (state.lastUserEventPlay !== play || Date.now() - state.lastUserEventAt >= 150) {
      state.lastUserEventPlay = play;
      state.lastUserEventAt = Date.now();
      try { window.AndroidMedia?.onUserPlaybackIntent?.(play); } catch (_) {}
    }
    setTimeout(() => send(true), 0);
  }
  document.addEventListener('click', event => {
    if (!event.isTrusted) return;
    const media = activeMedia();
    const target = event.target;
    if (target?.closest?.('[role="slider"],input[type="range"],.ytp-progress-bar-container,#progress-bar')) {
      beginUserSeek(media);
      return;
    }
    // Mini surface taps are expand gestures; its click handler runs after this document
    // capture listener. Do not misclassify the tap (or entry swipe's click) as pause.
    if (state.presentation && target === media) return;
    const label = (target?.closest?.('button,[role="button"]')?.getAttribute?.('aria-label') || '').toLowerCase();
    if (!media || (!target?.closest?.('.ytp-play-button,#play-pause-button,.play-pause-button') &&
        !/^(?:play|pause)(?:$|\s)/.test(label))) return;
    const pause = label.includes('pause') || (!label.includes('play') && !media.paused);
    userIntent(!pause);
  }, true);
  document.addEventListener('keydown', event => {
    if (event.isTrusted && ['ArrowLeft', 'ArrowRight', 'j', 'J', 'l', 'L'].includes(event.key) &&
        !event.target?.closest?.('input,textarea,[contenteditable="true"]')) beginUserSeek(activeMedia());
    if (!event.isTrusted || ![' ', 'k', 'K', 'MediaPlayPause'].includes(event.key) ||
        event.target?.closest?.('input,textarea,[contenteditable="true"]')) return;
    const media = activeMedia();
    if (!media) return;
    userIntent(media.paused);
  }, true);

  let miniGesture = null;
  const userSeekGesture = event => {
    if (!event.isTrusted) return;
    const media = activeMedia();
    if (event.target === media || event.target?.closest?.('[role="slider"],input[type="range"],.ytp-progress-bar-container,#progress-bar,.html5-video-player,#movie_player,.player-controls-background')) beginUserSeek(media);
  };
  document.addEventListener('pointerdown', userSeekGesture, { capture: true, passive: true });
  document.addEventListener('touchstart', userSeekGesture, { capture: true, passive: true });
  document.addEventListener('touchstart', event => {
    if (event.touches.length !== 1) { miniGesture = null; return; }
    const media = activeMedia();
    const touch = event.touches[0];
    const rect = media?.getBoundingClientRect();
    const within = rect && touch.clientX >= rect.left && touch.clientX <= rect.right &&
      touch.clientY >= rect.top && touch.clientY <= rect.bottom;
    miniGesture = within ? { x: touch.clientX, y: touch.clientY, time: Date.now() } : null;
  }, { capture: true, passive: true });
  document.addEventListener('touchcancel', () => { miniGesture = null; }, { capture: true, passive: true });
  document.addEventListener('touchend', event => {
    if (!miniGesture || !event.changedTouches.length) return;
    const gesture = miniGesture;
    const touch = event.changedTouches[0];
    miniGesture = null;
    const elapsed = Date.now() - gesture.time;
    if (elapsed >= 120 && elapsed <= 1000 && touch.clientY - gesture.y >= 70 &&
        Math.abs(touch.clientX - gesture.x) < 120) window.AndroidMedia?.onMiniPlayerRequested?.();
  }, { capture: true, passive: true });

  for (const name of ['pushState', 'replaceState']) {
    const original = history[name];
    history[name] = function (...args) {
      const result = original.apply(this, args);
      checkNavigation(); send(false, false);
      return result;
    };
  }
  ['popstate', 'hashchange'].forEach(type => window.addEventListener(type, () => { checkNavigation(); send(true); }));
  document.addEventListener('yt-navigate-start', () => { state.navigating = true; send(true); });
  ['yt-navigate-finish', 'yt-page-data-updated'].forEach(type => document.addEventListener(type, () => {
    checkNavigation(); state.navigating = false; attemptRestore(); send(true);
  }));

  function observe() {
    if (!document.documentElement) return;
    new MutationObserver(() => {
      if (state.mutationTimer) return;
      state.mutationTimer = setTimeout(() => {
        state.mutationTimer = 0;
        checkNavigation(); attemptRestore(); send(false, false);
      }, 250);
    }).observe(document.documentElement, { childList: true, subtree: true });
    const media = activeMedia();
    if (media && !media.paused && !media.ended && !isAd(media)) {
      state.wantsPlay = true; rememberPlayback(media);
    }
    send(true);
  }
  if (document.documentElement) observe();
  else document.addEventListener('DOMContentLoaded', observe, { once: true });
  setInterval(() => { checkNavigation(); attemptRestore(); recoverPlayback(); send(); }, 2000);
})();
