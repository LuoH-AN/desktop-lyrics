const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const html = fs.readFileSync('app/src/main/assets/home_lyrics.html', 'utf8');
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
class Element {
  constructor() {
    this.style = { setProperty() {}, removeProperty() {} };
    this.attributes = {};
    this.children = [];
    this.events = {};
    this.textContent = '';
    this.clientHeight = 640;
    this.offsetHeight = 40;
    this.offsetTop = 0;
    this.disabled = false;
    const classes = new Set();
    this.classList = {
      add: (...values) => values.forEach(value => classes.add(value)),
      remove: (...values) => values.forEach(value => classes.delete(value)),
      contains: value => classes.has(value),
      toggle(value, on) {
        const selected = on === undefined ? !classes.has(value) : on;
        if (selected) classes.add(value); else classes.delete(value);
        return selected;
      }
    };
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, listener) { this.events[name] = listener; }
  appendChild(child) { this.children.push(child); }
  getBoundingClientRect() { return { left: 0, width: 300 }; }
  set innerHTML(value) { this.children = []; }
}
const elements = new Map([...ids].map(id => [id, new Element()]));
let sought = null;
const context = {
  console,
  document: {
    documentElement: new Element(),
    getElementById(id) {
      assert.ok(ids.has(id), `missing element: ${id}`);
      return elements.get(id);
    },
    createElement: () => new Element(),
    createTextNode: text => Object.assign(new Element(), { textContent: text })
  },
  performance: { now: () => 0 },
  requestAnimationFrame: () => 1,
  cancelAnimationFrame() {},
  setInterval: () => 1,
  clearInterval() {},
  setTimeout: () => 1,
  clearTimeout() {},
  addEventListener() {},
  LyricHomeNative: {
    openMore() {}, editCustomLyrics() {}, togglePlay() {},
    seekTo(value) { sought = value; }
  }
};
context.window = context;
vm.createContext(context);
for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  vm.runInContext(match[1], context);
}
const home = context.LyricHome;
const snapshot = { track: 'Song', artist: 'Artist', durationMs: 100000, positionMs: 10000, state: 'playing', canPrev: true, canNext: true };
home.setSnapshot(snapshot);
assert.equal(elements.get('emptyText').textContent, '正在获取歌词…');
home.setLyrics([]);
assert.equal(elements.get('emptyText').textContent, '找不到歌词');
home.setSnapshot({ ...snapshot, state: 'paused' });
assert.equal(elements.get('emptyText').textContent, '找不到歌词', 'media updates must not turn a completed search back into loading');
assert.ok(!ids.has('emptyAdd'), 'empty state must not add a retry or import action');
home.setLyrics([{ time: 0, text: 'first' }, { time: 5000, text: 'second' }]);
assert.ok(!elements.get('empty').classList.contains('show'));
assert.equal(elements.get('btnPlay').attributes['aria-label'], '播放');
elements.get('progWrap').events.keydown({ key: 'ArrowRight', preventDefault() {} });
assert.equal(sought, 15000, 'keyboard seeking should use the current playback position');
home.setSnapshot({ track: '' });
assert.equal(elements.get('track').children.length, 0, 'ending a session must not leave old lyrics visible');
assert.equal(elements.get('emptyText').textContent, '未在播放');
assert.equal(elements.get('btnPlay').disabled, true);
home.clear();
home.setSnapshot(snapshot);
assert.equal(elements.get('emptyText').textContent, '正在获取歌词…');
home.setLyricsFromLrc('[00:01.00]第一句\n[00:03.00]第二句', '', '', 'test', false);
assert.equal(elements.get('track').children.length, 2);
console.log('PASS: home empty states, session reset, accessible controls, keyboard seek, LRC rendering');
