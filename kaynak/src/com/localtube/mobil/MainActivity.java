package com.localtube.mobil;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OrsayTube Mobil v2.0 — diekaiju/localtube (GPLv3) mimarisi:
 * ÖNCE GERÇEK YEREL SUNUCU AÇILIR (ServerService, ServerSocket :8080),
 * sonra WebView http://127.0.0.1:8080/ adresini açar.
 * Telefon tarayıcısı ve aynı ağdaki cihazlar da aynı sunucuya bağlanabilir.
 *
 * Video oynatma, arama, küçük resimler — hepsi yerel sunucu üzerinden
 * aynı-kökenli (same-origin) çalışır: CORS/karışık-içerik sorunu yoktur.
 */
public class MainActivity extends Activity {

    private static final Pattern VID_P1 = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern VID_P2 = Pattern.compile(
            "(?:youtu\\.be|youtube\\.com|youtube\\.googleapis\\.com)/"
                    + "(?:embed/|shorts/|live/)?([A-Za-z0-9_-]{11})");

    WebView web;
    private long lastBack;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        try {
            w.setStatusBarColor(Color.parseColor("#0f0f0f"));
            w.setNavigationBarColor(Color.parseColor("#0f0f0f"));
        } catch (Throwable ignored) {
        }

        /* 1) sunucuyu başlat (ön plan servisi — arka planda da yaşar) */
        try {
            startService(new Intent(this, ServerService.class));
        } catch (Throwable t) {
            toast("Sunucu servisi başlatılamadı: " + t);
        }

        /* 2) WebView → yerel sunucu */
        web = new WebView(this);
        web.setBackgroundColor(0xFF0F0F0F);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        try {
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        } catch (Throwable ignored) {
        }
        s.setUserAgentString(Net.UA_WEB);

        web.setWebViewClient(new AppWebViewClient(this));
        web.setWebChromeClient(new AppChromeClient(this));
        web.addJavascriptInterface(new Bridge(this), "OrsayTubeNative");
        web.setKeepScreenOn(true);
        setContentView(web);

        loadInitial(getIntent());
    }

    /* ---------------- ilk yükleme ---------------- */

    private void loadInitial(Intent intent) {
        String vid = null;
        try {
            if (intent != null) {
                String act = intent.getAction();
                String type = intent.getType();
                if (Intent.ACTION_SEND.equals(act)
                        && "text/plain".equals(type)) {
                    vid = parseVideoId(String.valueOf(
                            intent.getStringExtra(Intent.EXTRA_TEXT)));
                } else if (Intent.ACTION_VIEW.equals(act)
                        && intent.getData() != null) {
                    vid = parseVideoId(intent.getData().toString());
                }
            }
        } catch (Throwable ignored) {
        }
        final String start = (vid != null)
                ? "/watch?v=" + vid
                : "/";
        new Thread(new BootLoader(this, start), "orsaytube-load").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web == null || !ServerService.isReady()) {
            loadInitial(intent);
            return;
        }
        String vid = null;
        try {
            if (intent != null) {
                if (Intent.ACTION_SEND.equals(intent.getAction())) {
                    vid = parseVideoId(String.valueOf(
                            intent.getStringExtra(Intent.EXTRA_TEXT)));
                } else if (Intent.ACTION_VIEW.equals(intent.getAction())
                        && intent.getData() != null) {
                    vid = parseVideoId(intent.getData().toString());
                }
            }
        } catch (Throwable ignored) {
        }
        if (vid != null) {
            final String url = baseUrl() + "/watch?v=" + vid;
            runOnUiThread(new Runnable() {
                public void run() {
                    web.loadUrl(url);
                }
            });
        }
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + ServerService.getPort();
    }

    void toast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    static String parseVideoId(String text) {
        if (text == null || text.length() < 11) {
            return null;
        }
        Matcher m = VID_P1.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = VID_P2.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /* ---------------- tam ekran (video) ---------------- */

    void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            try {
                callback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            return;
        }
        customView = view;
        customViewCallback = callback;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(customView);
    }

    void exitFullscreen() {
        if (customView == null) {
            return;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(web);
        customView = null;
        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            customViewCallback = null;
        }
    }

    /* ---------------- geri tuşu / yaşam döngüsü ---------------- */

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        long now = System.currentTimeMillis();
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (now - lastBack < 2200) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "Çıkmak için geri tuşuna tekrar basın",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
        if (web != null && ServerService.isReady()
                && web.getUrl() != null && !web.getUrl().startsWith("http")) {
            web.loadUrl(baseUrl() + "/");
        }
    }

    @Override
    protected void onDestroy() {
        /* Sunucu servisi bilinçli olarak ÇALIŞIR bırakılır:
           telefonda tarayıcıdan localhost erişimi sürsün (diekaiju/localtube davranışı). */
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}

/* ================== yardımcı sınıflar (dost d8 için üst-düzey) ================== */

final class BootLoader implements Runnable {
    private final MainActivity activity;
    private final String startPath;

    BootLoader(MainActivity activity, String startPath) {
        this.activity = activity;
        this.startPath = startPath;
    }

    public void run() {
        long t0 = System.currentTimeMillis();
        while (!ServerService.isReady()
                && System.currentTimeMillis() - t0 < 12000) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
        }
        final boolean ok = ServerService.isReady();
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (ok) {
                    activity.web.loadUrl(MainActivity.baseUrl() + startPath);
                } else {
                    Toast.makeText(activity,
                            "Sunucu başlatılamadı — uygulama kapatılıp tekrar denenecek",
                            Toast.LENGTH_LONG).show();
                    new Thread(new BootLoader(activity, startPath),
                            "orsaytube-load2").start();
                }
            }
        });
    }
}

final class AppWebViewClient extends WebViewClient {
    private final MainActivity activity;

    AppWebViewClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.equals("127.0.0.1") || host.equals("localhost")) {
                return false; /* yerel sunucu — WebView içinde kalsın */
            }
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                /* durum çubuğu rengini sayfadan senkronla */
                view.evaluateJavascript(
                        "window.__ltTheme && window.__ltTheme('#0f0f0f');", null);
            }
        } catch (Throwable ignored) {
        }
    }
}

final class AppChromeClient extends WebChromeClient {
    private final MainActivity activity;

    AppChromeClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        activity.enterFullscreen(view, callback);
    }

    @Override
    public void onHideCustomView() {
        activity.exitFullscreen();
    }
}
