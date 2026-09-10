// Run with: node --test tests/player-policies.test.cjs
// No external dependencies: a deterministic page/media fixture exercises the injected assets.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const ASSETS = path.join(__dirname, '../app/src/main/assets');
const FIRST = 'abcdefghijk';
const SECOND = 'zyxwvutsrqp';

function page(url = `https://m.youtube.com/watch?v=${FIRST}`, sponsorEnabled = true) {
  let address = new URL(url);
  const navigations = [];
  const requests = [];
  const timers = new Map();
  let timerId = 0;
  const location = {
    get href() { return address.href; },
    get hostname() { return address.hostname; },
    get pathname() { return address.pathname; },
    assign(value) { navigations.push(['assign', value]); },
    replace(value) { navigations.push(['replace', value]); }
  };
  class Target {
    constructor() { this.listeners = {}; }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    emit(type, target = this, extra = {}) {
      const event = { type, target, ...extra, defaultPrevented: false, immediate: false,
        composedPath: () => [target],
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() {},
        stopImmediatePropagation() { this.immediate = true; }
      };
      for (const listener of this.listeners[type] || []) {
        listener(event);
        if (event.immediate) break;
      }
      return event;
    }
  }
  class Element extends Target {
    constructor(tag) {
      super();
      this.tagName = tag.toUpperCase();
      this.attributes = {};
      this.children = [];
      this.parentNode = null;
      this.style = { removeProperty() {}, setProperty() {} };
      this.textContent = '';
      const classes = new Set();
      this.classList = { add: value => classes.add(value), remove: value => classes.delete(value),
        contains: value => classes.has(value) };
    }
    get id() { return this.getAttribute('id') || ''; }
    set id(value) { this.setAttribute('id', value); }
    get href() {
      const value = this.getAttribute('href');
      return value == null ? undefined : new URL(value, address).href;
    }
    set href(value) { this.setAttribute('href', value); }
    get isConnected() {
      let node = this;
      while (node) { if (node === document) return true; node = node.parentNode; }
      return false;
    }
    appendChild(node) { node.parentNode = this; this.children.push(node); return node; }
    append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
    remove() {
      if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(n => n !== this);
      this.parentNode = null;
    }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) { return this.attributes[name] ?? null; }
    hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
    removeAttribute(name) { delete this.attributes[name]; }
    toggleAttribute(name, active) {
      if (active) this.setAttribute(name, ''); else this.removeAttribute(name);
    }
    matches(selector) {
      return selector.split(',').some(part => {
        const text = part.trim();
        const tag = /^[\w-]+/.exec(text)?.[0];
        if (tag && tag.toUpperCase() !== this.tagName) return false;
        const cssClass = /\.([\w-]+)/.exec(text)?.[1];
        if (cssClass && !this.classList.contains(cssClass)) return false;
        const attrs = [...text.matchAll(/\[([\w-]+)(?:(\*=|\^=|=)"([^"]*)")?\]/g)];
        for (const [, name, operator, value] of attrs) {
          const actual = this.getAttribute(name);
          if (actual == null) return false;
          if (operator === '=' && actual !== value) return false;
          if (operator === '*=' && !actual.includes(value)) return false;
          if (operator === '^=' && !actual.startsWith(value)) return false;
        }
        return true;
      });
    }
    closest(selector) {
      let node = this;
      while (node) { if (node.matches(selector)) return node; node = node.parentNode; }
      return null;
    }
    querySelectorAll(selector) {
      const result = [];
      for (const child of this.children) {
        if (child.matches(selector)) result.push(child);
        result.push(...child.querySelectorAll(selector));
      }
      return result;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    getBoundingClientRect() { return { x: 0, y: 0, width: 100, height: 100 }; }
    click() { this.emit('click'); }
  }
  const document = new Element('document');
  document.documentElement = document.appendChild(new Element('html'));
  document.body = document.documentElement.appendChild(new Element('body'));
  document.createElement = tag => new Element(tag);
  document.getElementById = id => document.querySelectorAll('[id]').find(node => node.id === id) || null;
  const window = new Target();
  window.top = window;
  window.AndroidSponsorBlock = { isEnabled: () => sponsorEnabled,
    requestSegments: id => requests.push(id) };
  const history = {};
  for (const method of ['pushState', 'replaceState']) {
    history[method] = (_, title, href) => { if (href != null) address = new URL(href, address); };
  }
  const context = vm.createContext({ window, document, location, history, URL, console,
    HTMLElement: Element,
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    MutationObserver: class { observe() {} },
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }
  });
  window.setTimeout = (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; };
  window.clearTimeout = id => timers.delete(id);
  return {
    window, document, location, history, navigations, requests,
    load(name) { vm.runInContext(fs.readFileSync(path.join(ASSETS, name), 'utf8'), context); },
    element(tag, parent = document.body) { return parent.appendChild(new Element(tag)); },
    media(parent = document.body) {
      const media = this.element('video', parent);
      media.paused = false;
      media.ended = false;
      media.seeking = false;
      media.readyState = 4;
      media.duration = 180;
      media.currentTime = 10;
      media.muted = false;
      media.playbackRate = 1;
      media.playCount = 0;
      media.play = () => { media.paused = false; media.playCount++; return Promise.resolve(); };
      media.pauseCount = 0;
      media.pause = () => { media.paused = true; media.pauseCount++; };
      return media;
    },
    flush() {
      const pending = [...timers].filter(([, timer]) => timer.delay < 100);
      for (const [id, timer] of pending) { timers.delete(id); timer.callback(); }
    },
    changeUrl(value) { address = new URL(value, address); }
  };
}

const segment = (start, end, overrides = {}) => ({
  category: 'sponsor', actionType: 'skip', segment: [start, end], videoDuration: 180, ...overrides
});

test('rewritten Shorts anchors remain interceptable before YouTube click handlers', () => {
  const p = page('https://m.youtube.com/results?search_query=tech');
  const link = p.element('a');
  link.href = `/shorts/${FIRST}`;
  const image = p.element('img', link);
  p.load('shorts-policy.js');
  assert.equal(link.href, `https://m.youtube.com/watch?v=${FIRST}`);
  let youtubeClicks = 0;
  p.window.addEventListener('click', () => { youtubeClicks++; });
  const event = p.window.emit('click', image);
  p.window.emit('click', image);
  assert.equal(event.defaultPrevented, true);
  assert.equal(youtubeClicks, 0);
  assert.deepEqual(p.navigations, [['assign', `https://m.youtube.com/watch?v=${FIRST}`]]);
});

test('a recycled Shorts card becomes a regular clickable card again', () => {
  const p = page('https://m.youtube.com/');
  const card = p.element('ytm-rich-item-renderer');
  const link = p.element('a', card);
  link.href = `/shorts/${FIRST}`;
  p.load('shorts-policy.js');
  assert.equal(card.getAttribute('data-shelby-shorts-card'), '');
  link.href = `/watch?v=${SECOND}`;
  p.window.__ytCleanShortsPolicy.run();
  assert.equal(card.getAttribute('data-shelby-shorts-card'), null);
  assert.equal(link.getAttribute('data-shelby-shorts-target'), null);
  assert.equal(p.window.emit('click', link).defaultPrevented, false);
});

test('Shorts cards and shelves are hidden outside search, including watch recommendations', () => {
  for (const route of ['/', '/feed/recommended', '/feed/subscriptions', '/feed/history',
    '/feed/library', '/@channel/videos', '/playlist?list=example', `/watch?v=${SECOND}`]) {
    const p = page(`https://m.youtube.com${route}`);
    const shelf = p.element('ytm-reel-shelf-renderer');
    const short = p.element('ytm-shorts-lockup-view-model-v2', shelf);
    p.element('a', short).href = `/shorts/${FIRST}`;
    const regular = p.element('ytm-compact-video-renderer');
    p.element('a', regular).href = `/watch?v=${SECOND}`;
    p.load('shorts-policy.js');
    assert.equal(p.document.documentElement.hasAttribute('data-shelby-hide-shorts'), true, route);
    assert.equal(short.hasAttribute('data-shelby-shorts-card'), true, route);
    assert.equal(regular.hasAttribute('data-shelby-shorts-card'), false, route);
    const css = p.document.getElementById('shelby-shorts-style').textContent;
    assert.ok(css.includes('html[data-shelby-hide-shorts] ytm-reel-shelf-renderer'));
    assert.ok(css.includes('html[data-shelby-hide-shorts] ytm-shorts-lockup-view-model-v2'));
  }
});

test('SPA search reveals Shorts and leaving search hides them again without losing watch routing', () => {
  const p = page('https://m.youtube.com/');
  const card = p.element('ytm-compact-video-renderer');
  const link = p.element('a', card);
  link.href = `/shorts/${FIRST}`;
  p.load('shorts-policy.js');
  for (const search of ['/results?search_query=science', '/results/?search_query=shorts']) {
    p.history.pushState({}, '', search);
    p.flush();
    assert.equal(p.document.documentElement.hasAttribute('data-shelby-hide-shorts'), false);
    assert.equal(card.hasAttribute('data-shelby-shorts-card'), true);
    assert.equal(link.href, `https://m.youtube.com/watch?v=${FIRST}`);
  }
  p.history.pushState({}, '', `/watch?v=${SECOND}`);
  p.flush();
  assert.equal(p.document.documentElement.hasAttribute('data-shelby-hide-shorts'), true);
  assert.equal(card.hasAttribute('data-shelby-shorts-card'), true);
});

test('Shorts touch interception distinguishes a tap from a feed swipe', () => {
  const p = page();
  const link = p.element('a');
  link.href = `/shorts/${SECOND}`;
  p.load('shorts-policy.js');
  p.window.emit('touchstart', link, { touches: [{ clientX: 50, clientY: 50 }] });
  const swipe = p.window.emit('touchend', link, { changedTouches: [{ clientX: 50, clientY: 200 }] });
  assert.equal(swipe.defaultPrevented, false);
  assert.equal(p.navigations.length, 0);
  p.window.emit('touchstart', link, { touches: [{ clientX: 50, clientY: 50 }] });
  const tap = p.window.emit('touchend', link, { changedTouches: [{ clientX: 52, clientY: 54 }] });
  assert.equal(tap.defaultPrevented, true);
  assert.equal(p.navigations.length, 1);
});

test('SPA Shorts history navigation redirects without writing a reel route', () => {
  const p = page();
  p.load('shorts-policy.js');
  p.history.pushState({}, '', `/shorts/${SECOND}`);
  assert.equal(p.location.pathname, '/watch');
  assert.deepEqual(p.navigations, [['replace', `https://m.youtube.com/watch?v=${SECOND}`]]);
  p.history.replaceState({}, '', '/results?search_query=hello');
  assert.equal(p.location.pathname, '/results');
});

test('direct Shorts navigation pauses previous playback without muting reused media', () => {
  const p = page(`https://m.youtube.com/shorts/${FIRST}`);
  const media = p.media();
  p.load('shorts-policy.js');
  assert.deepEqual(p.navigations, [['replace', `https://m.youtube.com/watch?v=${FIRST}`]]);
  assert.equal(media.paused, true);
  assert.equal(media.muted, false);
});

test('mini browser sends a Shorts tap to the native main player only once', () => {
  const p = page();
  const opened = [];
  p.window.AndroidBrowser = { openVideo: value => opened.push(value) };
  const link = p.element('a');
  link.href = `/shorts/${SECOND}`;
  p.load('shorts-policy.js');
  p.load('shorts-policy.js');
  p.window.emit('click', link);
  p.window.emit('click', link);
  assert.deepEqual(opened, [`https://m.youtube.com/watch?v=${SECOND}`]);
  assert.deepEqual(p.navigations, []);
  assert.equal(p.window.listeners.click.length, 1);
});

test('a misleading external Shorts URL is left alone', () => {
  const p = page();
  const link = p.element('a');
  link.href = `https://notyoutube.com/shorts/${FIRST}`;
  p.load('shorts-policy.js');
  assert.equal(p.window.emit('click', link).defaultPrevented, false);
  assert.deepEqual(p.navigations, []);
});

test('SponsorBlock merges overlaps, skips only sponsors, and Undo prevents immediate reskip', () => {
  const p = page();
  const media = p.media();
  p.load('sponsor-block.js');
  assert.deepEqual(p.requests, [FIRST]);
  p.window.__shelbySponsorBlock.setSegments(FIRST,
    [segment(5, 15), segment(10, 20), segment(20, 30, { category: 'intro' })]);
  assert.ok(Math.abs(media.currentTime - 20.02) < 0.001);
  assert.equal(media.muted, false);
  assert.equal(media.paused, false);
  assert.ok(p.document.getElementById('shelby-sponsor-notice'));
  p.window.__shelbySponsorBlock.undo();
  assert.equal(media.currentTime, 10);
  p.document.emit('timeupdate', media);
  assert.equal(media.currentTime, 10);
  assert.equal(p.document.getElementById('shelby-sponsor-notice'), null);
});

test('disabled SponsorBlock does not fetch and changes take effect on active playback', () => {
  const p = page(undefined, false);
  const media = p.media();
  p.load('sponsor-block.js');
  assert.deepEqual(p.requests, []);
  p.window.__shelbySponsorBlock.setEnabled(true);
  assert.deepEqual(p.requests, [FIRST]);
  p.window.__shelbySponsorBlock.setEnabled(false);
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.equal(media.currentTime, 10);
  p.window.__shelbySponsorBlock.setEnabled(true);
  assert.ok(media.currentTime > 20);
  assert.deepEqual(p.requests, [FIRST]);
});

test('late native responses cannot skip a different video after an SPA navigation', () => {
  const p = page();
  const media = p.media();
  p.load('sponsor-block.js');
  p.history.pushState({}, '', `/watch?v=${SECOND}`);
  p.flush();
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.equal(media.currentTime, 10);
  assert.deepEqual(p.requests, [FIRST, SECOND]);
  p.window.__shelbySponsorBlock.setSegments(SECOND, [segment(5, 30)]);
  assert.ok(media.currentTime > 30);
});

test('SponsorBlock rejects malformed, non-sponsor, outdated, and non-skip data', () => {
  const p = page();
  const media = p.media();
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, [null, {}, segment(-1, 20),
    segment(15, 5), segment(5, Infinity), segment(5, 20, { actionType: 'mute' }),
    segment(5, 20, { category: 'selfpromo' }), segment(5, 20, { videoDuration: 240 }),
    segment(5, 999)]);
  assert.equal(media.currentTime, 10);
  assert.equal(media.muted, false);
});

