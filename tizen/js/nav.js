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
  var lastKeyAt = 0, lastAnyKey = 0;
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

  var rectCache = null;   // rects measured once per key press (TVs are slow at layout reads)
  function rectOf(el) {
    if (rectCache) { var r = rectCache.get(el); if (r) return r; r = el.getBoundingClientRect(); rectCache.set(el, r); return r; }
    return el.getBoundingClientRect();
  }
  function focusables() {
    return Array.prototype.filter.call(layer().querySelectorAll('[data-f]'), function (el) {
      if (el.disabled || el.offsetParent === null) return false;
      var r = rectOf(el);
      return r.width > 0 && r.height > 0;
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

  /** True when nothing focusable sits above the element in this scroller (it's the first row). */
  function firstIn(p, el, r) {
    // only the first few focusables in page order can sit above the first row
    var list = p.querySelectorAll('[data-f]');
    for (var i = 0; i < list.length && i < 40; i++) {
      var o = list[i];
      if (o === el || o.disabled || o.offsetParent === null) continue;
      if (rectOf(o).bottom <= r.top + 1) return false;
    }
    return true;
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
        if (offTop + r.height < pr.height * 0.55 || firstIn(p, el, r)) dy = -p.scrollTop;   // near the start / first row: show the whole top (hero) again
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
    if (!(opts && opts.noScroll)) reveal(el, false);
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

  /**
   * Best element in a direction.
   * Direction is decided by centres (a focused item is scaled up and can overlap its neighbour — with edges
   * that neighbour was skipped: Live TV → Series, Today → nothing).
   * Up/down: stay in the same list/grid when it continues that way; otherwise go to the nearest row and pick
   * the closest item in it, even if it sits far to the side (Today / Tomorrow chips).
   */
  function pick(els, dir, from, c) {
    var cands = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === current) continue;
      var r = rectOf(el), p = center(r);
      var ok = dir === 'left' ? p.x < c.x - 2 && r.left < from.left - 1
        : dir === 'right' ? p.x > c.x + 2 && r.right > from.right + 1
        : dir === 'up' ? p.y < c.y - 2 && r.top < from.top - 1
        : p.y > c.y + 2 && r.bottom > from.bottom + 1;
      if (ok) cands.push({ el: el, r: r, p: p });
    }
    if (!cands.length) return null;
    function gapX(r) { return Math.max(0, Math.max(r.left, from.left) - Math.min(r.right, from.right)); }
    function gapY(r) { return Math.max(0, Math.max(r.top, from.top) - Math.min(r.bottom, from.bottom)); }
    var best = null, bestScore = Infinity, k, o, score;
    if (dir === 'left' || dir === 'right') {
      for (k = 0; k < cands.length; k++) {
        o = cands[k];
        var prim = dir === 'left' ? from.left - o.r.right : o.r.left - from.right;
        score = Math.max(prim, 0) + gapY(o.r) * 3 + Math.abs(o.p.y - c.y) * 0.1;
        if (score < bestScore) { bestScore = score; best = o.el; }
      }
      return best;
    }
    // up / down
    var group = current && current.closest('[data-group]');
    if (group) {
      for (k = 0; k < cands.length; k++) {
        o = cands[k];
        if (!group.contains(o.el) || gapX(o.r) > 0) continue;
        score = gapY(o.r) + Math.abs(o.p.x - c.x) * 0.1;
        if (score < bestScore) { bestScore = score; best = o.el; }
      }
      if (best) return best;
    }
    var nearest = Infinity;
    for (k = 0; k < cands.length; k++) nearest = Math.min(nearest, gapY(cands[k].r));
    for (k = 0; k < cands.length; k++) {
      o = cands[k];
      if (gapY(o.r) > nearest + 40) continue;   // only the nearest row
      score = gapX(o.r) * 2 + Math.abs(o.p.x - c.x) * 0.1 + gapY(o.r);
      if (score < bestScore) { bestScore = score; best = o.el; }
    }
    return best;
  }

  function move(dir) {
    rectCache = typeof Map === 'function' ? new Map() : null;
    try { return moveInner(dir); } finally { rectCache = null; }
  }

  function moveInner(dir) {
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
    // The top bar floats over the content, so it is never "between" rows:
    //  - inside the bar, left/right stay in the bar and up does nothing;
    //  - from the content, up goes through every row (even ones scrolled out of view) and only
    //    reaches the bar from the top-most row.
    var navEls = [], contentEls = [];
    for (var n = 0; n < els.length; n++) (els[n].closest('.topnav') ? navEls : contentEls).push(els[n]);
    if (inNav) {
      if (dir === 'up') return true;
      if (dir === 'left' || dir === 'right') els = navEls;
      else els = contentEls;
    } else if (dir !== 'up') {
      els = contentEls;
    }
    var from = current.getBoundingClientRect(), c = center(from);
    var best = null, bestScore = Infinity;
    if (!inNav && dir === 'up') {
      best = pick(contentEls, dir, from, c);
      if (!best) {
        var act = document.querySelector('.topnav .side-item.active');
        var target = act && visible(act) ? act : pick(navEls, dir, from, c) || navEls[0];
        if (target) {
          // show the top of the page (hero / first row) under the bar
          scrollParents(current).forEach(function (p) { if (p.scrollHeight > p.clientHeight + 1) p.scrollTop = 0; });
          focus(target, { noScroll: true });
        }
        return true;
      }
      els = [best];
    }
    if (!best) best = pick(els, dir, from, c);
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
    lastAnyKey = Date.now();
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
    /** ms since the last remote key — background work waits until the viewer pauses. */
    idleFor: function () { return Date.now() - lastAnyKey; },
    reveal: reveal,
    setKeyHandler: function (fn) { keyHandler = fn; },
    setBackHandler: function (fn) { backHandler = fn; },
    reset: function () { current = null; }
  };
})(window);
