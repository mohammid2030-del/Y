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
    ui: { section: 'live', cat: { live: '*all', movie: '*all', series: '*all' }, focusKey: null, search: { q: '', type: '' } }
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
    return url ? `<img class="${cls || ''}" src="${esc(url)}" onerror="this.style.visibility='hidden'" alt="">` : '';
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
    document.addEventListener('visibilitychange', onVisibility);
    if (!S.settings.onboarded) return showWelcome();
    if (!S.subs.length) return showAddSub();
    await activate(S.settings.activeSub || S.subs[0].id);
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
    showHome();
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
      actions.next = async function () { S.settings.onboarded = true; await saveSettings(); showAddSub(); };
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
        if (!existing) S.subs.push(record);
        await saveSubs();
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
        setTimeout(showHome, 300);
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
    if (cat === '*fav') return S.fav.filter(function (f) { return f.sub === S.sub.id && f.type === type; }).map(function (f) { return f.item; });
    if (cat === '*recent') {
      if (type === 'live') {
        return Object.keys(S.history).map(function (k) { return S.history[k]; })
          .filter(function (h) { return h.sub === S.sub.id && h.type === 'live'; })
          .sort(function (a, b) { return b.t - a.t; }).slice(0, 50).map(function (h) { return h.item; });
      }
      return all.filter(function (i) { return !S.hidden[type + '|' + i.cat]; }).slice().sort(function (a, b) { return (b.added || 0) - (a.added || 0); }).slice(0, 200);
    }
    return all.filter(function (i) { return i.cat === cat; });
  }
  function isFav(type, id) { return S.fav.some(function (f) { return f.sub === S.sub.id && f.type === type && f.id === id; }); }
  function toggleFav(type, item) {
    const i = S.fav.findIndex(function (f) { return f.sub === S.sub.id && f.type === type && f.id === item.id; });
    if (i >= 0) S.fav.splice(i, 1);
    else S.fav.push({ sub: S.sub.id, type: type, id: item.id, name: item.name, logo: item.logo, item: item });
    saveFav();
    return i < 0;
  }
  function hkey(type, id, ep) { return S.sub.id + '|' + type + '|' + id + '|' + (ep || ''); }
  function continueList(type) {
    return Object.keys(S.history).map(function (k) { return S.history[k]; }).filter(function (h) {
      return h.sub === S.sub.id && h.type === type && h.pos > 30000 && (!h.dur || h.pos < h.dur * 0.95);
    }).sort(function (a, b) { return b.t - a.t; }).slice(0, 20);
  }

  /** Renders a long list incrementally: only `batch` items at a time, more when focus nears the end. */
  function windowed(container, list, renderItem, bind, batch) {
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
    }
    more();
    return { more: more, get rendered() { return rendered; } };
  }

  // ------------------------------------------------------------------ home
  const SECTIONS = [
    { id: 'live', icon: '📺' }, { id: 'movie', icon: '🎬', label: 'movies' }, { id: 'series', icon: '🎞', label: 'series' },
    { id: 'favorites', icon: '★' }, { id: 'search', icon: '🔍' }, { id: 'settings', icon: '⚙' }
  ];

  function showHome(section) {
    if (section) S.ui.section = section;
    setScreen('home', function () {
      app.innerHTML = `<div class="home">
        <nav class="sidebar" data-group>
          <div class="logo small"></div>
          ${SECTIONS.map(function (s) {
            return `<button data-f data-action="section" data-sec="${s.id}" class="side-item${S.ui.section === s.id ? ' active' : ''}">
              <span class="ico">${s.icon}</span><span class="txt">${esc(t(s.label || s.id))}</span></button>`;
          }).join('')}
          <div class="sub-name">${esc(S.sub ? S.sub.name : '')}</div>
        </nav>
        <main id="content" class="content"></main>
      </div>`;
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
        const inSidebar = Nav.current && Nav.current.closest('.sidebar');
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
    if (sec === 'live') renderLive(c);
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
  function renderVod(c, type) {
    const cont = S.ui.cat[type] === '*all' ? continueList(type) : [];
    c.innerHTML = `<div class="vod-layout">
      <div class="cat-row" data-group>${catColumn(type)}</div>
      <div class="vod-scroll" id="vod-scroll">
        ${cont.length ? `<h3 class="row-title">${esc(t('continueWatching'))}</h3><div class="cont-row" data-group>` + cont.map(function (h, i) {
          const pct = h.dur ? Math.round(h.pos / h.dur * 100) : 0;
          return `<button data-f data-action="resume" data-h="${esc(hkey(h.type, h.id, h.ep))}" class="poster wide" data-key="cont:${i}">
            ${img(h.logo)}<span class="ptitle">${esc(h.title)}</span><span class="psub">${esc(h.subtitle || '')}</span>
            <span class="bar"><span style="width:${pct}%"></span></span></button>`;
        }).join('') + '</div>' : ''}
        <div class="grid" id="vod-grid" data-group></div>
      </div>
    </div>`;
    actions.cat = function (el) {
      S.ui.cat[type] = el.getAttribute('data-cat');
      renderSection(false);
      const sel = $('.cat-row .cat.active');
      if (sel) Nav.focus(sel);
    };
    actions.resume = function (el) {
      const h = S.history[el.getAttribute('data-h')];
      if (h) resumeHistory(h);
    };
    const list = itemsFor(type, S.ui.cat[type]);
    const grid = $('#vod-grid');
    if (!list.length && !cont.length) {
      grid.outerHTML = `<div class="empty">${esc(t(S.ui.cat[type] === '*fav' ? 'emptyFavorites' : 'emptyContent'))}</div>`;
      return;
    }
    actions.open = function (el) {
      const item = list[parseInt(el.getAttribute('data-i'), 10)];
      S.ui.focusKey = type + ':' + item.id;
      if (type === 'movie') showMovie(item); else showSeries(item);
    };
    windowed(grid, list, function (it, i) {
      return `<button data-f data-i="${i}" data-key="${type}:${esc(it.id)}" data-action="open" class="poster">
        <span class="pimg">${img(it.logo)}${isFav(type, it.id) ? '<span class="star">★</span>' : ''}</span>
        <span class="ptitle">${esc(it.name)}</span>
        <span class="psub">${esc([it.year, it.rating ? '★ ' + it.rating : ''].filter(Boolean).join('  '))}</span>
      </button>`;
    }, function (el, it) { S.ui.focusKey = type + ':' + it.id; }, 48);
  }

  function resumeHistory(h) {
    if (h.type === 'movie') playVod({ kind: 'movie', item: h.item, title: h.title, logo: h.logo });
    else playVod({ kind: 'series', item: h.item, title: h.title, logo: h.logo, episode: { id: h.ep, ext: h.ext }, subtitle: h.subtitle });
  }

  async function showMovie(item) {
    setScreen('detail', function () {
      app.innerHTML = detailHtml(item, null);
      bindMovie(item, null);
    }, { back: function () { showHome(); } });
    const info = await Api.vodInfo(S.sub, item.id);
    if (screen.name === 'detail' && info) {
      const focusAction = Nav.current && Nav.current.getAttribute('data-action');
      app.innerHTML = detailHtml(item, info);
      bindMovie(item, info);
      const again = focusAction && $('[data-action="' + focusAction + '"]');
      if (again) Nav.focus(again);
    }
  }

  function detailHtml(item, info) {
    const h = S.history[hkey('movie', item.id)];
    const canResume = h && h.pos > 30000 && (!h.dur || h.pos < h.dur * 0.95);
    const meta = [item.year || (info && info.year), info && info.duration, info && info.genre].filter(Boolean).join(' • ');
    const backdrop = (info && info.backdrop) || '';
    return `<div class="detail">
      ${backdrop ? `<div class="backdrop" style="background-image:url('${esc(backdrop)}')"></div>` : ''}
      <div class="detail-body">
        <div class="dposter">${img((info && info.image) || item.logo)}</div>
        <div class="dinfo">
          <h1>${esc(item.name)}</h1>
          <div class="meta">${item.rating || (info && info.rating) ? `<span class="badge g">★ ${esc(item.rating || info.rating)}</span>` : ''} ${esc(meta)}</div>
          <div class="row" data-group>
            <button data-f data-autofocus data-action="play" class="btn primary">▶ ${esc(t(canResume ? 'resume' : 'play'))}</button>
            ${canResume ? `<button data-f data-action="restart" class="btn">↺ ${esc(t('startOver'))}</button>` : ''}
            <button data-f data-action="fav" class="btn">${isFav('movie', item.id) ? '★ ' + esc(t('removeFav')) : '☆ ' + esc(t('addFav'))}</button>
          </div>
          ${canResume && h.dur ? `<div class="mini-progress wide"><div style="width:${Math.round(h.pos / h.dur * 100)}%"></div></div>` : ''}
          <p class="plot">${esc((info && info.plot) || '')}</p>
          ${info && info.cast ? `<p class="muted">${esc(info.cast)}</p>` : ''}
          ${info && info.director ? `<p class="muted">${esc(info.director)}</p>` : ''}
        </div>
      </div></div>`;
  }

  function bindMovie(item, info) {
    actions.play = function () { playVod({ kind: 'movie', item: item, title: item.name, logo: (info && info.image) || item.logo }); };
    actions.restart = function () { playVod({ kind: 'movie', item: item, title: item.name, logo: item.logo, fromStart: true }); };
    actions.fav = function (el) {
      const added = toggleFav('movie', item);
      el.textContent = added ? '★ ' + t('removeFav') : '☆ ' + t('addFav');
    };
    Nav.focusFirst();
  }

  async function showSeries(item, keepSeason) {
    let info = null, season = keepSeason || null, error = null;
    function render() {
      setScreen('detail', function () {
        const seasons = info ? Object.keys(info.seasons).map(Number).sort(function (a, b) { return a - b; }) : [];
        if (info && (season == null || seasons.indexOf(season) < 0)) season = seasons[0];
        const eps = info && season != null ? info.seasons[season] : [];
        const last = Object.keys(S.history).map(function (k) { return S.history[k]; })
          .filter(function (h) { return h.sub === S.sub.id && h.type === 'series' && h.id === item.id; })
          .sort(function (a, b) { return b.t - a.t; })[0];
        app.innerHTML = `<div class="detail series">
          <div class="detail-body">
            <div class="dposter">${img((info && info.cover) || item.logo)}</div>
            <div class="dinfo">
              <h1>${esc(item.name)}</h1>
              <div class="meta">${esc([item.year || (info && info.year), info && info.genre].filter(Boolean).join(' • '))}</div>
              <div class="row" data-group>
                ${last ? `<button data-f data-autofocus data-action="cont" class="btn primary">▶ ${esc(t('resume'))} ${esc((last.subtitle || '').split(' —')[0])}</button>` : ''}
                <button data-f data-action="fav" class="btn">${isFav('series', item.id) ? '★ ' + esc(t('removeFav')) : '☆ ' + esc(t('addFav'))}</button>
              </div>
              <p class="plot short">${esc((info && info.plot) || item.plot || '')}</p>
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
            return `<button data-f data-action="ep" data-i="${i}" class="episode"${!last && i === 0 ? ' data-autofocus' : ''}>
              <span class="eimg">${img(e.image || item.logo)}${pct ? `<span class="bar"><span style="width:${pct}%"></span></span>` : ''}</span>
              <span class="etxt"><b>${e.num}. ${esc(e.title || t('episode', e.num))}</b>
              <span class="muted">${e.duration ? esc(t('minutes', Math.round(e.duration / 60))) : ''}</span>
              <span class="edesc">${esc(e.plot)}</span></span></button>`;
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
        Nav.focusFirst();
      }, { back: function () { showHome(); } });
    }
    function load() {
      error = null; info = null; render();
      if (S.sub.type !== 'xtream') { error = { kind: 'unknown' }; render(); return; }
      Api.seriesInfo(S.sub, item.id).then(function (i) { info = i; if (screen.name === 'detail') render(); },
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
    const mine = S.fav.filter(function (f) { return f.sub === S.sub.id; });
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

  function runSearch() {
    const st = S.ui.search, box = $('#results');
    if (!box) return;
    const q = st.q.trim().toLowerCase();
    if (q.length < 2) { box.innerHTML = `<div class="empty">${esc(t('searchPrompt'))}</div>`; return; }
    const out = [];
    ['live', 'movie', 'series'].forEach(function (type) {
      if (st.type && st.type !== type) return;
      const all = (S.data && S.data[type]) || [];
      for (let i = 0; i < all.length && out.length < 300; i++) {
        const it = all[i];
        if (S.hidden[type + '|' + it.cat]) continue;
        if (it.name.toLowerCase().indexOf(q) >= 0) out.push({ type: type, item: it });
      }
    });
    out.sort(function (a, b) {
      const ai = a.item.name.toLowerCase().indexOf(q) === 0 ? 0 : 1, bi = b.item.name.toLowerCase().indexOf(q) === 0 ? 0 : 1;
      return ai - bi;
    });
    if (!out.length) { box.innerHTML = `<div class="empty">${esc(t('noResults'))}</div>`; return; }
    box.innerHTML = out.map(function (r, i) {
      return `<button data-f data-i="${i}" data-action="openResult" class="poster${r.type === 'live' ? ' channel' : ''}">
        <span class="pimg">${img(r.item.logo)}</span><span class="ptitle">${esc(r.item.name)}</span>
        <span class="psub">${esc(r.type === 'live' ? t('live') : r.type === 'movie' ? t('movies') : t('series'))}</span></button>`;
    }).join('');
    actions.openResult = function (el) {
      const r = out[parseInt(el.getAttribute('data-i'), 10)];
      if (r.type === 'live') playLive(r.item, [r.item]);
      else if (r.type === 'movie') showMovie(r.item);
      else showSeries(r.item);
    };
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
      <h3 class="row-title">${esc(t('settings'))}</h3>
      <button data-f data-action="lang" class="set-row">${esc(t('language'))}<span>${I18n.lang === 'ar' ? 'العربية' : 'English'}</span></button>
      <button data-f data-action="buffer" class="set-row">${esc(t('buffer'))}<span>${esc(t('seconds', S.settings.bufferSec))}</span></button>
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
    actions.subDel = async function (el) {
      const id = sid(el);
      if (!(await confirmBox(t('confirmDelete'), t('remove')))) return;
      S.subs = S.subs.filter(function (s) { return s.id !== id; });
      S.fav = S.fav.filter(function (f) { return f.sub !== id; });
      Object.keys(S.history).forEach(function (k) { if (S.history[k].sub === id) delete S.history[k]; });
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
    actions.clearHistory = async function () {
      Object.keys(S.history).forEach(function (k) { if (S.history[k].sub === S.sub.id) delete S.history[k]; });
      await Store.set('history', S.history);
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

    setScreen('player', function () {
      document.body.classList.add('playing');
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
        S.history[key] = { sub: S.sub.id, type: 'live', id: r.item.id, ep: '', title: r.item.name, logo: r.item.logo, pos: 0, dur: 0, t: Date.now(), item: r.item };
        saveHistory();
      }
      open(r, start);
      loadEpg();
      showOsd(false);
      clearInterval(saveTimer);
      if (r.kind === 'movie' || r.kind === 'series') saveTimer = setInterval(saveProgress, 10000);
    }

    function saveProgress(finished) {
      if (!cur || (cur.kind !== 'movie' && cur.kind !== 'series')) return;
      const d = Player.duration(), pos = finished ? d : (lastPos || Player.time());
      if (!d || !pos) return;
      const key = historyKeyFor(cur);
      S.history[key] = {
        sub: S.sub.id, type: cur.kind, id: cur.item.id, ep: cur.episode ? cur.episode.id : '', ext: cur.episode ? cur.episode.ext : '',
        title: cur.title, subtitle: cur.subtitle || '', logo: cur.logo, pos: pos, dur: d, t: Date.now(), item: cur.item
      };
      saveHistory();
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
      document.body.classList.remove('playing');
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
