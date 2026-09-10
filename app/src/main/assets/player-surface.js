// Presentation only: the provider's video always remains in its original DOM subtree.
(() => {
  'use strict';
  if (window !== window.top || window.__shelbySurface) return;
  let mode = '', video = null, path = [], enteredAt = 0;
  const savedStyles = new Map();
  const savedAttributes = new Map();
  let observing = false;

  function patch(node, properties) {
    if (!savedStyles.has(node)) savedStyles.set(node, {
      absent: node.getAttribute('style') === null,
      properties: new Map()
    });
    const saved = savedStyles.get(node).properties;
    for (const [property, value] of Object.entries(properties)) {
      if (!saved.has(property)) saved.set(property, [node.style.getPropertyValue(property), node.style.getPropertyPriority(property)]);
      node.style.setProperty(property, value, 'important');
    }
  }

  function attribute(node, name, value) {
    if (!savedAttributes.has(node)) savedAttributes.set(node, new Map());
    const saved = savedAttributes.get(node);
    if (!saved.has(name)) saved.set(name, node.getAttribute(name));
    node.setAttribute(name, value);
  }

  function restorePresentation() {
    if (video) video.removeEventListener('click', expand, true);
    for (const [node, saved] of savedStyles) {
      for (const [property, [value, priority]] of saved.properties) {
        if (value) node.style.setProperty(property, value, priority);
        else node.style.removeProperty(property);
      }
      // Preserve provider changes to unrelated inline properties made during playback.
      if (saved.absent && node.style.length === 0) node.removeAttribute('style');
    }
    for (const [node, attributes] of savedAttributes) {
      for (const [name, value] of attributes) {
        if (value === null) node.removeAttribute(name);
        else node.setAttribute(name, value);
      }
    }
    savedStyles.clear(); savedAttributes.clear();
    video = null; path = [];
    document.documentElement?.classList.remove('shelby-video-surface');
  }

  function exit() {
    mode = '';
    observer.disconnect(); observing = false;
    restorePresentation();
    window.__shelbyPlayback?.setPresentation?.(false);
    return true;
  }

  function expand(event) {
    if (mode !== 'mini') return;
    event.preventDefault(); event.stopImmediatePropagation();
    // The swipe that entered mini can be followed by a synthetic click.
    if (Date.now() - enteredAt < 350) return;
    window.AndroidMedia?.onMiniPlayerExpandRequested?.();
  }

  function ancestors(media) {
    const result = [];
    for (let node = media?.parentElement; node && node !== document.documentElement; node = node.parentElement) result.push(node);
    return result;
  }

  function hideOtherBranches() {
    let child = video;
    for (const ancestor of path) {
      for (const sibling of ancestor.children) {
        if (sibling === child) continue;
        if (mode === 'mini' && sibling.getAttribute('id') === 'shelby-sponsor-notice') {
          // Keep the bounded SponsorBlock Undo notice usable above the mini video.
          patch(sibling, { opacity: '1', visibility: 'visible', 'pointer-events': 'auto', 'z-index': '2147483647' });
          continue;
        }
        // Opacity hides even descendants with inline visibility:visible!important.
        // Keep geometry intact so the provider doesn't recreate a collapsed player.
        patch(sibling, { opacity: '0', visibility: 'hidden', 'pointer-events': 'none' });
        attribute(sibling, 'inert', '');
      }
      child = ancestor;
    }
  }

  function refresh() {
    if (!mode || !document.documentElement) return false;
    const selected = window.__shelbyPlayback?.activeMedia?.() || document.querySelector('video');
    const candidate = selected?.tagName === 'VIDEO' && selected.isConnected !== false ? selected : null;
    const nextPath = ancestors(candidate);
    if (!candidate) {
      restorePresentation();
      return false;
    }
    const unchanged = candidate === video && path.length === nextPath.length && path.every((node, index) => node === nextPath[index]);
    if (!unchanged) {
      restorePresentation();
      video = candidate; path = nextPath;
      attribute(video, 'data-shelby-surface', '');
      patch(document.documentElement, { overflow: 'hidden', 'background-color': '#000', 'background-image': 'none' });
      for (const node of path) {
        patch(node, {
          transform: 'none', translate: 'none', rotate: 'none', scale: 'none',
          overflow: 'visible', contain: 'none', 'content-visibility': 'visible',
          'clip-path': 'none', clip: 'auto', filter: 'none', perspective: 'none',
          opacity: '1', visibility: 'hidden'
        });
      }
      // Inline longhands also override YouTube's inline sizing without destroying it.
      patch(video, {
        position: 'fixed', top: '0', right: '0', bottom: '0', left: '0',
        width: '100vw', height: '100vh', 'min-width': '0', 'min-height': '0',
        'max-width': 'none', 'max-height': 'none', 'object-fit': 'contain',
        'background-color': '#000', visibility: 'visible', opacity: '1',
        'z-index': '2147483647', transform: 'none', translate: 'none', rotate: 'none', scale: 'none',
        'margin-top': '0', 'margin-right': '0', 'margin-bottom': '0', 'margin-left': '0',
        'pointer-events': 'auto'
      });
      video.addEventListener('click', expand, true);
    }
    document.documentElement.classList.add('shelby-video-surface');
    attribute(document.documentElement, 'data-shelby-surface-mode', mode);
    hideOtherBranches();
    return true;
  }

  const observer = new MutationObserver(refresh);
  function enter(next) {
    if (next !== 'mini' && next !== 'pip') return false;
    window.__shelbyPlayback?.setPresentation?.(true);
    if (mode !== next) {
      enteredAt = Date.now();
      // Mode-specific exceptions (mini Undo vs. clean PiP) must not inherit the
      // previous mode's hidden/inert styles. The playback presentation guard stays on.
      restorePresentation();
    }
    mode = next;
    if (!observing && document.documentElement) {
      observer.observe(document.documentElement, { childList: true, subtree: true });
      observing = true;
    }
    return refresh();
  }
  // YouTube may start a replacement player after its insertion mutation has fired.
  ['play', 'playing', 'loadedmetadata', 'yt-navigate-finish'].forEach(type => document.addEventListener(type, refresh, true));
  window.__shelbySurface = { enter, exit };
})();
