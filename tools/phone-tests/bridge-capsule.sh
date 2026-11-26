#!/bin/bash
# 外部客户端在顶部胶囊上（core/BridgeFocus.kt）：经外部入口调手机工具时胶囊显示「电脑在操作」，在主屏上动手时主屏边缘流光亮着；
# 调用结束后胶囊停留约 30 秒再收起。胶囊和流光都在 Zeta 的「主屏」上，phone-run 把它指到了测试屏，所以截测试屏。
# 截图：main-glow.png（在主屏上动手、等待中）、during.png（后台屏幕上的设置、等待中）、after.png（刚结束）、gone.png（收起后）。
# 用法：ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/bridge-capsule.sh
set -euo pipefail

fail=0
check() { # check <说明> <命令…>：命令成功算通过
  local what=$1
  shift
  if "$@"; then echo "通过：$what"; else
    echo "不对：$what"
    fail=1
  fi
}

. "$ZETA_ROOT/tools/phone-tests/lib.sh"
bridge_helper
# talk <名字> <请求…>：以工作站上的 `phone ui` 默认的客户端标识调一次
talk() { bridge_talk workstation "$@"; }
# inflight <名字>：等放在后台的 talk 开始处理调用，再等胶囊换好那一句
inflight() {
  bridge_answered "$1"
  sleep 1
}
windows() { android 'dumpsys window windows' | grep -oE 'ZetaCapsule|ZetaEdgeGlow' | sort -u | tr '\n' ' '; }
has() { [[ " $(windows) " == *" $1 "* ]]; }
# shellcheck disable=SC2329 # 经 check 间接调用
hasnt() { ! has "$1"; }

# 一、在主屏（测试屏）上动手：滑一下聊天区的空白处，再等 8 秒，等待期间截图
talk main1 "$(bridge_call 2 screen '{"display":"main"}')" "$(bridge_call 3 swipe '{"x1":360,"y1":900,"x2":360,"y2":1000,"duration_ms":300}')"
check "在主屏上滑动" bridge_ok main1.jsonl 3
talk main2 "$(bridge_call 4 wait '{"duration_ms":8000}')" &
waiter=$!
inflight main2
for _ in 1 2 3 4 5 6 7 8 9 10; do has ZetaEdgeGlow && break; done
vd shot main-glow.png
echo "主屏等待中的悬浮窗口：$(windows)"
check "等待中胶囊显示着" has ZetaCapsule
check "上一下动手在主屏上：边缘流光亮着" has ZetaEdgeGlow
wait "$waiter"
check "主屏上的等待做完" bridge_ok main2.jsonl 4

# 二、在后台屏幕上开设置，再等 10 秒：胶囊右侧是后台屏幕的缩略画面，第二行「电脑在操作 · 设置」
talk open "$(bridge_call 5 open_app '{"app":"com.android.settings"}')"
check "在后台屏幕打开设置" bridge_ok open.jsonl 5
talk wait "$(bridge_call 6 wait '{"duration_ms":10000}')" &
waiter=$!
inflight wait
for _ in 1 2 3 4 5 6 7 8 9 10; do hasnt ZetaEdgeGlow && break; done
vd shot during.png
echo "后台屏幕等待中的悬浮窗口：$(windows)"
check "等待中胶囊显示着" has ZetaCapsule
check "在后台屏幕上动手：边缘流光不亮" hasnt ZetaEdgeGlow
wait "$waiter"
check "后台屏幕上的等待做完" bridge_ok wait.jsonl 6
ended=$(date +%s)

# 三、刚结束：胶囊还在（「等待下一步」）；之后约 30 秒收起
vd shot after.png
check "最后一个调用刚结束：胶囊还停留着" has ZetaCapsule
gone=""
while [ $(($(date +%s) - ended)) -lt 60 ]; do
  if hasnt ZetaCapsule; then
    gone=$(($(date +%s) - ended))
    break
  fi
done
echo "调用结束后 ${gone:-60 秒还没}收起"
vd shot gone.png
check "停留约 30 秒后收起（20 到 45 秒之间）" test "${gone:-99}" -ge 20 -a "${gone:-99}" -le 45

exit $fail
