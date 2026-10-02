package com.localtube.mobil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper;

/**
 * YouTube çözümleyici — diekaiju/localtube / NewPipe modeli:
 * TÜM istekler cihazın kendi IP'sinden yapılır, akış URL'leri cihaz için
 * geçerli olur (IP-kilitli googlevideo bağlantıları bu yüzden telefonda çalışır).
 *
 * Zincirler (v3 — diekaiju/localtube'ın GERÇEK motoru):
 *   Arama   : NewPipe Extractor → innertube WEB → Piped
 *   Trendler: Piped (TR) → innertube WEB browse → sabit yedek liste
 *   Akışlar : NewPipe Extractor (imza/n-parametre Rhino ile çözülür)
 *             → innertube ANDROID_VR → IOS (HLS) → Piped
 *             → v4.6: WEB imzalı 720p (itag 22) zenginleştirmesi —
 *               VR/IOS 2025'ten beri imzasız 720p muxed vermiyor;
 *               WEB istemcisinin signatureCipher'ı Rhino ile çözülür
 */
public final class Resolver {

    private static final Pattern VID = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final String WEB_VER = "2.20260922.06.00";
    private static final long SEARCH_TTL = 5 * 60 * 1000L;
    private static final long TREND_TTL = 10 * 60 * 1000L;
    /* v4.4: offline yedek liste yalnız 60sn önbelleklenir — kaynaklar
     * toparlanınca bir dakika içinde GERÇEK trendler döner */
    private static final long TREND_OFFLINE_TTL = 60 * 1000L;
    private static final long STREAM_TTL = 30 * 60 * 1000L;

    /* Piped örnekleri — sırayla denenir (doğrulanmış: private.coffee) */
    private static final String[] PIPED_BASES = {
            "https://api.piped.private.coffee",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.ducks.party",
            "https://api.piped.yt",
    };

    /* Yedek trend listesi — her üçüncü taraf ölürse ana sayfa yine dolu olsun */
    private static final String[][] CURATED = {
            {"dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up"},
            {"kJQP7kiw5Fk", "Luis Fonsi - Despacito ft. Daddy Yankee"},
            {"JGwWNGJdvx8", "Ed Sheeran - Shape of You"},
            {"OPf0YbXqDm0", "Mark Ronson - Uptown Funk ft. Bruno Mars"},
            {"fJ9rUzIMcZQ", "Queen - Bohemian Rhapsody"},
            {"9bZkp7q19f0", "PSY - Gangnam Style"},
            {"YQHsXMglC9o", "Adele - Hello"},
            {"CevxZvSJLk8", "Katy Perry - Roar"},
            {"hT_nvWreIhg", "OneRepublic - Counting Stars"},
            {"09R8_2nJtjg", "Maroon 5 - Sugar"},
            {"lp-EO5I60KA", "Ed Sheeran - Thinking Out Loud"},
            {"450p7goxZqg", "John Legend - All of Me"},
    };

    /** Vekil akış için yukarı-adres + UA bilgisi. */
    public static final class Up {
        public final String url;
        public final String uaKind;

        public Up(String url, String uaKind) {
            this.url = url;
            this.uaKind = uaKind;
        }
    }

