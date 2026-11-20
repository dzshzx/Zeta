#!/bin/bash
# 结束对话（终端里的 /exit）：顶栏“更多”里的“结束对话”。假 Claude 答完一轮后点它，背后的进程要收掉（托管目录没了）；
# 再起一轮停在半路的，点它先弹确认，点“结束”后进程也收掉。截图 idle-ended.png、confirm.png、running-ended.png 看界面换成空的新对话。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/end-conversation.sh
# 假 Claude 不写会话记录，没有要删的测试会话。坐标按整屏 1220×2656。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

MORE_X=1102 MORE_Y=84         # 顶栏右边的“更多”
NEW_X=863 NEW_Y=268           # 菜单第一项“新对话”
END_X=863 END_Y=398           # 菜单第二项“结束对话”
CONFIRM_X=866 CONFIRM_Y=2430  # 确认框右边的“结束”

end_conversation() {
  vd tap $MORE_X $MORE_Y >/dev/null
  sleep 1
  vd tap $END_X $END_Y >/dev/null
}

# gone <托管目录>：10 秒内目录没了（zeta_host.sh stop 收掉进程后删掉它）
gone() {
  for _ in $(seq 20); do
    android "[ -d $1 ]" || return 0
    sleep 0.5
  done
  echo "托管目录还在：$1"
  return 1
}

# 冷启动可能停在别的对话上：先开新对话
vd tap $MORE_X $MORE_Y >/dev/null
sleep 1
vd tap $NEW_X $NEW_Y >/dev/null
sleep 1.5

NEWER=/data/local/tmp/zeta-end-conversation.mark
android "touch $NEWER"

# 一、闲着的对话：直接结束，不问
vd fake "$HERE/fake/text.jsonl" >/dev/null
send end-idle
wait_result 1 20 || { vd shot idle-timeout.png >/dev/null; exit 1; }
host=${log%/out.jsonl}
sleep 1
end_conversation
gone "$host" || { vd shot idle-not-ended.png >/dev/null; exit 1; }
sleep 1
vd shot idle-ended.png >/dev/null
echo "闲着的对话：结束后进程收掉了"

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
end_conversation
sleep 1.5
vd shot confirm.png >/dev/null
android "[ -d $host ]" || { echo "还没确认，进程就被收掉了"; exit 1; }
vd tap $CONFIRM_X $CONFIRM_Y >/dev/null
gone "$host" || { vd shot running-not-ended.png >/dev/null; exit 1; }
sleep 1
vd shot running-ended.png >/dev/null
echo "进行中的对话：确认后进程收掉了"
