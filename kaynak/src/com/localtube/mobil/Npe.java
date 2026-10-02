package com.localtube.mobil;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OrsayTube'ın GERÇEK motoru: NewPipe Extractor (TeamNewPipe master).
 *
 * diekaiju/localtube, NewPipeExtractor'ı gömülü olarak kullanır ve video
 * akışlarını bu motorla çözer. v3'te aynı sistem kullanılır:
 *   - YoutubeStreamExtractor: ANDROID → IOS → VISIONOS zinciri
 *   - İmza (sig) ve n-parametre çözümlemesi Rhino JS motoruyla yapılır —
 *     v2'de imzasız URL kalmayınca video oynamıyordu; bu motor imzalı
 *     biçimleri de çözümler.
 *   - Akışlar cihazın kendi IP'sinden çözülür (telefonda çalışır).
 */
public final class Npe {

    private static volatile boolean inited = false;
    private static volatile StreamingService yt;
    private static final Object INIT_LOCK = new Object();

    private Npe() {
    }

    /** Uygulama/sunucu başında bir kez çağrılır. */
    public static boolean init() {
        if (inited) {
            return true;
        }
        synchronized (INIT_LOCK) {
            if (inited) {
                return true;
            }
            long t0 = System.currentTimeMillis();
            try {
                NewPipe.init(new HttpDownloader(), new Localization("tr", "TR"));
                yt = NewPipe.getService(0);
                inited = true;
                Diag.ev("newpipe init OK " + Diag.ms(t0));
                return true;
            } catch (Throwable t) {
                Diag.ev("newpipe init HATA: " + Resolver.stack(t));
                return false;
            }
        }
    }

    public static StreamingService service() {
        init();
        return yt;
    }

    /* ================= HTTP İndİRİCİ ================= */

