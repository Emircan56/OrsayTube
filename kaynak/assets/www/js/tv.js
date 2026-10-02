/*
 * OrsayTube TV v4.6 — telefon sunucusuna baglanan TV arayuzu
 * ES5: arrow yok, let/const yok, Promise yok, fetch yok.
 * Kumanda (D-pad) ile tam gezinme; oynatici kumanda ile yonetilir.
 *
 * v4.6 degisiklikleri:
 *  - MP4 ONCE: Bu TV tarayicisi HLS oynatmiyor (kullanici testi) —
 *    kaynak sirasi artik ilerici MP4 (en yuksek cozunurluk once),
 *    LBRY yedek, EN SONA HLS (elle secilebilir deneysel secenek).
 *    Varsayilan = en iyi MP4 (720p sunucu tarafindan imzali WEB
 *    istemcisinden cozulur — Resolver.java webEnrich).
 *  - ARAC CUBUGU/UST YAZI KAYMASI: #tools artik #app duzeyinde sabit
 *    (content kaydirmasindan kopuk); olcekleme TEK (uniform) oranla
 *    ve 1280x720 goruntude transform hic uygulanmaz (eski WebKit'te
 *    olcek sonrasi metin bayat boyaliyordu); resize sadece gercek
 *    degisimde; sayfa kaymasi scrollTo(0,0) ile iade edilir.
 *  - Izgara gecisi (transition) kaldirildi — eski TV'lerde kaydirma
 *    sirasinda metin bayat boya birakiyordu.
 * Kaynak: diekaiju/localtube (GPLv3) ilhami + NewPipe motoru.
 */
