#!/bin/bash
# 构建 debug 包并装到手机。用法：tools/install.sh [--no-build]
# 在手机 Debian 里直接进 Android 一侧安装；在工作站上经 ssh（主机名取 ZETA_PHONE_SSH，默认 xiaomi-17-debian）。
# APK 从标准输入交给 pm install -S，两边走同一条路，不用先拷到手机上。
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${1:-}" != "--no-build" ]; then
  # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
  tools/gradle --console=plain :app:assembleDebug 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
cmd="pm install -r -S $(stat -c %s "$APK")"
root=(sudo -n timeout 300 nsenter -t 1 -m -- /system/bin/sh -c "$cmd")
if [[ $(uname -r) == *android* ]]; then
  "${root[@]}" <"$APK"
else
  ssh "${ZETA_PHONE_SSH:-xiaomi-17-debian}" "${root[*]@Q}" <"$APK"
fi
