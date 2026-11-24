#!/bin/bash
# 回答流式输出时测试版用多少 CPU：假 Claude（剧本 fake/stream.jsonl）照真实节奏流出约 1200 字（先一段思考，
# 正文每 30 毫秒 4 个字，和真 Claude 一轮的增量数相当），先在界面开着时答一轮，再把界面用系统设置盖住答一轮，
# 按线程记测试版的 CPU，并数界面开着那轮画了多少帧（dumpsys gfxinfo）。
# 数字随设备和负载波动，只用来比：改流式显示前后在同一台设备上各跑一次。两种设备都能跑。
#   ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/stream-cpu.sh
# 产物：cpu.txt（两轮的 CPU 合计和按线程名的前几名、界面开着那轮的帧数）。
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-stream-cpu.mark
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

vd fake "$HERE/fake/stream.jsonl"
android "touch $NEWER"
new_chat
pid=$(android "pidof $PKG" | tr -d '\r')
[[ -n $pid ]] || { echo "测试版没在跑"; exit 1; }

send warm
wait_result 1 60
sleep 3

android "dumpsys gfxinfo $PKG reset" >/dev/null
send shown
shown="$(measure_cpu 2 界面开着)画了 $(frames) 帧"

send covered
sleep 0.5
android "am start --display $DISPLAY_ID -n com.android.settings/.Settings" >/dev/null
for _ in 1 2 3 4 5 6; do [[ -z $(resumed) ]] && break; sleep 0.5; done
[[ -z $(resumed) ]] || { echo "系统设置没盖住测试版：$(resumed)"; exit 1; }
covered=$(measure_cpu 3 界面被盖住)

printf '%s\n%s\n' "$shown" "$covered" | tee cpu.txt
