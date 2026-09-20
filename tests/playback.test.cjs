const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const source = readFileSync(resolve(__dirname, '../app/src/main/assets/playback.js'), 'utf8');
const VIDEO = 'a1b2c3d4e5f';
const OTHER = 'g6h7i8j9k0l';

function fixture(options = {}) {
  let now = 100000;
  let nextTimer = 0;
  const timers = new Map();
  const snapshots = [];
  const env = { hidden: false, ad: false, providerId: VIDEO, extra: [], snapshots, userIntents: [] };
  class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(type, listener, options) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push({ listener, once: options?.once });
    }
    dispatch(type, fields = {}) {
      let stopped = false;
      const event = { type, target: this, isTrusted: true, ...fields,
        preventDefault() {}, stopImmediatePropagation() { stopped = true; } };
      for (const entry of [...(this.listeners.get(type) || [])]) {
        entry.listener(event);
        if (entry.once) this.listeners.set(type, this.listeners.get(type).filter(item => item !== entry));
        if (stopped) break;
      }
      return event;
    }
  }
  function schedule(callback, milliseconds, interval = 0) {
    const id = ++nextTimer;
    timers.set(id, { callback, time: now + Number(milliseconds || 0), interval });
    return id;
  }
  function advance(milliseconds) {
    const until = now + milliseconds;
    let count = 0;
    while (true) {
      const entry = [...timers].filter(([, timer]) => timer.time <= until).sort((a, b) => a[1].time - b[1].time)[0];
      if (!entry) break;
      assert.ok(++count < 10000, 'timer loop must remain bounded');
      const [id, timer] = entry;
      now = timer.time;
      timers.delete(id);
      if (timer.interval) timers.set(id, { ...timer, time: now + timer.interval });
      timer.callback();
    }
    now = until;
  }
  let document;
  class Media {
    constructor(main = true) {
      this.main = main;
      this.isConnected = true;
      this.paused = true;
      this.ended = false;
      this.readyState = 4;
      this.duration = 240;
      this._time = 0;
      this.currentSrc = main ? 'blob:current-track' : 'blob:preview';
      this.seeking = false;
      this.volume = 0.8;
      this.muted = false;
      this.videoWidth = 1280;
      this.videoHeight = 720;
      this.playCalls = 0;
      this.pauseCalls = 0;
      this.seekCalls = [];
      this.seekable = { length: 1, start: () => 0, end: () => this.duration };
    }
    get currentTime() { return this._time; }
    set currentTime(value) {
      this._time = value;
      this.seekCalls.push(value);
      this.seeking = true;
      this.ended = false;
      schedule(() => { this.seeking = false; this.emit('seeked'); }, 10);
    }
    play() {
      this.playCalls++;
      if (this.paused) {
        this.paused = false;
        schedule(() => this.emit('play'), 0);
      }
      return Promise.resolve();
    }
    pause() {
      this.pauseCalls++;
      if (!this.paused) {
        this.paused = true;
        schedule(() => this.emit('pause'), 0);
      }
    }
    emit(type) { document.dispatch(type, { target: this }); }
    matches(selector) { return this.main && selector === '.html5-main-video'; }
    closest(selector) {
      if (selector.includes('ad-showing')) return env.ad && this.main ? env.player : null;
      if (selector.includes('html5-video-player') || selector.includes('#movie_player')) return this.main ? this.player || env.player : null;
      return null;
    }
    getAttribute() { return null; }
    getBoundingClientRect() { return { left: 0, top: 50, width: 400, height: 225, right: 400, bottom: 275 }; }
  }
  const media = new Media();
  const preview = new Media(false);
  env.player = { getVideoData: () => ({ video_id: env.providerId }), contains: target => target === media };
  class Document extends Events {
    get hidden() { return env.hidden; }
    querySelectorAll(selector) { return selector === 'video,audio' ? [media, ...env.extra] : []; }
    querySelector(selector) {
      if (selector.includes('.ad-showing') || selector.includes('[ad-showing]')) return env.ad ? env.player : null;
      if (selector === 'ytmusic-player' && options.music) return { playerApi: env.player };
      return null;
    }
    getElementById(id) { return id === 'movie_player' ? env.player : null; }
    hasFocus() { return !env.hidden; }
  }
  document = new Document();
  document.title = options.music ? 'Music track - YouTube Music' : 'Video title - YouTube';
  document.documentElement = {};
  document.readyState = 'complete';
  const window = new Events();
  window.top = options.subframe ? {} : window;
  window.scrollX = 0;
  window.scrollY = 0;
  window.scrollTo = (x, y) => { window.scrollX = x; window.scrollY = y; };
  window.AndroidMedia = {
    onSnapshot: raw => snapshots.push(JSON.parse(raw)),
    onUserPlaybackIntent: play => env.userIntents.push(play),
    onMiniPlayerRequested: () => env.miniRequested = true
  };
  let url = new URL(options.url || `https://${options.music ? 'music' : 'm'}.youtube.com/watch?v=${VIDEO}`);
  const location = {};
  for (const key of ['href', 'protocol', 'hostname', 'origin', 'pathname', 'search']) {
    Object.defineProperty(location, key, { get: () => url[key] });
  }
  const context = vm.createContext({
    window, document, location, URL, HTMLMediaElement: Media, Promise,
    Date: class extends Date { static now() { return now; } },
    history: {
      pushState(_state, _unused, href) { url = new URL(href, url); },
      replaceState(_state, _unused, href) { url = new URL(href, url); }
    },
    setTimeout: (callback, milliseconds) => schedule(callback, milliseconds),
    clearTimeout: id => timers.delete(id),
    setInterval: (callback, milliseconds) => schedule(callback, milliseconds, milliseconds),
    MutationObserver: class { observe() {} }
  });
  vm.runInContext(source, context);
  Object.assign(env, { controller: window.__shelbyPlayback, media, preview, document, window, context, advance });
  env.last = () => snapshots.at(-1);
  env.start = () => { media.play(); advance(0); };
  env.saved = overrides => ({
    url: location.href, mediaUrl: location.href, videoId: VIDEO, position: 83,
    playing: true, pausedByUser: false, ended: false, ...overrides
  });
  return env;
}

