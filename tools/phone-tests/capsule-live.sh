#!/bin/bash
# 顶部胶囊显示着这个对话时，它那条通知（id=2）撤掉、不上超级岛（照 Eta，免得顶部同时出现两个说同一件事的胶囊）。用假 Claude 的剧本
# fake/capsule-live.jsonl 跑一轮、发完压到后台（看着这个对话时本来就不发）：先只说话（没有胶囊，那条通知应带焦点参数上岛），
# 再经 Zeta 真调一次手机工具在后台屏幕上开系统设置（胶囊出来，那条通知应撤掉）。两段后面剧本各等一次标记，脚本取完本包的通知记录和测试屏上的窗口再放。
# 任何时候都不该带 Android 16 的实时通知标记（PROMOTED_ONGOING，见 docs/assistant.md「超级岛」）。
# 不等真模型，几十秒：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/capsule-live.sh
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
PROMOTED=$((0x40000))

# 看一眼：本包对话那条通知带没带焦点参数（上岛）、测试屏上有没有胶囊（窗口标题 ZetaCapsule）。一次 ssh，手机上先滤成
# 通知记录的头、flags 行和焦点图标行（模拟器的 dumpsys 不列出 miui.focus.param，三项是一起写的）、胶囊窗口数，不把整份 dump 拉回来。只看本包记录头之后、下一条记录之前的行。
# 带了实时通知标记时记为「实时」，后面的核对都过不了。
look() {
  local out rec flags count
  out=$(android "dumpsys notification --noredact | grep -E 'NotificationRecord\\(|^ +flags=|miui\\.focus\\.pics='; echo @@; dumpsys window windows | grep -c ZetaCapsule; true")
  rec=$(sed '/^@@$/,$d' <<<"$out" | awk -v p="pkg=$PKG " '/NotificationRecord\(/ { if (done) exit; keep = index($0, p) > 0 && index($0, " id=2 ") > 0; if (keep) done = 1; next } keep')
  count=$(sed -n '/^@@$/{n;p;}' <<<"$out")
  local p=无通知
  flags=$(grep -m1 -E '^ +flags=' <<<"$rec" | sed 's/^ *flags=//' || true)
  if [[ -n $flags ]]; then
    p=否
    grep -q 'miui\.focus\.pics=' <<<"$rec" && p=是
    if [[ $flags == *PROMOTED_ONGOING* ]] || { [[ $flags == 0x* ]] && ((flags & PROMOTED)); }; then p=实时; fi
  fi
  echo "$p $(((${count:-0} > 0)) && echo 有 || echo 无)"
}
# 每秒看一次，直到两样都是想要的值或 30 次到了；记下最后看到的
sample() {
  local want=$1 seen
  for _ in $(seq 30); do
    seen=$(look)
    [[ $seen == "$want" ]] && break
    sleep 1
  done
  echo "$seen"
}

vd fake "$HERE/fake/capsule-live.jsonl"
send capsule-live
background

read -r p1 c1 < <(sample "是 无")
vd shot before-phone.png >/dev/null
echo "调手机工具前：通知上岛=$p1，胶囊=$c1"
# 放标记让剧本去调手机工具；调完胶囊进场，等到胶囊出来再看通知
mark island
read -r p2 c2 < <(sample "无通知 有")
vd shot capsule.png >/dev/null
echo "胶囊显示着：通知上岛=$p2，胶囊=$c2"
mark capsule

[[ $p1 == 是 && $c1 == 无 ]] || { echo "没有胶囊时通知应带焦点参数上岛"; exit 1; }
[[ $c2 == 有 ]] || { echo "调过手机工具后没等到胶囊"; exit 1; }
[[ $p2 == 无通知 ]] || { echo "胶囊显示着时那条通知该撤掉（看到的是上岛=$p2）"; exit 1; }
echo "胶囊显示时那条通知撤掉"
