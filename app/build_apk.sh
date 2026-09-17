#!/bin/bash
##########################################################################################
# futureharmony.tb522fu.aon APK build pipeline (aapt2 + kotlinc + d8 + apksigner)
# Kotlin 2.2.20 (app/tools/kotlinc), joint compilation with the Xposed Java stubs.
# Usage: ./build_apk.sh          -> build/aon.apk (signed)
#        ./build_apk.sh install  -> build + adb install -r -g
##########################################################################################
set -e
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/build"
KS="$DIR/aon_release.keystore"
KOTLINC="$DIR/tools/kotlinc/bin/kotlinc"
KSTDLIB="$DIR/tools/kotlinc/lib/kotlin-stdlib.jar"

if [ ! -x "$KOTLINC" ]; then
  echo "ERROR: kotlinc not found at $KOTLINC" >&2
  echo "Run: cd tools && curl -sL -o kc.zip https://github.com/JetBrains/kotlin/releases/download/v2.2.20/kotlin-compiler-2.2.20.zip && unzip -q kc.zip" >&2
  exit 1
fi

rm -rf "$OUT/obj"; mkdir -p "$OUT/obj" "$OUT/gen"

# 1) compile resources + link manifest
mkdir -p "$OUT/compiled_res"
"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/compiled_res/res.zip"
"$BT/aapt2" link --manifest "$DIR/AndroidManifest.xml" \
    -R "$OUT/compiled_res/res.zip" \
    --auto-add-overlay \
    --min-sdk-version 31 --target-sdk-version 36 \
    --java "$OUT/gen" \
    -I "$PLATFORM" -o "$OUT/base.apk"

# 2) joint compile kotlin + java (xposed stubs)
SOURCES=$(find "$DIR/kotlin" -name "*.kt"; find "$DIR/java" -name "*.java")
"$KOTLINC" -jvm-target 11 -nowarn -cp "$PLATFORM" -d "$OUT/obj" $SOURCES

# 3) dex (program classes + kotlin-stdlib; exclude the compile-only Xposed stubs)
CLASSES=$(find "$OUT/obj" -name "*.class" | grep -v "/de/robv/android/xposed/")
"$SDK/cmdline-tools/latest/bin/d8" --release --lib "$PLATFORM" --lib "$KSTDLIB" \
    --min-api 31 --output "$OUT" $CLASSES "$KSTDLIB"

# 4) pack dex + native helper into apk (headless: no xposed_init, no UI resources)
mkdir -p "$OUT/lib/arm64-v8a"
cp "$DIR/jniLibs/arm64-v8a/libaon.so" "$OUT/lib/arm64-v8a/libaon.so"
(cd "$OUT" && zip -q base.apk classes.dex lib/arm64-v8a/libaon.so)

# 5) align + sign
rm -f "$OUT/aon.apk"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aon.apk"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias aon -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass aonkeep2026 -keypass aonkeep2026 \
    -dname "CN=futureharmony,OU=TB522FU,O=System,C=CN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:aonkeep2026 \
    --key-pass pass:aonkeep2026 --out "$OUT/aon.apk" "$OUT/aon.apk"

echo "OK: $OUT/aon.apk"
cp -f "$OUT/aon.apk" "$DIR/../magisk_module/aon.apk"
echo "Synced to: $DIR/../magisk_module/aon.apk"

# 6) optional install
if [ "$1" = "install" ]; then
  adb install -r -g "$OUT/aon.apk"
fi
