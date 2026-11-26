#!/bin/bash
# 提醒通知（渠道“完成与确认提醒”）：测试版在后台时，假 Claude 停下来等批准、提问、进程意外退出。核对每个对话一条提醒
# （tag 是会话号）、等批准时带“允许”“拒绝”、单选题的选项是按钮；按钮回答后这一轮接着跑完，提醒换成“已完成”；
# 常驻通知显示着这个对话的当前步骤时，“在等你”显示在常驻通知（超级岛）上，带同样的按钮、换到出声的渠道，不另发提醒；回到前台看着这个对话时提醒撤掉；请求在看着时来、随后离开的照样提醒；进程意外退出时提醒“已中断”。常驻通知不该带 Android 16 的实时通知标记（PROMOTED_ONGOING）。
# 按钮由 root 直接发给 AlertReplyReceiver（和按钮的 PendingIntent 里是同一个 Intent）；通知栏上真点一下、锁屏上先要解锁，要人看。
# 等你、回答后几个时刻截下主屏的真实样子（main-*.png），看超级岛弹没弹。
# 用法：ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/alerts.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-alerts-start
android "touch $NEWER"
vd fake "$HERE/fake/alerts.jsonl" >/dev/null
new_chat

# 把测试版压到后台：测试屏上开另一个应用（NEW_TASK | EXCLUDE_FROM_RECENTS，不在「最近任务」里留记录），确认测试版不在前台了。
# 「系统跟踪」要开着开发者选项，否则一启动就退出，压不住
background() {
  local i
  for i in 1 2 3; do
    android "am start --display $DISPLAY_ID -f 0x10800000 -n com.android.traceur/.MainActivity >/dev/null 2>&1"
    sleep 1
    [[ -z $(resumed) ]] && return 0
  done
  echo "测试版退不到后台：$(resumed)（开发者选项开着吗？）"
  return 1
}

# step <说明>：打上时间，卡住时看得出卡在哪一步
step() { echo "[$(date +%T)] $*"; }

# snap <名字>：记下手机主屏真实的样子（超级岛、横幅都画在主屏上，虚拟屏上看不到）和屏幕状态（亮没亮、锁没锁），
# 存成产物目录里的 main-<名字>.png
snap() {
  local state
  state=$(android "dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+'; dumpsys window 2>/dev/null | grep -m1 -oE 'isKeyguardShowing=[a-z]+'" | tr '\n' ' ')
  vd shot --main "main-$1.png" >/dev/null
  echo "[$(date +%T)] 主屏 $1：$state→ main-$1.png"
}

# alert：本包的提醒（id=2）那条记录，没有时是空的；常驻通知（id=1）的第一条记录写进 status.txt
alert() {
  ours >all.txt
  awk '/NotificationRecord\(/ { keep = index($0, " id=2 ") > 0 } keep' all.txt
  awk '/NotificationRecord\(/ { keep = index($0, " id=1 ") > 0 && !done; if (keep) done = 1 } keep' all.txt >status.txt
}

# wait_waiting <名字> <正文的开头>：等“在等你”出现（最多 40 秒）。常驻通知显示着这个对话时出现在常驻通知上（WHERE=live，
# 超级岛上就能回答，不该另有提醒）；否则出现在提醒上（WHERE=alert）。记录存进 alert-<名字>.txt、status-<名字>.txt
WHERE=''
wait_waiting() {
  local i
  for ((i = 0; i < 80; i++)); do
    alert >"alert-$1.txt"
    cp status.txt "status-$1.txt"
    if grep -q 'miui\.focus\.pics=' "status-$1.txt" && grep -qF "android.text=String ($2" "status-$1.txt"; then
      WHERE=live
      return 0
    fi
    if grep -qF "android.text=String ($2" "alert-$1.txt"; then
      WHERE=alert
      return 0
    fi
    sleep 0.5
  done
  echo "40 秒没等到「$2」"
  return 1
}

# check_waiting <名字> <按钮，逗号隔开>：在等你的那条（常驻通知或提醒）上的按钮；常驻通知上时还核对渠道、没有另发提醒；常驻通知没带实时通知标记
check_waiting() {
  if [[ $WHERE == live ]]; then
    check "$1：常驻通知（超级岛）上的按钮" "$(buttons "status-$1.txt" | paste -sd,)" "$2"
    check "$1：常驻通知在运行状态渠道" "$(grep -m1 -oE 'channel=[a-z_]+' "status-$1.txt")" "channel=status"
    check "$1：超级岛能回答时不另发提醒" "$(grep -c NotificationRecord "alert-$1.txt" || true)" "0"
  else
    check "$1：提醒上的按钮" "$(buttons "alert-$1.txt" | paste -sd,)" "$2"
  fi
  check "$1：常驻通知没带实时通知标记" "$(grep -c PROMOTED_ONGOING "status-$1.txt" || true)" "0"
}