test('controller is restricted to the top-level HTTPS YouTube document', () => {
  for (const options of [{ url: 'https://youtube.com.evil.example/watch?v=' + VIDEO },
    { url: 'http://m.youtube.com/watch?v=' + VIDEO }, { subframe: true }]) {
    const env = fixture(options);
    assert.equal(env.controller, undefined);
    assert.equal(env.snapshots.length, 0);
  }
});

test('background guard preserves only the established player and respects notification pause', () => {
  const env = fixture();
  env.extra.push(env.preview);
  env.start();
  env.controller.setBackground(true);
  env.media.pause();
  assert.equal(env.media.paused, false, 'provider background pause is blocked');
  env.preview.play(); env.advance(0);
  env.preview.pause(); env.advance(0);
  assert.equal(env.preview.paused, true, 'unrelated previews must be allowed to stop');
  env.controller.command('pause'); env.advance(5000);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, true);
  assert.equal(env.media.playCalls, 1, 'watchdog must not override a user pause');
});

test('foreground mini/PiP presentation blocks provider layout pauses but respects user and platform pauses', () => {
  const env = fixture();
  env.start();
  env.controller.setPresentation(true);
  env.document.dispatch('click', { target: env.media });
  assert.deepEqual(env.userIntents, [], 'mini tap-to-expand must not also request pause');
  env.media.pause(); env.advance(0);
  assert.equal(env.media.paused, false, 'provider layout pause must not stop foreground mini playback');
  assert.equal(env.last().pausedByUser, false);
  env.controller.command('pause'); env.advance(0);
  assert.equal(env.media.paused, true, 'explicit native pause still works in mini');
  env.controller.command('play'); env.advance(0);
  env.media.paused = true; env.media.emit('pause'); env.advance(4000);
  assert.equal(env.media.paused, true, 'presentation must not restart a native focus interruption');
  assert.equal(env.last().platformPaused, true);
  env.controller.command('play'); env.advance(0);
  env.controller.setPresentation(false);
  env.media.pause(); env.advance(0);
  assert.equal(env.media.paused, true, 'ordinary provider controls work again after surface exit');
});

