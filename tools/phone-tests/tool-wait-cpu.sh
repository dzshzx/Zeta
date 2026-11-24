#!/bin/bash
# 一轮里等工具跑完、屏上没有新字的这段时间，测试版用多少 CPU、画了多少帧：假 Claude（剧本 fake/tool-wait.jsonl）
# 先说一句，再跑一个 15 秒的 Bash 工具，最后说一句；界面开着，聊天页上只有进行中的提示（转圈、呼吸的那一行）在动。
# 数字随设备和负载波动，只用来比：改这些提示的动画前后在同一台设备上各跑一次；帧数要看手机（模拟器卡在 25 帧每秒上下）。
#   ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/tool-wait-cpu.sh
# 产物：cpu.txt（这一轮的 CPU 合计、按线程名的前几名和帧数）。
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-tool-wait-cpu.mark
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

vd fake "$HERE/fake/tool-wait.jsonl"
android "touch $NEWER"
new_chat
pid=$(android "pidof $PKG" | tr -d '\r')
[[ -n $pid ]] || { echo "测试版没在跑"; exit 1; }

send warm
wait_result 1 60
sleep 3

android "dumpsys gfxinfo $PKG reset" >/dev/null
send tool
echo "$(measure_cpu 2 等工具)画了 $(frames) 帧" | tee cpu.txt
