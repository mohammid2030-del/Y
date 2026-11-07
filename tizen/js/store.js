/* Persistent storage (IndexedDB key/value) + secret encryption with a non-extractable WebCrypto key. */
(function (global) {
  'use strict';

  var DB_NAME = 'nova-iptv';
  var dbPromise = null;

  function open() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise(function (resolve, reject) {
      var req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = function () { req.result.createObjectStore('kv'); };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
    return dbPromise;
  }

  function tx(mode, fn) {
    return open().then(function (db) {
      return new Promise(function (resolve, reject) {
        var t = db.transaction('kv', mode);
        var store = t.objectStore('kv');
        var result = fn(store);
        t.oncomplete = function () { resolve(result && result.result !== undefined ? result.result : undefined); };
        t.onerror = function () { reject(t.error); };
        t.onabort = function () { reject(t.error); };
      });
    });
  }

  function get(key, fallback) {
    return tx('readonly', function (s) { return s.get(key); }).then(function (v) {
      return v === undefined ? fallback : v;
    });
  }

  function set(key, value) { return tx('readwrite', function (s) { s.put(value, key); }); }
  function del(key) { return tx('readwrite', function (s) { s.delete(key); }); }

  function delPrefix(prefix) {
    return tx('readwrite', function (s) {
      var range = IDBKeyRange.bound(prefix, prefix + '￿');
      var req = s.openCursor(range);
      req.onsuccess = function () {
        var c = req.result;
        if (c) { c.delete(); c.continue(); }
      };
    });
  }

  // ---- secrets ----
  var keyPromise = null;
  function cryptoKey() {
    if (!(global.crypto && crypto.subtle)) return Promise.resolve(null);
    if (keyPromise) return keyPromise;
    keyPromise = get('__key', null).then(function (k) {
      if (k) return k;
      return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
        .then(function (nk) { return set('__key', nk).then(function () { return nk; }); });
    }).catch(function () { return null; });
    return keyPromise;
  }

  function b64(buf) {
    var bytes = new Uint8Array(buf), s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }
  function unb64(str) {
    var s = atob(str), out = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  }

  /** Returns "enc:..." when WebCrypto is available, otherwise "plain:..." (sandboxed app storage). */
  function encrypt(plain) {
    if (!plain) return Promise.resolve('');
    return cryptoKey().then(function (key) {
      if (!key) return 'plain:' + plain;
      var iv = crypto.getRandomValues(new Uint8Array(12));
      return crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, new TextEncoder().encode(plain))
        .then(function (ct) { return 'enc:' + b64(iv) + ':' + b64(ct); });
    }).catch(function () { return 'plain:' + plain; });
  }

  function decrypt(value) {
    if (!value) return Promise.resolve('');
    if (value.indexOf('plain:') === 0) return Promise.resolve(value.slice(6));
    if (value.indexOf('enc:') !== 0) return Promise.resolve(value);
    var parts = value.split(':');
    return cryptoKey().then(function (key) {
      if (!key) return '';
      return crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(parts[1]) }, key, unb64(parts[2]))
        .then(function (pt) { return new TextDecoder().decode(pt); });
    }).catch(function () { return ''; });
  }

  function sha256(text) {
    if (global.crypto && crypto.subtle) {
      return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then(function (h) { return b64(h); });
    }
    // Fallback (non-cryptographic) only if WebCrypto is unavailable.
    var hash = 5381;
    for (var i = 0; i < text.length; i++) hash = ((hash << 5) + hash + text.charCodeAt(i)) | 0;
    return Promise.resolve('djb2:' + hash);
  }

  global.Store = { get: get, set: set, del: del, delPrefix: delPrefix, encrypt: encrypt, decrypt: decrypt, sha256: sha256 };
})(window);
