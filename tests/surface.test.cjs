const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const source = readFileSync(resolve(__dirname, '../app/src/main/assets/player-surface.js'), 'utf8');

function fixture() {
  let now = 10000;
  const observers = [];
  const env = { expands: 0, presentationChanges: [] };
  class Element {
    constructor(tagName) {
      this.tagName = tagName.toUpperCase();
      this.parentElement = null;
      this.children = [];
      this.attributes = new Map();
      this.listeners = new Map();
      this.isConnected = true;
      const properties = new Map();
      this.style = {
        get length() { return properties.size; },
        getPropertyValue: property => properties.get(property)?.[0] || '',
        getPropertyPriority: property => properties.get(property)?.[1] || '',
        setProperty: (property, value, priority = '') => {
          this.attributes.set('style', '');
          properties.set(property, [value, priority]);
        },
        removeProperty: property => properties.delete(property)
      };
      const classes = new Set();
      this.classList = { add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value) };
    }
    append(child) { child.parentElement = this; this.children.push(child); }
    remove(child) { this.children = this.children.filter(node => node !== child); child.parentElement = null; child.isConnected = false; }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    setAttribute(name, value) { this.attributes.set(name, value); }
    removeAttribute(name) { this.attributes.delete(name); }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
    emit(type) {
      const event = { type, target: this, prevented: false, stopped: false,
        preventDefault() { this.prevented = true; }, stopImmediatePropagation() { this.stopped = true; } };
      for (const listener of this.listeners.get(type) || []) listener(event);
      return event;
    }
  }
  const html = new Element('html');
  const body = new Element('body');
  const app = new Element('ytm-app');
  const player = new Element('div');
  const video = new Element('video');
  const playerControls = new Element('div');
  const recommendations = new Element('div');
  const overlay = new Element('div');
  html.append(body); body.append(app); body.append(overlay);
  app.append(player); app.append(recommendations);
  player.append(video); player.append(playerControls);
  const document = new Element('document');
  document.documentElement = html;
  document.querySelector = () => video;
  const window = {
    AndroidMedia: { onMiniPlayerExpandRequested() { env.expands++; } },
    __shelbyPlayback: {
      activeMedia: () => env.active,
      setPresentation: active => env.presentationChanges.push({ active, surfaced: html.classList.contains('shelby-video-surface') })
    }
  };
  window.top = window;
  const context = vm.createContext({
    window, document,
    Date: class extends Date { static now() { return now; } },
    MutationObserver: class {
      constructor(callback) { this.callback = callback; this.active = false; observers.push(this); }
      observe() { this.active = true; }
      disconnect() { this.active = false; }
    }
  });
  Object.assign(env, { active: video, Element, html, body, app, player, video, playerControls, recommendations, overlay, document, window, context });
  env.advance = ms => now += ms;
  env.mutate = () => observers.filter(observer => observer.active).forEach(observer => observer.callback());
  env.activeObservers = () => observers.filter(observer => observer.active).length;
  vm.runInContext(source, context);
  env.surface = window.__shelbySurface;
  return env;
}

test('surface never reparents the video and hides all other branches without collapsing layout', () => {
  const env = fixture();
  const child = new env.Element('div');
  child.style.setProperty('visibility', 'visible', 'important');
  env.overlay.append(child);
  const originalChildren = [...env.player.children];
  assert.equal(env.surface.enter('mini'), true);
  assert.equal(env.video.parentElement, env.player);
  assert.deepEqual(env.player.children, originalChildren);
  assert.equal(env.video.style.getPropertyValue('visibility'), 'visible');
  assert.equal(env.video.style.getPropertyValue('position'), 'fixed');
  for (const ancestor of [env.player, env.app, env.body]) {
    assert.equal(ancestor.style.getPropertyValue('visibility'), 'hidden', 'ancestor must not reveal siblings');
  }
  for (const sibling of [env.playerControls, env.recommendations, env.overlay]) {
    assert.equal(sibling.style.getPropertyValue('opacity'), '0', 'hides descendants with explicit visibility');
    assert.equal(sibling.style.getPropertyPriority('opacity'), 'important');
    assert.equal(sibling.style.getPropertyValue('display'), '', 'do not trigger a provider player rebuild through collapsed layout');
    assert.equal(sibling.getAttribute('inert'), '', 'hidden controls must not intercept touches or focus');
  }
});

test('exit restores provider styles, attributes and DOM, preserving unrelated live changes', () => {
  const env = fixture();
  env.player.style.setProperty('transform', 'translateY(10px)', 'important');
  env.video.style.setProperty('width', '430px');
  env.overlay.setAttribute('inert', 'existing');
  env.video.setAttribute('data-shelby-surface', 'prior-value');
  env.surface.enter('pip');
  env.player.style.setProperty('color', 'red');
  env.surface.exit();
  assert.equal(env.video.parentElement, env.player);
  assert.equal(env.player.style.getPropertyValue('transform'), 'translateY(10px)');
  assert.equal(env.player.style.getPropertyPriority('transform'), 'important');
  assert.equal(env.player.style.getPropertyValue('color'), 'red');
  assert.equal(env.video.style.getPropertyValue('width'), '430px');
  assert.equal(env.video.style.getPropertyPriority('width'), '');
  assert.equal(env.video.style.getPropertyValue('position'), '');
  assert.equal(env.video.getAttribute('data-shelby-surface'), 'prior-value');
  assert.equal(env.overlay.getAttribute('inert'), 'existing');
  assert.equal(env.recommendations.getAttribute('style'), null);
  assert.equal(env.recommendations.getAttribute('inert'), null);
  assert.equal(env.html.classList.contains('shelby-video-surface'), false);
  assert.equal(env.activeObservers(), 0);
});

