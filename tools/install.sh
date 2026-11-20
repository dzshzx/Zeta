#!/bin/bash
# 构建并安装。用法：tools/install.sh [--release [--minor]] [--no-build]
#   不带      装测试版（com.dzshzx.zeta.dev，桌面名「Zeta 测试」），默认装到本机的模拟器（没开就由 tools/emu 开机），
#             ZETA_DEVICE=phone 时装到手机，和用户日常用的正式版并存。开发测试一律用它，一般经 tools/phone-run 调。
#             版本号跟着提交走，比设备上的旧也照装（任务分支可能比别人的旧）。
#   --release 上线：装正式版（com.dzshzx.zeta），只在用户说上线时由主会话装，ZETA_PHONE_OWNER 不设或为 main 时才肯装。
#             要在 master 的最新提交上、工作区干净；HEAD 还没有上线 tag 就在本地打下一个版本（升 patch），
#             构建、核对、装好后才推到 origin，中途失败就删掉这个本地 tag（推上去的 tag 不能改）。HEAD 已有 tag 就重装这个版本。
#   --minor   上线时升 minor（0.7.3 → 0.8.0），用户说了才用。
# 正式版只装在手机上：在手机 Debian 里直接进 Android 一侧安装，在工作站上经 ssh（主机名取 ZETA_PHONE_SSH，默认 xiaomi-17-debian）。
# APK 从标准输入交给 pm install -S，手机、模拟器都走这一条路，不用先拷到设备上。
set -euo pipefail
cd "$(dirname "$0")/.."
variant=dev build=1 minor= created=
for arg in "$@"; do
  case $arg in
    --release) variant=debug ;;
    --no-build) build= ;;
    --minor) minor=1 ;;
    *) echo "用法：tools/install.sh [--release [--minor]] [--no-build]" >&2; exit 2 ;;
  esac
done
if [[ -n $minor && $variant == dev ]]; then echo "--minor 只用于上线（和 --release 一起用）" >&2; exit 2; fi
on_phone=
[[ $(uname -r) == *android* ]] && on_phone=1
device=${ZETA_DEVICE:-emu}
[[ $device == emu || $device == phone ]] || { echo "ZETA_DEVICE 只能是 emu 或 phone（现在是 $device）" >&2; exit 2; }
[[ $variant == dev && -z $on_phone ]] || device=phone # 正式版只在手机上
lease=main
[[ $variant == dev ]] && lease=$([[ $device == emu ]] && echo emu || echo dev)
# 谁能装哪个包由 tools/phone-lock 定；构建前先问一次，被拒绝时不白构建
[[ -n $on_phone ]] || tools/phone-lock --check "$lease" || exit 2
package=com.dzshzx.zeta
[[ $variant == dev ]] && package=com.dzshzx.zeta.dev

tag=
if [[ $variant == debug ]]; then
  # 上线：正式版的版本就是 HEAD 上的 tag，构建时 app/build.gradle.kts 从 git describe 取
  match='v[0-9]*.[0-9]*.[0-9]*'
  git fetch -q origin master --tags
  if [[ $(git rev-parse HEAD) != "$(git rev-parse origin/master)" ]]; then
    echo "上线要在 origin/master 的最新提交上（现在是 $(git rev-parse --short HEAD)）：先同步" >&2
    exit 1
  fi
  if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
    echo "工作区有没提交的改动，正式版要和 master 一致" >&2
    exit 1
  fi
  tag=$(git describe --tags --exact-match --match "$match" HEAD 2>/dev/null || true)
  if [[ -z $tag ]]; then
    last=$(git describe --tags --abbrev=0 --match "$match" 2>/dev/null || true)
    if [[ -z $last ]]; then
      next=0.7.0
    else
      IFS=. read -r major mid patch <<<"${last#v}"
      if [[ -n $minor ]]; then next=$major.$((mid + 1)).0; else next=$major.$mid.$((patch + 1)); fi
    fi
    tag=v$next
    git tag -a "$tag" -m "上线 $next"
    created=$tag
    trap '[[ -z $created ]] || { git tag -d "$created" >/dev/null; echo "没装成，删掉了本地的 $created" >&2; }' EXIT
    echo "打了 $tag（上一个是 ${last:-无}），装好后推到 origin"
  elif [[ -n $minor ]]; then
    echo "HEAD 已经是 $tag，没有新提交可以升 minor" >&2
    exit 1
  else
    echo "HEAD 已经是 $tag，重装这个版本"
  fi
fi

if [[ -n $build ]]; then
  # 构建失败就停，不装旧包；输出只留错误、Kotlin 警告和结果行。
  tools/gradle --console=plain ":app:assemble${variant^}" 2>&1 \
    | grep -E '^e: |^w: .*\.kt|BUILD|FAILED|What went wrong|error:' -A3 | cut -c1-400
fi
APK=app/build/outputs/apk/$variant/app-$variant.apk
[ -f "$APK" ] || { echo "没有 $APK" >&2; exit 1; }
# 包名对不上（例如 --no-build 时上次构建的是另一个变体）就不装；上线时版本名也要和 tag 一致
aapt2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | tail -n 1 || true)
if [[ -n $aapt2 ]]; then
  built=$("$aapt2" dump packagename "$APK")
  [[ $built == "$package" ]] || { echo "$APK 是 $built 的包，这次要装 $package：去掉 --no-build 重新构建" >&2; exit 1; }
  if [[ -n $tag ]]; then
    name=$("$aapt2" dump badging "$APK" | grep -oE "versionName='[^']*'" | cut -d"'" -f2)
    [[ $name == "${tag#v}" ]] || { echo "$APK 的版本是 $name，不是 ${tag#v}：去掉 --no-build 重新构建" >&2; exit 1; }
  fi
fi
# 测试版可以比手机上的旧（-d，debug 包才允许）：任务分支的提交数可能比别人刚装的少
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
  ssh "${ZETA_PHONE_SSH:-xiaomi-17-debian}" "${root[*]@Q}" <"$APK"
fi
if [[ -n $tag ]]; then
  created=
  # 本地有、origin 上还没有（这次新打的，或上回推送失败的）就推上去
  git ls-remote --exit-code -q origin "refs/tags/$tag" >/dev/null || git push -q origin "$tag"
  echo "正式版已是 ${tag#v}"
fi
