package com.localtube.mobil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * ÖN PLAN SERVİSİ — diekaiju/localtube'ın ServerService'ı gibi (GPLv3):
 * sunucu uygulama arka plana alınsa da çalışmaya devam eder, böylece
 * telefon tarayıcısından http://localhost:8080 her zaman açılır.
 */
public class ServerService extends Service {

    private static volatile LocalServer server;
    private static volatile boolean ready;
    private static volatile WifiManager.MulticastLock mlock;

    public static boolean isReady() {
        LocalServer s = server;
        return ready && s != null && s.isRunning();
    }

    public static int getPort() {
        LocalServer s = server;
        return s == null ? -1 : s.getPort();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        holdMulticastLock();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                if (nm.getNotificationChannel("orsaytube") == null) {
                    NotificationChannel ch = new NotificationChannel("orsaytube",
                            "Yerel Sunucu", NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("OrsayTube yerel sunucu durumu");
                    nm.createNotificationChannel(ch);
                }
                /* eski kanal kimliği temizlensin (görünmez geçiş) */
                nm.deleteNotificationChannel("localtube");
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1001, buildNotification());
        if (!isReady()) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ready = false;
        LocalServer s = server;
        if (s != null) {
            s.stop();
        }
        server = null;
        releaseMulticastLock();
        super.onDestroy();
    }

    /**
     * v4.2: WiFi çipleri yayın (broadcast) paketlerini varsayılan olarak
     * filtreler; UDP keşif yanıtı VEREBİLMEK için MulticastLock tutulur.
     */
    private void holdMulticastLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm != null && mlock == null) {
                mlock = wm.createMulticastLock("orsaytube-discovery");
                mlock.setReferenceCounted(false);
                mlock.acquire();
                android.util.Log.d("OrsayTube", "multicast lock alindi");
            }
        } catch (Throwable t) {
            android.util.Log.d("OrsayTube", "multicast lock hatasi: " + t);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (mlock != null && mlock.isHeld()) {
                mlock.release();
            }
        } catch (Throwable ignored) {
        }
        mlock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        final LocalServer.AssetSource assets = new AndroidAssets(getAssets());
        final File cacheDir = getCacheDir();
        final Resolver resolver = new Resolver();
        new Thread(new Runnable() {
            public void run() {
                LocalServer s = new LocalServer(assets, cacheDir, resolver,
                        new LocalServer.Logger() {
                            public void log(String msg) {
                                android.util.Log.d("OrsayTube", msg);
                            }
                        });
                s.setLanIp(detectLanIp());
                s.start();
                s.startDiscoveryResponder(); /* v4.2: TV APK keşfi */
                server = s;
                ready = s.isRunning();
                if (ready) {
                    android.util.Log.i("OrsayTube",
                            "sunucu hazir: http://localhost:" + s.getPort());
                }
                warmUpEngine(resolver);
            }
        }, "orsaytube-boot").start();
        /* bildirimi gerçek portla tazele */
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 40; i++) {
                    if (isReady()) {
                        break;
                    }
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (isReady()) {
                    try {
                        startForeground(1001, buildNotification());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "orsaytube-notif").start();
    }

    /**
     * v4 motor ısınması: NewPipe init + bilinen bir videonun çözümlenmesi
     * arka planda bir kez yapılır. Böylece player JS indirme + Rhino
     * derlemesi (telefonda pahalı) İLK videodan önce tamamlanır; kullanıcı
     * ilk videoyu açtığında çözümleme saniyeler içinde döner.
     */
    private static void warmUpEngine(final Resolver resolver) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(800); /* sunucunun kabul döngüsüne yer aç */
                } catch (InterruptedException ignored) {
                }
                try {
                    if (Npe.init()) {
                        resolver.streams("dQw4w9WgXcQ");
                        Diag.ev("motor isinmasi tamam");
                    }
                } catch (Throwable t) {
                    Diag.ev("motor isinmasi hatasi: " + Resolver.stack(t));
                }
            }
        }, "orsaytube-warmup").start();
    }

    private Notification buildNotification() {
        int port = getPort();
        String text = port > 0
                ? "Sunucu çalışıyor — tarayıcıdan: http://localhost:" + port
                : "Yerel sunucu başlatılıyor…";
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, "orsaytube");
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("OrsayTube Mobil")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    static String detectLanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /* ---------------- varlık (asset) kaynağı ---------------- */

    static final class AndroidAssets implements LocalServer.AssetSource {
        private final AssetManager am;

        AndroidAssets(AssetManager am) {
            this.am = am;
        }

        public byte[] load(String relPath) {
            try {
                InputStream in = am.open("www/" + relPath);
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    return bos.toByteArray();
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