test('screen-off page visibility pauses cannot stop the established music track', () => {
  const env = fixture({ music: true });
  env.media._time = 51;
  env.start();
  env.hidden = true;
  let providerSawHidden = false;
  env.document.addEventListener('visibilitychange', () => { providerSawHidden = true; });
  env.document.dispatch('visibilitychange');
  assert.equal(env.document.hidden, false);
  assert.equal(providerSawHidden, false);
  env.media.pause(); env.advance(120);
  assert.equal(env.media.paused, false);
  assert.equal(env.media.currentTime, 51);
  assert.equal(env.last().url, `https://music.youtube.com/watch?v=${VIDEO}`);
  assert.equal(env.last().pausedByUser, false);
});

test('raw Chromium focus pause stays paused through background heartbeats and accepts native focus-gain play', () => {
  const env = fixture({ music: true });
  env.start(); env.controller.setBackground(true);
  env.media.paused = true;
  env.media.emit('pause');
  assert.equal(env.last().platformPaused, true);
  assert.equal(env.last().pausedByUser, false);
  assert.equal(env.last().wantsPlay, true);
  env.advance(10000);
  env.controller.setBackground(true);
  env.document.dispatch('visibilitychange');
  env.window.dispatch('pageshow');
  env.media.emit('canplay'); env.advance(4000);
  assert.equal(env.media.paused, true);
  assert.equal(env.media.playCalls, 1, 'no watchdog may steal audio focus back');
  env.media.paused = false;
  env.media.emit('play');
  assert.equal(env.last().platformPaused, false);
  assert.equal(env.last().pausedByUser, false);
  assert.equal(env.last().playing, true);
});

test('foreground native focus loss is distinct from a page-requested pause', () => {
  const env = fixture();
  env.start();
  env.media.paused = true; env.media.emit('pause');
  assert.equal(env.last().platformPaused, true);
  assert.equal(env.last().pausedByUser, false);
  env.controller.command('play'); env.advance(0);
  assert.equal(env.last().platformPaused, false);
  assert.equal(env.media.paused, false);
  env.media.pause(); env.advance(0);
  assert.equal(env.last().pausedByUser, true, 'foreground JavaScript pause preserves deliberate provider control');
  assert.equal(env.last().platformPaused, false);
});

test('user pause during a native interruption prevents later automatic playback', () => {
  const env = fixture();
  env.start(); env.controller.setBackground(true);
  env.media.paused = true; env.media.emit('pause');
  env.controller.command('pause'); env.advance(0);
  env.media.paused = false; env.media.emit('play'); env.advance(0);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, true);
  assert.equal(env.last().platformPaused, false);
});

test('audio-focus loss during metadata restoration prevents play when the saved seek completes', () => {
  const env = fixture();
  env.start();
  env.media.seekable.length = 0;
  env.controller.restore(env.saved());
  env.media.paused = true; env.media.emit('pause');
  assert.equal(env.last().platformPaused, true);
  env.media.seekable.length = 1;
  env.media.emit('canplay'); env.advance(10);
  assert.equal(env.media.currentTime, 83);
  assert.equal(env.media.playCalls, 1, 'restoration must not steal focus after a native pause');
  assert.equal(env.last().restoring, false);
  assert.equal(env.last().platformPaused, true);
  env.media.paused = false; env.media.emit('playing');
  assert.equal(env.last().platformPaused, false);
  assert.equal(env.last().playing, true);
});

test('trusted page pause and subsequent play work during background transition', () => {
  const env = fixture();
  env.start(); env.controller.setBackground(true);
  const button = {
    closest: selector => selector.includes('slider') ? null : button,
    getAttribute: () => 'Pause'
  };
  env.document.dispatch('click', { target: button });
  env.media.pause(); env.advance(2000);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, true);
  button.getAttribute = () => 'Play';
  env.document.dispatch('click', { target: button });
  env.media.play(); env.advance(0);
  assert.equal(env.media.paused, false);
  assert.equal(env.last().pausedByUser, false);
});

test('transient audio-focus suspension resumes intent but never a deliberate pause', () => {
  const env = fixture();
  env.start(); env.controller.setBackground(true);
  env.controller.command('suspend'); env.advance(5000);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, false, 'asynchronous internal pause is not manual');
  assert.equal(env.last().suspended, true);
  env.controller.command('resume'); env.advance(0);
  assert.equal(env.media.paused, false);
  env.controller.command('suspend'); env.advance(0);
  env.controller.command('pause'); env.advance(0);
  env.controller.command('resume'); env.advance(2000);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, true);
});

