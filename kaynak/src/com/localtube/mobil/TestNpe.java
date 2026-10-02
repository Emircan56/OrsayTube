package com.localtube.mobil;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Desktop validation of the real NewPipe Extractor engine (diekaiju/localtube's system). */
public class TestNpe {

    static class SimpleDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String method = request.httpMethod();
            URL url = new URL(request.url());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(true);
            if (request.headers() != null) {
                for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                    for (String v : e.getValue()) conn.addRequestProperty(e.getKey(), v);
                }
            }
            if (!hasHeader(request.headers(), "User-Agent")) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            }
            boolean post = method.equalsIgnoreCase("POST");
            conn.setDoOutput(post);
            if (post) {
                byte[] data = request.dataToSend() != null ? request.dataToSend() : new byte[0];
                conn.setFixedLengthStreamingMode(data.length);
                conn.getOutputStream().write(data);
            }
            int code = conn.getResponseCode();
            if (code == 429) throw new ReCaptchaException("recaptcha", request.url());
            Map<String, List<String>> respHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null) respHeaders.put(e.getKey(), e.getValue());
            }
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is != null ? readAll(is, 8 * 1024 * 1024) : "";
            String latest = conn.getURL().toString();
            conn.disconnect();
            return new Response(code, "OK", respHeaders, body, latest);
        }

        private boolean hasHeader(Map<String, List<String>> h, String name) {
            if (h == null) return false;
            for (String k : h.keySet()) if (k != null && k.equalsIgnoreCase(name)) return true;
            return false;
        }

        private String readAll(InputStream is, int max) throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > max) break;
            }
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    public static void main(String[] args) throws Exception {
        NewPipe.init(new SimpleDownloader(), new Localization("tr", "TR"));
        StreamingService yt = NewPipe.getService(0);
        String[] ids = args.length > 0 ? args : new String[]{"dQw4w9WgXcQ", "aqz-KE-bpKQ"};
        for (String id : ids) {
            System.out.println("==== VIDEO " + id + " ====");
            try {
                long t0 = System.currentTimeMillis();
                StreamInfo info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=" + id);
                System.out.println("title: " + info.getName() + "  (" + (System.currentTimeMillis() - t0) + " ms)");
                System.out.println("hls: " + info.getHlsUrl());
                List<VideoStream> vs = info.getVideoStreams();
                System.out.println("progressive streams: " + (vs == null ? 0 : vs.size()));
                if (vs != null) {
                    for (VideoStream s : vs) {
                        System.out.println("  itag=" + s.getItag() + " " + s.getResolution() + " "
                            + s.getFormat().getSuffix() + " url=" + shorten(s.getContent()));
                    }
                }
                List<VideoStream> vo = info.getVideoOnlyStreams();
                System.out.println("video-only: " + (vo == null ? 0 : vo.size())
                    + " (top: " + (vo != null && !vo.isEmpty() ? vo.get(0).getResolution() : "-") + ")");
                List<AudioStream> au = info.getAudioStreams();
                System.out.println("audio: " + (au == null ? 0 : au.size()));
                // test fetch first bytes of best progressive stream
                if (vs != null && !vs.isEmpty()) {
                    String url = vs.get(0).getContent();
                    testFetch(url);
                }
                if (au != null && !au.isEmpty()) {
                    testFetch(au.get(0).getContent());
                }
            } catch (Exception e) {
                System.out.println("EXTRACT FAIL: " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    static String shorten(String u) {
        if (u == null) return null;
        return u.length() > 110 ? u.substring(0, 110) + "..." : u;
    }

    static void testFetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "com.google.android.youtube/21.03.2 (Linux; U; Android 14) gzip");
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            String ct = c.getContentType();
            c.getInputStream().read(new byte[64]);
            c.disconnect();
            System.out.println("FETCH[Range 0-1023] -> " + code + " " + ct);
        } catch (Exception e) {
            System.out.println("FETCH FAIL: " + e);
        }
    }
}
