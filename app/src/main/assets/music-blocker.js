// Shelby - responsive YouTube Music ad and stale-overlay blocker.
(() => {
  'use strict';

  if (location.hostname !== 'music.youtube.com') return;
  if (window.__shelbyMusicBlocker) {
    window.__shelbyMusicBlocker.schedule(0);
    return;
  }

  const AD_SELECTORS = [
    '.video-ads',
    '.ytp-ad-module',
    '.ytp-ad-overlay-container',
    '.ytp-ad-player-overlay',
    '.ytp-ad-image-overlay',
    '#masthead-ad',
    '#player-ads',
    'ytmusic-player-queue-item[is-advertisement]',
    'ytmusic-player-queue-item[is-ad]',
    'ytmusic-responsive-list-item-renderer[is-advertisement]'
  ];

  const SKIP_SELECTORS = [
    '.ytp-ad-skip-button-modern',
    '.ytp-ad-skip-button',
    '.ytp-skip-ad-button',
    'button[aria-label*="Skip ad" i]',
    'button[aria-label*="Skip Ads" i]'
  ];

  const PROMO_PATTERN = /(youtube\s+music\s+premium|music\s+premium|try\s+premium|upgrade\s+to\s+premium|ad[- ]free)/i;
  const DISMISS_PATTERN = /(no thanks|not now|dismiss|close|maybe later|got it)/i;

  const addStyles = () => {
    if (!document.documentElement || document.getElementById('shelby-music-ad-style')) return;
    const style = document.createElement('style');
    style.id = 'shelby-music-ad-style';
    style.textContent = `${AD_SELECTORS.join(',')}{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important}`;
    document.documentElement.appendChild(style);
  };

  const visible = element => {
    if (!(element instanceof HTMLElement)) return false;
    const css = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return css.display !== 'none' && css.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
  };

  const blocker = {
    timer: 0,
    pendingTimer: 0,
    forcedMedia: null,
    previousMuted: false,
    previousRate: 1,

    player() {
      return document.getElementById('movie_player') || document.querySelector('ytmusic-player .html5-video-player')
        || document.querySelector('ytmusic-player');
    },

    isAd(player) {
      // Queue and app metadata can describe the next ad while a normal song is playing.
      // Only the live media player's ad state authorizes seeking, muting, or speeding up.
      return Boolean(
        player?.classList.contains('ad-showing') ||
        player?.classList.contains('ad-interrupting') ||
        (player?.tagName === 'YTMUSIC-PLAYER' && player.hasAttribute('ad-showing'))
      );
    },

    dismissPremiumUpsells() {
      document.querySelectorAll(
        'tp-yt-paper-dialog,ytmusic-dialog,ytmusic-popup-container,ytmusic-mealbar-promo-renderer'
      ).forEach(container => {
        if (!PROMO_PATTERN.test(container.textContent || '')) return;
        const buttons = [...container.querySelectorAll('button,[role="button"],tp-yt-paper-button')];
        const dismiss = buttons.find(button => DISMISS_PATTERN.test(
          `${button.textContent || ''} ${button.getAttribute('aria-label') || ''}`
        ));
        if (dismiss instanceof HTMLElement && visible(dismiss)) {
          dismiss.click();
        } else if (visible(container)) {
          container.closest('tp-yt-paper-dialog')?.close?.();
        }
      });
    },

    clearStaleClickShields() {
      const visibleDialog = [...document.querySelectorAll(
        'tp-yt-paper-dialog[opened],tp-yt-paper-dialog,ytd-popup-container tp-yt-paper-dialog'
      )].some(visible);

      if (visibleDialog) return;
      document.querySelectorAll(
        'tp-yt-iron-overlay-backdrop.opened,tp-yt-iron-overlay-backdrop[opened]'
      ).forEach(backdrop => {
        if (!(backdrop instanceof HTMLElement)) return;
        backdrop.classList.remove('opened');
        backdrop.removeAttribute('opened');
        backdrop.style.setProperty('display', 'none', 'important');
        backdrop.style.setProperty('pointer-events', 'none', 'important');
      });
      document.documentElement?.style.removeProperty('overflow');
      document.body?.style.removeProperty('overflow');
      document.body?.style.removeProperty('pointer-events');
    },

    clickAdSkip(player) {
      for (const selector of SKIP_SELECTORS) {
        player.querySelectorAll(selector).forEach(button => {
          if (button instanceof HTMLElement) button.click();
        });
      }
    },

    forcePastAd(media) {
      if (this.forcedMedia !== media) {
        this.restoreMedia();
        this.forcedMedia = media;
        this.previousMuted = media.muted;
        this.previousRate = media.playbackRate || 1;
      }
      try {
        media.muted = true;
        media.playbackRate = 16;
      } catch (_) { this.restoreMedia(); return; }
      const duration = Number(media.duration);
      if (Number.isFinite(duration) && duration > 0) {
        try { media.currentTime = Math.max(0, duration - 0.03); } catch (_) {}
      }
    },

    restoreMedia() {
      if (!this.forcedMedia) return;
      const media = this.forcedMedia;
      this.forcedMedia = null;
      try {
        if (media.muted === true) media.muted = this.previousMuted;
        if (media.playbackRate === 16) media.playbackRate = this.previousRate || 1;
      } catch (_) {}
    },

    run() {
      window.clearTimeout(this.timer);
      window.clearTimeout(this.pendingTimer);
      addStyles();
      this.dismissPremiumUpsells();
      this.clearStaleClickShields();

      const player = this.player();
      if (this.isAd(player)) this.clickAdSkip(player);
      // The Skip button can change the current player immediately.
      const ad = this.isAd(player);
      const media = player?.querySelector('video,audio');
      if (ad && media && !media.paused && !media.ended) this.forcePastAd(media);
      else this.restoreMedia();

      this.timer = window.setTimeout(() => this.run(), ad ? 100 : 1500);
    },

    schedule(delay = 120) {
      window.clearTimeout(this.pendingTimer);
      this.pendingTimer = window.setTimeout(() => this.run(), delay);
    }
  };

  window.__shelbyMusicBlocker = blocker;
  new MutationObserver(() => {
    // Clear mute/speed in this microtask as soon as the ad class disappears.
    if (!blocker.isAd(blocker.player())) blocker.restoreMedia();
    blocker.schedule();
  }).observe(document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class', 'ad-showing', 'opened']
  });
  document.addEventListener('yt-navigate-start', () => blocker.restoreMedia());
  document.addEventListener('yt-navigate-finish', () => {
    blocker.restoreMedia();
    blocker.schedule(0);
  });
  ['emptied', 'loadstart', 'loadedmetadata'].forEach(type => {
    document.addEventListener(type, event => {
      if (blocker.forcedMedia === event.target) blocker.restoreMedia();
    }, true);
  });
  document.addEventListener('playing', () => blocker.schedule(0), true);
  window.addEventListener('pagehide', () => {
    window.clearTimeout(blocker.timer);
    window.clearTimeout(blocker.pendingTimer);
    blocker.restoreMedia();
  });
  window.addEventListener('pageshow', () => blocker.schedule(0));
  blocker.run();
})();
