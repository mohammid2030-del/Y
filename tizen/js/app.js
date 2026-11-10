/* NOVA IPTV for Samsung Tizen TV — screens, state and flows. ES2017 (Tizen 5.5+ / 2020+ TVs). */
(function () {
  'use strict';

  const t = I18n.t;
  const app = document.getElementById('app');
  const K = Nav.KEY;

  // ------------------------------------------------------------------ state
  const S = {
    settings: { lang: '', onboarded: false, activeSub: '', pinHash: '', bufferSec: 10 },
    subs: [],          // [{id,name,type,server,user,passEnc,m3uEnc,ua,liveFormat,lastSync,expires,maxConn}]
    sub: null,         // active subscription with decrypted secrets (memory only)
    data: null,        // {live,movie,series,cats:{live,movie,series}}
    fav: [],           // [{sub,type,id,name,logo,item}]
    history: {},       // key -> {sub,type,id,ep,title,subtitle,logo,ext,pos,dur,t,item}
    hidden: {},        // "type|catId" -> true (active subscription)
    ui: { section: 'home', cat: { live: '*all', movie: '*all', series: '*all' }, focusKey: null, search: { q: '', type: '' } }
  };

  let actions = {};
  let screen = null;      // { name, back(), key(code) }

  document.addEventListener('click', function (e) {
    const el = e.target.closest && e.target.closest('[data-action]');
    if (!el) return;
    const fn = actions[el.getAttribute('data-action')];
    if (fn) fn(el, e);
  });

  Nav.setBackHandler(function () { if (screen && screen.back) screen.back(); });
  Nav.setKeyHandler(function (code, e) {
    if (document.querySelector('.modal')) return false;
    return !!(screen && screen.key && screen.key(code, e));
  });

  // ------------------------------------------------------------------ helpers
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function fmtTime(ms) { const d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function fmtDate(ms) { return new Date(ms).toLocaleDateString(I18n.lang === 'ar' ? 'ar' : 'en'); }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtDur(ms) {
    const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return (h ? h + ':' + pad(m) : m) + ':' + pad(s % 60);
  }
  function img(url, cls) {
    return url ? `<img class="${cls || ''}" src="${esc(url)}" decoding="async" onerror="this.style.visibility='hidden'" alt="">` : '';
  }
  function errText(e) {
    const k = e && e.kind;
    if (k === 'network') return t('errNetwork');
    if (k === 'timeout') return t('errTimeout');
    if (k === 'auth') return t('errAuth');
    if (k === 'expired') return t('errExpired');
    if (k === 'http') return t('errHttp', e.code);
    if (k === 'notPlaylist') return t('errNotPlaylist');
    if (k === 'parse') return t('errParse');
    if (k === 'invalid') return t('errInvalid');
    return t('errUnknown');
  }
  function toast(msg) {
    let el = $('#toast');
    if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
    el.textContent = msg; el.className = 'show';
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { el.className = ''; }, 2600);
  }
  function setScreen(name, render, opts) {
    screen = Object.assign({ name: name }, opts || {});
    actions = {};
    app.className = 'screen-' + name;
    app.innerHTML = '';
    Nav.reset();
    render();
  }
  function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }
  function saveSettings() { return Store.set('settings', S.settings); }
  function saveSubs() { return Store.set('subs', S.subs); }
  function saveFav() { return Store.set('fav', S.fav); }
  let historyTimer = null;
  function saveHistory() { clearTimeout(historyTimer); historyTimer = setTimeout(function () { Store.set('history', S.history); }, 500); }

  // ------------------------------------------------------------------ modals
  function modal(html, onAction, opts) {
    const m = document.createElement('div');
    m.className = 'modal';
    m.innerHTML = `<div class="modal-card">${html}</div>`;
    document.body.appendChild(m);
    const prevFocus = Nav.current;
    const prevBack = screen && screen.back;
    function close() {
      m.remove();
      if (screen) screen.back = prevBack;
      if (prevFocus && document.body.contains(prevFocus)) Nav.focus(prevFocus); else Nav.focusFirst();
    }
    m.addEventListener('click', function (e) {
      const el = e.target.closest('[data-m]');
      if (el) { e.stopPropagation(); onAction(el.getAttribute('data-m'), close, m, el); }
    });
    if (screen) screen.back = function () { close(); if (opts && opts.onCancel) opts.onCancel(); };
    Nav.focusFirst(m);
    return { close: close, el: m };
  }

  function confirmBox(text, yesLabel) {
    return new Promise(function (resolve) {
      modal(`<h2>${esc(text)}</h2><div class="row center">
        <button data-f data-m="yes" class="btn primary">${esc(yesLabel || t('yes'))}</button>
        <button data-f data-m="no" data-autofocus class="btn">${esc(t('no'))}</button></div>`,
        function (a, close) { close(); resolve(a === 'yes'); }, { onCancel: function () { resolve(false); } });
    });
  }

  function pinBox(title) {
    return new Promise(function (resolve) {
      const md = modal(`<h2>${esc(title)}</h2>
        <input data-f data-autofocus id="pin-input" class="input" type="password" inputmode="numeric" maxlength="8" placeholder="••••">
        <div class="row center"><button data-f data-m="ok" class="btn primary">${esc(t('ok'))}</button>
        <button data-f data-m="cancel" class="btn">${esc(t('cancel'))}</button></div>`,
        function (a, close) {
          const v = $('#pin-input', md.el).value.replace(/\D/g, '');
          close(); resolve(a === 'ok' ? v : null);
        }, { onCancel: function () { resolve(null); } });
    });
  }

  function chooseBox(title, options, selected) {
    return new Promise(function (resolve) {
      const html = `<h2>${esc(title)}</h2><div class="choice-list">` + options.map(function (o, i) {
        return `<button data-f data-m="${i}" class="choice${o.value === selected ? ' selected' : ''}"${o.value === selected ? ' data-autofocus' : ''}>${esc(o.label)}</button>`;
      }).join('') + '</div>';
      modal(html, function (a, close) { close(); resolve(options[parseInt(a, 10)].value); }, { onCancel: function () { resolve(undefined); } });
    });
  }

  async function verifyPin() {
    if (!S.settings.pinHash) return true;
    const pin = await pinBox(t('enterPin'));
    if (pin == null) return false;
    const ok = (await Store.sha256('nova|' + pin)) === S.settings.pinHash;
    if (!ok) toast(t('wrongPin'));
    return ok;
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    Nav.registerKeys();
    S.settings = Object.assign(S.settings, await Store.get('settings', {}));
    I18n.setLang(S.settings.lang || I18n.detect());
    S.subs = await Store.get('subs', []);
    S.fav = await Store.get('fav', []);
    S.history = await Store.get('history', {});
    try { await Tmdb.init(); await Matches.init(); } catch (e) { /* optional features */ }
    try { await Cloud.loadAccount(); } catch (e) { /* sync is optional */ }
    document.addEventListener('visibilitychange', onVisibility);
    if (!S.settings.onboarded) return showWelcome();
    if (!S.subs.length) return Cloud.googleAvailable() && !Cloud.account() ? showGoogle() : showAddSub();
    showProfiles();
    if (Cloud.account()) pullAccount().then(function (changed) { if (changed && screen && screen.name === 'profiles') showProfiles(); });
  }

  async function activate(subId) {
    const raw = S.subs.find(function (s) { return s.id === subId; }) || S.subs[0];
    if (!raw) return showAddSub();
    S.settings.activeSub = raw.id;
    await saveSettings();
    S.sub = Object.assign({}, raw, { pass: await Store.decrypt(raw.passEnc), m3uUrl: await Store.decrypt(raw.m3uEnc) });
    S.hidden = await Store.get('hidden:' + raw.id, {});
    S.data = await Store.get('data:' + raw.id, null);
    if (!S.data) return showSync(raw.id);
    await enterAfterSub();
  }

  function onVisibility() {
    if (screen && screen.onVisibility) screen.onVisibility(document.hidden);
  }

  // ------------------------------------------------------------------ onboarding
  function showWelcome() {
    setScreen('welcome', function () {
      app.innerHTML = `<div class="welcome">
        <div class="logo xl"></div>
        <h1 class="brand">NOVA IPTV</h1>
        <p class="tagline">${esc(t('tagline'))}</p>
        <button data-f data-autofocus data-action="start" class="btn primary big">${esc(t('getStarted'))}</button>
      </div>`;
      actions.start = showLanguage;
      Nav.focusFirst();
    }, { back: exitApp });
  }

  function showLanguage() {
    setScreen('language', function () {
      const cur = I18n.lang;
      app.innerHTML = `<div class="center-col">
        <h1>${esc(t('chooseLanguage'))}</h1>
        <div class="lang-list">
          <button data-f data-action="lang" data-lang="ar" class="choice${cur === 'ar' ? ' selected' : ''}"${cur === 'ar' ? ' data-autofocus' : ''}>العربية</button>
          <button data-f data-action="lang" data-lang="en" class="choice${cur === 'en' ? ' selected' : ''}"${cur === 'en' ? ' data-autofocus' : ''}>English</button>
        </div>
        <button data-f data-action="next" class="btn primary big">${esc(t('continueLabel'))}</button>
      </div>`;
      actions.lang = async function (el) {
        S.settings.lang = el.getAttribute('data-lang');
        I18n.setLang(S.settings.lang);
        await saveSettings();
        showLanguage();
      };
      actions.next = async function () {
        S.settings.onboarded = true; await saveSettings();
        if (Cloud.googleAvailable() && !Cloud.account()) showGoogle(); else showAddSub();
      };
      Nav.focusFirst();
    }, { back: showWelcome });
  }

  async function showAddSub(editId) {
    const existing = editId ? S.subs.find(function (s) { return s.id === editId; }) : null;
    const form = existing ? {
      type: existing.type, name: existing.name, server: existing.server, user: existing.user,
      pass: await Store.decrypt(existing.passEnc), m3uUrl: await Store.decrypt(existing.m3uEnc),
      ua: existing.ua || '', liveFormat: existing.liveFormat || 'm3u8'
    } : { type: 'xtream', name: '', server: '', user: '', pass: '', m3uUrl: '', ua: '', liveFormat: 'm3u8' };

    function readForm() {
      ['name', 'server', 'user', 'pass', 'm3uUrl', 'ua'].forEach(function (k) {
        const el = document.getElementById('f-' + k);
        if (el) form[k] = el.value.trim();
      });
    }

    function render(message, isError) {
      setScreen('add', function () {
        const x = form.type === 'xtream';
        app.innerHTML = `<div class="form-page">
          <h1>${esc(t(existing ? 'editSubscription' : 'addSubscription'))}</h1>
          <div class="tabs" data-group>
            <button data-f data-action="type" data-type="xtream" class="tab${x ? ' active' : ''}">${esc(t('xtream'))}</button>
            <button data-f data-action="type" data-type="m3u" class="tab${!x ? ' active' : ''}">${esc(t('m3u'))}</button>
          </div>
          <label>${esc(t('name'))}<input data-f id="f-name" class="input" value="${esc(form.name)}"></label>
          ${x ? `
          <label>${esc(t('server'))}<input data-f id="f-server" class="input" dir="ltr" placeholder="http://example.com:8080" value="${esc(form.server)}"></label>
          <div class="row">
            <label class="grow">${esc(t('username'))}<input data-f id="f-user" class="input" dir="ltr" value="${esc(form.user)}"></label>
            <label class="grow">${esc(t('password'))}<input data-f id="f-pass" class="input" dir="ltr" type="password" value="${esc(form.pass)}"></label>
          </div>
          <div class="row"><span class="label">${esc(t('liveFormat'))}</span>
            <button data-f data-action="fmt" data-fmt="m3u8" class="chip${form.liveFormat !== 'ts' ? ' active' : ''}">HLS (m3u8)</button>
            <button data-f data-action="fmt" data-fmt="ts" class="chip${form.liveFormat === 'ts' ? ' active' : ''}">MPEG-TS</button>
          </div>` : `
          <label>${esc(t('m3uUrl'))}<input data-f id="f-m3uUrl" class="input" dir="ltr" placeholder="https://…/playlist.m3u8" value="${esc(form.m3uUrl)}"></label>`}
          <label>${esc(t('userAgent'))}<input data-f id="f-ua" class="input" dir="ltr" value="${esc(form.ua)}"></label>
          <div class="msg${isError ? ' error' : ''}">${message || ''}</div>
          <div class="row">
            <button data-f data-action="test" class="btn">${esc(t('test'))}</button>
            <button data-f data-autofocus data-action="save" class="btn primary">${esc(t('saveLoad'))}</button>
          </div>
          <p class="legal">${esc(t('legal'))}</p>
        </div>`;
        actions.type = function (el) { readForm(); form.type = el.getAttribute('data-type'); render(); };
        actions.fmt = function (el) { readForm(); form.liveFormat = el.getAttribute('data-fmt'); render(); };
        actions.test = function () { readForm(); run(false); };
        actions.save = function () { readForm(); run(true); };
        Nav.focusFirst();
      }, { back: function () { if (S.subs.length) { if (S.data) showHome(); else activate(S.settings.activeSub); } else showLanguage(); } });
    }

    async function run(save) {
      const x = form.type === 'xtream';
      const sub = {
        type: form.type, server: x ? Api.normalizeServer(form.server) : '', user: x ? form.user : '',
        pass: x ? form.pass : '', m3uUrl: x ? '' : form.m3uUrl
      };
      if (x ? !(sub.server && sub.user && sub.pass) : !/^https?:\/\//i.test(sub.m3uUrl)) {
        return render(esc(t('errInvalid')), true);
      }
      render(esc(t('testing')));
      try {
        let info = '';
        if (x) {
          const a = await Api.auth(sub);
          info = esc(t('connectionOk'));
          if (a.expires) info += ' · ' + esc(t('expiresOn', fmtDate(a.expires)));
          if (a.maxConn) info += ' · ' + esc(t('maxConn', a.maxConn));
        } else {
          const n = await Api.testM3u(sub.m3uUrl);
          info = esc(t('connectionOk')) + ' · ' + esc(t('items', n));
        }
        if (!save) return render(info);
        const record = existing || { id: uid() };
        Object.assign(record, {
          name: form.name || (x ? hostOf(sub.server) : hostOf(sub.m3uUrl)) || 'IPTV',
          type: form.type, server: sub.server, user: sub.user,
          passEnc: await Store.encrypt(sub.pass), m3uEnc: await Store.encrypt(sub.m3uUrl),
          ua: form.ua, liveFormat: form.liveFormat
        });
        record.updated = Date.now() / 1000;
        if (!existing) S.subs.push(record);
        await saveSubs();
        pushAccountSub(record, sub.pass, sub.m3uUrl);
        S.settings.activeSub = record.id;
        S.settings.onboarded = true;
        await saveSettings();
        S.sub = Object.assign({}, record, { pass: sub.pass, m3uUrl: sub.m3uUrl });
        S.hidden = await Store.get('hidden:' + record.id, {});
        showSync(record.id);
      } catch (e) {
        render(esc(errText(e)), true);
      }
    }
    render();
  }

  function hostOf(url) { const m = /^https?:\/\/([^/:?]+)/i.exec(url || ''); return m ? m[1] : ''; }

  // ------------------------------------------------------------------ sync
  function showSync(subId) {
    const raw = S.subs.find(function (s) { return s.id === subId; });
    let cancelled = false;
    setScreen('sync', function () {
      app.innerHTML = `<div class="center-col">
        <div class="logo"></div>
        <h2 id="sync-stage">${esc(t('stageConnecting'))}</h2>
        <div class="progress"><div id="sync-bar" style="width:3%"></div></div>
        <p id="sync-count" class="muted"></p>
        <div id="sync-error"></div>
      </div>`;
    }, { back: function () { cancelled = true; if (S.data) showHome(); else showAddSub(subId); } });

    const labels = { connecting: 'stageConnecting', content: 'stageContent', playlist: 'stagePlaylist', saving: 'stageSaving', done: 'stageDone' };
    function progress(stage, pct, count) {
      if (cancelled) return;
      $('#sync-stage').textContent = t(labels[stage] || 'stageContent');
      $('#sync-bar').style.width = pct + '%';
      $('#sync-count').textContent = count ? pct + '% · ' + t('items', count.toLocaleString()) : pct + '%';
    }

    (async function () {
      try {
        const sub = S.sub && S.sub.id === subId ? S.sub
          : Object.assign({}, raw, { pass: await Store.decrypt(raw.passEnc), m3uUrl: await Store.decrypt(raw.m3uEnc) });
        const r = sub.type === 'xtream' ? await Api.loadXtream(sub, progress) : await Api.loadM3u(sub, progress);
        if (cancelled) return;
        progress('saving', 90, r.live.length + r.movie.length + r.series.length);
        const data = { live: r.live, movie: r.movie, series: r.series, cats: r.cats };
        await Store.set('data:' + subId, data);
        raw.lastSync = Date.now();
        if (r.auth) { raw.expires = r.auth.expires; raw.maxConn = r.auth.maxConn; }
        await saveSubs();
        progress('done', 100, 0);
        S.sub = sub;
        S.data = data;
        S.settings.activeSub = subId;
        await saveSettings();
        setTimeout(function () { enterAfterSub(); }, 300);
      } catch (e) {
        if (cancelled) return;
        const hasCache = !!(await Store.get('data:' + subId, null));
        $('#sync-stage').textContent = t('syncFailed');
        $('#sync-error').innerHTML = `<p class="error">${esc(errText(e))}</p><div class="row center">
          <button data-f data-autofocus data-action="retry" class="btn primary">${esc(t('retry'))}</button>
          <button data-f data-action="edit" class="btn">${esc(t('editSubscription'))}</button>
          ${hasCache ? `<button data-f data-action="cached" class="btn">${esc(t('continueLabel'))}</button>` : ''}</div>`;
        actions.retry = function () { showSync(subId); };
        actions.edit = function () { showAddSub(subId); };
        actions.cached = function () { activate(subId); };
        Nav.focusFirst();
      }
    })();
  }

  // ------------------------------------------------------------------ content helpers
  function visibleCats(type) {
    const cats = (S.data && S.data.cats[type]) || [];
    return cats.filter(function (c) { return !S.hidden[type + '|' + c.id]; });
  }
  function itemsFor(type, cat) {
    const all = (S.data && S.data[type]) || [];
    if (cat === '*all') return all.filter(function (i) { return !S.hidden[type + '|' + i.cat]; });
    if (cat === '*fav') return S.fav.filter(function (f) { return f.sub === owner() && f.type === type; }).map(function (f) { return f.item; });
    if (cat === '*recent') {
      if (type === 'live') {
        return Object.keys(S.history).map(function (k) { return S.history[k]; })
          .filter(function (h) { return h.sub === owner() && h.type === 'live'; })
          .sort(function (a, b) { return b.t - a.t; }).slice(0, 50).map(function (h) { return h.item; });
      }
      return all.filter(function (i) { return !S.hidden[type + '|' + i.cat]; }).slice().sort(function (a, b) { return (b.added || 0) - (a.added || 0); }).slice(0, 200);
    }
    return all.filter(function (i) { return i.cat === cat; });
  }
  function isFav(type, id) { return S.fav.some(function (f) { return f.sub === owner() && f.type === type && f.id === id; }); }
  function toggleFav(type, item) {
    const i = S.fav.findIndex(function (f) { return f.sub === owner() && f.type === type && f.id === item.id; });
    let f;
    if (i >= 0) { f = S.fav.splice(i, 1)[0]; cloudPut(favPath(f), Cloud.Codec.fav(f, true)); }
    else { f = { sub: owner(), type: type, id: item.id, name: item.name, logo: item.logo, item: item }; S.fav.push(f); cloudPut(favPath(f), Cloud.Codec.fav(f, false)); }
    saveFav();
    return i < 0;
  }
  function hkey(type, id, ep) { return owner() + '|' + type + '|' + id + '|' + (ep || ''); }
  function continueList(type) {
    return Object.keys(S.history).map(function (k) { return S.history[k]; }).filter(function (h) {
      return h.sub === owner() && h.type === type && h.pos > 30000 && (!h.dur || h.pos < h.dur * 0.95);
    }).sort(function (a, b) { return b.t - a.t; }).filter(function (h, i, arr) {
      // one card per series: its most recent episode
      return arr.findIndex(function (x) { return x.type === h.type && x.id === h.id; }) === i;
    }).slice(0, 20);
  }

  /** Renders a long list incrementally: only `batch` items at a time, more when focus nears the end. */
  function windowed(container, list, renderItem, bind, batch, after) {
    batch = batch || 60;
    let rendered = 0;
    function more() {
      const end = Math.min(list.length, rendered + batch);
      if (end <= rendered) return;
      const frag = document.createElement('div');
      let html = '';
      for (let i = rendered; i < end; i++) html += renderItem(list[i], i);
      frag.innerHTML = html;
      const nodes = Array.prototype.slice.call(frag.children);
      nodes.forEach(function (n) {
        container.appendChild(n);
        const idx = parseInt(n.getAttribute('data-i'), 10);
        n.onfocusnav = function () { if (idx >= rendered - 12) more(); if (bind) bind(n, list[idx], idx); };
      });
      rendered = end;
      if (after) after(nodes);
    }
    more();
    return { more: more, get rendered() { return rendered; } };
  }

  // ------------------------------------------------------------------ home
  const SECTIONS = [
    { id: 'home', icon: '⌂' }, { id: 'live', icon: '📺' }, { id: 'movie', icon: '🎬', label: 'movies' }, { id: 'series', icon: '🎞', label: 'series' },
    { id: 'favorites', icon: '★' }, { id: 'search', icon: '🔍' }, { id: 'settings', icon: '⚙' }
  ];

  function showHome(section) {
    if (section) S.ui.section = section;
    if (S.data) Library.ensure(S.data);
    setScreen('home', function () {
      app.innerHTML = `<div class="home">
        <main id="content" class="content"></main>
        <div class="topscrim"></div>
        <nav class="topnav" data-group>
          <div class="logo small"></div>
          <div class="pill">${SECTIONS.map(function (s) {
            return `<button data-f data-action="section" data-sec="${s.id}" class="side-item${S.ui.section === s.id ? ' active' : ''}">
              <span class="ico">${s.icon}</span><span class="txt">${esc(t(s.label || s.id))}</span></button>`;
          }).join('')}</div>
          <button data-f data-action="profiles" class="profile-btn" title="${esc(t('switchProfile'))}">${esc((S.sub && S.sub.name || 'N').trim().charAt(0).toUpperCase())}</button>
        </nav>
      </div>`;
      actions.profiles = function () { showProfiles(); };
      actions.section = function (el) {
        const sec = el.getAttribute('data-sec');
        if (sec === S.ui.section) return;
        S.ui.section = sec;
        document.querySelectorAll('.side-item').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-sec') === sec); });
        renderSection(false);
      };
      renderSection(true);
    }, {
      back: function () {
        const inSidebar = Nav.current && Nav.current.closest('.topnav');
        if (!inSidebar) { Nav.focus($('.side-item.active')); return; }
        exitApp();
      }
    });
  }

  function renderSection(focusContent) {
    const c = $('#content');
    c.innerHTML = '';
    c.className = 'content sec-' + S.ui.section;
    const sec = S.ui.section;
    if (sec === 'home') renderDiscover(c);
    else if (sec === 'live') renderLive(c);
    else if (sec === 'movie' || sec === 'series') renderVod(c, sec);
    else if (sec === 'favorites') renderFavorites(c);
    else if (sec === 'search') renderSearch(c);
    else renderSettings(c);
    if (focusContent) {
      const key = S.ui.focusKey;
      const target = key ? c.querySelector('[data-key="' + cssEsc(key) + '"]') : null;
      if (target) Nav.focus(target); else if (!Nav.focusFirst(c)) Nav.focus($('.side-item.active'));
    }
  }

  function cssEsc(s) { return String(s).replace(/["\\]/g, '\\$&'); }

  function catColumn(type) {
    const specials = [['*all', t('all')], ['*fav', t('favorites')], ['*recent', type === 'live' ? t('recentWatched') : t('recentAdded')]];
    const sel = S.ui.cat[type];
    return specials.map(function (s) {
      return `<button data-f data-action="cat" data-cat="${esc(s[0])}" class="cat${sel === s[0] ? ' active' : ''}">${esc(s[1])}</button>`;
    }).join('') + visibleCats(type).map(function (c) {
      return `<button data-f data-action="cat" data-cat="${esc(c.id)}" class="cat${sel === c.id ? ' active' : ''}">${esc(c.name)}</button>`;
    }).join('');
  }

  // ---- live
  let epgTimer = null;
  function renderLive(c) {
    c.innerHTML = `<div class="live-layout">
      <div class="cat-col" data-group>${catColumn('live')}</div>
      <div class="chan-col" id="chan-list" data-group></div>
      <div class="preview" id="preview"></div>
    </div>`;
    actions.cat = function (el) {
      S.ui.cat.live = el.getAttribute('data-cat');
      document.querySelectorAll('.cat-col .cat').forEach(function (b) { b.classList.toggle('active', b === el); });
      fillChannels();
      const first = $('#chan-list [data-f]');
      if (first) Nav.focus(first);
    };
    actions.chan = function (el) { playLive(currentChannels[parseInt(el.getAttribute('data-i'), 10)], currentChannels); };
    actions.prevWatch = function () { if (previewItem) playLive(previewItem, currentChannels); };
    actions.prevFav = function (el) {
      if (!previewItem) return;
      const added = toggleFav('live', previewItem);
      el.textContent = added ? '★ ' + t('removeFav') : '☆ ' + t('addFav');
      const row = $('#chan-list [data-key="live:' + cssEsc(previewItem.id) + '"] .fav-mark');
      if (row) row.textContent = added ? '★' : '';
    };
    actions.prevGuide = function () { if (previewItem) showSchedule(previewItem); };
    fillChannels();
  }

  let currentChannels = [];
  let previewItem = null;
  function fillChannels() {
    const list = itemsFor('live', S.ui.cat.live);
    currentChannels = list;
    const box = $('#chan-list');
    box.innerHTML = '';
    if (!list.length) {
      box.innerHTML = `<div class="empty">${esc(t(S.ui.cat.live === '*fav' ? 'emptyFavorites' : 'emptyChannels'))}</div>`;
      $('#preview').innerHTML = '';
      return;
    }
    windowed(box, list, function (ch, i) {
      return `<button data-f data-i="${i}" data-key="live:${esc(ch.id)}" data-action="chan" class="chan">
        <span class="num">${ch.num || ''}</span>
        <span class="clogo">${img(ch.logo)}</span>
        <span class="cname">${esc(ch.name)}</span>
        ${ch.archive ? `<span class="badge">${esc(t('catchup'))}</span>` : ''}
        <span class="fav-mark">${isFav('live', ch.id) ? '★' : ''}</span>
      </button>`;
    }, function (el, ch) {
      S.ui.focusKey = 'live:' + ch.id;
      showPreview(ch);
    }, 80);
    showPreview(list[0]);
  }

  function showPreview(ch) {
    previewItem = ch;
    const p = $('#preview');
    if (!p) return;
    p.innerHTML = `<div class="plogo">${img(ch.logo)}</div>
      <h2>${esc(ch.name)}</h2>
      <div id="pv-epg" class="pv-epg muted">${S.sub.type === 'xtream' ? '…' : esc(t('noInfo'))}</div>
      <div class="pv-actions" data-group>
        <button data-f data-action="prevWatch" class="btn primary">▶ ${esc(t('watch'))}</button>
        ${S.sub.type === 'xtream' ? `<button data-f data-action="prevGuide" class="btn">${esc(t('guide'))}</button>` : ''}
        <button data-f data-action="prevFav" class="btn">${isFav('live', ch.id) ? '★ ' + esc(t('removeFav')) : '☆ ' + esc(t('addFav'))}</button>
      </div>`;
    clearTimeout(epgTimer);
    if (S.sub.type !== 'xtream') return;
    epgTimer = setTimeout(function () {
      Api.shortEpg(S.sub, ch.id).then(function (list) {
        if (previewItem !== ch) return;
        const box = $('#pv-epg');
        if (!box) return;
        const now = Date.now();
        const cur = list.find(function (x) { return x.start <= now && x.end > now; });
        const nxt = list.find(function (x) { return x.start > now; });
        if (!cur && !nxt) { box.textContent = t('noInfo'); return; }
        let html = '';
        if (cur) {
          const pct = Math.min(100, Math.max(0, (now - cur.start) / (cur.end - cur.start) * 100));
          html += `<div class="now"><b>${esc(t('now'))}</b> ${fmtTime(cur.start)}–${fmtTime(cur.end)}<div class="ptitle">${esc(cur.title)}</div>
            <div class="mini-progress"><div style="width:${pct}%"></div></div><p class="pdesc">${esc(cur.desc)}</p></div>`;
        }
        if (nxt) html += `<div class="next"><b>${esc(t('next'))}</b> ${fmtTime(nxt.start)} ${esc(nxt.title)}</div>`;
        box.innerHTML = html;
        box.classList.remove('muted');
      });
    }, 450);
  }

  // ---- movies / series
  function libState(type) {
    S.ui.lib = S.ui.lib || {};
    if (!S.ui.lib[type]) S.ui.lib[type] = { sel: '__recent', cat: null, genre: null };
    return S.ui.lib[type];
  }
  let genreJob = 0;

  /** Movies / Series organised like the big streaming apps: sections on the left (Netflix, Shahid, Turkish …),
      the provider's categories and genre filters on top, one card per title (best copy). */
  function renderVod(c, type) {
    const all = (S.data && S.data[type]) || [];
    if (!Library.ready()) {
      c.innerHTML = `<div class="empty"><div class="spinner"></div><p>${esc(t('organizing'))}</p></div>`;
      Library.ensure(S.data).then(function () { if (screen && screen.name === 'home' && S.ui.section === type) renderSection(false); });
      return;
    }
    const st = libState(type);
    const shelves = Library.shelves(type);
    if (st.sel.indexOf('__') !== 0 && !shelves.some(function (x) { return x.id === st.sel; })) { st.sel = '__recent'; st.cat = null; }
    const shelf = shelves.filter(function (x) { return x.id === st.sel; })[0];
    const hasCont = continueList(type).length > 0;
    function base() {
      if (st.sel === '__recent') return Library.collapse(type, latest(type, 300)).slice(0, 120);
      if (st.sel === '__cont') return continueList(type).map(function (h) { return h.item; });
      if (st.sel === '__fav') return S.fav.filter(function (f) { return f.sub === owner() && f.type === type; }).map(function (f) { return f.item; });
      return Library.browse(type, all.filter(function (m) { return !S.hidden[type + '|' + m.cat]; }), st.sel, st.cat);
    }
    const title = st.sel === '__recent' ? t('recentAdded') : st.sel === '__cont' ? t('continueWatching') : st.sel === '__fav' ? t('favorites')
      : (shelf ? shelf.title : '') + (st.cat ? ' · ' + Library.catName(type, st.cat) : '');
    function side(id, label, icon, count) {
      return `<button data-f data-action="libSel" data-id="${esc(id)}" class="cat${st.sel === id ? ' active' : ''}"><span class="ic">${esc(icon || '')}</span>${esc(label)}${count != null ? `<span class="cnt">${count}</span>` : ''}</button>`;
    }
    c.innerHTML = `<div class="lib">
      <div class="lib-side" data-group>
        ${side('__recent', t('recentAdded'), '✦')}${hasCont ? side('__cont', t('continueWatching'), '▶') : ''}${side('__fav', t('favorites'), '♥')}
        <div class="sec-title">${esc(t('sections'))}</div>
        ${shelves.map(function (x) { return side(x.id, x.title, x.icon, x.count); }).join('')}
      </div>
      <div class="lib-main">
        <div class="lib-head"><h2>${esc(title)}</h2><span class="cnt" id="lib-count"></span><span class="lprog" id="lib-prog"></span>
          ${st.cat || st.genre ? `<button data-f data-action="libReset" class="btn small">↺ ${esc(t('resetFilters'))}</button>` : ''}</div>
        ${shelf && shelf.cats.length > 1 ? `<div class="chip-row" data-group>
          <button data-f data-action="libCat" data-id="" class="chip${!st.cat ? ' on' : ''}">${esc(t('all'))}</button>
          ${shelf.cats.map(function (k) { return `<button data-f data-action="libCat" data-id="${esc(k.id)}" class="chip${st.cat === k.id ? ' on' : ''}">${esc(k.name)}</button>`; }).join('')}
        </div>` : ''}
        ${Tmdb.enabled() ? `<div class="chip-row" data-group>
          <button data-f data-action="libGenre" data-id="" class="chip${!st.genre ? ' on' : ''}">☰ ${esc(t('allGenres'))}</button>
          ${Library.genres(type).map(function (g) { return `<button data-f data-action="libGenre" data-id="${g}" class="chip${st.genre === g ? ' on' : ''}">${esc(t('g_' + g))}</button>`; }).join('')}
        </div>` : `<div class="lib-hint">${esc(t('genresNeedTMDB'))}</div>`}
        <div class="vod-scroll" id="vod-scroll"><div class="grid" id="vod-grid" data-group></div></div>
      </div>
    </div>`;
    function refocus(sel) { const el = $(sel); if (el) Nav.focus(el); }
    actions.libSel = function (el) { st.sel = el.getAttribute('data-id'); st.cat = null; renderSection(false); refocus('.lib-side .cat.active'); };
    actions.libCat = function (el) { st.cat = el.getAttribute('data-id') || null; renderSection(false); refocus('.chip-row .chip.on'); };
    actions.libGenre = function (el) { st.genre = el.getAttribute('data-id') || null; renderSection(false); refocus('[data-action="libGenre"].on'); };
    actions.libReset = function () { st.cat = null; st.genre = null; renderSection(false); refocus('.lib-side .cat.active'); };
    actions.open = function (el) {
      const item = shown[parseInt(el.getAttribute('data-i'), 10)];
      if (!item) return;
      S.ui.focusKey = type + ':' + item.id;
      openTitle(type, item);
    };
    let shown = [];
    function draw(list) {
      shown = list;
      $('#lib-count').textContent = String(list.length);
      const grid = $('#vod-grid');
      if (!grid) return;
      const keep = Nav.current && grid.contains(Nav.current) ? Nav.current.getAttribute('data-key') : null;
      grid.innerHTML = '';
      if (!list.length) { grid.innerHTML = `<div class="empty">${esc(t(st.sel === '__fav' ? 'emptyFavorites' : 'emptyContent'))}</div>`; return; }
      const w = windowed(grid, list, function (it, i) {
        return `<button data-f data-i="${i}" data-key="${type}:${esc(it.id)}" data-action="open" class="poster">
          <span class="pimg">${img(it.logo)}${isFav(type, it.id) ? '<span class="star">★</span>' : ''}</span>
          <span class="ptitle">${esc(Clean.title(it.name))}</span>
          <span class="psub">${esc([it.year, it.rating ? '★ ' + it.rating : ''].filter(Boolean).join('  '))}</span>
        </button>`;
      }, function (el, it) { S.ui.focusKey = type + ':' + it.id; }, 48, function (nodes) {
        nodes.forEach(function (n) { localize(n, type, list[parseInt(n.getAttribute('data-i'), 10)]); });
      });
      if (keep) { const again = grid.querySelector('[data-key="' + cssEsc(keep) + '"]'); if (again) Nav.focus(again, { noScroll: true }); }
      else if (S.ui.focusKey && S.ui.focusKey.indexOf(type + ':') === 0) {
        // coming back from a title: make sure its card exists so the remote lands on it again
        const want = S.ui.focusKey.slice(type.length + 1);
        let at = list.findIndex(function (m) { return String(m.id) === want; });
        if (at < 0) {
          // the card may now show another copy of the same title (copies merged after TMDB matched them)
          const orig = all.filter(function (m) { return String(m.id) === want; })[0];
          const g = orig && Library.groupKey(type, orig);
          if (g) at = list.findIndex(function (m) { return Library.groupKey(type, m) === g; });
          if (at >= 0) S.ui.focusKey = type + ':' + list[at].id;
        }
        while (at >= 0 && w.rendered <= at && w.rendered < list.length) w.more();
      }
    }
    const list = base();
    const job = ++genreJob;
    if (!st.genre) {
      draw(list);
      // let TMDB identify titles in the background so copies with different names merge next time
      if (Tmdb.enabled() && st.sel.indexOf('__') !== 0) list.slice(0, 400).forEach(function (m) { Tmdb.card(type, m.name, m.year, true); });
      return;
    }
    // genre: categories named after the genre count at once; the rest are checked with TMDB (cached afterwards)
    const wanted = Library.genreIds(st.genre, type);
    const out = [];
    let done = 0;
    draw([]);
    $('#lib-prog').textContent = t('classifying', 0, list.length);
    (async function () {
      for (let i = 0; i < list.length; i += 12) {
        if (job !== genreJob || !$('#vod-grid')) return;
        const chunk = list.slice(i, i + 12);
        const res = await Promise.all(chunk.map(function (m) {
          if (Library.genreInCategory(st.genre, Library.catName(type, m.cat))) return Promise.resolve(true);
          return Tmdb.card(type, m.name, m.year).then(function (r) {
            return !!(r && r.g && r.g.some(function (g) { return wanted.indexOf(g) >= 0; }));
          }, function () { return false; });
        }));
        if (job !== genreJob || !$('#vod-grid')) return;
        res.forEach(function (ok, j) { if (ok) out.push(chunk[j]); });
        done = Math.min(list.length, i + 12);
        $('#lib-prog').textContent = done < list.length ? t('classifying', done, list.length) : '';
        // refresh the grid unless the user is moving inside it (it updates once they leave)
        if (!(Nav.current && $('#vod-grid').contains(Nav.current)) || done >= list.length) draw(out.slice());
      }
    })();
  }

  // ---- detail history: a title opened from an actor's page goes back to the title you came from
  let detailStack = [], curDetail = null;
  function detailBack() {
    const prev = detailStack.pop();
    if (prev) { if (prev.type === 'movie') showMovie(prev.item, true); else showSeries(prev.item, null, true); }
    else showHome();
  }
  /** Identity of the focused control, so a re-render can put focus back on the same thing. */
  function focusId(el) {
    if (!el) return null;
    const a = el.getAttribute('data-action'); if (!a) return null;
    return a + '|' + (el.getAttribute('data-i') || el.getAttribute('data-s') || el.getAttribute('data-key') || '');
  }
  function refocusId(id) {
    if (!id) return false;
    const parts = id.split('|'), a = parts[0], v = parts.slice(1).join('|');
    const els = document.querySelectorAll('[data-action="' + cssEsc(a) + '"]');
    for (let i = 0; i < els.length; i++) {
      const e = els[i];
      if ((e.getAttribute('data-i') || e.getAttribute('data-s') || e.getAttribute('data-key') || '') === v) { Nav.focus(e); return true; }
    }
    if (els[0]) { Nav.focus(els[0]); return true; }
    return false;
  }

  /** Opens a title; when the library has several copies, asks which one every time (Arabic copies first, preselected). */
  async function openTitle(type, item, beforeShow) {
    const all = (S.data && S.data[type]) || [];
    const copies = Library.ordered(type, Library.variants(type, all, item));
    let pick = item;
    if (copies.length > 1) {
      const v = await chooseBox(t('chooseVersion'), copies.map(function (m, i) { return { value: i, label: Library.label(type, m) || m.name }; }), 0);
      if (v === undefined) return;
      pick = copies[v];
    }
    if (beforeShow) beforeShow();
    else if (!(Nav.current && Nav.current.closest('.detail'))) detailStack = [];   // opened from a list: fresh history
    if (type === 'movie') showMovie(pick, true); else showSeries(pick, null, true);
  }

  /** Cast member: photo, role, biography and best-known titles (those in the subscription open directly). */
  async function showPerson(p) {
    const md = modal(`<div class="person-sheet"><div class="pp">${p.photo ? `<img src="${esc(p.photo)}">` : ''}</div>
      <div><h2>${esc(p.name)}</h2><p class="muted">${esc(p.role || '')}</p><div id="pbio"><div class="spinner"></div></div></div></div>
      <div id="pknown"></div>`, function () {}, {});
    const info = await Tmdb.person(p.id);
    if (!document.body.contains(md.el)) return;
    const bio = $('#pbio', md.el), known = $('#pknown', md.el);
    bio.innerHTML = info && info.bio ? `<button data-f data-m="bio" class="set-row plot" style="white-space:normal;text-align:start">${esc(info.bio.slice(0, 700))}${info.bio.length > 700 ? '…' : ''}</button>` : '';
    const works = (info && info.known) || [];
    function find(w) {
      const type = w.tv ? 'series' : 'movie';
      const item = Library.ready() ? Library.find(type, w) : null;
      return item ? { type: type, item: item } : null;
    }
    const found = works.map(find);
    known.innerHTML = works.length ? `<h3>${esc(t('knownFor'))}</h3><div class="known-row" data-group>${works.map(function (w, i) {
      return `<button data-f data-m="w${i}" class="poster${found[i] ? '' : ' na'}"><span class="pimg"><img src="${esc(w.poster)}">${found[i] ? '<span class="rate">▶</span>' : ''}</span><span class="ptitle">${esc(w.title)}</span></button>`;
    }).join('')}</div>` : '';
    md.el.onclick = null;
    md.el.addEventListener('click', function (e) {
      const el = e.target.closest('[data-m]'); if (!el) return;
      const m = el.getAttribute('data-m');
      if (m === 'bio') { el.textContent = info.bio; return; }
      const i = parseInt(m.slice(1), 10), f = found[i];
      if (!f) { toast(t('notInSub', works[i].title)); return; }
      md.close();
      const from = curDetail;
      openTitle(f.type, f.item, function () { if (from) detailStack.push(from); });
    });
    Nav.focusFirst(md.el);
  }

  function resumeHistory(h) {
    if (h.type === 'movie') playVod({ kind: 'movie', item: h.item, title: h.title, logo: h.logo });
    else playVod({ kind: 'series', item: h.item, title: h.title, logo: h.logo, episode: { id: h.ep, ext: h.ext }, subtitle: h.subtitle });
  }

  async function showMovie(item, fromStack) {
    if (!fromStack) detailStack = [];
    curDetail = { type: 'movie', item: item };
    setScreen('detail', function () {
      app.innerHTML = detailHtml(item, null);
      bindMovie(item, null);
    }, { back: detailBack });
    let info = await Api.vodInfo(S.sub, item.id);
    function redraw() {
      if (screen.name !== 'detail') return;
      const fid = focusId(Nav.current), scroller = $('.detail'), top = scroller ? scroller.scrollTop : 0;
      app.innerHTML = detailHtml(item, info);
      bindMovie(item, info);
      const sc = $('.detail'); if (sc) sc.scrollTop = top;
      if (!refocusId(fid)) Nav.focusFirst();
    }
    if (info) redraw();
    const tm = await Tmdb.details('movie', info && info.tmdb, item.name, item.year);
    if (tm) { info = Object.assign({}, info || {}, { tm: tm }); redraw(); }
    // other parts of the series (Fast & Furious 1, 2, 3…) — the ones you have open straight away
    if (tm && tm.collection) {
      const col = await Tmdb.collection(tm.collection.id);
      if (col && col.parts.length > 1 && screen.name === 'detail' && curDetail && curDetail.item === item) {
        col.parts.forEach(function (w) { w.found = Library.ready() ? Library.find('movie', w) : null; });
        info = Object.assign({}, info, { parts: col });
        redraw();
      }
    }
  }

  function detailHtml(item, info) {
    const h = S.history[hkey('movie', item.id)];
    const canResume = h && h.pos > 30000 && (!h.dur || h.pos < h.dur * 0.95);
    const tm = info && info.tm;
    const meta = [(tm && tm.year) || item.year || (info && info.year), tm && tm.runtime ? t('minutes', tm.runtime) : info && info.duration,
      !tm && info && info.genre].filter(Boolean).join(' • ');
    const backdrop = (tm && tm.backdrop) || (info && info.backdrop) || '';
    const rating = (tm && tm.rating) || item.rating || (info && info.rating);
    return `<div class="detail">
      ${backdrop ? `<div class="backdrop" style="background-image:url('${esc(backdrop)}')"></div>` : ''}
      <div class="detail-body">
        <div class="dposter">${img((tm && tm.poster) || (info && info.image) || item.logo)}</div>
        <div class="dinfo">
          ${titleBlock(tm, item.name)}
          <div class="meta">${rating ? `<span class="badge g">★ ${esc(rating)}</span>` : ''}${tm && tm.votes ? `<span class="muted small">TMDB · ${esc(t('votes', tm.votes))}</span>` : ''} ${esc(meta)}</div>
          ${tm && tm.genres.length ? `<div class="genres">${tm.genres.slice(0, 4).map(function (g) { return `<span class="chip">${esc(g)}</span>`; }).join('')}</div>` : ''}
          <div class="row" data-group>
            <button data-f data-autofocus data-action="play" class="btn primary">▶ ${esc(t(canResume ? 'resume' : 'play'))}</button>
            ${canResume ? `<button data-f data-action="restart" class="btn">↺ ${esc(t('startOver'))}</button>` : ''}
            <button data-f data-action="trailer" class="btn">🎬 ${esc(t('trailer'))}</button>
            <button data-f data-action="fav" class="btn">${isFav('movie', item.id) ? '★ ' + esc(t('removeFav')) : '☆ ' + esc(t('addFav'))}</button>
            ${versionCount('movie', item) > 1 ? `<button data-f data-action="versions" class="btn vbtn">⧉ ${esc(Library.label('movie', item))}</button>` : ''}
          </div>
          ${canResume && h.dur ? `<div class="mini-progress wide"><div style="width:${Math.round(h.pos / h.dur * 100)}%"></div></div>` : ''}
          ${tm && tm.tagline ? `<p class="tagline-q">“${esc(tm.tagline)}”</p>` : ''}
          <p class="plot">${esc((tm && tm.overview) || (info && info.plot) || '')}</p>
          ${tm && tm.cast.length ? castRow(tm.cast) : info && info.cast ? `<p class="muted">${esc(info.cast)}</p>` : ''}
          ${(tm && tm.creators.length) || (info && info.director) ? `<p class="muted">${esc(t('director'))}: ${esc((tm && tm.creators.join('، ')) || info.director)}</p>` : ''}
        </div>
      </div>
      ${partsRow(info && info.parts, tm && tm.id)}</div>`;
  }

  /** Every part of the film series, in order; the ones in the subscription open directly. */
  function partsRow(col, currentId) {
    if (!col || !col.parts || col.parts.length < 2) return '';
    return `<section class="parts"><h3>${esc(t('partsOf', col.name || ''))}</h3><div class="known-row" data-group>${col.parts.map(function (w, i) {
      const here = w.id === currentId, ok = !!w.found;
      return `<button data-f data-action="part" data-i="${i}" class="poster${ok || here ? '' : ' na'}${here ? ' current' : ''}">
        <span class="pimg"><img src="${esc(w.poster)}">${here ? `<span class="rate">${esc(t('nowShowing'))}</span>` : ok ? '<span class="rate">▶</span>' : ''}</span>
        <span class="ptitle">${esc(w.title)}</span><span class="psub">${esc(w.year)}</span></button>`;
    }).join('')}</div></section>`;
  }

  function versionCount(type, item) {
    return Library.ready() ? Library.variants(type, (S.data && S.data[type]) || [], item).length : 1;
  }

  function titleBlock(tm, name) {
    const title = (tm && tm.title) || Clean.title(name);
    if (tm && tm.logo) return `<div class="tlogo"><img src="${esc(tm.logo)}" onerror="this.parentNode.outerHTML='<h1>${esc(title).replace(/'/g, '&#39;')}</h1>'"></div><div class="muted">${esc(title)}</div>`;
    return `<h1>${esc(title)}</h1>${tm && tm.original && tm.original !== title ? `<div class="muted">${esc(tm.original)}</div>` : ''}`;
  }

  function castRow(cast) {
    return `<div class="cast-row" data-group>${cast.slice(0, 12).map(function (p, i) {
      return `<button data-f data-action="person" data-i="${i}" class="person"><div class="pphoto">${p.photo ? `<img src="${esc(p.photo)}">` : '👤'}</div><b>${esc(p.name)}</b><span class="muted">${esc(p.role)}</span></button>`;
    }).join('')}</div>`;
  }

  function bindMovie(item, info) {
    actions.part = function (el) {
      const w = info && info.parts && info.parts.parts[parseInt(el.getAttribute('data-i'), 10)];
      if (!w || (info.tm && w.id === info.tm.id)) return;
      if (!w.found) { toast(t('notInSub', w.title)); return; }
      const from = curDetail;
      openTitle('movie', w.found, function () { if (from) detailStack.push(from); });
    };
    actions.person = function (el) { const c = info && info.tm && info.tm.cast[parseInt(el.getAttribute('data-i'), 10)]; if (c) showPerson(c); };
    actions.versions = function () { openTitle('movie', item); };
    actions.play = function () { playVod({ kind: 'movie', item: item, title: item.name, logo: (info && info.image) || item.logo }); };
    actions.restart = function () { playVod({ kind: 'movie', item: item, title: item.name, logo: item.logo, fromStart: true }); };
    actions.trailer = function () { showTrailer(info && info.tm, item, 0); };
    actions.fav = function (el) {
      const added = toggleFav('movie', item);
      el.textContent = added ? '★ ' + t('removeFav') : '☆ ' + t('addFav');
    };
    Nav.focusFirst();
  }

  async function showSeries(item, keepSeason, fromStack) {
    if (!fromStack) detailStack = [];
    curDetail = { type: 'series', item: item };
    let firstRender = true, autoFid = null;
    let info = null, season = keepSeason || null, error = null, tm = null, tmEps = {}, tmSeason = null;
    function render() {
      // until the viewer moves, keep landing on the main action (Play / Continue / first episode) as data arrives
      const fid = firstRender || focusId(Nav.current) === autoFid ? null : focusId(Nav.current);
      const oldScroll = !firstRender && $('.detail') ? $('.detail').scrollTop : 0;
      setScreen('detail', function () {
        const seasons = info ? Object.keys(info.seasons).map(Number).sort(function (a, b) { return a - b; }) : [];
        if (info && (season == null || seasons.indexOf(season) < 0)) season = seasons[0];
        const eps = info && season != null ? info.seasons[season] : [];
        const last = Object.keys(S.history).map(function (k) { return S.history[k]; })
          .filter(function (h) { return h.sub === owner() && h.type === 'series' && h.id === item.id; })
          .sort(function (a, b) { return b.t - a.t; })[0];
        if (tm && season != null && tmSeason !== season) {
          tmSeason = season;
          Tmdb.season(tm.id, season).then(function (m) { tmEps = m; if (screen.name === 'detail' && tmSeason === season) render(); });
        }
        const bd = (tm && tm.backdrop) || (info && info.backdrop) || '';
        const rating = (tm && tm.rating) || item.rating || (info && info.rating);
        app.innerHTML = `<div class="detail series">
          ${bd ? `<div class="backdrop" style="background-image:url('${esc(bd)}')"></div>` : ''}
          <div class="detail-body">
            <div class="dposter">${img((tm && tm.poster) || (info && info.cover) || item.logo)}</div>
            <div class="dinfo">
              ${titleBlock(tm, item.name)}
              <div class="meta">${rating ? `<span class="badge g">★ ${esc(rating)}</span>` : ''} ${esc([(tm && tm.year) || item.year || (info && info.year),
                tm && tm.seasons ? t('seasonsCount', tm.seasons) : '', !tm && info && info.genre].filter(Boolean).join(' • '))}</div>
              ${tm && tm.genres.length ? `<div class="genres">${tm.genres.slice(0, 4).map(function (g) { return `<span class="chip">${esc(g)}</span>`; }).join('')}</div>` : ''}
              <div class="row" data-group>
                ${last ? `<button data-f data-autofocus data-action="cont" class="btn primary">▶ ${esc(t('resume'))} ${esc((last.subtitle || '').split(' —')[0])}</button>` : ''}
                <button data-f data-action="trailer" class="btn">🎬 ${esc(t('trailer'))}</button>
                <button data-f data-action="fav" class="btn">${isFav('series', item.id) ? '★ ' + esc(t('removeFav')) : '☆ ' + esc(t('addFav'))}</button>
                ${versionCount('series', item) > 1 ? `<button data-f data-action="versions" class="btn vbtn">⧉ ${esc(Library.label('series', item))}</button>` : ''}
              </div>
              <p class="plot short">${esc((tm && tm.overview) || (info && info.plot) || item.plot || '')}</p>
              ${tm && tm.cast.length ? castRow(tm.cast) : ''}
            </div>
          </div>
          ${!info && !error ? '<div class="spinner"></div>' : ''}
          ${error ? `<p class="error">${esc(errText(error))}</p><button data-f data-action="reload" class="btn">${esc(t('retry'))}</button>` : ''}
          ${info ? `<div class="season-row" data-group>${seasons.map(function (s) {
            return `<button data-f data-action="season" data-s="${s}" class="chip${s === season ? ' active' : ''}">${esc(t('season', s))}</button>`;
          }).join('')}</div>
          <div class="episodes" data-group>${eps.length ? eps.map(function (e, i) {
            const h = S.history[hkey('series', item.id, e.id)];
            const pct = h && h.dur ? Math.round(h.pos / h.dur * 100) : 0;
            const te = tmEps[e.num] || {};
            return `<button data-f data-action="ep" data-i="${i}" class="episode"${!last && i === 0 ? ' data-autofocus' : ''}>
              <span class="eimg">${img(te.still || e.image || item.logo)}${pct ? `<span class="bar"><span style="width:${pct}%"></span></span>` : ''}</span>
              <span class="etxt"><b>${e.num}. ${esc(te.name || e.title || t('episode', e.num))}</b>
              <span class="muted">${e.duration ? esc(t('minutes', Math.round(e.duration / 60))) : ''}</span>
              <span class="edesc">${esc(te.overview || e.plot)}</span></span></button>`;
          }).join('') : `<div class="empty">${esc(t('noEpisodes'))}</div>`}</div>` : ''}
        </div>`;
        actions.season = function (el) { season = parseInt(el.getAttribute('data-s'), 10); render(); const s = $('.season-row .active'); if (s) Nav.focus(s); };
        actions.ep = function (el) {
          const e = eps[parseInt(el.getAttribute('data-i'), 10)];
          playVod({
            kind: 'series', item: item, title: item.name, logo: (info && info.cover) || item.logo,
            episode: { id: e.id, ext: e.ext }, subtitle: 'S' + e.season + ' E' + e.num + (e.title ? ' — ' + e.title : ''),
            returnTo: function () { showSeries(item, season); }
          });
        };
        actions.cont = function () { if (last) resumeHistory(Object.assign({}, last, { item: item })); };
        actions.fav = function (el) {
          const added = toggleFav('series', item);
          el.textContent = added ? '★ ' + t('removeFav') : '☆ ' + t('addFav');
        };
        actions.reload = load;
        actions.trailer = async function () {
          // several seasons: ask which season's trailer
          const max = Math.min(30, Math.max((tm && tm.seasons) || 0, seasons.length ? seasons[seasons.length - 1] : 0));
          if (max < 2) { showTrailer(tm, item, 0); return; }
          const list = []; for (let n = 1; n <= max; n++) list.push({ value: n, label: t('season', n) });
          const n = await chooseBox(t('trailerSeason'), list, season || max);
          if (n !== undefined) showSeasonTrailer(tm, item, n);
        };
        actions.person = function (el) { const c = tm && tm.cast[parseInt(el.getAttribute('data-i'), 10)]; if (c) showPerson(c); };
        actions.versions = function () { openTitle('series', item); };
        // keep the remote where it was when the page refreshes with more data (TMDB, episodes)
        const sc = $('.detail'); if (sc && oldScroll) sc.scrollTop = oldScroll;
        if (!refocusId(fid)) { Nav.focusFirst(); autoFid = focusId(Nav.current); }
        firstRender = false;
      }, { back: detailBack });
    }
    function load() {
      error = null; info = null; render();
      if (S.sub.type !== 'xtream') { error = { kind: 'unknown' }; render(); return; }
      Api.seriesInfo(S.sub, item.id).then(function (i) {
        info = i;
        if (screen.name === 'detail') render();
        Tmdb.details('series', i.tmdb, item.name, item.year).then(function (x) { if (x) { tm = x; if (screen.name === 'detail') render(); } });
      },
        function (e) { error = e; if (screen.name === 'detail') render(); });
    }
    load();
  }

  // ---- schedule (catch-up)
  function showSchedule(ch) {
    setScreen('schedule', function () {
      app.innerHTML = `<div class="schedule"><h1>${esc(ch.name)} · ${esc(t('guide'))}</h1><div id="sched" class="sched-list" data-group><div class="spinner"></div></div></div>`;
    }, { back: function () { showHome(); } });
    Api.dayEpg(S.sub, ch.id).then(function (list) {
      const box = $('#sched');
      if (!box) return;
      const now = Date.now();
      if (!list.length) { box.innerHTML = `<div class="empty">${esc(t('noInfo'))}</div>`; Nav.focusFirst(); return; }
      let focusIdx = 0;
      box.innerHTML = list.map(function (p, i) {
        const live = p.start <= now && p.end > now;
        if (live) focusIdx = i;
        const past = p.end <= now;
        const playable = live || (past && ch.archive && p.archive);
        return `<button data-f data-i="${i}" data-action="prog" class="prog${live ? ' live' : ''}${past ? ' past' : ''}"${playable ? '' : ' data-np="1"'}>
          <span class="ptime">${fmtTime(p.start)}</span><span class="pt"><b>${esc(p.title)}</b><span class="muted">${esc(p.desc)}</span></span>
          ${live ? `<span class="badge red">${esc(t('liveBadge'))}</span>` : past && ch.archive && p.archive ? `<span class="badge">${esc(t('catchup'))}</span>` : ''}</button>`;
      }).join('');
      actions.prog = function (el) {
        if (el.getAttribute('data-np')) return;
        const p = list[parseInt(el.getAttribute('data-i'), 10)];
        if (p.start <= Date.now() && p.end > Date.now()) playLive(ch, currentChannels);
        else playVod({ kind: 'catchup', item: ch, title: ch.name, subtitle: p.title, logo: ch.logo,
          catchup: { start: p.start, duration: Math.max(1, Math.round((p.end - p.start) / 60000)) },
          returnTo: function () { showSchedule(ch); } });
      };
      const items = box.querySelectorAll('[data-f]');
      Nav.focus(items[focusIdx]);
    }, function (e) {
      const box = $('#sched');
      if (box) box.innerHTML = `<p class="error">${esc(errText(e))}</p>`;
    });
  }

  // ---- favorites
  function renderFavorites(c) {
    const mine = S.fav.filter(function (f) { return f.sub === owner(); });
    if (!mine.length) { c.innerHTML = `<div class="empty">${esc(t('emptyFavorites'))}</div>`; return; }
    const groups = [['live', t('live')], ['movie', t('movies')], ['series', t('series')]];
    c.innerHTML = groups.map(function (g) {
      const list = mine.filter(function (f) { return f.type === g[0]; });
      if (!list.length) return '';
      return `<h3 class="row-title">${esc(g[1])}</h3><div class="grid small" data-group>` + list.map(function (f) {
        return `<button data-f data-action="openFav" data-type="${g[0]}" data-id="${esc(f.id)}" data-key="fav:${g[0]}:${esc(f.id)}" class="poster${g[0] === 'live' ? ' channel' : ''}">
          <span class="pimg">${img(f.logo)}</span><span class="ptitle">${esc(f.name)}</span></button>`;
      }).join('') + '</div>';
    }).join('');
    actions.openFav = function (el) {
      const type = el.getAttribute('data-type'), id = el.getAttribute('data-id');
      const f = mine.find(function (x) { return x.type === type && x.id === id; });
      S.ui.focusKey = 'fav:' + type + ':' + id;
      if (type === 'live') playLive(f.item, mine.filter(function (x) { return x.type === 'live'; }).map(function (x) { return x.item; }));
      else if (type === 'movie') showMovie(f.item);
      else showSeries(f.item);
    };
  }

  // ---- search
  function renderSearch(c) {
    const st = S.ui.search;
    const types = [['', t('all')], ['live', t('live')], ['movie', t('movies')], ['series', t('series')]];
    c.innerHTML = `<div class="search-page">
      <input data-f id="q" class="input big" placeholder="${esc(t('searchHint'))}" value="${esc(st.q)}">
      <div class="row" data-group>${types.map(function (ty) {
        return `<button data-f data-action="stype" data-t="${ty[0]}" class="chip${st.type === ty[0] ? ' active' : ''}">${esc(ty[1])}</button>`;
      }).join('')}</div>
      <div id="results" class="grid small" data-group></div></div>`;
    const input = $('#q');
    let timer = null;
    input.addEventListener('input', function () {
      st.q = input.value;
      clearTimeout(timer);
      timer = setTimeout(runSearch, 300);
    });
    input.addEventListener('keydown', function (e) { if (e.keyCode === 13 || e.keyCode === 65376) { input.blur(); runSearch(); } });
    actions.stype = function (el) {
      st.type = el.getAttribute('data-t');
      document.querySelectorAll('.search-page .chip').forEach(function (b) { b.classList.toggle('active', b === el); });
      runSearch();
    };
    runSearch();
  }

  /** Search text that ignores Arabic letter variants and diacritics (أ/ا, ة/ه, ى/ي, tashkeel). */
  function searchNorm(x) {
    return String(x || '').toLowerCase().replace(/[\u064B-\u0652\u0640]/g, '').replace(/[أإآ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي');
  }
  let searchToken = 0;
  function runSearch() {
    const st = S.ui.search, box = $('#results');
    if (!box) return;
    const raw = st.q.trim(), q = searchNorm(raw);
    const token = ++searchToken;
    if (q.length < 2) { box.innerHTML = `<div class="empty">${esc(t('searchPrompt'))}</div>`; return; }
    const out = [], seen = {};
    ['live', 'movie', 'series'].forEach(function (type) {
      if (st.type && st.type !== type) return;
      const all = (S.data && S.data[type]) || [];
      for (let i = 0; i < all.length && out.length < 300; i++) {
        const it = all[i];
        if (S.hidden[type + '|' + it.cat]) continue;
        if (searchNorm(it.name).indexOf(q) >= 0) { out.push({ type: type, item: it }); seen[type + '|' + it.id] = 1; }
      }
    });
    out.sort(function (a, b) {
      const ai = searchNorm(a.item.name).indexOf(q) === 0 ? 0 : 1, bi = searchNorm(b.item.name).indexOf(q) === 0 ? 0 : 1;
      return ai - bi;
    });
    function draw() {
      if (!out.length) { box.innerHTML = `<div class="empty">${esc(t('noResults'))}</div>`; return; }
      box.innerHTML = out.map(function (r, i) {
        return `<button data-f data-i="${i}" data-action="openResult" class="poster${r.type === 'live' ? ' channel' : ''}">
          <span class="pimg">${img(r.poster || r.item.logo)}</span><span class="ptitle">${esc(r.label || r.item.name)}</span>
          <span class="psub">${esc(r.type === 'live' ? t('live') : r.type === 'movie' ? t('movies') : t('series'))}</span></button>`;
      }).join('');
    }
    draw();
    actions.openResult = function (el) {
      const r = out[parseInt(el.getAttribute('data-i'), 10)];
      if (r.type === 'live') playLive(r.item, [r.item]);
      else openTitle(r.type, r.item);
    };
    // titles known by another name: ask TMDB (Arabic / English / original names) and show the ones in the subscription
    if (st.type === 'live' || !Library.ready() || !(window.Tmdb && Tmdb.enabled && Tmdb.enabled())) return;
    Tmdb.searchAll(raw).then(function (hits) {
      if (token !== searchToken || !$('#results')) return;
      let added = 0;
      hits.forEach(function (w) {
        if (st.type && st.type !== w.type) return;
        const m = Library.find(w.type, w);
        if (!m || seen[w.type + '|' + m.id]) return;
        seen[w.type + '|' + m.id] = 1; added++;
        out.unshift({ type: w.type, item: m, label: w.title || m.name, poster: w.poster });
      });
      if (!added) return;
      const had = focusId(Nav.current);
      draw();
      if (had) refocusId(had);
    });
  }

  // ---- settings
  function renderSettings(c) {
    c.innerHTML = `<div class="settings">
      <h3 class="row-title">${esc(t('subscriptions'))}</h3>
      ${S.subs.map(function (s) {
        const act = s.id === S.sub.id;
        return `<div class="set-sub${act ? ' active' : ''}" data-group>
          <div class="sname"><div><b>${esc(s.name)}</b> ${act ? `<span class="badge g">${esc(t('active'))}</span>` : ''}</div>
            <span class="muted">${esc(s.type === 'xtream' ? t('xtream') : t('m3u'))}${s.lastSync ? ' · ' + esc(t('lastUpdate', fmtDate(s.lastSync))) : ''}${s.expires ? ' · ' + esc(t('expiresOn', fmtDate(s.expires))) : ''}</span></div>
          ${act ? '' : `<button data-f data-action="subUse" data-id="${s.id}" class="btn small">${esc(t('active'))}</button>`}
          <button data-f data-action="subRefresh" data-id="${s.id}" class="btn small">${esc(t('refresh'))}</button>
          <button data-f data-action="subEdit" data-id="${s.id}" class="btn small">${esc(t('editSubscription'))}</button>
          <button data-f data-action="subDel" data-id="${s.id}" class="btn small danger">${esc(t('remove'))}</button>
        </div>`;
      }).join('')}
      <button data-f data-action="subAdd" class="set-row">＋ ${esc(t('addSubscription'))}</button>
      ${Cloud.googleAvailable() ? `<h3 class="row-title">${esc(t('googleAccount'))}</h3>
      ${Cloud.account() ? `<button data-f data-action="accSync" class="set-row">${esc(t('syncNow'))}<span class="muted">${esc(Cloud.account().email)}</span></button>
        <button data-f data-action="accOut" class="set-row">${esc(t('signOut'))}</button>`
        : `<button data-f data-action="accIn" class="set-row">${esc(t('signInGoogle'))}</button><p class="muted about">${esc(t('googleHint'))}</p>`}` : ''}
      <h3 class="row-title">${esc(t('viewers'))}</h3>
      <button data-f data-action="switchViewer" class="set-row">${esc(t('switchViewer'))}<span>${esc(currentViewer() ? currentViewer().name : '')}</span></button>
      ${Cloud.enabled() ? `<button data-f data-action="syncNow" class="set-row">${esc(t('syncNow'))}<span class="muted">${esc(S.cloudStatus || '')}</span></button>` : ''}
      <p class="muted about">${esc(t(Cloud.enabled() ? 'cloudOnHint' : 'cloudOffHint'))}</p>
      <h3 class="row-title">${esc(t('settings'))}</h3>
      <button data-f data-action="lang" class="set-row">${esc(t('language'))}<span>${I18n.lang === 'ar' ? 'العربية' : 'English'}</span></button>
      <button data-f data-action="buffer" class="set-row">${esc(t('buffer'))}<span>${esc(t('seconds', S.settings.bufferSec))}</span></button>
      <h3 class="row-title">${esc(t('tmdbSection'))}</h3>
      <button data-f data-action="tmdb" class="set-row">${esc(t('tmdbKey'))}<span class="muted">${esc(t(Tmdb.enabled() ? 'tmdbOn' : 'tmdbOff'))}</span></button>
      ${Tmdb.enabled() ? `<button data-f data-action="tmdbRemove" class="set-row">${esc(t('remove'))} · TMDB</button>` : ''}
      <p class="muted about">${esc(t('tmdbAttribution'))}</p>
      <h3 class="row-title">${esc(t('matchesSection'))}</h3>
      <button data-f data-action="matchesToggle" class="set-row">${esc(t('showMatches'))}<span>${esc(Matches.prefs().show ? t('on') : t('off'))}</span></button>
      <button data-f data-action="matchPrefs" class="set-row">${esc(t('myTeams'))}<span class="muted">${Matches.prefs().teams.length}</span></button>
      <h3 class="row-title">${esc(t('parental'))}</h3>
      <button data-f data-action="pin" class="set-row">${esc(t(S.settings.pinHash ? 'changePin' : 'setPin'))}</button>
      ${S.settings.pinHash ? `<button data-f data-action="pinRemove" class="set-row">${esc(t('removePin'))}</button>` : ''}
      <button data-f data-action="hidden" class="set-row">${esc(t('hiddenCats'))}<span class="muted">${Object.keys(S.hidden).length}</span></button>
      <button data-f data-action="clearHistory" class="set-row">${esc(t('clearHistory'))}</button>
      <h3 class="row-title">${esc(t('about'))}</h3>
      <p class="muted about">NOVA IPTV 1.0 · ${esc(t('aboutDesc'))}</p>
    </div>`;
    function sid(el) { return el.getAttribute('data-id'); }
    actions.subUse = function (el) { activate(sid(el)); };
    actions.subRefresh = function (el) { activate(sid(el)).then(function () { showSync(sid(el)); }); };
    actions.subEdit = function (el) { showAddSub(sid(el)); };
    actions.subAdd = function () { showAddSub(); };
    actions.switchViewer = function () { showViewers(); };
    actions.accIn = function () { showGoogle(); };
    actions.accOut = async function () { await Cloud.signOut(); renderSection(false); toast(t('signedOut')); };
    actions.accSync = async function () { await pullAccount(); renderSection(false); toast(t('cloudSynced')); };
    actions.syncNow = async function () { await cloudPullViewers(); await cloudPullData(); renderSection(false); toast(t('cloudSynced')); };
    actions.subDel = async function (el) {
      const id = sid(el);
      if (!(await confirmBox(t('confirmDelete'), t('remove')))) return;
      S.subs = S.subs.filter(function (s) { return s.id !== id; });
      if (Cloud.account()) Cloud.put('users/' + Cloud.account().uid + '/subs/' + Cloud.safe(id), { x: true, up: Date.now() / 1000 });
      function mine(o) { return o === id || String(o).indexOf(id + '#') === 0; }
      S.fav = S.fav.filter(function (f) { return !mine(f.sub); });
      Object.keys(S.history).forEach(function (k) { if (mine(S.history[k].sub)) delete S.history[k]; });
      Store.del('viewers:' + id); Store.del('viewer:' + id);
      await Promise.all([saveSubs(), saveFav(), Store.set('history', S.history), Store.del('data:' + id), Store.del('hidden:' + id)]);
      if (!S.subs.length) { S.sub = null; S.data = null; showAddSub(); return; }
      if (id === S.sub.id) await activate(S.subs[0].id); else renderSection(false);
      S.ui.section = 'settings';
      if (screen.name === 'home') renderSection(true);
    };
    actions.lang = async function () {
      const v = await chooseBox(t('language'), [{ value: 'ar', label: 'العربية' }, { value: 'en', label: 'English' }], I18n.lang);
      if (v === undefined) return;
      S.settings.lang = v; I18n.setLang(v); await saveSettings();
      S.ui.section = 'settings'; showHome();
    };
    actions.buffer = async function () {
      const v = await chooseBox(t('buffer'), [5, 10, 15, 20].map(function (n) { return { value: n, label: t('seconds', n) }; }), S.settings.bufferSec);
      if (v === undefined) return;
      S.settings.bufferSec = v; await saveSettings(); renderSection(false); Nav.focus($('[data-action="buffer"]'));
    };
    actions.pin = async function () {
      if (!(await verifyPin())) return;
      const p = await pinBox(t('newPin'));
      if (!p || p.length < 4) return;
      S.settings.pinHash = await Store.sha256('nova|' + p);
      await saveSettings(); renderSection(false); Nav.focus($('[data-action="pin"]'));
    };
    actions.pinRemove = async function () {
      if (!(await verifyPin())) return;
      S.settings.pinHash = ''; await saveSettings(); renderSection(false); Nav.focus($('[data-action="pin"]'));
    };
    actions.hidden = async function () { if (await verifyPin()) showHiddenCats(); };
    actions.tmdb = async function () {
      const v = await inputBox(t('tmdbKey'), t('tmdbOff'));
      if (!v) return;
      toast(t('tmdbChecking'));
      const ok = await Tmdb.setKey(v);
      toast(t(ok ? 'tmdbSaved' : 'tmdbInvalid'));
      if (ok) {
        if (Cloud.account()) Cloud.put('users/' + Cloud.account().uid + '/settings/tmdb', v.trim());
        renderSection(false); Nav.focus($('[data-action="tmdb"]'));
      }
    };
    actions.tmdbRemove = async function () { await Tmdb.remove(); renderSection(false); Nav.focus($('[data-action="tmdb"]')); };
    actions.matchesToggle = async function () {
      Matches.prefs().show = !Matches.prefs().show; await Matches.save(); renderSection(false); Nav.focus($('[data-action="matchesToggle"]'));
    };
    actions.matchPrefs = function () { showMatchPrefs(); };
    actions.clearHistory = async function () {
      Object.keys(S.history).forEach(function (k) { if (S.history[k].sub === owner()) delete S.history[k]; });
      await Store.set('history', S.history);
      cloudPut('data/' + Cloud.safe(S.viewerId) + '/h', {});
      toast(t('historyCleared'));
    };
  }

  function showHiddenCats() {
    setScreen('hidden', function () {
      const groups = [['live', t('live')], ['movie', t('movies')], ['series', t('series')]];
      app.innerHTML = `<div class="settings page"><h1>${esc(t('hiddenCats'))}</h1><p class="muted">${esc(t('hiddenCatsDesc'))}</p>` +
        groups.map(function (g) {
          const cats = (S.data && S.data.cats[g[0]]) || [];
          if (!cats.length) return '';
          return `<h3 class="row-title">${esc(g[1])}</h3><div class="grid cats" data-group>` + cats.map(function (c) {
            const key = g[0] + '|' + c.id, hid = !!S.hidden[key];
            return `<button data-f data-action="toggle" data-k="${esc(key)}" class="set-row${hid ? ' off' : ''}">${esc(c.name)}<span>${esc(t(hid ? 'hidden' : 'visible'))}</span></button>`;
          }).join('') + '</div>';
        }).join('') + '</div>';
      actions.toggle = function (el) {
        const k = el.getAttribute('data-k');
        if (S.hidden[k]) delete S.hidden[k]; else S.hidden[k] = true;
        Store.set('hidden:' + S.sub.id, S.hidden);
        el.classList.toggle('off', !!S.hidden[k]);
        el.querySelector('span').textContent = t(S.hidden[k] ? 'hidden' : 'visible');
      };
      Nav.focusFirst();
    }, { back: function () { S.ui.section = 'settings'; showHome(); } });
  }

  async function exitApp() {
    if (await confirmBox(t('exitQ'), t('exit'))) {
      Player.stop();
      try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.close(); }
    }
  }


  // ------------------------------------------------------------------ Google account: subscriptions follow the account
  async function pushAccountSub(rec, pass, m3u) {
    const acc = Cloud.account(); if (!acc) return;
    if (pass == null) pass = await Store.decrypt(rec.passEnc);
    if (m3u == null) m3u = await Store.decrypt(rec.m3uEnc);
    Cloud.put('users/' + acc.uid + '/subs/' + Cloud.safe(rec.id), {
      n: rec.name || '', t: rec.type, s: rec.server || '', u: rec.user || '', p: pass || '', m: m3u || '', up: rec.updated || Date.now() / 1000
    });
  }

  /** Merges the account's subscriptions with this TV's (newest wins, deletions travel), then the TMDB key. */
  async function pullAccount() {
    const acc = Cloud.account(); if (!acc) return false;
    const remote = await Cloud.get('users/' + acc.uid) || {};
    const rs = remote.subs || {};
    let changed = false;
    for (const id of Object.keys(rs)) {
      const d = rs[id] || {}, up = Number(d.up) || 0;
      const i = S.subs.findIndex(function (x) { return Cloud.safe(x.id) === id; });
      if (d.x) { if (i >= 0 && (S.subs[i].updated || 0) <= up) { S.subs.splice(i, 1); changed = true; } continue; }
      const rec = { id: i >= 0 ? S.subs[i].id : id, name: d.n || '', type: d.t === 'm3u' ? 'm3u' : 'xtream', server: d.s || '', user: d.u || '',
        passEnc: await Store.encrypt(d.p || ''), m3uEnc: await Store.encrypt(d.m || ''), updated: up };
      if (i < 0) { S.subs.push(rec); changed = true; }
      else if ((S.subs[i].updated || 0) < up) { S.subs[i] = Object.assign({}, S.subs[i], rec); changed = true; }
      else if ((S.subs[i].updated || 0) > up) pushAccountSub(S.subs[i]);
    }
    for (const x of S.subs) if (!rs[Cloud.safe(x.id)]) pushAccountSub(x);
    if (changed) await saveSubs();
    const key = remote.settings && remote.settings.tmdb;
    if (key && !Tmdb.enabled()) { try { await Tmdb.setKey(key); } catch (e) { /* invalid key */ } }
    else if (!key && Tmdb.enabled()) { const enc = await Store.get('tmdbKey', ''); const mine = enc ? await Store.decrypt(enc) : ''; if (mine) Cloud.put('users/' + acc.uid + '/settings/tmdb', mine); }
    return changed;
  }

  /** Turns Google's error code into a hint about what to fix. */
  function googleHint(e) {
    const l = String(e || '').toLowerCase();
    if (/invalid_client|unauthorized_client/.test(l)) return t('gErrClient') + ' (' + e + ')';
    if (/restricted|consent|access_denied/.test(l)) return t('gErrConsent') + ' (' + e + ')';
    if (/operation_not_allowed|not enabled/.test(l)) return t('gErrProvider') + ' (' + e + ')';
    if (/invalid_idp_response|audience/.test(l)) return t('gErrAudience') + ' (' + e + ')';
    return e || '';
  }

  /** Google sign-in for TVs: a short code on screen, entered on the phone at google.com/device. */
  function showGoogle() {
    let alive = true, code = null;
    setScreen('google', function () {
      app.innerHTML = `<div class="profiles">
        <div class="glow g1"></div><div class="glow g2"></div><div class="glow g3"></div>
        <div class="pf-brand"><div class="logo small"></div><span>NOVA</span></div>
        <h1>${esc(t('signInGoogle'))}</h1>
        <p class="muted" style="max-width:1000px">${esc(t('googleWhy'))}</p>
        <div id="g-box" class="center-col" style="min-height:300px"><div class="spinner"></div></div>
        <div class="row center">
          <button data-f data-action="gNew" class="btn">${esc(t('newCode'))}</button>
          <button data-f data-autofocus data-action="gSkip" class="btn ghost">${esc(t(S.subs.length ? 'later' : 'addManually'))}</button>
        </div>
      </div>`;
      actions.gSkip = function () { alive = false; if (S.subs.length) showProfiles(); else showAddSub(); };
      actions.gNew = function () { alive = false; showGoogle(); };
      Nav.focusFirst();
    }, { back: function () { alive = false; if (S.subs.length) showProfiles(); else showAddSub(); } });
    (async function () {
      code = await Cloud.startGoogle();
      const box = $('#g-box');
      if (!alive || !box) return;
      if (!code) { box.innerHTML = `<p class="error">${esc(t('googleFailed'))}</p><p class="muted">${esc(googleHint(Cloud.lastError()))}</p>`; return; }
      box.innerHTML = `<p style="font-size:30px">1. ${esc(t('googleStep1'))} <b style="color:var(--primary)">${esc(code.url.replace('https://', ''))}</b></p>
        <p style="font-size:30px">2. ${esc(t('googleStep2'))}</p>
        <div style="font-size:72px;font-weight:900;letter-spacing:10px;background:var(--s2);padding:14px 40px;border-radius:18px;direction:ltr">${esc(code.user)}</div>
        <p class="muted" id="g-wait">${esc(t('googleWaiting'))}</p>`;
      while (alive) {
        await new Promise(function (r) { setTimeout(r, code.interval * 1000); });
        if (!alive) return;
        const r = await Cloud.pollGoogle(code);
        if (!alive) return;
        if (r && r.ok) {
          alive = false;
          toast(t('signedInAs', r.email));
          await pullAccount();
          if (S.subs.length) showProfiles(); else showAddSub();
          return;
        }
        if (r === 'expired') { alive = false; showGoogle(); return; }
        if (r === 'denied' || r === 'failed') { const w = $('#g-wait'); if (w) w.textContent = r === 'denied' ? t('googleDenied') : t('googleFailed') + ' — ' + googleHint(Cloud.lastError()); alive = false; return; }
      }
    })();
  }

  // ------------------------------------------------------------------ viewers (up to 5 per subscription) + sync
  function owner() { return !S.viewerId || S.viewerId === 'main' ? S.sub.id : S.sub.id + '#' + S.viewerId; }
  function currentViewer() { return (S.viewers || []).find(function (v) { return v.id === S.viewerId; }); }
  function defaultViewers() { return [{ id: 'main', name: I18n.lang === 'ar' ? 'أنا' : 'Me', color: 0, pinHash: null, updated: 0 }]; }
  let cloudKey = '';
  async function loadViewers() {
    const all = await Store.get('viewers:' + S.sub.id, null) || defaultViewers();
    S.viewersAll = all;
    S.viewers = all.filter(function (v) { return !v.deleted; });
    if (!S.viewers.length) { S.viewersAll = S.viewers = defaultViewers(); }
    const saved = await Store.get('viewer:' + S.sub.id, 'main');
    S.viewerId = S.viewers.some(function (v) { return v.id === saved; }) ? saved : S.viewers[0].id;
    cloudKey = Cloud.enabled() ? await Cloud.subKey(S.sub) : '';
  }
  function saveViewers() {
    S.viewers = S.viewersAll.filter(function (v) { return !v.deleted; });
    return Store.set('viewers:' + S.sub.id, S.viewersAll);
  }
  function cloudPath(p) { return cloudKey ? 'nova/v1/' + cloudKey + '/' + p : ''; }
  function cloudPut(p, v) { const full = cloudPath(p); if (full) Cloud.put(full, v); }
  function favPath(f) { return 'data/' + Cloud.safe(S.viewerId || 'main') + '/f/' + Cloud.Codec.favKey(f.type, f.id); }
  function pushHistory(h) { if (h) cloudPut('data/' + Cloud.safe(S.viewerId || 'main') + '/h/' + Cloud.Codec.historyKey(h), Cloud.Codec.history(h)); }
  function pushViewer(v) { cloudPut('profiles/' + Cloud.safe(v.id), Cloud.Codec.viewer(v)); }
  function pinHashOf(pin) { return Promise.resolve(Cloud.sha256hex((cloudKey || S.sub.id) + ':' + pin)); }

  async function enterAfterSub() {
    await loadViewers();
    if (S.viewers.length === 1 && !S.viewers[0].pinHash) await chooseViewer(S.viewers[0]);
    else showViewers();
    cloudPullViewers().then(function (changed) { if (changed && screen && screen.name === 'viewers') showViewers(); });
  }
  async function chooseViewer(v) {
    S.viewerId = v.id;
    await Store.set('viewer:' + S.sub.id, v.id);
    S.ui.section = 'home';
    showHome();
    cloudPullData().then(function (changed) { if (changed && screen && screen.name === 'home') renderSection(false); });
  }

  /** Viewers: newest change wins on each side; deletions travel as tombstones. */
  async function cloudPullViewers() {
    const base = cloudPath('profiles');
    if (!base) return false;
    const sid = S.sub.id;
    const remote = await Cloud.get(base) || {};
    if (!S.sub || S.sub.id !== sid) return false;
    let changed = false;
    Object.keys(remote).forEach(function (id) {
      const rv = Cloud.Codec.viewerFrom(id, remote[id] || {});
      const i = S.viewersAll.findIndex(function (v) { return v.id === id; });
      if (i < 0) { S.viewersAll.push(rv); changed = true; }
      else if (rv.updated > (S.viewersAll[i].updated || 0)) { S.viewersAll[i] = rv; changed = true; }
      else if (rv.updated < (S.viewersAll[i].updated || 0)) pushViewer(S.viewersAll[i]);
    });
    S.viewersAll.forEach(function (v) { if (!remote[v.id]) pushViewer(v); });
    if (changed) {
      await saveViewers();
      if (!S.viewers.some(function (v) { return v.id === S.viewerId; })) S.viewerId = S.viewers[0].id;
    }
    S.cloudStatus = t('cloudSynced');
    return changed;
  }

  /** Progress and favorites of the current viewer: newest wins per title, both ways. */
  async function cloudPullData() {
    const base = cloudPath('data/' + Cloud.safe(S.viewerId || 'main'));
    if (!base) return false;
    const own = owner();
    const remote = await Cloud.get(base) || {};
    if (own !== owner()) return false;
    let changed = false;
    const rh = remote.h || {}, seen = {};
    Object.keys(rh).forEach(function (k) {
      const h = Cloud.Codec.historyFrom(rh[k], own);
      if (!h) return;
      seen[Cloud.Codec.historyKey(h)] = 1;
      const key = own + '|' + h.type + '|' + h.id + '|' + (h.ep || '');
      const local = S.history[key];
      if (!local || h.t > (local.t || 0)) { S.history[key] = h; changed = true; }
      else if (h.t < (local.t || 0)) pushHistory(local);
    });
    Object.keys(S.history).forEach(function (k) { const h = S.history[k]; if (h.sub === own && !seen[Cloud.Codec.historyKey(h)]) pushHistory(h); });
    const rf = remote.f || {}, rseen = {};
    Object.keys(rf).forEach(function (k) {
      const d = rf[k]; if (!d || !d.k) return;
      rseen[k] = 1;
      const id = String(d.i);
      const i = S.fav.findIndex(function (f) { return f.sub === own && f.type === d.k && f.id === id; });
      if (d.del) { if (i >= 0) { S.fav.splice(i, 1); changed = true; } }
      else if (i < 0) {
        const item = { id: id, name: d.n || '', logo: d.l || '', cat: d.c || '', ext: d.x || '', year: d.y || '' };
        S.fav.push({ sub: own, type: d.k, id: id, name: item.name, logo: item.logo, item: item }); changed = true;
      }
    });
    S.fav.forEach(function (f) { if (f.sub === own && !rseen[Cloud.Codec.favKey(f.type, f.id)]) cloudPut(favPath(f), Cloud.Codec.fav(f, false)); });
    if (changed) { saveHistory(); saveFav(); }
    S.cloudStatus = t('cloudSynced');
    return changed;
  }

  function showViewers(editing) {
    setScreen('viewers', function () {
      app.innerHTML = `<div class="profiles">
        <div class="glow g1"></div><div class="glow g2"></div><div class="glow g3"></div>
        <div class="pf-brand"><div class="logo small"></div><span>NOVA</span></div>
        <h1>${esc(t(editing ? 'manageViewers' : 'whoWatching'))}</h1>
        <p class="muted">${esc(S.sub.name || '')}${Cloud.enabled() ? ' · ' + esc(t('cloudOn')) : ''}</p>
        <div class="pf-row" data-group>
          ${S.viewers.map(function (v, i) {
            const pal = PALETTES[(v.color || 0) % PALETTES.length];
            const act = v.id === S.viewerId;
            return `<button data-f data-action="${editing ? 'vEdit' : 'vPick'}" data-id="${esc(v.id)}" class="pf"${act ? ' data-autofocus' : ''} style="animation-delay:${0.15 + i * 0.08}s">
              <span class="pf-tile" style="background:linear-gradient(135deg,${pal[0]},${pal[1]})">
                <span class="pf-init">${esc((v.name || 'N').trim().charAt(0).toUpperCase())}</span>
                ${v.pinHash ? '<span class="pf-type">🔒</span>' : ''}
                ${editing ? '<span class="pf-check">✎</span>' : ''}
              </span>
              <b>${esc(v.name)}</b></button>`;
          }).join('')}
          ${S.viewers.length < 5 ? `<button data-f data-action="vAdd" class="pf add"><span class="pf-tile dashed">＋</span><b>${esc(t('addViewer'))}</b></button>` : ''}
        </div>
        <div class="row center">
          <button data-f data-action="vManage" class="btn ghost">${editing ? '✓ ' + esc(t('done')) : '✎ ' + esc(t('manageViewers'))}</button>
          <button data-f data-action="vSwitchSub" class="btn ghost">⇄ ${esc(t('switchSub'))}</button>
        </div>
      </div>`;
      function find(el) { return S.viewers.find(function (v) { return v.id === el.getAttribute('data-id'); }); }
      actions.vPick = async function (el) {
        const v = find(el); if (!v) return;
        if (v.pinHash) {
          const pin = await pinBox(t('enterPin') + ' — ' + v.name);
          if (pin == null) return;
          if ((await pinHashOf(pin)) !== v.pinHash) { toast(t('wrongPin')); return; }
        }
        chooseViewer(v);
      };
      actions.vEdit = function (el) { const v = find(el); if (v) editViewer(v); };
      actions.vAdd = function () { editViewer(null); };
      actions.vManage = function () { showViewers(!editing); };
      actions.vSwitchSub = function () { showProfiles(); };
      Nav.focusFirst();
    }, { back: function () { if (editing) showViewers(false); else showProfiles(); } });
  }

  async function editViewer(v) {
    const now = Date.now() / 1000;
    if (!v) {
      if (S.viewers.length >= 5) return;
      const name = await inputBox(t('addViewer'), t('viewerName'));
      if (!name) return showViewers(true);
      const nv = { id: Math.random().toString(36).slice(2, 14), name: name, color: S.viewers.length % PALETTES.length, pinHash: null, updated: now };
      if (await confirmBox(t('setPinQ'), t('yes'))) {
        const pin = await pinBox(t('pinOptional'));
        if (pin && pin.length === 4) nv.pinHash = await pinHashOf(pin);
      }
      S.viewersAll.push(nv);
      await saveViewers(); pushViewer(nv);
      return showViewers(true);
    }
    const choice = await chooseBox(v.name, [
      { value: 'name', label: t('rename') }, { value: 'color', label: t('changeColor') },
      { value: 'pin', label: t(v.pinHash ? 'changePin' : 'setPin') }
    ].concat(v.pinHash ? [{ value: 'nopin', label: t('removePin') }] : [])
     .concat(S.viewers.length > 1 ? [{ value: 'del', label: t('deleteViewer') }] : []), null);
    const rec = S.viewersAll.find(function (x) { return x.id === v.id; });
    if (!choice || !rec) return showViewers(true);
    if (choice === 'name') { const n = await inputBox(t('rename'), v.name); if (n) rec.name = n; }
    else if (choice === 'color') {
      const c = await chooseBox(t('changeColor'), PALETTES.map(function (p, i) { return { value: i, label: ['🔵', '🩷', '🟢', '🟠', '🟣', '💚'][i] + ' ' + (i + 1) }; }), rec.color);
      if (c !== undefined) rec.color = c;
    }
    else if (choice === 'pin') { const pin = await pinBox(t('pinOptional')); if (pin && pin.length === 4) rec.pinHash = await pinHashOf(pin); else if (pin) toast(t('pin4')); }
    else if (choice === 'nopin') rec.pinHash = null;
    else if (choice === 'del') {
      if (!(await confirmBox(t('deleteViewer') + ' — ' + rec.name + '?', t('remove')))) return showViewers(true);
      rec.deleted = true;
      const own = rec.id === 'main' ? S.sub.id : S.sub.id + '#' + rec.id;
      Object.keys(S.history).forEach(function (k) { if (S.history[k].sub === own) delete S.history[k]; });
      S.fav = S.fav.filter(function (f) { return f.sub !== own; });
      saveHistory(); saveFav();
      if (S.viewerId === rec.id) S.viewerId = null;
    }
    rec.updated = Date.now() / 1000;
    await saveViewers(); pushViewer(rec);
    if (!S.viewerId && S.viewers.length) S.viewerId = S.viewers[0].id;
    showViewers(true);
  }

  // ------------------------------------------------------------------ profiles ("Who's watching?")
  const PALETTES = [['#4F7BFF', '#9B6CFF'], ['#FF5E8A', '#FF9F5A'], ['#16C7A8', '#2D8CFF'], ['#FFB648', '#FF5E5E'], ['#B45CFF', '#FF5CC8'], ['#3DDC84', '#0FA3B1']];
  function showProfiles() {
    setScreen('profiles', function () {
      app.innerHTML = `<div class="profiles">
        <div class="glow g1"></div><div class="glow g2"></div><div class="glow g3"></div>
        <div class="pf-brand"><div class="logo small"></div><span>NOVA</span></div>
        <h1>${esc(t('chooseSub'))}</h1>
        <p class="muted">${esc(t('chooseSubHint'))}</p>
        <div class="pf-row" data-group>
          ${S.subs.map(function (s, i) {
            const pal = PALETTES[i % PALETTES.length];
            const days = s.expires ? Math.floor((s.expires - Date.now()) / 86400000) : null;
            const status = days == null ? '' : days < 0 ? `<span class="pf-st bad">${esc(t('expired'))}</span>`
              : `<span class="pf-st${days <= 7 ? ' warn' : ''}">${esc(t('daysLeft', days))}</span>`;
            const act = s.id === S.settings.activeSub;
            return `<button data-f data-action="pick" data-id="${s.id}" class="pf"${act ? ' data-autofocus' : ''} style="animation-delay:${0.15 + i * 0.08}s">
              <span class="pf-tile" style="background:linear-gradient(135deg,${pal[0]},${pal[1]})">
                <span class="pf-init">${esc((s.name || 'N').trim().charAt(0).toUpperCase())}</span>
                <span class="pf-type">${s.type === 'xtream' ? 'XTREAM' : 'M3U'}</span>
                ${act ? '<span class="pf-check">✓</span>' : ''}
              </span>
              <b>${esc(s.name)}</b>${status}</button>`;
          }).join('')}
          <button data-f data-action="addProfile" class="pf add"><span class="pf-tile dashed">＋</span><b>${esc(t('addSubscription'))}</b></button>
        </div>
        <button data-f data-action="manage" class="btn ghost">⚙ ${esc(t('manageSubs'))}</button>
      </div>`;
      actions.pick = function (el) { activate(el.getAttribute('data-id')); };
      actions.addProfile = function () { showAddSub(); };
      actions.manage = async function () { await activate(S.settings.activeSub || S.subs[0].id); S.ui.section = 'settings'; showHome(); };
      Nav.focusFirst();
    }, { back: function () { exitApp(); } });
  }

  // ------------------------------------------------------------------ discover (home)
  let heroTimer = null, matchTimer = null, matchDay = 0, matchList = null;
  function featuredItems() {
    function recent(type) {
      return ((S.data && S.data[type]) || []).filter(function (x) { return x.logo && !S.hidden[type + '|' + x.cat]; })
        .slice().sort(function (a, b) { return (b.added || 0) - (a.added || 0); }).slice(0, 6).map(function (x) { return { type: type, item: x }; });
    }
    const m = recent('movie'), se = recent('series'), out = [];
    for (let i = 0; i < 6 && out.length < 8; i++) { if (m[i]) out.push(m[i]); if (se[i]) out.push(se[i]); }
    return out.slice(0, 8);
  }
  function latest(type, n) {
    return ((S.data && S.data[type]) || []).filter(function (x) { return !S.hidden[type + '|' + x.cat]; })
      .slice().sort(function (a, b) { return (b.added || 0) - (a.added || 0); }).slice(0, n);
  }

  function contCard(h, i) {
    const pct = h.dur ? Math.max(1, Math.min(99, Math.round(h.pos / h.dur * 100))) : 0;
    const se = h.type === 'series' ? Clean.se(h.subtitle) : null;
    return `<button data-f data-action="resume" data-h="${esc(hkey(h.type, h.id, h.ep))}" class="wcard" data-key="cont:${i}">
      <span class="wbg">${img(h.logo)}</span><span class="wshade"></span>
      <span class="wchip">${esc(t('watchedPct', pct))}</span><span class="wplay">▶</span>
      <span class="wtxt">${se ? `<span class="wse">${esc(t('seasonEpisode', se.s, se.e))}</span>` : ''}<b>${esc(Clean.title(h.title))}</b>
      <span class="bar"><span style="width:${pct}%"></span></span></span></button>`;
  }

  function posterBtn(type, it, i, action) {
    return `<button data-f data-action="${action}" data-i="${i}" data-type="${type}" data-key="d:${action}:${type}:${esc(it.id)}" class="poster">
      <span class="pimg">${img(it.logo)}${it.rating && it.rating !== '0' ? `<span class="rate">★ ${esc(String(it.rating).slice(0, 3))}</span>` : ''}</span>
      <span class="ptitle">${esc(Clean.title(it.name))}</span><span class="psub">${esc(it.year || '')}</span></button>`;
  }

  /** Swaps a card's artwork/title for the TMDB version in the app language. */
  function localize(node, type, it) {
    if (!node || !it || !Tmdb.enabled()) return;
    Tmdb.card(type, it.name, it.year).then(function (r) {
      if (!r || !document.body.contains(node)) return;
      if (r.poster) { const im = node.querySelector('.pimg img'); if (im) { im.decoding = 'async'; im.src = r.poster; } else { const b = node.querySelector('.pimg'); if (b) b.insertAdjacentHTML('afterbegin', `<img decoding="async" src="${esc(r.poster)}">`); } }
      if (r.title) { const tt = node.querySelector('.ptitle'); if (tt) tt.textContent = r.title; }
    });
  }

  function renderDiscover(c) {
    clearInterval(heroTimer);
    const feat = featuredItems();
    const cont = continueList('movie').concat(continueList('series')).sort(function (a, b) { return b.t - a.t; }).slice(0, 15);
    const chans = Object.keys(S.history).map(function (k) { return S.history[k]; })
      .filter(function (h) { return h.sub === owner() && h.type === 'live' && h.item; }).sort(function (a, b) { return b.t - a.t; }).slice(0, 15);
    const mov = Library.ready() ? Library.collapse('movie', latest('movie', 80)).slice(0, 20) : latest('movie', 20);
    const ser = Library.ready() ? Library.collapse('series', latest('series', 80)).slice(0, 20) : latest('series', 20);
    const favs = S.fav.filter(function (f) { return f.sub === owner() && f.type !== 'live' && f.item; });
    c.innerHTML = `<div class="disc" id="disc">
      ${feat.length ? `<section class="hero" id="hero"></section>` : '<div style="height:120px"></div>'}
      ${Matches.prefs().show ? `<section class="rowsec"><div class="rowhead"><span class="ball">⚽</span><h3>${esc(t('matches'))}</h3>
        <div class="row" data-group><button data-f data-action="mday" data-d="0" class="chip${matchDay === 0 ? ' active' : ''}">${esc(t('today'))}</button>
        <button data-f data-action="mday" data-d="1" class="chip${matchDay === 1 ? ' active' : ''}">${esc(t('tomorrow'))}</button>
        <button data-f data-action="matchPrefs" class="chip">⚙</button></div></div>
        <div class="hrow" id="matches" data-group><div class="mcard sk"></div><div class="mcard sk"></div><div class="mcard sk"></div></div></section>` : ''}
      ${cont.length ? `<section class="rowsec"><h3 class="row-title">${esc(t('continueWatching'))}</h3><div class="hrow" data-group>${cont.map(contCard).join('')}</div></section>` : ''}
      ${chans.length ? `<section class="rowsec"><h3 class="row-title">${esc(t('recentChannels'))}</h3><div class="hrow" data-group>${chans.map(function (h, i) {
        return `<button data-f data-action="dchan" data-i="${i}" class="lcard"><span class="lbadge">● ${esc(t('liveBadge'))}</span><span class="limg">${img(h.item.logo)}</span><b>${esc(Clean.title(h.item.name))}</b></button>`;
      }).join('')}</div></section>` : ''}
      ${mov.length ? `<section class="rowsec"><h3 class="row-title">${esc(t('latestMovies'))}</h3><div class="hrow posters" data-group data-type="movie">${mov.map(function (it, i) { return posterBtn('movie', it, i, 'dopen'); }).join('')}</div></section>` : ''}
      ${ser.length ? `<section class="rowsec"><h3 class="row-title">${esc(t('latestSeries'))}</h3><div class="hrow posters" data-group data-type="series">${ser.map(function (it, i) { return posterBtn('series', it, i, 'dopen'); }).join('')}</div></section>` : ''}
      ${favs.length ? `<section class="rowsec"><h3 class="row-title">${esc(t('favorites'))}</h3><div class="hrow posters" data-group data-type="fav">${favs.map(function (f, i) { return posterBtn(f.type, f.item, i, 'dfav'); }).join('')}</div></section>` : ''}
    </div>`;
    const lists = { movie: mov, series: ser };
    actions.resume = function (el) { S.ui.focusKey = el.getAttribute('data-key'); const h = S.history[el.getAttribute('data-h')]; if (h) resumeHistory(h); };
    actions.dchan = function (el) { const h = chans[parseInt(el.getAttribute('data-i'), 10)]; playLive(h.item, chans.map(function (x) { return x.item; })); };
    actions.dopen = function (el) {
      S.ui.focusKey = el.getAttribute('data-key');
      const type = el.getAttribute('data-type'), it = lists[type][parseInt(el.getAttribute('data-i'), 10)];
      openTitle(type, it);
    };
    actions.dfav = function (el) { S.ui.focusKey = el.getAttribute('data-key'); const f = favs[parseInt(el.getAttribute('data-i'), 10)]; if (f.type === 'movie') showMovie(f.item); else showSeries(f.item); };
    actions.mday = function (el) {
      matchDay = parseInt(el.getAttribute('data-d'), 10); matchList = null;
      document.querySelectorAll('[data-action="mday"]').forEach(function (b) { b.classList.toggle('active', b === el); });
      loadMatches(true);
    };
    actions.matchPrefs = function () { showMatchPrefs(); };
    actions.mwatch = function (el) { const m = (matchList || [])[parseInt(el.getAttribute('data-i'), 10)]; if (m) watchMatch(m); };
    c.querySelectorAll('.hrow.posters .poster').forEach(function (n) {
      const type = n.getAttribute('data-type');
      const row = n.parentNode.getAttribute('data-type');
      const it = row === 'fav' ? favs[parseInt(n.getAttribute('data-i'), 10)].item : lists[type][parseInt(n.getAttribute('data-i'), 10)];
      localize(n, type, it);
    });
    // continue watching: TMDB title and wide artwork, like the posters
    if (Tmdb.enabled()) c.querySelectorAll('.wcard').forEach(function (n) {
      const h = S.history[n.getAttribute('data-h')];
      if (!h || h.type === 'live') return;
      const name = (h.item && h.item.name) || h.title;
      Tmdb.card(h.type, name, h.item && h.item.year).then(function (r) {
        if (!r || !document.body.contains(n)) return;
        const art = r.bd || r.poster;
        if (art) { const b = n.querySelector('.wbg'); if (b) b.innerHTML = `<img src="${esc(art)}">`; }
        if (r.title) { const tt = n.querySelector('.wtxt b'); if (tt) tt.textContent = r.title; }
      });
    });
    if (feat.length) renderHero(feat, 0);
    if (Matches.prefs().show) loadMatches(false);
  }

  // ------------------------------------------------------------------ screensaver
  // After a minute without the remote (and nothing playing), the TV shows the library's movies and series
  // full-screen from TMDB: backdrop, logo/title, type, year, genres, rating and story — a new one every 10 s.
  const Saver = (function () {
    const IDLE = 60000, SLIDE = 10000;
    let box = null, slideTimer = null, layer = 0, shown = 0, preparing = null, recent = [];
    function canStart() {
      return Tmdb.enabled() && S.data && screen && screen.name !== 'player' && !box &&
        !document.querySelector('.trailer') && Nav.idleFor && Nav.idleFor() >= IDLE;
    }
    function randomEntry() {
      const types = ['movie', 'series'], type = types[Math.floor(Math.random() * 2)];
      const list = (S.data[type] || []).filter(function (x) { return !S.hidden[type + '|' + x.cat]; });
      if (!list.length) return null;
      // favour recent additions a little, but keep it varied
      const pool = Math.random() < 0.5 ? list.slice().sort(function (a, b) { return (b.added || 0) - (a.added || 0); }).slice(0, 300) : list;
      const it = pool[Math.floor(Math.random() * pool.length)];
      return recent.indexOf(type + it.id) >= 0 ? null : { type: type, item: it };
    }
    function preload(url) {
      return new Promise(function (res) { const im = new Image(); im.onload = function () { res(true); }; im.onerror = function () { res(false); }; im.src = url; });
    }
    /** Finds a title with full TMDB artwork + story (tries a few). */
    async function prepare() {
      for (let tries = 0; tries < 16; tries++) {
        const e = randomEntry(); if (!e) continue;
        let tm = await Tmdb.details(e.type, e.item.tmdb, e.item.name, e.item.year).catch(function () { return null; });
        if (!tm || !tm.backdrop || !tm.overview) continue;
        // Arabic app: only titles TMDB has in Arabic (title + story), no English fallbacks
        if (I18n.lang === 'ar' && !(tm.localText && /[\u0600-\u06FF]/.test(tm.overview) && /[\u0600-\u06FF]/.test(tm.title))) continue;
        if (!(await preload(tm.backdrop))) continue;
        const logo = I18n.lang === 'ar' ? tm.logoLocal : tm.logo;
        if (logo) await preload(logo);
        tm = Object.assign({}, tm, { saverLogo: logo });
        recent.push(e.type + e.item.id); if (recent.length > 40) recent.shift();
        return { e: e, tm: tm };
      }
      return null;
    }
    function show(d) {
      if (!box) return;
      layer = 1 - layer;
      const L = box.querySelectorAll('.sv-layer'), info = box.querySelector('.sv-info');
      const cur = L[layer], old = L[1 - layer];
      cur.style.backgroundImage = "url('" + d.tm.backdrop + "')";
      cur.classList.remove('on'); void cur.offsetWidth; cur.classList.add('on');   // restart the slow zoom
      old.classList.remove('on');
      const tm = d.tm, meta = [tm.year, tm.genres.slice(0, 3).join(' · '), tm.seasons ? t('seasonsCount', tm.seasons) : ''].filter(Boolean).join('   •   ');
      info.classList.remove('in'); void info.offsetWidth;
      info.innerHTML = `<span class="sv-type">${esc(t(d.e.type === 'movie' ? 'typeMovie' : 'typeSeries'))}</span>
        ${tm.saverLogo ? `<img class="sv-logo" src="${esc(tm.saverLogo)}">` : `<h1>${esc(tm.title)}</h1>`}
        <div class="sv-meta">${tm.rating && tm.votes >= 20 ? `<span class="sv-rate">★ ${esc(tm.rating)}</span>&emsp;•&emsp;` : ''}<bdi>${esc(meta)}</bdi></div>
        <p class="sv-plot">${esc(tm.overview)}</p>`;
      info.classList.add('in');
      shown++;
    }
    async function cycle() {
      if (!box) return;
      const d = preparing ? await preparing : await prepare();
      preparing = null;
      if (!box) return;
      if (d) show(d);
      else if (!shown) { stop(); return; }   // nothing with TMDB artwork: don't show an empty screen
      preparing = prepare();                  // get the next one ready while this one is on screen
      slideTimer = setTimeout(cycle, SLIDE);
    }
    function clock() {
      if (!box) return;
      const d = new Date(), c = box.querySelector('.sv-clock');
      if (c) c.textContent = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
      setTimeout(clock, 15000);
    }
    async function start() {
      const first = await prepare();
      if (!first || !canStart()) return;
      box = document.createElement('div');
      box.className = 'saver';
      box.innerHTML = '<div class="sv-layer"></div><div class="sv-layer"></div><div class="sv-shade"></div>' +
        '<div class="sv-clock"></div><div class="sv-brand"><span class="logo small"></span></div><div class="sv-info"></div>';
      document.body.appendChild(box);
      shown = 0; preparing = Promise.resolve(first);
      clock(); cycle();
    }
    function stop() {
      clearTimeout(slideTimer); preparing = null;
      if (!box) return;
      const b = box; box = null;
      b.classList.add('out');
      setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 400);
    }
    // any key wakes the app; that key does nothing else
    window.addEventListener('keydown', function (e) {
      if (!box) return;
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      if (Nav.touch) Nav.touch();
      stop();
    }, true);
    let checking = false;
    setInterval(function () {
      if (checking || !canStart()) return;
      checking = true; start().then(function () { checking = false; }, function () { checking = false; });
    }, 5000);
    return { stop: stop, get active() { return !!box; } };
  })();

  /** Swaps a background picture only once it has loaded (no flash of an empty frame). */
  function swapBg(el, url) {
    if (!el || !url) return;
    const im = new Image();
    im.onload = function () { if (document.body.contains(el)) el.style.backgroundImage = "url('" + url + "')"; };
    im.src = url;
  }

  function renderHero(feat, idx) {
    const box = $('#hero');
    if (!box) return;
    const f = feat[idx], it = f.item, isMovie = f.type === 'movie';
    const focusedIn = Nav.current && box.contains(Nav.current) ? Nav.current.getAttribute('data-action') : null;
    const oldBg = box.querySelector('.hbg.cur'), prevImg = oldBg ? oldBg.style.backgroundImage : '';
    // cross-fade: the previous picture stays underneath while the new one fades in
    box.innerHTML = `${prevImg ? `<div class="hbg prev" style="background-image:${prevImg.replace(/"/g, '&quot;')}"></div>` : ''}
      <div class="hbg cur${prevImg ? ' fadein' : ''}" style="background-image:url('${esc(it.logo)}')"></div><div class="hshade"></div>
      <div class="hbody${prevImg ? ' fadein' : ''}">
        <span class="hbadge">● ${esc(t('newBadge'))} · ${esc(t(isMovie ? 'typeMovie' : 'typeSeries'))}</span>
        <div id="htitle"><h1>${esc(Clean.title(it.name))}</h1></div>
        <div class="hmeta" id="hmeta">${it.rating && it.rating !== '0' ? `<span class="star-inline">★ ${esc(String(it.rating).slice(0, 3))}</span>` : ''}${esc(it.year || '')}</div>
        <p class="hplot" id="hplot">${esc(it.plot || '')}</p>
        <div class="row" data-group>
          <button data-f data-action="hplay" class="btn white">${isMovie ? '▶ ' + esc(t('play')) : 'ℹ ' + esc(t('watchNow'))}</button>
          ${isMovie ? `<button data-f data-action="hinfo" class="btn glass">ℹ ${esc(t('details'))}</button>` : ''}
          <button data-f data-action="hfav" class="btn glass round">${isFav(f.type, it.id) ? '✓' : '＋'}</button>
        </div>
      </div>
      <div class="dots">${feat.map(function (_, i) { return `<span class="${i === idx ? 'on' : ''}"></span>`; }).join('')}</div>`;
    actions.hplay = function () { if (isMovie) playVod({ kind: 'movie', item: it, title: it.name, logo: it.logo }); else showSeries(it); };
    actions.hinfo = function () { showMovie(it); };
    actions.hfav = function (el) { const added = toggleFav(f.type, it); el.textContent = added ? '✓' : '＋'; };
    if (focusedIn) { const again = box.querySelector('[data-action="' + focusedIn + '"]') || box.querySelector('[data-f]'); Nav.focus(again, { noScroll: true }); }
    // Artwork + Arabic text from the provider / TMDB.
    const info = f.type === 'movie' ? Api.vodInfo(S.sub, it.id) : (S.sub.type === 'xtream' ? Api.seriesInfo(S.sub, it.id).catch(function () { return null; }) : Promise.resolve(null));
    info.then(function (pi) {
      if (!$('#hero') || feat[idx] !== f) return;
      if (pi && pi.backdrop) swapBg($('.hbg.cur'), pi.backdrop);
      if (pi && pi.plot && !it.plot) $('#hplot').textContent = pi.plot;
      return Tmdb.details(f.type, pi && pi.tmdb, it.name, it.year).then(function (tm) {
        if (!tm || !$('#hero') || feat[idx] !== f) return;
        if (tm.backdrop) swapBg($('.hbg.cur'), tm.backdrop);
        $('#htitle').innerHTML = tm.logo ? `<img class="hlogo" src="${esc(tm.logo)}">` : `<h1>${esc(tm.title || Clean.title(it.name))}</h1>`;
        $('#hmeta').innerHTML = (tm.rating ? `<span class="star-inline">★ ${esc(tm.rating)}</span>` : '') + esc([tm.year, tm.genres.slice(0, 2).join(' · ')].filter(Boolean).join('   •   '));
        if (tm.overview) $('#hplot').textContent = tm.overview;
      });
    }).catch(function () {});
    clearInterval(heroTimer);
    // every 5 s, also while Play / Details / + is selected (focus stays on the same button)
    heroTimer = setInterval(function () {
      if (screen.name !== 'home' || S.ui.section !== 'home' || !$('#hero')) { clearInterval(heroTimer); return; }
      if (Nav.idleFor && Nav.idleFor() < 1500) return;              // not in the middle of a key press
      if (document.querySelector('.modal, .saver')) return;
      const hb = $('#hero'); if (hb && hb.getBoundingClientRect().bottom < 0) return;   // scrolled away
      renderHero(feat, (idx + 1) % feat.length);
    }, 5000);
  }

  // ---- matches strip
  function countdown(ms) { const m = Math.max(0, Math.floor(ms / 60000)), h = Math.floor(m / 60); return h ? h + 'h ' + (m % 60) + 'm' : m + 'm'; }
  function loadMatches(showLoading) {
    clearTimeout(matchTimer);
    const box = $('#matches');
    if (!box) return;
    if (showLoading) box.innerHTML = '<div class="mcard sk"></div><div class="mcard sk"></div><div class="mcard sk"></div>';
    const day = matchDay;
    Matches.forDay(day).then(function (list) {
      if (day !== matchDay || !$('#matches')) return;
      matchList = list;
      drawMatches();
      matchTimer = setTimeout(function () { if ($('#matches')) loadMatches(false); }, list.some(Matches.isLive) ? 45000 : 600000);
    }, function () { const b = $('#matches'); if (b) b.innerHTML = `<div class="empty small">${esc(t('noMatches'))}</div>`; });
  }
  function drawMatches() {
    const box = $('#matches');
    if (!box) return;
    const list = matchList || [];
    if (!list.length) { box.innerHTML = `<div class="empty small">${esc(t('noMatches'))}</div>`; return; }
    const focusedId = Nav.current && box.contains(Nav.current) ? Nav.current.getAttribute('data-mid') : null;
    const keepX = box.scrollLeft;
    box.innerHTML = list.map(function (m, i) {
      const open = Matches.canOpen(m), net = Matches.network(m.slug);
      const hc = m.home.color || '#2a2f5a';
      const ac = (m.away.color && m.away.color !== m.home.color ? m.away.color : m.away.alt) || '#4a2a5a';
      const status = Matches.isLive(m) ? `<span class="mst live">● ${esc(m.state === 'ht' ? t('matchHt') : t('matchLive') + ' ' + m.clock)}</span>`
        : m.state === 'post' ? `<span class="mst">${esc(t('matchFt'))}</span>` : m.state === 'postponed' ? `<span class="mst warn">${esc(t('matchPostponed'))}</span>` : '';
      const center = m.state === 'pre' || m.state === 'postponed'
        ? `<b class="mtime">${fmtTime(m.start)}</b><span class="muted">VS</span>` : `<b class="mscore">${esc(m.home.score)} - ${esc(m.away.score)}</b>`;
      const foot = open ? `▶ ${esc(t('watchNow'))}${net ? ' · ' + esc(net) : ''}`
        : m.state === 'pre' ? `🔒 ${esc(t('opensIn', countdown(m.start - 30 * 60000 - Date.now())))}${net ? ' · ' + esc(net) : ''}` : esc(net);
      return `<button data-f data-action="mwatch" data-i="${i}" data-mid="${esc(m.id)}" class="mcard${Matches.isLive(m) ? ' live' : ''}${open ? '' : ' locked'}"
          style="background:linear-gradient(120deg,${hc} 0%,#0b0b14 50%,${ac} 100%)">
        <span class="mwm l">${img(m.home.logo)}</span><span class="mwm r">${img(m.away.logo)}</span><span class="mpitch"></span><span class="mshade"></span>
        <span class="mtop">${m.leagueLogo ? `<span class="mlg">${img(m.leagueLogo)}</span>` : ''}<span class="mleague">${esc(m.league)}</span>${m.fav ? '<span class="mfav">★</span>' : ''}${status}</span>
        <span class="mteams"><span class="mteam"><span class="mlogo">${img(m.home.logo)}</span>${esc(m.home.short)}</span>
          <span class="mcenter">${center}</span>
          <span class="mteam"><span class="mlogo">${img(m.away.logo)}</span>${esc(m.away.short)}</span></span>
        <span class="mfoot${open ? ' on' : ''}">${foot}</span></button>`;
    }).join('');
    box.scrollLeft = keepX;
    if (focusedId) { const again = box.querySelector('[data-mid="' + cssEsc(focusedId) + '"]'); if (again) Nav.focus(again, { noScroll: true }); }
  }
  async function watchMatch(m) {
    if (!Matches.canOpen(m)) { toast(t('opensIn', countdown(m.start - 30 * 60000 - Date.now()))); return; }
    const ranked = Matches.rankChannels(m, (S.data && S.data.live) || [], S.sub.id);
    if (!ranked.length) { toast(t('noChannel')); return; }
    function go(ch, list) { Matches.remember(m, S.sub.id, ch); playLive(ch, list); }
    if (ranked[0].score >= 500) return go(ranked[0].ch, ranked.slice(0, 10).map(function (r) { return r.ch; }));
    // Only the competition's rights holder (Arabic first), not every sports channel.
    const holders = ranked.filter(function (r) { return Matches.isRightsHolder(m, r.ch.name); });
    const pool = (holders.length ? holders : ranked).slice(0, 40);
    let showing = [];
    if (S.sub.type === 'xtream') {
      // The channels' own programme guide says which one is showing this match.
      toast(t('findingChannel'));
      const tokens = Matches.teamTokens(m.home).concat(Matches.teamTokens(m.away));
      const t0 = m.start;
      for (let i = 0; i < pool.length; i += 8) {
        const chunk = pool.slice(i, i + 8);
        const res = await Promise.all(chunk.map(function (r) {
          return Promise.race([Api.shortEpg(S.sub, r.ch.id), new Promise(function (ok) { setTimeout(function () { ok([]); }, 5000); })]).then(function (progs) {
            return (progs || []).some(function (p) {
              const near = !p.start || (p.start <= t0 + 2 * 3600000 && (!p.end || p.end >= t0 - 30 * 60000));
              const txt = ((p.title || '') + ' ' + (p.desc || '')).toLowerCase();
              return near && tokens.some(function (k) { return txt.indexOf(k) >= 0; });
            });
          }, function () { return false; });
        }));
        res.forEach(function (ok, j) { if (ok) showing.push(chunk[j].ch); });
      }
    }
    if (showing.length === 1) return go(showing[0], showing);
    const options = showing.length ? showing : pool.slice(0, 8).map(function (r) { return r.ch; });
    if (options.length === 1) return go(options[0], options);
    const title = (showing.length ? t('pickChannel') : t('notInGuide')) + ' — ' + m.home.short + ' × ' + m.away.short;
    const v = await chooseBox(title, options.map(function (ch, i) { return { value: i, label: ch.name }; }), 0);
    if (v === undefined) return;
    go(options[v], options);
  }

  // ---- teams & competitions
  function showMatchPrefs() {
    let slug = 'ksa.1', teams = null, failed = false;
    function draw() {
      const pr = Matches.prefs();
      const favIds = pr.teams.map(function (x) { return x.id; });
      const pickable = Matches.COMPETITIONS.filter(function (c) { return ['uefa.champions', 'uefa.europa', 'uefa.europa.conf', 'afc.champions', 'eng.fa', 'esp.copa_del_rey'].indexOf(c.slug) < 0; });
      const focusKey = Nav.current && Nav.current.getAttribute('data-k');
      app.innerHTML = `<div class="settings page mprefs">
        <h1>${esc(t('myTeams'))}</h1>
        <button data-f data-action="only" data-k="only" class="set-row">${esc(t('onlyMyTeams'))}<span>${esc(pr.only ? t('on') : t('off'))}</span></button>
        <h3 class="row-title">${esc(t('favTeams'))}</h3>
        <div class="row" data-group>${pr.teams.map(function (tm) {
          return `<button data-f data-action="unfav" data-id="${esc(tm.id)}" data-k="u${esc(tm.id)}" class="chip team"><span class="tl">${img(tm.logo)}</span>${esc(tm.name)} ✕</button>`;
        }).join('') || `<span class="muted">—</span>`}</div>
        <h3 class="row-title">${esc(t('addTeam'))}</h3>
        <div class="row comp-pick" data-group>${pickable.map(function (c) {
          return `<button data-f data-action="tslug" data-s="${c.slug}" data-k="s${c.slug}" class="chip${c.slug === slug ? ' active' : ''}">${esc(Matches.label(c))}</button>`;
        }).join('')}</div>
        <div class="team-grid" data-group>${teams == null ? (failed ? `<p class="error">${esc(t('teamsError'))}</p>` : '<div class="spinner"></div>') : teams.map(function (tm, i) {
          const on = favIds.indexOf(tm.id) >= 0;
          return `<button data-f data-action="tpick" data-i="${i}" data-k="t${esc(tm.id)}" class="tcard${on ? ' on' : ''}"><span class="tl big">${img(tm.logo)}</span><b>${esc(tm.name)}</b>${on ? '<span class="tchk">✓</span>' : ''}</button>`;
        }).join('')}</div>
        <h3 class="row-title">${esc(t('clubComps'))}</h3>
        <div class="row" data-group>${Matches.COMPETITIONS.filter(function (c) { return !c.nat; }).map(compChip).join('')}</div>
        <h3 class="row-title">${esc(t('natComps'))}</h3>
        <div class="row" data-group>${Matches.COMPETITIONS.filter(function (c) { return c.nat; }).map(compChip).join('')}</div>
      </div>`;
      function compChip(c) {
        const on = pr.comps.indexOf(c.slug) >= 0;
        return `<button data-f data-action="comp" data-s="${c.slug}" data-k="c${c.slug}" class="chip${on ? ' active' : ''}">${on ? '✓ ' : ''}${esc(Matches.label(c))}</button>`;
      }
      const again = focusKey && $('[data-k="' + cssEsc(focusKey) + '"]');
      if (again) Nav.focus(again); else Nav.focusFirst();
    }
    function load() {
      teams = null; failed = false; draw();
      const want = slug;
      Matches.teams(slug).then(function (l) { if (want === slug && screen.name === 'mprefs') { teams = l; draw(); } },
        function () { if (want === slug && screen.name === 'mprefs') { failed = true; teams = null; draw(); } });
    }
    setScreen('mprefs', function () {
      actions.only = async function () { Matches.prefs().only = !Matches.prefs().only; await Matches.save(); draw(); };
      actions.unfav = async function (el) {
        const tm = Matches.prefs().teams.filter(function (x) { return x.id === el.getAttribute('data-id'); })[0];
        if (tm) { await Matches.toggleTeam(tm); draw(); }
      };
      actions.tslug = function (el) { slug = el.getAttribute('data-s'); load(); };
      actions.tpick = async function (el) { await Matches.toggleTeam(teams[parseInt(el.getAttribute('data-i'), 10)]); draw(); };
      actions.comp = async function (el) { await Matches.toggleComp(el.getAttribute('data-s')); draw(); };
      load();
    }, { back: function () { matchList = null; showHome(); } });
  }

  // ---- trailer: opens in the TV's YouTube app (TMDB trailer, or a YouTube search for Arabic / Turkish titles TMDB lacks)
  const YOUTUBE_APP = '111299001912';
  /** What to search on YouTube, and the names the video's title must contain (so we never pick an unrelated clip). */
  /** One season's trailer: TMDB season videos, else a YouTube search that must name that season. */
  async function showSeasonTrailer(tm, item, n) { showTrailer(tm, item, n); }
  /**
   * What to search on YouTube, in the work's original language (Turkish series → "<Turkish name> fragman"),
   * and the names the video's title must contain.
   */
  function trailerQuery(tm, item, seasonNo) {
    const name = (tm && (tm.original || tm.title)) || Clean.title(item.name);
    const year = String((tm && tm.year) || item.year || '').slice(0, 4);
    const series = !!(tm && tm.seasons) || (curDetail && curDetail.type === 'series');
    const arabic = /[\u0600-\u06FF]/.test(name);
    const lang = (tm && tm.lang) || (arabic ? 'ar' : '');
    let q;
    if (lang === 'tr') q = seasonNo ? name + ' ' + seasonNo + '. sezon fragman' : name + (series ? ' 1. bölüm fragmanı' : ' fragman');
    else if (lang === 'ar' || arabic) {
      // "اعلان" alone also means a TV commercial — say what it is: مسلسل / فيلم + برومو / اعلان رسمي
      q = seasonNo ? 'مسلسل ' + name + ' الموسم ' + seasonNo + ' برومو' : series ? 'مسلسل ' + name + ' برومو' : 'فيلم ' + name + ' ' + year + ' اعلان رسمي';
    } else q = seasonNo ? name + ' season ' + seasonNo + ' official trailer' : name + ' ' + year + (series ? ' series' : '') + ' official trailer';
    const names = [name, tm && tm.title, Clean.title(item.name)].filter(Boolean);
    return { q: q.replace(/\s+/g, ' ').trim(), names: names, season: seasonNo || 0, lang: lang, series: series };
  }
  function launchYouTube(values, onFail) {
    try {
      if (!(window.tizen && tizen.application && tizen.ApplicationControl)) throw new Error('no tizen');
      const data = new tizen.ApplicationControlData('PAYLOAD', [JSON.stringify({ values: values })]);
      const ctl = new tizen.ApplicationControl('http://tizen.org/appcontrol/operation/view', null, null, null, [data]);
      tizen.application.launchAppControl(ctl, YOUTUBE_APP, function () {}, function () { onFail(); });
    } catch (e) { onFail(); }
  }
  // In-app player: YouTube's official embedded player on a small web page (docs/trailer.html on GitHub Pages),
  // because YouTube refuses embeds from apps without a web address. Falls back to the YouTube app.
  const PLAYER_PAGE = 'https://mohammid2030-del.github.io/Y/trailer.html';
  /** The title's (or season's) official trailer: TMDB's in the original language first, else a checked YouTube search. */
  async function showTrailer(tm, item, seasonNo) {
    toast(t('findingTrailer'));
    const q = trailerQuery(tm, item, seasonNo);
    const type = curDetail && curDetail.type === 'series' ? 'series' : (tm && tm.seasons ? 'series' : 'movie');
    let keys = tm && tm.id ? await Tmdb.trailers(type, tm.id, tm.lang, seasonNo) : [];
    if (!seasonNo && tm && tm.trailers) keys = keys.concat(tm.trailers.filter(function (k) { return keys.indexOf(k) < 0; }));
    keys = keys.map(function (x) { return String(x || '').replace(/[^A-Za-z0-9_-]/g, ''); }).filter(Boolean);
    const k = await YouTube.pick(keys, q);
    if (k) { embedTrailer(k); return; }
    toast(t('noTrailer'));   // nothing that really is this title's official trailer: say so instead of showing a random video
  }
  function embedTrailer(k) {
    const prev = screen;
    const box = document.createElement('div');
    box.className = 'trailer';
    box.innerHTML = `<iframe src="${PLAYER_PAGE}?v=${k}&hl=${I18n.lang}" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
      <div class="trailer-hint">${esc(t('back'))} ↩</div><div class="trailer-wait spinner"></div>`;
    document.body.appendChild(box);
    const prevBack = screen.back;
    let started = false, closed = false, playing = false, readyAt = 0;   // ready but never playing in 9 s → YouTube app
    function close() {
      if (closed) return; closed = true;
      window.removeEventListener('blur', refocus); window.removeEventListener('message', onMsg);
      clearTimeout(guard);
      // unload YouTube's player first: the TV has one video decoder and a lingering player keeps it (sound but no picture later)
      const fr = box.querySelector('iframe'); if (fr) { try { fr.src = 'about:blank'; } catch (e) { /* */ } }
      if (box.parentNode) box.parentNode.removeChild(box);
      if (screen === prev) screen.back = prevBack;
    }
    // why the in-app player gave up (shown briefly, then the YouTube app): no-player / not-playing / YouTube error code
    function toApp(why) { close(); toast(t('trailerInYouTube') + (why ? ' (' + why + ')' : '')); launchYouTube('v=' + k, function () { toast(t('noTrailer')); }); }
    function onMsg(e) {
      let d = null; try { d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data; } catch (x) { return; }
      if (!d || d.nova !== 'trailer') return;
      if (d.type === 'ready' && !readyAt) { readyAt = Date.now(); setTimeout(function () { if (!playing && !closed) toApp('not-playing'); }, 9000); }
      if (d.type === 'ready' || d.type === 'playing') { started = true; const w = box.querySelector('.trailer-wait'); if (w) w.remove(); }
      if (d.type === 'playing') playing = true;
      if (d.type === 'ended') close();
      if (d.type === 'error') toApp('yt-' + d.extra);   // the video doesn't allow embedding (or the page isn't up): YouTube app
    }
    window.addEventListener('message', onMsg);
    // nothing from the player in 12 s: open it in the YouTube app instead
    const guard = setTimeout(function () { if (!started && !closed) toApp('no-player'); }, 15000);
    // keep the remote's BACK key with the app even if the embedded player grabs focus
    function refocus() { setTimeout(function () { try { window.focus(); } catch (e) { /* */ } }, 50); }
    window.addEventListener('blur', refocus);
    screen.back = close;
  }

  /** Text entry dialog (TV keyboard). */
  function inputBox(title, hint) {
    return new Promise(function (resolve) {
      const html = `<h2>${esc(title)}</h2><p class="muted">${esc(hint || '')}</p>
        <input data-f id="inbox" class="input big" autocomplete="off">
        <div class="row center"><button data-f data-m="ok" class="btn primary">${esc(t('save'))}</button><button data-f data-m="cancel" class="btn">${esc(t('cancel'))}</button></div>`;
      const md = modal(html, function (a, close) {
        const v = $('#inbox') ? $('#inbox').value : '';
        close(); resolve(a === 'ok' ? v.trim() : '');
      }, { onCancel: function () { resolve(''); } });
      const input = md.el.querySelector('#inbox');
      input.addEventListener('keydown', function (e) {
        if (e.keyCode === 13 || e.keyCode === 65376) { e.preventDefault(); input.blur(); const ok = md.el.querySelector('[data-m="ok"]'); if (ok) Nav.focus(ok); }
      });
    });
  }

  // ------------------------------------------------------------------ player
  function playLive(ch, list) {
    const ctx = (list && list.length ? list : [ch]);
    startPlayer({ kind: 'live', item: ch, title: ch.name, logo: ch.logo, list: ctx });
  }
  function playVod(req) { startPlayer(req); }

  function startPlayer(req) {
    const returnTo = req.returnTo || function () { showHome(); };
    let overlayTimer = null, retries = 0, retryTimer = null, saveTimer = null, epgTimerP = null, numBuf = '', numTimer = null;
    let cur = req, opening = false;

    Saver.stop();
    setScreen('player', function () {
      document.body.classList.add('playing'); document.documentElement.classList.add('playing');
      app.innerHTML = `<div class="player-ui">
        <div class="osd top" id="osd-top"></div>
        <div class="center-msg" id="center-msg"></div>
        <div class="subtitle" id="subtitle"></div>
        <div class="num-entry" id="num-entry"></div>
        <div class="osd bottom" id="osd-bottom">
          <div class="vod-bar" id="vod-bar"><span id="t-cur">0:00</span><div class="track"><div id="t-fill"></div></div><span id="t-dur">0:00</span></div>
          <div class="row" data-group>
            <button data-f data-action="pp" class="btn round" id="btn-pp">❚❚</button>
            <button data-f data-action="audio" class="btn">${esc(t('audio'))}</button>
            <button data-f data-action="subs" class="btn">${esc(t('subtitles'))}</button>
            <button data-f data-action="aspect" class="btn">${esc(t('aspect'))}</button>
            <span class="hint" id="hint"></span>
          </div>
        </div>
      </div>`;
      actions.pp = function () { Player.togglePause(); updatePP(); showOsd(); };
      actions.audio = async function () {
        const tr = Player.tracks('AUDIO');
        if (!tr.length) return;
        const v = await chooseBox(t('audio'), tr.map(function (x) { return { value: x.index, label: x.label }; }));
        if (v !== undefined) Player.selectTrack('AUDIO', v);
      };
      actions.subs = async function () {
        const tr = Player.tracks('TEXT');
        const opts = [{ value: -1, label: t('off') }].concat(tr.map(function (x) { return { value: x.index, label: x.label }; }));
        const v = await chooseBox(t('subtitles'), opts);
        if (v !== undefined) Player.selectTrack('TEXT', v);
      };
      actions.aspect = function () {
        const i = Player.cycleAspect();
        toast(t(['aspectFit', 'aspectFill', 'aspectAuto'][i]));
      };
    }, {
      back: function () {
        if (osdVisible() && Nav.current && Nav.current.closest('.osd')) { hideOsd(); return; }
        exit();
      },
      key: onKey,
      onVisibility: function (hidden) {
        if (hidden) { saveProgress(); Player.stop(); }
        else open(cur, cur.kind === 'live' ? 0 : Math.max(0, lastPos - 3000));
      }
    });

    let lastPos = 0;
    Player.on('time', function (ms) {
      lastPos = ms;
      if (cur.kind === 'live' || !osdVisible()) return;
      const d = Player.duration();
      $('#t-cur').textContent = fmtDur(ms);
      $('#t-dur').textContent = fmtDur(d);
      $('#t-fill').style.width = (d ? Math.min(100, ms / d * 100) : 0) + '%';
    });
    Player.on('buffering', function (on) { msg(on ? '<div class="spinner"></div>' : ''); if (!on) retries = 0; });
    Player.on('subtitle', function (text) { const s = $('#subtitle'); if (s) s.innerHTML = esc(text).replace(/\n/g, '<br>'); });
    Player.on('ended', function () {
      if (cur.kind === 'live') return reconnect();
      saveProgress(true);
      exit();
    });
    Player.on('error', function () { reconnect(); });

    function osdVisible() { return app.querySelector('.player-ui.osd-on') !== null; }
    function showOsd(focusControls) {
      const ui = $('.player-ui');
      if (!ui) return;
      ui.classList.add('osd-on');
      renderTop();
      updatePP();
      $('#vod-bar').style.display = cur.kind === 'live' ? 'none' : '';
      $('#hint').textContent = cur.kind === 'live' ? t('keysHint') : Player.info();
      if (focusControls && !(Nav.current && Nav.current.closest('.osd'))) Nav.focus($('#btn-pp'));
      clearTimeout(overlayTimer);
      overlayTimer = setTimeout(hideOsd, 6000);
    }
    function hideOsd() {
      const ui = $('.player-ui');
      if (ui) ui.classList.remove('osd-on');
      if (Nav.current) Nav.current.classList.remove('focused');
      Nav.reset();
    }
    function updatePP() { const b = $('#btn-pp'); if (b) b.textContent = Player.state() === 'PAUSED' ? '▶' : '❚❚'; }
    function msg(html) { const m = $('#center-msg'); if (m) m.innerHTML = html; }

    let epgNow = null, epgNext = null;
    function renderTop() {
      const top = $('#osd-top');
      if (!top) return;
      const isLive = cur.kind === 'live';
      const num = isLive && cur.item.num ? cur.item.num + '  ' : '';
      let epgHtml = '';
      if (isLive && epgNow) {
        const now = Date.now(), pct = Math.min(100, Math.max(0, (now - epgNow.start) / (epgNow.end - epgNow.start) * 100));
        epgHtml = `<div class="epg-now">${fmtTime(epgNow.start)}–${fmtTime(epgNow.end)} · ${esc(epgNow.title)}</div>
          <div class="mini-progress"><div style="width:${pct}%"></div></div>
          ${epgNext ? `<div class="muted">${esc(t('next'))}: ${fmtTime(epgNext.start)} ${esc(epgNext.title)}</div>` : ''}`;
      }
      top.innerHTML = `<div class="osd-logo">${img(cur.logo)}</div><div class="osd-text">
        <div>${isLive ? `<span class="badge red">${esc(t('liveBadge'))}</span>` : ''} <b class="osd-title">${esc(num + cur.title)}</b></div>
        ${cur.subtitle ? `<div class="muted">${esc(cur.subtitle)}</div>` : ''}${epgHtml}</div>`;
    }

    function loadEpg() {
      epgNow = epgNext = null;
      clearTimeout(epgTimerP);
      if (cur.kind !== 'live' || S.sub.type !== 'xtream') return;
      const item = cur.item;
      Api.shortEpg(S.sub, item.id).then(function (list) {
        if (cur.item !== item) return;
        const now = Date.now();
        epgNow = list.find(function (x) { return x.start <= now && x.end > now; }) || null;
        epgNext = list.find(function (x) { return x.start > now; }) || null;
        if (osdVisible()) renderTop();
      });
      epgTimerP = setTimeout(loadEpg, 120000);
    }

    function historyKeyFor(r) { return hkey(r.kind === 'catchup' ? 'live' : r.kind, r.item.id, r.episode ? r.episode.id : ''); }

    async function open(r, startMs) {
      opening = true;
      clearTimeout(retryTimer);
      msg('<div class="spinner"></div>');
      const url = Api.streamUrl(S.sub, r.kind === 'catchup' ? 'live' : r.kind, r.item, {
        episodeId: r.episode && r.episode.id, ext: r.episode && r.episode.ext,
        catchupStart: r.catchup && r.catchup.start, duration: r.catchup && r.catchup.duration
      });
      try {
        await Player.open(url, { userAgent: S.sub.ua, startMs: startMs, bufferSec: S.settings.bufferSec });
        msg('');
        retries = 0;
      } catch (e) {
        reconnect();
      } finally {
        opening = false;
      }
    }

    function reconnect() {
      if (screen.name !== 'player') return;
      if (retries >= 5) {
        msg(`<div class="err-box"><h2>${esc(t('errPlayback'))}</h2><p class="muted">${esc(t('errPlaybackHint'))}</p>
          <div class="row center"><button data-f data-action="pRetry" class="btn primary">${esc(t('retry'))}</button>
          <button data-f data-action="pBack" class="btn">${esc(t('back'))}</button></div></div>`);
        actions.pRetry = function () { retries = 0; open(cur, cur.kind === 'live' ? 0 : lastPos); };
        actions.pBack = exit;
        Nav.focus($('[data-action="pRetry"]'));
        return;
      }
      retries++;
      msg(`<div class="reconnect"><div class="spinner small"></div>${esc(t('reconnecting', retries))}</div>`);
      clearTimeout(retryTimer);
      retryTimer = setTimeout(function () { open(cur, cur.kind === 'live' ? 0 : lastPos); }, Math.min(15000, 1000 * Math.pow(2, retries)));
    }

    function begin(r) {
      saveProgress();
      cur = r;
      lastPos = 0;
      retries = 0;
      let start = 0;
      const key = historyKeyFor(r);
      const h = S.history[key];
      if (r.kind !== 'live' && r.kind !== 'catchup' && !r.fromStart && h && h.pos > 30000 && (!h.dur || h.pos < h.dur * 0.95)) start = h.pos;
      if (r.kind === 'live') {
        S.history[key] = { sub: owner(), type: 'live', id: r.item.id, ep: '', title: r.item.name, logo: r.item.logo, pos: 0, dur: 0, t: Date.now(), item: r.item };
        saveHistory();
        pushHistory(S.history[key]);
      }
      open(r, start);
      loadEpg();
      showOsd(false);
      clearInterval(saveTimer);
      if (r.kind === 'movie' || r.kind === 'series') saveTimer = setInterval(saveProgress, 10000);
    }

    let lastCloudSave = 0;
    function saveProgress(finished) {
      if (!cur || (cur.kind !== 'movie' && cur.kind !== 'series')) return;
      const d = Player.duration(), pos = finished ? d : (lastPos || Player.time());
      if (!d || !pos) return;
      const key = historyKeyFor(cur);
      S.history[key] = {
        sub: owner(), type: cur.kind, id: cur.item.id, ep: cur.episode ? cur.episode.id : '', ext: cur.episode ? cur.episode.ext : '',
        title: cur.title, subtitle: cur.subtitle || '', logo: cur.logo, pos: pos, dur: d, t: Date.now(), item: cur.item
      };
      saveHistory();
      if (finished || Date.now() - lastCloudSave > 30000) { lastCloudSave = Date.now(); pushHistory(S.history[key]); }
    }

    function zap(delta) {
      const list = cur.list || [cur.item];
      if (cur.kind !== 'live' || list.length < 2) return;
      let i = list.findIndex(function (c) { return c.id === cur.item.id; });
      i = (i + delta + list.length) % list.length;
      const ch = list[i];
      begin({ kind: 'live', item: ch, title: ch.name, logo: ch.logo, list: list });
    }

    function numberKey(d) {
      if (cur.kind !== 'live') return;
      numBuf = (numBuf + d).slice(-4);
      $('#num-entry').textContent = numBuf;
      clearTimeout(numTimer);
      numTimer = setTimeout(function () {
        const n = parseInt(numBuf, 10);
        numBuf = '';
        $('#num-entry').textContent = '';
        const all = (S.data && S.data.live) || [];
        const ch = all.find(function (c) { return c.num === n; });
        if (ch) begin({ kind: 'live', item: ch, title: ch.name, logo: ch.logo, list: cur.list });
      }, 1500);
    }

    function onKey(code) {
      const osdFocused = osdVisible() && Nav.current && (Nav.current.closest('.osd') || Nav.current.closest('.err-box'));
      if (code >= 48 && code <= 57) { numberKey(String(code - 48)); return true; }
      switch (code) {
        case K.CH_UP: zap(1); return true;
        case K.CH_DOWN: zap(-1); return true;
        case K.PLAY_PAUSE: case K.PLAY: case K.PAUSE: Player.togglePause(); updatePP(); showOsd(); return true;
        case K.STOP: exit(); return true;
        case K.FF: if (cur.kind !== 'live') { Player.seekBy(30000); showOsd(); } return true;
        case K.RW: if (cur.kind !== 'live') { Player.seekBy(-30000); showOsd(); } return true;
        case K.INFO: if (osdVisible()) hideOsd(); else showOsd(true); return true;
        case K.RED: actions.audio(); return true;
        case K.GREEN: actions.subs(); return true;
        case K.YELLOW: actions.aspect(); return true;
      }
      if (osdFocused) { showOsd(); return false; } // let spatial navigation handle controls
      if (app.querySelector('.err-box')) return false;
      if (code === K.UP || code === K.DOWN) {
        if (cur.kind === 'live') { zap(code === K.UP ? 1 : -1); return true; }
        showOsd(true); return true;
      }
      if (code === K.LEFT || code === K.RIGHT) {
        if (cur.kind !== 'live') {
          const rtl = I18n.lang === 'ar';
          const forward = (code === K.RIGHT) !== rtl;
          Player.seekBy(forward ? 10000 : -10000); showOsd(); return true;
        }
        showOsd(true); return true;
      }
      if (code === K.ENTER) { showOsd(true); return true; }
      return false;
    }

    function exit() {
      saveProgress();
      clearTimeout(overlayTimer); clearTimeout(retryTimer); clearInterval(saveTimer); clearTimeout(epgTimerP); clearTimeout(numTimer);
      ['time', 'buffering', 'subtitle', 'ended', 'error'].forEach(function (n) { Player.on(n, null); });
      Player.stop();
      document.body.classList.remove('playing'); document.documentElement.classList.remove('playing');
      if (opening) { /* ignore late callbacks */ }
      returnTo();
    }

    begin(req);
  }

  // ------------------------------------------------------------------ start
  window.addEventListener('load', function () {
    boot().catch(function (e) {
      app.innerHTML = '<div class="center-col"><h2>NOVA IPTV</h2><p class="error">' + esc(String(e && e.message || e)) + '</p></div>';
    });
  });
})();
