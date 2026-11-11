/*
 * Playback engine. On Samsung TVs uses the native AVPlay API (HLS, MPEG-TS, MP4, MKV…);
 * in a desktop browser falls back to <video> so the UI can be tested.
 */
(function (global) {
  'use strict';

  var hasAvplay = !!(global.webapis && webapis.avplay);
  var listeners = {};
  var video = null;
  var subtitleTimer = null;

  function emit(name, a, b) { if (listeners[name]) listeners[name](a, b); }

  function avplayListener() {
    return {
      onbufferingstart: function () { emit('buffering', true); },
      onbufferingprogress: function () {},
      onbufferingcomplete: function () { emit('buffering', false); },
      oncurrentplaytime: function (ms) { emit('time', ms); },
      onstreamcompleted: function () { emit('ended'); },
      onerror: function (type) { emit('error', String(type)); },
      onevent: function () {},
      onsubtitlechange: function (duration, text) {
        emit('subtitle', text);
        clearTimeout(subtitleTimer);
        subtitleTimer = setTimeout(function () { emit('subtitle', ''); }, Math.max(1000, parseInt(duration, 10) || 3000));
      },
      ondrmevent: function () {}
    };
  }

  function stop() {
    if (hasAvplay) {
      try {
        var st = webapis.avplay.getState();
        if (st !== 'NONE' && st !== 'IDLE') webapis.avplay.stop();
        webapis.avplay.close();
      } catch (e) { /* ignore */ }
    } else if (video) {
      video.pause(); video.removeAttribute('src'); video.load();
    }
    emit('subtitle', '');
  }

  /**
   * opts: { userAgent, startMs, bufferSec }
   * Resolves when playback starts; rejects on failure.
   */
  function open(url, opts) {
    opts = opts || {};
    stop();
    if (hasAvplay) {
      return new Promise(function (resolve, reject) {
        try {
          webapis.avplay.open(url);
          webapis.avplay.setDisplayRect(0, 0, 1920, 1080);
          try { webapis.avplay.setDisplayMethod(currentAspect); } catch (e) { /* older firmware */ }
          if (opts.userAgent) { try { webapis.avplay.setStreamingProperty('USER_AGENT', opts.userAgent); } catch (e) { } }
          if (opts.bufferSec) {
            try {
              webapis.avplay.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', Math.min(opts.bufferSec, 20));
              webapis.avplay.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', Math.min(opts.bufferSec, 20));
            } catch (e) { }
          }
          webapis.avplay.setListener(avplayListener());
          webapis.avplay.prepareAsync(function () {
            var start = function () {
              try { webapis.avplay.play(); resolve(); } catch (e) { reject(e); }
            };
            if (opts.startMs > 0) {
              try { webapis.avplay.seekTo(opts.startMs, start, start); } catch (e) { start(); }
            } else start();
          }, function (err) { reject(err); });
        } catch (e) { reject(e); }
      });
    }
    // desktop fallback
    if (!video) {
      video = document.createElement('video');
      video.id = 'html5-player';
      document.body.insertBefore(video, document.body.firstChild);
      video.addEventListener('waiting', function () { emit('buffering', true); });
      video.addEventListener('playing', function () { emit('buffering', false); });
      video.addEventListener('timeupdate', function () { emit('time', video.currentTime * 1000); });
      video.addEventListener('ended', function () { emit('ended'); });
      video.addEventListener('error', function () { emit('error', 'MEDIA_ERROR'); });
    }
    video.src = url;
    if (opts.startMs > 0) video.currentTime = opts.startMs / 1000;
    return video.play();
  }

  function state() {
    if (hasAvplay) { try { return webapis.avplay.getState(); } catch (e) { return 'NONE'; } }
    if (!video || !video.src) return 'NONE';
    return video.paused ? 'PAUSED' : 'PLAYING';
  }

  function togglePause() {
    if (hasAvplay) {
      var s = state();
      try { if (s === 'PLAYING') webapis.avplay.pause(); else if (s === 'PAUSED' || s === 'READY') webapis.avplay.play(); } catch (e) { }
    } else if (video) { if (video.paused) video.play(); else video.pause(); }
  }

  function seekBy(deltaMs) {
    var t = time() + deltaMs, d = duration();
    if (t < 0) t = 0;
    if (d > 0 && t > d - 2000) t = d - 2000;
    if (hasAvplay) { try { webapis.avplay.seekTo(t); } catch (e) { } }
    else if (video) video.currentTime = t / 1000;
  }

  function seek(ms) {
    var d = duration(), t = Math.max(0, ms);
    if (d > 0 && t > d - 2000) t = d - 2000;
    if (hasAvplay) { try { webapis.avplay.seekTo(t); } catch (e) { } }
    else if (video) video.currentTime = t / 1000;
  }

  function time() {
    if (hasAvplay) { try { return webapis.avplay.getCurrentTime(); } catch (e) { return 0; } }
    return video ? video.currentTime * 1000 : 0;
  }

  function duration() {
    if (hasAvplay) { try { return webapis.avplay.getDuration(); } catch (e) { return 0; } }
    return video && isFinite(video.duration) ? video.duration * 1000 : 0;
  }

  /** [{index, lang, label}] for 'AUDIO' or 'TEXT' */
  function tracks(type) {
    if (!hasAvplay) return [];
    try {
      return webapis.avplay.getTotalTrackInfo().filter(function (t) { return t.type === type; }).map(function (t, i) {
        var lang = '';
        try { var ex = JSON.parse(t.extra_info); lang = ex.language || ex.track_lang || ''; } catch (e) { }
        return { index: t.index, lang: lang, label: (lang || type) + ' ' + (i + 1) };
      });
    } catch (e) { return []; }
  }

  function selectTrack(type, index) {
    if (!hasAvplay) return;
    try {
      if (type === 'TEXT') {
        if (index < 0) { webapis.avplay.setSilentSubtitle(true); emit('subtitle', ''); return; }
        webapis.avplay.setSilentSubtitle(false);
      }
      webapis.avplay.setSelectTrack(type, index);
    } catch (e) { }
  }

  var ASPECTS = ['PLAYER_DISPLAY_MODE_LETTER_BOX', 'PLAYER_DISPLAY_MODE_FULL_SCREEN', 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO'];
  var currentAspect = ASPECTS[0];
  function cycleAspect() {
    currentAspect = ASPECTS[(ASPECTS.indexOf(currentAspect) + 1) % ASPECTS.length];
    if (hasAvplay) { try { webapis.avplay.setDisplayMethod(currentAspect); } catch (e) { } }
    else if (video) video.style.objectFit = currentAspect.indexOf('FULL') >= 0 ? 'fill' : 'contain';
    return ASPECTS.indexOf(currentAspect);
  }

  function info() {
    if (!hasAvplay) return video && video.videoWidth ? video.videoWidth + '×' + video.videoHeight : '';
    try {
      var v = webapis.avplay.getTotalTrackInfo().filter(function (t) { return t.type === 'VIDEO'; })[0];
      if (!v) return '';
      var ex = JSON.parse(v.extra_info);
      var parts = [];
      if (ex.Width) parts.push(ex.Width + '×' + ex.Height);
      if (ex.Bit_rate) parts.push((ex.Bit_rate / 1e6).toFixed(1) + ' Mbps');
      if (ex.fourCC) parts.push(ex.fourCC);
      return parts.join('  ');
    } catch (e) { return ''; }
  }

  function suspend() { if (hasAvplay) { try { webapis.avplay.suspend(); } catch (e) { } } else if (video) video.pause(); }
  function restore() { if (hasAvplay) { try { webapis.avplay.restore(); } catch (e) { } } }

  global.Player = {
    on: function (name, fn) { listeners[name] = fn; },
    open: open, stop: stop, state: state, togglePause: togglePause, seekBy: seekBy, seek: seek, time: time, duration: duration,
    tracks: tracks, selectTrack: selectTrack, cycleAspect: cycleAspect, info: info, suspend: suspend, restore: restore,
    native: hasAvplay
  };
})(window);
