#!/bin/bash
# 每条消息按发的人的权限模式跑：测试版换用假 Claude（剧本 fake/mode-per-sender.jsonl），用户的权限模式设成自动接受编辑，
# 放一个接着同一个对话、权限模式是完全放行的定时任务，用闹钟的广播让它到点两次：
#   第 1 次新开对话，跑的时候打开它（这个对话成了用户正看着的那个）；跑完用户在里面发一条；
#   第 2 次接着这个开着的对话跑；跑完用户再发一条。
# 核对这个对话的假 Claude 每轮开头 init 报的权限模式依次是 完全放行、自动接受编辑、完全放行、自动接受编辑，
# 控制请求没有出错，用户的设置还是自动接受编辑。截图 run-1、mine-1、run-2、mine-2 是每轮之后的聊天页：
# 输入栏的模式按钮一直是用户的自动接受编辑（用户接下来发的按它跑），后台运行的模式不显示在这里。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/mode-per-sender.sh
# 假 Claude 不写会话记录，没有要删的测试会话；任务和设置由收尾的 vd stop 改回去，闹钟随 force-stop 清掉。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-mode-per-sender.mark
ID=ztmode
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

# 闹钟到点：和 ScheduleAlarms 排的闹钟发的是同一个广播
fire() {
  android "am broadcast -n $PKG/com.dzshzx.zeta.core.schedule.ScheduleReceiver -a $PKG.action.SCHEDULE_FIRE --es task $ID --el at $(($(date +%s) * 1000))" >/dev/null
}

android "touch $NEWER"
task_json='[{"id":"'$ID'","name":"假模式任务","prompt":"fake-mode-run","rule":{"kind":"daily","time":"03:00"},"enabled":true,"permissionMode":"bypassPermissions","newSession":false,"created":0}]'
vd prefs-set "scheduledTasks=$task_json" "permissionMode=acceptEdits"
vd fake "$HERE/fake/mode-per-sender.jsonl"
vd launch
# 冷启动后第一次打字在第 1 次运行之后，不用另等
[[ $(task | jq -r .permissionMode) == bypassPermissions ]] || { echo "测试任务没放进设置：$(task)"; exit 1; }

# 第 1 次：新开对话，跑的时候（剧本里停 8 秒）打开它
fire
session=''
for _ in $(seq 20); do
  session=$(task | jq -r '.sessionId // empty')
  [[ -n $session ]] && break
  sleep 1
done
[[ -n $session ]] || { echo "第 1 次运行 20 秒没拿到会话号：$(task)"; exit 1; }
android "am start --display $DISPLAY_ID -n $PKG/com.dzshzx.zeta.ui.MainActivity -f 0x30000000 --es com.dzshzx.zeta.extra.CONVERSATION $session" >/dev/null
wait_run 1
echo "第 1 次：$RUN"
sleep 0.5
vd shot run-1.png >/dev/null
find_log
[[ -n $log ]] || { echo "没找到这次假 Claude 的日志"; exit 1; }

send "fake-mode-mine"
wait_result 2 30 || exit 1
sleep 0.5
vd shot mine-1.png >/dev/null

# 第 2 次：接着这个开着的对话
fire
wait_run 2
echo "第 2 次：$RUN"
[[ $(jq -r .session <<<"$RUN") == "$session" ]] || { echo "第 2 次应接着同一个对话 $session"; exit 1; }
sleep 0.5
vd shot run-2.png >/dev/null

send "fake-mode-mine-2"
wait_result 4 30 || exit 1
sleep 0.5
vd shot mine-2.png >/dev/null

android "cat $log" >run.jsonl
modes=$(jq -r 'select(.type == "system" and .subtype == "init") | .permissionMode' run.jsonl | paste -sd ' ')
errors=$(control_errors run.jsonl)
echo "每轮开头的权限模式：$modes"
[[ $modes == "bypassPermissions acceptEdits bypassPermissions acceptEdits" ]] || { echo "应依次是 完全放行、自动接受编辑、完全放行、自动接受编辑"; exit 1; }
[[ -z $errors ]] || { echo "控制请求出错：$errors"; exit 1; }
mine=$(pref permissionMode)
[[ $mine == acceptEdits ]] || { echo "用户的设置被改成了 ${mine:-没有}"; exit 1; }
echo "后台运行的模式只管它发的那几条，用户自己的消息切回了自动接受编辑"
