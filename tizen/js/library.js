/* Organised library: sections by platform / region / type, one card per title (best copy), language of each copy,
   genre filters. Same rules as the Apple app. ES2017, Chromium 69 safe. */
(function (global) {
  'use strict';

  var RULES = [
    ['netflix', 'نتفليكس', 'Netflix', 'N', /netflix|نتفليكس|نتفلكس|(^|[^a-z])nf([^a-z]|$)/],
    ['shahid', 'شاهد', 'Shahid', '▶', /shahid|شاهد/],
    ['prime', 'أمازون برايم', 'Prime Video', 'a', /amazon|prime|برايم|امازون|أمازون|(^|[^a-z])amz([^a-z]|$)/],
    ['disney', 'ديزني+', 'Disney+', '✦', /disney|ديزني/],
    ['hbo', 'HBO و Max', 'HBO & Max', 'H', /hbo|اتش بي او|(^|[^a-z])max([^a-z]|$)/],
    ['apple', 'Apple TV+', 'Apple TV+', '', /apple|ابل تي|آبل|(^|[^a-z])atv([^a-z]|$)/],
    ['starz', 'ستارز بلاي', 'STARZPLAY', '★', /starz|ستارز/],
    ['osn', 'OSN+', 'OSN+', 'O', /(^|[^a-z])osn/],
    ['platforms', 'منصات أخرى', 'More platforms', '▣', /paramount|hulu|peacock|crunchyroll|(^|[^a-z])tod([^a-z]|$)|yango|watch ?it|wavo|viu|tvplus|tv\+|jawwy|weyyak|وياك|منصة/],
    ['ramadan', 'رمضان', 'Ramadan', '☾', /رمضان|ramadan/],
    ['kids', 'أطفال وأنمي', 'Kids & anime', '☺', /kid|child|اطفال|أطفال|cartoon|كرتون|انمي|أنمي|anime|animation|spacetoon|سبيستون|رسوم/],
    ['docs', 'وثائقي', 'Documentaries', '◎', /docu|وثائقي/],
    ['turkish', 'تركي', 'Turkish', '☪', /turk|ترك/],
    ['asian', 'كوري وآسيوي', 'Korean & Asian', '☀', /korea|كوري|asia|اسيو|آسيو|china|chinese|صين|japan|يابان|thai|تايلند/],
    ['indian', 'هندي', 'Indian', '♪', /india|هند|bollywood|بوليوود/],
    ['arabic', 'عربي', 'Arabic', 'ع', /arab|عرب|مصر|خليج|سوري|لبنان|سعودي|مغرب|عراق|كويت|اردن|تونس|جزائر|egypt|khaleej|syri/],
    ['foreign', 'أجنبي وعالمي', 'International', '◍', /english|اجنبي|أجنبي|foreign|hollywood|هوليوود|(^|[^a-z])(en|us|uk)([^a-z]|$)|spanish|اسباني|french|فرنس|europe|اوروب|latino/]
  ];

  var GENRES = {
    action: [[28], [10759], /action|اكشن|أكشن/i], drama: [[18], [18], /drama|دراما/i], comedy: [[35], [35], /comedy|كوميدي|كوميديا/i],
    romance: [[10749], [10749], /romance|romantic|رومانس|رومنس|رومانسي/i], thriller: [[53], [9648, 80], /thriller|اثارة|إثارة|تشويق/i],
    horror: [[27], [27], /horror|رعب/i], crime: [[80], [80], /crime|جريمة|جرائم/i], scifi: [[878, 14], [10765], /sci-?fi|science|خيال علمي/i],
    adventure: [[12], [10759], /adventure|مغامرة|مغامرات/i], animation: [[16], [16], /animation|cartoon|anime|انمي|أنمي|كرتون|رسوم/i],
    family: [[10751], [10751, 10762], /family|عائلي|عائلية/i], mystery: [[9648], [9648], /mystery|غموض/i], war: [[10752], [10768], /war|حرب|حروب/i],
    documentary: [[99], [99], /docu|وثائقي/i]
  };
  var GENRE_ORDER = {
    movie: ['action', 'drama', 'comedy', 'romance', 'thriller', 'horror', 'crime', 'scifi', 'adventure', 'animation', 'family', 'war', 'documentary'],
    series: ['drama', 'comedy', 'action', 'crime', 'thriller', 'scifi', 'animation', 'family', 'mystery', 'war', 'documentary']
  };

  function ar() { return !global.I18n || I18n.lang !== 'en'; }

  function shelfId(name) {
    var n = String(name || '').toLowerCase();
    for (var i = 0; i < RULES.length; i++) if (RULES[i][4].test(n)) return RULES[i][0];
    return /[؀-ۿ]/.test(n) ? 'other' : 'foreign';
  }
  function shelfMeta(id) {
    if (id === 'other') return { title: ar() ? 'أخرى' : 'More', icon: '▦' };
    var r = RULES.filter(function (x) { return x[0] === id; })[0];
    return { title: ar() ? r[1] : r[2], icon: r[3] };
  }

  function quality(name) {
    var n = String(name || '').toUpperCase();
    if (/(^|[^A-Z0-9])(4K|UHD|2160P?)([^A-Z0-9]|$)/.test(n)) return 3;
    if (/(^|[^A-Z0-9])(FHD|1080P?)([^A-Z0-9]|$)/.test(n)) return 2;
    if (/(^|[^A-Z0-9])(HD|720P?)([^A-Z0-9]|$)/.test(n)) return 1;
    return 0;
  }

  /** "Zmora: PL" / "ZMORA" / "Zmóra" → "zmora". */
  function normalize(title) {
    var w = String(title || '').split(/\s+/).filter(Boolean);
    while (w.length > 1 && /^[A-Z]{1,3}$/.test(w[w.length - 1])) w.pop();
    var s = w.join(' ').toLowerCase();
    try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) { /* old engine */ }
    s = s.replace(/[ً-ْ]/g, '').replace(/[^0-9a-z؀-ۿЀ-ӿ぀-ヿ一-鿿가-힯]+/g, '');
    return s || String(title || '').toLowerCase();
  }

  /** Language of a copy, from its name and category (Arabic first in the version picker). */
  function language(name, cat) {
    var n = (String(name || '') + ' ' + String(cat || '')).toLowerCase(), A = ar();
    if (/مدبلج|dubbed ?ar|ar ?dub|arabic dub/.test(n)) return { label: A ? 'مدبلج عربي' : 'Arabic dubbed', rank: 0 };
    if (/مترجم|arsub|ar-sub|ar sub|sub ?ar|arabic sub|subbed/.test(n)) return { label: A ? 'مترجم عربي' : 'Arabic subtitles', rank: 1 };
    if (/عرب|arab|(^|[^a-z])ar([^a-z]|$)/.test(n)) return { label: A ? 'عربي' : 'Arabic', rank: 2 };
    if (/english|(^|[^a-z])(en|eng|us|uk)([^a-z]|$)/.test(n)) return { label: A ? 'إنجليزي' : 'English', rank: 3 };
    if (/españa|espana|spain|spanish|latino|(^|[^a-z])(es|esp)([^a-z]|$)/.test(n)) return { label: A ? 'إسباني' : 'Spanish', rank: 5 };
    if (/nordic|sweden|swedish|norway|danish|denmark|finland|(^|[^a-z])(se|no|dk|fi)([^a-z]|$)/.test(n)) return { label: A ? 'إسكندنافي' : 'Nordic', rank: 5 };
    if (/turk|ترك|(^|[^a-z])tr([^a-z]|$)/.test(n)) return { label: A ? 'تركي' : 'Turkish', rank: 4 };
    if (/french|france|(^|[^a-z])fr([^a-z]|$)/.test(n)) return { label: A ? 'فرنسي' : 'French', rank: 5 };
    if (/german|deutsch|(^|[^a-z])de([^a-z]|$)/.test(n)) return { label: A ? 'ألماني' : 'German', rank: 5 };
    if (/poland|polish|(^|[^a-z])pl([^a-z]|$)/.test(n)) return { label: A ? 'بولندي' : 'Polish', rank: 5 };
    if (/india|hindi|هند/.test(n)) return { label: A ? 'هندي' : 'Hindi', rank: 5 };
    return { label: '', rank: 4 };
  }

  var idx = null, building = null, owner = null;

  function wait() { return new Promise(function (r) { setTimeout(r, 0); }); }

  /** Built in slices so the TV stays responsive. */
  async function build(data) {
    var out = { shelves: {}, shelfOf: {}, key: {}, q: {}, titleTmdb: {}, variants: {}, catName: {}, byNorm: {}, clean: {}, ct: {}, byCT: {}, gk: {}, groups: {}, gver: {}, gat: {}, bcache: {}, scache: {} };
    var types = ['movie', 'series'];
    for (var ti = 0; ti < types.length; ti++) {
      var type = types[ti];
      var items = (data && data[type]) || [], cats = (data && data.cats && data.cats[type]) || [];
      var counts = {};
      items.forEach(function (m) { counts[m.cat] = (counts[m.cat] || 0) + 1; });
      var groups = {};
      cats.forEach(function (c) {
        out.catName[type + '|' + c.id] = c.name;
        if (!counts[c.id]) return;
        var sid = shelfId(c.name);
        (groups[sid] = groups[sid] || []).push(c);
        out.shelfOf[type + '|' + c.id] = sid;
      });
      out.shelves[type] = RULES.map(function (r) { return r[0]; }).concat(['other']).filter(function (sid) { return groups[sid]; }).map(function (sid) {
        var cs = groups[sid].slice().sort(function (a, b) { return (counts[b.id] || 0) - (counts[a.id] || 0); });
        var meta = shelfMeta(sid);
        return { id: sid, title: meta.title, icon: meta.icon, cats: cs, count: cs.reduce(function (n, c) { return n + (counts[c.id] || 0); }, 0) };
      });
      var byTitle = {};
      for (var i = 0; i < items.length; i++) {
        var m = items[i];
        var c = Clean.full(m.name);
        var y = /^\d{4}/.test(m.year || '') ? String(m.year).slice(0, 4) : c.year;
        var nt = normalize(c.title);
        (byTitle[nt] = byTitle[nt] || []).push([m, y]);
        (out.byNorm[type + '|' + nt] = out.byNorm[type + '|' + nt] || []).push(m);
        out.clean[type + '|' + m.id] = y;
        out.ct[type + '|' + m.id] = c.title;   // cleaned once here, reused for TMDB lookups
        (out.byCT[type + '|' + c.title + '|' + y] = out.byCT[type + '|' + c.title + '|' + y] || []).push(m);
        out.q[type + '|' + m.id] = quality(m.name);
        if (i % 1500 === 1499) await wait();
      }
      Object.keys(byTitle).forEach(function (tk) {
        var list = byTitle[tk], years = {};
        list.forEach(function (p) { if (p[1]) years[p[1]] = (years[p[1]] || 0) + 1; });
        var main = Object.keys(years).sort(function (a, b) { return years[b] - years[a]; })[0] || '';
        var groupsY = {};
        list.forEach(function (p) {
          var near = Object.keys(years).filter(function (k) { return p[1] && Math.abs(parseInt(k, 10) - parseInt(p[1], 10)) <= 1; })[0];
          var g = p[1] ? (near || p[1]) : main;
          (groupsY[g] = groupsY[g] || []).push(p[0]);
        });
        Object.keys(groupsY).forEach(function (y2) {
          var k = tk + '|' + y2, ms = groupsY[y2];
          ms.forEach(function (m2) { out.key[type + '|' + m2.id] = k; });
          var t = ms.filter(function (m2) { return m2.tmdb; })[0];
          if (t) out.titleTmdb[type + '|' + k] = t.tmdb;
          out.variants[type + '|' + k] = ms;
        });
      });
      await wait();
    }
    return out;
  }

  /** Group keys of every title + TMDB groups, worked out once per TMDB update (no full scans per page). */
  function ensureGroups(type, all) {
    var v = global.Tmdb && Tmdb.idsVersion ? Tmdb.idsVersion() : 0;
    if (idx.gver[type] === v && idx.gk[type]) return;
    if (idx.gk[type] && Tmdb.idsSince) {
      // only the titles TMDB identified since last time move to their TMDB group (no full scan)
      var gk0 = idx.gk[type], groups0 = idx.groups[type];
      Tmdb.idsSince(idx.gver[type]).forEach(function (e) {
        if (e[0] !== type) return;
        var nk = 'tmdb:' + e[3];
        (idx.byCT[type + '|' + e[1] + '|' + e[2]] || []).forEach(function (m) {
          if (m.tmdb || idx.titleTmdb[type + '|' + (idx.key[type + '|' + m.id] || m.id)]) return;
          var old = gk0[m.id];
          if (old === nk) return;
          if (old && old.indexOf('tmdb:') === 0 && groups0[old]) groups0[old] = groups0[old].filter(function (x) { return x !== m; });
          gk0[m.id] = nk;
          (groups0[nk] = groups0[nk] || []).push(m);
        });
      });
      idx.gver[type] = v;
      return;
    }
    var gk = {}, groups = {};
    idx.gk[type] = null;
    for (var i = 0; i < all.length; i++) {
      var k = rawGroupKey(type, all[i]);
      gk[all[i].id] = k;
      if (k.indexOf('tmdb:') === 0) (groups[k] = groups[k] || []).push(all[i]);
    }
    idx.gk[type] = gk; idx.groups[type] = groups; idx.gver[type] = v;
  }

  function groupKey(type, m) {
    if (!idx) return m.id;
    var v = global.Tmdb && Tmdb.idsVersion ? Tmdb.idsVersion() : 0;
    if (idx.gk[type] && idx.gk[type][m.id]) return idx.gk[type][m.id];   // may be up to 20 s old: fine
    return rawGroupKey(type, m);
  }

  function rawGroupKey(type, m) {
    var tk = idx.key[type + '|' + m.id] || m.id;
    if (m.tmdb) return 'tmdb:' + m.tmdb;
    var t = idx.titleTmdb[type + '|' + tk];
    if (t) return 'tmdb:' + t;
    var ct = idx.ct[type + '|' + m.id];
    var id = !(global.Tmdb && Tmdb.enabled()) ? 0
      : ct !== undefined && Tmdb.idForClean ? Tmdb.idForClean(type, ct, idx.clean[type + '|' + m.id] || '') : Tmdb.idFor(type, m.name, m.year);
    if (id) return 'tmdb:' + id;
    return tk;
  }

  function better(type, a, b) {
    var qa = idx.q[type + '|' + a.id] || 0, qb = idx.q[type + '|' + b.id] || 0;
    return qa !== qb ? qa > qb : (a.added || 0) > (b.added || 0);
  }

  /** One card per title (best copy), newest first. */
  function collapse(type, list) {
    if (!idx) return list;
    var best = {}, order = [];
    list.forEach(function (m) {
      var k = groupKey(type, m);
      if (!best[k]) { best[k] = m; order.push(k); } else if (better(type, m, best[k])) best[k] = m;
    });
    return order.map(function (k) { return best[k]; }).sort(function (a, b) { return (b.added || 0) - (a.added || 0); });
  }

  global.Library = {
    ready: function () { return !!idx; },
    /** (Re)builds for this subscription's catalogue; resolves when ready. */
    ensure: function (data) {
      if (owner === data && (idx || building)) return building || Promise.resolve(idx);
      owner = data; idx = null;
      building = build(data).then(function (r) {
        if (owner === data) {
          idx = r; building = null;
          // group the titles ahead of time (when the remote is idle) so the first shelf opens at once
          var warm = function (type) {
            if (idx !== r) return;
            if (global.Nav && Nav.idleFor && Nav.idleFor() < 2000) { setTimeout(function () { warm(type); }, 2000); return; }
            ensureGroups(type, (data && data[type]) || []);
          };
          setTimeout(function () { warm('movie'); setTimeout(function () { warm('series'); }, 500); }, 1500);
        }
        return r;
      });
      return building;
    },
    shelves: function (type) { return (idx && idx.shelves[type]) || []; },
    browse: function (type, all, shelf, cat) {
      if (!idx) return [];
      ensureGroups(type, all);
      // same shelf again (back from a title, switching tabs): reuse the list instead of re-scanning the library
      var ck = type + '|' + shelf + '|' + (cat || '') + '|' + all.length + '|' + idx.gver[type];
      if (idx.bcache[ck]) return idx.bcache[ck];
      var sk = type + '|' + shelf + '|' + (cat || '') + '|' + all.length;
      var src = idx.scache[sk] || (idx.scache[sk] = cat ? all.filter(function (m) { return m.cat === cat; })
        : all.filter(function (m) { return idx.shelfOf[type + '|' + m.cat] === shelf; }));
      var out = collapse(type, src);
      if (Object.keys(idx.bcache).length > 60) idx.bcache = {};
      idx.bcache[ck] = out;
      return out;
    },
    collapse: collapse,
    groupKey: function (type, m) { return idx ? groupKey(type, m) : String(m.id); },
    /** A title from an actor's list, if the subscription has it (instant lookup). */
    find: function (type, w) {
      if (!idx) return null;
      // exact TMDB match first (provider id or a title TMDB already identified)
      if (w.id && idx.groups[type] && idx.groups[type]['tmdb:' + w.id] && idx.groups[type]['tmdb:' + w.id].length) return idx.groups[type]['tmdb:' + w.id][0];
      var names = [w.title, w.original].filter(Boolean).map(function (n) { return normalize(Clean.full(n).title); });
      var hits = [];
      names.forEach(function (n) { hits = hits.concat(idx.byNorm[type + '|' + n] || []); });
      var same = hits.filter(function (m) {
        var y = idx.clean[type + '|' + m.id];
        return !w.year || !y || Math.abs(parseInt(y, 10) - parseInt(w.year, 10)) <= 1;
      })[0];
      return same || hits[0] || null;
    },
    variants: function (type, all, m) {
      if (!idx) return [m];
      ensureGroups(type, all);
      var g = groupKey(type, m), list;
      if (g.indexOf('tmdb:') === 0) list = idx.groups[type][g] || [m];
      else list = idx.variants[type + '|' + g] || [m];
      return (list.length ? list : [m]).slice();
    },
    /** Copies sorted for the picker: Arabic dubbed, Arabic subtitles, Arabic, English, others; best quality first. */
    ordered: function (type, list) {
      return list.map(function (m) { return [m, language(m.name, idx ? idx.catName[type + '|' + m.cat] : '').rank, quality(m.name)]; })
        .sort(function (a, b) { return a[1] !== b[1] ? a[1] - b[1] : b[2] - a[2]; }).map(function (x) { return x[0]; });
    },
    label: function (type, m) {
      var cat = idx ? idx.catName[type + '|' + m.cat] || '' : '';
      return [language(m.name, cat).label, ['', 'HD', 'FHD', '4K'][quality(m.name)], cat].filter(Boolean).join(' · ');
    },
    genres: function (type) { return GENRE_ORDER[type === 'series' ? 'series' : 'movie']; },
    genreIds: function (g, type) { return GENRES[g][type === 'series' ? 1 : 0]; },
    genreInCategory: function (g, catName) { return GENRES[g][2].test(catName || ''); },
    catName: function (type, id) { return idx ? idx.catName[type + '|' + id] || '' : ''; }
  };
})(window);
