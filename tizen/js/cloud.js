/* Viewer sync over Firebase Realtime Database (REST + anonymous auth). Same wire format as the Apple and Android apps:
   nova/v1/<hash of the subscription's own server|user|pass>/profiles|data. ES2017, Chromium 69 safe. */
(function (global) {
  'use strict';
  var cfg = global.NOVA_CONFIG || {};
  var idToken = '', expiry = 0;

  function enabled() { return !!(cfg.dbUrl && cfg.apiKey); }

  function req(method, url, body, form) {
    return new Promise(function (resolve) {
      var x = new XMLHttpRequest();
      x.open(method, url, true);
      x.timeout = 15000;
      if (body != null) x.setRequestHeader('Content-Type', form ? 'application/x-www-form-urlencoded' : 'application/json');
      x.onload = function () {
        if (x.status === 401) idToken = '';
        if (x.status !== 200) return resolve(undefined);
        try { resolve(JSON.parse(x.responseText)); } catch (e) { resolve(undefined); }
      };
      x.onerror = x.ontimeout = function () { resolve(undefined); };
      x.send(body == null ? null : body);
    });
  }

  async function token() {
    if (idToken && Date.now() < expiry) return idToken;
    var refresh = await Store.get('fbRefresh', '');
    if (refresh) {
      var r = await req('POST', 'https://securetoken.googleapis.com/v1/token?key=' + cfg.apiKey,
        'grant_type=refresh_token&refresh_token=' + encodeURIComponent(refresh), true);
      if (r && r.id_token) {
        idToken = r.id_token; expiry = Date.now() + (parseInt(r.expires_in, 10) - 120) * 1000;
        if (r.refresh_token) Store.set('fbRefresh', r.refresh_token);
        return idToken;
      }
    }
    var s = await req('POST', 'https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=' + cfg.apiKey, '{"returnSecureToken":true}', false);
    if (!s || !s.idToken) return '';
    idToken = s.idToken; expiry = Date.now() + (parseInt(s.expiresIn, 10) - 120) * 1000;
    if (s.refreshToken) Store.set('fbRefresh', s.refreshToken);
    return idToken;
  }

  async function call(method, path, value) {
    if (!enabled()) return undefined;
    var t = await token();
    if (!t) return undefined;
    var base = String(cfg.dbUrl).replace(/\/+$/, '');
    return req(method, base + '/' + path + '.json?auth=' + encodeURIComponent(t), value === undefined ? null : JSON.stringify(value), false);
  }

  function safe(s) { return String(s).replace(/[.$#\[\]\/]/g, '_'); }

  function normalizeServer(input) {
    var s = String(input || '').trim();
    if (s && !/^http/i.test(s)) s = 'http://' + s;
    ['/player_api.php', '/get.php', '/xmltv.php', '/panel_api.php', '/c/'].forEach(function (m) {
      var i = s.indexOf(m); if (i >= 0) s = s.slice(0, i);
    });
    return s.replace(/\/+$/, '');
  }

  /** Shared folder of everyone signed in to this subscription. */
  async function subKey(sub) {
    if (!sub) return '';
    var raw = sub.type === 'xtream' ? normalizeServer(sub.server).toLowerCase() + '|' + (sub.user || sub.username || '') + '|' + (sub.pass || '')
      : (sub.m3uUrl || '');
    if (raw.length <= 3) return '';
    return (await Store.sha256('nova:' + raw)).slice(0, 40);
  }

  var Codec = {
    viewer: function (v) { var d = { n: v.name, c: v.color, u: v.updated }; if (v.pinHash) d.p = v.pinHash; if (v.deleted) d.x = true; return d; },
    viewerFrom: function (id, d) { return { id: id, name: d.n || '', color: d.c || 0, pinHash: d.p || null, updated: Number(d.u) || 0, deleted: !!d.x }; },
    historyKey: function (h) { return safe(h.type + '_' + h.id + '_' + (h.ep || '')); },
    history: function (h) {
      var it = h.item || {};
      return { k: h.type, i: h.id, n: it.name || h.title || '', l: h.logo || it.logo || '', c: it.cat || '', x: it.ext || '', y: it.year || '',
        e: h.ep || '', ex: h.ext || '', s: h.subtitle || '', p: h.pos || 0, d: h.dur || 0, u: (h.t || Date.now()) / 1000 };
    },
    historyFrom: function (d, owner) {
      if (!d || !d.k || !d.i) return null;
      var item = { id: String(d.i), name: d.n || '', logo: d.l || '', cat: d.c || '', ext: d.x || '', year: d.y || '' };
      return { sub: owner, type: d.k, id: String(d.i), ep: d.e || '', ext: d.ex || '', title: d.n || '', subtitle: d.s || '', logo: d.l || '',
        pos: Number(d.p) || 0, dur: Number(d.d) || 0, t: Math.round((Number(d.u) || 0) * 1000), item: item };
    },
    favKey: function (type, id) { return safe(type + '_' + id); },
    fav: function (f, deleted) {
      var it = f.item || {};
      var d = { k: f.type, i: f.id, n: f.name || it.name || '', l: f.logo || it.logo || '', c: it.cat || '', x: it.ext || '', y: it.year || '', u: Date.now() / 1000 };
      if (deleted) d.del = true;
      return d;
    }
  };

  global.Cloud = {
    enabled: enabled,
    subKey: subKey,
    safe: safe,
    Codec: Codec,
    get: function (p) { return call('GET', p); },
    put: function (p, v) { return call('PUT', p, v); }
  };
})(window);
