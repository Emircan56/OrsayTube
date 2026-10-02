package com.localtube.mobil;

import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

/** NewPipe'in çözdüğü ham URL'leri + UA eşleşmesini yazdır */
public final class TestNpeUrl {
    public static void main(String[] args) throws Exception {
        String v = args.length > 0 ? args[0] : "dQw4w9WgXcQ";
        if (!Npe.init()) { System.out.println("init fail"); return; }
        long t0 = System.currentTimeMillis();
        StreamInfo info = StreamInfo.getInfo(Npe.service(),
                "https://www.youtube.com/watch?v=" + v);
        System.out.println("sure: " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("hls: " + info.getHlsUrl());
        List<VideoStream> vs = info.getVideoStreams();
        for (VideoStream s : vs) {
            if (s.isVideoOnly()) continue;
            String url = s.getContent();
            String kind = Net.uaKindForUrl(url);
            int ci = url.indexOf("c=");
            String cparam = ci >= 0 ? url.substring(ci, Math.min(ci + 20, url.length())) : "?";
            System.out.println("itag=" + s.getItag() + " res=" + s.getResolution()
                    + " uaKind=" + kind + " " + cparam);
            System.out.println("   url=" + url.substring(0, Math.min(140, url.length())));
        }
    }
}