test('ads, live streams, paused media and a previous SPA video are never sponsor-seeked', () => {
  const p = page();
  const player = p.element('div');
  player.id = 'movie_player';
  player.classList.add('html5-video-player');
  const media = p.media(player);
  player.classList.add('ad-showing');
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.equal(media.currentTime, 10);
  player.classList.remove('ad-showing');
  media.duration = Infinity;
  p.document.emit('timeupdate', media);
  assert.equal(media.currentTime, 10);
  media.duration = 180;
  media.paused = true;
  p.document.emit('timeupdate', media);
  assert.equal(media.currentTime, 10);
  media.paused = false;
  player.getVideoData = () => ({ video_id: SECOND });
  p.document.emit('timeupdate', media);
  assert.equal(media.currentTime, 10);
  player.getVideoData = () => ({ video_id: FIRST });
  p.document.emit('timeupdate', media);
  assert.ok(media.currentTime > 20);
});

test('empty API responses and repeated injections do not cause network polling', () => {
  const p = page();
  const media = p.media();
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, []);
  p.load('sponsor-block.js');
  for (let index = 0; index < 100; index++) {
    p.document.emit('timeupdate', media);
    p.window.__shelbySponsorBlock.refresh();
  }
  assert.deepEqual(p.requests, [FIRST]);
  assert.equal(p.document.listeners.timeupdate.length, 1);
  assert.equal(media.currentTime, 10);
});

