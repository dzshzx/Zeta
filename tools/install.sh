#!/bin/bash
# 构建并装到手机。用法：tools/install.sh [--dev] [--no-build]
#   --dev  装测试版，和用户日常用的正式版并存。开发测试一律用它。测试版有三份（com.dzshzx.zeta.dev、.dev2、.dev3，
#          桌面名「Zeta 测试」「Zeta 测试2」「Zeta 测试3」），装哪一份由手机租约给空着的那份（tools/phone-lock），
#          打印在第一行；之后的 vd.py --dev 自动用同一份。
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
on_phone=
[[ $(uname -r) == *android* ]] && on_phone=1
props=() package=com.dzshzx.zeta
if [[ $variant == dev ]]; then
  # 先拿租约：份号决定包名，要在构建前定下来。在手机上直接装时不协调，用 ZETA_DEV_SLOT（默认 1）
  if [[ -n $on_phone ]]; then slot=${ZETA_DEV_SLOT:-1}; else slot=$(tools/phone-lock dev); fi
  props=("-Pzeta.devSlot=$slot")
  package=com.dzshzx.zeta.dev$([[ $slot == 1 ]] || echo "$slot")
  echo "测试版第 $slot 份：$package"
fi
if [[ -n $build ]]; then
  # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
  tools/gradle --console=plain "${props[@]}" ":app:assemble${variant^}" 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/$variant/app-$variant.apk
[ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
# 包名对不上（例如 --no-build 时上次构建的是另一份）就不装，免得装错一份
aapt2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | tail -n 1 || true)
if [[ -n $aapt2 ]]; then
  built=$("$aapt2" dump packagename "$APK")
  [[ $built == "$package" ]] || { echo "$APK 是 $built 的包，这次要装 $package：去掉 --no-build 重新构建" >&2; exit 1; }
fi
cmd="pm install -r -S $(stat -c %s "$APK")"
root=(sudo -n timeout 300 nsenter -t 1 -m -- /system/bin/sh -c "$cmd")
if [[ -n $on_phone ]]; then
  "${root[@]}" <"$APK"
else
  # 构建可能排了几分钟队：装之前再续一次租约，份号变了说明租约过期被别人拿走了
  if [[ $variant == dev ]]; then
    [[ $(tools/phone-lock dev) == "$slot" ]] || { echo "构建期间第 $slot 份的租约过期，被别人拿走了；重跑一次" >&2; exit 75; }
  else
    tools/phone-lock main
  fi
  ssh "${ZETA_PHONE_SSH:-xiaomi-17-debian}" "${root[*]@Q}" <"$APK"
fi
