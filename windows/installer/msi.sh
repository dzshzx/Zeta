#!/bin/bash
# 把编好的 exe 打成 MSI：msi.sh <exe> <版本> <输出的 .msi>。平时经 build.sh 调用；installer_test.go 拿一个假的 exe 调它，核对打出来的表。
# 版本名是干净的 X.Y.Z 时打正式版 Zeta，别的（0.7.2-dev.3+gabc1234）打和它并存的测试版 Zeta Dev（同 internal/update 的
# Version.Release；两个版本的身份写在 Zeta.wxs 开头）。
# 要 wixl（Debian/Ubuntu 上 apt install wixl，读 MSI 的 msiinfo 在 msitools 包里）。
set -euo pipefail
exe=$1 version=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
command -v wixl >/dev/null || {
  echo "msi.sh: 缺 wixl（Debian/Ubuntu 上 sudo apt install wixl msitools）" >&2
  exit 1
}
variant=dev
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && variant=release
# MSI 的版本只认 X.Y.Z 三段数字：预发布（0.7.2-dev.3+gabc1234）取前面的 0.7.2
wixl -a x64 -D "Variant=$variant" -D "Version=${version%%-*}" -D "Exe=$exe" -D "Here=$here" -o "$out" "$here/Zeta.wxs"
