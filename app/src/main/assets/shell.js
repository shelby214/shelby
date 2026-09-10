(() => {
  if (window !== top || !document.documentElement || document.getElementById('shelby-shell')) return;
  const style = document.createElement('style'); style.id = 'shelby-shell';
  style.textContent = `:root{color-scheme:dark;--yt-spec-base-background:#0f0f0f!important;--yt-spec-raised-background:#212121!important;--yt-spec-text-primary:#f1f1f1!important;--yt-spec-text-secondary:#aaa!important}
html,body{background:#0f0f0f!important;overscroll-behavior:none}body{-webkit-tap-highlight-color:transparent}button,a{touch-action:manipulation}video{background:#000}html:not(.shelby-video-surface){scroll-behavior:auto}
ytm-app,ytm-browse,ytm-search,ytm-watch,ytm-item-section-renderer,ytm-rich-grid-renderer,ytm-section-list-renderer,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer{background:#0f0f0f!important;color:#f1f1f1!important}
ytm-chip-cloud-chip-renderer button{background:#272727!important;color:#f1f1f1!important;border-color:#3f3f3f!important}
a[href^="intent://m.youtube.com/"],ytm-app-promo,ytm-mealbar-promo-renderer{display:none!important}
@media(prefers-reduced-motion:no-preference){button,a{transition:background-color .14s ease}}`;
  document.documentElement.appendChild(style);
  document.documentElement.setAttribute('dark','');
  document.documentElement.setAttribute('darker-dark-theme','');
})();