    /* ---------------- v4.5: HLS kısa-jeton kaydı ----------------
     * Eski TV tarayıcıları 1,5-2 KB'lık googlevideo adreslerini sorgu
     * dizgisinde taşımakta zorlanabiliyordu. /hls?u=... artık 10
     * karakterlik kısa jetonla çalışır: jeton → (adres, ua) eşlemesi
     * bellekte LRU olarak tutulur; manifest yeniden-yazımı da aynı
     * kaydı kullanır (parça adresleri de kısa jeton olur). */
    private static final int HLS_TOK_MAX = 4096;
    private static final Map<String, String[]> hlsTok =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<String, String[]>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, String[]> e) {
                            return size() > HLS_TOK_MAX;
                        }
                    });
    private static final Map<String, String> hlsTokRev =
            java.util.Collections.synchronizedMap(new HashMap<String, String>());
    private static final java.util.Random HLS_RND =
            new java.util.Random();

    /** Adres için kalıcı kısa /hls?u=... referansı üretir (aynı adres → aynı jeton). */
    public static String hlsRef(String absUrl, String uaKind) {
        if (absUrl == null || absUrl.length() == 0) {
            return null;
        }
        synchronized (hlsTokRev) {
            String have = hlsTokRev.get(absUrl);
            if (have != null) {
                String[] cur = hlsTok.get(have);
                if (cur != null) {
                    hlsTok.get(have); /* LRU dokunuş */
                    return "/hls?u=" + have;
                }
            }
            String tok;
            do {
                tok = Long.toHexString((HLS_RND.nextLong() & 0xFFFFFFFFFFL))
                        + Long.toHexString((System.nanoTime() >>> 16) & 0xFFFFL);
            } while (tok.length() < 10 || hlsTok.containsKey(tok));
            hlsTok.put(tok, new String[]{absUrl, uaKind == null ? "ios" : uaKind});
            hlsTokRev.put(absUrl, tok);
            return "/hls?u=" + tok;
        }
    }

    /** Jetonu çözer → [adres, ua]; bilinmiyorsa null. */
    public static String[] hlsRefGet(String tok) {
        if (tok == null || tok.length() == 0 || tok.startsWith("http")) {
            return null;
        }
        String[] r = hlsTok.get(tok);
        if (r != null) {
            hlsTok.get(tok); /* LRU dokunuş */
        }
        return r;
    }

    /**
     * v4.5: HLS master manifest'i indirip kalite varyantlarını
     * (1080p/720p/...) ayrı kaynak olarak çözer. Hata olursa null —
     * çağıran yalnız master kaynağıyla yetinir.
     */
    private static List<Map<String, Object>> hlsVariants(String masterUrl,
            String uaKind) {
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("User-Agent", Net.uaFor(uaKind));
            Net.Resp r = Net.fetch(masterUrl, "GET", h, null, 5000, 9000, 262144);
            if (r.code < 200 || r.code >= 300 || r.body.length == 0) {
                return null;
            }
            String body = r.bodyText();
            if (body == null || body.indexOf("#EXT-X-STREAM-INF") < 0) {
                return null;
            }
            java.net.URL base = new java.net.URL(masterUrl);
            String[] lines = body.split("\n", -1);
            List<int[]> metas = new ArrayList<int[]>();   /* {satır, w, h, fps} */
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (!t.startsWith("#EXT-X-STREAM-INF")) {
                    continue;
                }
                /* ses-only satırlar RESOLUTION taşımaz — atla */
                Matcher mr = Pattern.compile("RESOLUTION=(\\d+)[xX](\\d+)")
                        .matcher(t);
                if (!mr.find()) {
                    continue;
                }
                int w = 0, hh = 0, fps = 0;
                try {
                    w = Integer.parseInt(mr.group(1));
                    hh = Integer.parseInt(mr.group(2));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                Matcher mf = Pattern.compile("FRAME-RATE=(\\d+(?:\\.\\d+)?)")
                        .matcher(t);
                if (mf.find()) {
                    try {
                        fps = (int) Math.round(Double.parseDouble(mf.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                /* sıradaki boş-olmayan ve yorum-olmayan satır = URI */
                String uri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String u2 = lines[j].trim();
                    if (u2.length() == 0) {
                        continue;
                    }
                    if (u2.startsWith("#")) {
                        break; /* beklenmedik biçim — sonraki STREAM-INF'e geç */
                    }
                    uri = u2;
                    metas.add(new int[]{j, w, hh, fps});
                    break;
                }
            }
            if (metas.isEmpty()) {
                return null;
            }
            java.util.Collections.sort(metas, new java.util.Comparator<int[]>() {
                public int compare(int[] a, int[] b) {
                    if (b[2] != a[2]) {
                        return b[2] - a[2];
                    }
                    return b[3] - a[3];
                }
            });
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            List<String> seen = new ArrayList<String>();
            for (int[] m : metas) {
                String uri = lines[m[0]].trim();
                String label = "HLS " + m[2] + "p" + (m[3] > 30 ? m[3] : "");
                if (seen.contains(label)) {
                    continue; /* aynı etiket iki kez (ör. HDR + SDR) — ilki kalır */
                }
                try {
                    String abs = new java.net.URL(base, uri).toString();
                    if (!abs.startsWith("http")) {
                        continue;
                    }
                    Map<String, Object> src = new LinkedHashMap<String, Object>();
                    src.put("itag", "hls" + m[2]);
                    src.put("label", label);
                    src.put("kind", "hls");
                    src.put("h", Integer.valueOf(m[2]));
                    src.put("mime", "application/x-mpegurl");
                    src.put("url", hlsRef(abs, uaKind));
                    out.add(src);
                    seen.add(label);
                    if (out.size() >= 8) {
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v4.5: HLS kaynak listesi üretir — varyantlar (yüksek kalite önce)
     * + "otomatik" master. sources listesinin BAŞINA eklenir.
     */
    private static void addHlsSources(String hlsUrl, String uaKind,
            List<Object> sources, Map<String, Up> ups) {
        if (hlsUrl == null || hlsUrl.length() == 0) {
            return;
        }
        ups.put("hls", new Up(hlsUrl, uaKind));
        List<Map<String, Object>> vs = hlsVariants(hlsUrl, uaKind);
        List<Object> add = new ArrayList<Object>();
        if (vs != null) {
            add.addAll(vs);
        }
        Map<String, Object> master = new LinkedHashMap<String, Object>();
        master.put("itag", "hls");
        master.put("label", "HLS (otomatik kalite)");
        master.put("kind", "hls");
        master.put("mime", "application/x-mpegurl");
        master.put("url", hlsRef(hlsUrl, uaKind));
        add.add(master);
        sources.addAll(0, add);
    }

    /* ================= v4.6: WEB istemcisi imzalı 720p ================= */

    /**
     * v4.6: "720p" gibi etiketten çözünürlük yüksekliği çıkarır.
     */
    private static int resHeightOf(String label) {
        if (label == null) {
            return 0;
        }
        Matcher m = Pattern.compile("(\\d{3,4})p").matcher(label);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * v4.6: WEB istemcisi signatureCipher (s/sp/url) üçlemesini çözer.
     * İmza, NewPipe'in JS yöneticisiyle (Rhino) deobfuscate edilir —
     * bu, uygulamanın zaten gömdüğü ve NewPipe'ın kullandığı yolun
     * birebir aynısıdır. Başarısızlıkta null döner (çağıran atlar).
     */
    private String decipherWebCipher(String v, String cipher) {
        try {
            Map<String, String> parts = new HashMap<String, String>();
            String[] kvs = cipher.split("&");
            for (String kv : kvs) {
                int eq = kv.indexOf('=');
                if (eq <= 0 || eq >= kv.length() - 1) {
                    continue;
                }
                parts.put(
                        java.net.URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                        java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            }
            String s = parts.get("s");
            String url = parts.get("url");
            if (s == null || s.length() == 0 || url == null || url.length() == 0) {
                return null;
            }
            String sp = parts.get("sp");
            if (sp == null || sp.length() == 0) {
                sp = "signature";
            }
            String sig = YoutubeJavaScriptPlayerManager
                    .deobfuscateSignature(v, s);
            if (sig == null || sig.length() == 0) {
                return null;
            }
            return url + "&" + sp + "=" + sig;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v4.6: ANDROID_VR/IOS artık imzasız 720p muxed biçim vermiyor —
     * bu yüzden TV 360p'de kalıyordu. WEB istemcisi itag 22'yi (720p
     * muxed MP4) İMZALI (signatureCipher) verir; imza + n-parametresi
     * NewPipe'in JS yöneticisiyle çözülüp ilerici kaynak listesine
     * eklenir. Listede zaten 720p+ ilerici kaynak varsa atlanır; her
     * hata yutulur (zincir asla bozulmaz). Yalnızca video/MP4 kabul
     * edilir — eski TV'ler webm kabullenmez.
     */
    private void webEnrich(String v, List<Object> sources, Map<String, Up> ups) {
        try {
            boolean have720 = false;
            for (Object o : sources) {
                Map<String, Object> s = Json.asMap(o);
                if (s != null && "progressive".equals(Json.s(s, "kind"))
                        && resHeightOf(Json.s(s, "label")) >= 720) {
                    have720 = true;
                    break;
                }
            }
            if (have720) {
                return;
            }
            if (!Npe.init()) {
                return; /* JS yöneticisi NewPipe indiricisi ister */
            }
            /* v4.6: motorun KENDİ WEB_EMBEDDED_PLAYER yardımcısı —
             * visitorData, imza zaman damgası (STS) ve doğru istemci
             * başlıkları hazır gelir. STS, player JS'sinden okunur. */
            int sts = 0;
            try {
                Integer st = YoutubeJavaScriptPlayerManager
                        .getSignatureTimestamp(v);
                if (st != null) {
                    sts = st.intValue();
                }
            } catch (Throwable ignored) {
            }
            JsonObject wobj = null;
            try {
                wobj = YoutubeStreamHelper
                        .getWebEmbeddedPlayerResponse(
                                new Localization("tr", "TR"),
                                new ContentCountry("TR"),
                                v,
                                "https://www.youtube.com/embed/" + v,
                                null, sts);
            } catch (Throwable t) {
                Diag.ev("web720: WEB_EMBEDDED hatasi: " + stack(t));
                return;
            }
            if (wobj == null) {
                Diag.ev("web720: WEB_EMBEDDED yanit yok " + Diag.vid(v));
                return;
            }
            /* nanojson API doğrudan — kendi Json ayrıştırıcımız nanojson
             * çıktısındaki bazı kalıpları yönetemiyor (key@1 hatası) */
            JsonObject ps = wobj.getObject("playabilityStatus");
            String pst = ps == null ? null : ps.getString("status");
            if (pst != null && !pst.equals("OK")) {
                Diag.ev("web720: WEB_EMBEDDED playability=" + pst
                        + " " + Diag.vid(v));
                return;
            }
            JsonObject sd = wobj.getObject("streamingData");
            JsonArray fmts = sd == null ? null : sd.getArray("formats");
            if (fmts == null || fmts.isEmpty()) {
                return;
            }
            int added = 0;
            for (Object o : fmts) {
                if (!(o instanceof JsonObject)) {
                    continue;
                }
                JsonObject f = (JsonObject) o;
                long itag = f.getInt("itag", 0);
                if (itag <= 0) {
                    continue;
                }
                String it = String.valueOf(itag);
                if (ups.containsKey(it)) {
                    continue; /* VR'den aynı itag zaten var (imzasız tercih) */
                }
                String mime = f.getString("mimeType");
                if (mime != null && mime.contains("webm")) {
                    continue; /* eski TV mp4 ister */
                }
                String label = f.getString("qualityLabel");
                String url = f.getString("url");
                if (url == null || url.length() == 0) {
                    String cipher = f.getString("signatureCipher");
                    if (cipher == null || cipher.length() == 0) {
                        cipher = f.getString("cipher");
                    }
                    if (cipher == null || cipher.length() == 0) {
                        continue;
                    }
                    url = decipherWebCipher(v, cipher);
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                }
                /* n-parametresi (hız sınırlayıcı) çöz — çözülmezse URL
                 * yine çalışabilir; istisna yutulur */
                try {
                    String u2 = YoutubeJavaScriptPlayerManager
                            .getUrlWithThrottlingParameterDeobfuscated(v, url);
                    if (u2 != null && u2.length() > 0) {
                        url = u2;
                    }
                } catch (Throwable ignored) {
                }
                if (url == null || url.length() == 0) {
                    continue;
                }
                ups.put(it, new Up(url, "web"));
                Map<String, Object> src = new LinkedHashMap<String, Object>();
                src.put("itag", it);
                src.put("label", label == null ? it : label);
                src.put("kind", "progressive");
                src.put("mime", mime == null ? "video/mp4" : mime.split(";")[0]);
                src.put("url", "/video?v=" + enc(v) + "&itag=" + it);
                sources.add(src);
                added++;
            }
            if (added > 0) {
                Diag.ev("web720: +" + added + " imzali ilerici kaynak " + Diag.vid(v));
            }
        } catch (Throwable t) {
            Diag.ev("web720 hata: " + stack(t));
        }
    }

    private static final class CacheEntry {
        final String json;
        final long ts;
        final Map<String, Up> ups;

        CacheEntry(String json, Map<String, Up> ups) {
            this.json = json;
            this.ts = System.currentTimeMillis();
            this.ups = ups;
        }
    }

    private final Map<String, CacheEntry> searchCache =
            java.util.Collections.synchronizedMap(new HashMap<String, CacheEntry>());
    private volatile CacheEntry trendCache;
    private final Map<String, CacheEntry> streamCache =
            java.util.Collections.synchronizedMap(new HashMap<String, CacheEntry>());

    public void invalidate(String videoId) {
        if (videoId != null) {
            streamCache.remove(videoId);
            /* v4 rotasyon: NPE kaynakları 403 verdiyse bir SONRAKİ
             * çözümleme VR (innertube) zincirini ÖNCE dener — farklı
             * imzacı istemciden yeni URL şansı. */
            preferVr.put(videoId, Boolean.TRUE);
        }
    }

    /* "VR zincirini önce dene" bayrağı (invalidate ile açılır) */
    private final Map<String, Boolean> preferVr =
            java.util.Collections.synchronizedMap(
                    new HashMap<String, Boolean>());

    private static String searchSummary(String json) {
        try {
            Map<String, Object> m = Json.asMap(Json.parse(json));
            if (m == null) {
                return "?";
            }
            Object items = m.get("items");
            int n = items instanceof List ? ((List<?>) items).size() : 0;
            return "sonuc=" + n + " via=" + m.get("via");
        } catch (Throwable t) {
            return "?";
        }
    }

    /* ================= ARAMA ================= */

    public String search(String q) {
        if (q == null || q.trim().length() == 0) {
            return errJson(400, "sorgu bos");
        }
        long t0 = System.currentTimeMillis();
        q = q.trim();
        synchronized (searchCache) {
            CacheEntry e = searchCache.get(q);
            if (e != null && System.currentTimeMillis() - e.ts < SEARCH_TTL) {
                return e.json;
            }
        }
        String json = searchLive(q);
        Diag.ev("arama '" + q + "' -> " + Diag.ms(t0) + " "
                + (json == null ? "BOS" : searchSummary(json)));
        if (json != null) {
            synchronized (searchCache) {
                if (searchCache.size() > 48) {
                    searchCache.clear();
                }
                searchCache.put(q, new CacheEntry(json, null));
            }
            return json;
        }
        return errJson(502, "arama kaynaklari yanit vermedi (innertube + Piped)");
    }

    private String searchLive(String q) {
        /* 0) NewPipe Extractor — diekaiju/localtube motoru */
        try {
            List<Map<String, Object>> npeItems = Npe.search(q, 40);
            if (npeItems != null && !npeItems.isEmpty()) {
                List<Object> items = new ArrayList<Object>(npeItems);
                return itemsJson(items, "newpipe");
            }
        } catch (Throwable ignored) {
        }
        List<Object> items = innertubeSearch(q, 40);
        String via = "innertube";
        if (items == null || items.isEmpty()) {
            items = pipedList("/search?q=" + enc(q) + "&filter=videos", "items", 40);
            via = "piped";
        }
        if (items == null || items.isEmpty()) {
            return null;
        }
        return itemsJson(items, via);
    }

    /* ================= TRENDLER ================= */

    public String trending() {
        CacheEntry e = trendCache;
        long ttl = trendOffline ? TREND_OFFLINE_TTL : TREND_TTL;
        if (e != null && System.currentTimeMillis() - e.ts < ttl) {
            return e.json;
        }
        String json = trendingLive();
        boolean offline = json != null && json.contains("\"offline-liste\"");
        if (json != null && !offline) {
            trendCache = new CacheEntry(json, null);
            /* v4.3: son İYİ listeyi süresiz sakla — ağ kaynakları ölürse
             * offline sabit liste yerine bu taze liste gösterilir */
            trendStale = json;
            trendOffline = false;
            return json;
        }
        /* v4.4: offline yedek kısa TTL ile önbelleklenir (60sn) — uygulama
         * açıkken kaynaklar toparlanırsa otomatik gerçek listeye döner */
        if (json != null) {
            trendCache = new CacheEntry(json, null);
            trendOffline = true;
            return json;
        }
        /* v4.3: canlı trend alınamadıysa önce eski iyi liste */
        if (trendStale != null) {
            Diag.ev("trendler canli alinamadi — eski iyi liste servis ediliyor");
            return trendStale;
        }
        return errJson(502, "trend kaynaklari yanit vermedi");
    }

    /* v4.3: son başarılı trend listesi (TTL'siz joker) */
    private volatile String trendStale;
    /* v4.4: önbellekteki liste offline yedek mi (kısa TTL)? */
    private volatile boolean trendOffline;

    private String trendingLive() {
        /* v4.2: ÖNCE gömülü NewPipe motoru — cihaz IP'sinden GERÇEK YouTube
         * trend listesi (imza/n çözümlemesi çalışan motor). Motor boş
         * dönerse Piped → innertube → son çare sabit liste. */
        List<Object> items = toObjList(Npe.trending(40));
        String via = "newpipe";
        if (items == null || items.isEmpty()) {
            items = pipedList("/trending?region=TR", null, 40);
            via = "piped";
        }
        if (items == null || items.isEmpty()) {
            items = innertubeTrending(40);
            via = "innertube";
        }
        if ((items == null || items.isEmpty())) {
            items = new ArrayList<Object>();
            for (String[] c : CURATED) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("videoId", c[0]);
                m.put("title", c[1]);
                m.put("channel", "OrsayTube");
                m.put("duration", "");
                items.add(m);
            }
            via = "offline-liste";
        }
        return itemsJson(items, via);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toObjList(List<? extends Object> in) {
        if (in == null) {
            return null;
        }
        return (List<Object>) in;
    }

    /* ================= AKIŞLAR ================= */

    /**
     * /api/streams yanıtı: meta + oynatılabilir kaynak listesi.
     * Kaynak URL'leri yerel vekile işaret eder (/video, /direct, /hls).
     */
    public String streams(String v) {
        if (v == null || !v.matches("[A-Za-z0-9_-]{11}")) {
            return errJson(400, "gecersiz video kimligi");
        }
        synchronized (streamCache) {
            CacheEntry e = streamCache.get(v);
            if (e != null && System.currentTimeMillis() - e.ts < STREAM_TTL) {
                return e.json;
            }
        }
        CacheEntry e = streamsLive(v, new String[1]);
        if (e == null) {
            return errJson(502, "video bilgisi alinamadi — " + lastWhy);
        }
        synchronized (streamCache) {
            if (streamCache.size() > 60) {
                streamCache.clear();
            }
            streamCache.put(v, e);
        }
        return e.json;
    }

    /** Vekil akış için (videoId, itag) → yukarı adres. */
    public Up upstream(String v, String itag) {
        synchronized (streamCache) {
            CacheEntry e = streamCache.get(v);
            if (e != null && e.ups != null && e.ups.containsKey(itag)
                    && System.currentTimeMillis() - e.ts < STREAM_TTL) {
                return e.ups.get(itag);
            }
        }
        CacheEntry e = streamsLive(v);
        if (e != null && e.ups != null && e.ups.containsKey(itag)) {
            synchronized (streamCache) {
                streamCache.put(v, e);
            }
            return e.ups.get(itag);
        }
        return null;
    }

    /** Son başarısız çözümlemenin kısa nedeni (hatayı kullanıcıya taşımak için). */
    private volatile String lastWhy = "kaynaklar yanit vermedi";

    private CacheEntry streamsLive(String v) {
        return streamsLive(v, new String[1]);
    }

    private CacheEntry streamsLive(String v, String[] why) {
        boolean vrFirst;
        synchronized (preferVr) {
            vrFirst = Boolean.TRUE.equals(preferVr.get(v));
        }
        long t0 = System.currentTimeMillis();
        Diag.ev("cozumleme BASLA " + Diag.vid(v) + (vrFirst ? " (vr-once)" : " (newpipe-once)"));
        /* 0) NewPipe Extractor — diekaiju/localtube'ın gerçek motoru.
         *    İmza (sig) ve n-parametre çözümlemesi Rhino ile yapılır; v2'nin
         *    'imzasız URL yoksa kaynak yok' açığını kapatır.
         *    (rotasyon: önceki denemede NPE URL'leri 403 verdiyse bu kez VR zinciri
         *    önce koşar — telefonda değişen koşullara ikinci şans) */
        if (!vrFirst) {
            try {
                if (Npe.init()) {
                    Npe.Vid nv = Npe.streams(v);
                    if (nv != null && (!nv.sources.isEmpty()
                            || (nv.hls != null && nv.hls.length() > 0))) {
                        CacheEntry e = entryFromNpe(v, nv);
                        Diag.ev("cozumleme OK newpipe " + Diag.ms(t0) + " "
                                + entrySummary(e));
                        return e;
                    }
                    Diag.ev("newpipe bos kaynak dondu " + Diag.vid(v));
                    why[0] = "newpipe kaynak dondurmedi";
                } else {
                    Diag.ev("newpipe init BASARISIZ");
                    why[0] = "newpipe motoru baslamadi";
                }
            } catch (Throwable t) {
                Diag.ev("newpipe istisna: " + stack(t));
                String msg = String.valueOf(t);
                if (msg.contains("bot") || msg.contains("not a bot")
                        || msg.contains("sign in") || msg.contains("SignIn")) {
                    why[0] = "YouTube bu IP'ye bot bayragı vurdu (oturum duvari) — "
                            + "Wi-Fi agina gecip tekrar dene";
                } else if (msg.contains("restricted")
                        || msg.contains("Google Workspace")
                        || msg.contains("ContentNotAvailable")) {
                    /* v4.3: müzik videolarında yaygın — ANDROID istemcisi
                     * reddedilir ama IOS/VISIONOS (motor yamasıyla) çalışır;
                     * zincir zaten devam ediyor, kullanıcıya korkutucu
                     * mesaj verme */
                    why[0] = "YouTube bu videoyu bu istemci için kısıtladı "
                            + "(yedek istemciler deneniyor)";
                } else if (msg.contains("age") || msg.contains("Age")) {
                    why[0] = "yas kisitli video";
                } else {
                    why[0] = "newpipe: " + stack(t);
                }
            }
        }

        String title = null;
        String author = null;
        long durSec = -1;
        Map<String, Up> ups = new LinkedHashMap<String, Up>();
        List<Object> sources = new ArrayList<Object>();
        List<Object> related = new ArrayList<Object>();
        StringBuilder via = new StringBuilder();

        /* 1) ANDROID_VR — imzasız itag 18/22 (garantili oynatılabilir) */
        Map<String, Object> vr = innertubePlayer(v, "ANDROID_VR", "1.64.88", "28",
                Net.UA_VR, 9000);
        if (vr != null) {
            Map<String, Object> det = Json.asMap(vr.get("videoDetails"));
            if (det != null) {
                title = Json.s(det, "title");
                author = Json.s(det, "author");
                durSec = Json.lg(det, "lengthSeconds", -1);
            }
            List<Object> fmts = Json.asList(
                    Json.asMap(vr.get("streamingData")).get("formats"));
            if (fmts != null) {
                for (Object o : fmts) {
                    Map<String, Object> f = Json.asMap(o);
                    if (f == null) {
                        continue;
                    }
                    String url = Json.s(f, "url");
                    long itag = Json.lg(f, "itag", -1);
                    if (url == null || url.length() == 0 || itag <= 0) {
                        continue; /* imzalı (s parametreli) biçimler atlanır */
                    }
                    String label = Json.s(f, "qualityLabel");
                    String mime = Json.s(f, "mimeType");
                    String it = String.valueOf(itag);
                    if (ups.containsKey(it)) {
                        continue;
                    }
                    ups.put(it, new Up(url, "vr"));
                    Map<String, Object> src = new LinkedHashMap<String, Object>();
                    src.put("itag", it);
                    src.put("label", label == null ? (itag + "") : label);
                    src.put("kind", "progressive");
                    src.put("mime", mime == null ? "video/mp4" : mime.split(";")[0]);
                    src.put("url", "/video?v=" + enc(v) + "&itag=" + it);
                    sources.add(src);
                }
            }
            if (!sources.isEmpty()) {
                via.append("androidvr");
            }
        }

        /* 1b) v4.6: WEB imzalı 720p — VR artık itag 22 vermiyor; WEB
         * istemcisinin signatureCipher'ı Rhino ile çözülür (TV 360p'de
         * kalıyordu — kullanıcı raporu). 720p zaten varsa atlanır. */
        webEnrich(v, sources, ups);

        /* 2) IOS — HLS manifest (otomatik kalite; destekleyen cihazlarda)
         * v4.3: istemci sürümü NewPipe master ile eşitlendi (21.03.2) —
         * eski sürümler YouTube tarafından bot sayılıyordu. */
        Map<String, Object> ios = innertubePlayer(v, "IOS", "21.03.2", "5",
                Net.UA_IOS, 7000);
        String hlsUrl = null;
        if (ios != null) {
            Map<String, Object> sd = Json.asMap(ios.get("streamingData"));
            hlsUrl = sd == null ? null : Json.s(sd, "hlsManifestUrl");
            if (durSec < 0) {
                Map<String, Object> det = Json.asMap(ios.get("videoDetails"));
                if (det != null) {
                    if (title == null) {
                        title = Json.s(det, "title");
                    }
                    if (author == null) {
                        author = Json.s(det, "author");
                    }
                    durSec = Json.lg(det, "lengthSeconds", -1);
                }
            }
        }

        /* 3) Piped — yedek kaynaklar + benzer videolar + eksik meta */
        Map<String, Object> piped = pipedStreams(v);
        if (piped != null) {
            if (title == null) {
                title = Json.s(piped, "title");
            }
            if (author == null) {
                author = Json.s(piped, "uploader");
            }
            if (durSec < 0) {
                durSec = Json.lg(piped, "duration", -1);
            }
            if (hlsUrl == null) {
                hlsUrl = Json.s(piped, "hls");
            }
            List<Object> vs = Json.asList(piped.get("videoStreams"));
            if (vs != null) {
                for (Object o : vs) {
                    Map<String, Object> f = Json.asMap(o);
                    if (f == null) {
                        continue;
                    }
                    String url = Json.s(f, "url");
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                    long itag = Json.lg(f, "itag", -2);
                    boolean videoOnly = Boolean.TRUE.equals(f.get("videoOnly"));
                    String qual = Json.s(f, "quality");
                    String mime = Json.s(f, "mimeType");
                    if (mime != null && mime.contains("mpegurl")) {
                        continue; /* HLS ayrı ele alınır */
                    }
                    if (itag >= 0 && !videoOnly) {
                        String it = String.valueOf(itag);
                        if (ups.containsKey(it)) {
                            continue;
                        }
                        ups.put(it, new Up(url, "web"));
                        Map<String, Object> src = new LinkedHashMap<String, Object>();
                        src.put("itag", it);
                        src.put("label", qual == null ? it : qual);
                        src.put("kind", "progressive");
                        src.put("mime", mime == null ? "video/mp4" : mime.split(";")[0]);
                        src.put("url", "/video?v=" + enc(v) + "&itag=" + it);
                        sources.add(src);
                    } else if (itag < 0 && !videoOnly && qual != null
                            && qual.contains("LBRY") && !qual.contains("HLS")) {
                        /* LBRY doğrudan mp4 — YouTube dışı yedek */
                        Map<String, Object> src = new LinkedHashMap<String, Object>();
                        src.put("itag", "lbry");
                        src.put("label", qual);
                        src.put("kind", "direct");
                        src.put("mime", "video/mp4");
                        src.put("url", "/direct?u=" + enc(url) + "&ua=web");
                        sources.add(src);
                    }
                }
            }
            List<Object> rel = Json.asList(piped.get("relatedStreams"));
            if (rel != null) {
                for (Object o : rel) {
                    Map<String, Object> r = Json.asMap(o);
                    if (r == null) {
                        continue;
                    }
                    if (!"stream".equals(Json.s(r, "type"))) {
                        continue;
                    }
                    String rid = vidFromUrl(Json.s(r, "url"));
                    if (rid == null) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("videoId", rid);
                    item.put("title", Json.s(r, "title"));
                    item.put("channel", Json.s(r, "uploaderName"));
                    item.put("duration", fmtDur(Json.lg(r, "duration", -1)));
                    related.add(item);
                    if (related.size() >= 24) {
                        break;
                    }
                }
            }
            if (!piped.isEmpty() && via.length() > 0) {
                via.append("+piped");
            } else if (!piped.isEmpty()) {
                via.append("piped");
            }
        }

        /* v4.5: HLS kaynakları EN ÖNE (1080p'ye kadar varyantlar + otomatik)
         * — ilerici 360p yedek olarak arkada kalır. Jeton URL'leri kısadır. */
        if (hlsUrl != null && hlsUrl.length() > 0) {
            addHlsSources(hlsUrl, "ios", sources, ups);
            if (via.length() > 0) {
                via.append("+ios");
            } else {
                via.append("ios");
            }
        }

        if (title == null && sources.isEmpty()) {
            /* rotasyon modunda v2 zinciri de sonuç vermediyse son çare NewPipe
             * (meta + benzer videolar için bile faydalı) */
            if (vrFirst) {
                try {
                    if (Npe.init()) {
                        Npe.Vid nv = Npe.streams(v);
                        if (nv != null && (!nv.sources.isEmpty()
                                || (nv.hls != null && nv.hls.length() > 0))) {
                            Diag.ev("cozumleme OK (vr zinciri bos — newpipe kurtardi) "
                                    + Diag.ms(t0));
                            return entryFromNpe(v, nv);
                        }
                    }
                } catch (Throwable t) {
                    Diag.ev("newpipe 2. deneme istisna: " + stack(t));
                }
            }
            Diag.ev("cozumleme BOS " + Diag.vid(v) + " " + Diag.ms(t0));
            if (why[0] == null) {
                why[0] = "tum kaynak zinciri (newpipe + innertube + Piped) yanit vermedi";
            }
            lastWhy = why[0];
            return null;
        }

        /* başarılı çözümlemede rotasyon bayrağını sıfırla */
        synchronized (preferVr) {
            preferVr.remove(v);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("videoId", v);
        out.put("title", title == null ? "" : title);
        out.put("author", author == null ? "" : author);
        out.put("duration", fmtDur(durSec));
        out.put("durationSec", durSec < 0 ? 0 : durSec);
        out.put("sources", sources);
        out.put("related", related);
        out.put("via", via.length() == 0 ? "-" : via.toString());
        out.put("thumb", "/thumb?v=" + enc(v));
        out.put("hls", hlsUrl == null ? "" : hlsUrl);
        for (Object o : sources) {
            Map<String, Object> s = Json.asMap(o);
            if (s != null && "progressive".equals(Json.s(s, "kind"))) {
                Up u = ups.get(String.valueOf(s.get("itag")));
                if (u != null) {
                    out.put("direct", u.url);
                    out.put("directKind", u.uaKind);
                }
                break;
            }
        }
        return new CacheEntry(Json.write(out), ups);
    }

    /** İstisnanın kısa küme izi (tanılama satırına sığacak kadar). */
    static String stack(Throwable t) {
        if (t == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        String msg = t.getMessage();
        if (msg != null) {
            sb.append(": ").append(msg.length() > 90
                    ? msg.substring(0, 90) + "…" : msg);
        }
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < 2 && st != null && i < st.length; i++) {
            sb.append(" @").append(st[i].getClassName()
                    .substring(st[i].getClassName().lastIndexOf('.') + 1))
                    .append('.').append(st[i].getMethodName());
        }
        return sb.toString();
    }

    private static String entrySummary(CacheEntry e) {
        if (e == null || e.json == null) {
            return "?";
        }
        try {
            Map<String, Object> m = Json.asMap(Json.parse(e.json));
            if (m == null) {
                return "?";
            }
            Object srcs = m.get("sources");
            int n = srcs instanceof List ? ((List<?>) srcs).size() : 0;
            boolean hls = m.get("hls") != null
                    && String.valueOf(m.get("hls")).length() > 0;
            return "via=" + m.get("via") + " kaynak=" + n + (hls ? "+HLS" : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** NewPipe Vid → /api/streams yanıtı (v2 biçimiyle uyumlu). */
    private CacheEntry entryFromNpe(String v, Npe.Vid nv) {
        Map<String, Up> ups = new LinkedHashMap<String, Up>(nv.ups);
        List<Object> sources = new ArrayList<Object>(nv.sources);
        String hlsUrl = nv.hls;

        /* Piped yedeği: NewPipe sadece metadata verdiyse kaynakları tamamla */
        if (sources.isEmpty() && hlsUrl == null) {
            Map<String, Object> piped = pipedStreams(v);
            if (piped != null) {
                List<Object> vs = Json.asList(piped.get("videoStreams"));
                if (vs != null) {
                    for (Object o : vs) {
                        Map<String, Object> f = Json.asMap(o);
                        if (f == null) {
                            continue;
                        }
                        String url = Json.s(f, "url");
                        if (url == null || url.length() == 0) {
                            continue;
                        }
                        long itag = Json.lg(f, "itag", -2);
                        boolean videoOnly = Boolean.TRUE.equals(f.get("videoOnly"));
                        String qual = Json.s(f, "quality");
                        String mime = Json.s(f, "mimeType");
                        if (mime != null && mime.contains("mpegurl")) {
                            continue;
                        }
                        if (itag >= 0 && !videoOnly) {
                            String it = String.valueOf(itag);
                            if (ups.containsKey(it)) {
                                continue;
                            }
                            ups.put(it, new Up(url, Net.uaKindForUrl(url)));
                            Map<String, Object> src = new LinkedHashMap<String, Object>();
                            src.put("itag", it);
                            src.put("label", qual == null ? it : qual);
                            src.put("kind", "progressive");
                            src.put("mime", mime == null ? "video/mp4" : mime.split(";")[0]);
                            src.put("url", "/video?v=" + enc(v) + "&itag=" + it);
                            sources.add(src);
                        }
                    }
                }
                if (hlsUrl == null) {
                    hlsUrl = Json.s(piped, "hls");
                }
            }
        }

        /* innertube ANDROID_VR yedeği — NPE kaynakları telefonda 403 verirse
         * oynatıcının elinde FARKLI imzacı bir istemciden gelen kaynak olsun
         * (yeni itag'lar; mevcut itag'a dokunulmaz) */
        if (!sources.isEmpty()) {
            try {
                Map<String, Object> vr = innertubePlayer(v, "ANDROID_VR",
                        "1.64.88", "28", Net.UA_VR, 9000);
                if (vr != null) {
                    List<Object> fmts = Json.asList(
                            Json.asMap(vr.get("streamingData")).get("formats"));
                    if (fmts != null) {
                        for (Object o : fmts) {
                            Map<String, Object> f = Json.asMap(o);
                            if (f == null) {
                                continue;
                            }
                            String url = Json.s(f, "url");
                            long itag = Json.lg(f, "itag", -1);
                            if (url == null || url.length() == 0 || itag <= 0) {
                                continue;
                            }
                            String it = String.valueOf(itag);
                            if (ups.containsKey(it)) {
                                continue;
                            }
                            ups.put(it, new Up(url, "vr"));
                            Map<String, Object> src = new LinkedHashMap<String, Object>();
                            String q = Json.s(f, "qualityLabel");
                            src.put("itag", it);
                            src.put("label", (q == null ? it : q) + " (yedek)");
                            src.put("kind", "progressive");
                            src.put("mime", "video/mp4");
                            src.put("url", "/video?v=" + enc(v) + "&itag=" + it);
                            sources.add(src);
                        }
                    }
                    Diag.ev("vr-yedek eklendi: " + sources.size() + " kaynak toplam");
                }
            } catch (Throwable ignored) {
            }
        }


        /* v4.6: NPE 360p'de kaldıysa WEB imzalı 720p'yi dene —
         * NPE'nin ANDROID/IOS zinciri de artık 22 vermiyor */
        webEnrich(v, sources, ups);

        /* v4.5: NPE yolunda da HLS varyantları en öne */
        addHlsSources(hlsUrl, "ios", sources, ups);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("videoId", v);
        out.put("title", nv.title == null ? "" : nv.title);
        out.put("author", nv.author == null ? "" : nv.author);
        out.put("duration", fmtDur(nv.duration));
        out.put("durationSec", nv.duration < 0 ? 0 : Long.valueOf(nv.duration));
        out.put("live", Boolean.valueOf(nv.live));
        out.put("sources", sources);
        out.put("related", nv.related);
        out.put("via", "newpipe");
        out.put("hls", hlsUrl == null ? "" : hlsUrl);
        out.put("thumb", "/thumb?v=" + enc(v));

        /* Dış oynatıcı (VLC/MX) kaçış kapısı: ilk ilerici kaynağın HAM URL'si.
         * URL'ler cihazın IP'sine kilitli ve ~6 saat geçerlidir — aynı
         * telefonda VLC gibi bir uygulama bu URL'yi doğrudan çalabilir. */
        for (Object o : sources) {
            Map<String, Object> s = Json.asMap(o);
            if (s != null && "progressive".equals(Json.s(s, "kind"))) {
                Up u = ups.get(String.valueOf(s.get("itag")));
                if (u != null) {
                    out.put("direct", u.url);
                    out.put("directKind", u.uaKind);
                }
                break;
            }
        }
        return new CacheEntry(Json.write(out), ups);
    }

    /* ================= innertube ================= */

    private Map<String, Object> innertubePlayer(String v, String cname, String cver,
            String cnum, String ua, int timeoutMs) {
        try {
            Map<String, Object> client = new LinkedHashMap<String, Object>();
            client.put("clientName", cname);
            client.put("clientVersion", cver);
            client.put("hl", "tr");
            client.put("gl", "TR");
            client.put("userAgent", ua);
            if ("ANDROID_VR".equals(cname)) {
                client.put("androidSdkVersion", 32);
                client.put("deviceModel", "Quest 2");
                client.put("osName", "Android");
                client.put("osVersion", "12");
            } else if ("IOS".equals(cname)) {
                client.put("deviceMake", "Apple");
                client.put("deviceModel", "iPhone16,2");
                client.put("osName", "iPhone");
                client.put("osVersion", "18.7.2.22H124");
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("context", newMap("client", client));
            body.put("videoId", v);
            body.put("contentCheckOk", Boolean.TRUE);
            body.put("racyCheckOk", Boolean.TRUE);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", "application/json");
            headers.put("X-YouTube-Client-Name", cnum);
            headers.put("X-YouTube-Client-Version", cver);
            headers.put("User-Agent", ua);
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Referer", "https://www.youtube.com/");

            Net.Resp r = Net.fetch(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    "POST", headers, Net.utf8(Json.write(body)), timeoutMs, timeoutMs,
                    6 * 1024 * 1024);
            if (r.code != 200) {
                return null;
            }
            Map<String, Object> d = Json.asMap(Json.parse(r.bodyText()));
            if (d == null) {
                return null;
            }
            Map<String, Object> ps = Json.asMap(d.get("playabilityStatus"));
            String st = ps == null ? null : Json.s(ps, "status");
            if (st != null && !st.equals("OK") && !st.equals("LIVE_STREAM_OFFLINE")) {
                return null;
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object> innertubeSearch(String q, int limit) {
        Map<String, Object> d = innertubePost("search", searchBrowseBody(
                newMap("query", q)), 9000);
        if (d == null) {
            return null;
        }
        return collectVideoRenderers(d, limit);
    }

    private List<Object> innertubeTrending(int limit) {
        /* v4.4: YouTube FEtrending (browse) ucunu Temmuz 2025'te KALDIRDI →
         * eski istisnasız 400 veriyordu. Yeni istek NewPipe'in CANLI
         * kiosk'undan birebir: YouTube Live kanalının 'live' sekmesi
         * (browseId UC4R8DWoMoI7CAwX8_LjQHig + live-tab params). Bu,
         * şu an en çok izlenen CANLI yayınları verir — trend yedeği olarak
         * Piped'ten ÖNCE denenir. */
        Map<String, Object> body = searchBrowseBody(null);
        body.put("browseId", "UC4R8DWoMoI7CAwX8_LjQHig");
        body.put("params", "EgdsaXZldGFikgEDCKEK");
        Map<String, Object> d = innertubePost("browse", body, 9000);
        if (d == null) {
            return null;
        }
        List<Object> items = collectVideoRenderers(d, limit);
        Diag.ev("innertube live-trend: " + (items == null ? 0 : items.size()) + " video");
        return items;
    }

    private Map<String, Object> searchBrowseBody(Map<String, Object> extra) {
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        client.put("clientName", "WEB");
        client.put("clientVersion", WEB_VER);
        client.put("hl", "tr");
        client.put("gl", "TR");
        client.put("userAgent", Net.UA_WEB);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("context", newMap("client", client));
        if (extra != null) {
            body.putAll(extra);
        }
        return body;
    }

    private Map<String, Object> innertubePost(String endpoint,
            Map<String, Object> body, int timeoutMs) {
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", Net.UA_WEB);
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Referer", "https://www.youtube.com/");
            /* v4.4: innertube uçları X-YouTube-Client başlıklarını ve
             * SOCS consent çerezini ister (NewPipe aynı düzeni kullanır) */
            headers.put("X-YouTube-Client-Name", "1");
            headers.put("X-YouTube-Client-Version", WEB_VER);
            headers.put("Cookie", "SOCS=CAE=");
            Net.Resp r = Net.fetch(
                    "https://www.youtube.com/youtubei/v1/" + endpoint
                            + "?prettyPrint=false",
                    "POST", headers, Net.utf8(Json.write(body)), timeoutMs, timeoutMs,
                    6 * 1024 * 1024);
            if (r.code != 200) {
                return null;
            }
            return Json.asMap(Json.parse(r.bodyText()));
        } catch (Exception e) {
            return null;
        }
    }

    /** videoRenderer düğümlerini derinlemesine toplar (arama + trend). */
    private static List<Object> collectVideoRenderers(Map<String, Object> d, int limit) {
        List<Object> out = new ArrayList<Object>();
        walk(d, "videoRenderer", out, 0);
        List<Object> items = new ArrayList<Object>();
        for (Object o : out) {
            Map<String, Object> vr = Json.asMap(o);
            if (vr == null) {
                continue;
            }
            String vid = Json.s(vr, "videoId");
            String title = titleOf(Json.asMap(vr.get("title")));
            if (vid == null || vid.length() != 11 || title == null
                    || title.length() == 0) {
                continue;
            }
            String channel = null;
            Map<String, Object> owner = Json.asMap(vr.get("ownerText"));
            List<Object> runs = owner == null ? null : Json.asList(owner.get("runs"));
            if (runs != null && !runs.isEmpty()) {
                channel = Json.s(Json.asMap(runs.get(0)), "text");
            }
            Map<String, Object> lt = Json.asMap(vr.get("lengthText"));
            String dur = lt == null ? null : Json.s(lt, "simpleText");
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("videoId", vid);
            item.put("title", title);
            item.put("channel", channel == null ? "" : channel);
            item.put("duration", dur == null ? "" : dur);
            items.add(item);
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private static void walk(Object node, String key, List<Object> out, int depth) {
        if (depth > 16 || out.size() > 120) {
            return;
        }
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object hit = m.get(key);
            if (hit instanceof Map && ((Map<?, ?>) hit).get("videoId") != null) {
                out.add(hit);
                if (out.size() > 120) {
                    return;
                }
            }
            for (Object v : m.values()) {
                walk(v, key, out, depth + 1);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                walk(v, key, out, depth + 1);
            }
        }
    }

    private static String titleOf(Map<String, Object> t) {
        if (t == null) {
            return null;
        }
        String s = Json.s(t, "simpleText");
        if (s != null && s.length() > 0) {
            return s;
        }
        List<Object> runs = Json.asList(t.get("runs"));
        if (runs != null) {
            StringBuilder sb = new StringBuilder();
            for (Object r : runs) {
                String tx = Json.s(Json.asMap(r), "text");
                if (tx != null) {
                    sb.append(tx);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    /* ================= Piped ================= */

    private Map<String, Object> pipedStreams(String v) {
        for (String base : PIPED_BASES) {
            Net.Resp r = Net.fetch(base + "/streams/" + v, "GET", null, null,
                    6000, 9000, 8 * 1024 * 1024);
            if (r.code == 200) {
                try {
                    Map<String, Object> d = Json.asMap(Json.parse(r.bodyText()));
                    if (d != null && d.containsKey("title")) {
                        return d;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** Piped liste uç noktası (arama/trend) → normalize öğe listesi. */
    private List<Object> pipedList(String pathAndQuery, String listKey, int limit) {
        for (String base : PIPED_BASES) {
            Net.Resp r = Net.fetch(base + pathAndQuery, "GET", null, null,
                    6000, 9000, 4 * 1024 * 1024);
            if (r.code != 200) {
                continue;
            }
            try {
                Object parsed = Json.parse(r.bodyText());
                List<Object> raw = listKey == null
                        ? Json.asList(parsed)
                        : Json.asList(Json.asMap(parsed).get(listKey));
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                List<Object> items = new ArrayList<Object>();
                for (Object o : raw) {
                    Map<String, Object> it = Json.asMap(o);
                    if (it == null) {
                        continue;
                    }
                    if (listKey != null && !"stream".equals(Json.s(it, "type"))) {
                        continue;
                    }
                    String vid = vidFromUrl(Json.s(it, "url"));
                    if (vid == null) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("videoId", vid);
                    item.put("title", Json.s(it, "title"));
                    item.put("channel", Json.s(it, "uploaderName"));
                    item.put("duration", fmtDur(Json.lg(it, "duration", -1)));
                    items.add(item);
                    if (items.size() >= limit) {
                        break;
                    }
                }
                if (!items.isEmpty()) {
                    return items;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /* ================= yardımcılar ================= */

    private static String vidFromUrl(String u) {
        if (u == null) {
            return null;
        }
        Matcher m = VID.matcher(u);
        if (m.find()) {
            return m.group(1);
        }
        if (u.matches("https?://[^/]+/[A-Za-z0-9_-]{11}")) {
            return u.substring(u.lastIndexOf('/') + 1);
        }
        return null;
    }

    /** Npe katmanı için herkese açık sarmalayıcı. */
    public static String videoIdOf(String u) {
        return vidFromUrl(u);
    }

    /** Görüntülenme sayısını okunur biçime çevirir (12.4B gibi). */
    public static String fmtViews(long views) {
        if (views < 0) {
            return "";
        }
        if (views >= 1_000_000_000L) {
            return String.format(java.util.Locale.US, "%.1f Mr", views / 1e9);
        }
        if (views >= 1_000_000L) {
            return String.format(java.util.Locale.US, "%.1f Mn", views / 1e6);
        }
        if (views >= 1_000L) {
            return String.format(java.util.Locale.US, "%.1f B", views / 1e3);
        }
        return String.valueOf(views);
    }

    private static String itemsJson(List<Object> items, String via) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("count", Integer.valueOf(items.size()));
        out.put("via", via);
        return Json.write(out);
    }

    private static String errJson(int code, String msg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error", Boolean.TRUE);
        out.put("code", Integer.valueOf(code));
        out.put("message", msg == null ? "bilinmeyen hata" : msg);
        return Json.write(out);
    }

    public static String fmtDur(long sec) {
        if (sec < 0) {
            return "";
        }
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return h + ":" + pad2(m) + ":" + pad2(s);
        }
        return m + ":" + pad2(s);
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static Map<String, Object> newMap(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20").replace("*", "%2A");
        } catch (Exception e) {
            return s;
        }
    }
}