    /**
     * NewPipe Downloader uygulaması — HttpURLConnection üstüne.
     * (diekaiju/localtube ServerDownloader'ının bağımlılıksız eşdeğeri)
     */
    static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String method = request.httpMethod();
            URL url = new URL(request.url());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod(method);
                boolean hasUa = false;
                if (request.headers() != null) {
                    for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                        if (e.getKey() == null) {
                            continue;
                        }
                        for (String v : e.getValue()) {
                            conn.addRequestProperty(e.getKey(), v);
                            if ("User-Agent".equalsIgnoreCase(e.getKey())) {
                                hasUa = true;
                            }
                        }
                    }
                }
                if (!hasUa) {
                    conn.setRequestProperty("User-Agent", Net.UA_WEB);
                }
                boolean post = "POST".equalsIgnoreCase(method);
                conn.setDoOutput(post);
                if (post) {
                    byte[] data = request.dataToSend() != null
                            ? request.dataToSend() : new byte[0];
                    conn.setFixedLengthStreamingMode(data.length);
                    conn.getOutputStream().write(data);
                    conn.getOutputStream().flush();
                }
                int code = conn.getResponseCode();
                if (code == 429) {
                    throw new ReCaptchaException("recaptcha", request.url());
                }
                Map<String, List<String>> respHeaders = new HashMap<String, List<String>>();
                for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                    if (e.getKey() != null) {
                        respHeaders.put(e.getKey(), e.getValue());
                    }
                }
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = "";
                if (is != null) {
                    try {
                        java.io.ByteArrayOutputStream bos =
                                new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                            if (bos.size() > 16 * 1024 * 1024) {
                                break;
                            }
                        }
                        body = new String(bos.toByteArray(), "UTF-8");
                    } finally {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                String latest = conn.getURL().toString();
                return new Response(code, "", respHeaders, body, latest);
            } finally {
                conn.disconnect();
            }
        }
    }

    /* ================= TRENDLER ================= */

    /**
     * YouTube TREND kiosk'u (FEtrending) Temmuz 2025'te YouTube tarafından
     * KALDIRILDI (NewPipe kaynağında da 'deprecated' notu var) → eski
     * "Trending" kiosk'u 400/ParsingException verir.
     *
     * v4.4 stratejisi: CANLI ve ÇALIŞAN kiosk'lar karıştırılır —
     *   trending_music (TR Top 100 video listesi), trending_gaming,
     *   trending_movies_and_shows, live (şu an yayınlanan canlılar)
     * Sırayla deneyip round-robin karıştırılır: biri ölürse digerleri
     * listeyi doldurur; hepsi ölürse Piped → sabit liste (Resolver).
     */
    private static final String[] KIOSKS = {
            "trending_music", "trending_gaming",
            "trending_movies_and_shows", "live"
    };

    public static List<Map<String, Object>> trending(int limit) {
        /* v4.4: 4 kiosk PARALEL çekilir (sırayla 4-5sn yerine ~2sn) —
         * her kiosk başına yeterince öğe al, sonra karıştır */
        final int perKiosk = Math.max(10, limit / KIOSKS.length + 6);
        final List<List<Map<String, Object>>> parts =
                java.util.Collections.synchronizedList(
                        new ArrayList<List<Map<String, Object>>>());
        Thread[] ts = new Thread[KIOSKS.length];
        for (int i = 0; i < KIOSKS.length; i++) {
            final String kid = KIOSKS[i];
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    List<Map<String, Object>> k = trendingKiosk(kid, perKiosk);
                    if (k != null && !k.isEmpty()) {
                        parts.add(k);
                    }
                }
            }, "lt-kiosk-" + kid);
            ts[i].setDaemon(true);
            ts[i].start();
        }
        long deadline = System.currentTimeMillis() + 9000;
        for (int i = 0; i < ts.length; i++) {
            long rest = deadline - System.currentTimeMillis();
            if (rest <= 0) {
                break;
            }
            try {
                ts[i].join(rest);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        /* snapshot: asılı kalan bir kiosk iş parçacığı listeye eklerse
         * yineleme sırasında ConcurrentModificationException patlamasın */
        List<List<Map<String, Object>>> snap;
        synchronized (parts) {
            snap = new ArrayList<List<Map<String, Object>>>(parts);
        }
        List<Map<String, Object>> out = interleave(snap, limit);
        Diag.ev("newpipe trending v4.4: " + out.size() + " video ("
                + parts.size() + "/" + KIOSKS.length + " kiosk canli)");
        return out;
    }

    /** Listeleri sırayla birer birer karıştır (tümü müzik olmasın). */
    private static List<Map<String, Object>> interleave(
            List<List<Map<String, Object>>> parts, int limit) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        int idx = 0;
        boolean added = true;
        while (out.size() < limit && added) {
            added = false;
            for (int p = 0; p < parts.size() && out.size() < limit; p++) {
                List<Map<String, Object>> part = parts.get(p);
                if (idx < part.size()) {
                    Map<String, Object> m = part.get(idx);
                    String vid = String.valueOf(m.get("videoId"));
                    if (!seen.contains(vid)) {
                        seen.add(vid);
                        out.add(m);
                        added = true;
                    }
                }
            }
            idx++;
        }
        return out;
    }

    /** Belirli bir kiosk'u TR ülkesiyle çözümle (hata yutulur, boş döner). */
    private static List<Map<String, Object>> trendingKiosk(
            String kioskId, int limit) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            StreamingService s = service();
            if (s == null) {
                return out;
            }
            KioskList kl = s.getKioskList();
            kl.forceContentCountry(new ContentCountry("TR"));
            KioskExtractor<?> kiosk = kl.getExtractorById(kioskId, null);
            if (kiosk == null) {
                return out;
            }
            long t0 = System.currentTimeMillis();
            kiosk.fetchPage();
            ListExtractor.InfoItemsPage<? extends InfoItem> page =
                    kiosk.getInitialPage();
            collectItems(page.getItems(), out, limit);
            Diag.ev("newpipe kiosk " + kioskId + " OK: " + out.size()
                    + " video " + Diag.ms(t0));
        } catch (Throwable t) {
            Diag.ev("newpipe kiosk " + kioskId + " HATA: " + Resolver.stack(t));
        }
        return out;
    }

    /* ================= ARAMA ================= */

    /** NewPipe araması → videoId/title/channel/duration sözlük listesi. */
    public static List<Map<String, Object>> search(String q, int limit) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            StreamingService s = service();
            if (s == null) {
                return out;
            }
            SearchQueryHandler qh = s.getSearchQHFactory().fromQuery(q);
            SearchExtractor se = s.getSearchExtractor(qh);
            se.fetchPage();
            ListExtractor.InfoItemsPage<InfoItem> page = se.getInitialPage();
            collectItems(page.getItems(), out, limit);
            /* ilk sayfa az gelirse sonraki sayfaları da dene (tek seferlik) */
            if (out.size() < Math.min(limit, 10) && page.hasNextPage()) {
                try {
                    ListExtractor.InfoItemsPage<InfoItem> next = se.getPage(
                            page.getNextPage());
                    if (next != null) {
                        collectItems(next.getItems(), out, limit);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            System.out.println("NPE search fail: " + t);
        }
        return out;
    }

    private static void collectItems(List<? extends InfoItem> items,
            List<Map<String, Object>> out, int limit) {
        if (items == null) {
            return;
        }
        for (InfoItem it : items) {
            if (!(it instanceof StreamInfoItem)) {
                continue;
            }
            StreamInfoItem si = (StreamInfoItem) it;
            String url = si.getUrl();
            String id = Resolver.videoIdOf(url);
            if (id == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("videoId", id);
            m.put("title", si.getName());
            m.put("channel", si.getUploaderName());
            m.put("duration", Resolver.fmtDur(si.getDuration()));
            m.put("views", Resolver.fmtViews(si.getViewCount()));
            out.add(m);
            if (out.size() >= limit) {
                return;
            }
        }
    }

    /* ================= AKIŞ ÇÖZÜMLEME ================= */

    /** Çözümlenen video bilgisi — Resolver tarafından JSON'a dökülür. */
    public static final class Vid {
        public String title;
        public String author;
        public long duration = -1;
        public String hls;
        public boolean live;
        public final List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        public final Map<String, Resolver.Up> ups = new HashMap<String, Resolver.Up>();
        public final List<Map<String, Object>> related = new ArrayList<Map<String, Object>>();
    }

    /**
     * NewPipe StreamInfo ile akışları çözümler.
     * Progressive (muxed) akışlar itag listesine girer; HLS manifest ayrıca
     * taşınır. İmza/n-parametre çözümlemesi motorun içinde Rhino ile yapılır.
     */
    public static Vid streams(String videoId) throws IOException,
            org.schabi.newpipe.extractor.exceptions.ExtractionException {
        long t0 = System.currentTimeMillis();
        Vid v = new Vid();
        StreamInfo info = StreamInfo.getInfo(service(),
                "https://www.youtube.com/watch?v=" + videoId);
        Diag.ev("StreamInfo.getInfo OK " + Diag.ms(t0) + " " + Diag.vid(videoId));
        v.title = info.getName();
        v.author = info.getUploaderName();
        if (info.getDuration() > 0) {
            v.duration = info.getDuration();
        }
        v.live = info.getStreamType() == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                || info.getStreamType() == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM;
        try {
            v.hls = info.getHlsUrl();
        } catch (Exception ignored) {
            v.hls = null;
        }

        /* Progressive (muxed) akışlar — HTML5 video ile doğrudan oynar */
        try {
            List<VideoStream> vs = info.getVideoStreams();
            if (vs != null) {
                for (VideoStream s : vs) {
                    if (s.isVideoOnly()) {
                        continue;
                    }
                    String url = s.getContent();
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                    String it = String.valueOf(s.getItag());
                    String label = s.getResolution() != null
                            ? s.getResolution() : it;
                    if (v.ups.containsKey(it)) {
                        continue;
                    }
                    /* diekaiju/localtube yöntemi: URL'deki c= parametresine göre UA */
                    v.ups.put(it, new Resolver.Up(url, Net.uaKindForUrl(url)));
                    Map<String, Object> src = new LinkedHashMap<String, Object>();
                    src.put("itag", it);
                    src.put("label", label);
                    src.put("kind", "progressive");
                    src.put("mime", s.getFormat() != null && s.getFormat().mimeType != null
                            ? s.getFormat().mimeType : "video/mp4");
                    src.put("url", "/video?v=" + videoId + "&itag=" + it);
                    v.sources.add(src);
                }
            }
        } catch (Exception ignored) {
        }

        /* Benzer videolar */
        try {
            List<InfoItem> rel = info.getRelatedStreams();
            if (rel != null) {
                int n = 0;
                for (InfoItem it : rel) {
                    if (!(it instanceof StreamInfoItem)) {
                        continue;
                    }
                    StreamInfoItem si = (StreamInfoItem) it;
                    String id = Resolver.videoIdOf(si.getUrl());
                    if (id == null) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("videoId", id);
                    m.put("title", si.getName());
                    m.put("channel", si.getUploaderName());
                    m.put("duration", Resolver.fmtDur(si.getDuration()));
                    v.related.add(m);
                    if (++n >= 12) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return v;
    }
}
