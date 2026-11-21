#!/bin/bash
# 装机。用法：tools/install.sh [--no-build] | tools/install.sh --release [vX.Y.Z]
#   不带      构建并装测试版（com.dzshzx.zeta.dev，桌面名「Zeta 测试」），默认装到本机的模拟器（没开就由 tools/emu 开机），
#             ZETA_DEVICE=phone 时装到手机，和用户日常用的正式版并存。开发测试一律用它，一般经 tools/phone-run 调。
#             版本号跟着提交走，比设备上的旧也照装（任务分支可能比别人的旧）。--no-build 装上次构建的包。
#   --release 上线：把 GitHub Release 上的正式版（com.dzshzx.zeta）装到手机，不给 tag 就装最新的 Release。
#             只在用户说上线时由主会话装：命令前要写明 ZETA_PHONE_OWNER=main，没设或是别的名字都不装。不在本机构建、不打 tag（发版见 docs/release.md）：
#             用 gh release download 取 APK、.sha256 和 update.json，经 tools/release check-download 核对 sha256、
#             签名证书（本仓 release/signing-certs.txt 登记的）、包名和版本后再装。要 gh、go 和 Android SDK 的 build-tools。
# 正式版只装在手机上：在手机 Debian 里直接进 Android 一侧安装，在工作站上经 ssh（手机 Debian 的别名取 ZETA_PHONE_SSH 或 git config zeta.phoneSsh）。
# APK 从标准输入交给 pm install -S，手机、模拟器都走这一条路，不用先拷到设备上。
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/config.sh
variant=dev build=1 tag=
usage() { echo "用法：tools/install.sh [--no-build] | tools/install.sh --release [vX.Y.Z]" >&2; exit 2; }
for arg in "$@"; do
  case $arg in
    --release) variant=release ;;
    --no-build) build= ;;
    v[0-9]*.[0-9]*.[0-9]*) tag=$arg ;;
    *) usage ;;
  esac
done
[[ $variant == release && -n $build || $variant == dev && -z $tag ]] || usage # --no-build 只给测试版，tag 只给 --release
on_phone=
[[ $(uname -r) == *android* ]] && on_phone=1
device=${ZETA_DEVICE:-emu}
[[ $device == emu || $device == phone ]] || { echo "ZETA_DEVICE 只能是 emu 或 phone（现在是 $device）" >&2; exit 2; }
[[ $variant == dev && -z $on_phone ]] || device=phone # 正式版只在手机上
lease=main
[[ $variant == dev ]] && lease=$([[ $device == emu ]] && echo emu || echo dev)
# 谁能装哪个包由 tools/phone-lock 定；构建或下载前先问一次，被拒绝时不白做。手机 Debian 里不拿租约，装正式版照样先问
[[ -n $on_phone && $lease != main ]] || tools/phone-lock --check "$lease" || exit 2
phone=
[[ -n $on_phone || $device != phone ]] || phone=$(zeta_phone_ssh) # 工作站上连手机：别名没配就在构建前停下

if [[ $variant == release ]]; then
  # 上线：装 Release 上的包，核对通过才装；check-download 在标准输出打印核对过的 APK 路径
  dl=$(mktemp -d)
  trap 'rm -rf "$dl"' EXIT
  gh release download ${tag:+"$tag"} --repo dzshzx/Zeta --dir "$dl" \
    --pattern 'Zeta-v*.apk' --pattern 'Zeta-v*.apk.sha256' --pattern update.json
  APK=$(JAVA_HOME=$(tools/java-home) go -C tools/release run . check-download -dir "$dl")
else
  if [[ -n $build ]]; then
    # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
    tools/gradle --console=plain :app:assembleDebug 2>&1 \
      | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
  fi
  APK=app/build/outputs/apk/debug/app-debug.apk
  [ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
  # 包名对不上（例如上次构建被别的变体覆盖）就不装
  aapt2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | tail -n 1 || true)
  if [[ -n $aapt2 ]]; then
    built=$("$aapt2" dump packagename "$APK")
    [[ $built == com.dzshzx.zeta.dev ]] || { echo "$APK 是 $built 的包，不是测试版：去掉 --no-build 重新构建" >&2; exit 1; }
  fi
fi
# 测试版可以比手机上的旧（-d）：任务分支的提交数可能比别人刚装的少。手机上只有可调试的包能这样装，ZETA_R8=1 构建的不行
cmd="pm install -r $([[ $variant == dev ]] && echo -d) -S $(stat -c %s "$APK")"
root=(sudo -n timeout 300 nsenter -t 1 -m -- /system/bin/sh -c "$cmd")
if [[ -n $on_phone ]]; then
  "${root[@]}" <"$APK"
elif [[ $device == emu ]]; then
  tools/phone-lock "$lease" # 先构建，装之前才拿租约：构建的几分钟不占设备；拿到再开模拟器
  tools/emu start
  "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" -s emulator-5554 shell -T "timeout 300 $cmd" <"$APK"
else
  tools/phone-lock "$lease"
  ssh "$phone" "${root[*]@Q}" <"$APK"
fi
[[ $variant == dev ]] || echo "正式版已是 $(basename "$APK" .apk)"
