#!/bin/bash
# 构建并装到手机。用法：tools/install.sh [--dev] [--no-build]
#   --dev  装测试版（com.dzshzx.zeta.dev，桌面名「Zeta 测试」），和用户日常用的正式版并存。开发测试一律用它，
#          一般经 tools/phone-run 调。
#   不带   装正式版（com.dzshzx.zeta），只在用户说上线时由主会话装：ZETA_PHONE_OWNER 不设或为 main 时才肯装。
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
  echo "正式版只在上线时由主会话装（现在 ZETA_PHONE_OWNER=$ZETA_PHONE_OWNER）；开发测试装测试版：tools/install.sh --dev" >&2
  exit 2
fi
on_phone=
[[ $(uname -r) == *android* ]] && on_phone=1
package=com.dzshzx.zeta
[[ $variant == dev ]] && package=com.dzshzx.zeta.dev
if [[ -n $build ]]; then
  # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
  tools/gradle --console=plain ":app:assemble${variant^}" 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/$variant/app-$variant.apk
[ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
# 包名对不上（例如 --no-build 时上次构建的是另一个变体）就不装
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
  # 先构建，装之前才拿租约：构建的几分钟不占手机
  tools/phone-lock "$([[ $variant == dev ]] && echo dev || echo main)"
  ssh "${ZETA_PHONE_SSH:-xiaomi-17-debian}" "${root[*]@Q}" <"$APK"
fi
