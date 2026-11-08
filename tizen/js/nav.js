/* Remote-control spatial navigation. Focusable elements carry the [data-f] attribute. */
(function (global) {
  'use strict';

  var KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8,
    PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    CH_UP: 427, CH_DOWN: 428, INFO: 457, RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406
  };

  var current = null;
  var lastRect = null;        // where focus was, to recover when the focused element is re-rendered
  var lastKeyAt = 0;
  var lastContent = null;     // last focused element outside the top navigation
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

  function scrollParents(el) {
    var out = [];
    for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      var cs = getComputedStyle(p);
      var oy = cs.overflowY, ox = cs.overflowX;
      if ((/(auto|scroll|hidden)/.test(oy) && p.scrollHeight > p.clientHeight + 1) ||
          (/(auto|scroll|hidden)/.test(ox) && p.scrollWidth > p.clientWidth + 1)) out.push(p);
    }
    return out;
  }

  /** Scrolls every scrollable ancestor so the element is fully visible and never hidden under the floating top bar. */
  function reveal(el, smooth) {
    var nav = document.querySelector('.topnav');
    var navBottom = nav ? nav.getBoundingClientRect().bottom + 24 : 0;
    var parents = scrollParents(el);
    for (var i = 0; i < parents.length; i++) {
      var p = parents[i], pr = p.getBoundingClientRect(), r = el.getBoundingClientRect();
      var topLimit = Math.max(pr.top, navBottom > pr.top && navBottom < pr.bottom ? navBottom : pr.top) + 70;   // room for the row title above the card
      var bottomLimit = pr.bottom - 30;
      var dy = 0, dx = 0;
      if (p.scrollHeight > p.clientHeight + 1) {
        var offTop = r.top - pr.top + p.scrollTop;         // position inside the scrolled content
        if (offTop + r.height < pr.height * 0.55) dy = -p.scrollTop;   // near the start: show the whole top (hero) again
        else if (el.closest('.hrow') && p.contains(el.closest('.hrow'))) dy = r.top - topLimit;   // rows: snap the row under the bar, like a TV shelf
        else if (r.top < topLimit) dy = r.top - topLimit;
        else if (r.bottom > bottomLimit) dy = Math.min(r.bottom - bottomLimit, r.top - topLimit);
      }
      if (p.scrollWidth > p.clientWidth + 1) {
        var l = pr.left + 50, rr = pr.right - 50;
        if (r.left < l) dx = r.left - l; else if (r.right > rr) dx = Math.min(r.right - rr, r.left - l);
      }
      if (dy || dx) {
        if (smooth && p.scrollTo) { try { p.scrollTo({ top: p.scrollTop + dy, left: p.scrollLeft + dx, behavior: 'smooth' }); continue; } catch (e) { /* old engine */ } }
        p.scrollTop += dy; p.scrollLeft += dx;
      }
    }
  }

  function focus(el, opts) {
    if (!el) return;
    if (current && current !== el) current.classList.remove('focused');
    current = el;
    el.classList.add('focused');
    if (!el.closest('.topnav')) lastContent = el;
    if (el.tagName === 'INPUT') {
      // don't open the TV keyboard just because focus passed through
    } else if (document.activeElement && document.activeElement.tagName === 'INPUT') {
      document.activeElement.blur();
    }
    if (!(opts && opts.noScroll)) reveal(el, !(opts && opts.instant) && Date.now() - lastKeyAt > 160);
    lastRect = el.getBoundingClientRect();
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

  function nearestTo(rect, els) {
    var c = center(rect), best = null, bd = Infinity;
    for (var i = 0; i < els.length; i++) {
      var p = center(els[i].getBoundingClientRect());
      var d = Math.abs(p.x - c.x) + Math.abs(p.y - c.y);
      if (d < bd) { bd = d; best = els[i]; }
    }
    return best;
  }

  function move(dir) {
    var els = focusables();
    if (!current || els.indexOf(current) < 0) {
      // the focused element was re-rendered: continue from where it was instead of jumping to the top
      var back = lastRect ? nearestTo(lastRect, els) : null;
      if (back) focus(back); else focusFirst();
      return true;
    }
    var inNav = !!current.closest('.topnav');
    // Leaving the top bar downwards returns to the item you came from.
    if (inNav && dir === 'down' && lastContent && document.body.contains(lastContent) && visible(lastContent)) {
      focus(lastContent);
      return true;
    }
    var from = current.getBoundingClientRect(), c = center(from);
    var best = null, bestScore = Infinity;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === current) continue;
      var r = el.getBoundingClientRect(), p = center(r), primary, secondary;
      // secondary = gap between the rectangles on the other axis (0 when they overlap), so a wide field
      // directly above/below always beats a smaller element further away.
      var gapX = Math.max(0, Math.max(r.left, from.left) - Math.min(r.right, from.right));
      var gapY = Math.max(0, Math.max(r.top, from.top) - Math.min(r.bottom, from.bottom));
      if (dir === 'left') { if (r.right > from.left + 1) continue; primary = from.left - r.right; secondary = gapY * 3 + Math.abs(p.y - c.y) * 0.1; }
      else if (dir === 'right') { if (r.left < from.right - 1) continue; primary = r.left - from.right; secondary = gapY * 3 + Math.abs(p.y - c.y) * 0.1; }
      else if (dir === 'up') { if (r.bottom > from.top + 1) continue; primary = from.top - r.bottom; secondary = gapX * 3 + Math.abs(p.x - c.x) * 0.1; }
      else { if (r.top < from.bottom - 1) continue; primary = r.top - from.bottom; secondary = gapX * 3 + Math.abs(p.x - c.x) * 0.1; }
      var score = Math.max(primary, 0) + secondary;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best && !inNav && best.closest('.topnav')) {
      // Entering the top bar always lands on the current section.
      var act = document.querySelector('.topnav .side-item.active');
      focus(act && visible(act) ? act : best);
      return true;
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
      var now = Date.now();
      if (e.repeat && now - lastKeyAt < 70) return;   // keep up with a held key without piling up work
      lastKeyAt = now;
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
    reveal: reveal,
    setKeyHandler: function (fn) { keyHandler = fn; },
    setBackHandler: function (fn) { backHandler = fn; },
    reset: function () { current = null; }
  };
})(window);
