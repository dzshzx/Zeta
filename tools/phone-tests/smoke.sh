#!/bin/bash
# 冒烟：测试版装上后能冷启动、进程还在（画面看 phone-run 收尾截的 final.png）。发版时 tools/release ship 照正式版那样
# 经 R8 构建跑它（ZETA_R8=1 tools/phone-run tools/phone-tests/smoke.sh，见 docs/release.md）。合入时不跑，门禁里的 AppStartTest 查不经 R8 的启动。也是设备脚本的样例：vd、android、$PKG 由 phone-run 提供，当前目录就是产物目录。
set -euo pipefail
android "pidof $PKG" >/dev/null || { echo "$PKG 冷启动后没在跑"; exit 1; }
