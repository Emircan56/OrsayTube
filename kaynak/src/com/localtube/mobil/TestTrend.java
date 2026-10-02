package com.localtube.mobil;

import java.util.List;
import java.util.Map;

/** v4.4 teşhis: NewPipe trending şu an çalışıyor mu? */
public class TestTrend {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> out = Npe.trending(20);
        System.out.println("sure: " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("oge: " + (out == null ? -1 : out.size()));
        if (out != null) {
            for (int i = 0; i < Math.min(6, out.size()); i++) {
                System.out.println("  - " + out.get(i).get("videoId") + " | "
                        + out.get(i).get("title") + " | " + out.get(i).get("channel"));
            }
        }
    }
}
