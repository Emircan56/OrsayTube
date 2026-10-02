/* OrsayTube Mobil v2.0 — yerel sunucu istemcisi (ES5 uyumlu)
 * Tüm istekler aynı kökene (localhost:8080) gider: /api/..., /thumb, /video
 */
(function () {
  'use strict';

  var view = document.getElementById('view');
  var searchForm = document.getElementById('searchForm');
  var searchInput = document.getElementById('searchInput');
  var serverBar = document.getElementById('serverBar');
  var serverDot = document.getElementById('serverDot');
  var serverText = document.getElementById('serverText');

  var current = { view: '', v: '', sources: [], srcIdx: -1, videoEl: null };
  var HKEY = 'lt-history-v2';
  var NATIVE = (typeof window.OrsayTubeNative !== 'undefined');
  /* v4: tarayıcı tarafı gözlemleri (oynatıcı hataları vb.) — Tanılama
   * raporuna eklenir, sunucu günlüğüyle birleşir */
  var DiagLines = [];

  /* v4.5: HLS çalma destekleri — yerel (video etiketi) veya hls.js (MSE).
   * hls.js 375KB olduğundan YALNIZCA MediaSource varsa tembel yüklenir. */
  var hlsEng = null;

  function canNativeHls(vEl) {
    try {
      var v = vEl || current.videoEl;
      return !!(v && (v.canPlayType('application/vnd.apple.mpegurl') ||
                v.canPlayType('application/x-mpegURL')));
    } catch (e) { return false; }
  }

  function hlsJsOk() {
    return !!(window.MediaSource || window.WebKitMediaSource);
  }

  function ensureHlsJs(cb) {
    if (window.Hls || !hlsJsOk()) { cb(); return; }
    var sc = document.createElement('script');
    sc.src = '/js/hls.min.js?v=45';
    sc.onload = sc.onerror = function () { cb(); };
    (document.head || document.getElementsByTagName('head')[0] || document.body).appendChild(sc);
  }

  function stopHls() {
    if (hlsEng) {
      try { hlsEng.destroy(); } catch (e) {}
      hlsEng = null;
    }
  }

  /* ================= yardımcılar ================= */

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) {
      e.appendChild(document.createTextNode(text));
    }
    return e;
  }

  function esc(s) { return String(s || ''); }

  function enc(s) {
    return encodeURIComponent(String(s || ''));
  }

  function toast(msg) {
    if (NATIVE && window.OrsayTubeNative.toast) {
      try { window.OrsayTubeNative.toast(msg); return; } catch (e) {}
    }
    var t = el('div', 'toastmsg', msg);
    document.body.appendChild(t);
    setTimeout(function () { t.className = 'toastmsg show'; }, 10);
    setTimeout(function () {
      t.className = 'toastmsg';
      setTimeout(function () { if (t.parentNode) { t.parentNode.removeChild(t); } }, 300);
    }, 2600);
  }

  function api(path, cb, timeoutMs) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', path, true);
    try { xhr.timeout = timeoutMs || 90000; } catch (e) {}
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) { return; }
      var d = null;
      try { d = JSON.parse(xhr.responseText); } catch (e) {}
      if (xhr.status >= 200 && xhr.status < 300 && d && !d.error) {
        cb(null, d);
      } else {
        cb((d && d.message) ? d.message : ('HTTP ' + xhr.status), d);
      }
    };
    xhr.ontimeout = function () { cb('Zaman aşımı (' + ((timeoutMs || 90000) / 1000) + ' sn)'); };
    xhr.onerror = function () { cb('Ağ hatası'); };
    xhr.send(null);
  }

  /* ================= yönlendirici ================= */

  function parseHash() {
    var h = location.hash || '#/';
    var path = h.replace(/^#/, '');
    var params = {};
    var qi = path.indexOf('?');
    if (qi >= 0) {
      var qs = path.substring(qi + 1).split('&');
      for (var i = 0; i < qs.length; i++) {
        var kv = qs[i].split('=');
        if (kv.length === 2) { params[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1]); }
      }
      path = path.substring(0, qi);
    }
    return { path: path, params: params };
  }

  /* Sunucu rotası /watch?v=X (uygulama intent'i) → hash'e çevir */
  (function normalizeServerRoute() {
    var p = location.pathname;
    if (p.indexOf('/watch') === 0) {
      var v = getQueryParam('v');
      if (v) {
        location.replace(location.origin + '/#/watch?v=' + enc(v));
        return;
      }
    }
    if (p !== '/' && p !== '/index.html') {
      location.replace(location.origin + '/#/');
    }
  })();

  function getQueryParam(name) {
    var m = location.search.substring(1).split('&');
    for (var i = 0; i < m.length; i++) {
      var kv = m[i].split('=');
      if (kv.length === 2 && kv[0] === name) { return decodeURIComponent(kv[1]); }
    }
    return null;
  }

  function route() {
    var r = parseHash();
    if (current.videoEl) {
      try { current.videoEl.pause(); } catch (e) {}
    }
    /* v4.5: watch'tan ayrılınca hls.js motorunu da bırak */
    if (r.path !== '/watch') { stopHls(); }
    if (r.path === '/' || r.path === '') {
      current.view = 'home';
      renderHome();
    } else if (r.path === '/results') {
      current.view = 'results';
      renderSearch(r.params.q || '');
    } else if (r.path === '/watch') {
      current.view = 'watch';
      renderWatch(r.params.v || '');
    } else if (r.path === '/history') {
      current.view = 'history';
      renderHistory();
    } else if (r.path === '/about') {
      current.view = 'about';
      renderAbout();
    } else if (r.path === '/diag') {
      current.view = 'diag';
      renderDiag();
    } else {
      location.hash = '#/';
    }
    highlightNav();
  }

  function highlightNav() {
    var navs = document.querySelectorAll('[data-nav]');
    for (var i = 0; i < navs.length; i++) {
      navs[i].className = (navs[i].getAttribute('data-nav') === current.view) ? 'active' : '';
    }
  }

  window.addEventListener('hashchange', route);

  searchForm.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var q = (searchInput.value || '').trim();
    if (!q) { return; }
    location.hash = '#/results?q=' + enc(q);
  });

  /* ================= sunucu durumu ================= */

  function pollServer() {
    api('/api/ping', function (err, d) {
      if (err) {
        serverBar.className = 'serverbar err';
        serverText.textContent = 'Yerel sunucuya ulaşılamıyor — uygulamayı yeniden açın';
      } else {
        serverBar.className = 'serverbar on';
        serverText.textContent = 'Sunucu çalışıyor · localhost:' + (d && d.port ? d.port : '?');
      }
    });
  }
  pollServer();
  setInterval(pollServer, 15000);

  /* ================= kartlar ================= */

  function card(videoId, title, channel, duration) {
    var a = el('a', 'card');
    a.href = '#/watch?v=' + enc(videoId);
    var tw = el('div', 'thumbwrap');
    var img = el('img');
    img.src = '/thumb?v=' + enc(videoId);
    img.alt = '';
    img.loading = 'lazy';
    tw.appendChild(img);
    if (duration) { tw.appendChild(el('span', 'dur', duration)); }
    a.appendChild(tw);
    a.appendChild(el('div', 'ct', esc(title)));
    if (channel) { a.appendChild(el('div', 'cc', esc(channel))); }
    return a;
  }

  function skeletons(n) {
    var g = el('div', 'grid');
    for (var i = 0; i < (n || 8); i++) {
      var c = el('div', 'skcard');
      var t = el('div', 'sk t');
      var l = el('div', 'sk l');
      var l2 = el('div', 'sk l2');
      c.appendChild(t); c.appendChild(l); c.appendChild(l2);
      g.appendChild(c);
    }
    return g;
  }

  function gridOf(items) {
    var g = el('div', 'grid');
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      g.appendChild(card(it.videoId, it.title, it.channel, it.duration));
    }
    return g;
  }

  function errorBox(msg, retryFn) {
    var box = el('div', 'errbox');
    box.appendChild(el('div', 'big', '⚠'));
    box.appendChild(el('div', 'msg', esc(msg)));
    var b = el('button', 'btn', 'Tekrar dene');
    b.addEventListener('click', function () { retryFn(); });
    box.appendChild(b);
    return box;
  }

  /* ================= ana sayfa ================= */

  /* v4.4: offline yedek görünürse 45sn'de bir KENDİSİ yeniden dener —
     kullanıcı düğmeye basmadan gerçek trendler gelince liste tazelenir */
  var homeRetryTimer = null;

  function renderHome() {
    if (homeRetryTimer) { clearTimeout(homeRetryTimer); homeRetryTimer = null; }
    view.innerHTML = '';
    var head = el('div', 'sechead');
    head.appendChild(el('h2', null, 'Trendler'));
    var muted = el('span', 'muted', 'yükleniyor…');
    head.appendChild(muted);
    view.appendChild(head);
    var sk = skeletons(8);
    view.appendChild(sk);

    api('/api/home', function (err, d) {
      if (current.view !== 'home') { return; }
      if (err) {
        view.innerHTML = '';
        view.appendChild(errorBox('Trendler alınamadı: ' + err + ' — aramayı deneyin.', renderHome));
        return;
      }
      var items = (d && d.items) || [];
      muted.textContent = items.length + ' video · kaynak: ' + ((d && d.via) || '-');
      view.replaceChild(gridOf(items), sk);
      /* v4.3: trend kaynakları ölmediyse offline sabit listesi GERİDE kalır —
         listeye düşünce kullanıcıya sebep + yeniden deneme düğmesi göster */
      if (d && d.via === 'offline-liste') {
        var warn = el('div', 'offwarn');
        warn.appendChild(el('span', null,
          '⚠ Gerçek trend listesi alınamadı — yedek liste gösteriliyor. ' +
          'Otomatik yeniden deneniyor…'));
        var rb = el('button', 'btn ghost', 'Yeniden dene');
        rb.addEventListener('click', function () { renderHome(); });
        warn.appendChild(rb);
        view.insertBefore(warn, view.childNodes[1] || null);
        /* v4.4: 45sn sonra sessizce tekrar dene (ana sayfada kalındıkça sürer) */
        homeRetryTimer = setTimeout(function () {
          homeRetryTimer = null;
          if (current.view === 'home') { renderHome(); }
        }, 45000);
      }
    });
  }

  /* ================= arama ================= */

  function renderSearch(q) {
    if (searchInput && searchInput.value !== q) { searchInput.value = q; }
    view.innerHTML = '';
    var head = el('div', 'sechead');
    head.appendChild(el('h2', null, 'Sonuçlar: "' + q + '"'));
    view.appendChild(head);
    var sk = skeletons(8);
    view.appendChild(sk);

    api('/api/search?q=' + enc(q), function (err, d) {
      if (current.view !== 'results') { return; }
      view.innerHTML = '';
      var head2 = el('div', 'sechead');
      if (err) {
        head2.appendChild(el('h2', null, 'Sonuçlar: "' + q + '"'));
        view.appendChild(head2);
        view.appendChild(errorBox('Arama başarısız: ' + err, function () { renderSearch(q); }));
        return;
      }
      var items = (d && d.items) || [];
      head2.appendChild(el('h2', null, 'Sonuçlar: "' + q + '"'));
      head2.appendChild(el('span', 'muted', items.length + ' sonuç · ' + ((d && d.via) || '-')));
      view.appendChild(head2);
      if (items.length === 0) {
        var em = el('div', 'empty');
        em.appendChild(el('div', 'big', '🔍'));
        em.appendChild(el('p', null, 'Sonuç bulunamadı. Farklı kelimeler deneyin.'));
        view.appendChild(em);
        return;
      }
      view.appendChild(gridOf(items));
    });
  }

  /* ================= oynatıcı ================= */

  function renderWatch(v) {
    view.innerHTML = '';
    var box = el('div', 'watchbox');

    var pw = el('div', 'pwrap');
    var vid = document.createElement('video');
    vid.controls = true;
    vid.setAttribute('playsinline', '');
    vid.setAttribute('webkit-playsinline', '');
    vid.preload = 'metadata';
    vid.setAttribute('autoplay', 'autoplay');
    pw.appendChild(vid);

    var perr = el('div', 'perr');
    perr.appendChild(el('div', 'big', '🎬'));
    perr.appendChild(el('div', 'msg',
      'Video açılamadı. Kaynaklar sırayla denendi.'));
    pw.appendChild(perr);

    var meta = el('div', 'wmeta');
    var title = el('div', 'wtitle', 'Yükleniyor…');
    var sub = el('div', 'wsub');
    var chips = el('div', 'chips');
    var note = el('div', 'vid-note',
      'Kalite: 360p standart (H.264). Kaynak hata verirse otomatik yedeklere geçilir.');

    /* v4: çözümleme sürmekte — süre sayacı (telefonda ilk çözümleme
     * 10-30 sn sürebilir; sessiz bekleyiş 'çalışmıyor' hissi vermesin) */
    var waitNote = el('div', 'vid-note wait');
    var waitSec = 0;
    waitNote.textContent = 'Kaynaklar çözümleniyor… 0 sn (ilk açılışta 10-30 sn sürebilir)';
    var waitTimer = setInterval(function () {
      waitSec++;
      waitNote.textContent = 'Kaynaklar çözümleniyor… ' + waitSec
        + ' sn' + (waitSec > 25 ? ' — çok uzun sürüyor, Tanılama sayfasını dene' : '');
    }, 1000);

    meta.appendChild(title);
    meta.appendChild(sub);
    meta.appendChild(waitNote);
    meta.appendChild(chips);
    meta.appendChild(note);

    box.appendChild(pw);
    box.appendChild(meta);

    var relHead = el('div', 'relhead', 'Benzer videolar');
    var relGrid = el('div', 'relgrid');
    box.appendChild(relHead);
    box.appendChild(relGrid);

    view.appendChild(box);
    current.v = v;
    current.videoEl = vid;
    current.sources = [];
    current.srcIdx = -1;

    api('/api/streams?v=' + enc(v), function (err, d) {
      if (current.view !== 'watch' || current.v !== v) { return; }
      clearInterval(waitTimer);
      waitNote.parentNode.removeChild(waitNote);
      if (err) {
        title.textContent = 'Video yüklenemedi';
        perr.className = 'perr show';
        perr.querySelector('.msg').textContent = 'Bilgi alınamadı: ' + err;
        var b = el('button', 'btn', 'Tekrar dene');
        b.addEventListener('click', function () { renderWatch(v); });
        perr.appendChild(b);
        var bd = el('button', 'btn ghost', 'Tanılamayı çalıştır');
        bd.addEventListener('click', function () { location.hash = '#/diag'; });
        perr.appendChild(bd);
        return;
      }
      title.textContent = d.title || '';
      var subText = [];
      if (d.author) { subText.push(d.author); }
      if (d.duration) { subText.push(d.duration); }
      subText.push('kaynak: ' + (d.via || '-'));
      sub.appendChild(el('span', null, subText.join(' · ')));
      pushHistory(v, d.title, d.author, d.duration);

      var sources = d.sources || [];
      current.sources = sources;
      chips.innerHTML = '';
      for (var i = 0; i < sources.length; i++) {
        (function (idx) {
          var s = sources[idx];
          var chip = el('button', 'chip', s.label || ('kaynak ' + (idx + 1)));
          chip.addEventListener('click', function () { setSource(idx, true); });
          chips.appendChild(chip);
        })(i);
      }
      if (sources.length === 0) {
        perr.className = 'perr show';
        perr.querySelector('.msg').textContent =
          'Bu video için oynatılabilir kaynak bulunamadı (yaş kısıtı / kiralık içerik olabilir).';
        var bd0 = el('button', 'btn ghost', 'Tanılamayı çalıştır');
        bd0.addEventListener('click', function () { location.hash = '#/diag'; });
        perr.appendChild(bd0);
        note.textContent = '';
        return;
      }

      /* v4: dış oynatıcı kaçış kapısı — WebView oynatmazsa VLC/MX ile dene */
      if (d.direct && NATIVE && window.OrsayTubeNative.openExternal) {
        var ext = el('button', 'chip act', 'Dış oynatıcıda aç (VLC)');
        ext.addEventListener('click', function () {
          try { window.OrsayTubeNative.openExternal(d.direct); } catch (e) { }
        });
        chips.appendChild(ext);
      }

      /* v4.3: oynatma hızı çipleri (TV'deki hız tuşunun mobil eşdeğeri) */
      var SPD = [0.75, 1, 1.25, 1.5, 2];
      var spdRow = el('div', 'chips spd');
      var spdLbl = el('span', 'spdlbl', 'Hız:');
      spdRow.appendChild(spdLbl);
      for (var si = 0; si < SPD.length; si++) {
        (function (k) {
          var c = el('button', 'chip' + (k === 1 ? ' active' : ''),
            (SPD[k] === 1 ? '1×' : SPD[k] + '×'));
          c.addEventListener('click', function () {
            try { vid.playbackRate = SPD[k]; } catch (e) {}
            for (var j = 0; j < spdRow.querySelectorAll('.chip').length; j++) {
              spdRow.querySelectorAll('.chip')[j].className = 'chip';
            }
            c.className = 'chip active';
          });
          spdRow.appendChild(c);
        })(si);
      }
      meta.appendChild(spdRow);

      /* v4.5: akıllı varsayılan — HLS 1080p'ye kadar en iyi uygun kalite
       * (4K varyantı telefon şebekesinde gereksiz veri yakar; elle seçilebilir) */
      var defIdx = 0;
      for (var di = 0; di < sources.length; di++) {
        var sD = sources[di];
        if (sD.kind === 'hls') {
          var hm = /(\d{2,4})p/.exec(String(sD.label || ''));
          var hh = hm ? parseInt(hm[1], 10) : 0;
          if (hh === 0 || (hh <= 1080 && hh >= 360)) { defIdx = di; break; }
        } else {
          defIdx = di;
          break;
        }
      }
      setSource(defIdx, false);

      var related = d.related || [];
      relGrid.innerHTML = '';
      for (var r = 0; r < related.length; r++) {
        var it = related[r];
        relGrid.appendChild(card(it.videoId, it.title, it.channel, it.duration));
      }
      if (related.length === 0) {
        relHead.style.display = 'none';
      }
    });

    function setSource(idx, keepTime) {
      if (idx < 0 || idx >= current.sources.length) { return; }
      var s = current.sources[idx];
      current.srcIdx = idx;
      var t = 0;
      try { if (keepTime && vid.currentTime) { t = vid.currentTime; } } catch (e) {}
      stopHls();
      vid.onerror = function () {
        var ec = 0, emsg = '';
        try {
          ec = vid.error ? vid.error.code : 0;
          emsg = ['', 'kaynak durduruldu', 'ağ hatası', 'kod çözme hatası',
            'biçim desteklenmiyor'][ec] || ('kod ' + ec);
        } catch (e) {}
        DiagLines.push('oynatıcı hata: ' + (s.label || s.itag) + ' → ' + emsg
          + ' (kaynak ' + (idx + 1) + '/' + current.sources.length + ')');
        if (idx + 1 < current.sources.length) {
          toast('Kaynak hata verdi (' + emsg + ') — yedeğe geçiliyor: ' +
            current.sources[idx + 1].label);
          setSource(idx + 1, keepTime);
        } else {
          perr.className = 'perr show';
          perr.querySelector('.msg').textContent =
            'Video açılamadı (' + emsg + '). Kaynakların tamamı denendi.';
          var bdx = el('button', 'btn ghost', 'Tanılamayı çalıştır');
          bdx.addEventListener('click', function () { location.hash = '#/diag'; });
          perr.appendChild(bdx);
        }
      };
      /* v4.5: HLS kaynakları — yerel HLS yoksa MSE+hls.js ile çal,
       * o da yoksa video etiketiyle dene (hata zinciri yedeğe düşürür) */
      if (s.kind === 'hls' && !canNativeHls(vid)) {
        var vv = v;
        ensureHlsJs(function () {
          if (current.v !== vv || current.srcIdx !== idx || current.view !== 'watch') { return; }
          if (window.Hls && window.Hls.isSupported && window.Hls.isSupported()) {
            try {
              hlsEng = new window.Hls({ enableWorker: true });
              hlsEng.attachMedia(vid);
              hlsEng.loadSource(s.url);
              hlsEng.on(window.Hls.Events.ERROR, function (evt, data) {
                if (data && data.fatal) {
                  stopHls();
                  DiagLines.push('hls.js ölümcül hata: ' + (s.label || 'hls')
                    + ' → ' + (data.details || data.type));
                  if (idx + 1 < current.sources.length) {
                    toast('HLS hatası — yedeğe geçiliyor: ' +
                      current.sources[idx + 1].label);
                    setSource(idx + 1, true);
                  } else {
                    perr.className = 'perr show';
                    perr.querySelector('.msg').textContent =
                      'HLS akışı açılamadı. Kaynakların tamamı denendi.';
                  }
                }
              });
              try {
                vid.play().catch(function () { /* autoplay engellendi */ });
              } catch (e) {}
            } catch (e2) {
              vid.onerror();
            }
          } else {
            vid.src = s.url;
            try { vid.load(); } catch (e) {}
            try { vid.play().catch(function () {}); } catch (e) {}
          }
          if (t > 0) {
            vid.addEventListener('loadedmetadata', function onMeta() {
              vid.removeEventListener('loadedmetadata', onMeta);
              try { vid.currentTime = t; } catch (e) {}
            });
          }
        });
      } else {
        vid.src = s.url;
        try { vid.load(); } catch (e) {}
        try { vid.play().catch(function () { /* autoplay engellendi — kullanıcı oynatacak */ }); } catch (e) {}
        if (t > 0) {
          vid.addEventListener('loadedmetadata', function onMeta() {
            vid.removeEventListener('loadedmetadata', onMeta);
            try { vid.currentTime = t; } catch (e) {}
          });
        }
      }
      var chipsEls = chips.children;
      for (var c = 0; c < chipsEls.length; c++) {
        chipsEls[c].className = (c === idx) ? 'chip active' : 'chip';
      }
      perr.className = 'perr';
    }
  }

  /* ================= geçmiş ================= */

  function loadHistory() {
    try {
      var raw = localStorage.getItem(HKEY);
      if (!raw) { return []; }
      var arr = JSON.parse(raw);
      return (arr && arr.length) ? arr : [];
    } catch (e) { return []; }
  }

  function saveHistory(arr) {
    try { localStorage.setItem(HKEY, JSON.stringify(arr.slice(0, 200))); } catch (e) {}
  }

  function pushHistory(v, title, channel, duration) {
    var h = loadHistory().filter(function (x) { return x.videoId !== v; });
    h.unshift({
      videoId: v, title: title || '', channel: channel || '',
      duration: duration || '', ts: Date.now()
    });
    saveHistory(h);
  }

  function renderHistory() {
    view.innerHTML = '';
    var head = el('div', 'sechead');
    head.appendChild(el('h2', null, 'İzleme geçmişi'));
    var h = loadHistory();
    if (h.length) {
      var clear = el('button', 'chip act', 'Tümünü temizle');
      clear.addEventListener('click', function () {
        saveHistory([]);
        renderHistory();
      });
      head.appendChild(clear);
    }
    view.appendChild(head);

    if (!h.length) {
      var em = el('div', 'empty');
      em.appendChild(el('div', 'big', '🕘'));
      em.appendChild(el('p', null, 'Henüz video izlemedin. Ana sayfadan başla!'));
      view.appendChild(em);
      return;
    }
    var list = el('div');
    for (var i = 0; i < h.length; i++) {
      (function (item) {
        var row = el('div', 'histrow');
        var a = el('a');
        a.href = '#/watch?v=' + enc(item.videoId);
        var tw = el('div', 'ht');
        var img = el('img');
        img.src = '/thumb?v=' + enc(item.videoId);
        tw.appendChild(img);
        a.appendChild(tw);
        var hi = el('div', 'hi');
        hi.appendChild(el('div', 'ht2', item.title || item.videoId));
        hi.appendChild(el('div', 'hs', (item.channel || '') +
          (item.duration ? ' · ' + item.duration : '')));
        a.appendChild(hi);
        row.appendChild(a);
        var x = el('button', 'hx', '×');
        x.addEventListener('click', function () {
          saveHistory(loadHistory().filter(function (z) { return z.videoId !== item.videoId; }));
          renderHistory();
        });
        row.appendChild(x);
        list.appendChild(row);
      })(h[i]);
    }
    view.appendChild(list);
  }

  /* ================= TANILAMA (v4) ================= */

  /**
   * Tanılama ekranı — video oynamadığında HATANIN ADINI bulur.
   * Sırayla: sunucu → arama → akış çözümleme → vekil video testi →
   * WebView codec desteği → sunucu günlüğü. Rapor kopyalanabilir.
   */
  function renderDiag() {
    view.innerHTML = '';
    var box = el('div', 'diagbox');

    var head = el('div', 'sechead');
    head.appendChild(el('h2', null, 'Tanılama'));
    box.appendChild(head);
    box.appendChild(el('p', 'vid-note',
      'Video oynamıyorsa buraya bas: her adım tek tek test edilir, ' +
      'çıkan raporu kopyalayıp gönder — sorunun tam yerini görürüz.'));

    var runBtn = el('button', 'btn', 'Tanılamayı Başlat');
    box.appendChild(runBtn);

    var out = el('pre', 'diagout');
    out.textContent = 'Henüz çalıştırılmadı.';
    box.appendChild(out);

    var copyBtn = el('button', 'btn ghost', 'Raporu Kopyala');
    copyBtn.style.display = 'none';
    box.appendChild(copyBtn);

    view.appendChild(box);

    var report = [];

    function line(s) {
      report.push(s);
      out.textContent = report.join('\n');
    }

    copyBtn.addEventListener('click', function () {
      var text = 'OrsayTube v4 Tanılama Raporu\n' + report.join('\n');
      if (NATIVE && window.OrsayTubeNative.copyText) {
        try { window.OrsayTubeNative.copyText(text); return; } catch (e) {}
      }
      try {
        window.prompt('Raporu kopyala (Ctrl+C):', text);
      } catch (e) {}
    });

    /* İkili veri isteyen aralık testi (video vekili) — aynı köken olduğu
     * için Range başlığı sorunsuz gönderilebilir. */
    function probeVideo(cb) {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/video?v=dQw4w9WgXcQ&itag=18', true);
      try { xhr.timeout = 90000; } catch (e) {}
      xhr.responseType = 'arraybuffer';
      var t0 = Date.now();
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) { return; }
        var n = 0;
        try { n = xhr.response ? xhr.response.byteLength : 0; } catch (e) {}
        var ct = (xhr.getResponseHeader && xhr.getResponseHeader('Content-Type')) || '?';
        cb(xhr.status, n, ct, Date.now() - t0);
      };
      xhr.onerror = function () { cb(0, 0, '?', Date.now() - t0); };
      xhr.ontimeout = function () { cb(-1, 0, '?', Date.now() - t0); };
      try { xhr.setRequestHeader('Range', 'bytes=0-1023'); } catch (e) {}
      xhr.send(null);
    }

    runBtn.addEventListener('click', function () {
      runBtn.disabled = true;
      report = [];
      out.textContent = '';
      line('=== OrsayTube v4 Tanılama ===');
      line('Zaman: ' + new Date().toLocaleString());

      var vp = document.createElement('video');
      var mp4 = '', webm = '';
      try {
        mp4 = vp.canPlayType('video/mp4; codecs="avc1.42E01E, mp4a.40.2"') || 'hayır';
        webm = vp.canPlayType('video/webm; codecs="vp8, vorbis"') || 'hayır';
      } catch (e) {}
      line('WebView video: H.264/MP4=' + mp4 + ' · VP8/WebM=' + webm);
      line('---');

      /* 1) sunucu */
      line('[1/5] Yerel sunucu (ping)…');
      api('/api/ping', function (err, d) {
        if (err) {
          line('  HATA: ' + err);
          done();
          return;
        }
        line('  OK — sürüm ' + d.version + ', port ' + d.port);

        /* 2) arama */
        line('[2/5] Arama motoru (innertube/NewPipe)…');
        var t0 = Date.now();
        api('/api/search?q=muzik', function (err2, d2) {
          var items = (d2 && d2.items) || [];
          line(err2 ? ('  HATA: ' + err2)
            : ('  OK — ' + items.length + ' sonuç, kaynak: ' + d2.via
              + ' (' + ((Date.now() - t0) / 1000).toFixed(1) + ' sn)'));

          /* 3) akış çözümleme */
          line('[3/5] Video çözümleme (imza/n-parametre + Rhino)…');
          var t1 = Date.now();
          api('/api/streams?v=dQw4w9WgXcQ', function (err3, d3) {
            if (err3) {
              line('  HATA: ' + err3);
              done();
              return;
            }
            var srcs = d3.sources || [];
            line('  OK — ' + ((Date.now() - t1) / 1000).toFixed(1) + ' sn, kaynak: ' + d3.via
              + ', ' + srcs.length + ' oynatıcı kaynağı');
            for (var i = 0; i < srcs.length && i < 6; i++) {
              line('    · ' + srcs[i].label + ' (' + srcs[i].kind + ')');
            }
            if (d3.hls) { line('    · HLS manifest hazır'); }

            /* 4) vekil video testi */
            line('[4/5] Vekil video testi (/video Range 0-1023)…');
            probeVideo(function (code, n, ct, ms) {
              if (code === 206 || code === 200) {
                line('  OK — HTTP ' + code + ', ' + n + ' bayt, ' + ct
                  + ' (' + (ms / 1000).toFixed(1) + ' sn)');
                line('  → VİDEO AKIŞI ÇALIŞIYOR — oynatıcı tarafı sağlam.');
              } else if (code === -1) {
                line('  HATA: zaman aşımı (90 sn) — kaynak hiç dönmedi');
              } else {
                line('  HATA: HTTP ' + code + ' (' + n + ' bayt, ' + ms + ' ms)');
              }

              /* 5) sunucu günlüğü */
              line('[5/5] Sunucu günlüğü (son olaylar)…');
              api('/api/diag', function (err4, d4) {
                if (!err4 && d4) {
                  if (d4.publicIp) {
                    line('  Cihaz dış IP: ' + d4.publicIp);
                  }
                  var lg = d4.log || [];
                  for (var k = Math.max(0, lg.length - 25); k < lg.length; k++) {
                    line('  · ' + lg[k]);
                  }
                } else {
                  line('  günlük alınamadı: ' + err4);
                }
                if (DiagLines.length) {
                  line('Tarayıcı gözlemleri:');
                  for (var j = 0; j < DiagLines.length; j++) {
                    line('  · ' + DiagLines[j]);
                  }
                }
                done();
              }, 20000);
            });
          }, 120000);
        }, 60000);
      }, 15000);

      function done() {
        line('=== Tanılama tamam ===');
        runBtn.disabled = false;
        copyBtn.style.display = '';
        copyBtn.scrollIntoView();
      }
    });
  }

  /* ================= hakkında / sunucu ================= */

  function renderAbout() {
    view.innerHTML = '';
    var box = el('div', 'about');

    var p1 = el('div', 'panel');
    p1.appendChild(el('h3', null, '⭕ Yerel Sunucu'));
    var kvPort = el('div', 'kv');
    kvPort.appendChild(el('span', 'k', 'Durum'));
    var kvV = el('span', 'v', '…');
    kvPort.appendChild(kvV);
    p1.appendChild(kvPort);
    var kvHost = el('div', 'kv');
    kvHost.appendChild(el('span', 'k', 'Bu telefonda'));
    kvHost.appendChild(el('span', 'v', 'http://localhost:8080'));
    p1.appendChild(kvHost);
    var kvLan = el('div', 'kv');
    kvLan.appendChild(el('span', 'k', 'Aynı ağdaki cihazlar'));
    var lanV = el('span', 'v', '…');
    kvLan.appendChild(lanV);
    p1.appendChild(kvLan);
    p1.appendChild(el('p', null,
      'Sunucu, uygulamayı kapatsan da arka planda çalışır. ' +
      'Telefonun tarayıcısını açıp localhost adresini yazarak aynı arayüze gir. ' +
      'Videolar telefondan çözümlenip vekil üzerinden aktarılır.'));
    var openBtn = el('button', 'btn ghost', 'Tarayıcıda aç');
    openBtn.addEventListener('click', function () {
      if (NATIVE && window.OrsayTubeNative.openExternal) {
        window.OrsayTubeNative.openExternal('http://localhost:8080/');
      } else {
        toast('Telefon tarayıcına http://localhost:8080 yaz');
      }
    });
    p1.appendChild(openBtn);
    box.appendChild(p1);

    /* v4.2: TV kurulum paneli — TV arayüzü + TV APK indirme adresi */
    var p1b = el('div', 'panel');
    p1b.appendChild(el('h3', null, '📺 Televizyon'));
    var kvTvWeb = el('div', 'kv');
    kvTvWeb.appendChild(el('span', 'k', 'TV web arayüzü'));
    var tvWebV = el('span', 'v', '…');
    kvTvWeb.appendChild(tvWebV);
    p1b.appendChild(kvTvWeb);
    var kvTvApp = el('div', 'kv');
    kvTvApp.appendChild(el('span', 'k', 'Android TV uygulaması'));
    var tvAppV = el('span', 'v', '…');
    kvTvApp.appendChild(tvAppV);
    p1b.appendChild(kvTvApp);
    var kvTvW = el('div', 'kv');
    kvTvW.appendChild(el('span', 'k', 'Samsung TV widget’ı (2012-13)'));
    var tvWV = el('span', 'v', '…');
    kvTvW.appendChild(tvWV);
    p1b.appendChild(kvTvW);
    p1b.appendChild(el('p', null,
      'TV tarayıcısıyla ilk adresteki /tv sayfası açılırsa kumandayla kullanılır ' +
      '(10-foot arayüz). Android TV içinse ikinci adresten uygulamayı indirip kur — ' +
      'kurulumda “bilinmeyen kaynaklara” izin ver. Uygulama, OrsayTube açık telefonu ' +
      'aynı Wi-Fi’de otomatik bulur.'));
    p1b.appendChild(el('p', null,
      'Samsung 2012-2013 (Orsay) TV: Smart Hub’dan “develop” hesabıyla giriş yap, ' +
      'Gelişme (Develop) bölümünde sunucu IP’sini gir (soldaki IP, port’suz) ve ' +
      '“Kullanıcı uygulamalarını senkronla” de. OrsayTube TV widget’ı kurulur; ' +
      'açılışta telefon adresini sorar.'));
    var tvCopyBtn = el('button', 'btn ghost', 'TV adreslerini kopyala');
    tvCopyBtn.addEventListener('click', function () {
      var txt = 'TV arayüzü: ' + tvWebV.textContent + '\n' +
                'TV uygulaması (APK): ' + tvAppV.textContent + '\n' +
                'Samsung widget listesi: ' + tvWV.textContent;
      if (NATIVE && window.OrsayTubeNative.copyText) {
        window.OrsayTubeNative.copyText(txt);
        toast('Kopyalandı');
      } else if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(txt);
        toast('Kopyalandı');
      } else {
        toast('Kopyalanamadı — adresleri elle not al');
      }
    });
    p1b.appendChild(tvCopyBtn);
    box.appendChild(p1b);

    var p2 = el('div', 'panel');
    p2.appendChild(el('h3', null, 'ℹ️ Nasıl çalışır?'));
    p2.appendChild(el('p', null,
      'Video çözümlemesinde diekaiju/localtube projesinin GERÇEK motoru kullanılıyor: ' +
      'NewPipe Extractor (TeamNewPipe). YouTube akış URL lerinin imza (sig) ve ' +
      'n-parametre çözümlemesi bu motor sayesinde yapılır; videolar yerel vekil ' +
      '(proxy) üzerinden Range destekli iletilir, HLS manifest leri localhost a ' +
      'yeniden yazılır. Yedek zincir: innertube → Piped örnekleri.'));
    p2.appendChild(el('p', null,
      'Bu uygulama diekaiju/localtube (GPLv3) mimarisi üzerine kuruludur; ' +
      'motor olarak NewPipe Extractor ve NewPipe topluluğunun emeğini kullanır. ' +
      'Proje sayfaları: github.com/diekaiju/localtube · ' +
      'github.com/TeamNewPipe/NewPipeExtractor'));
    box.appendChild(p2);

    view.appendChild(box);

    function fill() {
      api('/api/info', function (err, d) {
        if (current.view !== 'about') { return; }
        if (err) {
          kvV.textContent = 'ulaşılamıyor';
          kvV.className = 'v badbadge';
          return;
        }
        kvV.textContent = d.running ? 'ÇALIŞIYOR' : 'DURDU';
        kvV.className = 'v ' + (d.running ? 'okbadge' : 'badbadge');
        kvHost.lastChild.textContent = 'http://localhost:' + d.port;
        var lan = d.lanIp || '';
        if (!lan && NATIVE && window.OrsayTubeNative.serverInfo) {
          try {
            var si = JSON.parse(window.OrsayTubeNative.serverInfo());
            lan = si.lanIp || '';
          } catch (e) {}
        }
        lanV.textContent = lan ? ('http://' + lan + ':' + d.port) : 'Wi-Fi IP bulunamadı';
        if (lan) {
          tvWebV.textContent = 'http://' + lan + ':' + d.port + '/tv';
          tvAppV.textContent = d.tvApp
            ? ('http://' + lan + ':' + d.port + '/localtube-tv.apk')
            : 'paket yok (v4.2 kur)';
          tvWV.textContent = 'http://' + lan + ':' + d.port + '/widgetlist.xml';
        } else {
          tvWebV.textContent = 'Wi-Fi IP bulunamadı';
          tvAppV.textContent = 'Wi-Fi IP bulunamadı';
          tvWV.textContent = 'Wi-Fi IP bulunamadı';
        }
      });
    }
    fill();
    setInterval(fill, 10000);
  }

  /* ================= başlat ================= */

  route();
})();
