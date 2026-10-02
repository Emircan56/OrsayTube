package com.localtube.mobil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Basit HTTP istemcisi — HttpURLConnection üstüne ince katman.
 * Sunucu tarafında (telefon/masaüstü) tüm dış istekler buradan geçer.
 */
public final class Net {

    /* YouTube istemci kimlikleri — NewPipe master (ClientsConstants) ile birebir
     * aynı sürümler: vekil (proxy) isteğindeki UA, akışı ÇÖZÜMLEYEN istemciyle
     * eşleşmeli — yoksa googlevideo 403 döner (diekaiju/localtube'ın c= eşleştirmesi). */
    public static final String UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    public static final String UA_VR =
            "com.google.android.apps.youtube.vr.oculus/1.64.88 "
                    + "(Linux; U; Android 12; eureka-user Build/SQ3A.220705.004.A1) gzip";
    public static final String UA_IOS =
            "com.google.ios.youtube/21.03.2"
                    + " (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; TR)";
    public static final String UA_ANDROID =
            "com.google.android.youtube/21.03.36 (Linux; U; Android 15; TR) gzip";
    public static final String UA_VISIONOS =
            "com.google.visionos.youtube/1.02"
                    + " (RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; TR)";

    public static String uaFor(String kind) {
        if ("vr".equals(kind)) {
            return UA_VR;
        }
        if ("ios".equals(kind)) {
            return UA_IOS;
        }
        if ("android".equals(kind)) {
            return UA_ANDROID;
        }
        if ("visionos".equals(kind)) {
            return UA_VISIONOS;
        }
        return UA_WEB;
    }

    /**
     * diekaiju/localtube yöntemi: googlevideo URL'indeki c= parametresine bakarak
     * akışı çıkaran istemcinin UA'sını seçer (IOS/VISIONOS/ANDROID/WEB).
     */
    public static String uaKindForUrl(String url) {
        if (url == null) {
            return "web";
        }
        String u = url.toLowerCase();
        if (!u.contains("googlevideo.com")) {
            return "web";
        }
        if (containsParam(u, "c=ios")) {
            return "ios";
        }
        if (containsParam(u, "c=visionos")) {
            return "visionos";
        }
        if (containsParam(u, "c=android") || containsParam(u, "c=android_vr")) {
            return "android";
        }
        return "web";
    }

    private static boolean containsParam(String urlLow, String p) {
        return urlLow.contains(p) || urlLow.contains(p.replace("=", "%3d"));
    }

    public static final class Resp {
        public int code;
        public byte[] body = new byte[0];
        public Map<String, String> headers = new LinkedHashMap<String, String>();

        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        public String bodyText() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    /**
     * Tamponlu istek — API çağrıları için (akış proxy'si için değil).
     */
    public static Resp fetch(String url, String method, Map<String, String> headers,
            byte[] body, int connectTimeoutMs, int readTimeoutMs, int maxBytes) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null) {
                conn.setRequestMethod(method == null ? "POST" : method);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                    os.flush();
                } finally {
                    try {
                        os.close();
                    } catch (IOException ignored) {
                    }
                }
            } else if (method != null && !method.equalsIgnoreCase("GET")
                    && !method.equalsIgnoreCase("HEAD")) {
                conn.setRequestMethod(method);
            }

            try {
                r.code = conn.getResponseCode();
            } catch (IOException e) {
                r.code = 0;
                return r;
            }

            InputStream in = null;
            try {
                in = (r.code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            } catch (IOException e) {
                in = null;
            }
            if (in == null && r.code < 400) {
                return r;
            }
            if (in != null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > maxBytes) {
                            break;
                        }
                        bos.write(buf, 0, n);
                    }
                    r.body = bos.toByteArray();
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip") && r.body.length > 0) {
                try {
                    GZIPInputStream gz = new GZIPInputStream(
                            new ByteArrayInputStream(r.body));
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b2 = new byte[16384];
                    int m;
                    while ((m = gz.read(b2)) > 0) {
                        out.write(b2, 0, m);
                    }
                    gz.close();
                    r.body = out.toByteArray();
                } catch (IOException ignored) {
                }
            }

            Map<String, String> hh = new LinkedHashMap<String, String>();
            for (int i = 0; ; i++) {
                String k = conn.getHeaderFieldKey(i);
                String v = conn.getHeaderField(i);
                if (k == null && v == null) {
                    break;
                }
                if (k != null && v != null && !hh.containsKey(k)) {
                    hh.put(k, v);
                }
            }
            r.headers = hh;
        } catch (Exception e) {
            r.code = -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    public static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