test('policy history wrappers coexist and normal music routes stay intact', () => {
  const p = page();
  p.load('shorts-policy.js');
  p.load('sponsor-block.js');
  p.history.pushState({}, '', `/shorts/${SECOND}`);
  p.flush();
  assert.deepEqual(p.navigations, [['replace', `https://m.youtube.com/watch?v=${SECOND}`]]);
  assert.deepEqual(p.requests, [FIRST]);
  const music = page(`https://music.youtube.com/watch?v=${SECOND}`);
  music.load('shorts-policy.js');
  music.load('sponsor-block.js');
  assert.equal(music.window.__ytCleanShortsPolicy, undefined);
  assert.deepEqual(music.requests, [SECOND]);
});

for (const [asset, host, accessor] of [
  ['blocker.js', 'm.youtube.com', p => p.window.__shelbyVideoBlocker],
  ['music-blocker.js', 'music.youtube.com', p => p.window.__shelbyMusicBlocker]
]) {
  test(`${asset}: restores the original mute and chosen playback speed after an ad`, () => {
    const p = page(`https://${host}/watch?v=${FIRST}`);
    const player = p.element('div');
    player.id = 'movie_player';
    const media = p.media(player);
    media.playbackRate = 1.5;
    player.classList.add('ad-showing');
    p.load(asset);
    assert.equal(media.muted, true);
    assert.equal(media.playbackRate, 16);
    player.classList.remove('ad-showing');
    accessor(p).run();
    assert.equal(media.muted, false);
    assert.equal(media.playbackRate, 1.5);
    media.muted = true;
    media.playbackRate = 2;
    player.classList.add('ad-showing');
    accessor(p).run();
    player.classList.remove('ad-showing');
    accessor(p).run();
    assert.equal(media.muted, true, 'preserve an intentional pre-ad mute');
    assert.equal(media.playbackRate, 2);
  });

  test(`${asset}: restores a replaced ad element and leaves normal content untouched`, () => {
    const p = page(`https://${host}/watch?v=${FIRST}`);
    const player = p.element('div');
    player.id = 'movie_player';
    const old = p.media(player);
    old.playbackRate = 1.25;
    player.classList.add('ad-showing');
    p.load(asset);
    old.remove();
    const content = p.media(player);
    player.classList.remove('ad-showing');
    accessor(p).run();
    assert.equal(old.muted, false);
    assert.equal(old.playbackRate, 1.25);
    assert.equal(content.muted, false);
    assert.equal(content.playbackRate, 1);
    assert.equal(content.currentTime, 10);
  });

  test(`${asset}: does not resume a user-paused ad and restores on source changes`, () => {
    const p = page(`https://${host}/watch?v=${FIRST}`);
    const player = p.element('div');
    player.id = 'movie_player';
    const media = p.media(player);
    player.classList.add('ad-showing');
    media.paused = true;
    p.load(asset);
    assert.equal(media.playCount, 0);
    assert.equal(media.muted, false);
    assert.equal(media.currentTime, 10);
    media.paused = false;
    accessor(p).run();
    assert.equal(media.muted, true);
    p.document.emit('emptied', media);
    assert.equal(media.muted, false);
    assert.equal(media.playbackRate, 1);
  });

  test(`${asset}: a Skip button returning to content synchronously does not mute it`, () => {
    const p = page(`https://${host}/watch?v=${FIRST}`);
    const player = p.element('div');
    player.id = 'movie_player';
    const media = p.media(player);
    const button = p.element('button', player);
    button.classList.add('ytp-ad-skip-button-modern');
    button.addEventListener('click', () => player.classList.remove('ad-showing'));
    player.classList.add('ad-showing');
    p.load(asset);
    assert.equal(media.muted, false);
    assert.equal(media.playbackRate, 1);
    assert.equal(media.currentTime, 10);
  });

  test(`${asset}: reinjection does not duplicate media listeners or blocker state`, () => {
    const p = page(`https://${host}/watch?v=${FIRST}`);
    p.load(asset);
    const controller = accessor(p);
    p.load(asset);
    assert.equal(accessor(p), controller);
    assert.equal(p.document.listeners.playing.length, 1);
    assert.equal(p.window.listeners.pagehide.length, 1);
  });
}

