#!/bin/bash
# Build skazhi.apk without Gradle (aapt2 -> javac -> d8 -> zipalign -> apksigner).
# Needs Android cmdline-tools (build-tools 34, platform 34) + a JDK.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"
rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip >/dev/null
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/base.apk build/res.zip \
    --min-sdk-version 28 --target-sdk-version 34
javac --release 11 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 28 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
# Release builds (CI) pass KEYSTORE/KS_PASS/KS_ALIAS; locally the debug key is used.
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
[ -n "${KEYSTORE:-}" ] || [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug" -keyalg RSA -validity 10000 >/dev/null 2>&1
KS_PASS="${KS_PASS:-android}" "$BT/apksigner" sign --ks "$KS" --ks-pass env:KS_PASS \
    --ks-key-alias "${KS_ALIAS:-androiddebugkey}" --out skazhi.apk build/aligned.apk
echo "built $(pwd)/skazhi.apk"
