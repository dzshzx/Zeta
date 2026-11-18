#!/bin/bash
# 构建 debug 包并安装到本机。用法：tools/install.sh [--no-build]
set -e
cd "$(dirname "$0")/.."
if [ "$1" != "--no-build" ]; then
  ./gradlew --no-daemon --console=plain :app:assembleDebug 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "no apk"; exit 1; }
sudo nsenter -t 1 -m -- /system/bin/sh -c "cp /proc/$$/root$(pwd)/$APK /data/local/tmp/zeta.apk && pm install -r /data/local/tmp/zeta.apk; rm -f /data/local/tmp/zeta.apk"