test('queued Music ad metadata cannot mute or accelerate the currently playing song', () => {
  const p = page(`https://music.youtube.com/watch?v=${FIRST}`);
  const app = p.element('ytmusic-app');
  app.setAttribute('ad-showing', '');
  const player = p.element('div', app);
  player.id = 'movie_player';
  const media = p.media(player);
  const queueItem = p.element('ytmusic-player-queue-item', app);
  queueItem.setAttribute('is-ad', '');
  queueItem.setAttribute('is-advertisement', '');
  p.load('music-blocker.js');
  assert.equal(media.muted, false);
  assert.equal(media.playbackRate, 1);
  assert.equal(media.currentTime, 10);
});

test('SponsorBlock re-enabling retries a lookup whose native delivery was suppressed while disabled', () => {
  const p = page();
  const media = p.media();
  p.load('sponsor-block.js');
  assert.deepEqual(p.requests, [FIRST]);
  p.window.__shelbySponsorBlock.setEnabled(false);
  // Native finishes and caches the request while OFF, so it makes no JS callback.
  p.window.__shelbySponsorBlock.setEnabled(true);
  assert.deepEqual(p.requests, [FIRST, FIRST]);
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.ok(media.currentTime > 20);
  p.window.__shelbySponsorBlock.setEnabled(false);
  p.window.__shelbySponsorBlock.setEnabled(true);
  assert.deepEqual(p.requests, [FIRST, FIRST], 'completed JS cache should be reused');
});

