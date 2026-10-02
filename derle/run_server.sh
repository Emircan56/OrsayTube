#!/bin/bash
# v4.6 tam sunucu (NewPipe motoru + TV arayüzü + UDP keşif) masaüstü canlı test
# Yollar OrsayTube derleme ağacına güncellendi.
set -e
JAVAC=/home/z/jdk/bin/javac
JAVA=/home/z/jdk/bin/java
SRC=/home/z/my-project/orsay-build/proj/src
LIBS=/home/z/my-project/orsay-build/proj/libs
OUT=/home/z/my-project/orsay-build/proj/out/desktop
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 --release 11 -nowarn -g:none \
  -classpath "$LIBS/newpipe-extractor.jar:$LIBS/jsoup-1.22.2.jar:$LIBS/rhino-1.8.1.jar:$LIBS/rhino-engine-1.8.1.jar:$LIBS/nanojson.jar" \
  -d "$OUT" \
  "$SRC/com/localtube/mobil/Json.java" \
  "$SRC/com/localtube/mobil/Net.java" \
  "$SRC/com/localtube/mobil/Npe.java" \
  "$SRC/com/localtube/mobil/Resolver.java" \
  "$SRC/com/localtube/mobil/LocalServer.java" \
  "$SRC/com/localtube/mobil/Diag.java" \
  "$SRC/com/localtube/mobil/TestServer.java"
"$JAVA" -cp "$OUT:$LIBS/newpipe-extractor.jar:$LIBS/jsoup-1.22.2.jar:$LIBS/rhino-1.8.1.jar:$LIBS/rhino-engine-1.8.1.jar:$LIBS/protobuf-javalite-4.35.1.jar:$LIBS/nanojson.jar" \
  com.localtube.mobil.TestServer