# wait_alert <名字> <正文的开头>：等提醒的正文变成这样（最多 40 秒），记录存进 alert-<名字>.txt
wait_alert() {
  local i
  for ((i = 0; i < 80; i++)); do
    alert >"alert-$1.txt"
    if grep -qF "android.text=String ($2" "alert-$1.txt"; then
      cp status.txt "status-$1.txt"
      return 0
    fi
    sleep 0.5
  done
  echo "40 秒没等到提醒「$2」，最后一次："
  cat "alert-$1.txt"
  return 1
}

# wait_gone <名字>：等提醒撤掉（最多 20 秒）
wait_gone() {
  local i
  for ((i = 0; i < 40; i++)); do
    [[ -z $(alert) ]] && return 0
    sleep 0.5
  done
  echo "$1：20 秒了提醒还在"
  return 1
}

# 记录里的按钮文字，一行一个
buttons() { grep -oE '^ +\[[0-9]+\] "[^"]*"' "$1" | sed -E 's/^ +\[[0-9]+\] "(.*)"$/\1/' || true; }

# 常驻通知：flags、大岛左边的字
status_line() {
  local flags short
  flags=$(grep -m1 -E '^ +flags=' "$1" | sed 's/^ *flags=//' || true)
  short=$(grep -m1 -oE '"aodTitle":"[^"]*"' "$1" | sed 's/.*:"\(.*\)"$/\1/' || true)
  echo "常驻通知 flags=$flags 大岛左边=${short:-无}"
}

# press <第几个按钮>：照通知上那个按钮发给 AlertReplyReceiver。会话号、请求号取日志里的会话和最近的 can_use_tool
press() {
  local key request
  find_log
  key=$(android "grep -m1 '\"session_id\"' $log" | jq -r .session_id)
  request=$(android "grep '\"can_use_tool\"' $log | tail -1" | jq -r .request_id)
  echo "按第 $1 个按钮：会话 $key 请求 $request"
  android "am broadcast -n $PKG/com.dzshzx.zeta.core.AlertReplyReceiver -a $PKG.action.ALERT_REPLY --es key '$key' --es request '$request' --ei index $1 >/dev/null"
}

fail=0
check() { # check <说明> <实际> <期望>
  if [[ $2 == "$3" ]]; then echo "通过：$1"; else
    echo "不对：$1：实际「$2」，期望「$3」"
    fail=1
  fi
}

# 第一轮：等批准。剧本先等标记，测试版退到后台了才来请求
step 第一轮
send "one"
background
mark away
step 切到后台
wait_waiting confirm "需要你确认：列出文件"
step "等到了（$WHERE）"
snap confirm
sleep 1.5
snap confirm-later
status_line status-confirm.txt
check_waiting confirm "允许,拒绝"
press 0
wait_alert done1 "已完成"
snap done1
check "一轮结束后的提醒没有按钮" "$(buttons alert-done1.txt | wc -l)" "0"

# 回到前台看着这个对话：提醒撤掉
vd launch >/dev/null
wait_gone "回到前台"
echo "通过：回到前台后提醒撤掉"

# 第二轮：单选题。等提问在聊天页上弹出来再离开：看着时来的请求，离开后也要提醒
step 第二轮
send "two"
wait_text '先改哪个' 20 || { vd shot no-question.png >/dev/null; echo "20 秒了提问没在聊天页上弹出来"; exit 1; }
sleep 1.5 # 看着时不该发提醒：留一点时间，真发了能看到
[[ -z $(alert) ]] || { echo "不对：看着这个对话时就发了提醒"; fail=1; }
background
step 切到后台
wait_waiting ask "需要你回答：先改哪个？"
step "等到了（$WHERE）"
snap ask
sleep 1.5
snap ask-later
status_line status-ask.txt
check_waiting ask "界面,文档"
press 1
wait_alert done2 "已完成"

# 第三轮：进程意外退出
step 第三轮
vd launch >/dev/null
wait_gone "回到前台"
send "three"
background
mark away # 剧本等到这里才让进程退出
wait_alert interrupted "已中断"
echo "通过：进程意外退出时提醒已中断"

vd launch >/dev/null
wait_gone "最后回到前台"
exit $fail
