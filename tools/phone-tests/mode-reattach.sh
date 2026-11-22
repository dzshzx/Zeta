#!/bin/bash
# 接回进程时恢复用户在对话里定的权限模式：测试版换用假 Claude（剧本 fake/mode-reattach.jsonl），新对话在输入栏切到计划模式、
# 发一条；回答到一半（剧本在这里等标记）强行停掉测试版（托管的 Claude Code 进程不随应用退出），冷启动后核心推来它还在回答，
# Zeta 按会话号把它打开放在后台（hub.openRunning，不换当前页面）；脚本按会话号切到它，这时才放标记；等这一轮答完再发一条。
# 核对假 Claude 两轮开头 init 报的权限模式都是计划模式，没被换成 Claude Code 的默认。
# 截图 reattached.png 是接回后的聊天页，输入栏的模式按钮应是计划模式。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/mode-reattach.sh
# 假 Claude 不写会话记录，没有要删的测试会话。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-mode-reattach.mark
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

default=$(claude_default_mode)
echo "Claude Code 的默认权限模式：$default"
[[ $default != plan ]] || { echo "Claude Code 的默认就是计划模式，分不出恢复的和默认的"; exit 1; }
android "touch $NEWER"
vd fake "$HERE/fake/mode-reattach.jsonl"
vd launch
sleep 10 # 冷启动后马上输入会丢字

choose_mode plan
send "fake-reattach-1"
# 等假 Claude 开始回答（剧本先说一句，再等标记）
for _ in $(seq 30); do
  find_log
  [[ -n $log ]] && android "grep -q '\"type\":\"assistant\"' $log" && break
  sleep 0.5
done
[[ -n $log ]] || { echo "没找到这次假 Claude 的日志"; exit 1; }
android "am force-stop $PKG"
sleep 1
vd launch
sleep 10
# 接回的对话在后台：按会话号切到它（还在回答，切走前不会被收掉），再让它答完
session=$(android "grep -m1 '\"session_id\"' $log" | jq -r .session_id)
open_conversation "$session" 2
mark relaunched
wait_result 1 40 || exit 1
sleep 1
vd shot reattached.png >/dev/null

send "fake-reattach-2"
wait_result 2 30 || { vd shot after.png >/dev/null; exit 1; }
android "cat $log" >run.jsonl
modes=$(jq -r 'select(.type == "system" and .subtype == "init") | .permissionMode' run.jsonl | paste -sd ' ')
errors=$(control_errors run.jsonl)
echo "每轮开头的权限模式：$modes"
[[ $modes == "plan plan" ]] || { echo "两轮都应是计划模式"; exit 1; }
[[ -z $errors ]] || { echo "控制请求出错：$errors"; exit 1; }
echo "接回进程后，用户在对话里定的计划模式照旧"
