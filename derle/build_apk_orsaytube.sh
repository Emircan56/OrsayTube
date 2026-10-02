#!/bin/bash
# OrsayTube Mobil v4.6 — APK derleme betiği
# v4.6 kodu ile AYNI; yalnızca:
#   - yeni noktasız logo (mipmap + icon-512) + gömülü widget ikonları
#   - isim: OrsayTube (etiket, bildirim, arayüz metinleri)
#   - versionCode 48 (her eski sürümün ÜSTÜNE kurulur) / versionName 4.6
# Teknik tanımlayıcılar KORUNDU (güncelleme ve TV uyumluluğu için):
# paket adı, TV indirme uç adları, keystore, imza.
set -e

SDK=${SDK:-/home/z/android-sdk}
BT="$SDK/android-14"
PLAT="$SDK/android-13/android.jar"
JDK=${JDK:-/home/z/jdk/bin}
R8=${R8:-/home/z/my-project/r8.jar}
# Depo-göreli yollar: betik depo içinde derle/ altında — kaynak aynı depoda
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJ="$ROOT/kaynak"
LIBS="$PROJ/libs"
OUT="$PROJ/out/apk46"   # .gitignore kapsamında
SRC="$PROJ/src/com/localtube/mobil"

# 0) TV APK: depoya gömülü sürüm kullanılır (varlık adı KALIR — sunucu ucu bu adı bekler).
#    Yeni bir TV APK'sı derleyip gömmek isterseniz: TVAPK=/yol/OrsayTube-TV-1.3.apk ./build_apk_orsaytube.sh
if [ -n "$TVAPK" ] && [ -f "$TVAPK" ]; then
  cp "$TVAPK" "$PROJ/assets/www/localtube-tv.apk"
  echo "[0/8] TV APK varlıklara gömüldü: $(stat -c%s "$TVAPK") bayt"
else
  echo "[0/8] gömülü TV APK kullanılıyor: $(stat -c%s "$PROJ/assets/www/localtube-tv.apk") bayt"
fi

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/dex"

echo "[1/8] aapt2 compile (kaynaklar)"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[2/8] aapt2 link (APK iskeleti + varlıklar)"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code 48 --version-name 4.6 \
  -A "$PROJ/assets" \
  "$OUT/res.zip"

echo "[3/8] javac (Java 11 — yamalı motor jar dahil)"
"$JDK/javac" -encoding UTF-8 --release 11 -nowarn -g:none \
  -classpath "$PLAT:$LIBS/newpipe-extractor.jar:$LIBS/jsoup-1.22.2.jar:$LIBS/jsr305-3.0.2.jar:$LIBS/protobuf-javalite-4.35.1.jar:$LIBS/rhino-1.8.1.jar:$LIBS/rhino-engine-1.8.1.jar:$LIBS/nanojson.jar" \
  -d "$OUT/obj" \
  "$SRC/Json.java" \
  "$SRC/Net.java" \
  "$SRC/Npe.java" \
  "$SRC/Resolver.java" \
  "$SRC/LocalServer.java" \
  "$SRC/Diag.java" \
  "$SRC/ServerService.java" \
  "$SRC/Bridge.java" \
  "$SRC/MainActivity.java"

echo "[4/8] d8 (uygulama + YAMALI motor + bağımlılıklar → dex, min-api 26)"
"$JDK/jar" cf "$OUT/app-classes.jar" -C "$OUT/obj" .
"$JDK/java" -cp "$R8" com.android.tools.r8.D8 --release \
  --lib "$PLAT" --min-api 26 \
  --output "$OUT/dex" \
  "$OUT/app-classes.jar" \
  "$LIBS/newpipe-extractor.jar" \
  "$LIBS/jsoup-1.22.2.jar" \
  "$LIBS/rhino-1.8.1.jar" \
  "$LIBS/rhino-engine-1.8.1.jar" \
  "$LIBS/protobuf-javalite-4.35.1.jar" \
  "$LIBS/nanojson.jar" \
  "$LIBS/jsr305-3.0.2.jar"

echo "[5/8] dex dosyalarını APK'ya ekle + zipalign"
ls "$OUT/dex/"
cd "$OUT/dex"
zip -q -j "$OUT/base.apk" classes*.dex
cd "$PROJ"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/8] imzala + doğrula"
"$BT/apksigner" sign \
  --ks "$PROJ/keystore/localtube.keystore" \
  --ks-key-alias localtube \
  --ks-pass pass:localtubemobil2024 \
  --key-pass pass:localtubemobil2024 \
  --out "$OUT/OrsayTube-Mobil-v4.6.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/OrsayTube-Mobil-v4.6.apk" | head -4

echo "[7/8] paket doğrulama"
"$BT/aapt2" dump badging "$OUT/OrsayTube-Mobil-v4.6.apk" | head -3
echo "--- gömülü varlıklar (TV APK + widget + TV arayüzü) ---"
unzip -l "$OUT/OrsayTube-Mobil-v4.6.apk" | grep -E "localtube-tv.apk|localtube-tv-widget.zip|tv.html|tv.css|tv.js" | head -6
echo "--- webEnrich kanıtı (dex içinde) ---"
"$BT/dexdump" "$OUT"/dex/classes*.dex 2>/dev/null | grep -c "webEnrich" || echo "0 — HATA: webEnrich dex'e girmedi!"

echo "--- OrsayTube isim kanıtı (dex + varlıklar) ---"
"$BT/dexdump" "$OUT"/dex/classes*.dex 2>/dev/null | grep -o "OrsayTube Mobil" | head -2
unzip -p "$OUT/OrsayTube-Mobil-v4.6.apk" assets/www/index.html | grep -o "OrsayTube" | head -2
unzip -p "$OUT/OrsayTube-Mobil-v4.6.apk" assets/www/widget/localtube-tv-widget.zip > /tmp/w.zip && unzip -p /tmp/w.zip config.xml | grep -o "OrsayTube TV" | head -1

ls -la "$OUT/OrsayTube-Mobil-v4.6.apk"
echo "Mobil APK HAZIR: $OUT/OrsayTube-Mobil-v4.6.apk"
