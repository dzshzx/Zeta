#!/bin/bash
# 冒烟：测试版装上后能冷启动、进程还在、截得到图。合入后装正式版前跑一次：tools/phone-run tools/phone-tests/smoke.sh
# 也是真机脚本的样例：vd、android、$PKG 由 tools/phone-run 提供，当前目录就是产物目录。
set -euo pipefail
vd shot start.png
android "pidof $PKG" >/dev/null || { echo "$PKG 冷启动后没在跑"; exit 1; }
