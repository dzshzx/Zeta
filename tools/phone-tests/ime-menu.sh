#!/bin/bash
# 键盘开着时，输入栏 + 号的附件菜单整块在 + 号上方，不往下展开、被键盘盖住（原理见 Menus.kt 的 MenuPopup）。
# 输入法只服务全局焦点所在的屏，键盘弹不到不抢焦点的测试屏（VdHold 的 OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED），
# 所以下一行让 phone-run 把测试版开在设备的主屏上，只在模拟器上跑（手机的主屏是用户在用的）。
# phone-run: --main
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/ime-menu.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

fail() { echo "$1（看 $2）"; exit 1; }
# until_ok <说明> <命令…>：命令成功就返回；10 秒还不成功就截图、失败退出
until_ok() {
  local what=$1 end=$((SECONDS + 10))
  shift
  until "$@"; do
    ((SECONDS < end)) || {
      vd shot --main timeout.png >/dev/null
      vd nodes --main >timeout-nodes.txt || true
      fail "10 秒没等到：$what" timeout.png
    }
    sleep 0.25
  done
}
# grep 读完整段再判断：grep -q 提前退出时 dumpsys 会报 Broken pipe
ime_shown() { android "dumpsys input_method | grep mInputShown=true >/dev/null"; }
# 下面几个把读到的控件留在 PLUS、ROWS 里，等到以后直接用，不再读一遍
PLUS=''
plus_shown() { PLUS=$(vd nodes --main '^添加附件$'); [[ -n $PLUS ]]; }
plus_above() { local y; plus_shown && read -r _ y _ <<<"$PLUS" && ((y < $1)); }
ROWS=''
menu_shown() { ROWS=$(vd nodes --main '^(图片|文件|文件夹|输入路径|常用指令)$'); [[ -n $ROWS ]]; }

until_ok "主屏上出现输入栏" plus_shown
read -r px low _ <<<"$PLUS"
# 输入框本身不在控件清单里（没有文字）：点 + 号右上方的输入区
android "input tap $((px + 250)) $((low - 100))"
until_ok "输入法弹出来" ime_shown
until_ok "输入栏随键盘上移" plus_above "$low"
vd shot --main 01-keyboard.png >/dev/null
read -r _ py _ <<<"$PLUS"
vd tap-text --main '^添加附件$' >/dev/null
until_ok "附件菜单弹出来" menu_shown
vd shot --main 02-menu.png >/dev/null
ime_shown || fail "点 + 号后键盘收起了，这一轮验不到" 02-menu.png
android "input keyevent KEYCODE_BACK"
echo "+ 号中心 y=$py（键盘收起时 $low）；菜单各项："
echo "$ROWS"
[[ $ROWS == *常用指令* ]] || fail "菜单的最后一项「常用指令」不在屏上，被键盘盖住了" 02-menu.png
lowest=$(cut -f2 <<<"$ROWS" | sort -n | tail -1)
((lowest < py)) || fail "菜单有一项在 + 号下方（y=$lowest），被键盘盖住了" 02-menu.png
echo "通过：菜单整块在 + 号上方"
