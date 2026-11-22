#!/bin/bash
# 顶部胶囊显示着时常驻通知不提升为实时通知（照 Eta，免得顶部同时出现两个胶囊）。用假 Claude 的剧本
# fake/capsule-live.jsonl 跑一轮：先只说话（没有胶囊，通知应提升），再经 Zeta 真调一次手机工具在后台屏幕上
# 开系统设置（胶囊出来，通知应退回普通常驻通知）。两段后面剧本各等一次标记，脚本取完本包的通知记录和测试屏上的窗口再放。
# 不等真模型，几十秒：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/capsule-live.sh
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
PROMOTED=$((0x40000))

# 看一眼：本包常驻通知提没提升、测试屏上有没有胶囊（窗口标题 ZetaCapsule）。一次 ssh，手机上先滤成通知记录的头和
# flags 行、胶囊窗口数，不把整份 dump 拉回来。flags 取本包记录头之后的第一行 flags（按名字列，也可能是十六进制）
look() {
  local out flags count
  out=$(android "dumpsys notification --noredact | grep -E 'NotificationRecord\\(|^ +flags='; echo @@; dumpsys window windows | grep -c ZetaCapsule; true")
  flags=$(sed '/^@@$/,$d' <<<"$out" | awk -v p="pkg=$PKG " '/NotificationRecord\(/ { keep = index($0, p) > 0; next } keep && $1 ~ /^flags=/ { sub(/^flags=/, "", $1); print $1; exit }')
  count=$(sed -n '/^@@$/{n;p;}' <<<"$out")
  local p=无通知
  if [[ -n $flags ]]; then
    p=否
    if [[ $flags == *PROMOTED_ONGOING* ]] || { [[ $flags == 0x* ]] && ((flags & PROMOTED)); }; then p=是; fi
  fi
  echo "$p $(((${count:-0} > 0)) && echo 有 || echo 无)"
}
# 每秒看一次，直到两样都是想要的值或 8 次到了；记下最后看到的
sample() {
  local want=$1 seen
  for _ in $(seq 8); do
    seen=$(look)
    [[ $seen == "$want" ]] && break
    sleep 1
  done
  echo "$seen"
}

canpost=$(android "dumpsys package $PKG" | grep -c 'POST_PROMOTED_NOTIFICATIONS: granted=true' || true)
((canpost > 0)) || { echo "测试版没有 POST_PROMOTED_NOTIFICATIONS，测不了"; exit 1; }

vd fake "$HERE/fake/capsule-live.jsonl"
send capsule-live

read -r p1 c1 < <(sample "是 无")
vd shot before-phone.png >/dev/null
echo "调手机工具前：通知提升=$p1，胶囊=$c1"
# 放标记让剧本去调手机工具；调完胶囊进场，等到胶囊出来再看通知
mark promoted
read -r p2 c2 < <(sample "否 有")
vd shot capsule.png >/dev/null
echo "胶囊显示着：通知提升=$p2，胶囊=$c2"
mark capsule

[[ $p1 == 是 && $c1 == 无 ]] || { echo "没有胶囊时通知应提升为实时通知"; exit 1; }
[[ $c2 == 有 ]] || { echo "调过手机工具后没等到胶囊"; exit 1; }
[[ $p2 == 否 ]] || { echo "胶囊显示着时通知不该提升"; exit 1; }
echo "胶囊显示时通知退回普通常驻通知"