test('focus ducking restores the pre-existing volume and never mutes playback', () => {
  const env = fixture();
  env.controller.command('duck'); env.controller.command('duck');
  assert.ok(Math.abs(env.media.volume - 0.16) < 1e-10);
  env.controller.command('unduck');
  assert.equal(env.media.volume, 0.8);
  assert.equal(env.media.muted, false);
});

test('trusted play requests native audio focus while suspended without a callback loop', () => {
  const env = fixture();
  env.start(); env.controller.command('suspend'); env.advance(0);
  const button = { closest: selector => selector.includes('slider') ? null : button, getAttribute: () => 'Play' };
  env.document.dispatch('click', { target: button });
  env.document.dispatch('click', { target: button });
  assert.deepEqual(env.userIntents, [true], 'duplicate capture of one gesture is idempotent');
  env.media.play(); env.advance(0);
  assert.equal(env.media.paused, true, 'provider cannot play before audio focus is granted');
  env.controller.command('play'); env.advance(0);
  assert.equal(env.media.paused, false, 'native play after focus grant clears suspension');
  assert.equal(env.last().suspended, false);
  assert.deepEqual(env.userIntents, [true], 'native transport must not recursively request focus');
  env.controller.command('pause'); env.advance(0);
  assert.deepEqual(env.userIntents, [true], 'native pause also must not echo through the bridge');
});

test('prepareNavigation allows provider cleanup without creating a deliberate pause', () => {
  const env = fixture();
  env.start(); env.controller.setBackground(true);
  env.controller.prepareNavigation();
  env.media.pause(); env.advance(0);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, false);
  assert.equal(env.last().navigating, true);
});

test('a replacement main player supersedes the paused old element', () => {
  const env = fixture();
  env.start();
  env.controller.prepareNavigation();
  env.media.pause(); env.advance(0);
  vm.runInContext(`history.pushState({}, '', '/watch?v=${OTHER}')`, env.context);
  const replacement = new env.media.constructor();
  replacement.currentSrc = 'blob:new-main-player';
  replacement.player = { getVideoData: () => ({ video_id: OTHER }) };
  env.extra.push(replacement);
  replacement.play(); env.advance(0);
  assert.equal(env.controller.activeMedia(), replacement);
  assert.equal(env.last().videoId, OTHER);
  assert.equal(env.last().playing, true);
  assert.equal(env.last().pausedByUser, false);
});

test('restore waits for exact content, metadata, seekable data and seek completion before play', () => {
  const env = fixture();
  env.providerId = OTHER;
  env.media._time = 9;
  assert.equal(env.controller.restore(env.saved()), true);
  env.advance(1000);
  assert.equal(env.media.seekCalls.length, 0, 'old player must never be seeked');
  assert.equal(env.media.playCalls, 0, 'old player must never be played');
  env.providerId = VIDEO;
  env.media.readyState = 0;
  env.media.emit('loadedmetadata'); env.advance(250);
  assert.equal(env.media.seekCalls.length, 0);
  env.media.readyState = 4;
  env.media.seekable.length = 0;
  env.media.emit('canplay'); env.advance(250);
  assert.equal(env.media.seekCalls.length, 0);
  env.media.seekable.length = 1;
  env.media.emit('canplay');
  assert.deepEqual(env.media.seekCalls, [83]);
  assert.equal(env.media.playCalls, 0, 'must wait for asynchronous seek completion');
  env.advance(10);
  assert.equal(env.media.playCalls, 1);
  assert.equal(env.media.currentTime, 83);
  assert.equal(env.last().restoring, false);
  assert.equal(env.last().playing, true);
});

test('an unpaused media element without current-frame data reports buffering, not playing', () => {
  const env = fixture({ music: true });
  env.media.readyState = 0;
  env.start();
  assert.equal(env.last().playing, false);
  assert.equal(env.last().buffering, true);
  assert.equal(env.last().readyState, 0);
  env.media.readyState = 1; env.media.emit('loadedmetadata');
  assert.equal(env.last().playing, false);
  env.media.readyState = 2; env.media.emit('canplay');
  assert.equal(env.last().playing, true);
  assert.equal(env.last().buffering, false);
});

