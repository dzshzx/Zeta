#!/bin/bash
# 顶部胶囊显示着时常驻通知不提升为实时通知（照 Eta，免得顶部同时出现两个胶囊）。用假 Claude 的剧本
# fake/capsule-live.jsonl 跑一轮：先只说话、停 9 秒（没有胶囊，通知应提升），再经 Zeta 真调一次手机工具在后台屏幕上
# 开计算器、停 9 秒（胶囊出来，通知应退回普通常驻通知）。两段各在停顿里取一次本包的通知记录和测试屏上的窗口。
# 不等真模型，几十秒：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/capsule-live.sh
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本。
set -euo pipefail
HERE=$ZETA_ROOT/tools/phone-tests
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548
PROMOTED=$((0x40000))

# 本包常驻通知是否提升：flags 取记录自己那一行（dumpsys 按名字列，也可能是十六进制）
promoted() {
  local flags
  flags=$(android "dumpsys notification --noredact" | awk -v p="pkg=$PKG " '
    /NotificationRecord\(/ { keep = index($0, p) > 0; ind = match($0, /[^ ]/); next }
    keep { if (match($0, /[^ ]/) <= ind) keep = 0; else if ($1 ~ /^flags=/) { sub(/^flags=/, "", $1); print $1; exit } }')
  [[ -n $flags ]] || { echo 无通知; return; }
  if [[ $flags == *PROMOTED_ONGOING* ]] || { [[ $flags == 0x* ]] && ((flags & PROMOTED)); }; then echo 是; else echo 否; fi
}
# 测试屏上有没有胶囊窗口（窗口标题 ZetaCapsule，挂在 phoneDisplay 指到的测试屏上）
capsule() {
  if android "dumpsys window windows" | grep -q "ZetaCapsule"; then echo 有; else echo 无; fi
}
# 一段停顿里每秒看一次，直到两样都是想要的值或 8 秒到了；记下最后看到的
sample() {
  local want_p=$1 want_c=$2 p c
  for _ in $(seq 8); do
    p=$(promoted) c=$(capsule)
    [[ $p == "$want_p" && $c == "$want_c" ]] && break
    sleep 1
  done
  echo "$p $c"
}

canpost=$(android "dumpsys package $PKG" | grep -c 'POST_PROMOTED_NOTIFICATIONS: granted=true' || true)
((canpost > 0)) || { echo "测试版没有 POST_PROMOTED_NOTIFICATIONS，测不了"; exit 1; }

vd fake "$HERE/fake/capsule-live.jsonl"
vd tap $INPUT_X $INPUT_Y
sleep 1
vd text "capsule-live"
sleep 0.5
vd tap $SEND_X $SEND_Y

read -r p1 c1 < <(sample 是 无)
vd shot before-phone.png >/dev/null
echo "调手机工具前：通知提升=$p1，胶囊=$c1"
# 手机工具调完、胶囊进场：等到胶囊出来再看通知
read -r p2 c2 < <(sample 否 有)
vd shot capsule.png >/dev/null
echo "胶囊显示着：通知提升=$p2，胶囊=$c2"

[[ $p1 == 是 && $c1 == 无 ]] || { echo "没有胶囊时通知应提升为实时通知"; exit 1; }
[[ $c2 == 有 ]] || { echo "调过手机工具后没等到胶囊"; exit 1; }
[[ $p2 == 否 ]] || { echo "胶囊显示着时通知不该提升"; exit 1; }
echo "胶囊显示时通知退回普通常驻通知"
