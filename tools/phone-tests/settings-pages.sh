#!/bin/bash
# 设置的页面和返回键：通知深链打开设置子页（权限健康页用 Permissions，和 0.7.0 起无障碍提醒发的一样，开两次；
# 手机操作页用 Phone），返回键先回设置首页、再回聊天页；从设置首页点进手机的主机页、再进它的默认思考强度页，
# 返回键一层层退回设置首页；从权限健康页开 Linux 环境页（环境就绪、已登录时这一行不能点，就留在权限健康页），返回先关 Linux 环境页、回到权限健康页。深链用 root 的 am start 带 extra 模拟，和无障碍被关掉的提醒发的 Intent 一样。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/settings-pages.sh
# 每一步截一张图（编号在文件名前面），看停在哪一页；进程没了、界面不在前台，或该换页时画面没变，就失败。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

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
  [[ $(resumed) == "$PKG/"* ]] || { echo "Zeta 不在前台（$shot）"; exit 1; }
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
step permission-health-link
back
step settings
back
step chat

link Permissions
step permission-health
# 权限健康页里的「Linux 环境」一行：就绪、已登录时不能点，画面不变，这时还在权限健康页，不按返回；
# 打开了 Linux 环境页时返回先关它、回到权限健康页。看权限健康页的分组标题还在不在，不比截图：点按会带出滚动条
vd tap 400 834 >/dev/null
sleep 1.5
step linux-from-permission-health same
if [[ -z $(vd nodes '^权限与状态$') ]]; then
  back
  step back-to-permission-health
fi
back
step settings
back
step chat

link Phone
step phone
back
step settings
# 设置首页最上面的「手机」，再点主机页里的「默认思考强度」（第一组第二行，读到默认值后才能点）
vd tap 365 633 >/dev/null
sleep 1.5
step host-phone
vd tap 365 780 >/dev/null
sleep 1.5
step host-effort
back
step host-phone-again
back
step settings-again
back
step chat
echo "走完 $n 步"