test('timed-out restoration preserves a durable target and retries only on explicit play', () => {
  const env = fixture({ music: true });
  env.media.readyState = 0;
  env.media._time = 14.97;
  env.start();
  env.controller.restore(env.saved({ position: 46 }));
  env.advance(45250);
  assert.equal(env.last().restoring, false);
  assert.equal(env.last().restoreFailed, true);
  assert.equal(env.last().restoreFailureReason, 'timeout');
  assert.equal(env.last().restoreTarget.position, 46);
  assert.equal(env.last().position, 14.97, 'actual loading position remains explicitly separate');
  assert.equal(env.last().playing, false);
  assert.equal(env.media.seekCalls.length, 0);
  env.controller.setBackground(true);
  env.media.readyState = 4; env.media.emit('canplay'); env.advance(10000);
  assert.equal(env.media.seekCalls.length, 0, 'no unbounded automatic restoration retries');
  assert.equal(env.media.playCalls, 1);
  assert.equal(env.last().restoreTarget.position, 46);
  env.controller.command('play'); env.advance(10);
  assert.deepEqual(env.media.seekCalls, [46]);
  assert.equal(env.last().restoreFailed, false);
  assert.equal(env.last().restoreTarget, null);
  assert.equal(env.last().playing, true);
  assert.equal(env.media.playCalls, 2);
});

test('explicit retry cannot apply a failed restore to a different video', () => {
  const env = fixture();
  env.media.readyState = 0;
  env.controller.restore(env.saved()); env.advance(45250);
  assert.equal(env.last().restoreFailed, true);
  vm.runInContext(`history.pushState({}, '', '/watch?v=${OTHER}')`, env.context);
  assert.equal(env.controller.retryRestore(), false);
  assert.equal(env.media.seekCalls.length, 0);
});

test('explicit page/native play during restoration retains the saved timestamp', () => {
  const env = fixture();
  env.media.readyState = 0;
  env.controller.restore(env.saved());
  const button = { closest: selector => selector.includes('slider') ? null : button, getAttribute: () => 'Play' };
  env.document.dispatch('click', { target: button });
  env.controller.command('play'); env.advance(0);
  assert.equal(env.last().restoring, true);
  assert.equal(env.media.playCalls, 0, 'play must wait for saved position restoration');
  env.media.readyState = 4; env.media.emit('canplay'); env.advance(10);
  assert.deepEqual(env.media.seekCalls, [83]);
  assert.equal(env.media.playCalls, 1);
  assert.equal(env.last().restoring, false);
});

test('Music snapshots retain queue context when the playing track is later browsed away from', () => {
  const queueUrl = `https://music.youtube.com/watch?v=${VIDEO}&list=RDAMVM${VIDEO}&index=2`;
  const env = fixture({ music: true, url: queueUrl });
  env.start();
  assert.equal(env.last().mediaUrl, queueUrl);
  vm.runInContext("history.pushState({}, '', '/library')", env.context);
  env.controller.send(true);
  assert.equal(env.last().mediaUrl, queueUrl);
  assert.equal(env.last().url, 'https://music.youtube.com/library');
});

test('paused restoration preserves position and pause through later provider autoplay', () => {
  const env = fixture();
  env.controller.restore(env.saved({ playing: false, pausedByUser: true }));
  env.advance(10);
  assert.equal(env.media.currentTime, 83);
  assert.equal(env.media.playCalls, 0);
  env.media.play(); env.advance(0);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().pausedByUser, true);
});

test('paused Music restoration survives a late provider reset to zero with bounded repairs', () => {
  const env = fixture({ music: true });
  env.controller.restore(env.saved({ position: 91.339, playing: false, wantsPlay: false, pausedByUser: true }));
  env.advance(10);
  assert.equal(env.last().position, 91.339);
  assert.equal(env.last().restoring, false);
  assert.equal(env.last().restoreCheckpoint.position, 91.339);
  for (let reset = 0; reset < 2; reset++) {
    env.media._time = 0;
    env.media.emit('timeupdate');
    assert.equal(env.last().restoring, true, 'reset must not be persisted before re-seek completes');
    env.advance(10);
    assert.equal(env.media.currentTime, 91.339);
    assert.equal(env.last().pausedByUser, true);
    assert.equal(env.media.playCalls, 0, 'paused repair must never start music');
  }
  env.media._time = 0; env.media.emit('timeupdate'); env.advance(10000);
  assert.equal(env.last().restoreFailed, true);
  assert.equal(env.last().restoreFailureReason, 'timeline-reset');
  assert.equal(env.last().restoreTarget.position, 91.339);
  assert.equal(env.media.seekCalls.length, 3, 'initial seek plus at most two repair seeks');
  assert.equal(env.media.playCalls, 0);
});

