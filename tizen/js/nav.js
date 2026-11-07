/* Remote-control spatial navigation. Focusable elements carry the [data-f] attribute. */
(function (global) {
  'use strict';

  var KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8,
    PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    CH_UP: 427, CH_DOWN: 428, INFO: 457, RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406
  };

  var current = null;
  var keyHandler = null;   // screen-level handler, return true to consume
  var backHandler = null;

  function registerKeys() {
    try {
      if (global.tizen && tizen.tvinputdevice) {
        var wanted = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
          'ChannelUp', 'ChannelDown', 'Info', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue'];
        var supported = tizen.tvinputdevice.getSupportedKeys().map(function (k) { return k.name; });
        var list = wanted.filter(function (k) { return supported.indexOf(k) >= 0; });
        if (tizen.tvinputdevice.registerKeyBatch) tizen.tvinputdevice.registerKeyBatch(list);
        else list.forEach(function (k) { tizen.tvinputdevice.registerKey(k); });
      }
    } catch (e) { /* not on a TV */ }
  }

  function layer() {
    var modals = document.querySelectorAll('.modal');
    return modals.length ? modals[modals.length - 1] : document.getElementById('app');
  }

  function visible(el) {
    if (!el || el.offsetParent === null) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function focusables() {
    return Array.prototype.filter.call(layer().querySelectorAll('[data-f]'), function (el) {
      return !el.disabled && visible(el);
    });
  }

  function focus(el, opts) {
    if (!el) return;
    if (current && current !== el) current.classList.remove('focused');
    current = el;
    el.classList.add('focused');
    if (el.tagName === 'INPUT') {
      // don't open the TV keyboard just because focus passed through
    } else if (document.activeElement && document.activeElement.tagName === 'INPUT') {
      document.activeElement.blur();
    }
    if (!(opts && opts.noScroll)) {
      try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) { el.scrollIntoView(false); }
    }
    var group = el.closest('[data-group]');
    if (group) group.setAttribute('data-last', indexInGroup(el, group));
    if (el.onfocusnav) el.onfocusnav(el);
  }

  function indexInGroup(el, group) {
    return Array.prototype.indexOf.call(group.querySelectorAll('[data-f]'), el);
  }

  function focusFirst(root) {
    var scope = root || layer();
    var preferred = scope.querySelector('[data-f][data-autofocus]');
    var el = preferred && visible(preferred) ? preferred : null;
    if (!el) {
      var list = Array.prototype.filter.call(scope.querySelectorAll('[data-f]'), visible);
      el = list[0];
    }
    if (el) focus(el);
    return el;
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(dir) {
    var els = focusables();
    if (!current || els.indexOf(current) < 0) { focusFirst(); return true; }
    var from = current.getBoundingClientRect(), c = center(from);
    var best = null, bestScore = Infinity;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === current) continue;
      var r = el.getBoundingClientRect(), p = center(r), primary, secondary;
      if (dir === 'left') { if (r.right > from.left + 1) continue; primary = from.left - r.right; secondary = Math.abs(p.y - c.y); }
      else if (dir === 'right') { if (r.left < from.right - 1) continue; primary = r.left - from.right; secondary = Math.abs(p.y - c.y); }
      else if (dir === 'up') { if (r.bottom > from.top + 1) continue; primary = from.top - r.bottom; secondary = Math.abs(p.x - c.x); }
      else { if (r.top < from.bottom - 1) continue; primary = r.top - from.bottom; secondary = Math.abs(p.x - c.x); }
      var score = Math.max(primary, 0) + secondary * 2.2;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) {
      // entering a different group: restore that group's last focused item
      var g = best.closest('[data-group]');
      if (g && g !== current.closest('[data-group]') && g.hasAttribute('data-last')) {
        var items = g.querySelectorAll('[data-f]');
        var remembered = items[parseInt(g.getAttribute('data-last'), 10)];
        if (remembered && visible(remembered)) best = remembered;
      }
      focus(best);
      return true;
    }
    return false;
  }

  function onKey(e) {
    var code = e.keyCode;
    var inInput = document.activeElement && document.activeElement.tagName === 'INPUT';
    if (keyHandler && keyHandler(code, e)) { e.preventDefault(); return; }

    if (code === KEY.BACK || code === KEY.ESC || (code === KEY.BACKSPACE && !inInput)) {
      e.preventDefault();
      if (inInput) { document.activeElement.blur(); return; }
      if (backHandler) backHandler();
      return;
    }
    if (inInput && (code === KEY.LEFT || code === KEY.RIGHT)) return; // caret movement
    var dir = code === KEY.LEFT ? 'left' : code === KEY.RIGHT ? 'right' : code === KEY.UP ? 'up' : code === KEY.DOWN ? 'down' : null;
    if (dir) {
      e.preventDefault();
      if (inInput) document.activeElement.blur();
      move(dir);
      return;
    }
    if (code === KEY.ENTER && current) {
      e.preventDefault();
      if (current.tagName === 'INPUT') { current.focus(); return; }
      current.click();
    }
  }

  document.addEventListener('keydown', onKey);
  document.addEventListener('click', function (e) {
    var el = e.target.closest && e.target.closest('[data-f]');
    if (el) focus(el, { noScroll: true });
  }, true);

  global.Nav = {
    KEY: KEY, registerKeys: registerKeys, focus: focus, focusFirst: focusFirst, move: move,
    get current() { return current; },
    setKeyHandler: function (fn) { keyHandler = fn; },
    setBackHandler: function (fn) { backHandler = fn; },
    reset: function () { current = null; }
  };
})(window);
