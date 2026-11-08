#!/usr/bin/env bash
# Robolectric UI tests: the real MainActivity / LoggerService running on the Android
# framework inside the JVM (no phone, no emulator).
#   test/run_ui_tests.sh [SDK ...]      default: 23 28 31 32
# Needs: JDK 17+, gradle (only to fetch Robolectric from Maven Central), curl.
#
# Two pragmatic substitutions (documented, not hidden):
#  * androidx.test:monitor / espresso-idling-resource live only on Google's Maven; the
#    androidx-stubs/ folder has minimal stand-ins for the 24 classes Robolectric touches.
#  * Robolectric parses the app with its newest framework jar (API 35, ~150 MB). If that jar
#    is not in the cache, the tested SDK's own jar is used for it instead.
set -euo pipefail
cd "$(dirname "$0")/.."
unset JAVA_TOOL_OPTIONS || true
SDKS="${*:-23 28 31 32}"
C="${XDG_CACHE_HOME:-$HOME/.cache}/mustang_obd/robolectric"
ANDROID_JAR="${XDG_CACHE_HOME:-$HOME/.cache}/mustang_obd/android-all-14-robolectric-10818077.jar"
mkdir -p "$C/sdk"
[ -f build/base.apk ] && [ -f "$ANDROID_JAR" ] || ./build_apk.sh

ver() { case "$1" in
    23) echo 6.0.1_r3-robolectric-r1;; 26) echo 8.0.0_r4-robolectric-r1;; 28) echo 9-robolectric-4913185-2;;
    29) echo 10-robolectric-5803371;; 30) echo 11-robolectric-6757853;; 31) echo 12-robolectric-7732740;;
    32) echo 12.1-robolectric-8229987;; 33) echo 13-robolectric-9030017;; 34) echo 14-robolectric-10818077;;
    35) echo 15-robolectric-12650502;; *) echo "unsupported SDK $1" >&2; exit 1;; esac; }

fetch() {   # android-all-instrumented jar for an SDK into the cache (sha1-checked, retries)
    local f="$C/sdk/android-all-instrumented-$(ver "$1")-i7.jar"
    [ -f "$f" ] && return 0
    local u="https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$(ver "$1")-i7/$(basename "$f")"
    for i in 1 2 3 4; do
        if curl -fsL -o "$f.part" "$u" && [ "$(curl -fsL "$u.sha1" | cut -c1-40)" = "$(sha1sum "$f.part" | cut -c1-40)" ]; then
            mv "$f.part" "$f"; return 0; fi
        rm -f "$f.part"; echo "  download of SDK $1 jar failed (attempt $i), retrying..."; sleep $((i * 30))
    done
    return 1
}

if [ ! -d "$C/libs" ]; then
    mkdir -p "$C/resolve"
    cat > "$C/resolve/build.gradle" <<'G'
plugins { id 'java' }
repositories { mavenCentral() }
configurations.all {
    exclude group: 'androidx.test'; exclude group: 'androidx.test.espresso'
    exclude module: 'nativeruntime-dist-compat'
}
dependencies { testImplementation 'org.robolectric:robolectric:4.14.1'; testImplementation 'junit:junit:4.13.2' }
tasks.register('libs', Copy) { from configurations.testRuntimeClasspath; into 'libs' }
G
    echo "rootProject.name='uitest'" > "$C/resolve/settings.gradle"
    (cd "$C/resolve" && gradle -q --no-daemon libs) && mv "$C/resolve/libs" "$C/libs"
fi
LIBS="$(ls "$C"/libs/*.jar | tr '\n' ':')"

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/stubs" "$W/test" "$W/cfg/com/android/tools" "$W/assets"
javac -nowarn --release 17 -cp "$ANDROID_JAR" -d "$W/stubs" $(find test/robolectric/androidx-stubs -name '*.java')
javac -nowarn --release 17 -cp "$LIBS$ANDROID_JAR:$W/stubs:build/classes" -d "$W/test" test/robolectric/UiTest.java
cat > "$W/cfg/com/android/tools/test_config.properties" <<P
android_merged_manifest=$PWD/AndroidManifest.xml
android_merged_assets=$W/assets
android_resource_apk=$PWD/build/base.apk
android_custom_package=io.github.borohue.mustangobd
P

FAIL=0
for SDK in $SDKS; do
    fetch "$SDK" || { echo "SDK $SDK: framework jar unavailable - skipped"; FAIL=1; continue; }
    D="$W/deps-$SDK"
    mkdir -p "$D"
    ln -s "$C/sdk/android-all-instrumented-$(ver "$SDK")-i7.jar" "$D/"
    NEWEST="android-all-instrumented-$(ver 35)-i7.jar"
    if [ -f "$C/sdk/$NEWEST" ]; then ln -sf "$C/sdk/$NEWEST" "$D/$NEWEST"
    else ln -sf "$C/sdk/android-all-instrumented-$(ver "$SDK")-i7.jar" "$D/$NEWEST"; fi
    echo "sdk=$SDK" > "$W/cfg/robolectric.properties"
    echo "== Robolectric, Android API $SDK"
    set +e
    java --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \
        --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
        -Drobolectric.enabledSdks="$SDK" -Drobolectric.offline=true -Drobolectric.dependency.dir="$D" \
        -cp "$LIBS$W/stubs:build/classes:$W/test:$W/cfg:$ANDROID_JAR" \
        org.junit.runner.JUnitCore io.github.borohue.mustangobd.UiTest > "$W/out-$SDK.txt" 2>&1
    RC=$?
    set -e
    grep -vE "^\s+at (org\.junit|java\.|jdk\.|sun\.|com\.google|org\.robolectric\.internal)" "$W/out-$SDK.txt" \
        | grep -v "^ERROR" || true
    if [ $RC -ne 0 ] || ! grep -qE "^OK \([1-9][0-9]* tests\)" "$W/out-$SDK.txt"; then FAIL=1; fi
done
exit $FAIL
