#!/bin/bash
# 把编好的 Zeta.exe 打成 MSI：msi.sh <Zeta.exe> <版本> <输出的 .msi>。平时经 build.sh 调用；installer_test.go 拿一个假的 exe 调它，核对打出来的表。
# 要 wixl（Debian/Ubuntu 上 apt install wixl，读 MSI 的 msiinfo 在 msitools 包里）。
set -euo pipefail
exe=$1 version=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
command -v wixl >/dev/null || {
  echo "msi.sh: 缺 wixl（Debian/Ubuntu 上 sudo apt install wixl msitools）" >&2
  exit 1
}
# MSI 的版本只认 X.Y.Z 三段数字：预发布（0.7.2-dev.3+gabc1234）取前面的 0.7.2
wixl -a x64 -D "Version=${version%%-*}" -D "Exe=$exe" -D "IconFile=$here/zeta.ico" -o "$out" "$here/Zeta.wxs"