(function () {
  'use strict';

  var COLS = 5;
  var ROWH = 252;          /* kart yuksekligi + bosluk */
  var VISROWS = 2.0;       /* gorunen satir sayisi (araç cubugu sonrasi) */

  /* ---- sunucu adresi (widget modu) ---- */
  var BASE = '';
  function url(p) { return BASE + p; }

  /* ---- durum ---- */
  var S = {
    view: 'grid',          /* grid | keyboard | player */
    items: [],
    focus: 0,
    toolFocus: -1,         /* v4.3: -1 = kapali, 0..2 = araç cubuğu */
    scroll: 0,
    query: '',
    kbMode: 'search',      /* v4.3: 'search' | 'ip' (widget kurulumu) */
    kbRow: 0,
    kbCol: 0,
    cur: null,             /* oynatilan video {videoId,title,author} */
    sources: [],
    srcIdx: -1,
    ovItems: [],           /* oynatici listesi (kalite/hiz/benzer) */
    ovKind: 'quality',     /* quality | speed | related */
    ovFocus: 0,
    ovScroll: 0,
    pbFocus: -1,           /* v4.3: oynatici buton satiri (-1 = kapali) */
    spdIdx: 1,             /* v4.3: hiz listesi indeksi */
    ctrlTimer: null,
    waiting: false,
    retried: false,
    widgetMode: false,     /* v4.4: Orsay widget (file://) — Return tusu keydown gelir */
    lastTrans: 0,          /* v4.4: OK cift-teslim emniyeti (kapat→yeniden ac yarsi) */
    statusSpin: false,     /* v4.4: durum yazisi döner simgeli mi */
    sef: false,            /* v1.3: SEF oynatıcı eklentisi var mı */
    useSef: false,         /* v1.3: şu anki kaynak SEF ile mi çalıyor */
    sefPaused: false,      /* v1.3: SEF duraklatma durumu (olay yok — elle izlenir) */
    sefDurMs: 0,           /* v1.3: SEF toplam süre (ms) */
    sefPosMs: 0            /* v1.3: SEF güncel konum (ms) */
  };

  var SPEEDS = [0.75, 1, 1.25, 1.5, 2];
  var SPEED_LABELS = ['0.75×', '1× (normal)', '1.25×', '1.5×', '2×'];

  /* ---- DOM ---- */
  function $(id) { return document.getElementById(id); }
  var app, grid, tools, subtitle, clockEl, dot, statusTxt;
  var playerEl, vid, ptitle, pauthor, pstatus, pstext, perr, perrtext;
  var pinfo, pcontrols, ptime, pdur, pcur, pbuf, ppause, plive, pquality;
  var pbtns, pbtnEls;
  var poverlay, pohead, polist;
  var keyboardEl, kgrid, kbquery, kbtitle;
  var helpEl;

  /* ================= yardimcilar ================= */

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== null && text !== undefined) {
      e.appendChild(document.createTextNode(text));
    }
    if (parent) { parent.appendChild(e); }
    return e;
  }

  function show(node) {
    node.className = node.className.replace(/\s*hidden\s*/g, ' ').replace(/^\s+|\s+$/g, '');
  }
  function hide(node) {
    if (node.className.indexOf('hidden') < 0) { node.className += ' hidden'; }
  }

  function XHR(method, u, cb) {
    var x;
    try { x = new XMLHttpRequest(); } catch (e) { cb(0, null); return; }
    x.open(method, url(u), true);
    x.onreadystatechange = function () {
      if (x.readyState === 4) {
        var d = null;
        try { d = JSON.parse(x.responseText); } catch (e2) { d = null; }
        cb(x.status, d);
      }
    };
    try { x.send(null); } catch (e3) { cb(0, null); }
  }

  function fmtSec(t) {
    t = Math.max(0, Math.floor(t || 0));
    var h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
    var mm = (m < 10 ? '0' : '') + m, ss = (s < 10 ? '0' : '') + s;
    return (h > 0 ? h + ':' : '') + mm + ':' + ss;
  }

  function fmtClock() {
    var d = new Date();
    var h = d.getHours(), m = d.getMinutes();
    if (h < 10) { h = '0' + h; }
    if (m < 10) { m = '0' + m; }
    return h + ':' + m;
  }

  /* 1280x720 tasarimi gercek ekrana olcekle (eski TV guvenli).
   * v4.6: TEK (uniform) olcek katsayisi + ortalama. Ayr X/Y olcekleme
   * eski WebKit'te metin kutudan kayiyordu; 1280x720 tam goruntude
   * transform HIC uygulanmaz ('none') — boylece metin boya yolu hep
   * transform'suz. Kenar bosluklari siyah zemin gorunmez. */
  var lastFitW = 0, lastFitH = 0;

  function fit() {
    var w = window.innerWidth || 1280;
    var h = window.innerHeight || 720;
    if (!w || !h) { w = 1280; h = 720; }
    lastFitW = w;
    lastFitH = h;
    var s = Math.min(w / 1280, h / 720);
    var t;
    if (s > 0.995 && s < 1.005) {
      t = 'none';
    } else {
      var tx = Math.round((w - 1280 * s) / 2);
      var ty = Math.round((h - 720 * s) / 2);
      t = 'translate(' + tx + 'px,' + ty + 'px) scale(' + s + ')';
    }
    app.style['-webkit-transform'] = t;
    app.style['transform'] = t;
  }

  /* v4.6: tarayici cubugu gizlenip gorununce kucuk resize yagmuru
   * gelir — sadece GERCEK boyut degisiminde yeniden olcekle. */
  function onWinResize() {
    var w = window.innerWidth || 0;
    var h = window.innerHeight || 0;
    if (Math.abs(w - lastFitW) < 48 && Math.abs(h - lastFitH) < 48) { return; }
    fit();
  }

  /* v4.6: TV tarayicisi D-pad ile SAYFAYI kaydirirsa (viewport tasarimdan
   * kucukse) header + dugme metinleri kayiyordu — kaydirmayi aninda geri al. */
  function onWinScroll() {
    try { window.scrollTo(0, 0); } catch (e) {}
  }

  /* ================= durum cubugu ================= */

  /* ---- v4.4: history katmanları (GERİ tuşu yakalama) ----
   * TV tarayicilari kumandanin Return'unu SAYFAYA VERMEZ — tarayicinin
   * kendisi history geri gider ve oynaticidan onceki SITEYE atlar.
   * Cozum: her katman (oynatici/kalite listesi/klavye/yardim) acilirken
   * history'ye bir durum itilir; Return = popstate/hashchange → en ust
   * katman kapanir. pushState yoksa hash yedegi, o da yoksa pas. */
  var H = { stack: [], mode: 'none', pending: 0, selfNav: 0, lastNav: 0 };

  function histInit() {
    if (S.widgetMode) { H.mode = 'none'; return; }
    if (window.history && window.history.pushState) {
      H.mode = 'push';
    } else if ('onhashchange' in window) {
      H.mode = 'hash';
    }
    if (H.mode === 'push') {
      window.onpopstate = function () { onHistBack(); };
    } else if (H.mode === 'hash') {
      window.onhashchange = function () { onHistBack(); };
    }
    /* takilan sayaclari temizle (popstate gelmezse Return'i yutmasin) */
    setInterval(function () {
      if (H.pending > 0 && Date.now() - H.lastNav > 1200) { H.pending = 0; }
      if (H.selfNav > 0 && Date.now() - H.lastNav > 1200) { H.selfNav = 0; }
    }, 800);
  }

  function histPush(name) {
    if (S.widgetMode || H.mode === 'none') { return; }
    H.lastNav = Date.now();
    if (H.mode === 'push') {
      try { history.pushState({ lt: name }, '', location.href); }
      catch (e) { H.mode = 'none'; return; }
    } else {
      try {
        H.selfNav++;
        location.hash = 'lt' + (H.stack.length + 1);
      } catch (e2) { H.selfNav--; H.mode = 'none'; return; }
    }
    H.stack.push(name);
  }

  /* kendi actigimiz katmani biz kapatinca girdiyi senkron tuket */
  function histConsume(name) {
    var n = H.stack.length;
    if (!n || H.stack[n - 1] !== name) { return; }
    H.stack.pop();
    if (H.mode === 'none') { return; }
    H.lastNav = Date.now();
    H.pending++;
    try { history.back(); } catch (e) { H.pending--; }
  }

  /* tarayici Return'u (popstate/hashchange) → en ust katmani kapat */
  function onHistBack() {
    if (H.selfNav > 0) { H.selfNav--; return; }
    if (H.pending > 0) { H.pending--; return; }
    if (!H.stack.length) { return; }
    var name = H.stack.pop();
    if (name === 'base') { return; }        /* izgarada ilk Return yutulur */
    if (name === 'player') { closePlayerUI(); }
    else if (name === 'kb') { closeKbUI(); }
    else if (name === 'overlay') { hide(poverlay); }
    else if (name === 'help') { closeHelpUI(); }
  }

  function markTrans() { S.lastTrans = Date.now(); }

  function setStatus(ok, txt) {
    dot.className = 'dot ' + (ok ? 'on' : 'off');
    statusTxt.replaceChild(document.createTextNode(txt), statusTxt.firstChild);
  }

  function pingOnce() {
    XHR('GET', '/api/ping', function (st) {
      if (st === 200) { setStatus(true, 'Sunucu hazır'); }
      else { setStatus(false, 'Sunucu yok'); }
    });
  }

  /* ================= ANA IZGARA ================= */

  function renderGrid(items, title) {
    S.items = items || [];
    S.focus = 0;
    S.toolFocus = -1;
    S.scroll = 0;
    grid.innerHTML = '';
    grid.style.webkitTransform = 'translateY(0)';
    if (typeof grid.style.transform === 'string') {
      grid.style.transform = 'translateY(0)';
    }
    for (var i = 0; i < S.items.length; i++) {
      var it = S.items[i];
      var c = el('div', 'card', grid);
      var th = el('div', 'thumb', c);
      var img = document.createElement('img');
      img.src = url('/thumb?v=' + encodeURIComponent(it.videoId));
      img.alt = '';
      th.appendChild(img);
      if (it.duration) { el('div', 'dur', th, it.duration); }
      el('div', 'ttl', c, it.title || '');
      el('div', 'by', c, it.channel || '');
    }
    subtitle.replaceChild(document.createTextNode(title), subtitle.firstChild);
    updateFocus();
  }

  /* v4.4: trend yedeği (offline liste) dönerse 45sn sonra kendisi yeniden
   * dener — TV tarafında düğme olmadığı için otomatik olması şart */
  var trendRetryTimer = null;

  function loadTrending() {
    if (trendRetryTimer) { clearTimeout(trendRetryTimer); trendRetryTimer = null; }
    subtitle.replaceChild(document.createTextNode('Yükleniyor…'), subtitle.firstChild);
    grid.innerHTML = '';
    XHR('GET', '/api/home', function (st, d) {
      if (st === 200 && d && d.items && d.items.length) {
        var off = d.via === 'offline-liste';
        var src = off ? 'Trendler alınamadı — yedek liste (tekrar deneniyor…)'
                : (d.via === 'newpipe' ? 'Trendler · YouTube TR' : 'Trendler · ' + (d.via || '-'));
        renderGrid(d.items, src);
        if (off) {
          trendRetryTimer = setTimeout(function () {
            trendRetryTimer = null;
            if (S.view === 'grid') { loadTrending(); }
          }, 45000);
        }
      } else {
        renderGrid([], 'Trendler alınamadı');
        trendRetryTimer = setTimeout(function () {
          trendRetryTimer = null;
          if (S.view === 'grid') { loadTrending(); }
        }, 45000);
      }
    });
  }

  function runSearch(q) {
    S.query = q;
    subtitle.replaceChild(document.createTextNode('Aranıyor…'), subtitle.firstChild);
    grid.innerHTML = '';
    XHR('GET', '/api/search?q=' + encodeURIComponent(q), function (st, d) {
      if (st === 200 && d && d.items) {
        renderGrid(d.items, 'Sonuçlar: "' + q + '"');
      } else {
        renderGrid([], 'Arama başarısız');
      }
    });
  }

  function updateTools() {
    for (var i = 0; i < 3; i++) {
      var t = tools.children[i];
      if (!t) { continue; }
      t.className = (S.toolFocus === i) ? 'tool focus' : 'tool';
    }
  }

  function updateFocus() {
    updateTools();
    var kids = grid.childNodes;
    for (var i = 0; i < kids.length; i++) {
      var on = (S.toolFocus < 0 && i === S.focus) ? ' card focus' : '';
      kids[i].className = 'card' + on;
    }
    var row = Math.floor(S.focus / COLS);
    var maxScroll = Math.max(0, Math.ceil(S.items.length / COLS) - VISROWS) * ROWH;
    var y = Math.min(row * ROWH, maxScroll);
    S.scroll = y;
    var t = 'translateY(-' + y + 'px)';
    grid.style.webkitTransform = t;
    if (typeof grid.style.transform === 'string') { grid.style.transform = t; }
  }

  function navGrid(dir) {
    /* araç çubuğu aktifken önce oradan çık */
    if (S.toolFocus >= 0) {
      if (dir === 'down') { S.toolFocus = -1; updateFocus(); return; }
      if (dir === 'left') { S.toolFocus = S.toolFocus > 0 ? S.toolFocus - 1 : 2; updateFocus(); return; }
      if (dir === 'right') { S.toolFocus = S.toolFocus < 2 ? S.toolFocus + 1 : 0; updateFocus(); return; }
      if (dir === 'up') { return; } /* zaten üstte */
    }
    var n = S.items.length;
    if (n === 0) {
      /* boş ızgarada yukarı = araç çubuğu */
      if (dir === 'up') { S.toolFocus = 0; updateFocus(); }
      return;
    }
    var idx = S.focus;
    var col = idx % COLS;
    if (dir === 'left') { idx = col > 0 ? idx - 1 : idx; }
    else if (dir === 'right') { idx = (col < COLS - 1 && idx < n - 1) ? idx + 1 : idx; }
    else if (dir === 'up') {
      if (row0(idx)) {
        /* ilk satırdan yukarı: araç çubuğuna gir (v4.3) */
        S.toolFocus = 0;
        updateFocus();
        return;
      }
      idx = idx - COLS;
    }
    else if (dir === 'down') { idx = idx + COLS; }
    if (idx < 0) { idx = idx + COLS; if (idx < 0) { idx = 0; } }
    if (idx > n - 1) { idx = n - 1; }
    if (idx !== S.focus) {
      S.focus = idx;
      updateFocus();
    }
  }

  function row0(idx) { return Math.floor(idx / COLS) === 0; }

  function toolActivate() {
    if (S.toolFocus === 0) { openKb('search'); }
    else if (S.toolFocus === 1) { loadTrending(); }
    else if (S.toolFocus === 2) { openHelp(); }
    S.toolFocus = -1;
    updateFocus();
  }

  function openFocused() {
    if (S.toolFocus >= 0) { toolActivate(); return; }
    if (!S.items.length) { return; }
    var it = S.items[S.focus];
    openPlayer(it.videoId, it.title, it.channel);
  }

  /* ================= ARAMA / IP KLAVYESI ================= */

  var KB_ROWS = [
    ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
    ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'],
    ['Z', 'X', 'C', 'V', 'B', 'N', 'M'],
    ['Ğ', 'Ü', 'Ş', 'İ', 'Ö', 'Ç', ' '],
    ['SİL', 'TEMİZLE', 'ARA', 'KAPAT']
  ];

  /* v4.3 (widget kurulumu): telefon IP'si girme düzeni */
  var KB_ROWS_IP = [
    ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
    ['.', ':', 'SİL', 'TEMİZLE', 'KAYDET']
  ];

  function kbRows() { return S.kbMode === 'ip' ? KB_ROWS_IP : KB_ROWS; }

  function renderKb() {
    var rows = kbRows();
    kgrid.innerHTML = '';
    for (var r = 0; r < rows.length; r++) {
      var rowEl = el('div', 'krow', kgrid);
      for (var c = 0; c < rows[r].length; c++) {
        var k = rows[r][c];
        var cls = 'kkey';
        if (S.kbMode !== 'ip' && r === 4) { cls += ' wide action'; }
        if (S.kbMode === 'ip' && (k === 'KAYDET' || k === 'SİL' || k === 'TEMİZLE')) { cls += ' wide action'; }
        var key = el('div', cls, rowEl, k === ' ' ? 'BOŞLUK' : k);
        if (r === S.kbRow && c === S.kbCol) { key.className += ' focus'; }
      }
    }
    kbUpdateQuery();
  }

  function kbUpdateQuery() {
    kbquery.replaceChild(
      document.createTextNode(S.query + ((S.query.length < 34) ? '_' : '')),
      kbquery.firstChild);
  }

  function kbPress() {
    var k = kbRows()[S.kbRow][S.kbCol];
    if (k === 'BOŞLUK' || k === ' ') {
      if (S.kbMode !== 'ip') { S.query += ' '; }
    } else if (k === 'SİL') {
      S.query = S.query.substring(0, S.query.length - 1);
    } else if (k === 'TEMİZLE') {
      S.query = '';
    } else if (k === 'ARA' || k === 'KAYDET') {
      if (S.kbMode === 'ip') {
        saveBase();
      } else if (S.query.replace(/\s/g, '').length > 0) {
        closeKbUI();
        runSearch(S.query);
      }
      return;
    } else if (k === 'KAPAT') {
      if (S.kbMode === 'ip') {
        /* widget'ta kapat yok — tekrar dene */
        saveBase();
      } else {
        closeKbUI();
      }
      return;
    } else {
      S.query += k;
    }
    kbUpdateQuery();
  }

  /* v4.3: widget kurulumu — girilen adresi kaydet ve bağlan */
  function saveBase() {
    var a = S.query.replace(/\s/g, '');
    if (!/^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?$/.test(a)) {
      kbtitle.replaceChild(document.createTextNode('Adres geçersiz — örnek: 192.168.1.5 veya 192.168.1.5:8080'), kbtitle.firstChild);
      return;
    }
    if (a.indexOf(':') < 0) { a += ':8080'; }
    BASE = 'http://' + a;
    try { window.localStorage.setItem('lt_base', BASE); } catch (e) {}
    XHR('GET', '/api/ping', function (st) {
      if (st === 200) {
        closeKbUI();
        setStatus(true, 'Bağlı: ' + a);
        loadTrending();
      } else {
        BASE = '';
        kbtitle.replaceChild(document.createTextNode('Bağlanılamadı — telefonda OrsayTube açık mı? Tekrar deneyin'), kbtitle.firstChild);
      }
    });
  }

  function navKb(dir) {
    var rows = kbRows();
    var len = rows[S.kbRow].length;
    if (dir === 'left') {
      S.kbCol = S.kbCol > 0 ? S.kbCol - 1 : len - 1;
    } else if (dir === 'right') {
      S.kbCol = S.kbCol < len - 1 ? S.kbCol + 1 : 0;
    } else if (dir === 'up') {
      S.kbRow = S.kbRow > 0 ? S.kbRow - 1 : rows.length - 1;
      if (S.kbCol >= rows[S.kbRow].length) { S.kbCol = rows[S.kbRow].length - 1; }
    } else if (dir === 'down') {
      S.kbRow = S.kbRow < rows.length - 1 ? S.kbRow + 1 : 0;
      if (S.kbCol >= rows[S.kbRow].length) { S.kbCol = rows[S.kbRow].length - 1; }
    }
    renderKb();
  }

  function openKb(mode) {
    S.view = 'keyboard';
    S.kbMode = mode || 'search';
    if (S.kbMode === 'ip') {
      S.query = '192.168.';
      S.kbRow = 1; S.kbCol = 4; /* KAYDET uzerinde */
      kbtitle.replaceChild(document.createTextNode('Telefon Adresi (Sunucu IP)'), kbtitle.firstChild);
    } else {
      S.kbRow = 4; S.kbCol = 2; /* ARA uzerinde */
      kbtitle.replaceChild(document.createTextNode('Arama'), kbtitle.firstChild);
    }
    renderKb();
    show(keyboardEl);
    markTrans();
    histPush('kb');
  }

  /* v4.4: klavyeyi kapat (history girdisini de tuketir) */
  function closeKbUI() {
    if (S.view !== 'keyboard') { return; }
    S.view = 'grid';
    hide(keyboardEl);
    markTrans();
    histConsume('kb');
  }

  /* ================= OYNATICI ================= */

  function canNativeHls() {
    try {
      return !!(vid.canPlayType('application/vnd.apple.mpegurl') ||
                vid.canPlayType('application/x-mpegURL'));
    } catch (e) { return false; }
  }

  function resHeight(label) {
    var m = /(\d{2,4})p/.exec(String(label || ''));
    return m ? parseInt(m[1], 10) : 0;
  }

  function openPlayer(videoId, title, author) {
    S.view = 'player';
    S.cur = { videoId: videoId, title: title || '', author: author || '' };
    S.retried = false;
    S.pbFocus = -1;
    setSpeed(1, true);
    show(playerEl);
    ptitle.replaceChild(document.createTextNode(S.cur.title), ptitle.firstChild);
    pauthor.replaceChild(document.createTextNode(S.cur.author || ''), pauthor.firstChild);
    setPStatus('Kaynaklar çözümleniyor…', true);
    hide(perr);
    hide(poverlay);
    markTrans();
    histPush('player');
    XHR('GET', '/api/streams?v=' + encodeURIComponent(videoId), function (st, d) {
      if (S.view !== 'player' || !S.cur || S.cur.videoId !== videoId) { return; }
      if (st === 200 && d && !d.error) {
        if (d.title) { ptitle.replaceChild(document.createTextNode(d.title), ptitle.firstChild); }
        if (d.author) { pauthor.replaceChild(document.createTextNode(d.author), pauthor.firstChild); }
        buildSources(d);
        if (!S.sources.length) {
          setPlayerError('Oynatılabilir kaynak bulunamadı. OK ile yeniden deneyin ya da telefonda Tanılama çalıştırın.');
          return;
        }
        S.srcIdx = bestDefaultIdx();
        applySource(true);
      } else {
        var msg = (d && d.message) ? d.message : ('HTTP ' + st);
        setPlayerError('Video bilgisi alınamadı: ' + msg);
      }
    });
  }

  function buildSources(d) {
    S.sources = [];
    var list = (d && d.sources) || [];
    var prog = [], direct = [], hls = [];
    var i, s;
    for (i = 0; i < list.length; i++) {
      s = list[i];
      if (s.kind === 'progressive' && s.url) { prog.push(s); }
      else if (s.kind === 'direct' && s.url) { direct.push(s); }
      else if (s.kind === 'hls' && s.url) { hls.push(s); }
    }
    /* v4.6: MP4 ONCE. Bu TV tarayicisi HLS oynatmadi (kullanici testi) —
     * artık canPlayType ne derse desin HLS liste basina ALINMAZ.
     * Sıra: ilerici MP4 (çözünürlük azalan) → LBRY yedek → HLS (son,
     * elle seçilebilir deneysel). Canlı yayında MP4 yoksa HLS yine
     * tek seçenek olarak kullanılır. */
    prog.sort(function (a, b) { return resHeight(b.label) - resHeight(a.label); });
    for (i = 0; i < prog.length; i++) { S.sources.push(prog[i]); }
    for (i = 0; i < direct.length; i++) { S.sources.push(direct[i]); }
    for (i = 0; i < hls.length; i++) { S.sources.push(hls[i]); }
    S.related = (d && d.related) || [];
    S.durationSec = (d && d.durationSec) || 0;
    S.live = !!(d && d.hls && d.durationSec === 0 && d.duration === '');
  }

  /* v4.6: varsayılan = en iyi MP4 (liste başı). HLS'e otomatik geçiş YOK —
   * yalnızca kalite menüsünden elle seçilir. */
  function bestDefaultIdx() {
    return 0;
  }

  function srcLabel(s, idx) {
    return s.label || ('Kaynak ' + (idx + 1));
  }

  /* v4.5: hls.js (MSE) — yerel HLS'i olmayan tarayicilar icin
   * tembel yukleme. Eski TV'lerde MediaSource yoksa 375KB'lik
   * kutuphane HIC inmez. */
  var hlsEng = null;

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

  function applySource(play) {
    var s = S.sources[S.srcIdx];
    if (!s) { return; }
    pquality.replaceChild(
      document.createTextNode('Kaynak ' + (S.srcIdx + 1) + '/' + S.sources.length + ' · ' + srcLabel(s, S.srcIdx)),
      pquality.firstChild);
    hide(perr);
    setPStatus('Yükleniyor…', true);
    stopHls();
    S.useSef = false;

    /* v1.3: HLS kaynağı + SEF eklentisi → TV'nin kendi oynatıcısı */
    if (S.sef && sef && s.kind === 'hls') {
      try { vid.pause(); } catch (e0) {}
      vid.removeAttribute('autoplay');
      vid.removeAttribute('src');
      try { vid.load(); } catch (e0b) {}
      S.sefDurMs = 0;
      S.sefPosMs = 0;
      S.sefPaused = false;
      S.useSef = true;
      var u = url(s.url);
      /* E seri (2012) HLS için bileşen ipucu ister; F/H yok sayar */
      u += '|COMPONENT=HLS';
      try {
        try { sef.Stop(); } catch (eS) {}
        sef.Play(u);
        setPauseIcon(false);
      } catch (e1) {
        /* SEF reddetti → video etiketiyle düş */
        S.useSef = false;
        vid.src = url(s.url);
        try { vid.load(); } catch (e2) {}
        if (play !== false) { try { vid.play(); } catch (e3) {} }
      }
      showControls();
      return;
    }

    if (s.kind === 'hls' && !canNativeHls()) {
      /* v4.5: MSE varsa hls.js ile, yoksa video etiketiyle dene */
      ensureHlsJs(function () {
        if (S.view !== 'player' || !S.cur || S.sources[S.srcIdx] !== s) { return; }
        if (window.Hls && window.Hls.isSupported && window.Hls.isSupported()) {
          try {
            hlsEng = new window.Hls({ enableWorker: true });
            hlsEng.attachMedia(vid);
            hlsEng.loadSource(url(s.url));
            hlsEng.on(window.Hls.Events.ERROR, function (evt, data) {
              if (data && data.fatal) {
                stopHls();
                if (S.view !== 'player') { return; }
                if (nextSource()) { return; }
                setPlayerError('HLS akışı açılamadı — kaynaklar tükendi.');
              }
            });
            if (play !== false) { try { vid.play(); } catch (e3) {} }
          } catch (e) {
            if (S.view === 'player' && !nextSource()) {
              setPlayerError('HLS başlatılamadı.');
            }
          }
        } else {
          /* MSE de yok — video etiketiyle şansımızı dene (bazı TV'ler
           * canPlayType'i yanlış bildirir) */
          vid.src = url(s.url);
          try { vid.load(); } catch (e4) {}
          if (play !== false) { try { vid.play(); } catch (e5) {} }
        }
      });
    } else {
      vid.src = url(s.url);
      try { vid.load(); } catch (e) {}
      if (play !== false) {
        try { vid.play(); } catch (e2) {}
      }
    }
    showControls();
  }

  function nextSource() {
    if (S.srcIdx < S.sources.length - 1) {
      S.srcIdx++;
      applySource(true);
      return true;
    }
    return false;
  }

  function retryResolve() {
    /* tum kaynaklar tukenmisse API'den taze URL'ler al (403 kisirlasmis olabilir) */
    setPStatus('Yeniden çözümleniyor…', true);
    XHR('GET', '/api/streams?v=' + encodeURIComponent(S.cur.videoId) + '&t=' + new Date().getTime(), function (st, d) {
      if (S.view !== 'player') { return; }
      if (st === 200 && d && !d.error) {
        buildSources(d);
        if (S.sources.length) {
          S.srcIdx = 0;
          applySource(true);
          return;
        }
      }
      setPlayerError("Kaynaklar tükendi. Telefon uygulamasındaki Tanılama ekranını çalıştırıp durumu kontrol edin.");
    });
  }

  function setPStatus(txt, spin) {
    pstext.replaceChild(document.createTextNode(txt), pstext.firstChild);
    S.statusSpin = !!spin;
    /* v4.4: 'hidden' SINIFI ASLA SILINMEMELI — clearPStatus'taki
     * className='' hatası "Tamponlanıyor…" yazısını ekranda bırakıyordu */
    pstatus.className = spin ? 'spin' : '';
    show(pstatus);
  }
  function clearPStatus() {
    /* v4.4 DOĞRUSU: hidden KALSIN, yalnızca spin kalksın */
    pstatus.className = 'hidden';
    S.statusSpin = false;
  }

  function setPlayerError(msg) {
    clearPStatus();
    perrtext.replaceChild(document.createTextNode(msg), perrtext.firstChild);
    show(perr);
  }

  /* ---- oynatici kontrolleri (kumanda + ekran üstü butonlar) ---- */

  function showControls() {
    pinfo.className = '';
    pcontrols.className = '';
    if (S.ctrlTimer) { clearTimeout(S.ctrlTimer); }
    S.ctrlTimer = setTimeout(function () {
      if (!vid.paused) {
        pinfo.className = 'hide';
        pcontrols.className = 'hide';
      }
    }, 4200);
  }

  function setPauseIcon(paused) {
    /* simge BASILINCA yapılacak İŞLEMİ gösterir: oynarken ⏸ (duraklat),
     * dururken ▶ (oynat) — tersi değil */
    ppause.className = paused ? 'play' : 'pause';
    updatePbtns();
  }

  /* ================= v1.3: ORSAY SEF OYNATICI =================
   * Widget ortamında SAMSUNG-INFOLINK-PLAYER eklentisi varsa HLS
   * kaynakları TV'nin kendi oynatıcı motoruyla çalınır — m3u8'i
   * <video> etiketiyle desteklemeyen eski TV'lerde bile çalışır.
   * MP4 ilerici kaynaklar kanıtlanmış <video> yolunda kalır.
   * Tarayıcıda eklenti yok → kod tamamen atılır (SEF testi var/yok). */

  var sef = null;          /* eklenti nesnesi */
  var KEY_EXTRA = {};      /* tvKey'den gelen ek tuş kodları */

  function initSef() {
    try {
      sef = document.getElementById('pluginPlayer');
      if (sef && typeof sef.Play === 'function') {
        S.sef = true;
        try { sef.OnCurrentPlayTime = 'LTSefTime'; } catch (e) {}
        try { sef.OnBufferingStart = 'LTSefBufStart'; } catch (e) {}
        try { sef.OnBufferingProgress = 'LTSefBufProgress'; } catch (e) {}
        try { sef.OnBufferingComplete = 'LTSefBufComplete'; } catch (e) {}
        try { sef.OnStreamInfoReady = 'LTSefInfoReady'; } catch (e) {}
        try { sef.OnRenderingComplete = 'LTSefComplete'; } catch (e) {}
        try { sef.OnStreamNotFound = 'LTSefError'; } catch (e) {}
        try { sef.OnConnectionFailed = 'LTSefError'; } catch (e) {}
        try { sef.OnNetworkDisconnected = 'LTSefError'; } catch (e) {}
        try { sef.OnRenderError = 'LTSefError'; } catch (e) {}
        try { sef.OnAuthenticationFailed = 'LTSefError'; } catch (e) {}
        return;
      }
    } catch (e) {}
    sef = null;
    S.sef = false;
  }

  function sefDurSec() { return S.sefDurMs > 0 ? S.sefDurMs / 1000 : 0; }

  /* SEF geri çağrıları — DİZGE adıyla bağlanırlar (Orsay kuralı),
   * bu yüzden global (window) düzeyinde olmalılar */
  window.LTSefTime = function (ms) {
    S.sefPosMs = parseInt(ms, 10) || 0;
    var d = sefDurSec();
    if (d > 0) {
      ptime.replaceChild(document.createTextNode(fmtSec(S.sefPosMs / 1000)), ptime.firstChild);
      pdur.replaceChild(document.createTextNode(fmtSec(d)), pdur.firstChild);
      pcur.style.width = Math.min(100, (S.sefPosMs / 1000 / d) * 100) + '%';
      pbuf.style.width = '100%';
    } else if (S.live) {
      show(plive);
    }
    if (S.statusSpin) { clearPStatus(); }
    setPauseIcon(false);
    S.sefPaused = false;
  };
  window.LTSefBufStart = function () { setPStatus('Tamponlanıyor…', true); };
  window.LTSefBufProgress = function (p) {
    var v = parseInt(p, 10) || 0;
    setPStatus('Tamponlanıyor… %' + v, true);
  };
  window.LTSefBufComplete = function () { clearPStatus(); };
  window.LTSefInfoReady = function () {
    var d = 0;
    try { d = parseInt(sef.GetDuration(), 10) || 0; } catch (e) {}
    /* bazı modeller sn, bazıları ms döndürür — sezgisel normalle */
    if (d > 0 && d < 100000) { d = d * 1000; }
    S.sefDurMs = d;
    if (d > 0) {
      pdur.replaceChild(document.createTextNode(fmtSec(d / 1000)), pdur.firstChild);
    }
    clearPStatus();
  };
  window.LTSefComplete = function () {
    setPauseIcon(true);
    S.sefPaused = true;
    showControls();
    pinfo.className = '';
    pcontrols.className = '';
    openOverlay('related');
  };
  window.LTSefError = function () {
    if (S.view !== 'player') { return; }
    if (nextSource()) { return; }
    if (!S.retried) {
      S.retried = true;
      retryResolve();
    } else {
      setPlayerError('Video oynatılamadı — kaynaklar tükendi. OK: yeniden dene.');
    }
  };

  function togglePause() {
    /* v1.3: SEF motoru — Pause/Resume ile */
    if (S.useSef && sef) {
      if (S.sefPaused) {
        try { sef.Resume(); S.sefPaused = false; setPauseIcon(false); } catch (e) {}
      } else {
        try { sef.Pause(); S.sefPaused = true; setPauseIcon(true); } catch (e2) {}
      }
      showControls();
      return;
    }
    if (vid.paused || vid.ended) {
      try { vid.play(); } catch (e) {}
    } else {
      try { vid.pause(); } catch (e2) {}
    }
    showControls();
  }

  function seek(delta) {
    /* v1.3: SEF — ileri/geri atlama (saniye) */
    if (S.useSef && sef) {
      try {
        if (delta >= 0) { sef.JumpForward(delta); }
        else { sef.JumpBackward(-delta); }
      } catch (e) {}
      showControls();
      return;
    }
    if (!vid.duration || !isFinite(vid.duration)) { return; }
    var t = vid.currentTime + delta;
    if (t < 0) { t = 0; }
    if (t > vid.duration - 1) { t = vid.duration - 1; }
    try { vid.currentTime = t; } catch (e) {}
    showControls();
  }

  /* ---- v4.3: hız ---- */

  function setSpeed(idx, silent) {
    S.spdIdx = idx;
    var r = SPEEDS[idx];
    /* v1.3: SEF motoru hız değiştirmeyi desteklemiyor — bilgi ver */
    if (S.useSef) {
      S.spdIdx = 1;
      if (!silent) {
        setPStatus('Hız değişikliği SEF oynatıcıda yok — 1×', false);
        setTimeout(clearPStatus, 1800);
      }
      return;
    }
    try { vid.playbackRate = r; } catch (e) {}
    updatePbtns();
    if (!silent) {
      setPStatus('Hız: ' + SPEED_LABELS[idx], false);
      setTimeout(clearPStatus, 1200);
    }
  }

  function cycleSpeed() {
    setSpeed((S.spdIdx + 1) % SPEEDS.length);
  }

  /* ---- v4.3: tam ekran (destek yoksa zaten tüm sayfa) ---- */

  function fsElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }

  function toggleFs() {
    if (fsElement()) {
      try {
        if (document.exitFullscreen) { document.exitFullscreen(); }
        else if (document.webkitExitFullscreen) { document.webkitExitFullscreen(); }
      } catch (e) {}
      return;
    }
    var target = playerEl || document.documentElement;
    try {
      if (target.requestFullscreen) { target.requestFullscreen(); return; }
      if (target.webkitRequestFullscreen) { target.webkitRequestFullscreen(); return; }
      if (target.webkitRequestFullScreen) { target.webkitRequestFullScreen(); return; }
    } catch (e2) {}
    setPStatus('Tarayıcı tam ekran desteklemiyor — video zaten tüm sayfayı kaplıyor', false);
    setTimeout(clearPStatus, 2200);
  }

  /* ---- v4.3: ekran üstü buton satırı ---- */

  var PB_ACTS = ['pause', 'back10', 'fwd10', 'speed', 'quality', 'related', 'fs', 'close'];

  function updatePbtns() {
    if (!pbtnEls) { return; }
    for (var i = 0; i < pbtnEls.length; i++) {
      var b = pbtnEls[i];
      var act = PB_ACTS[i];
      var cls = 'pbtn' + (S.pbFocus === i ? ' focus' : '');
      if (b.className !== cls) { b.className = cls; }
    }
    /* etiketler canlı kalır (v1.3: SEF durumu da yansır) */
    if (pbtnEls[0]) {
      var paused = S.useSef ? S.sefPaused : (vid && vid.paused);
      replaceText(pbtnEls[0], paused ? 'Oynat' : 'Duraklat');
    }
    if (pbtnEls[3]) {
      replaceText(pbtnEls[3], 'Hız ' + SPEED_LABELS[S.spdIdx].replace(' (normal)', ''));
    }
  }

  function replaceText(node, txt) {
    if (node && node.firstChild) {
      node.replaceChild(document.createTextNode(txt), node.firstChild);
    }
  }

  function pbActivate() {
    var act = PB_ACTS[S.pbFocus];
    if (act === 'pause') { togglePause(); }
    else if (act === 'back10') { seek(-10); }
    else if (act === 'fwd10') { seek(10); }
    else if (act === 'speed') { openOverlay('speed'); }
    else if (act === 'quality') { openOverlay('quality'); }
    else if (act === 'related') { openOverlay('related'); }
    else if (act === 'fs') { toggleFs(); }
    else if (act === 'close') { closePlayer(); }
  }

  /* ---- kalite / hız / benzer listesi ---- */

  function openOverlay(kind) {
    S.ovKind = kind;
    S.ovFocus = 0;
    S.ovScroll = 0;
    if (kind === 'quality') {
      S.ovItems = S.sources;
      pohead.replaceChild(document.createTextNode('Kalite / Kaynak'), pohead.firstChild);
    } else if (kind === 'speed') {
      S.ovItems = SPEED_LABELS;
      S.ovFocus = S.spdIdx;
      pohead.replaceChild(document.createTextNode('Oynatma Hızı'), pohead.firstChild);
    } else {
      S.ovItems = S.related || [];
      pohead.replaceChild(document.createTextNode('Benzer Videolar'), pohead.firstChild);
      if (!S.ovItems.length) {
        setPStatus('Benzer video yok', false);
        setTimeout(clearPStatus, 1500);
        return;
      }
    }
    renderOverlay();
    show(poverlay);
    markTrans();
    histPush('overlay');
  }

  function renderOverlay() {
    polist.innerHTML = '';
    polist.style.webkitTransform = 'translateY(0)';
    if (typeof polist.style.transform === 'string') { polist.style.transform = 'translateY(0)'; }
    for (var i = 0; i < S.ovItems.length; i++) {
      var it = S.ovItems[i];
      var name, sub;
      if (S.ovKind === 'quality') {
        name = srcLabel(it, i);
        sub = (it.kind === 'hls' ? 'HLS — bu TV\u2019de çalışmayabilir' : (it.kind === 'direct' ? 'LBRY yedek sunucu' : 'MP4 doğrudan'));
      } else if (S.ovKind === 'speed') {
        name = it;
        sub = '';
      } else {
        name = (it.title || '');
        sub = ((it.channel || '') + (it.duration ? ' · ' + it.duration : ''));
      }
      var item = el('div', 'pitem', polist);
      item.appendChild(document.createTextNode(name));
      if (sub) { el('div', 'pt2', item, sub); }
      if (i === S.ovFocus) { item.className = 'pitem focus'; }
      if (S.ovKind === 'quality' && i === S.srcIdx) {
        item.appendChild(document.createTextNode('  ✓'));
      }
      if (S.ovKind === 'speed' && i === S.spdIdx) {
        item.appendChild(document.createTextNode('  ✓'));
      }
    }
  }

  function updateOverlayFocus() {
    var kids = polist.childNodes;
    for (var i = 0; i < kids.length; i++) {
      kids[i].className = (i === S.ovFocus) ? 'pitem focus' : 'pitem';
    }
    var row = S.ovFocus;
    var rowH = 66;
    var y = 0;
    if (row * rowH > 470) { y = row * rowH - 470; }
    var t = 'translateY(-' + y + 'px)';
    polist.style.webkitTransform = t;
    if (typeof polist.style.transform === 'string') { polist.style.transform = t; }
  }

  function navOverlay(dir) {
    var n = S.ovItems.length;
    if (!n) { return; }
    if (dir === 'up') { S.ovFocus = S.ovFocus > 0 ? S.ovFocus - 1 : n - 1; }
    else if (dir === 'down') { S.ovFocus = S.ovFocus < n - 1 ? S.ovFocus + 1 : 0; }
    else if (dir === 'left') { S.ovFocus = Math.max(0, S.ovFocus - 3); }
    else if (dir === 'right') { S.ovFocus = Math.min(n - 1, S.ovFocus + 3); }
    updateOverlayFocus();
  }

  function overlaySelect() {
    if (S.ovKind === 'quality') {
      if (S.ovFocus !== S.srcIdx) {
        S.srcIdx = S.ovFocus;
        applySource(true);
      }
      closeOverlay();
    } else if (S.ovKind === 'speed') {
      setSpeed(S.ovFocus);
      closeOverlay();
    } else {
      var it = S.ovItems[S.ovFocus];
      closeOverlay();
      /* v4.4: eski oynatici history girdisini de degistir — benzer video
       * secilince yiginda ayni derinlik kalsin (GERİ listeye dönsun) */
      histConsume('player');
      openPlayer(it.videoId, it.title, it.channel);
    }
  }

  function closeOverlay() {
    if (poverlay.className.indexOf('hidden') >= 0) { return; }
    hide(poverlay);
    markTrans();
    histConsume('overlay');
  }

  /* v4.4: oynatıcıyı kapat — video DURUR (autoplay kaldırıldı, eski
   * WebKit load()'dan sonra yeniden oynatmaz), history girdisi tüketilir */
  function closePlayer() {
    closePlayerUI();
    histConsume('player');
  }

  function closePlayerUI() {
    if (S.view !== 'player') { return; }
    S.view = 'grid';
    S.pbFocus = -1;
    try { vid.pause(); } catch (e) {}
    /* v4.5: hls.js motorunu da durdur */
    stopHls();
    /* v4.4: autoplay'i her yoldan kaldır — load() sonrası yeniden
     * başlaması (kullanıcının "kapatınca yeniden başlatıyor" hatası) budur */
    try { vid.autoplay = false; } catch (e0) {}
    vid.removeAttribute('autoplay');
    vid.removeAttribute('src');
    try { vid.load(); } catch (e2) {}
    hide(playerEl);
    hide(poverlay);
    clearPStatus();
    hide(perr);
    pinfo.className = '';
    pcontrols.className = '';
    markTrans();
    /* v4.3: derin bağlantıyla (?v=) gelindiyse ızgara boştur — doldur */
    if (!S.items.length) {
      loadTrending();
    }
  }

  /* ================= YARDIM ================= */

  function openHelp() {
    if (S.helpOpen) { return; }
    S.helpOpen = true;
    show(helpEl);
    markTrans();
    histPush('help');
  }
  function closeHelp() {
    closeHelpUI();
    histConsume('help');
  }
  function closeHelpUI() {
    if (!S.helpOpen) { return; }
    S.helpOpen = false;
    hide(helpEl);
    markTrans();
  }

  /* ================= TUS TANIMA ================= */

  function keyOf(code) {
    switch (code) {
      case 37: case 29461: return 'left';
      case 38: case 29462: return 'up';
      case 39: case 29459: return 'right';
      case 40: case 29460: return 'down';
      case 13: case 29443: return 'ok';
      case 8: case 27: case 88: case 461: case 10009: case 10135: return 'back';
      case 403: return 'red';
      case 404: return 'green';
      case 405: case 457: return 'yellow';
      case 406: return 'blue';
      case 415: case 71: return 'play';
      case 19: case 72: return 'pause';
      case 413: case 73: return 'stop';
      case 417: case 70: return 'ff';
      case 412: case 69: return 'rw';
      /* v4.3: sayı tuşları — renkli tuşlar tarayıcıda yutulursa bile
         tüm işlevler erişilebilir olsun */
      case 49: case 97: return 'n1';
      case 50: case 98: return 'n2';
      case 51: case 99: return 'n3';
      case 52: case 100: return 'n4';
    }
    /* v1.3: TV'nin kendi tvKey tablosundan gelen ek kodlar */
    return KEY_EXTRA[code] || null;
  }

  function onKey(e) {
    var k = keyOf(e.keyCode || 0);
    if (!k) {
      /* gercek klavye (bazı kumandalarda harf gonderir) */
      if (S.view === 'keyboard' && e.charCode >= 32 && e.charCode <= 126) {
        S.query += String.fromCharCode(e.charCode);
        kbUpdateQuery();
      }
      return;
    }

    /* v4.4: OK çift teslimi (bazı TV tarayıcıları 29443+13 iki kez
     * gönderir) — katman geçişinden hemen sonra gelen OK'u yut:
     * kapat→(ikinci OK)→yeniden oynat yarışını keser */
    if (k === 'ok' && Date.now() - S.lastTrans < 500) {
      if (e.preventDefault) { e.preventDefault(); }
      return false;
    }

    /* yardim acikken her sey kapanir */
    if (S.helpOpen) {
      if (k === 'yellow' || k === 'back' || k === 'ok' || k === 'n3') { closeHelp(); }
      if (e.preventDefault) { e.preventDefault(); }
      return false;
    }

    if (S.view === 'grid') { keyGrid(k); }
    else if (S.view === 'keyboard') { keyKb(k, e); }
    else if (S.view === 'player') { keyPlayer(k); }
    if (e.preventDefault) { e.preventDefault(); }
    return false;
  }

  function keyGrid(k) {
    if (k === 'left' || k === 'right' || k === 'up' || k === 'down') {
      navGrid(k);
    } else if (k === 'ok') {
      openFocused();
    } else if (k === 'red' || k === 'n1') {
      openKb('search');
    } else if (k === 'green' || k === 'n2') {
      loadTrending();
    } else if (k === 'yellow' || k === 'n3') {
      openHelp();
    } else if (k === 'back') {
      /* gridde geri: cikamaz, hicbir sey yapma (TV tarayicisi kendisi cikar) */
    }
  }

  function keyKb(k, e) {
    if (k === 'left' || k === 'right' || k === 'up' || k === 'down') {
      navKb(k);
    } else if (k === 'ok') {
      kbPress();
    } else if (k === 'back') {
      /* klavyede geri = sil; sorgu boşsa klavyeyi kapat (doğal çıkış) */
      if (S.query.length > 0) {
        S.query = S.query.substring(0, S.query.length - 1);
        kbUpdateQuery();
      } else {
        closeKbUI();
      }
    } else if (k === 'blue' || k === 'n1') {
      if (S.kbMode === 'search') {
        closeKbUI();
      }
    } else if (k === 'play' || k === 'n2') {
      if (S.kbMode === 'ip') { saveBase(); }
      else if (S.query.replace(/\s/g, '').length > 0) {
        closeKbUI();
        runSearch(S.query);
      }
    }
    if (e && e.preventDefault) { e.preventDefault(); }
  }

  function keyPlayer(k) {
    var overlayOpen = !poverlay.className.match(/hidden/);
    if (overlayOpen) {
      if (k === 'up' || k === 'down' || k === 'left' || k === 'right') { navOverlay(k); }
      else if (k === 'ok') { overlaySelect(); }
      else if (k === 'back') { closeOverlay(); }
      return;
    }

    /* v4.3: buton satiri aktifken gezinme orada */
    if (S.pbFocus >= 0) {
      if (k === 'left') { S.pbFocus = S.pbFocus > 0 ? S.pbFocus - 1 : PB_ACTS.length - 1; updatePbtns(); return; }
      if (k === 'right') { S.pbFocus = S.pbFocus < PB_ACTS.length - 1 ? S.pbFocus + 1 : 0; updatePbtns(); return; }
      if (k === 'up') { S.pbFocus = -1; updatePbtns(); showControls(); return; }
      if (k === 'down') { return; }
      if (k === 'ok' || k === 'play' || k === 'pause') { pbActivate(); showControls(); return; }
      if (k === 'back' || k === 'stop') { S.pbFocus = -1; updatePbtns(); return; }
    }

    if (k === 'ok' || k === 'play' || k === 'pause') { togglePause(); }
    else if (k === 'left' || k === 'rw') { seek(-10); }
    else if (k === 'right' || k === 'ff') { seek(10); }
    else if (k === 'up') { seek(60); }
    else if (k === 'down') {
      /* v4.3: aşağı = ekran üstü kontrol butonları (hız/kalite/benzer/tam ekran) */
      S.pbFocus = 0;
      updatePbtns();
      showControls();
    }
    else if (k === 'stop') { closePlayer(); }
    else if (k === 'back') { closePlayer(); }
    else if (k === 'blue' || k === 'n1') { openOverlay('quality'); }
    else if (k === 'red' || k === 'n3') { openOverlay('related'); }
    else if (k === 'n2') { openOverlay('speed'); }
    else if (k === 'n4') { toggleFs(); }
    else if (k === 'yellow') {
      if (!perr.className.match(/hidden/)) {
        setPlayerError('Telefon uygulamasında "Tanılama" ekranını çalıştırıp raporu kontrol edin. Sunucu günlüğü /api/diag ucundadir.');
      } else { openHelp(); }
    }
  }

  /* ================= video olaylari ================= */

  function bindVideo() {
    vid.addEventListener('playing', function () {
      clearPStatus();
      setPauseIcon(false);
    });
    vid.addEventListener('pause', function () {
      setPauseIcon(true);
      showControls();
    });
    /* v4.4: 'waiting' yalnızca OYNARKEN mesaj göstersin; duraklatılmış
     * videoda bazı eski tarayıcılar waiting'i boşa ateşliyordu */
    vid.addEventListener('waiting', function () {
      if (!vid.paused && !vid.ended) { setPStatus('Tamponlanıyor…', true); }
    });
    vid.addEventListener('stalled', function () {
      if (!vid.paused && !vid.ended) { setPStatus('Tamponlanıyor…', true); }
    });
    vid.addEventListener('canplay', function () {
      clearPStatus();
    });
    /* v4.4: eski TV tarayıcıları playing/canplay'i TÜM olaylarda
     * ateşlemeyebilir — veri geldiğinde de durumu temizle */
    vid.addEventListener('loadeddata', function () {
      if (vid.readyState >= 2) { clearPStatus(); }
    });
    vid.addEventListener('progress', function () {
      if (vid.readyState >= 3 && !vid.paused) { clearPStatus(); }
    });
    vid.addEventListener('timeupdate', function () {
      /* v4.4: video İLERLİYORSA tampon yazısı ekranda ASLA kalamaz —
      * eski tarayıcılarda playing/canplay aksayabilir, timeupdate sağlam */
      if (!vid.paused && !vid.seeking) { clearPStatus(); }
      var d = vid.duration;
      if (d && isFinite(d)) {
        ptime.replaceChild(document.createTextNode(fmtSec(vid.currentTime)), ptime.firstChild);
        pdur.replaceChild(document.createTextNode(fmtSec(d)), pdur.firstChild);
        pcur.style.width = Math.min(100, (vid.currentTime / d) * 100) + '%';
        try {
          if (vid.buffered && vid.buffered.length > 0) {
            pbuf.style.width = Math.min(100, (vid.buffered.end(vid.buffered.length - 1) / d) * 100) + '%';
          }
        } catch (e) {}
      } else if (S.live) {
        show(plive);
      }
    });
    vid.addEventListener('ended', function () {
      setPauseIcon(true);
      showControls();
      pinfo.className = '';
      pcontrols.className = '';
      openOverlay('related');
    });
    vid.addEventListener('error', function () {
      if (S.view !== 'player') { return; }
      if (nextSource()) { return; }
      if (!S.retried) {
        S.retried = true;
        retryResolve();
      } else {
        setPlayerError('Video oynatılamadı — kaynaklar tükendi. OK: yeniden dene.');
      }
    });
  }

  /* ================= baslangic ================= */

  function boot() {
    app = $('app');
    grid = $('grid');
    tools = $('tools');
    subtitle = $('subtitle');
    clockEl = $('clock');
    dot = $('dot');
    statusTxt = $('statusTxt');
    playerEl = $('player');
    vid = $('tvvideo');
    ptitle = $('ptitle');
    pauthor = $('pauthor');
    pstatus = $('pstatus');
    pstext = $('pstext');
    perr = $('perr');
    perrtext = $('perrtext');
    pinfo = $('pinfo');
    pcontrols = $('pcontrols');
    ptime = $('ptime');
    pdur = $('pdur');
    pcur = $('pcur');
    pbuf = $('pbuf');
    ppause = $('ppause');
    plive = $('plive');
    pquality = $('pquality');
    pbtns = $('pbtns');
    poverlay = $('poverlay');
    pohead = $('pohead');
    polist = $('polist');
    keyboardEl = $('keyboard');
    kgrid = $('kgrid');
    kbquery = $('kbquery');
    kbtitle = $('kbtitle');
    helpEl = $('help');

    if (pbtns) {
      pbtnEls = [];
      for (var bi = 0; bi < pbtns.children.length; bi++) {
        pbtnEls.push(pbtns.children[bi]);
      }
    }

    fit();
    /* v4.6: resize yalnız GERÇEK boyut değişiminde; sayfa kaydırma
     * anında geri alınır (header/dugme metni kayması) */
    window.onresize = onWinResize;
    window.onscroll = onWinScroll;
    try { window.scrollTo(0, 0); } catch (e0) {}

    /* replaceChild için boş metin düğümü tohumla */
    var seedIds = ['subtitle', 'statusTxt', 'clock', 'ptitle', 'pauthor',
      'pstext', 'perrtext', 'ptime', 'pdur', 'pquality', 'pohead', 'kbquery', 'kbtitle'];
    for (var si = 0; si < seedIds.length; si++) {
      var sn = $(seedIds[si]);
      if (sn && !sn.firstChild) { sn.appendChild(document.createTextNode('')); }
    }

    setInterval(function () {
      clockEl.replaceChild(document.createTextNode(fmtClock()), clockEl.firstChild);
    }, 10000);
    clockEl.replaceChild(document.createTextNode(fmtClock()), clockEl.firstChild);

    bindVideo();

    /* v1.3: Orsay SEF oynatıcıyı keşfet (widget ortamında var) */
    initSef();

    /* v1.3: TV'nin tvKey tablosu — yerleşik kodların ÖZERİNE yazmadan
     * eksik kodları tamamla (farklı model/firmware kodları) */
    try {
      var tk = window.tvKey
        || (window.Common && window.Common.API
            ? new window.Common.API.TVKeyValue() : null);
      if (tk) {
        var tp = [
          [tk.KEY_UP, 'up'], [tk.KEY_DOWN, 'down'], [tk.KEY_LEFT, 'left'],
          [tk.KEY_RIGHT, 'right'], [tk.KEY_ENTER, 'ok'],
          [tk.KEY_PANEL_ENTER, 'ok'], [tk.KEY_RETURN, 'back'],
          [tk.KEY_PLAY, 'play'], [tk.KEY_PAUSE, 'pause'], [tk.KEY_STOP, 'stop'],
          [tk.KEY_FF, 'ff'], [tk.KEY_RW, 'rw'], [tk.KEY_RED, 'red'],
          [tk.KEY_GREEN, 'green'], [tk.KEY_YELLOW, 'yellow'], [tk.KEY_BLUE, 'blue']
        ];
        for (var tki = 0; tki < tp.length; tki++) {
          if (typeof tp[tki][0] === 'number' && keyOf(tp[tki][0]) === null) {
            KEY_EXTRA[tp[tki][0]] = tp[tki][1];
          }
        }
      }
    } catch (eTk) {}

    /* v1.3: Orsay hazır sinyali — Smart Hub açılışta bunu bekler */
    try {
      if (window.Common && window.Common.API && window.Common.API.Widget) {
        var wapi = new window.Common.API.Widget();
        if (wapi && typeof wapi.sendReadyEvent === 'function') {
          wapi.sendReadyEvent();
        }
      }
    } catch (eW) {}

    document.onkeydown = onKey;
    if (document.addEventListener) {
      document.addEventListener('keypress', function (e) {
        if (S.view === 'keyboard' && e.charCode >= 32 && e.charCode <= 126) {
          S.query += String.fromCharCode(e.charCode);
          kbUpdateQuery();
        }
      });
    }

    /* v4.4: emniyet sayacı — video oynuyorsa döner durum yazısı
     * ("Tamponlanıyor…") ekranda ASLA kalmaz; olaylar aksasa bile
     * v1.3: SEF oynatıcıda da konum ilerliyorsa temizlenir */
    setInterval(function () {
      if (S.view === 'player' && S.statusSpin) {
        if (S.useSef) {
          if (!S.sefPaused) { clearPStatus(); }
        } else if (!vid.paused && !vid.seeking
              && vid.readyState >= 2) {
          clearPStatus();
        }
      }
    }, 1200);

    /* v4.3: Samsung Orsay widget (file://) — kayıtlı sunucu adresi ya da
       IP giriş ekranı; tarayıcıda (http) BASE boş kalır, aynı origin */
    var isWidget = false;
    try { isWidget = location.protocol === 'file:'; } catch (e) {}
    S.widgetMode = isWidget;
    histInit();
    if (isWidget) {
      var saved = '';
      try { saved = window.localStorage.getItem('lt_base') || ''; } catch (e2) {}
      if (saved) {
        BASE = saved;
        pingOnce();
        setInterval(pingOnce, 30000);
        /* v1.3: widget'ta da derin bağlantı (?v=) — doğrudan video açılır */
        var dm = /[?&]v=([A-Za-z0-9_-]{11})/.exec(location.search || '');
        if (dm) {
          openPlayer(dm[1], '', '');
        } else {
          loadTrending();
        }
      } else {
        openKb('ip');
        pingOnce();
        setInterval(pingOnce, 30000);
      }
      return;
    }

    pingOnce();
    setInterval(pingOnce, 30000);

    /* v4.4: izgara için temel history girdisi — tarayıcı Return'u
     * (önceki siteye atlaması) ilk basışta burada yutulur */
    histPush('base');

    /* dogrudan video baglantisi: /tv?v=VIDEOID (TV APK derin baglanti) */
    var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(location.search || '');
    if (m) {
      openPlayer(m[1], '', '');
    } else {
      loadTrending();
    }
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(boot, 0);
  } else {
    document.onreadystatechange = function () {
      if (document.readyState === 'interactive') { setTimeout(boot, 0); }
    };
  }
})();
