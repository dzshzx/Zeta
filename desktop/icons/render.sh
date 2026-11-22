#!/bin/bash
# 从 zeta.svg 生成 Windows 安装包和快捷方式用的 zeta.ico（16–256 多尺寸），和窗口标题栏、任务栏用的 zeta.png。
# 要 rsvg-convert 和 ImageMagick；生成的文件提交进仓，构建不跑这个脚本。
set -euo pipefail
cd "$(dirname "$0")"
tmp=$(mktemp -d)
for s in 16 24 32 48 64 128 256; do rsvg-convert -w "$s" -h "$s" zeta.svg -o "$tmp/$s.png"; done
magick "$tmp"/{16,24,32,48,64,128,256}.png zeta.ico
cp "$tmp/256.png" ../src/jvmMain/resources/zeta.png
