#!/bin/bash
# 冒烟：测试版装上后能冷启动、进程还在（画面看 phone-run 收尾截的 final.png）。合入前跑一次：
# tools/phone-run tools/phone-tests/smoke.sh。也是设备脚本的样例：vd、android、$PKG 由 phone-run 提供，当前目录就是产物目录。
set -euo pipefail
android "pidof $PKG" >/dev/null || { echo "$PKG 冷启动后没在跑"; exit 1; }
