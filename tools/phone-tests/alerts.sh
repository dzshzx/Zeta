#!/bin/bash
# 提醒通知（渠道“完成与确认提醒”）：测试版在后台时，假 Claude 停下来等批准、提问、进程意外退出。核对每个对话一条提醒
# （tag 是会话号）、等批准时带“允许”“拒绝”、单选题的选项是按钮；按钮回答后这一轮接着跑完，提醒换成“已完成”；
# 回到前台看着这个对话时提醒撤掉；请求在看着时来、随后离开的照样提醒；进程意外退出时提醒“已中断”。等的时候顺带记下常驻通知有没有提升为实时通知、小胶囊上的字。
# 按钮由 root 直接发给 AlertReplyReceiver（和按钮的 PendingIntent 里是同一个 Intent）；通知栏上真点一下、锁屏上先要解锁，要人看。
# 用法：ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/alerts.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-alerts-start
android "touch $NEWER"
vd fake "$HERE/fake/alerts.jsonl" >/dev/null
new_chat

# 把测试版压到后台：测试屏上开另一个应用（NEW_TASK | EXCLUDE_FROM_RECENTS，不在「最近任务」里留记录），确认测试版不在前台了
background() {
  local i
  for i in 1 2 3; do
    android "am start --display $DISPLAY_ID -f 0x10800000 -n com.android.traceur/.MainActivity >/dev/null 2>&1"
    sleep 1
    [[ -z $(resumed) ]] && return 0
  done
  echo "测试版退不到后台：$(resumed)"
  return 1
}

# alert：本包的提醒（id=2）那条记录，没有时是空的；常驻通知（id=1）的记录写进 status.txt
alert() {
  ours >all.txt
  awk '/NotificationRecord\(/ { keep = index($0, " id=2 ") > 0 } keep' all.txt
  awk '/NotificationRecord\(/ { keep = index($0, " id=1 ") > 0 } keep' all.txt >status.txt
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

# 常驻通知：提升了没有、小胶囊上的字
status_line() {
  local flags short
  flags=$(grep -m1 -E '^ +flags=' "$1" | sed 's/^ *flags=//' || true)
  short=$(grep -m1 -oE 'android\.shortCriticalText=.*' "$1" | sed 's/.*String (\(.*\))$/\1/' || true)
  echo "常驻通知 flags=$flags 小胶囊=${short:-无}"
}

# press <第几个按钮>：照提醒上那个按钮发给 AlertReplyReceiver。会话号取提醒的 tag，请求号取日志里最近的 can_use_tool
press() {
  local key request
  key=$(grep -m1 -oE ' tag=[^ ]+' alert-now.txt | sed 's/ tag=//')
  find_log
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

# 第一轮：等批准
send "one"
background
wait_alert confirm "需要你确认：列出文件"
cp alert-confirm.txt alert-now.txt
check "等批准时的按钮" "$(buttons alert-confirm.txt | paste -sd,)" "允许,拒绝"
status_line status-confirm.txt
press 0
wait_alert done1 "已完成"
check "一轮结束后的提醒没有按钮" "$(buttons alert-done1.txt | wc -l)" "0"

# 回到前台看着这个对话：提醒撤掉
vd launch >/dev/null
wait_gone "回到前台"
echo "通过：回到前台后提醒撤掉"

# 第二轮：单选题。等提问在聊天页上弹出来（剧本停 4 秒）再离开：看着时来的请求，离开后也要提醒
send "two"
sleep 7
[[ -z $(alert) ]] || { echo "不对：看着这个对话时就发了提醒"; fail=1; }
background
wait_alert ask "需要你回答：先改哪个？"
cp alert-ask.txt alert-now.txt
check "单选题的按钮" "$(buttons alert-ask.txt | paste -sd,)" "界面,文档"
status_line status-ask.txt
press 1
wait_alert done2 "已完成"

# 第三轮：进程意外退出
vd launch >/dev/null
wait_gone "回到前台"
send "three"
background
wait_alert interrupted "已中断"
echo "通过：进程意外退出时提醒已中断"

vd launch >/dev/null
wait_gone "最后回到前台"
exit $fail
