#!/bin/bash
# 工具确认弹层（对齐终端版的那套选项）在真界面上的三种回答，用假 Claude（fake/prompt.jsonl：调一次要确认的 Bash）：
#   第 1 轮点“允许”：这一轮照常做完；
#   第 2 轮写了理由再拒绝：交给 Claude 的是终端版那句加理由，这一轮接着做完（不打断）；
#   第 3 轮不写理由直接拒绝：和终端里按 Esc 一样，这一轮以 aborted_tools 停下，界面上是“已停止”（stopped.png）。
# 每轮弹层出来时截一张（ask-<n>.png）。应答的格式和 Claude Code 怎么理解它们由 PromptsTest、ClaudeInteractionContractTest 在工作站上验。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/prompt-sheet.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

# 弹层的按钮和理由输入框（整屏坐标，照 tools/shots 的 chat-permission 量的）
ALLOW_X=870 DENY_X=348 BUTTON_Y=2424 REASON_X=600 REASON_Y=2205

# ask <第几轮>：发消息，等弹层出来（日志里有了这一轮的 can_use_tool），截图
ask() {
  local n=$1 i count=0
  vd fake "$HERE/fake/prompt.jsonl"
  send "fake-ask-$n"
  for ((i = 0; i < 40; i++)); do
    [[ -n $log ]] || find_log
    [[ -n $log ]] && count=$(android "grep -c '\"subtype\":\"can_use_tool\"' $log" || true)
    ((${count:-0} >= n)) && break
    sleep 0.5
  done
  ((${count:-0} >= n)) || { vd shot "ask-$n-timeout.png"; echo "第 $n 轮没等到确认请求"; exit 1; }
  sleep 1.5 # 等弹层动画放完
  vd shot "ask-$n.png" >/dev/null
}

ask 1
vd tap $ALLOW_X $BUTTON_Y >/dev/null
wait_result 1 20 || { vd shot "turn-1-timeout.png"; exit 1; }
[[ $(jq -r .terminal_reason <<<"$RESULT") == completed ]] || { echo "允许后这一轮没做完：$RESULT"; exit 1; }

ask 2
vd tap $REASON_X $REASON_Y >/dev/null
sleep 1
vd text "use ls"
sleep 0.5
vd key 4 >/dev/null # 收起输入法，弹层回到原位
sleep 1
vd tap $DENY_X $BUTTON_Y >/dev/null
wait_result 2 20 || { vd shot "turn-2-timeout.png"; exit 1; }
[[ $(jq -r .terminal_reason <<<"$RESULT") == completed ]] || { echo "写了理由的拒绝不该打断：$RESULT"; exit 1; }
tool=$(android "grep '\"type\":\"tool_result\"' $log | tail -n 1")
jq -e '.message.content[0].content | test("To tell you how to proceed, the user said:\nuse ls$")' <<<"$tool" >/dev/null ||
  { echo "交给 Claude 的理由不对：$tool"; exit 1; }

ask 3
vd tap $DENY_X $BUTTON_Y >/dev/null
wait_result 3 20 || { vd shot "turn-3-timeout.png"; exit 1; }
[[ $(jq -r .terminal_reason <<<"$RESULT") == aborted_tools ]] || { echo "不写理由的拒绝应当停下这一轮：$RESULT"; exit 1; }
sleep 2 # 等界面跟上：列表末尾是“已停止”，输入栏回到空闲（麦克风，不是停止键）
vd shot stopped.png >/dev/null
echo "允许、写理由拒绝、直接拒绝三种都对"