test('restore checkpoint remains through initial Play until real progress advances, then explicit seeks work', () => {
  const env = fixture({ music: true });
  env.controller.restore(env.saved({ position: 91, playing: false, wantsPlay: false, pausedByUser: true }));
  env.advance(10);
  env.controller.command('play'); env.advance(0);
  assert.equal(env.last().restoreCheckpoint.position, 91, 'a raw play event alone is not stable playback progress');
  env.media._time = 0; env.media.emit('timeupdate'); env.advance(10);
  assert.equal(env.media.currentTime, 91);
  assert.equal(env.media.paused, false);
  env.media._time = 115; env.media.emit('timeupdate');
  assert.equal(env.last().restoreCheckpoint, null);
  assert.equal(env.media.currentTime, 115, 'delayed background progress must not be rewound');
  env.controller.command('pause'); env.advance(0);
  env.controller.command('seek', 20); env.advance(10);
  assert.equal(env.media.currentTime, 20);
});

test('explicit seek or navigation cancels paused checkpoint protection', () => {
  const env = fixture({ music: true });
  env.controller.restore(env.saved({ playing: false, pausedByUser: true })); env.advance(10);
  env.controller.command('seek', 10); env.advance(10);
  assert.equal(env.media.currentTime, 10);
  assert.equal(env.last().restoreCheckpoint, null);
  env.controller.restore(env.saved({ playing: false, pausedByUser: true })); env.advance(10);
  env.controller.prepareNavigation();
  assert.equal(env.last().restoreCheckpoint, null);
});

test('YouTube Home inline previews cannot become native playback or replace the saved browsing route', () => {
  const env = fixture({ url: 'https://m.youtube.com/' });
  env.media.muted = true;
  env.start(); env.advance(2000);
  assert.equal(env.controller.activeMedia(), null);
  assert.equal(env.last().url, 'https://m.youtube.com/');
  assert.equal(env.last().mediaUrl, '');
  assert.equal(env.last().videoId, '');
  assert.equal(env.last().playing, false);
  assert.equal(env.last().wantsPlay, false);
});

test('different host, different video, stale provider and navigation cannot receive a saved seek', () => {
  const env = fixture();
  assert.equal(env.controller.restore(env.saved({ mediaUrl: `https://music.youtube.com/watch?v=${VIDEO}` })), false);
  assert.equal(env.controller.restore(env.saved({ videoId: OTHER })), false);
  env.providerId = OTHER;
  env.controller.restore(env.saved());
  vm.runInContext(`history.pushState({}, '', '/watch?v=${OTHER}')`, env.context);
  env.media.emit('canplay'); env.advance(2000);
  assert.equal(env.media.seekCalls.length, 0);
  assert.equal(env.media.playCalls, 0);
  assert.equal(env.last().restoring, false);
});

test('ad player cannot consume a restoration and ordinary video remains unmuted', () => {
  const env = fixture();
  env.ad = true;
  env.controller.restore(env.saved()); env.advance(1000);
  assert.equal(env.media.seekCalls.length, 0);
  assert.equal(env.media.playCalls, 0);
  env.ad = false;
  env.media.emit('canplay'); env.advance(10);
  assert.equal(env.media.currentTime, 83);
  assert.equal(env.media.playCalls, 1);
  assert.equal(env.media.muted, false);
});

test('ended track stays ended and only an identified next queue track can resume', () => {
  const env = fixture();
  env.start(); env.controller.setBackground(true);
  env.media.ended = true; env.media.paused = true; env.media.emit('ended'); env.advance(4000);
  assert.equal(env.media.playCalls, 1);
  vm.runInContext(`history.pushState({}, '', '/watch?v=${OTHER}')`, env.context);
  env.media.ended = false;
  env.media.currentSrc = 'blob:next-track';
  env.media.emit('canplay'); env.advance(2000);
  assert.equal(env.media.playCalls, 1, 'old identity cannot resume after route change');
  env.providerId = OTHER;
  env.media.emit('canplay'); env.advance(0);
  assert.equal(env.media.playCalls, 2);
  assert.equal(env.last().videoId, OTHER);
});