test('repeated transitions and reinjection do not duplicate listeners or lose originals', () => {
  const env = fixture();
  env.player.style.setProperty('visibility', 'visible');
  const original = env.surface;
  vm.runInContext(source, env.context);
  assert.equal(env.window.__shelbySurface, original);
  env.surface.enter('mini'); env.surface.enter('mini');
  env.surface.enter('pip'); env.surface.enter('mini');
  assert.equal(env.video.listeners.get('click').size, 1);
  assert.equal(env.activeObservers(), 1);
  env.surface.exit(); env.surface.exit();
  assert.equal(env.video.listeners.get('click').size, 0);
  assert.equal(env.player.style.getPropertyValue('visibility'), 'visible');
  assert.equal(env.video.getAttribute('data-shelby-surface'), null);
});

test('presentation guard begins before styling and stays active through mini-to-PiP changes', () => {
  const env = fixture();
  env.surface.enter('mini');
  assert.deepEqual(env.presentationChanges[0], { active: true, surfaced: false });
  env.surface.enter('pip');
  assert.ok(env.presentationChanges.every(change => change.active), 'mode transition must not clear pause protection');
  env.surface.exit();
  assert.deepEqual(env.presentationChanges.at(-1), { active: false, surfaced: false }, 'restore original DOM styling before clearing protection');
});

test('SponsorBlock Undo stays accessible in mini and mode transitions restore visibility correctly', () => {
  const env = fixture();
  const notice = new env.Element('div');
  notice.setAttribute('id', 'shelby-sponsor-notice');
  notice.style.setProperty('color', 'white');
  env.body.append(notice);
  env.surface.enter('mini');
  assert.equal(env.html.getAttribute('data-shelby-surface-mode'), 'mini');
  assert.equal(notice.style.getPropertyValue('opacity'), '1');
  assert.equal(notice.style.getPropertyValue('visibility'), 'visible');
  assert.equal(notice.style.getPropertyValue('pointer-events'), 'auto');
  assert.equal(notice.getAttribute('inert'), null);
  env.surface.enter('pip');
  assert.equal(env.html.getAttribute('data-shelby-surface-mode'), 'pip');
  assert.equal(notice.style.getPropertyValue('opacity'), '0');
  assert.equal(notice.getAttribute('inert'), '');
  env.surface.enter('mini');
  assert.equal(notice.style.getPropertyValue('opacity'), '1');
  assert.equal(notice.getAttribute('inert'), null, 'PiP inert state must not disable returning mini Undo');
  env.surface.exit();
  assert.equal(env.html.getAttribute('data-shelby-surface-mode'), null);
  assert.equal(notice.style.getPropertyValue('opacity'), '');
  assert.equal(notice.style.getPropertyValue('color'), 'white');
  assert.equal(notice.getAttribute('inert'), null);
});

test('new overlays are hidden and a replacement video is presented in its provider-owned location', () => {
  const env = fixture();
  env.surface.enter('mini');
  const popup = new env.Element('div');
  env.body.append(popup); env.mutate();
  assert.equal(popup.style.getPropertyValue('opacity'), '0');
  const replacement = new env.Element('video');
  env.player.append(replacement);
  env.active = replacement;
  env.document.emit('playing');
  assert.equal(replacement.parentElement, env.player);
  assert.equal(replacement.style.getPropertyValue('visibility'), 'visible');
  assert.equal(env.video.getAttribute('data-shelby-surface'), null);
  assert.equal(env.video.style.getPropertyValue('opacity'), '0');
  env.surface.exit();
  assert.equal(popup.getAttribute('style'), null);
  assert.equal(replacement.getAttribute('style'), null);
  assert.equal(env.video.getAttribute('style'), null);
});

test('provider reparenting is accommodated, while Shelby performs no reparenting itself', () => {
  const env = fixture();
  env.surface.enter('pip');
  const nextPlayer = new env.Element('div');
  env.app.append(nextPlayer);
  env.player.remove(env.video);
  nextPlayer.append(env.video); env.video.isConnected = true;
  env.mutate();
  assert.equal(env.video.parentElement, nextPlayer);
  assert.equal(nextPlayer.style.getPropertyValue('contain'), 'none');
  assert.equal(env.player.style.getPropertyValue('opacity'), '0');
  env.surface.exit();
  assert.equal(env.video.parentElement, nextPlayer);
  assert.equal(env.player.getAttribute('style'), null);
});

test('mini tap expands once after the entry gesture; PiP taps do not invoke mini controls', () => {
  const env = fixture();
  env.surface.enter('mini');
  const entryClick = env.video.emit('click');
  assert.equal(entryClick.prevented, true);
  assert.equal(env.expands, 0, 'swipe click must not immediately expand');
  env.advance(350);
  const tap = env.video.emit('click');
  assert.equal(tap.prevented, true);
  assert.equal(tap.stopped, true);
  assert.equal(env.expands, 1);
  env.surface.enter('pip'); env.advance(350);
  env.video.emit('click');
  assert.equal(env.expands, 1);
  env.surface.exit(); env.video.emit('click');
  assert.equal(env.expands, 1);
});

test('invalid modes and audio-only players do not expose a stale video surface', () => {
  const env = fixture();
  assert.equal(env.surface.enter('fullscreen'), false);
  assert.equal(env.activeObservers(), 0);
  env.active = new env.Element('audio');
  assert.equal(env.surface.enter('mini'), false);
  assert.equal(env.video.getAttribute('data-shelby-surface'), null);
  env.active = env.video;
  env.document.emit('play');
  assert.equal(env.video.style.getPropertyValue('visibility'), 'visible');
  env.surface.exit();
});
