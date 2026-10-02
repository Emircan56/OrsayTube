package com.localtube.mobil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Masaüstü test sarmalayıcı — LocalServer'ı APK'sız, PC üzerinde ayağa kaldırır.
 * Amaç: APK derlemeden önce tüm uç noktaları gerçek ağ istekleriyle doğrulamak.
 * Çalıştırma: java -cp out com.localtube.mobil.TestServer
 */
public final class TestServer {

    public static void main(String[] args) throws Exception {
        File assetsRoot = new File("/home/z/my-project/apk-build/assets/www");
        File cacheDir = new File("/home/z/my-project/apk-build/out/desktop-cache");
        cacheDir.mkdirs();

        LocalServer.AssetSource fs = new LocalServer.AssetSource() {
            public byte[] load(String relPath) {
                try {
                    File f = new File(assetsRoot, relPath);
                    if (!f.exists()) {
                        return null;
                    }
                    InputStream in = new FileInputStream(f);
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
        };

        LocalServer server = new LocalServer(fs, cacheDir, new Resolver(),
                new LocalServer.Logger() {
                    public void log(String msg) {
                        System.out.println("[LT] " + msg);
                    }
        });
        server.setLanIp("127.0.0.1");
        server.start();
        server.startDiscoveryResponder(); /* v4.2: UDP keşif yanıtlayıcı testi */
        if (!server.isRunning()) {
            System.out.println("SUNUCU ACILAMADI");
            System.exit(1);
        }
        System.out.println("HAZIR: http://localhost:" + server.getPort()
                + "  (kapatmak için Ctrl+C)");
        /* ana thread'i canlı tut */
        Thread.currentThread().join();
    }
}