test('periodic snapshot persists progress and Music browsing restore preserves scrolling', () => {
  const env = fixture({ music: true });
  env.start();
  env.media._time = 123.4;
  env.advance(2000);
  assert.equal(env.last().position, 123.4);
  assert.equal(env.last().title, 'Music track');
  assert.equal(env.last().mediaUrl, `https://music.youtube.com/watch?v=${VIDEO}`);
  const browse = fixture({ url: 'https://music.youtube.com/library' });
  browse.providerId = '';
  assert.equal(browse.controller.restore({ url: 'https://music.youtube.com/library', scrollY: 350 }), true);
  assert.equal(browse.window.scrollY, 350);
  assert.equal(browse.media.playCalls, 0);
});

test('reinjecting controller does not install duplicate periodic work', () => {
  const env = fixture();
  const original = env.controller;
  vm.runInContext(source, env.context);
  assert.equal(env.window.__shelbyPlayback, original);
  assert.equal(env.last().documentId, original.documentId);
  assert.notEqual(env.controller.documentId, fixture().controller.documentId, 'a different document needs a distinct native handshake token');
  env.start(); env.media._time = 10;
  const before = env.snapshots.length;
  env.advance(2000);
  assert.equal(env.snapshots.length, before + 1);
});

test('10 and 20 second skips resume after a provider seek pause', () => {
  for (const seconds of [10, 20]) {
    const env = fixture(); env.start();
    env.controller.command('seekBy', seconds);
    env.media.pause(); env.advance(30);
    assert.equal(env.media.currentTime, seconds);
    assert.equal(env.media.paused, false);
    assert.equal(env.last().pausedByUser, false);
  }
});

test('video tap is not a pause command and double-tap seek retains playback', () => {
  const env = fixture(); env.start();
  env.document.dispatch('pointerdown', { target: env.media });
  env.document.dispatch('click', { target: env.media });
  env.media.pause(); env.media.currentTime = 20; env.advance(30);
  assert.deepEqual(env.userIntents, []);
  assert.equal(env.media.paused, false);
});

test('slider seek resumes playback but explicit pause and focus loss win', () => {
  for (const interruption of ['none', 'pause', 'focus']) {
    const env = fixture(); env.start();
    const slider = { closest: selector => selector.includes('slider') ? slider : null };
    env.document.dispatch('pointerdown', { target: slider });
    env.media.pause(); env.advance(0);
    env.media.currentTime = 60;
    if (interruption === 'pause') env.controller.command('pause');
    if (interruption === 'focus') { env.media.paused = true; env.media.emit('pause'); }
    env.advance(30);
    assert.equal(env.media.paused, interruption !== 'none', interruption);
  }
});

test('seeking a deliberately paused video stays paused and Play video resumes it', () => {
  const env = fixture(); env.start();
  env.controller.command('pause'); env.advance(2000);
  env.controller.command('seekBy', 20); env.advance(30);
  assert.equal(env.media.paused, true);
  const button = { closest: selector => selector === 'button,[role="button"]' ? button : null,
    getAttribute: () => 'Play video' };
  env.document.dispatch('click', { target: button });
  env.media.play(); env.advance(30);
  assert.equal(env.media.paused, false);
  assert.deepEqual(env.userIntents, [true]);
});

test('mobile controls overlay double-tap seek clears a paused restore checkpoint', () => {
  const env = fixture();
  env.controller.restore(env.saved({playing:false, pausedByUser:true})); env.advance(30);
  const overlay = { closest: selector => selector.includes('.player-controls-background') ? overlay : null };
  env.document.dispatch('pointerdown', {target:overlay});
  env.media.currentTime = 103; env.advance(30);
  assert.equal(env.media.currentTime, 103);
  assert.equal(env.media.paused, true);
  assert.equal(env.last().restoreCheckpoint, null);
  env.controller.command('play'); env.advance(30);
  assert.equal(env.media.paused, false);
  assert.equal(env.media.currentTime, 103);
});

