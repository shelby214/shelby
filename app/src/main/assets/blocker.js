// ==UserScript==
// @name         YouTube Zero Ads (Safari)
// @namespace    local.youtube.zero-ads
// @version      2.1.0
// @description  Prevents YouTube ads and dismisses ad-block enforcement interruptions, with instant-skip fallbacks.
// @author       You
// @match        https://www.youtube.com/*
// @match        https://m.youtube.com/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  'use strict';

  const PATCH_FLAG = '__ytZeroAdsV21Patched';
  const DETECTION_FLAG_PATTERN = /(ad.?block|adblock|ab[_-]?(det|rsp|ref))/i;
  const AD_RESPONSE_KEYS = new Set([
    'adPlacements',
    'playerAds',
    'adSlots',
    'adBreakParams',
    'adBreakHeartbeatParams',
    'adParams'
  ]);

  const scrubAdData = (value, seen = new WeakSet()) => {
    if (!value || typeof value !== 'object' || seen.has(value)) return value;
    seen.add(value);

    for (const key of Object.keys(value)) {
      if (AD_RESPONSE_KEYS.has(key)) {
        try {
          delete value[key];
        } catch (_) {}
      } else if (DETECTION_FLAG_PATTERN.test(key) && typeof value[key] === 'boolean') {
        try {
          value[key] = false;
        } catch (_) {}
      } else {
        scrubAdData(value[key], seen);
      }
    }
    return value;
  };

  const installEarlyResponseFilter = () => {
    if (window[PATCH_FLAG]) return;
    window[PATCH_FLAG] = true;

    // Catch embedded player data before YouTube constructs the player.
    let initialPlayerResponse = scrubAdData(window.ytInitialPlayerResponse);
    try {
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        enumerable: true,
        get: () => initialPlayerResponse,
        set: (value) => {
          initialPlayerResponse = scrubAdData(value);
        }
      });
    } catch (_) {}

    // Catch XHR/text responses that YouTube parses itself.
    const nativeParse = JSON.parse.bind(JSON);
    JSON.parse = (text, reviver) => {
      const value = nativeParse(text, reviver);
      if (
        typeof text === 'string' &&
        (text.includes('"adPlacements"') ||
          text.includes('"playerAds"') ||
          text.includes('"adSlots"') ||
          text.includes('"adBlockerDetection') ||
          text.includes('"web_enable_ab_'))
      ) {
        scrubAdData(value);
      }
      return value;
    };

    // Catch fetch-based /youtubei/v1/player responses without replacing the
    // Response object or delaying ordinary YouTube requests.
    if (typeof Response !== 'undefined') {
      const nativeResponseJson = Response.prototype.json;
      const nativeResponseText = Response.prototype.text;

      Response.prototype.json = async function () {
        const value = await nativeResponseJson.call(this);
        if (this.url.includes('/youtubei/v1/player')) scrubAdData(value);
        return value;
      };

      Response.prototype.text = async function () {
        const text = await nativeResponseText.call(this);
        if (
          this.url.includes('/youtubei/v1/player') &&
          (text.includes('"adPlacements"') ||
            text.includes('"playerAds"') ||
            text.includes('"adSlots"') ||
            text.includes('"adBlockerDetection') ||
            text.includes('"web_enable_ab_'))
        ) {
          try {
            const value = nativeParse(text);
            scrubAdData(value);
            return JSON.stringify(value);
          } catch (_) {}
        }
        return text;
      };
    }
  };

  installEarlyResponseFilter();

  const STYLE_ID = 'yt-zero-ads-style';
  const AD_SELECTORS = [
    '.video-ads',
    '.ytp-ad-module',
    '.ytp-ad-overlay-container',
    '.ytp-ad-player-overlay',
    '.ytp-ad-image-overlay',
    'ytd-ad-slot-renderer',
    'ytd-display-ad-renderer',
    'ytd-promoted-video-renderer',
    'ytd-in-feed-ad-layout-renderer',
    'ytd-banner-promo-renderer',
    'ytd-companion-slot-renderer',
    'ytd-action-companion-ad-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer',
    'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]',
    '#masthead-ad',
    '#player-ads',
    '#panels ytd-ads-engagement-panel-content-renderer',
    'ytm-promoted-sparkles-web-renderer',
    'ytm-companion-ad-renderer'
  ];

  const SKIP_SELECTORS = [
    '.ytp-ad-skip-button-modern',
    '.ytp-ad-skip-button',
    '.ytp-skip-ad-button',
    'button.ytp-ad-skip-button-modern',
    'button[id*="skip-button"]'
  ];

  const disableAdBlockDetectionFlags = () => {
    const flagGroups = [
      window.yt?.config_?.EXPERIMENT_FLAGS,
      window.ytcfg?.data_?.EXPERIMENT_FLAGS,
      window.ytcfg?.data_?.EXPERIMENTS_FORCED_FLAGS
    ];

    for (const flags of flagGroups) {
      if (!flags || typeof flags !== 'object') continue;
      for (const key of Object.keys(flags)) {
        if (DETECTION_FLAG_PATTERN.test(key)) flags[key] = false;
      }
    }
  };

  const dismissedVideos = new Set();
  const dismissAdBlockEnforcement = () => {
    const messages = document.querySelectorAll('ytd-enforcement-message-view-model');
    if (!messages.length) return false;

    for (const message of messages) {
      const errorScreen = message.closest('#error-screen');
      const dialog = message.closest('tp-yt-paper-dialog');
      (errorScreen || dialog || message).remove();
    }

    document.querySelectorAll('tp-yt-iron-overlay-backdrop.opened').forEach((backdrop) => {
      backdrop.remove();
    });

    document.documentElement.style.removeProperty('overflow');
    document.body?.style.removeProperty('overflow');

    const player = document.getElementById('movie_player');
    const video = document.querySelector('video.html5-main-video') || document.querySelector('video');
    try {
      player?.playVideo?.();
      video?.play?.().catch(() => {});
    } catch (_) {}

    // A hard-block screen may arrive without attaching the requested media.
    // Re-request that video once, after the detection flags have been disabled.
    const videoId = new URL(location.href).searchParams.get('v');
    if (videoId && !dismissedVideos.has(videoId) && (!video || video.readyState === 0)) {
      dismissedVideos.add(videoId);
      window.setTimeout(() => {
        try {
          player?.loadVideoById?.(videoId);
        } catch (_) {}
      }, 100);
    }

    return true;
  };

  const addStyles = () => {
    if (!document.documentElement || document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `${AD_SELECTORS.join(',')} { display: none !important; visibility: hidden !important; opacity: 0 !important; }`;
    (document.head || document.documentElement).appendChild(style);
  };

  const playerIsShowingAd = () => {
    const player = document.getElementById('movie_player');
    const watch = document.querySelector('ytd-watch-flexy');
    return Boolean(
      player?.classList.contains('ad-showing') ||
      player?.classList.contains('ad-interrupting') ||
      watch?.hasAttribute('ad-showing')
    );
  };

  const clickSkipButtons = () => {
    for (const selector of SKIP_SELECTORS) {
      document.querySelectorAll(selector).forEach((button) => {
        if (button instanceof HTMLElement) button.click();
      });
    }
  };

  const skipCurrentAd = () => {
    clickSkipButtons();
    if (!playerIsShowingAd()) return false;

    const video = document.querySelector('video.html5-main-video') || document.querySelector('video');
    if (!video) return true;

    video.muted = true;
    video.playbackRate = 16;

    const duration = Number(video.duration);
    if (Number.isFinite(duration) && duration > 0) {
      try {
        video.currentTime = Math.max(0, duration - 0.05);
      } catch (_) {
        // A newly inserted ad may not be seekable yet; the next pass retries.
      }
    }

    if (video.paused) video.play().catch(() => {});
    return true;
  };

  const restorePlayback = () => {
    if (playerIsShowingAd()) return;
    const video = document.querySelector('video.html5-main-video') || document.querySelector('video');
    if (video && video.playbackRate === 16) video.playbackRate = 1;
  };

  let fastTimer = 0;
  const runFastPass = () => {
    window.clearTimeout(fastTimer);
    disableAdBlockDetectionFlags();
    dismissAdBlockEnforcement();
    const adFound = skipCurrentAd();
    restorePlayback();
    fastTimer = window.setTimeout(runFastPass, adFound ? 25 : 350);
  };

  addStyles();
  new MutationObserver(() => {
    addStyles();
    disableAdBlockDetectionFlags();
    dismissAdBlockEnforcement();
    skipCurrentAd();
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class', 'ad-showing']
  });

  document.addEventListener('yt-navigate-finish', () => {
    addStyles();
    skipCurrentAd();
  });

  runFastPass();
})();
