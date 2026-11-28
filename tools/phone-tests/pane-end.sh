#!/bin/bash
# 侧栏里结束对话，结束前一律确认：空白页没有建议卡片；长按一个还在运行的会话点「结束对话」，弹确认框、取消不结束、
# 确认后进程退出；多选两个点底部的「结束」，确认后两个都收掉；顶栏「更多」里结束一个闲着的对话也先问。
# 截图 empty.png、end-one.png、end-many.png、end-top.png。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/pane-end.sh
# 测试会话放在 Debian 的 $DIR 对应的项目目录里，脚本退出时删掉（连同替假 Claude 写的登记）；假 Claude 只接着它们跑，不写会话记录。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-pane-end
A=$(cat /proc/sys/kernel/random/uuid) B=$(cat /proc/sys/kernel/random/uuid) C=$(cat /proc/sys/kernel/random/uuid)
trap 'drop_session $A $DIR; drop_session $B $DIR; drop_session $C $DIR' EXIT
NEWER=/data/local/tmp/zeta-pane-end.mark

fail() { echo "$1"; vd shot "${2:-failed}.png" >/dev/null; exit 1; }

# run <会话号>：打开这个会话，发一句，假 Claude 答完；托管目录放进 host_<会话号前 8 位>
declare -A HOST
run() {
  android "touch $NEWER"
  log=''
  vd fake "$HERE/fake/text.jsonl" >/dev/null # 每个会话从剧本第一轮起
  open_conversation "$1"
  send "pane-end"
  wait_result 1 30 || fail "会话 $1 的一轮没跑完" "run-timeout"
  HOST[$1]=${log%/out.jsonl}
  register "$1"
}

# register <会话号>：替假 Claude 像真 Claude Code 那样在 ~/.claude/sessions/<pid>.json 登记它占着这个会话（假 Claude 不登记，
# 侧栏看登记判断会话还在不在运行，没有登记就当已结束、长按菜单里没有「结束对话」）；进程是托管目录 pid 文件里的那个
register() {
  local d=${HOST[$1]#/data/local/zeta/debian/rootfs}
  # shellcheck disable=SC2016 # 交给 Debian 里的 shell 展开
  vd debian 'p=$(cat '"$d"'/pid); start=$(sed "s/.*) //" /proc/$p/stat | cut -d" " -f20); mkdir -p ~/.claude/sessions
printf "{\"pid\":%s,\"sessionId\":\"%s\",\"procStart\":\"%s\",\"kind\":\"sdk\",\"entrypoint\":\"sdk-cli\",\"status\":\"idle\"}" $p '"$1"' $start >~/.claude/sessions/$p.json' >/dev/null
}

# long_press <正则>：长按侧栏里文字对得上的那一行（等它出现，最多 5 秒）
long_press() {
  local xy
  # 确认框收起的动画放完前，框下面的侧栏读不到：等那一行出现
  wait_text "$1" 5 || fail "侧栏里没有 $1" missing
  xy=$(vd nodes "$1" | head -1 | cut -f1,2)
  # shellcheck disable=SC2086 # x、y 分开传
  vd swipe ${xy/$'\t'/ } ${xy/$'\t'/ } 900 >/dev/null
  sleep 1.2
}

# 一、空白页：只有标题，没有建议卡片
new_chat
wait_text '有什么可以帮你' 10 || fail "空白页没有标题" empty
vd nodes '看看这个项目|检查 Git 状态|接着上次做|设备状况' | grep -q . && fail "空白页上还有建议卡片" empty
vd shot empty.png >/dev/null
echo "空白页：只有标题，没有建议卡片"

seed_session "$A" "$DIR" "pane end alpha" "alpha reply"
seed_session "$B" "$DIR" "pane end bravo" "bravo reply"
seed_session "$C" "$DIR" "pane end charlie" "charlie reply"
run "$A"
run "$B"
run "$C"
new_chat

# 二、长按一个：先问，取消不结束，确认后收掉
open_pane
wait_text 'pane end alpha' 15 || fail "侧栏里没有测试会话" missing
long_press 'pane end alpha'
vd tap-text '^结束对话$' >/dev/null || fail "长按菜单里没有结束对话" menu
wait_text '结束这个对话' 5 || fail "没弹确认框" no-confirm
vd shot end-one.png >/dev/null
vd tap-text '^取消$' >/dev/null
sleep 1.5
android "[ -d ${HOST[$A]} ]" || fail "取消了还是结束了" cancelled
long_press 'pane end alpha'
vd tap-text '^结束对话$' >/dev/null
wait_text '结束这个对话' 5 || fail "第二次没弹确认框" no-confirm
vd tap-text '^结束$' >/dev/null
exited "${HOST[$A]}" || fail "确认后进程没退出" one-not-ended
echo "长按结束：先问，取消不结束，确认后进程退出"

# 三、多选两个：底部「结束」，先问，确认后都收掉
long_press 'pane end bravo'
vd tap-text '^选择$' >/dev/null || fail "长按菜单里没有选择" menu
sleep 1.5
vd tap-text 'pane end charlie' >/dev/null
sleep 0.5
vd tap-text '^结束$' >/dev/null || fail "多选底部没有结束" select
wait_text '结束 2 个对话' 5 || fail "多选结束没弹确认框" no-confirm-many
vd shot end-many.png >/dev/null
vd tap-text '^结束$' >/dev/null # 确认框里的（最上层窗口的在前）
exited "${HOST[$B]}" || fail "多选结束后 bravo 的进程没退出" many-not-ended
exited "${HOST[$C]}" || fail "多选结束后 charlie 的进程没退出" many-not-ended
echo "多选结束：先问，确认后两个进程都退出"
vd key KEYCODE_BACK >/dev/null
sleep 1

# 四、顶栏「更多」里结束一个闲着的对话：也先问
run "$A"
menu_item $END_X $END_Y
wait_text '结束这个对话' 5 || fail "顶栏结束闲着的对话没问" top-no-confirm
vd nodes '结束会停止对应的' | grep -q . || fail "闲着的说明不对" top-summary
vd shot end-top.png >/dev/null
vd tap $CONFIRM_X $CONFIRM_Y >/dev/null
exited "${HOST[$A]}" || fail "顶栏确认后进程没退出" top-not-ended
echo "顶栏结束闲着的对话：先问，确认后进程退出"
