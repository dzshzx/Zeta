#!/bin/bash
# 设置的页面和返回键：通知深链打开设置子页（权限健康页用新名字 PermissionHealth 和改名前的旧名字 Permissions 各开一次，
# 手机操作页用 Phone），返回键先回设置首页、再回聊天页；从设置首页点进账号子页再返回；从权限健康页开 Linux 环境页，
# 返回先关 Linux 环境页、回到权限健康页。深链用 root 的 am start 带 extra 模拟，和无障碍被关掉的提醒发的 Intent 一样。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/settings-pages.sh
# 每一步截一张图（编号在文件名前面），看停在哪一页；进程没了、界面不在前台，或该换页时画面没变，就失败。
set -euo pipefail

COMPONENT=$PKG/com.dzshzx.zeta.ui.MainActivity
EXTRA=com.dzshzx.zeta.extra.SETTINGS_PAGE
n=0
last=''

# step <名字> [same]：截图，确认 Zeta 还在前台；不带 same 时画面要和上一张不一样
step() {
  n=$((n + 1))
  local shot
  shot=$(printf '%02d-%s.png' "$n" "$1")
  vd shot "$shot" >/dev/null
  android "pidof $PKG" >/dev/null || { echo "$PKG 不在跑了（$shot）"; exit 1; }
  android "dumpsys activity activities | grep -E 'ResumedActivity'" | grep -q "$PKG/" || { echo "Zeta 不在前台（$shot）"; exit 1; }
  if [[ ${2:-} != same && -n $last ]] && cmp -s "$last" "$shot"; then
    echo "画面没变：$last → $shot"
    exit 1
  fi
  last=$shot
}

link() {
  android "am start --display $DISPLAY_ID --activity-single-top -n $COMPONENT --es $EXTRA $1 >/dev/null"
  sleep 2
}

back() {
  vd key 4 >/dev/null
  sleep 1.5
}

step chat
link Permissions
step permission-health-old-link
back
step settings
back
step chat

link PermissionHealth
step permission-health
# 权限健康页里的「Linux 环境」一行（就绪、已登录时不能点，画面不变也不算失败）
vd tap 400 834 >/dev/null
sleep 1.5
step linux-from-permission-health same
back
step back-to-permission-health same
back
step settings
back
step chat

link Phone
step phone
back
step settings
# 设置首页最上面的「账号与额度」
vd tap 365 633 >/dev/null
sleep 1.5
step accounts
back
step settings-again
back
step chat
echo "走完 $n 步"
