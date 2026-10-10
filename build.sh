#!/bin/sh
# Gradle-less build of 「二维码贴背屏」 (aapt2 + javac + d8 + zipalign + apksigner), runs the JVM tests first.
#   sh build.sh  ->  build/rear-qr.apk   (OUT=/path/x.apk to also copy it there)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${SDK:-${ANDROID_HOME:-/opt/android-sdk}}
BT=${BT:-$SDK/build-tools/37.0.0}
PLAT=${PLAT:-$SDK/platforms/android-36/android.jar}
VERSION_CODE=${VERSION_CODE:-13}
VERSION_NAME=${VERSION_NAME:-0.1.12}
# signing key: local only, never committed (keystore/ is in .gitignore). Override with env vars, e.g.
#   KS=~/my.jks KS_ALIAS=me KS_PASS=env:MY_KS_PASS sh build.sh     (KS_PASS uses apksigner's pass:/env:/file: syntax)
KS=${KS:-$HERE/keystore/rearqr.jks}
KS_ALIAS=${KS_ALIAS:-rearqr}
KS_PASS=${KS_PASS:-file:$HERE/keystore/password.txt}
if [ ! -f "$KS" ]; then
    echo "no signing keystore at $KS - create one (see README «构建»), e.g.:" >&2
    echo "  mkdir -p keystore && keytool -genkeypair -keystore keystore/rearqr.jks -alias rearqr -keyalg RSA -keysize 2048 -validity 10000" >&2
    echo "  and put the store password into keystore/password.txt (or set KS / KS_ALIAS / KS_PASS)" >&2
    exit 1
fi
ZXING=$HERE/libs/core-3.5.4.jar
ZXING_SHA1=$(cat "$HERE/libs/core-3.5.4.jar.sha1")
B=$HERE/build
echo "$ZXING_SHA1  $ZXING" | sha1sum -c --quiet
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$B/gen" "$B/test"

# 1) JVM unit tests (pure-Java parts: QrLocator, ScreenRaw, RootShell, Focus, Stacks)
javac -nowarn -encoding UTF-8 -cp "$ZXING" -d "$B/test" \
    "$HERE/src/com/hhy/rearqr/QrLocator.java" "$HERE/src/com/hhy/rearqr/ScreenRaw.java" \
    "$HERE/src/com/hhy/rearqr/RootShell.java" "$HERE/src/com/hhy/rearqr/Focus.java" "$HERE/src/com/hhy/rearqr/Stacks.java" "$HERE/test/com/hhy/rearqr/Tests.java"
java -Djava.awt.headless=true -cp "$B/test:$ZXING" com.hhy.rearqr.Tests

# 2) APK
"$BT/aapt2" compile --dir "$HERE/res" -o "$B/res.zip"
"$BT/aapt2" link -I "$PLAT" --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 30 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$B/gen" -o "$B/unsigned.apk" "$B/res.zip"
# shellcheck disable=SC2046
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$PLAT:$BT/core-lambda-stubs.jar" \
    -classpath "$PLAT:$ZXING" -d "$B/classes" $(find "$HERE/src" "$B/gen" -name '*.java')
# shellcheck disable=SC2046
"$BT/d8" --release --min-api 30 --lib "$PLAT" --output "$B/dex" $(find "$B/classes" -name '*.class') "$ZXING"
(cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes*.dex)
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "$KS_PASS" \
    --out "$B/rear-qr.apk" "$B/aligned.apk"
"$BT/apksigner" verify --verbose "$B/rear-qr.apk" | head -6
"$BT/aapt2" dump badging "$B/rear-qr.apk" | head -3
[ -n "$OUT" ] && cp "$B/rear-qr.apk" "$OUT" && echo "copied to $OUT"
echo "built $B/rear-qr.apk"
