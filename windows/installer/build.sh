#!/bin/bash
# 在 Linux 上打 Go 桌面版的 Windows 安装包：build.sh [输出目录]，输出 <输出目录>/Zeta-v<版本>.msi（默认 windows/build/），最后一行打印它的路径。
# 1. 版本从 git 推出来（scripts/version.sh，算法同手机版）：
#    经 -ldflags -X 写进 internal/update（mygo build 把 MYGO_LDFLAGS 加在自己的 -ldflags 后面），程序靠它显示版本、决定查不查更新。
# 2. go tool mygo build 编 windows/amd64 的 exe（GUI 程序），不要 mygo 自己的 NSIS 安装包。
#    版本名是干净的 X.Y.Z 时是正式版：照 windows/mygo.json 编出 Zeta.exe。别的是测试版（同 internal/update 的 Version.Release），
#    是和正式版并存的另一个应用，照 mygo dev 给开发版的做法换身份：名字 Zeta Dev、identifier 加 .dev、图标换 installer/icon-dev.png。
#    Windows 上 mygo 从 exe 的版本资源读名字和 identifier（ProductName、MyGoIdentifier：单实例锁、mygo 存窗口位置和语言的目录都按它们分开），
#    版本资源是 mygo build 照项目目录里的 mygo.json 写的，-ldflags 改不了；mygo build 没有换配置文件的参数，又要求 i18n.dir 在项目目录里面，
#    所以在临时目录里搭一个 windows/ 的替身：各项都链接到真的，只有 mygo.json 是改过身份的一份（要 jq）。
# 3. msi.sh 交给 wixl 打成 MSI（两个版本的 MSI 身份见 Zeta.wxs 开头）。要先跑过 windows/scripts/assets.sh（嵌进程序的 zeta-helper 等）。
set -euo pipefail
windows=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$windows/build}
mkdir -p "$out"
out=$(cd "$out" && pwd)

read -r code version < <(bash "$windows/scripts/version.sh")

project=$windows name=Zeta
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  command -v jq >/dev/null || {
    echo "build.sh: 打测试版要 jq（sudo apt install jq）" >&2
    exit 1
  }
  name="Zeta Dev"
  stage=$(mktemp -d)
  trap 'rm -rf "$stage"' EXIT # 里面只有链接和一份 mygo.json，rm 不跟着链接走
  # windows/go.mod 的 replace 指着 ../client、../helper，替身旁边也要有
  ln -s "$windows/../client" "$windows/../helper" "$stage/"
  mkdir "$stage/windows"
  for f in "$windows"/*; do
    [[ $(basename "$f") == mygo.json ]] || ln -s "$f" "$stage/windows/"
  done
  jq --arg name "$name" '.name = $name | .identifier += ".dev" | .slug += "-dev" | .icon |= sub("\\.png$"; "-dev.png")' \
    "$windows/mygo.json" >"$stage/windows/mygo.json"
  project=$stage/windows
fi

pkg=github.com/dzshzx/Zeta/windows/internal/update
(
  cd "$windows"
  MYGO_LDFLAGS="-X $pkg.versionName=$version -X $pkg.versionCode=$code" \
    go tool mygo build -platform windows/amd64 -skip-installer -o "$out/mygo" "$project" >&2
)
msi=$out/Zeta-v$version.msi
"$windows/installer/msi.sh" "$out/mygo/windows-amd64/$name.exe" "$version" "$msi"
echo "$msi"
