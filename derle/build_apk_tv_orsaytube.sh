#!/bin/bash
# OrsayTube TV v1.3 — Android TV istemcisi APK derleme betiği
# TV 1.3 kodu ile aynı; yalnızca:
#   - yeni noktasız logo + banner
#   - etiket "OrsayTube TV"
#   - versionCode 2 / versionName 1.3 (eski 1.0 kurulumunun ÜSTÜNE kurulur)
set -e

SDK=${SDK:-/home/z/android-sdk}
BT="$SDK/android-14"
PLAT="$SDK/android-13/android.jar"
JDK=${JDK:-/home/z/jdk/bin}
R8=${R8:-/home/z/my-project/r8.jar}
# TV uygulaması kaynak ağacı (MainActivity.java + res) — TVAPK_SRC ile dışarıdan verilir.
# Not: depodaki gömülü ikili (kaynak/assets/www/localtube-tv.apk) yetkilidir;
# yeniden derleme yalnızca TV tarafında değişiklik gerektiğinde yapılır.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJ=${TVAPK_SRC:-$ROOT/tv-src}
OUT="$PROJ/out/apk"
KEY="$ROOT/kaynak/keystore/localtube.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/dex"

echo "[1/6] aapt2 compile (kaynaklar)"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link (APK iskeleti)"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version 33 \
  --version-code 5 --version-name 1.3 \
  "$OUT/res.zip"

echo "[3/6] javac (Java 8 uyumlu — eski TV'ler için anonim sınıflar)"
mkdir -p "$OUT/obj"
"$JDK/javac" -encoding UTF-8 --release 8 -nowarn -g:none \
  -classpath "$PLAT" \
  -d "$OUT/obj" \
  "$PROJ/src/com/localtube/tv/MainActivity.java"

echo "[4/6] d8 (r8 8.5.35 — min-api 21)"
"$JDK/jar" cf "$OUT/app-classes.jar" -C "$OUT/obj" .
"$JDK/java" -cp "$R8" com.android.tools.r8.D8 --release \
  --lib "$PLAT" --min-api 21 \
  --output "$OUT/dex" \
  "$OUT/app-classes.jar"

cd "$OUT"
cp base.apk unsigned.apk
cd dex && zip -q -j ../unsigned.apk classes.dex && cd ..

echo "[5/6] zipalign + imzala"
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KEY" \
  --ks-key-alias localtube \
  --ks-pass pass:localtubemobil2024 \
  --key-pass pass:localtubemobil2024 \
  --out "$OUT/OrsayTube-TV-1.3.apk" aligned.apk
"$BT/apksigner" verify --print-certs "$OUT/OrsayTube-TV-1.3.apk" | head -4

echo "[6/6] badging (TV uyumluluk denetimi)"
"$BT/aapt2" dump badging "$OUT/OrsayTube-TV-1.3.apk" | grep -E "package|application-label|launchable|uses-feature|banner" | head -10

ls -la "$OUT/OrsayTube-TV-1.3.apk"
echo "TV APK HAZIR: $OUT/OrsayTube-TV-1.3.apk"
