package com.localtube.mobil;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * WebView ↔ yerel kod köprüsü (app.js tarafında OrsayTubeNative olarak görünür).
 */
public class Bridge {

    private final MainActivity activity;

    Bridge(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String serverInfo() {
        int port = ServerService.getPort();
        String lan = ServerService.isReady() ? serverLan() : "";
        return "{\"running\":" + ServerService.isReady()
                + ",\"port\":" + port
                + ",\"lanIp\":\"" + lan + "\""
                + ",\"version\":\"" + LocalServer.VERSION + "\"}";
    }

    @android.webkit.JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            /* Video akışı URL'leri oynatıcıların ele alması için tip
             * ekle (VLC/MX ACTION_VIEW+video/mp4 dinler; tarayıcı
             * ele almaz → doğrudan oynatıcı seçilir) */
            String u = url == null ? "" : url;
            if (u.contains("googlevideo.com") || u.contains("/videoplayback")
                    || u.endsWith(".mp4")) {
                i.setDataAndType(Uri.parse(url), "video/mp4");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity,
                    "Uygun oynatıcı yok — VLC kurup tekrar deneyin",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Açılamadı: " + url,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @android.webkit.JavascriptInterface
    public void toast(String msg) {
        Toast.makeText(activity, msg == null ? "" : msg, Toast.LENGTH_SHORT).show();
    }

    @android.webkit.JavascriptInterface
    public void copyText(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) activity
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "OrsayTube", text));
                Toast.makeText(activity, "Kopyalandı", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(activity, "Kopyalanamadı", Toast.LENGTH_SHORT).show();
        }
    }

    private static String serverLan() {
        try {
            return ServerService.detectLanIp();
        } catch (Throwable t) {
            return "";
        }
    }
}
