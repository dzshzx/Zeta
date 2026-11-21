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

vd fake "$HERE/fake/stream.jsonl"
android "touch /data/local/tmp/zeta-stream-cpu.mark"
NEWER=/data/local/tmp/zeta-stream-cpu.mark
new_chat
pid=$(android "pidof $PKG" | tr -d '\r')
[[ -n $pid ]] || { echo "测试版没在跑"; exit 1; }

# threads：测试版每个线程的「线程号 名字 CPU 节拍（用户态加内核态）」
threads() {
  android "cd /proc/$pid/task && for t in *; do echo \"\$t \$(tr ' ' _ < \$t/comm) \$(sed 's/.*) //' \$t/stat | cut -d' ' -f12,13 | tr ' ' +)\"; done" |
    tr -d '\r' | awk '{ split($3, c, "+"); print $1, $2, c[1] + c[2] }'
}
# report <前> <后> <标题> <秒>：两次 threads 之间按线程名合计的增量（节拍是 10 毫秒），前 6 名
report() {
  awk -v title="$3" -v secs="$4" 'NR == FNR { a[$1] = $3; next } { d = $3 - a[$1]; if (d > 0) { by[$2] += d; total += d } }
    END { printf "%s（%s 秒）：合计 %d 毫秒；", title, secs, total * 10; n = 0; PROCINFO["sorted_in"] = "@val_num_desc"
      for (k in by) { if (n++ == 6) break; printf "%s %d，", k, by[k] * 10 } print "" }' "$1" "$2"
}
frames() { android "dumpsys gfxinfo $PKG | grep -m1 'Total frames rendered'" | tr -d '\r' | grep -o '[0-9]*$'; }

send warm
wait_result 1 60
sleep 3

android "dumpsys gfxinfo $PKG reset" >/dev/null
send shown
start=$(date +%s)
threads >shown-before
wait_result 2 90
threads >shown-after
shown="$(report shown-before shown-after 界面开着 $(($(date +%s) - start)))画了 $(frames) 帧"

send covered
sleep 0.5
android "am start --display $DISPLAY_ID -n com.android.settings/.Settings" >/dev/null
start=$(date +%s)
threads >covered-before
wait_result 3 90
threads >covered-after
covered=$(report covered-before covered-after 界面被盖住 $(($(date +%s) - start)))

android "rm -f /data/local/tmp/zeta-stream-cpu.mark"
printf '%s\n%s\n' "$shown" "$covered" | tee cpu.txt
