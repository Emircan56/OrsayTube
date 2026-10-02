package com.localtube.mobil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YEREL HTTP SUNUCU — diekaiju/localtube'ın LocalHttpServer'ı gibi (GPLv3):
 * telefonda gerçek bir ServerSocket dinler; uygulama içi WebView, telefon
 * tarayıcısı (http://localhost:8080) ve aynı ağdaki cihazlar aynı arayüze
 * bağlanır. Tüm YouTube istekleri sunucu tarafında (cihazın IP'sinden)
 * çözümlenir, akışlar /video vekili üzerinden Range destekli aktarılır.
 *
 * Saf Java — android.* bağımlılığı yoktur (masaüstü test edilebilir).
 */
public final class LocalServer {

    public interface AssetSource {
        byte[] load(String relPath);
    }

    public interface Logger {
        void log(String msg);
    }

    public static final String VERSION = "4.4";
    static final int PORT_FIRST = 8080;
    static final int PORT_LAST = 8090;

    final AssetSource assets;
    final File cacheDir;
    final Resolver resolver;
    final Logger log;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket ss;
    private volatile boolean running;
    private int port = -1;
    private long startedAt;
    private volatile String lanIp = "";
    private int reqCounter;

    public LocalServer(AssetSource assets, File cacheDir, Resolver resolver, Logger log) {
        this.assets = assets;
        this.cacheDir = cacheDir;
        this.resolver = resolver;
        this.log = log;
    }

    /* ---------------- yaşam döngüsü ---------------- */

    public synchronized void start() {
        if (running) {
            return;
        }
        for (int p = PORT_FIRST; p <= PORT_LAST; p++) {
            try {
                ss = new ServerSocket(p, 64, InetAddress.getByName("0.0.0.0"));
                port = p;
                break;
            } catch (IOException e) {
                ss = null;
            }
        }
        if (ss == null) {
            if (log != null) {
                log.log("PORT HATASI: 8080-8090 arası baglanamadi");
            }
            return;
        }
        try {
            ss.setSoTimeout(0);
        } catch (IOException ignored) {
        }
        running = true;
        startedAt = System.currentTimeMillis();
        Thread t = new Thread(new AcceptLoop(this));
        t.setName("orsaytube-server");
        t.setDaemon(true);
        t.start();
        if (log != null) {
            log.log("SUNUCU ACILDI: http://localhost:" + port);
        }
    }

    public void stop() {
        running = false;
        try {
            if (ss != null) {
                ss.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (discoverySock != null) {
                discoverySock.close();
            }
        } catch (Throwable ignored) {
        }
        pool.shutdownNow();
        if (log != null) {
            log.log("SUNUCU KAPANDI");
        }
    }

    public boolean isRunning() {
        return running && ss != null && !ss.isClosed();
    }

    public int getPort() {
        return port;
    }

    public long uptimeMs() {
        return running ? System.currentTimeMillis() - startedAt : 0;
    }

    public void setLanIp(String ip) {
        this.lanIp = ip == null ? "" : ip;
    }

    /* ---------------- UDP keşif (TV APK telefonu bulur) ----------------
     * v4.2: TV'deki OrsayTube TV uygulaması ağa "LOCALTUBE-DISCOVER"
     * yayınlar; bu yanıtlayıcı "LOCALTUBE <port>" ile cevap verir.
     * Android'de yayın paketlerini alabilmek için MulticastLock gerekir
     * (ServerService açar — bak: CHANGE_WIFI_MULTICAST_STATE). */

    public static final int DISCOVERY_PORT = 8181;
    private DatagramSocket discoverySock;

    public void startDiscoveryResponder() {
        try {
            discoverySock = new DatagramSocket(DISCOVERY_PORT);
        } catch (IOException e) {
            if (log != null) {
                log.log("UDP keşif kapalı (port " + DISCOVERY_PORT + " dolu)");
            }
            return;
        }
        Thread t = new Thread(new DiscoveryLoop(this), "orsaytube-discovery");
        t.setDaemon(true);
        t.start();
        if (log != null) {
            log.log("UDP keşif hazır: :" + DISCOVERY_PORT);
        }
    }

    void replyDiscovery(InetAddress addr, int theirPort) {
        try {
            byte[] msg = Net.utf8("LOCALTUBE " + port);
            discoverySock.send(new DatagramPacket(msg, msg.length, addr, theirPort));
        } catch (IOException ignored) {
        }
    }

    private static final class DiscoveryLoop implements Runnable {
        private final LocalServer s;

        DiscoveryLoop(LocalServer s) {
            this.s = s;
        }

        public void run() {
            byte[] buf = new byte[64];
            while (s.running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.discoverySock.receive(p);
                    String req = new String(buf, 0, p.getLength(), "UTF-8");
                    if (req.startsWith("LOCALTUBE-DISCOVER")) {
                        s.replyDiscovery(p.getAddress(), p.getPort());
                    }
                } catch (IOException e) {
                    if (s.running) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    }

    /* ---------------- kabul dongusu ---------------- */

    private static final class AcceptLoop implements Runnable {
        private final LocalServer s;

        AcceptLoop(LocalServer s) {
            this.s = s;
        }

        public void run() {
            while (s.running) {
                try {
                    Socket sock = s.ss.accept();
                    s.pool.execute(new Conn(s, sock));
                } catch (IOException e) {
                    if (s.running) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    }

    /* ---------------- baglanti isleyici ---------------- */

    private static final class Conn implements Runnable {
        private final LocalServer s;
        private final Socket sock;

        Conn(LocalServer s, Socket sock) {
            this.s = s;
            this.sock = sock;
        }

        public void run() {
            try {
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(30000);
                byte[] head = readHead(sock.getInputStream());
                if (head == null) {
                    return;
                }
                String[] lines = new String(head, "ISO-8859-1").split("\r\n");
                if (lines.length < 1) {
                    return;
                }
                String[] req = lines[0].split(" ");
                if (req.length < 2) {
                    return;
                }
                String method = req[0].toUpperCase();
                String target = req[1];
                Map<String, String> headers = new HashMap<String, String>();
                for (int i = 1; i < lines.length; i++) {
                    int c = lines[i].indexOf(':');
                    if (c > 0) {
                        headers.put(lines[i].substring(0, c).trim().toLowerCase(),
                                lines[i].substring(c + 1).trim());
                    }
                }
                s.reqCounter++;
                if (s.log != null) {
                    StringBuilder rl = new StringBuilder("ISTEK " + method + " " + target);
                    String rg = headers.get("range");
                    if (rg != null) {
                        rl.append(" [Range: ").append(rg).append(']');
                    }
                    s.log.log(rl.toString());
                }
                s.route(sock, method, target, headers);
            } catch (Exception e) {
                /* istemci koptu vb. — sessiz */
            } finally {
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        /* durum: 0=başlangıç, 1=\r görüldü, 2=\r\n görüldü, 3=\r\n\r görüldü */
        int state = 0;
        int total = 0;
        while (total < 32768) {
            int b = in.read();
            if (b < 0) {
                return bos.size() > 0 ? bos.toByteArray() : null;
            }
            total++;
            bos.write(b);
            if (b == '\r') {
                state = (state == 2) ? 3 : 1;
            } else if (b == '\n') {
                if (state == 3) {
                    return bos.toByteArray(); /* \r\n\r\n — başlık bitti */
                }
                state = (state == 1) ? 2 : 0;
            } else {
                state = 0;
            }
        }
        return bos.toByteArray();
    }

    /* ---------------- yonlendirme ---------------- */

    void route(Socket sock, String method, String target,
            Map<String, String> reqHeaders) {
        String path = target;
        Map<String, String> q = new HashMap<String, String>();
        int qi = target.indexOf('?');
        if (qi >= 0) {
            path = target.substring(0, qi);
            parseQuery(target.substring(qi + 1), q);
        }
        if (path.contains("..")) {
            sendJson(sock, 403, "{\"error\":true,\"message\":\"yasak yol\"}");
            return;
        }
        boolean headOnly = "HEAD".equals(method);
        if (!"GET".equals(method) && !headOnly) {
            sendJson(sock, 405, "{\"error\":true,\"message\":\"yalnizca GET/HEAD\"}");
            return;
        }

        boolean tvUa = isTvUserAgent(reqHeaders.get("user-agent"));

        if (path.equals("/") || path.equals("/index.html")) {
            /* v4.2: TV tarayıcısı ana sayfayı açarsa 10-foot TV arayüzü ver */
            sendAsset(sock, tvUa ? "tv.html" : "index.html",
                    "text/html; charset=utf-8", headOnly);
        } else if (path.equals("/tv") || path.equals("/tv.html")) {
            sendAsset(sock, "tv.html", "text/html; charset=utf-8", headOnly);
        } else if (path.equals("/watch")) {
            sendAsset(sock, tvUa ? "tv.html" : "index.html",
                    "text/html; charset=utf-8", headOnly);
        } else if (path.equals("/localtube-tv.apk") || path.equals("/tv-app")) {
            /* v4.2: TV APK — telefona değil doğrudan TV'ye kurulmak için
             * TV tarayıcısından indirilebilir (aynı ağ) */
            sendApkAsset(sock, "localtube-tv.apk", headOnly);
        } else if (path.equals("/widgetlist.xml")) {
            /* v1.3: ÇALIŞAN Orsay biçimi (Jellyfin-Orsay kurucusuyla birebir
             * aynı): <rsp stat="ok"><list> kökü + <download> öğesi.
             * v4.4'ün <widgetlist> kökü + download'suz biçimi TV tarafından
             * çözülemiyordu (tvappssecurity/ERR_development 002). */
            String xml = widgetListXml(reqHeaders.get("host"));
            sendRaw(sock, 200, xml.getBytes(), "text/xml; charset=utf-8", headOnly);
        } else if (path.equals("/localtube-tv-widget.zip")
                || path.equals("/LOCALTUBETV.zip")
                || path.startsWith("/widget")) {
            /* Orsay widget paketi (Smart Hub develop senkronlaması ile kurulur).
             * v4.4: TV zip'i {widgetid}.zip adıyla ister → LOCALTUBETV.zip uçları */
            sendAsset(sock, "widget/localtube-tv-widget.zip",
                    "application/zip", headOnly);
        } else if (path.equals("/favicon.ico")) {
            sendAsset(sock, "img/icon-512.png", "image/png", headOnly);
        } else if (path.equals("/manifest.webmanifest")) {
            sendAsset(sock, "manifest.webmanifest", "application/manifest+json",
                    headOnly);
        } else if (path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/img/")) {
            sendAsset(sock, path.substring(1), mimeOf(path), headOnly);
        } else if (path.equals("/api/ping")) {
            sendJson(sock, 200, "{\"ok\":true,\"app\":\"orsaytube-mobil\","
                    + "\"version\":\"" + VERSION + "\",\"port\":" + port + "}");
        } else if (path.equals("/api/info")) {
            boolean tvApk = hasAsset("localtube-tv.apk");
            sendJson(sock, 200, "{\"ok\":true,\"version\":\"" + VERSION + "\","
                    + "\"port\":" + port + ",\"running\":" + isRunning() + ","
                    + "\"lanIp\":\"" + esc(lanIp) + "\","
                    + "\"uptime\":" + uptimeMs() + ","
                    + "\"host\":\"http://localhost:" + port + "\","
                    + "\"tvUrl\":\"http://localhost:" + port + "/tv\","
                    + "\"tvAppUrl\":\"http://localhost:" + port + "/localtube-tv.apk\","
                    + "\"tvApp\":" + tvApk + "}");
        } else if (path.equals("/api/home")) {
            long t0 = System.currentTimeMillis();
            String json = resolver.trending();
            /* v4.3: trend zinciri nereden geldi — tanılama günlüğünde görünür */
            Diag.ev("/api/home -> " + Diag.ms(t0) + " " + streamSummary(json));
            sendJson(sock, 200, json);
        } else if (path.equals("/api/search")) {
            String qq = q.get("q");
            if (qq == null || qq.trim().length() == 0) {
                sendJson(sock, 400, "{\"error\":true,\"message\":\"q eksik\"}");
            } else {
                sendJson(sock, 200, resolver.search(qq));
            }
        } else if (path.equals("/api/streams")) {
            long t0 = System.currentTimeMillis();
            String json = resolver.streams(q.get("v"));
            Diag.ev("/api/streams " + Diag.vid(q.get("v")) + " -> "
                    + Diag.ms(t0) + " " + streamSummary(json));
            sendJson(sock, 200, json);
        } else if (path.equals("/api/diag")) {
            handleDiag(sock);
        } else if (path.equals("/thumb")) {
            handleThumb(sock, q, headOnly);
        } else if (path.equals("/video")) {
            handleVideo(sock, method, q, reqHeaders);
        } else if (path.equals("/direct") || path.equals("/hls")) {
            handleDirect(sock, method, path.equals("/hls"), q, reqHeaders);
        } else {
            sendJson(sock, 404, "{\"error\":true,\"message\":\"bulunamadi: "
                    + esc(path) + "\"}");
        }
    }

    /* ---------------- statik/durum yanıtları ---------------- */

    /**
     * v4.2: TV tarayıcı kullanıcı-ajanı tespiti. TV'ler kumanda ile
     * gezinir — onlara 10-foot arayüz (tv.html) sunulur.
     */
    static boolean isTvUserAgent(String ua) {
        if (ua == null) {
            return false;
        }
        String[] marks = {
            "SmartTV", "SMART-TV", "Smart TV", "Tizen", "Web0S", "WebOS",
            "NetCast", "HbbTV", "BRAVIA", "Viera", "NetTV", "Maple",
            "AppleTV", "GoogleTV", "Android TV", "AFT", "SHIELD",
            "CrKey", "ChromeCast", "; TV)", " TV;"
        };
        for (String m : marks) {
            if (ua.contains(m)) {
                return true;
            }
        }
        return false;
    }

    void sendApkAsset(Socket sock, String rel, boolean headOnly) {
        byte[] data = assets == null ? null : assets.load(rel);
        if (data == null) {
            sendJson(sock, 404, "{\"error\":true,\"message\":\"TV APK paketlenmemiş\"}");
            return;
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/vnd.android.package-archive");
        h.put("Content-Disposition", "attachment; filename=\"localtube-tv.apk\"");
        h.put("Cache-Control", "no-store");
        sendBytes(sock, 200, h, headOnly ? null : data);
    }

    boolean hasAsset(String rel) {
        try {
            return assets != null && assets.load(rel) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    void sendAsset(Socket sock, String rel, String mime, boolean headOnly) {
        byte[] data = assets == null ? null : assets.load(rel);
        if (data == null) {
            sendJson(sock, 404, "{\"error\":true,\"message\":\"varlik yok: "
                    + esc(rel) + "\"}");
            return;
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", mime);
        h.put("Cache-Control", "max-age=3600");
        sendBytes(sock, 200, h, headOnly ? null : data);
    }

    void sendJson(Socket sock, int code, String json) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        sendBytes(sock, code, h, Net.utf8(json));
    }

    /** v4.3: ham yanıt (widgetlist.xml gibi) — TV tarafının beklediği MIME ile. */
    void sendRaw(Socket sock, int code, byte[] data, String mime, boolean headOnly) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", mime);
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        sendBytes(sock, code, h, headOnly ? null : data);
    }

    /**
     * v1.3: Samsung Orsay geliştirme senkronunun beklediği widgetlist.xml —
     * ÇALIŞAN örneklerden (Jellyfin-Orsay-Installer) birebir alınan biçim:
     * rsp/list kökü + compression size=ZIP BAYT + download=TEK MIME +
     * download öğesi. Boyut gömülü zip'in TAM bayt boyutudur.
     */
    String widgetListXml(String hostHeader) {
        long size = 0;
        try {
            byte[] z = assets == null ? null : assets.load("widget/localtube-tv-widget.zip");
            if (z != null) {
                size = z.length;
            }
        } catch (Throwable ignored) {
        }
        String host = (hostHeader == null || hostHeader.length() == 0)
                ? "TELEFON_IP:8080" : hostHeader;
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<rsp stat=\"ok\">\n<list>\n<widget id=\"LOCALTUBETV\">\n"
                + "    <title>OrsayTube TV</title>\n"
                + "    <compression size=\"" + size + "\" type=\"zip\" />\n"
                + "    <description>OrsayTube TV - yerel ag YouTube oynaticisi</description>\n"
                + "    <download>http://" + host + "/LOCALTUBETV.zip</download>\n"
                + "</widget>\n</list>\n</rsp>\n";
    }

    void sendBytes(Socket sock, int code, Map<String, String> headers, byte[] body) {
        try {
            OutputStream out = sock.getOutputStream();
            StringBuilder hb = new StringBuilder();
            hb.append("HTTP/1.1 ").append(code).append(' ')
                    .append(statusText(code)).append("\r\n");
            if (body != null) {
                headers.put("Content-Length", String.valueOf(body.length));
            }
            headers.put("Connection", "close");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                hb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            hb.append("\r\n");
            out.write(hb.toString().getBytes("ISO-8859-1"));
            if (body != null && body.length > 0) {
                out.write(body);
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /* ---------------- kucuk resim vekili ---------------- */

    void handleThumb(Socket sock, Map<String, String> q, boolean headOnly) {
        String v = q.get("v");
        String f = q.get("f");
        if (f == null || f.length() == 0 || !f.matches("[a-z]+")) {
            f = "hqdefault";
        }
        if (v == null || !v.matches("[A-Za-z0-9_-]{11}")) {
            sendJson(sock, 400, "{\"error\":true,\"message\":\"gecersiz video id\"}");
            return;
        }
        try {
            File dir = new File(cacheDir, "thumbs");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, v + "_" + f + ".jpg");
            if (!file.exists() || System.currentTimeMillis() - file.lastModified()
                    > 24 * 3600 * 1000L) {
                byte[] data = null;
                String[] cands = {f, "mqdefault", "default"};
                for (String c : cands) {
                    byte[] d = fetchSmall("https://i.ytimg.com/vi/" + v + "/" + c
                            + ".jpg");
                    if (d != null && d.length > 100) {
                        data = d;
                        break;
                    }
                }
                if (data == null) {
                    sendJson(sock, 404,
                            "{\"error\":true,\"message\":\"resim yok\"}");
                    return;
                }
                try {
                    FileOutputStream fo = new FileOutputStream(file);
                    try {
                        fo.write(data);
                    } finally {
                        fo.close();
                    }
                } catch (IOException ignored) {
                }
                if (reqCounter % 40 == 0) {
                    sweepThumbs(dir);
                }
            }
            byte[] data = readFile(file);
            if (data == null) {
                sendJson(sock, 404, "{\"error\":true,\"message\":\"resim yok\"}");
                return;
            }
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Content-Type", "image/jpeg");
            h.put("Cache-Control", "public, max-age=86400");
            sendBytes(sock, 200, h, headOnly ? null : data);
        } catch (Exception e) {
            sendJson(sock, 500, "{\"error\":true,\"message\":\"resim hatasi\"}");
        }
    }

    private static void sweepThumbs(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < 500) {
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return Long.valueOf(a.lastModified()).compareTo(
                        Long.valueOf(b.lastModified()));
            }
        });
        int remove = files.length - 400;
        for (int i = 0; i < remove; i++) {
            files[i].delete();
        }
    }

    /* ---------------- video akıs vekili ---------------- */

    void handleVideo(Socket sock, String method, Map<String, String> q,
            Map<String, String> reqHeaders) {
        String v = q.get("v");
        String itag = q.get("itag");
        if (v == null || itag == null) {
            sendJson(sock, 400, "{\"error\":true,\"message\":\"v ve itag gerekli\"}");
            return;
        }
        Resolver.Up up = resolver.upstream(v, itag);
        Diag.ev("/video " + Diag.vid(v) + " itag=" + itag + " upstream="
                + (up == null ? "YOK" : upstreamInfo(up)));
        if (up == null) {
            sendJson(sock, 404, "{\"error\":true,\"message\":"
                    + "\"kaynak bulunamadi — tekrar aramayi deneyin\"}");
            return;
        }
        /* 403/404 gelirse önbelleği yenileyip bir kez daha dene
         * (ilk deneme 'sessiz': hata yanıtı istemciye yazılmaz, böylece
         * yeniden çözümleme sonrası tek temiz yanıt döner) */
        if (!streamUpstream(sock, method, up, reqHeaders, false)
                && !"HEAD".equals(method)) {
            Diag.ev("/video ilk deneme basarisiz — yeniden cozumleniyor");
            long t0 = System.currentTimeMillis();
            resolver.invalidate(v);
            Resolver.Up up2 = resolver.upstream(v, itag);
            Diag.ev("/video yeniden cozumleme " + Diag.ms(t0) + " upstream2="
                    + (up2 == null ? "YOK" : upstreamInfo(up2)));
            if (up2 != null && !up2.url.equals(up.url)) {
                streamUpstream(sock, method, up2, reqHeaders, true);
            } else if (up2 == null) {
                sendJson(sock, 404, "{\"error\":true,\"message\":"
                        + "\"kaynak bulunamadi — tekrar aramayi deneyin\"}");
            } else {
                sendJson(sock, 502, "{\"error\":true,\"message\":"
                        + "\"video kaynagi yanit vermedi, tekrar deneyin\"}");
            }
        }
    }

    void handleDirect(Socket sock, String method, boolean hls,
            Map<String, String> q, Map<String, String> reqHeaders) {
        String u = q.get("u");
        String ua = q.get("ua");
        if (u == null || u.length() == 0) {
            sendJson(sock, 400, "{\"error\":true,\"message\":\"u gerekli\"}");
            return;
        }
        /* v4.5: /hls?u= artık kısa jeton da olabilir (uzun adresleri
         * eski TV tarayıcılarına taşımamak için) */
        String[] tr = Resolver.hlsRefGet(u);
        if (tr != null) {
            u = tr[0];
            ua = tr[1];
        }
        if (!allowedHost(u)) {
            sendJson(sock, 403, "{\"error\":true,\"message\":\"izin verilmeyen adres\"}");
            return;
        }
        if (hls) {
            handleHls(sock, method, u, ua, reqHeaders);
            return;
        }
        Resolver.Up up = new Resolver.Up(u, ua == null ? Net.uaKindForUrl(u) : ua);
        streamUpstream(sock, method, up, reqHeaders, true);
    }

    /**
     * HLS vekili (diekaiju/localtube yöntemi): manifest (m3u8) ise içindeki tüm
     * parça adreslerini /hls?u=... biçiminde yeniden yazar — böylece
     * oynatıcı tüm istekleri localhost üzerinden yapar. Parça (ts/mp4)
     * ise akışı doğrudan geçiri.
     */
    void handleHls(Socket sock, String method, String u, String ua,
            Map<String, String> reqHeaders) {
        HttpURLConnection conn = null;
        try {
            String kind = ua == null ? Net.uaKindForUrl(u) : ua;
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", Net.uaFor(kind));
            conn.setRequestProperty("Accept", "*/*");
            String range = reqHeaders == null ? null : reqHeaders.get("range");
            if (range != null && range.length() > 0) {
                conn.setRequestProperty("Range", range);
            }
            int code = conn.getResponseCode();
            String ct = conn.getHeaderField("Content-Type");
            boolean manifest = false;
            if (ct != null) {
                String c = ct.toLowerCase();
                manifest = c.contains("mpegurl") || c.contains("m3u8");
            } else {
                String lu = u.toLowerCase();
                manifest = lu.contains(".m3u8") || lu.contains("/api/manifest/");
            }
            if (code >= 400) {
                sendJson(sock, 502, "{\"error\":true,\"code\":" + code
                        + ",\"message\":\"hls kaynagi yanit vermedi\"}");
                return;
            }
            if (manifest) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 4 * 1024 * 1024) {
                        break;
                    }
                }
                in.close();
                String body = new String(bos.toByteArray(), "UTF-8");
                URL base = new URL(u);
                StringBuilder sb = new StringBuilder();
                for (String line : body.split("\n", -1)) {
                    String t = line.trim();
                    if (t.length() == 0) {
                        sb.append(line).append('\n');
                        continue;
                    }
                    if (t.startsWith("#")) {
                        /* EXT-X-MEDIA / EXT-X-KEY gibi URI="..." özniteliklerini
                         * de yeniden yaz — ses/altyazı parçaları da localhost'tan
                         * geçsin (CORS/karışık içerik olmadan) */
                        if (t.contains("URI=\"")) {
                            line = rewriteUriAttribute(line, base, kind);
                        }
                        sb.append(line).append('\n');
                        continue;
                    }
                    try {
                        URL abs = new URL(base, t);
                        String ref = Resolver.hlsRef(abs.toString(), kind);
                        sb.append(ref == null ? line : ref).append('\n');
                    } catch (Exception e) {
                        sb.append(line).append('\n');
                    }
                }
                Map<String, String> h = new LinkedHashMap<String, String>();
                h.put("Content-Type", "application/vnd.apple.mpegurl");
                h.put("Cache-Control", "no-store");
                h.put("Access-Control-Allow-Origin", "*");
                sendBytes(sock, 200, h, Net.utf8(sb.toString()));
                return;
            }
            /* parça: akışı geçir */
            OutputStream out = sock.getOutputStream();
            StringBuilder hb = new StringBuilder();
            hb.append("HTTP/1.1 ").append(code).append(' ')
                    .append(statusText(code)).append("\r\n");
            hb.append("Content-Type: ").append(ct == null ? "video/mp2t" : ct)
                    .append("\r\n");
            String cl = conn.getHeaderField("Content-Length");
            if (cl != null && cl.length() > 0) {
                hb.append("Content-Length: ").append(cl).append("\r\n");
            }
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.length() > 0) {
                hb.append("Content-Range: ").append(cr).append("\r\n");
            }
            hb.append("Accept-Ranges: bytes\r\n");
            /* v1.3: widget (file://) ortamında hls.js XHR'leri için CORS —
             * file:// kaynağı "null" gönderir, joker izin verir */
            hb.append("Access-Control-Allow-Origin: *\r\n");
            hb.append("Connection: close\r\n\r\n");
            out.write(hb.toString().getBytes("ISO-8859-1"));
            out.flush();
            if ("HEAD".equals(method)) {
                return;
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                /* istemci kesti — normal */
            }
        } catch (Exception e) {
            if (log != null) {
                log.log("HLS err: " + e);
            }
            sendJson(sock, 502, "{\"error\":true,\"message\":\"hls aktarimi hatasi\"}");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** EXT özniteliği içindeki URI=\"...\" kısmını yeniden yazar. */
    private String rewriteUriAttribute(String line, URL base, String kind) {
        try {
            int a = line.indexOf("URI=\"") + 5;
            int b = line.indexOf('"', a);
            if (a > 4 && b > a) {
                String uri = line.substring(a, b);
                URL abs = new URL(base, uri);
                String nu = Resolver.hlsRef(abs.toString(), kind);
                if (nu == null) {
                    return line;
                }
                return line.substring(0, a) + nu + line.substring(b);
            }
        } catch (Exception ignored) {
        }
        return line;
    }

    boolean streamUpstream(Socket sock, String method, Resolver.Up up,
            Map<String, String> reqHeaders, boolean loud) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(up.url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setRequestProperty("User-Agent", Net.uaFor(up.uaKind));
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            String range = reqHeaders == null ? null : reqHeaders.get("range");
            if (range != null && range.length() > 0) {
                conn.setRequestProperty("Range", range);
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                try {
                    conn.getErrorStream();
                } catch (Exception ignored) {
                }
                Diag.ev("UPSTREAM " + code + " (ua=" + up.uaKind + " host="
                        + hostOf(up.url) + ")");
                if (log != null) {
                    log.log("UPSTREAM " + code + " (ua=" + up.uaKind + ")");
                }
                if (loud) {
                    if (code >= 500 || code == 403 || code == 404) {
                        /* çağıran taraf yeniden çözümleyip deneyebilir */
                        sendJson(sock, 502, "{\"error\":true,\"code\":" + code
                                + ",\"message\":\"video kaynagi yanit vermedi"
                                + " (HTTP " + code + ")\"}");
                    } else {
                        sendJson(sock, code, "{\"error\":true,\"code\":" + code + "}");
                    }
                }
                return false;
            }

            OutputStream out = sock.getOutputStream();
            StringBuilder hb = new StringBuilder();
            hb.append("HTTP/1.1 ").append(code).append(' ')
                    .append(statusText(code)).append("\r\n");
            String ct = conn.getHeaderField("Content-Type");
            hb.append("Content-Type: ").append(ct == null
                    ? (code == 200 || code == 206 ? "video/mp4" : "application/octet-stream")
                    : ct).append("\r\n");
            String cl = conn.getHeaderField("Content-Length");
            if (cl != null && cl.length() > 0) {
                hb.append("Content-Length: ").append(cl).append("\r\n");
            }
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.length() > 0) {
                hb.append("Content-Range: ").append(cr).append("\r\n");
            }
            hb.append("Accept-Ranges: bytes\r\n");
            /* v1.3: hls.js ve capraz-kaynak oynaticilar icin CORS */
            hb.append("Access-Control-Allow-Origin: *\r\n");
            hb.append("Connection: close\r\n\r\n");
            out.write(hb.toString().getBytes("ISO-8859-1"));
            out.flush();

            if ("HEAD".equals(method)) {
                return true;
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[65536];
            try {
                int n;
                long sent = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    sent += n;
                }
                Diag.ev("UPSTREAM tamam: " + code + " " + sent + " bayt ("
                        + hostOf(up.url) + ")");
            } catch (IOException e) {
                /* istemci akışı kesti — normal (seek/meta okuma) */
                Diag.ev("UPSTREAM koptu (istemci kapatti) — normal");
            }
            return true;
        } catch (Exception e) {
            Diag.ev("UPSTREAM istisna: " + e);
            try {
                sendJson(sock, 502, "{\"error\":true,\"message\":\"akis hatasi: "
                        + esc(String.valueOf(e)) + "\"}");
            } catch (Exception ignored) {
            }
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* ---------------- tanilama (v4) ---------------- */

    /**
     * /api/diag — telefonda ne olduğunu İÇERİDEN görebilmek için:
     * ring-günlük (Diag), cihazın genel IP'si, sürüm bilgisi.
     * Tanılama ekranı bu yanıtı kullanıcıya sunar.
     */
    void handleDiag(Socket sock) {
        String pubIp = publicIp();
        String json = "{\"ok\":true,\"version\":\"" + VERSION + "\","
                + "\"uptimeMs\":" + (System.currentTimeMillis() - startedAt) + ","
                + "\"publicIp\":\"" + esc(pubIp) + "\","
                + "\"log\":" + Diag.jsonArray() + "}";
        sendJson(sock, 200, json);
    }

    /** Cihazın dış IP'si (5 dk önbellekli) — IP-kilit karşılaştırması için. */
    private volatile String pubIpCache;
    private volatile long pubIpAt;

    String publicIp() {
        if (pubIpCache != null
                && System.currentTimeMillis() - pubIpAt < 300000) {
            return pubIpCache;
        }
        try {
            Net.Resp r = Net.fetch("https://api.ipify.org?format=text",
                    "GET", null, null, 6000, 6000, 200);
            String ip = r.bodyText() == null ? "" : r.bodyText().trim();
            if (r.code == 200 && ip.length() > 0 && ip.length() < 60) {
                pubIpCache = ip;
                pubIpAt = System.currentTimeMillis();
                return ip;
            }
        } catch (Throwable ignored) {
        }
        return pubIpCache == null ? "" : pubIpCache;
    }

    /** Upstream özeti: host + ip= param + UA türü. */
    static String upstreamInfo(Resolver.Up up) {
        if (up == null) {
            return "YOK";
        }
        return hostOf(up.url) + " ip=" + ipParamOf(up.url) + " ua=" + up.uaKind;
    }

    static String hostOf(String u) {
        try {
            return new URL(u).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    static String ipParamOf(String u) {
        if (u == null) {
            return "-";
        }
        int i = u.indexOf("ip=");
        if (i < 0) {
            return "-";
        }
        int end = u.indexOf('&', i);
        String v = end < 0 ? u.substring(i + 3) : u.substring(i + 3, end);
        return v.length() > 24 ? "-" : v;
    }

    /** /api/streams yanıtının kısa özeti (tanılamada gösterilir). */
    static String streamSummary(String json) {
        if (json == null) {
            return "?";
        }
        try {
            java.util.Map<String, Object> m = Json.asMap(Json.parse(json));
            if (m == null) {
                return "?";
            }
            if (Boolean.TRUE.equals(m.get("error"))) {
                return "HATA(" + m.get("code") + "): " + m.get("message");
            }
            Object srcs = m.get("sources");
            int n = srcs instanceof List ? ((List<?>) srcs).size() : 0;
            boolean hls = m.get("hls") != null && String.valueOf(m.get("hls")).length() > 0;
            return "via=" + m.get("via") + " kaynak=" + n + (hls ? "+HLS" : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    /* ---------------- yardımcılar ---------------- */

    static boolean allowedHost(String u) {
        try {
            String host = new URL(u).getHost().toLowerCase();
            String[] ok = {"googlevideo.com", "youtube.com", "youtu.be",
                    "ytimg.com", "lbry.tv", "lbry.pro", "odycdn.com", "odysee.com",
                    "piped.video", "pipedapi", "proxy.piped"};
            for (String s : ok) {
                if (host.equals(s) || host.endsWith("." + s)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static void parseQuery(String qs, Map<String, String> out) {
        String[] pairs = qs.split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                try {
                    out.put(URLDecoder.decode(p.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(p.substring(eq + 1), "UTF-8"));
                } catch (UnsupportedEncodingException ignored) {
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    static String mimeOf(String path) {
        String low = path.toLowerCase();
        if (low.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (low.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (low.endsWith(".png")) {
            return "image/png";
        }
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (low.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (low.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "'")
                .replace("\r", " ").replace("\n", " ");
    }

    private static byte[] fetchSmall(String url) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Net.UA_WEB);
        Net.Resp r = Net.fetch(url, "GET", h, null, 6000, 9000, 3 * 1024 * 1024);
        if (r.code == 200) {
            return r.body;
        }
        return null;
    }

    private static byte[] readFile(File f) {
        try {
            FileInputStream fi = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = fi.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                fi.close();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
