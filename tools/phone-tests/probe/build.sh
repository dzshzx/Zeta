#!/bin/bash
# 编探针应用：probe/build.sh <输出的 apk>。用 Android SDK 的 build-tools（aapt2、d8、zipalign、apksigner）和
# tools/java-home 的 JDK，签名用每次现生成的一次性密钥（探针装上测完就卸载，不需要能覆盖升级）。
set -euo pipefail
out=$(realpath -m "$1")
src=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
bt=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
jar=$(find "$sdk/platforms" -name android.jar | sort -V | tail -n 1)
java_home=$("$src/../../java-home")
export PATH=$java_home/bin:$PATH
work=${out%.apk}-build
mkdir -p "$work/classes" "$work/dex"
javac -nowarn --release 11 -cp "$jar" -d "$work/classes" "$src/ProbeActivity.java" 2>&1 | grep -v '^warning\|^注: \|^Note: ' || true
"$bt/d8" --release --min-api 26 --lib "$jar" --output "$work/dex" $(find "$work/classes" -name '*.class')
"$bt/aapt2" link --manifest "$src/AndroidManifest.xml" -I "$jar" -o "$work/unsigned.apk"
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/probe.jks" -storepass probepass -keypass probepass -alias probe \
  -keyalg RSA -keysize 2048 -validity 365 -dname CN=probe >/dev/null 2>&1
"$bt/apksigner" sign --ks "$work/probe.jks" --ks-pass pass:probepass --out "$out" "$work/aligned.apk" 2>/dev/null
echo "$out"
