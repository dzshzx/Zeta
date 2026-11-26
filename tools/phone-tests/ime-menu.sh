#!/bin/bash
# 键盘开着时，输入栏 + 号的附件菜单整块在 + 号上方，不往下展开、被键盘盖住（Menus.kt 的 MenuPopup 给宿主底下让出输入法的高度；
# Miuix 的玻璃弹层自己只避开状态栏和导航栏）。截图测试里没有输入法，所以在设备上验。
# 测试用的虚拟屏不抢全局焦点（VdHold 的 OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED），输入法只服务全局焦点所在的屏，键盘弹不到虚拟屏上；
# 所以这个测试把测试版开在模拟器自己的主屏上，只在模拟器上跑（手机的主屏是用户在用的）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/ime-menu.sh
set -euo pipefail
[[ $ZETA_DEVICE == emu ]] || { echo "这个测试要用设备的主屏，只在模拟器上跑"; exit 2; }

# main_nodes <正则>：主屏上文字或说明对得上的控件，一行一个：中心 x、y、文字、说明（同 vd nodes，读法见 core/diag/UiNodes.kt）
main_nodes() {
  local out
  out=$(android "am broadcast -n $PKG/com.dzshzx.zeta.core.diag.UiNodes --ei display 0")
  out=${out#*Broadcast completed: result=}
  # 结果码 1 是无障碍服务连不上，2 是读的时候出错：说明写到标准错误，当作没读到
  [[ $out == 0,* ]] || { echo "读不到主屏的控件：$out" >&2; return 0; }
  out=${out#*data=\"}
  awk -F'\t' -v re="$1" '$3 ~ re || $4 ~ re' <<<"${out%\"}"
}
# until_ok <说明> <命令…>：命令成功就返回；10 秒还不成功就截图、说明，失败退出
until_ok() {
  local what=$1
  shift
  for _ in $(seq 40); do
    "$@" && return 0
    sleep 0.25
  done
  shot timeout.png
  echo "10 秒没等到：$what（看 timeout.png）"
  exit 1
}
shot() { android "screencap -p" >"$1"; }
# 先收下整段输出再找：grep -q 找到就退出，前面的命令吃 SIGPIPE，pipefail 下整条管道算失败
ime_shown() { local s; s=$(android "dumpsys input_method"); [[ $s == *mInputShown=true* ]]; }
plus() { local s; s=$(main_nodes '^添加附件$'); echo "${s%%$'\n'*}"; }
plus_lower_than() { local y; y=$(plus | cut -f2); [[ -n $y ]] && ((y < $1)); }
has_plus() { [[ -n $(plus) ]]; }
has_menu() { [[ -n $(main_nodes '^图片$') ]]; }

android "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard"
android "am start -W --display 0 -n $PKG/com.dzshzx.zeta.ui.MainActivity >/dev/null"
until_ok "主屏上出现输入栏" has_plus
read -r px low _ < <(plus)
# 输入框本身不在控件清单里（没有文字）：点 + 号右上方的输入区
android "input tap $((px + 250)) $((low - 100))"
until_ok "输入法弹出来" ime_shown
until_ok "输入栏随键盘上移" plus_lower_than "$low"
shot 01-keyboard.png
read -r px py _ < <(plus)
android "input tap $px $py"
until_ok "附件菜单弹出来" has_menu
shot 02-menu.png
ime_shown || { echo "点 + 号后键盘收起了，这一轮验不到（看 02-menu.png）"; exit 1; }
rows=$(main_nodes '^(图片|文件|文件夹|输入路径|常用指令)$')
android "input keyevent KEYCODE_BACK"
echo "+ 号中心 y=$py（键盘收起时 $low）；菜单各项："
echo "$rows"
lowest=$(cut -f2 <<<"$rows" | sort -n | tail -1)
if [[ $rows != *常用指令* ]]; then
  echo "菜单的最后一项「常用指令」不在屏上，被键盘盖住了（看 02-menu.png）"
  exit 1
elif ((lowest < py)); then
  echo "通过：菜单整块在 + 号上方"
else
  echo "菜单有一项在 + 号下方（y=$lowest），被键盘盖住了（看 02-menu.png）"
  exit 1
fi
