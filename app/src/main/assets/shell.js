(() => {
  if (window !== top || !document.documentElement || document.getElementById('shelby-shell')) return;
  const style = document.createElement('style'); style.id = 'shelby-shell';
  style.textContent = `:root{color-scheme:dark;--yt-spec-base-background:#0f0f0f!important;--yt-spec-raised-background:#212121!important;--yt-spec-text-primary:#f1f1f1!important;--yt-spec-text-secondary:#aaa!important}
html,body{background:#0f0f0f!important;overscroll-behavior:none}body{-webkit-tap-highlight-color:transparent}button,a{touch-action:manipulation}video{background:#000}html:not(.shelby-video-surface){scroll-behavior:auto}
ytm-app,ytm-browse,ytm-search,ytm-watch,ytm-item-section-renderer,ytm-rich-grid-renderer,ytm-section-list-renderer,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer{background:#0f0f0f!important;color:#f1f1f1!important}
ytm-chip-cloud-chip-renderer button{background:#272727!important;color:#f1f1f1!important;border-color:#3f3f3f!important}
a[href^="intent://m.youtube.com/"],a[href^="vnd.youtube:"],ytm-app-promo,ytm-mealbar-promo-renderer,ytm-open-app-button{display:none!important}
@media(prefers-reduced-motion:no-preference){button,a{transition:background-color .14s ease}}`;
  document.documentElement.appendChild(style);
  document.documentElement.setAttribute('dark','');
  document.documentElement.setAttribute('darker-dark-theme','');
  // YouTube also renders Open App as a button with no intent URL. Handle that
  // variant and replacements created during in-page navigation.
  const hideOpenApp = () => {
    for (const control of document.querySelectorAll('a,button,[role="button"]')) {
      const text = (control.getAttribute('aria-label') || control.textContent || '').trim();
      if (/^open\s+(?:in\s+)?app$/i.test(text)) control.style.setProperty('display', 'none', 'important');
    }
  };
  hideOpenApp();
  let queued = false;
  new MutationObserver(() => {
    if (queued) return;
    queued = true;
    setTimeout(() => { queued = false; hideOpenApp(); }, 50);
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
