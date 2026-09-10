// SponsorBlock community data: https://sponsor.ajay.app / https://wiki.sponsor.ajay.app/w/API_Docs
// Native AndroidSponsorBlock owns network requests and the persistent enabled setting.
// requestSegments(videoId) -> __shelbySponsorBlock.setSegments(videoId, API response array).
(() => {
  'use strict';
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  if (window.__shelbySponsorBlock) { window.__shelbySponsorBlock.refresh(); return; }

  const cache = new Map();
  const requested = new Set();
  let videoId = '';
  let segments = [];
  let ignored = new Set();
  let enabled = false;
  let refreshPending = false;
  let notice = null;
  let noticeTimer = 0;
  let lastSkip = null;
  try { enabled = window.AndroidSponsorBlock?.isEnabled() === true; } catch (_) {}

  function currentVideoId() {
    try {
      const url = new URL(location.href);
      const id = url.pathname === '/watch' ? url.searchParams.get('v') : '';
      return /^[\w-]{11}$/.test(id || '') ? id : '';
    } catch (_) { return ''; }
  }

  function hideNotice() {
    clearTimeout(noticeTimer);
    notice?.remove();
    notice = null;
  }

  function undo() {
    const previous = lastSkip;
    if (!previous || previous.videoId !== currentVideoId() || !previous.media.isConnected) return;
    ignored.add(previous.segment.key);
    lastSkip = null;
    hideNotice();
    try { previous.media.currentTime = previous.time; } catch (_) {}
  }

  function ensureNoticeStyle() {
    if (!document.documentElement || document.getElementById('shelby-sponsor-notice-style')) return;
    const style = document.createElement('style');
    style.id = 'shelby-sponsor-notice-style';
    style.textContent = `
      html[data-shelby-surface-mode="mini"] #shelby-sponsor-notice {
        left: 6px !important; right: 6px !important; bottom: 52px !important;
        transform: none !important; max-width: none !important;
        gap: 4px !important; padding: 4px 8px !important;
        border-radius: 10px !important; font-size: 11px !important;
      }
      html[data-shelby-surface-mode="mini"] #shelby-sponsor-notice [data-shelby-sponsor-brand] {
        display: none !important;
      }
      html[data-shelby-surface-mode="mini"] #shelby-sponsor-notice button {
        font-size: 12px !important; padding: 0 4px !important;
        min-height: 36px !important; margin-left: auto !important;
      }
      html[data-shelby-surface-mode="pip"] #shelby-sponsor-notice { display: none !important; }
    `;
    document.documentElement.appendChild(style);
  }

  function showNotice() {
    hideNotice();
    if (!document.body) return;
    ensureNoticeStyle();
    notice = document.createElement('div');
    notice.id = 'shelby-sponsor-notice';
    notice.setAttribute('role', 'status');
    notice.setAttribute('aria-live', 'polite');
    notice.style.cssText = 'position:fixed;z-index:2147483647;left:50%;bottom:max(84px,env(safe-area-inset-bottom));' +
      'transform:translateX(-50%);display:flex;align-items:center;gap:16px;' +
      'max-width:calc(100vw - 32px);padding:12px 16px;border-radius:16px;' +
      'background:#252b34;color:#f5f7fa;box-shadow:0 6px 24px #0006;' +
      'font:500 13px/1.4 system-ui,sans-serif;';
    const label = document.createElement('span');
    const brand = document.createElement('span');
    brand.setAttribute('data-shelby-sponsor-brand', '');
    brand.textContent = 'SponsorBlock · ';
    const caption = document.createElement('span');
    caption.textContent = 'Sponsor skipped';
    label.append(brand, caption);
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = 'Undo';
    button.setAttribute('aria-label', 'Undo sponsor skip');
    button.style.cssText = 'border:0;background:transparent;color:#acd0ff;' +
      'font:600 14px system-ui;padding:10px 4px;min-height:44px;cursor:pointer;';
    button.addEventListener('click', event => {
      event.preventDefault();
      event.stopPropagation();
      undo();
    });
    notice.append(label, button);
    const fullscreen = document.fullscreenElement;
    const parent = fullscreen && !/^(VIDEO|AUDIO)$/.test(fullscreen.tagName)
      ? fullscreen : document.body;
    parent.appendChild(notice);
    noticeTimer = setTimeout(hideNotice, 6500);
  }

  function sanitize(data) {
    if (!Array.isArray(data)) return [];
    const valid = data.slice(0, 1000).filter(item => item && item.category === 'sponsor'
      && (!item.actionType || item.actionType === 'skip')
      && Array.isArray(item.segment) && item.segment.length === 2
      && item.segment.every(time => typeof time === 'number' && Number.isFinite(time))
      && item.segment[0] >= 0 && item.segment[1] > item.segment[0])
      .map(item => ({ start: item.segment[0], end: item.segment[1],
        duration: Number(item.videoDuration) || 0 }))
      .sort((a, b) => a.start - b.start);
    const merged = [];
    for (const item of valid) {
      const previous = merged[merged.length - 1];
      // Overlaps should produce one seek and one undo action, not a chain of notices.
      if (previous && item.start <= previous.end && item.duration === previous.duration) {
        previous.end = Math.max(previous.end, item.end);
      } else merged.push({ ...item });
    }
    return merged.map(item => ({ ...item, key: `${item.start}:${item.end}` }));
  }

  function refresh() {
    refreshPending = false;
    const next = currentVideoId();
    if (next !== videoId) {
      videoId = next;
      ignored = new Set();
      lastSkip = null;
      hideNotice();
    }
    segments = cache.get(videoId) || [];
    if (!enabled || !videoId || requested.has(videoId)) return;
    if (!window.AndroidSponsorBlock?.requestSegments) return;
    requested.add(videoId);
    // Bound session memory. Entries are fetched only on a genuine revisit after eviction.
    if (requested.size > 64) {
      const oldest = requested.values().next().value;
      requested.delete(oldest);
      cache.delete(oldest);
    }
    try { window.AndroidSponsorBlock.requestSegments(videoId); }
    catch (_) { /* Fail open; no repeating network timers or interference with playback. */ }
  }

  function scheduleRefresh() {
    if (refreshPending) return;
    refreshPending = true;
    setTimeout(refresh, 0);
  }

  function playerFor(media) {
    return media.closest?.('.html5-video-player') || document.getElementById('movie_player')
      || document.querySelector('ytmusic-player');
  }

  function check(media) {
    if (!media || !/^(VIDEO|AUDIO)$/.test(media.tagName)) return;
    if (videoId !== currentVideoId()) refresh();
    if (!enabled || !videoId || !segments.length || media.paused || media.ended
        || media.seeking || media.readyState < 2 || !media.isConnected) return;
    if (media.closest?.('ytd-reel-video-renderer,ytm-reel-video-renderer,' +
        'ytd-shorts,ytm-shorts-container')) return;
    // Use the same established/current-ID player as the native session. YouTube can
    // retain an older .html5-main-video before the current one in document order.
    const main = window.__shelbyPlayback?.activeMedia?.()
      || document.querySelector('video.html5-main-video,audio.html5-main-video');
    if (main && main !== media) return;
    const player = playerFor(media);
    if (player?.classList.contains('ad-showing') || player?.classList.contains('ad-interrupting')
        || (player?.tagName === 'YTMUSIC-PLAYER' && player.hasAttribute('ad-showing'))) return;
    try {
      // During SPA transitions the URL can change before the previous media is replaced.
      const actualId = player?.getVideoData?.()?.video_id;
      if (actualId && actualId !== videoId) return;
    } catch (_) { return; }
    const duration = Number(media.duration);
    const time = Number(media.currentTime);
    if (!Number.isFinite(duration) || duration <= 0 || !Number.isFinite(time)) return;
    const segment = segments.find(item => !ignored.has(item.key) && time >= item.start
      && time < item.end - 0.05 && item.end <= duration + 0.5
      && (!item.duration || Math.abs(item.duration - duration) <= 2));
    if (!segment) return;
    if (lastSkip?.videoId === videoId && lastSkip.segment.key === segment.key
        && Date.now() - lastSkip.at < 1500) return;
    const end = Math.min(segment.end + 0.02, duration);
    try {
      media.currentTime = end;
      lastSkip = { videoId, media, segment, time, at: Date.now() };
      showNotice();
    } catch (_) { /* A non-seekable/live player must continue unaffected. */ }
  }

  window.__shelbySponsorBlock = {
    refresh,
    setEnabled(value) {
      const previouslyEnabled = enabled;
      enabled = value === true;
      if (!enabled) { hideNotice(); lastSkip = null; }
      if (enabled && !previouslyEnabled) {
        // Native suppresses delivery while disabled, but may already have cached the
        // response. Re-request unresolved IDs on demand; native deduplicates requests
        // still in flight. Completed JS cache entries never need another lookup.
        for (const id of requested) {
          if (!cache.has(id)) requested.delete(id);
        }
      }
      refresh();
      if (enabled) document.querySelectorAll('video,audio').forEach(check);
    },
    setSegments(id, data) {
      if (!/^[\w-]{11}$/.test(id || '') || !requested.has(id)) return;
      cache.set(id, sanitize(data));
      if (id !== currentVideoId()) return;
      segments = cache.get(id);
      document.querySelectorAll('video,audio').forEach(check);
    },
    undo
  };

  ['timeupdate', 'playing', 'seeked', 'loadedmetadata', 'durationchange'].forEach(type => {
    document.addEventListener(type, event => {
      if (videoId !== currentVideoId() || type === 'loadedmetadata') refresh();
      check(event.target);
    }, true);
  });
  ['yt-navigate-finish', 'yt-page-data-updated', 'yt-player-updated', 'DOMContentLoaded']
    .forEach(type => document.addEventListener(type, scheduleRefresh));
  ['popstate', 'pageshow'].forEach(type => window.addEventListener(type, scheduleRefresh));
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function () {
      const result = original.apply(this, arguments);
      scheduleRefresh();
      return result;
    };
  }
  refresh();
})();
