#!/bin/bash
# 结束对话（终端里的 /exit）：顶栏“更多”里的“结束对话”，一律先确认。假 Claude 答完一轮后点它，先弹确认，确认后背后的进程要收掉
# （托管目录没了）；再起一轮停在半路的，点它也先弹确认，点“结束”后进程也收掉。截图 idle-ended.png、confirm.png、running-ended.png 看界面换成空的新对话。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/end-conversation.sh
# 假 Claude 不写会话记录，没有要删的测试会话。坐标按整屏 1220×2656。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

new_chat

NEWER=/data/local/tmp/zeta-end-conversation.mark
android "touch $NEWER"

# 一、闲着的对话：也先问，确认后结束
vd fake "$HERE/fake/text.jsonl" >/dev/null
send end-idle
wait_result 1 20 || { vd shot idle-timeout.png >/dev/null; exit 1; }
host=${log%/out.jsonl}
sleep 1
menu_item $END_X $END_Y
wait_text '结束这个对话' 5 || { echo "闲着的对话没问就结束了"; vd shot idle-no-confirm.png >/dev/null; exit 1; }
android "[ -d $host ]" || { echo "还没确认，进程就被收掉了"; exit 1; }
vd tap $CONFIRM_X $CONFIRM_Y >/dev/null
gone "$host" || { vd shot idle-not-ended.png >/dev/null; exit 1; }
sleep 1
vd shot idle-ended.png >/dev/null
echo "闲着的对话：先问，确认后进程收掉了"

# 二、进行中的对话：先确认，确认后停掉
jq -nc '{title: "假 Claude · 停在半路", steps: [{text: "开始了。"}, {pause: 60000}, {text: "不该走到这里。"}]}' >long.jsonl
vd fake "$PWD/long.jsonl" >/dev/null
android "touch $NEWER"
log=''
send end-running
for _ in $(seq 40); do
  find_log
  [[ -n $log ]] && android "grep -q '\"type\":\"assistant\"' $log" && break
  sleep 0.5
done
[[ -n $log ]] || { echo "进行中的那一轮没起来"; vd shot running-timeout.png >/dev/null; exit 1; }
host=${log%/out.jsonl}
sleep 1
menu_item $END_X $END_Y
sleep 1.5
vd shot confirm.png >/dev/null
android "[ -d $host ]" || { echo "还没确认，进程就被收掉了"; exit 1; }
vd tap $CONFIRM_X $CONFIRM_Y >/dev/null
gone "$host" || { vd shot running-not-ended.png >/dev/null; exit 1; }
sleep 1
vd shot running-ended.png >/dev/null
echo "进行中的对话：确认后进程收掉了"
