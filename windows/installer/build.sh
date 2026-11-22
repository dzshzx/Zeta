#!/bin/bash
# 在 Linux 上打 Go 桌面版的 Windows 安装包：build.sh [输出目录]，输出 <输出目录>/Zeta-v<版本>.msi（默认 windows/build/），最后一行打印它的路径。
# 1. 版本从 git 推出来，算法同根目录 build.gradle.kts 的 commitCount、versionLabel（手机版和 Compose 桌面版用的那个）：
#    versionCode 是 HEAD 的提交数，发版 tag 上版本名是干净的 X.Y.Z，别的提交是 X.Y.(Z+1)-dev.N+g<提交号>；
#    经 -ldflags -X 写进 internal/update（mygo build 把 MYGO_LDFLAGS 加在自己的 -ldflags 后面），程序靠它显示版本、决定查不查更新。
# 2. go tool mygo build 编 windows/amd64 的 Zeta.exe（GUI 程序，图标取 mygo.json 的 installer/icon.png），不要 mygo 自己的 NSIS 安装包。
# 3. msi.sh 交给 wixl 打成 MSI。要先跑过 windows/scripts/assets.sh（嵌进程序的 zeta-helper 等）。
set -euo pipefail
windows=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$windows/build}
mkdir -p "$out"
out=$(cd "$out" && pwd)

code=$(git -C "$windows" rev-list --count HEAD)
hash=$(git -C "$windows" rev-parse --short=7 HEAD)
described=$(git -C "$windows" describe --tags --long --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true) # v0.7.1-3-gabc1234
if [[ $described =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-([0-9]+)-g[0-9a-f]+$ ]]; then
  major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} ahead=${BASH_REMATCH[4]}
  if [[ $ahead == 0 ]]; then
    version=$major.$minor.$patch
  else
    version=$major.$minor.$((patch + 1))-dev.$ahead+g$hash
  fi
else
  version=0.7.0-dev.$code+g$hash
fi

pkg=github.com/dzshzx/Zeta/windows/internal/update
(
  cd "$windows"
  MYGO_LDFLAGS="-X $pkg.versionName=$version -X $pkg.versionCode=$code" \
    go tool mygo build -platform windows/amd64 -skip-installer -o "$out/mygo" >&2
)
msi=$out/Zeta-v$version.msi
"$windows/installer/msi.sh" "$out/mygo/windows-amd64/Zeta.exe" "$version" "$msi"
echo "$msi"
