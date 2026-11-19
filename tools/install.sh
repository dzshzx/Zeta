#!/bin/bash
# 构建并装到手机。用法：tools/install.sh [--dev] [--no-build]
#   --dev  装测试版（com.dzshzx.zeta.dev，桌面名「Zeta 测试」），和用户日常用的正式版并存。开发测试一律用它。
#   不带   装正式版（com.dzshzx.zeta），只由负责合入的会话在合入后装：ZETA_PHONE_OWNER 不设或为 main 时才肯装。
# 在手机 Debian 里直接进 Android 一侧安装；在工作站上经 ssh（主机名取 ZETA_PHONE_SSH，默认 xiaomi-17-debian）。
# APK 从标准输入交给 pm install -S，两边走同一条路，不用先拷到手机上。
set -euo pipefail
cd "$(dirname "$0")/.."
variant=debug build=1
for arg in "$@"; do
  case $arg in
    --dev) variant=dev ;;
    --no-build) build= ;;
    *) echo "用法：tools/install.sh [--dev] [--no-build]" >&2; exit 2 ;;
  esac
done
if [[ $variant == debug && ${ZETA_PHONE_OWNER:-main} != main ]]; then
  echo "正式版只由负责合入的会话装（现在 ZETA_PHONE_OWNER=$ZETA_PHONE_OWNER）；开发测试装测试版：tools/install.sh --dev" >&2
  exit 2
fi
if [[ -n $build ]]; then
  # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
  tools/gradle --console=plain ":app:assemble${variant^}" 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/$variant/app-$variant.apk
[ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
cmd="pm install -r -S $(stat -c %s "$APK")"
root=(sudo -n timeout 300 nsenter -t 1 -m -- /system/bin/sh -c "$cmd")
if [[ $(uname -r) == *android* ]]; then
  "${root[@]}" <"$APK"
else
  tools/phone-lock
  ssh "${ZETA_PHONE_SSH:-xiaomi-17-debian}" "${root[*]@Q}" <"$APK"
fi
