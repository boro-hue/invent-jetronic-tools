#!/usr/bin/env bash
# Build the Mustang OBD APK without Android Studio/Gradle.
#
# Needs: JDK 11+ (javac, keytool), aapt2, dx (dalvik-exchange) or d8, zipalign, apksigner, curl.
#   Ubuntu/Debian:  sudo apt install openjdk-17-jdk-headless aapt dalvik-exchange zipalign apksigner
# The Android API 34 framework jar is fetched once from Maven Central (Robolectric's
# android-all, which also carries the framework resources aapt2 needs) and checksum-verified.
#
# Output: dist/MustangOBD-<version>.apk
set -euo pipefail
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS || true

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)"
grep -q "VERSION = \"$VERSION\"" src/io/github/borohue/mustangobd/BuildInfo.java \
    || { echo "BuildInfo.VERSION does not match versionName $VERSION"; exit 1; }

ALL_VER=14-robolectric-10818077
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/mustang_obd"
ANDROID_JAR="${ANDROID_JAR:-$CACHE/android-all-$ALL_VER.jar}"
if [ ! -f "$ANDROID_JAR" ]; then
    mkdir -p "$CACHE"
    URL="https://repo1.maven.org/maven2/org/robolectric/android-all/$ALL_VER/android-all-$ALL_VER.jar"
    echo "Downloading Android API 34 framework jar (~130 MB) from Maven Central..."
    curl -fL --retry 3 -o "$ANDROID_JAR.part" "$URL"
    WANT="$(curl -fsL "$URL.sha1" | cut -c1-40)"
    GOT="$(sha1sum "$ANDROID_JAR.part" | cut -c1-40)"
    [ "$WANT" = "$GOT" ] || { echo "checksum mismatch ($GOT != $WANT)"; rm -f "$ANDROID_JAR.part"; exit 1; }
    mv "$ANDROID_JAR.part" "$ANDROID_JAR"
fi

DEX="$(command -v d8 || command -v dalvik-exchange || command -v dx || true)"
[ -n "$DEX" ] || { echo "need d8 or dx (apt install dalvik-exchange)"; exit 1; }

B=build
rm -rf "$B"
mkdir -p "$B/classes" "$B/gen" dist

echo "[1/6] resources"
aapt2 compile --dir res -o "$B/res.zip"
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java "$B/gen" \
    -o "$B/base.apk" "$B/res.zip"

echo "[2/6] java (Java 8 bytecode, no lambdas: dx-compatible, runs on Android 6+)"
javac --release 8 -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" \
    $(find src "$B/gen" -name '*.java')

echo "[3/6] dex"
if [ "$(basename "$DEX")" = "d8" ]; then
    "$DEX" --min-api 23 --lib "$ANDROID_JAR" --output "$B" $(find "$B/classes" -name '*.class')
else
    "$DEX" --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"
fi

echo "[4/6] package"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[5/6] sign"
# Public sideload key, committed on purpose so every build from this repo can update an
# installed copy without uninstalling (an uninstall would not delete logs in Download/).
# It is NOT secret and must not be used for anything else.
KS=signing/mustangobd-sideload.jks
if [ ! -f "$KS" ]; then
    mkdir -p signing
    keytool -genkeypair -keystore "$KS" -storepass mustangobd -keypass mustangobd -alias mustangobd \
        -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Mustang OBD sideload key" >/dev/null
fi
OUT="dist/MustangOBD-$VERSION.apk"
apksigner sign --ks "$KS" --ks-key-alias mustangobd --ks-pass pass:mustangobd --key-pass pass:mustangobd \
    --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"

echo "[6/6] verify"
apksigner verify "$OUT"
sha256sum "$OUT" | tee "$OUT.sha256"
ls -l "$OUT"