test('settings expose available qualities and apply speed and resolution without losing playback intent', () => {
  const env = fixture(); env.start();
  const chosen = [];
  env.player.getAvailableQualityLevels = () => ['hd720', 'medium', 'auto'];
  env.player.getPlaybackQuality = () => 'medium';
  env.player.setPlaybackQualityRange = q => chosen.push(['range', q]);
  env.player.setPlaybackQuality = q => { chosen.push(['quality', q]); env.media.pause(); };
  env.player.setPlaybackRate = speed => chosen.push(['speed', speed]);
  assert.equal(env.controller.playbackOptions().quality, 'medium');
  assert.equal(env.controller.setSpeed(1.5), true);
  assert.equal(env.media.playbackRate, 1.5);
  assert.equal(env.controller.setSpeed(99), false);
  assert.equal(env.controller.setQuality('hd2160'), false);
  assert.equal(env.controller.setQuality('hd720'), true);
  env.advance(0); env.media.emit('canplay'); env.advance(0);
  assert.equal(env.media.paused, false);
  assert.deepEqual(chosen, [['speed',1.5],['range','hd720'],['quality','hd720']]);
  env.controller.command('pause'); env.advance(0);
  env.controller.setQuality('auto'); env.media.emit('canplay'); env.advance(0);
  assert.equal(env.media.paused, true);
});

test('audio details match the active format rather than the largest available bitrate', () => {
  const env = fixture(); env.start();
  env.media.playbackRate = 1;
  env.media.buffered = {length:1,start:()=>0,end:()=>30};
  env.player.getPlayerResponse = () => ({videoDetails:{videoId:VIDEO,title:'Track',author:'Artist'},
    streamingData:{adaptiveFormats:[
      {itag:140,mimeType:'audio/mp4; codecs="mp4a.40.2"',averageBitrate:129000,bitrate:131000,audioSampleRate:'44100',audioChannels:2,audioQuality:'AUDIO_QUALITY_MEDIUM'},
      {itag:251,mimeType:'audio/webm; codecs="opus"',averageBitrate:141083,bitrate:156569,audioSampleRate:'48000',audioChannels:2,audioQuality:'AUDIO_QUALITY_MEDIUM'},
      {itag:774,mimeType:'audio/webm; codecs="opus"',averageBitrate:256000,audioQuality:'AUDIO_QUALITY_HIGH'}]}});
  env.player.getStatsForNerds = () => ({video_id_and_cpn:VIDEO+' / private-session-id',codecs:'avc1 (137) / opus (251)',bandwidth_kbps:'8412 Kbps'});
  const details = env.controller.audioDetails();
  assert.equal(details.active.averageBitrate,141083);
  assert.equal(details.active.sampleRate,48000);
  assert.equal(details.active.channels,2);
  assert.equal(details.highQualityAvailable,true);
  assert.equal(details.bufferedSeconds,30);
  assert.equal(details.networkEstimate,'8412 Kbps');
  assert.equal(JSON.stringify(details).includes('private-session-id'),false);
  env.player.getStatsForNerds = () => ({video_id_and_cpn:OTHER+' / stale',codecs:'0 / opus (251)'});
  assert.equal(env.controller.audioDetails().active,null,'stale stats cannot identify the active stream');
});

test('audio details reject stale metadata, ads, ambiguous formats and unavailable methods', () => {
  const env = fixture(); env.start();
  assert.equal(env.controller.audioDetails().active,null);
  const format = {itag:251,mimeType:'audio/webm; codecs="opus"',averageBitrate:150000};
  env.player.getStatsForNerds = () => ({video_id_and_cpn:VIDEO+' / id',codecs:'0 / opus (251)'});
  let response = {videoDetails:{videoId:OTHER},streamingData:{adaptiveFormats:[format]}};
  env.player.getPlayerResponse = () => response;
  assert.equal(env.controller.audioDetails().formats.length,0);
  response.videoDetails.videoId=VIDEO;
  response.streamingData.adaptiveFormats.push({...format,audioTrack:{id:'other-language'}});
  assert.equal(env.controller.audioDetails().active,null,'duplicate format IDs require a verified track match');
  env.ad=true;
  assert.equal(env.controller.audioDetails().formats.length,0);
  env.player.getStatsForNerds = () => {throw new Error('unsupported');};
  assert.doesNotThrow(()=>env.controller.audioDetails());
});