test('SponsorBlock unresolved lookups on previous SPA videos can recover after re-enabling', () => {
  const p = page();
  p.load('sponsor-block.js');
  p.history.pushState({}, '', `/watch?v=${SECOND}`);
  p.flush();
  p.window.__shelbySponsorBlock.setEnabled(false);
  p.window.__shelbySponsorBlock.setEnabled(true);
  p.window.__shelbySponsorBlock.setEnabled(true);
  assert.deepEqual(p.requests, [FIRST, SECOND, SECOND]);
  p.history.pushState({}, '', `/watch?v=${FIRST}`);
  p.flush();
  assert.deepEqual(p.requests, [FIRST, SECOND, SECOND, FIRST]);
});

test('SponsorBlock follows the session-selected media instead of an older first video element', () => {
  const p = page();
  const old = p.media();
  old.classList.add('html5-main-video');
  const current = p.media();
  current.classList.add('html5-main-video');
  p.window.__shelbyPlayback = { activeMedia: () => current };
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.equal(old.currentTime, 10);
  assert.ok(current.currentTime > 20);
});

test('stale Music app ad metadata does not suppress sponsors; live player ad state does', () => {
  const p = page(`https://music.youtube.com/watch?v=${FIRST}`);
  const app = p.element('ytmusic-app');
  app.setAttribute('ad-showing', '');
  const player = p.element('ytmusic-player', app);
  const media = p.media(player);
  player.setAttribute('ad-showing', '');
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  assert.equal(media.currentTime, 10);
  player.removeAttribute('ad-showing');
  p.document.emit('timeupdate', media);
  assert.ok(media.currentTime > 20);
});

test('the sponsor notice Undo button restores position and suppresses that skip without changing playback', () => {
  const p = page();
  const media = p.media();
  media.playbackRate = 1.25;
  p.document.documentElement.setAttribute('data-shelby-surface-mode', 'mini');
  p.load('sponsor-block.js');
  p.window.__shelbySponsorBlock.setSegments(FIRST, [segment(5, 20)]);
  const notice = p.document.getElementById('shelby-sponsor-notice');
  assert.ok(notice);
  notice.querySelector('button').click();
  assert.equal(media.currentTime, 10);
  assert.equal(media.paused, false);
  assert.equal(media.muted, false);
  assert.equal(media.playbackRate, 1.25);
  p.document.emit('timeupdate', media);
  assert.equal(media.currentTime, 10);
  assert.equal(p.document.getElementById('shelby-sponsor-notice'), null);
});
