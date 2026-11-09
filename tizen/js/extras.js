/* NOVA IPTV (Tizen) — title clean-up, TMDB enrichment and the matches strip. ES2017, Chromium 69 safe. */
(function (global) {
  'use strict';

  // ------------------------------------------------------------------ titles
  var PREFIX = ['AR', 'EN', 'TR', 'FR', 'DE', 'ES', 'IT', 'IN', 'US', 'UK', 'KSA', 'SA', 'EG', 'AE', 'KW', 'QA', 'NF', 'OSN', 'AS', 'KR', 'CN', 'JP', 'HI', '4K', '8K', 'FHD', 'UHD', 'HD', 'VIP', 'MULTI'];
  var TAGS = ['TOP', 'NEW', '4K', '8K', 'UHD', 'FHD', 'HD', 'SD', 'HEVC', 'H265', 'H264', 'HDR', 'AR', 'EN', 'TR', 'DO', 'DB', 'SUB', 'SUBS', 'DUB', 'DUBBED',
    'MULTI', 'ARSUB', 'VIP', 'NF', 'ULTRA', 'ULTRAHD', 'RAW', 'AMZ', 'ATV', 'DSNP', 'HMAX', 'MAX', 'HULU', 'PRIME', 'US', 'UK', 'GB', 'CA', 'AU',
    'KR', 'JP', 'FR', 'DE', 'ES', 'IT', 'MX', 'BR', 'SUIBS', 'DV', 'DOLBY', 'ATMOS', 'REMUX', 'WEB', 'WEBDL', 'BLURAY', '1080P', '720P', '2160P'];
  function isTagGroup(s) {
    var parts = s.split(/[-_ ]/).filter(function (x) { return x; });
    return parts.length > 0 && parts.every(function (x) { return TAGS.indexOf(x.toUpperCase()) >= 0 || PREFIX.indexOf(x.toUpperCase()) >= 0; });
  }

  /** "AR: Title - 4K-AR" → "Title"; "4K-AMZ - Drive (2025) (US) HEVC" → {title:"Drive", year:"2025"}. */
  function providerTag(g) {
    if (isTagGroup(g)) return true;
    var letters = g.replace(/[^A-Za-z]/g, '');
    return g.length <= 12 && letters.length > 0 && letters === letters.toUpperCase() && /^[\x00-\x7F]+$/.test(g) && (/[0-9\-_+.]/.test(g) || g.length <= 4);
  }
  function cleanFull(raw) {
    var t = String(raw || '').trim(), m, i;
    for (i = 0; i < 2; i++) {
      m = /^\s*[\[(|▎]\s*([^\])|▎]{1,12})\s*[\])|▎]\s*[:\-–—]?\s*/.exec(t);
      if (m && providerTag(m[1]) && t.length - m[0].length > 1) t = t.slice(m[0].length);
    }
    m = /^\s*([A-Za-z0-9]{2,5})\s*[:|]\s*/.exec(t);
    if (m && PREFIX.indexOf(m[1].toUpperCase()) >= 0 && t.length > m[0].length) t = t.slice(m[0].length);
    for (i = 0; i < 2; i++) {
      m = /^\s*([A-Za-z0-9][A-Za-z0-9_\-+.]{0,14})\s*[-|–—:▎]\s+/.exec(t);
      if (m && providerTag(m[1]) && t.length - m[0].length > 1) t = t.slice(m[0].length);
    }
    var year = '';
    m = /[(\[]((?:19|20)\d{2})[)\]]/.exec(t);
    if (m) { year = m[1]; if (m.index > 0) t = t.slice(0, m.index); }
    t = t.replace(/[(\[][^)\]]*[)\]]/g, ' ');
    t = t.replace(/\s[-–]?\s*(S\d{1,2}(\s*E\d{1,3})?|Season\s*\d+|الموسم\s*\S+)(\s.*)?$/i, '');
    if (!year) { m = /\s[-–]?\s*((?:19|20)\d{2})\s*$/.exec(t); if (m && m.index > 0) { year = m[1]; t = t.slice(0, m.index); } }
    var words = t.split(/\s+/).filter(function (w) { return w; });
    while (words.length > 1 && (TAGS.indexOf(words[words.length - 1].replace(/^[-|]+|[-|]+$/g, '').toUpperCase()) >= 0 || /^[-|–]$/.test(words[words.length - 1]))) words.pop();
    var out = words.join(' ').replace(/^[\s\-|–:]+|[\s\-|–:]+$/g, '');
    return { title: out || String(raw || '').trim(), year: year };
  }
  function seasonEpisode(text) {
    var m = /[Ss]\s*0*(\d{1,2})\s*[ .xX-]?\s*[Ee]\s*0*(\d{1,3})/.exec(text || '');
    return m ? { s: parseInt(m[1], 10), e: parseInt(m[2], 10) } : null;
  }

  // ------------------------------------------------------------------ TMDB
  var TMDB = 'https://api.themoviedb.org/3/';
  var tmdbKey = '';
  var infoCache = {}, posterCache = {}, seasonCache = {}, seasonVidCache = {};
  var cleanMemo = {}, idsVer = 0, inflight = {}, idsLog = [];
  /** A title TMDB just identified — the library merges its copies incrementally from this log. */
  function noteId(type, title, y, id) { if (!id) return; idsLog.push([type, title, y, id]); idsVer = idsLog.length; }   // cleaning titles is costly on a TV CPU
  var posterStore = null, posterDirty = false, posterTimer = null;
  var queue = [], active = 0;

  /** Titles in a script the viewer probably can't read (Cyrillic, CJK, Thai …). */
  function foreignScript(t) {
    for (var i = 0; i < (t || '').length; i++) {
      var c = t.charCodeAt(i);
      if (c < 0x250 || (c >= 0x1E00 && c <= 0x1EFF) || (c >= 0x600 && c <= 0x6FF) || (c >= 0x750 && c <= 0x77F) || (c >= 0xFB50 && c <= 0xFEFF)) continue;
      if (/[\s\d\p{P}]/u.test(t[i])) continue;
      return true;
    }
    return false;
  }
  function lang() { return global.I18n && I18n.lang === 'en' ? 'en' : 'ar'; }
  function img(path, size) { return path ? 'https://image.tmdb.org/t/p/' + size + path : ''; }

  function tmdbGet(path, params, key) {
    var k = key || tmdbKey;
    var bearer = /^eyJ/.test(k);
    var q = Object.keys(params || {}).map(function (a) { return a + '=' + encodeURIComponent(params[a]); });
    if (!bearer) q.push('api_key=' + encodeURIComponent(k));
    return Api.json(TMDB + path + (q.length ? '?' + q.join('&') : ''), 15000, bearer ? { Authorization: 'Bearer ' + k } : null);
  }

  // Cards on screen go first (newest request first); background identification only uses spare capacity.
  var lowQueue = [], lowTimer = null;
  function limited(fn, low) {
    return new Promise(function (resolve) {
      (low ? lowQueue : queue).push(function () {
        active++;
        fn().then(resolve, function () { resolve(null); }).then(function () { active--; next(); });
      });
      next();
    });
  }
  function next() {
    while (active < 5 && queue.length) queue.pop()();
    if (lowQueue.length && !queue.length && global.Nav && Nav.idleFor && Nav.idleFor() < 1500) {
      clearTimeout(lowTimer); lowTimer = setTimeout(next, 1600); return;   // viewer is browsing: wait
    }
    while (active < 2 && !queue.length && lowQueue.length) lowQueue.shift()();
  }

  function savePosters() {
    posterDirty = true;
    clearTimeout(posterTimer);
    // saving the whole cache is heavy on a TV: do it rarely, and only while the remote is idle
    posterTimer = setTimeout(function save() {
      if (!posterDirty) return;
      if (global.Nav && Nav.idleFor && Nav.idleFor() < 4000) { posterTimer = setTimeout(save, 4000); return; }
      posterDirty = false; Store.set('tmdbPosters2', posterCache);
    }, 20000);
  }

  var Tmdb = {
    init: async function () {
      var enc = await Store.get('tmdbKey', '');
      tmdbKey = enc ? await Store.decrypt(enc) : '';
      posterCache = await Store.get('tmdbPosters2', {}) || {};
    },
    enabled: function () { return !!tmdbKey; },
    setKey: async function (k) {
      k = String(k || '').trim();
      if (!k) return false;
      try { await tmdbGet('configuration', {}, k); } catch (e) { return false; }
      tmdbKey = k;
      await Store.set('tmdbKey', await Store.encrypt(k));
      infoCache = {}; posterCache = {}; seasonCache = {}; seasonVidCache = {};
      await Store.set('tmdbPosters2', {});
      return true;
    },
    remove: async function () {
      tmdbKey = '';
      infoCache = {}; posterCache = {};
      await Store.del('tmdbKey'); await Store.set('tmdbPosters2', {});
    },

    /** Localised poster + title for list cards. Resolves {poster, title} or null. */
    card: function (type, name, year, low) {
      if (!tmdbKey || (type !== 'movie' && type !== 'series')) return Promise.resolve(null);
      var c = cleanFull(name);
      var y = /^\d{4}/.test(year || '') ? String(year).slice(0, 4) : c.year;
      var key = type + '|' + lang() + '|' + c.title + '|' + y;
      if (posterCache[key] !== undefined) return Promise.resolve(posterCache[key]);
      if (inflight[key]) return inflight[key];
      var job = inflight[key] = limited(function () {
        var isTv = type === 'series';
        var params = { query: c.title, language: lang(), include_adult: 'false' };
        if (y) params[isTv ? 'first_air_date_year' : 'year'] = y;
        var path = isTv ? 'search/tv' : 'search/movie';
        return tmdbGet(path, params).then(function (r) {
          if ((!r.results || !r.results.length) && y) {
            delete params[isTv ? 'first_air_date_year' : 'year'];
            return tmdbGet(path, params);
          }
          return r;
        }).then(function (r) {
          var f = r.results && r.results[0];
          var v = f ? { poster: img(f.poster_path, 'w342'), title: f[isTv ? 'name' : 'title'] || '', id: f.id, g: f.genre_ids || [],
            bd: img(f.backdrop_path, 'w780') } : { poster: '', title: '', id: 0, g: [] };
          if (f && foreignScript(v.title)) {
            // no Arabic title on TMDB → show the English one instead of Cyrillic / CJK
            return tmdbGet((isTv ? 'tv/' : 'movie/') + f.id, { language: 'en-US' }).then(function (en) {
              v.title = (en && (en.name || en.title)) || v.title; posterCache[key] = v; savePosters(); noteId(type, c.title, y, v.id); return v;
            }, function () { posterCache[key] = v; savePosters(); return v; });
          }
          posterCache[key] = v; savePosters();
          if (v.id) noteId(type, c.title, y, v.id);
          return v;
        });
      }, low);
      job.then(function () { delete inflight[key]; });
      return job;
    },

    /** Bumps whenever TMDB identifies another title (so the library can merge copies again). */
    idsVersion: function () { return idsVer; },
    idsSince: function (n) { return idsLog.slice(n || 0); },

    /** Same as idFor, for a title that's already cleaned (no regex work). */
    idForClean: function (type, title, y) {
      var a = posterCache[type + '|ar|' + title + '|' + y], b = posterCache[type + '|en|' + title + '|' + y];
      return (a && a.id) || (b && b.id) || 0;
    },

    /** TMDB id already known for a title (no network). */
    idFor: function (type, name, year) {
      var c = cleanMemo[name] || (cleanMemo[name] = cleanFull(name));
      var y = /^\d{4}/.test(year || '') ? String(year).slice(0, 4) : c.year;
      var a = posterCache[type + '|ar|' + c.title + '|' + y], b = posterCache[type + '|en|' + c.title + '|' + y];
      return (a && a.id) || (b && b.id) || 0;
    },

    /** Biography and best-known titles of a cast member. */
    /** The other parts of a film series (TMDB collection), in release order. */
    collection: function (id) {
      if (!tmdbKey || !id) return Promise.resolve(null);
      var key = 'col|' + lang() + '|' + id;
      if (infoCache[key] !== undefined) return Promise.resolve(infoCache[key]);
      return tmdbGet('collection/' + id, { language: lang() }).then(function (o) {
        var parts = (o.parts || []).filter(function (d) { return d.release_date || d.poster_path; })
          .sort(function (a, b) { return String(a.release_date || '9999').localeCompare(String(b.release_date || '9999')); })
          .map(function (d) {
            var title = d.title || '';
            return { id: d.id, tv: false, poster: img(d.poster_path, 'w342'), title: title, original: d.original_title || '', year: String(d.release_date || '').slice(0, 4) };
          });
        var out = { name: o.name || '', parts: parts };
        infoCache[key] = out;
        return out;
      }, function () { return null; });
    },

    person: function (id) {
      if (!tmdbKey || !id) return Promise.resolve(null);
      var l = lang();
      return tmdbGet('person/' + id, { language: l, append_to_response: 'combined_credits' }).then(function (o) {
        var seen = {};
        var known = (((o.combined_credits || {}).cast) || []).filter(function (d) { return d.poster_path && !seen[d.id] && (seen[d.id] = 1); })
          .sort(function (a, b) { return (b.vote_count || 0) - (a.vote_count || 0); }).slice(0, 12).map(function (d) {
            var tv = d.media_type === 'tv';
            return { id: d.id, tv: tv, poster: img(d.poster_path, 'w342'), title: d[tv ? 'name' : 'title'] || '',
              original: d[tv ? 'original_name' : 'original_title'] || '', year: String(d[tv ? 'first_air_date' : 'release_date'] || '').slice(0, 4) };
          });
        var out = { bio: o.biography || '', born: o.birthday || '', place: o.place_of_birth || '', known: known };
        if (!out.bio && l !== 'en') return tmdbGet('person/' + id, { language: 'en-US' }).then(function (en) { out.bio = en.biography || ''; return out; }, function () { return out; });
        return out;
      }, function () { return null; });
    },

    /** Full details (Arabic first, English fallback). */
    details: function (type, tmdbId, name, year) {
      if (!tmdbKey) return Promise.resolve(null);
      var isTv = type === 'series';
      var c = cleanFull(name);
      var key = type + '|' + lang() + '|' + (tmdbId || c.title + '|' + (year || c.year));
      if (infoCache[key] !== undefined) return Promise.resolve(infoCache[key]);
      var l = lang();
      var idPromise = tmdbId ? Promise.resolve(parseInt(tmdbId, 10)) : Tmdb.card(type, name, year).then(function (r) { return r && r.id; });
      return idPromise.then(function (id) {
        if (!id) return null;
        var t = isTv ? 'tv' : 'movie';
        return tmdbGet(t + '/' + id, {
          language: l, append_to_response: 'credits,images,videos,' + (isTv ? 'content_ratings' : 'release_dates'),
          include_image_language: l + ',en,null', include_video_language: l + ',ar,en,tr,null'
        }).then(function (o) {
          var needEn = l !== 'en' && (!o.overview || !(o.title || o.name) || foreignScript(o.title || o.name));
          return (needEn ? tmdbGet(t + '/' + id, { language: 'en-US' }).catch(function () { return null; }) : Promise.resolve(null)).then(function (en) {
            function pick(list, langs) {
              var arr = (o.images && o.images[list]) || [];
              for (var i = 0; i < langs.length; i++) {
                var best = arr.filter(function (x) { return (x.iso_639_1 || null) === langs[i]; })
                  .sort(function (a, b) { return (b.vote_average * 10 + b.vote_count) - (a.vote_average * 10 + a.vote_count); })[0];
                if (best) return best.file_path;
              }
              return '';
            }
            // trailer in the viewer's language first, then English / any; trailer > teaser > clip
            var vids = ((o.videos && o.videos.results) || []).filter(function (v) { return v.site === 'YouTube'; });
            var TYPES = { Trailer: 0, Teaser: 1, Clip: 2, Featurette: 3 };
            var rank = function (v) { return (v.type in TYPES ? TYPES[v.type] : 9) * 10 + (v.iso_639_1 === l ? 0 : v.iso_639_1 === 'en' ? 1 : 2) - (v.official ? 0.5 : 0); };
            vids.sort(function (a, b) { return rank(a) - rank(b); });
            var trailer = vids.length && rank(vids[0]) < 40 ? vids[0].key : '';
            var crew = (o.credits && o.credits.crew) || [];
            var info = {
              id: id,
              title: (function () { var tt = o.title || o.name || (en && (en.title || en.name)) || ''; return foreignScript(tt) && en && (en.title || en.name) ? (en.title || en.name) : tt; })(),
              original: o.original_title || o.original_name || '',
              overview: o.overview || (en && en.overview) || '',
              tagline: o.tagline || '',
              rating: o.vote_average ? (Math.round(o.vote_average * 10) / 10).toFixed(1) : '',
              votes: o.vote_count || 0,
              year: String(o.release_date || o.first_air_date || '').slice(0, 4),
              runtime: o.runtime || (o.episode_run_time && o.episode_run_time[0]) || 0,
              genres: (o.genres || []).map(function (g) { return g.name; }).filter(Boolean),
              backdrop: img(pick('backdrops', [null]) || o.backdrop_path, 'w1280'),
              poster: img(pick('posters', [l]) || o.poster_path, 'w500'),
              logo: img(pick('logos', [l, 'en', null]), 'w500'),
              logoLocal: img(pick('logos', [l]), 'w500'),               // logo in the app language only
              localText: !!o.overview && !foreignScript(o.title || o.name || ''),   // TMDB has the story in the app language
              cast: ((o.credits && o.credits.cast) || []).slice(0, 12).map(function (p) { return { id: p.id, name: p.name, role: p.character || '', photo: img(p.profile_path, 'w185') }; }),
              creators: isTv ? (o.created_by || []).map(function (p) { return p.name; }) : crew.filter(function (p) { return p.job === 'Director'; }).map(function (p) { return p.name; }),
              seasons: o.number_of_seasons || 0,
              collection: o.belongs_to_collection && o.belongs_to_collection.id ? { id: o.belongs_to_collection.id, name: o.belongs_to_collection.name || '' } : null,
              trailer: trailer
            };
            if (!info.trailer && l !== 'en') {
              return tmdbGet(t + '/' + id + '/videos', { language: 'en-US' }).then(function (v) {
                var list = ((v && v.results) || []).filter(function (x) { return x.site === 'YouTube' && (x.type === 'Trailer' || x.type === 'Teaser'); });
                info.trailer = (list[0] || {}).key || '';
                return info;
              }, function () { return info; });
            }
            return info;
          });
        });
      }).then(function (info) { infoCache[key] = info; return info; }, function () { return null; });
    },

    /** Trailer / teaser of one season of a series (TMDB season videos), '' when TMDB has none. */
    seasonTrailer: function (tvId, n) {
      if (!tmdbKey || !tvId) return Promise.resolve('');
      var ck = tvId + '|' + n + '|' + lang();
      if (ck in seasonVidCache) return Promise.resolve(seasonVidCache[ck]);
      var l = lang();
      return tmdbGet('tv/' + tvId + '/season/' + n + '/videos', { language: l, include_video_language: l + ',en,null' }).then(function (o) {
        var list = ((o && o.results) || []).filter(function (v) { return v.site === 'YouTube' && (v.type === 'Trailer' || v.type === 'Teaser'); });
        var rank = function (v) { return (v.type === 'Trailer' ? 0 : 10) + (v.iso_639_1 === l ? 0 : v.iso_639_1 === 'en' ? 1 : 2) - (v.official ? 0.5 : 0); };
        list.sort(function (a, b) { return rank(a) - rank(b); });
        var k = (list[0] || {}).key || '';
        seasonVidCache[ck] = k;
        return k;
      }, function () { return ''; });
    },

    season: function (tvId, season) {
      if (!tmdbKey || !tvId) return Promise.resolve({});
      var key = tvId + '|' + season + '|' + lang();
      if (seasonCache[key]) return Promise.resolve(seasonCache[key]);
      return tmdbGet('tv/' + tvId + '/season/' + season, { language: lang() }).then(function (o) {
        var map = {};
        (o.episodes || []).forEach(function (e) {
          map[e.episode_number] = { name: /^(Episode|الحلقة)\s*\d+$/i.test(e.name || '') ? '' : (e.name || ''), overview: e.overview || '', still: img(e.still_path, 'w300') };
        });
        seasonCache[key] = map;
        return map;
      }, function () { return {}; });
    }
  };

  // ------------------------------------------------------------------ matches
  var COMPETITIONS = [
    { slug: 'uefa.champions', en: 'UEFA Champions League', ar: 'دوري أبطال أوروبا', p: 0 },
    { slug: 'ksa.1', en: 'Saudi Pro League', ar: 'دوري روشن السعودي', p: 1 },
    { slug: 'eng.1', en: 'Premier League', ar: 'الدوري الإنجليزي', p: 2 },
    { slug: 'esp.1', en: 'LaLiga', ar: 'الدوري الإسباني', p: 3 },
    { slug: 'ita.1', en: 'Serie A', ar: 'الدوري الإيطالي', p: 4 },
    { slug: 'ger.1', en: 'Bundesliga', ar: 'الدوري الألماني', p: 5 },
    { slug: 'fra.1', en: 'Ligue 1', ar: 'الدوري الفرنسي', p: 6 },
    { slug: 'uefa.europa', en: 'UEFA Europa League', ar: 'الدوري الأوروبي', p: 7 },
    { slug: 'afc.champions', en: 'AFC Champions League Elite', ar: 'دوري أبطال آسيا للنخبة', p: 8 },
    { slug: 'uefa.europa.conf', en: 'Conference League', ar: 'دوري المؤتمر الأوروبي', p: 10 },
    { slug: 'eng.fa', en: 'FA Cup', ar: 'كأس الاتحاد الإنجليزي', p: 11 },
    { slug: 'esp.copa_del_rey', en: 'Copa del Rey', ar: 'كأس ملك إسبانيا', p: 12 },
    { slug: 'ned.1', en: 'Eredivisie', ar: 'الدوري الهولندي', p: 13 },
    { slug: 'por.1', en: 'Liga Portugal', ar: 'الدوري البرتغالي', p: 14 },
    { slug: 'tur.1', en: 'Süper Lig', ar: 'الدوري التركي', p: 15 },
    { slug: 'usa.1', en: 'MLS', ar: 'الدوري الأمريكي', p: 16 },
    { slug: 'fifa.world', en: 'FIFA World Cup', ar: 'كأس العالم', p: 0, nat: true },
    { slug: 'fifa.worldq.afc', en: 'World Cup Qualifiers — Asia', ar: 'تصفيات كأس العالم — آسيا', p: 9, nat: true },
    { slug: 'afc.asian.cup', en: 'AFC Asian Cup', ar: 'كأس آسيا', p: 9, nat: true },
    { slug: 'uefa.nations', en: 'UEFA Nations League', ar: 'دوري الأمم الأوروبية', p: 12, nat: true },
    { slug: 'fifa.worldq.uefa', en: 'World Cup Qualifiers — Europe', ar: 'تصفيات كأس العالم — أوروبا', p: 13, nat: true },
    { slug: 'caf.nations', en: 'Africa Cup of Nations', ar: 'كأس أمم أفريقيا', p: 13, nat: true },
    { slug: 'fifa.friendly', en: 'International Friendlies', ar: 'مباريات ودية دولية', p: 20, nat: true }
  ];
  var DEFAULT_COMPS = ['uefa.champions', 'ksa.1', 'eng.1', 'esp.1', 'ita.1', 'ger.1', 'fra.1', 'uefa.europa', 'afc.champions', 'fifa.worldq.afc'];
  var CONTINENTAL = ['uefa.champions', 'uefa.europa', 'uefa.europa.conf', 'afc.champions'];
  var HINTS = {
    'uefa.champions': ['bein', 'بي ان'], 'uefa.europa': ['bein', 'بي ان'], 'eng.1': ['bein', 'بي ان'], 'esp.1': ['bein', 'بي ان'],
    'fra.1': ['bein', 'بي ان'], 'ger.1': ['mbc action', 'ام بي سي اكشن', 'shahid', 'mbc'],
    'ita.1': ['starz', 'ad sport', 'abu dhabi', 'ابوظبي', 'shasha'], 'ksa.1': ['thmanyah', 'ثمانية', 'ssc'],
    'afc.champions': ['thmanyah', 'ثمانية', 'ssc', 'alkass', 'الكاس'], 'fifa.worldq.afc': ['ssc', 'ksa sport', 'السعودية الرياضية', 'alkass', 'bein']
  };
  var mcache = {};
  var prefs = { comps: DEFAULT_COMPS.slice(), teams: [], only: false, show: true, picks: {} };

  function comp(slug) { return COMPETITIONS.filter(function (c) { return c.slug === slug; })[0]; }
  function ymd(d) { return d.getFullYear() + ('0' + (d.getMonth() + 1)).slice(-2) + ('0' + d.getDate()).slice(-2); }
  function hex(s) { return /^[0-9a-f]{6}$/i.test(s || '') ? '#' + s : ''; }

  function parseEvent(e, c, lgName, lgLogo) {
    var cp = (e.competitions || [])[0];
    if (!cp) return null;
    var teams = cp.competitors || [];
    var home = teams.filter(function (x) { return x.homeAway === 'home'; })[0] || teams[0];
    var away = teams.filter(function (x) { return x.homeAway === 'away'; })[0] || teams[1];
    if (!home || !away) return null;
    var st = cp.status || e.status || {}, ty = st.type || {};
    var name = ty.name || '';
    var state = /POSTPONED|CANCELED/.test(name) ? 'postponed' : /HALFTIME/.test(name) ? 'ht' : ty.state === 'in' ? 'live' : ty.state === 'post' ? 'post' : 'pre';
    function team(o) {
      var t = o.team || {};
      return {
        id: String(t.id || ''), name: t.displayName || '', short: t.shortDisplayName || t.abbreviation || t.displayName || '',
        logo: t.logo || ((t.logos || [])[0] || {}).href || '', score: state === 'pre' || state === 'postponed' ? '' : String(o.score || '0'),
        color: hex(t.color), alt: hex(t.alternateColor)
      };
    }
    var bc = [];
    (cp.broadcasts || []).forEach(function (b) { (b.names || []).forEach(function (n) { if (bc.indexOf(n) < 0) bc.push(n); }); });
    return {
      id: String(e.id), slug: c.slug, league: lgName, leagueLogo: lgLogo, p: c.p, home: team(home), away: team(away),
      start: Date.parse(e.date || cp.date) || 0, state: state, clock: st.displayClock || ty.shortDetail || '', broadcasters: bc.slice(0, 3)
    };
  }

  function fetchLeague(c, date) {
    var url = 'https://site.api.espn.com/apis/site/v2/sports/soccer/' + c.slug + '/scoreboard?dates=' + date;
    var hit = mcache[url], now = Date.now();
    if (hit && now - hit.t < (hit.v.some(isLive) ? 45000 : 600000)) return Promise.resolve(hit.v);
    return Api.json(url, 15000).then(function (r) {
      var lg = (r.leagues || [])[0] || {};
      var logo = ((lg.logos || [])[0] || {}).href || '';
      var list = (r.events || []).map(function (e) { return parseEvent(e, c, lg.name || c.en, logo); }).filter(Boolean);
      mcache[url] = { t: now, v: list };
      return list;
    }, function () { return []; });
  }
  function isLive(m) { return m.state === 'live' || m.state === 'ht'; }

  var Matches = {
    COMPETITIONS: COMPETITIONS,
    init: async function () { prefs = Object.assign(prefs, await Store.get('matchPrefs', {})); },
    prefs: function () { return prefs; },
    save: function () { return Store.set('matchPrefs', prefs); },
    label: function (c) { return lang() === 'ar' ? c.ar : c.en; },
    isLive: isLive,
    canOpen: function (m) { return isLive(m) || (m.state === 'pre' && Date.now() >= m.start - 30 * 60000); },
    network: function (slug) {
      if (['uefa.champions', 'uefa.europa', 'eng.1', 'esp.1', 'fra.1'].indexOf(slug) >= 0) return 'beIN SPORTS';
      if (slug === 'ger.1') return lang() === 'ar' ? 'MBC أكشن · شاهد' : 'MBC Action · Shahid';
      if (slug === 'ksa.1') return lang() === 'ar' ? 'ثمانية' : 'Thmanyah';
      return '';
    },

    forDay: function (offset) {
      var start = new Date(); start.setHours(0, 0, 0, 0); start.setDate(start.getDate() + offset);
      var s0 = start.getTime(), s1 = s0 + 86400000;
      var favIds = prefs.teams.map(function (t) { return t.id; });
      var slugs = [];
      function add(s) { if (slugs.indexOf(s) < 0) slugs.push(s); }
      if (!prefs.only || !prefs.teams.length) prefs.comps.forEach(add);
      prefs.teams.forEach(function (t) { if (t.slug) add(t.slug); });
      if (prefs.teams.some(function (t) { return !t.nat; })) CONTINENTAL.forEach(add);
      if (prefs.teams.some(function (t) { return t.nat; })) COMPETITIONS.filter(function (c) { return c.nat; }).forEach(function (c) { add(c.slug); });
      var dates = [-1, 0, 1].map(function (d) { var x = new Date(s0 + 12 * 3600000); x.setDate(x.getDate() + d); return ymd(x); });
      var jobs = [];
      slugs.forEach(function (sl) { var c = comp(sl) || { slug: sl, en: sl, ar: sl, p: 30 }; dates.forEach(function (d) { jobs.push(fetchLeague(c, d)); }); });
      return Promise.all(jobs).then(function (rs) {
        var seen = {}, out = [];
        rs.forEach(function (l) { l.forEach(function (m) { if (!seen[m.id]) { seen[m.id] = 1; out.push(m); } }); });
        out = out.filter(function (m) { return m.start >= s0 && m.start < s1; }).map(function (m) {
          m.fav = favIds.indexOf(m.home.id) >= 0 || favIds.indexOf(m.away.id) >= 0;
          return m;
        }).filter(function (m) { return prefs.only && prefs.teams.length ? m.fav : (m.fav || prefs.comps.indexOf(m.slug) >= 0); });
        var rank = { live: 0, ht: 0, pre: 1, post: 2, postponed: 3 };
        out.sort(function (a, b) { return (rank[a.state] - rank[b.state]) || ((a.fav ? 0 : 1) - (b.fav ? 0 : 1)) || (a.p - b.p) || (a.start - b.start); });
        return out;
      });
    },

    teams: function (slug) {
      var c = comp(slug) || {};
      return Api.json('https://site.api.espn.com/apis/site/v2/sports/soccer/' + slug + '/teams', 15000).then(function (r) {
        var lg = (((r.sports || [])[0] || {}).leagues || [])[0] || {};
        return (lg.teams || []).map(function (x) {
          var t = x.team || {};
          return { id: String(t.id || ''), name: t.displayName || '', logo: ((t.logos || [])[0] || {}).href || '', nat: !!c.nat, slug: slug };
        }).filter(function (t) { return t.id; }).sort(function (a, b) { return a.name < b.name ? -1 : 1; });
      });
    },
    toggleTeam: function (t) {
      var i = prefs.teams.findIndex(function (x) { return x.id === t.id; });
      if (i >= 0) prefs.teams.splice(i, 1); else prefs.teams.push(t);
      return Matches.save();
    },
    toggleComp: function (slug) {
      var i = prefs.comps.indexOf(slug);
      if (i >= 0) prefs.comps.splice(i, 1); else prefs.comps.push(slug);
      return Matches.save();
    },

    /** Ranks the user's live channels for a match (regional rights holder, app language, learned choice). */
    rankChannels: function (m, channels, subId) {
      var hints = HINTS[m.slug] || [];
      var ar = lang() === 'ar';
      var learnedMatch = prefs.picks[subId + '|m|' + m.id];
      var learnedFamily = prefs.picks[subId + '|' + m.slug];
      function family(n) { return String(n).toLowerCase().replace(/\d+/g, '#').trim(); }
      var out = [];
      for (var i = 0; i < channels.length; i++) {
        var ch = channels[i];
        var text = (ch.name || '').toLowerCase();
        var score = 0, hinted = false;
        // sports channels only: "beIN BOX OFFICE", "MBC 2" or a movies channel is never the match channel
        if (!/sport|سبورت|رياض|ssc|thmanyah|ثمانية|alkass|al kass|الكاس|action|اكشن|أكشن/.test(text)) continue;
        if (/box ?office|movie|cinema|سينما|افلام|أفلام|film|drama|دراما|kids|اطفال|أطفال|junior|jeem|news|اخبار|أخبار|series|مسلسل|documentar|وثائقي|gourmet|nat ?geo|discovery|music|موسيقى|quran|قرآن|comedy|كوميدي/.test(text)) continue;
        score = 10;
        hints.forEach(function (h, idx) { if (text.indexOf(h) >= 0) { score = Math.max(score, 70 - idx * 8); hinted = true; } });
        var isAr = /(^|[^a-z])(ar|arab|arabic)([^a-z]|$)/.test(text) || /[؀-ۿ]/.test(text);
        var isEn = /(^|[^a-z])(en|eng|english|uk)([^a-z]|$)/.test(text);
        var other = /(^|[^a-z])(es|esp|spain|spanish|fr|french|tr|turk|de|ger|it|ita|pt|por|in|hindi|ru|pl|nl|us|usa)([^a-z]|$)/.test(text);
        score += ar ? (isAr ? 40 : isEn ? -20 : other ? -80 : 0) : (isEn ? 40 : isAr ? -10 : other ? -80 : 0);
        if (/\b(fhd|uhd|4k)\b/.test(text)) score += 6; else if (/\bhd\b/.test(text)) score += 4; else if (/\bsd\b/.test(text)) score -= 6;
        if (learnedFamily && family(ch.name) === learnedFamily) score += 80;
        if (learnedMatch === ch.id) score += 500;
        if (score > 0) out.push({ ch: ch, score: score });
      }
      out.sort(function (a, b) { return b.score - a.score; });
      return out;
    },
    /** The channel carries the competition's regional rights holder (beIN for the Premier League …). */
    isRightsHolder: function (m, name) {
      var text = String(name || '').toLowerCase();
      return (HINTS[m.slug] || []).some(function (h) { return text.indexOf(h) >= 0; });
    },
    /** Words that identify a team in a programme guide (English + common Arabic spellings). */
    teamTokens: function (team) {
      var AR = { 'real madrid': ['ريال مدريد'], barcelona: ['برشلونة'], atletico: ['أتلتيكو', 'اتلتيكو'], 'real sociedad': ['ريال سوسيداد'],
        rayo: ['رايو'], villarreal: ['فياريال'], betis: ['بيتيس'], sevilla: ['إشبيلية', 'اشبيلية'], valencia: ['فالنسيا'],
        'manchester united': ['مانشستر يونايتد'], 'man utd': ['مانشستر يونايتد'], 'manchester city': ['مانشستر سيتي'], 'man city': ['مانشستر سيتي'],
        liverpool: ['ليفربول'], arsenal: ['أرسنال', 'آرسنال', 'ارسنال'], chelsea: ['تشيلسي'], tottenham: ['توتنهام'], 'aston villa': ['أستون فيلا', 'استون فيلا'],
        newcastle: ['نيوكاسل'], bayern: ['بايرن'], dortmund: ['دورتموند'], leverkusen: ['ليفركوزن'], freiburg: ['فرايبورغ'], juventus: ['يوفنتوس'],
        inter: ['إنتر', 'انتر'], milan: ['ميلان'], napoli: ['نابولي'], roma: ['روما'], psg: ['باريس'], 'paris saint-germain': ['باريس'],
        hilal: ['الهلال'], nassr: ['النصر'], ittihad: ['الاتحاد'], ahli: ['الأهلي', 'الاهلي'], shabab: ['الشباب'], qadsiah: ['القادسية'], ettifaq: ['الاتفاق'] };
      var out = [], generic = ['fc', 'cf', 'club', 'united', 'city', 'real', 'al', 'de', 'sc', 'ac', 'afc', 'sporting'];
      [team.name, team.short].forEach(function (n) {
        n = String(n || '').toLowerCase(); if (!n) return;
        out.push(n);
        Object.keys(AR).forEach(function (k) { if (n.indexOf(k) >= 0) out = out.concat(AR[k]); });
        n.split(/[^a-z\u00c0-\u024f]+/).forEach(function (w) { if (w.length >= 5 && generic.indexOf(w) < 0) out.push(w); });
      });
      return out.filter(function (x, i) { return out.indexOf(x) === i; });
    },
    remember: function (m, subId, ch) {
      prefs.picks[subId + '|m|' + m.id] = ch.id;
      prefs.picks[subId + '|' + m.slug] = String(ch.name).toLowerCase().replace(/\d+/g, '#').trim();
      return Matches.save();
    }
  };

  global.Clean = { full: cleanFull, title: function (s) { return cleanFull(s).title; }, se: seasonEpisode };
  global.Tmdb = Tmdb;
  global.Matches = Matches;

  /**
   * YouTube Data API: finds a trailer when TMDB has none (Arabic / Turkish titles). 100 searches a day for the
   * whole project, so every answer is remembered (also "nothing found") and never searched again.
   */
  var ytCache = null;
  /** Title comparison that ignores Arabic letter variants, diacritics and punctuation. */
  function ytNorm(s) {
    return String(s || '').toLowerCase().replace(/[\u064B-\u0652\u0640]/g, '').replace(/[أإآ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي')
      .replace(/[^a-z0-9\u0600-\u06FF\u00C0-\u024F]+/g, ' ').replace(/\s+/g, ' ').trim();
  }
  global.YouTube = {
    enabled: function () { return !!(global.NOVA_CONFIG && NOVA_CONFIG.yt); },
    /** Does a video title name season n ("Season 2", "S02", "الموسم الثاني", "الجزء 2", "2. Sezon")? */
    seasonTest: function (n) {
      var ar = ['', 'الاول', 'الثاني', 'الثالث', 'الرابع', 'الخامس', 'السادس', 'السابع', 'الثامن', 'التاسع', 'العاشر'];
      function re(k) {
        var num = '(?:0?' + k + '|' + String(k).replace(/\d/g, function (d) { return '٠١٢٣٤٥٦٧٨٩'[d]; }) + (ar[k] ? '|' + ar[k] : '') + ')';
        return new RegExp('(?:season|sezon|الموسم|موسم|الجزء|جزء)\\s*' + num + '(?![0-9])|(?:^|\\s)s0?' + k + '(?![0-9])|(?:^|\\s)' + k + '\\s*sezon', 'i');
      }
      var mine = re(n), others = [];
      for (var k = 1; k <= 30; k++) if (k !== n) others.push(re(k));
      return function (title) {
        var t = ytNorm(title);
        if (mine.test(t)) return true;
        // season 1 is often just "the trailer": fine as long as no other season is named
        return n === 1 && !others.some(function (r) { return r.test(t); });
      };
    },
    find: async function (query, names, check) {
      if (!this.enabled() || !query) return '';
      if (!ytCache) ytCache = (await Store.get('ytTrailers2')) || {};
      var key = query.toLowerCase();
      if (ytCache[key] !== undefined) return ytCache[key];
      var url = 'https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10&videoEmbeddable=true&safeSearch=moderate' +
        '&q=' + encodeURIComponent(query) + '&key=' + encodeURIComponent(NOVA_CONFIG.yt);
      var r;
      try { r = await Api.json(url, 12000); } catch (e) { return ''; }   // quota used up / offline: try again another time
      var items = (r && r.items) || [];
      // the video title must name this title (any of its names), and look like a trailer / promo
      var wanted = (names || []).map(ytNorm).filter(function (n) { return n.length >= 2; });
      var words = /trailer|teaser|promo|official|اعلان|إعلان|برومو|تريلر|الاعلان الرسمي|fragman|tanıtım/i;
      var music = /اغنية|أغنية|كليب|music video|lyrics|official video|official audio|\bmv\b/i;
      function names_ok(it) { var tt = ytNorm((it.snippet && it.snippet.title) || ''); return wanted.some(function (n) { return tt.indexOf(n) >= 0; }); }
      var good = items.filter(function (it) { var tt = (it.snippet && it.snippet.title) || ''; return names_ok(it) && words.test(tt) && !music.test(tt) && (!check || check(tt)); });
      var best = good[0];
      var id = best && best.id && best.id.videoId ? best.id.videoId : '';
      ytCache[key] = id;
      Store.set('ytTrailers2', ytCache);
      return id;
    }
  };
})(window);
