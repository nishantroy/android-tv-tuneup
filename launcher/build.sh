#!/usr/bin/env bash
# Builds launcher/build/tvhome.apk without Gradle.
#
#   launcher/build.sh                       build only
#   TV=<address> launcher/build.sh install  build, then install on the TV at <address>
#
# Needs a JDK (17 works) and the Android SDK's "platforms;android-28" and
# "build-tools;34.0.0" packages. Set ANDROID_HOME to the SDK if it is not in Homebrew's
# location, and JAVA_HOME if `javac` is not on your PATH.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-28/android.jar
brew_jdk=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
if [[ -z "${JAVA_HOME:-}" && -d $brew_jdk ]]; then export JAVA_HOME=$brew_jdk; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
[[ -f $PLAT ]] || { echo "Android platform 28 not found at $PLAT (set ANDROID_HOME)" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "Android build-tools 34.0.0 not found at $BT (set ANDROID_HOME)" >&2; exit 1; }
javac -version >/dev/null 2>&1 || { echo "No working JDK found (set JAVA_HOME)" >&2; exit 1; }

rm -rf build && mkdir -p build/gen build/classes
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$PLAT" --manifest AndroidManifest.xml --java build/gen -A assets build/res.zip
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$PLAT:$BT/core-lambda-stubs.jar" -d build/classes \
  src/local/tvhome/*.java build/gen/local/tvhome/R.java
"$BT/d8" --lib "$PLAT" --min-api 28 --output build build/classes/local/tvhome/*.class
(cd build && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# A signing key is created on the first build and kept (it is git-ignored), so later builds
# install over earlier ones. An APK signed with a different key will not install over an
# existing one: uninstall the old one first.
[[ -f debug.keystore ]] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/tvhome.apk build/aligned.apk
echo "built launcher/build/tvhome.apk ($(du -h build/tvhome.apk | cut -f1 | tr -d ' '))"
if [[ "${1:-}" == install ]]; then
  [[ -n "${TV:-}" ]] || { echo "Set TV to the TV's adb address, e.g. TV=192.168.1.50:5555 $0 install" >&2; exit 1; }
  adb -s "$TV" install -r build/tvhome.apk
fi
