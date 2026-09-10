// Shelby: route Shorts to the regular watch player before YouTube handles the tap.
// Install at document start; safe to reinject after an SPA/page navigation.
(() => {
  'use strict';
  if (window.top !== window || !/(^|\.)youtube\.com$/.test(location.hostname)
      || location.hostname === 'music.youtube.com') return;
  if (window.__ytCleanShortsPolicy) {
    window.__ytCleanShortsPolicy.schedule();
    return;
  }

  const SHORTS_TARGET = 'data-shelby-shorts-target';
  const REELS = 'ytm-reel-video-renderer,ytd-reel-video-renderer,ytm-shorts-player,' +
    'ytm-shorts-container,ytd-shorts';
  const SHORTS_CARDS = 'ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,' +
    'yt-shorts-lockup-view-model,ytd-shorts-lockup-view-model,' +
    'ytm-reel-item-renderer,ytd-reel-item-renderer';
  const SHORTS_SHELVES = 'ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,' +
    'ytm-rich-shelf-renderer[is-shorts],ytd-rich-shelf-renderer[is-shorts]';
  const CARDS = SHORTS_CARDS + ',ytm-rich-item-renderer,ytd-rich-item-renderer,' +
    'ytm-video-with-context-renderer,ytm-compact-video-renderer,ytd-compact-video-renderer,' +
    'ytm-video-renderer,ytd-video-renderer,ytm-lockup-view-model,yt-lockup-view-model,' +
    'ytm-video-lockup-view-model,yt-video-lockup-view-model,' +
    'ytm-playlist-video-renderer,ytd-playlist-video-renderer';
  let pending = false;
  let navigating = false;
  let lastTarget = '';
  let lastNavigation = 0;
  let touch = null;

  function normalUrl(href) {
    try {
      const url = new URL(href, location.href);
      if (!/(^|\.)youtube\.com$/.test(url.hostname)) return null;
      const match = /^\/shorts\/([\w-]{11})(?:\/|$)/.exec(url.pathname);
      if (!match) return null;
      const target = new URL('https://m.youtube.com/watch');
      target.searchParams.set('v', match[1]);
      const start = url.searchParams.get('t') || url.searchParams.get('start');
      if (start && /^\d+(?:h\d*m?\d*s?|m\d*s?|s)?$/.test(start)) {
        target.searchParams.set('t', start);
      }
      return target.href;
    } catch (_) { return null; }
  }

  function targetFor(link) {
    if (!link) return null;
    const direct = normalUrl(link.href);
    if (direct) return direct;
    // Keep the identity after href rewriting: YouTube's endpoint data still says "reel".
    const saved = link.getAttribute(SHORTS_TARGET);
    if (saved === link.href) return saved;
    // YouTube recycles cards. A newly assigned regular video must remain a regular link.
    if (saved) link.removeAttribute(SHORTS_TARGET);
    return null;
  }

  function eventLink(event) {
    const path = event.composedPath?.() || [event.target];
    for (const node of path) {
      const link = node?.closest?.('a[href]');
      if (link) return link;
    }
    return null;
  }

  function pauseReels() {
    document.querySelectorAll('video').forEach(video => {
      if (!navigating && !video.closest(REELS)) return;
      // Do not mute: the same media element can later be reused by the regular player.
      try {
        const pause = video.__shelbyOriginalPause || video.__ytCleanMiniOriginalPause || video.pause;
        pause.call(video);
      } catch (_) {}
    });
  }

  function navigate(target, replace) {
    if (target === lastTarget && Date.now() - lastNavigation < 1500) return;
    lastTarget = target;
    lastNavigation = Date.now();
    if (window.AndroidBrowser?.openVideo) {
      pauseReels();
      window.AndroidBrowser.openVideo(target);
      return;
    }
    navigating = true;
    window.__shelbyPlayback?.prepareNavigation?.();
    pauseReels();
    if (replace) location.replace(target);
    else location.assign(target);
  }

  function intercept(event) {
    const target = targetFor(eventLink(event));
    if (!target) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    navigate(target, false);
  }

  // Window capture runs before YouTube's delegated document/click handlers.
  window.addEventListener('click', intercept, true);
  window.addEventListener('touchstart', event => {
    const point = event.touches?.[0];
    touch = event.touches?.length === 1 && point
      ? { x: point.clientX, y: point.clientY, link: eventLink(event) } : null;
  }, { capture: true, passive: true });
  window.addEventListener('touchcancel', () => { touch = null; }, true);
  window.addEventListener('touchend', event => {
    const start = touch;
    touch = null;
    const point = event.changedTouches?.[0];
    if (!start || !point || start.link !== eventLink(event)
        || Math.abs(point.clientX - start.x) > 14
        || Math.abs(point.clientY - start.y) > 14) return;
    // YouTube mobile can navigate on touchend, before a synthetic click even exists.
    intercept(event);
  }, { capture: true, passive: false });

  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function (state, title, url) {
      const target = url == null ? null : normalUrl(String(url));
      if (target) {
        navigate(target, true);
        return;
      }
      const result = original.apply(this, arguments);
      schedule();
      return result;
    };
  }

  function installStyle() {
    if (!document.documentElement || document.getElementById('shelby-shorts-style')) return;
    const style = document.createElement('style');
    style.id = 'shelby-shorts-style';
    const feedShorts = [SHORTS_CARDS, SHORTS_SHELVES, '[data-shelby-shorts-card]']
      .join(',').split(',').map(selector => `html[data-shelby-hide-shorts] ${selector}`).join(',\n');
    style.textContent = `
      ${REELS} { display: none !important; }
      ${feedShorts} { display: none !important; }
      /* Modern Home grid shelves own the heading and bottom spacer outside the
         individual Shorts cards. Keep any shelf that also contains normal videos. */
      html[data-shelby-home] ytm-rich-section-renderer:has(> .rich-section-content > grid-shelf-view-model :is(${SHORTS_CARDS},a[${SHORTS_TARGET}])):not(:has(a[href*="/watch"]:not([${SHORTS_TARGET}]))) {
        display: none !important;
      }
      html[data-shelby-home] ytm-post-renderer,
      html[data-shelby-home] ytm-backstage-post-renderer,
      html[data-shelby-home] ytd-post-renderer,
      html[data-shelby-home] ytd-backstage-post-renderer,
      [data-shelby-shorts-tab] { display: none !important; }
    `;
    document.documentElement.appendChild(style);
  }

  function run() {
    pending = false;
    const redirected = normalUrl(location.href);
    if (redirected) { navigate(redirected, true); return; }
    if (!document.documentElement) return;
    installStyle();
    // Search is the only discovery surface that may display Shorts. This also
    // covers related videos, subscriptions, channels, history, and mini browsing.
    document.documentElement.toggleAttribute('data-shelby-hide-shorts',
      location.pathname.replace(/\/$/, '') !== '/results');
    document.documentElement.toggleAttribute('data-shelby-home',
      location.pathname === '/' || location.pathname === '/feed/recommended');
    // Clear card markers as well: both feed cards and their anchors can be recycled.
    document.querySelectorAll('[data-shelby-shorts-card]').forEach(card => {
      if (![...card.querySelectorAll('a[href]')].some(targetFor)) {
        card.removeAttribute('data-shelby-shorts-card');
      }
    });
    document.querySelectorAll(`a[href*="/shorts/"],a[${SHORTS_TARGET}]`).forEach(link => {
      const target = targetFor(link);
      if (!target) return;
      link.setAttribute(SHORTS_TARGET, target);
      if (link.href !== target) link.href = target;
      link.closest(CARDS)?.setAttribute('data-shelby-shorts-card', '');
    });
    document.querySelectorAll('ytm-pivot-bar-item-renderer,' +
      'ytd-mini-guide-entry-renderer,ytd-guide-entry-renderer').forEach(item => {
      const href = item.querySelector('a[href]')?.href;
      let isShorts = item.textContent.trim() === 'Shorts';
      try { isShorts = isShorts || (href && new URL(href).pathname.startsWith('/shorts')); }
      catch (_) {}
      item.toggleAttribute('data-shelby-shorts-tab', Boolean(isShorts));
    });
    pauseReels();
  }

  function schedule() {
    if (pending) return;
    pending = true;
    setTimeout(run, 32);
  }

  window.__ytCleanShortsPolicy = { run, schedule };
  new MutationObserver(schedule).observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['href']
  });
  document.addEventListener('play', event => {
    if (navigating || event.target?.closest?.(REELS)) pauseReels();
  }, true);
  ['yt-navigate-start', 'yt-navigate-finish', 'yt-page-data-updated', 'DOMContentLoaded']
    .forEach(type => document.addEventListener(type, schedule));
  window.addEventListener('popstate', () => {
    const target = normalUrl(location.href);
    if (target) navigate(target, true);
    else schedule();
  });
  window.addEventListener('pageshow', () => { navigating = false; schedule(); });
  run();
})();
