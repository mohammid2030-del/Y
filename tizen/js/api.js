/* Provider access: Xtream Codes API, M3U parsing, short EPG. ES2017. */
(function (global) {
  'use strict';

  function ApiError(kind, code) { this.kind = kind; this.code = code || 0; }

  function normalizeServer(input) {
    var s = String(input || '').trim();
    if (!s) return s;
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    ['/player_api.php', '/get.php', '/xmltv.php', '/panel_api.php', '/c/'].forEach(function (m) {
      var i = s.toLowerCase().indexOf(m);
      if (i > 0) s = s.slice(0, i);
    });
    var q = s.indexOf('?');
    if (q > 0) s = s.slice(0, q);
    return s.replace(/\/+$/, '');
  }

  var enc = encodeURIComponent;

  function xurl(sub, action, extra) {
    var u = sub.server + '/player_api.php?username=' + enc(sub.user) + '&password=' + enc(sub.pass);
    if (action) u += '&action=' + action;
    if (extra) u += '&' + extra;
    return u;
  }

  function request(url, timeoutMs) {
    if (!/^https?:\/\//i.test(url)) return Promise.reject(new ApiError('invalid'));
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, timeoutMs || 45000);
    return fetch(url, { signal: ctrl ? ctrl.signal : undefined, credentials: 'omit', cache: 'no-store' })
      .then(function (res) {
        clearTimeout(timer);
        if (res.status === 401 || res.status === 403) throw new ApiError('auth', res.status);
        if (!res.ok) throw new ApiError('http', res.status);
        return res;
      }, function (err) {
        clearTimeout(timer);
        throw new ApiError(err && err.name === 'AbortError' ? 'timeout' : 'network');
      });
  }

  function getJson(url, timeoutMs) {
    return request(url, timeoutMs).then(function (res) {
      return res.text().then(function (txt) {
        try { return JSON.parse(txt); } catch (e) { throw new ApiError('parse'); }
      });
    });
  }

  function getText(url, timeoutMs) {
    return request(url, timeoutMs).then(function (res) { return res.text(); });
  }

  function asList(v) {
    if (Array.isArray(v)) return v;
    if (v && typeof v === 'object') return Object.keys(v).map(function (k) { return v[k]; }).filter(function (x) { return x && typeof x === 'object'; });
    return [];
  }

  function str(v) { return v == null ? '' : String(v); }
  function num(v) { var n = parseInt(v, 10); return isNaN(n) ? 0 : n; }

  function parseAuth(json) {
    var info = json && json.user_info;
    if (!info) return { authenticated: false, status: '', expires: 0, maxConn: 0 };
    var a = str(info.auth);
    return {
      authenticated: a === '1' || a === 'true',
      status: str(info.status),
      expires: num(info.exp_date) * 1000,
      maxConn: num(info.max_connections)
    };
  }

  function auth(sub) {
    return getJson(xurl(sub), 20000).then(function (json) {
      var a = parseAuth(json);
      if (!a.authenticated) throw new ApiError('auth');
      if (a.status && a.status.toLowerCase() !== 'active') throw new ApiError('expired');
      return a;
    });
  }

  function cats(list) {
    return asList(list).filter(function (c) { return c.category_id != null; })
      .map(function (c) { return { id: str(c.category_id), name: str(c.category_name) || str(c.category_id) }; });
  }

  function loadXtream(sub, progress) {
    progress('connecting', 3, 0);
    return auth(sub).then(function (a) {
      progress('content', 10, 0);
      var total = 0, done = 0;
      function step(n) { total += n; done++; progress('content', 10 + done * 13, total); }
      var jobs = [
        getJson(xurl(sub, 'get_live_categories')).then(cats),
        getJson(xurl(sub, 'get_vod_categories')).then(cats),
        getJson(xurl(sub, 'get_series_categories')).then(cats),
        getJson(xurl(sub, 'get_live_streams'), 120000).then(function (j) {
          var out = asList(j).filter(function (s) { return s.stream_id != null; }).map(function (s, i) {
            return {
              id: str(s.stream_id), name: str(s.name).trim(), cat: str(s.category_id), logo: str(s.stream_icon),
              num: num(s.num) || i + 1, epg: str(s.epg_channel_id), archive: str(s.tv_archive) === '1',
              archiveDays: num(s.tv_archive_duration)
            };
          });
          step(out.length); return out;
        }),
        getJson(xurl(sub, 'get_vod_streams'), 120000).then(function (j) {
          var out = asList(j).filter(function (s) { return s.stream_id != null; }).map(function (s) {
            return {
              id: str(s.stream_id), name: str(s.name).trim(), cat: str(s.category_id), logo: str(s.stream_icon),
              ext: str(s.container_extension), rating: str(s.rating) === '0' ? '' : str(s.rating),
              year: str(s.year) || str(s.releaseDate).slice(0, 4), added: num(s.added)
            };
          });
          step(out.length); return out;
        }),
        getJson(xurl(sub, 'get_series'), 120000).then(function (j) {
          var out = asList(j).filter(function (s) { return s.series_id != null; }).map(function (s) {
            return {
              id: str(s.series_id), name: str(s.name).trim(), cat: str(s.category_id), logo: str(s.cover),
              rating: str(s.rating) === '0' ? '' : str(s.rating), plot: str(s.plot),
              year: str(s.year) || (str(s.releaseDate) || str(s.release_date)).slice(0, 4), added: num(s.last_modified)
            };
          });
          step(out.length); return out;
        })
      ];
      return Promise.all(jobs).then(function (r) {
        return {
          auth: a,
          cats: { live: withOrphans(r[0], r[3]), movie: withOrphans(r[1], r[4]), series: withOrphans(r[2], r[5]) },
          live: r[3], movie: r[4], series: r[5]
        };
      });
    });
  }

  function withOrphans(categories, items) {
    var known = {};
    categories.forEach(function (c) { known[c.id] = true; });
    items.forEach(function (it) {
      if (!known[it.cat]) { known[it.cat] = true; categories.push({ id: it.cat, name: it.cat || I18n.t('other') }); }
    });
    return categories;
  }

  // ---- M3U ----
  var ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  function attrs(line) {
    var out = {}, m;
    ATTR.lastIndex = 0;
    while ((m = ATTR.exec(line)) !== null) out[m[1].toLowerCase()] = m[2];
    return out;
  }
  function titleSep(line) {
    var q = false;
    for (var i = 0; i < line.length; i++) {
      var ch = line[i];
      if (ch === '"') q = !q; else if (ch === ',' && !q) return i;
    }
    return -1;
  }
  function hashId(s) {
    var h1 = 5381, h2 = 52711;
    for (var i = 0; i < s.length; i++) { var c = s.charCodeAt(i); h1 = (h1 * 33) ^ c; h2 = (h2 * 33) ^ c; }
    return (h1 >>> 0).toString(36) + (h2 >>> 0).toString(36);
  }
  var VOD_EXT = /\.(mp4|mkv|avi|mov|wmv|m4v|webm)$/i;

  function parseM3u(text) {
    var lines = text.split(/\r?\n/);
    var live = [], movie = [], liveCats = [], movieCats = [], seenL = {}, seenM = {};
    var header = false, pending = null, epgUrl = '';
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (i === 0) line = line.replace(/^﻿/, '');
      if (!line) continue;
      if (/^#EXTM3U/i.test(line)) {
        header = true;
        var h = attrs(line);
        epgUrl = (h['x-tvg-url'] || h['url-tvg'] || '').split(',')[0].trim();
      } else if (/^#EXTINF/i.test(line)) {
        header = true;
        var sep = titleSep(line);
        var a = attrs(sep >= 0 ? line.slice(0, sep) : line);
        pending = { a: a, name: sep >= 0 ? line.slice(sep + 1).trim() : '', group: '' };
      } else if (/^#EXTGRP:/i.test(line)) {
        if (pending) pending.group = line.slice(8).trim();
      } else if (line[0] === '#') {
        continue;
      } else if (pending) {
        var url = line, base = url.split('?')[0].toLowerCase();
        var isVod = base.indexOf('/movie/') >= 0 || base.indexOf('/series/') >= 0 || VOD_EXT.test(base);
        var group = pending.a['group-title'] || pending.group || '';
        var item = {
          id: hashId(url), name: pending.name || pending.a['tvg-name'] || url.split('/').pop(),
          cat: group, logo: pending.a['tvg-logo'] || '', url: url, epg: pending.a['tvg-id'] || ''
        };
        if (isVod) {
          movie.push(item);
          if (!seenM[group]) { seenM[group] = true; movieCats.push({ id: group, name: group || I18n.t('other') }); }
        } else {
          item.num = live.length + 1;
          live.push(item);
          if (!seenL[group]) { seenL[group] = true; liveCats.push({ id: group, name: group || I18n.t('other') }); }
        }
        pending = null;
      } else if (!header && i > 5) {
        break;
      }
    }
    if (!header || (live.length === 0 && movie.length === 0)) throw new ApiError('notPlaylist');
    return { live: live, movie: movie, series: [], cats: { live: liveCats, movie: movieCats, series: [] }, epgUrl: epgUrl };
  }

  function loadM3u(sub, progress) {
    progress('connecting', 5, 0);
    return getText(sub.m3uUrl, 180000).then(function (text) {
      progress('playlist', 40, 0);
      var r = parseM3u(text);
      progress('playlist', 70, r.live.length + r.movie.length);
      return r;
    });
  }

  // ---- details / EPG (Xtream) ----
  function b64utf8(s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))); } catch (e) { return String(s); }
  }

  function mapEpg(list) {
    return asList(list).map(function (p) {
      return {
        title: b64utf8(p.title), desc: b64utf8(p.description),
        start: num(p.start_timestamp) * 1000, end: num(p.stop_timestamp) * 1000,
        archive: str(p.has_archive) === '1'
      };
    }).filter(function (p) { return p.start > 0; }).sort(function (a, b) { return a.start - b.start; });
  }

  function shortEpg(sub, streamId) {
    if (sub.type !== 'xtream') return Promise.resolve([]);
    return getJson(xurl(sub, 'get_short_epg', 'stream_id=' + enc(streamId) + '&limit=4'), 15000)
      .then(function (j) { return mapEpg(j && j.epg_listings); })
      .catch(function () { return []; });
  }

  function dayEpg(sub, streamId) {
    if (sub.type !== 'xtream') return Promise.resolve([]);
    return getJson(xurl(sub, 'get_simple_data_table', 'stream_id=' + enc(streamId)), 30000)
      .then(function (j) { return mapEpg(j && j.epg_listings); });
  }

  function seriesInfo(sub, id) {
    return getJson(xurl(sub, 'get_series_info', 'series_id=' + enc(id)), 30000).then(function (j) {
      var info = (j && j.info) || {};
      var seasons = {};
      function add(e, key) {
        if (!e || e.id == null) return;
        var s = num(e.season) || key || 1;
        var ei = e.info || {};
        (seasons[s] = seasons[s] || []).push({
          id: str(e.id), season: s, num: num(e.episode_num), title: str(e.title), ext: str(e.container_extension),
          plot: str(ei.plot), image: str(ei.movie_image), duration: num(ei.duration_secs)
        });
      }
      var eps = j && j.episodes;
      if (Array.isArray(eps)) eps.forEach(function (x) { if (Array.isArray(x)) x.forEach(function (e) { add(e); }); else add(x); });
      else if (eps && typeof eps === 'object') Object.keys(eps).forEach(function (k) {
        var v = eps[k]; (Array.isArray(v) ? v : [v]).forEach(function (e) { add(e, num(k)); });
      });
      Object.keys(seasons).forEach(function (k) { seasons[k].sort(function (a, b) { return a.num - b.num; }); });
      return {
        name: str(info.name), plot: str(info.plot), cover: str(info.cover), genre: str(info.genre),
        rating: str(info.rating), year: (str(info.releaseDate) || str(info.release_date)).slice(0, 4), seasons: seasons,
        tmdb: (str(info.tmdb) || str(info.tmdb_id)).replace(/\D/g, ''),
        backdrop: str(Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path)
      };
    });
  }

  function vodInfo(sub, id) {
    if (sub.type !== 'xtream') return Promise.resolve(null);
    return getJson(xurl(sub, 'get_vod_info', 'vod_id=' + enc(id)), 20000).then(function (j) {
      var i = (j && j.info) || {};
      var bd = Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : i.backdrop_path;
      return {
        plot: str(i.plot || i.description), genre: str(i.genre), duration: str(i.duration),
        year: str(i.releasedate || i.release_date).slice(0, 4), rating: str(i.rating), cast: str(i.cast || i.actors),
        director: str(i.director), image: str(i.movie_image || i.cover_big), backdrop: str(bd),
        tmdb: (str(i.tmdb_id) || str(i.tmdb)).replace(/\D/g, '')
      };
    }).catch(function () { return null; });
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  /** Builds the playable URL in memory (credentials never stored in history/favorites). */
  function streamUrl(sub, kind, item, opts) {
    opts = opts || {};
    if (sub.type !== 'xtream') return item.url || '';
    var base = sub.server, u = enc(sub.user), p = enc(sub.pass);
    if (opts.catchupStart) {
      var d = new Date(opts.catchupStart);
      var start = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ':' + pad(d.getHours()) + '-' + pad(d.getMinutes());
      return base + '/timeshift/' + u + '/' + p + '/' + opts.duration + '/' + start + '/' + item.id + '.ts';
    }
    if (kind === 'live') return base + '/live/' + u + '/' + p + '/' + item.id + '.' + (sub.liveFormat === 'ts' ? 'ts' : 'm3u8');
    if (kind === 'movie') return base + '/movie/' + u + '/' + p + '/' + item.id + '.' + (item.ext || 'mp4');
    return base + '/series/' + u + '/' + p + '/' + opts.episodeId + '.' + (opts.ext || 'mp4');
  }

  /** Plain JSON GET for third-party services (TMDB, ESPN). */
  function json(url, timeoutMs, headers) {
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, timeoutMs || 15000);
    return fetch(url, { signal: ctrl ? ctrl.signal : undefined, headers: headers || {} }).then(function (res) {
      clearTimeout(timer);
      if (!res.ok) throw new ApiError(res.status === 401 ? 'auth' : 'http', res.status);
      return res.json();
    }, function (e) { clearTimeout(timer); throw new ApiError('network'); });
  }

  global.Api = {
    json: json,
    ApiError: ApiError, normalizeServer: normalizeServer, parseAuth: parseAuth, parseM3u: parseM3u,
    auth: auth, loadXtream: loadXtream, loadM3u: loadM3u, shortEpg: shortEpg, dayEpg: dayEpg,
    seriesInfo: seriesInfo, vodInfo: vodInfo, streamUrl: streamUrl, b64utf8: b64utf8,
    testM3u: function (url) {
      return getText(url, 60000).then(function (t) { var r = parseM3u(t); return r.live.length + r.movie.length; });
    }
  };
})(window);
