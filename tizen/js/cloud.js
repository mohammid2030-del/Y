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

  function form(o) { return Object.keys(o).map(function (k) { return k + '=' + encodeURIComponent(o[k]); }).join('&'); }

  /* ---- Google account: code on the TV, google.com/device on the phone */
  function googleAvailable() { return enabled() && !!(cfg.gid && cfg.gsec); }
  async function startGoogle() {
    if (!googleAvailable()) return null;
    var r = await req('POST', 'https://oauth2.googleapis.com/device/code', form({ client_id: cfg.gid, scope: 'openid email profile' }), true);
    if (!r || !r.device_code) return null;
    return { device: r.device_code, user: r.user_code, url: r.verification_url || 'https://www.google.com/device',
      interval: Math.max(5, r.interval || 5), expires: Date.now() + Math.max(60, r.expires_in || 900) * 1000 };
  }
  /** One poll; resolves {ok,email} | 'pending' | 'denied' | 'expired' | 'failed'. */
  function pollGoogle(code) {
    if (Date.now() > code.expires) return Promise.resolve('expired');
    return new Promise(function (resolve) {
      var x = new XMLHttpRequest();
      x.open('POST', 'https://oauth2.googleapis.com/token', true);
      x.timeout = 15000;
      x.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      x.onload = function () {
        var j = {}; try { j = JSON.parse(x.responseText); } catch (e) { /* ignore */ }
        if (j.error) return resolve(j.error === 'authorization_pending' || j.error === 'slow_down' ? 'pending' : j.error === 'access_denied' ? 'denied' : j.error === 'expired_token' ? 'expired' : 'failed');
        if (!j.id_token) return resolve('failed');
        req('POST', 'https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=' + cfg.apiKey,
          JSON.stringify({ postBody: 'id_token=' + j.id_token + '&providerId=google.com', requestUri: 'http://localhost', returnSecureToken: true }), false)
          .then(async function (f) {
            if (!f || !f.idToken) return resolve('failed');
            idToken = f.idToken; expiry = Date.now() + (parseInt(f.expiresIn, 10) - 120) * 1000;
            await Store.set('fbUser', { refresh: f.refreshToken, uid: f.localId, email: f.email || '' });
            account = { uid: f.localId, email: f.email || '' };
            resolve({ ok: true, email: f.email || '' });
          });
      };
      x.onerror = x.ontimeout = function () { resolve('pending'); };
      x.send(form({ client_id: cfg.gid, client_secret: cfg.gsec, device_code: code.device, grant_type: 'urn:ietf:params:oauth:grant-type:device_code' }));
    });
  }
  var account = null;
  async function loadAccount() { var u = await Store.get('fbUser', null); account = u && u.uid ? { uid: u.uid, email: u.email } : null; return account; }
  async function signOut() { await Store.del('fbUser'); account = null; idToken = ''; }

  async function token() {
    if (idToken && Date.now() < expiry) return idToken;
    var user = await Store.get('fbUser', null);
    if (user && user.refresh) {
      var u = await req('POST', 'https://securetoken.googleapis.com/v1/token?key=' + cfg.apiKey,
        'grant_type=refresh_token&refresh_token=' + encodeURIComponent(user.refresh), true);
      if (u && u.id_token) {
        idToken = u.id_token; expiry = Date.now() + (parseInt(u.expires_in, 10) - 120) * 1000;
        if (u.refresh_token && u.refresh_token !== user.refresh) { user.refresh = u.refresh_token; Store.set('fbUser', user); }
        return idToken;
      }
    }
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

  /** SHA-256 as lowercase hex (same as the Apple / Android apps), pure JS so it works on every TV. */
  function sha256hex(str) {
    var bytes = unescape(encodeURIComponent(str)), i, j;
    var K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
      0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
      0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var l = bytes.length, words = [];
    for (i = 0; i < l; i++) words[i >> 2] |= (bytes.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8);
    words[l >> 2] |= 0x80 << (24 - (l % 4) * 8);
    words[(((l + 8) >> 6) << 4) + 15] = l * 8;
    function r(x, n) { return (x >>> n) | (x << (32 - n)); }
    for (i = 0; i < words.length; i += 16) {
      var w = [], a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (j = 0; j < 64; j++) {
        if (j < 16) w[j] = words[i + j] | 0;
        else {
          var s0 = r(w[j - 15], 7) ^ r(w[j - 15], 18) ^ (w[j - 15] >>> 3), s1 = r(w[j - 2], 17) ^ r(w[j - 2], 19) ^ (w[j - 2] >>> 10);
          w[j] = (w[j - 16] + s0 + w[j - 7] + s1) | 0;
        }
        var t1 = (h + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + K[j] + w[j]) | 0;
        var t2 = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    return H.map(function (x) { return ('00000000' + (x >>> 0).toString(16)).slice(-8); }).join('');
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
    return sha256hex('nova:' + raw).slice(0, 40);
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
    googleAvailable: googleAvailable, startGoogle: startGoogle, pollGoogle: pollGoogle,
    loadAccount: loadAccount, account: function () { return account; }, signOut: signOut,
    subKey: subKey,
    safe: safe,
    sha256hex: sha256hex,
    Codec: Codec,
    get: function (p) { return call('GET', p); },
    put: function (p, v) { return call('PUT', p, v); }
  };
})(window);
