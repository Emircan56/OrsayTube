package com.localtube.mobil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Sunucu içi tanılama (v3.1) — telefon tarafında ne olduğunu
 * KOD İÇİNDEN görebilmek için: çözümleme süreleri, kaynak zinciri,
 * upstream HTTP kodları ring-buffer'a yazılır; /api/diag ile
 * izleme sayfasına (ve 'Tanılamayı kopyala' düğmesine) taşınır.
 *
 * Saf Java — masaüstünde de çalışır.
 */
public final class Diag {

    private static final int CAP = 70;
    private static final ArrayDeque<String> BUF =
            new ArrayDeque<String>(CAP);

    private Diag() {
    }

    public static void ev(String msg) {
        synchronized (BUF) {
            if (BUF.size() >= CAP) {
                BUF.pollFirst();
            }
            BUF.add(System.currentTimeMillis() + " " + msg);
        }
        System.out.println("[DIAG] " + msg);
    }

    /** Kısa video kimliği: dQw4w9WgXcQ → dQw4…gXcQ */
    static String vid(String v) {
        if (v == null || v.length() <= 8) {
            return String.valueOf(v);
        }
        return v.substring(0, 4) + "…" + v.substring(v.length() - 4);
    }

    static String ms(long t0) {
        return (System.currentTimeMillis() - t0) + "ms";
    }

    /** /api/diag yanıtı için satır listesi (JSON dizisi). */
    public static String jsonArray() {
        List<String> lines;
        synchronized (BUF) {
            lines = new ArrayList<String>(BUF);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(LocalServer.esc(lines.get(i))).append('"');
        }
        sb.append("]");
        return sb.toString();
    }
}
