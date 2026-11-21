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

case $(android 'uname -m' | tr -d '\r') in
  aarch64) arch=arm64 ;;
  x86_64) arch=amd64 ;;
  *) echo "不认识的架构" && exit 1 ;;
esac
HELPER=$ZETA_ROOT/app/build/generated/zeta-helper/zeta-helper/linux-$arch
BIN=/data/local/tmp/zeta-capsule-helper
vd sh --stdin "cat > $BIN && chmod 755 $BIN" <"$HELPER"
# 上一次留下的应答文件会让 inflight 以为已经答过
vd sh "rm -f $BIN.*.out"

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"bridge-capsule-test","version":"1"}}}'
# talk <名字> <请求…>：以 root 跑一次 phone-mcp（一次连接，像工作站上的一次调用），写法同 phone-bridge.sh 的 talk，
# 应答存到 <名字>.jsonl。每个名字用设备上自己的一组文件，可以放后台和截图同时跑
talk() {
  local tag=$1
  shift
  local req=$BIN.$tag.req res=$BIN.$tag.out
  printf '%s\n' "$INIT" '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$@" | vd sh --stdin "cat > $req"
  local want=$(($# + 1))
  vd sh ": > $res; (cat $req; i=0; while [ \$(wc -l < $res) -lt $want ] && [ \$i -lt 200 ]; do sleep 0.3; i=\$((i+1)); done) | timeout 90 $BIN phone-mcp $PKG workstation > $res 2>$res.err; cat $res" >"$tag.jsonl" || true
}
# inflight <名字>：等放在后台的 talk 答完 initialize（请求是一起发出的，工具调用随即开始处理），再等胶囊换好那一句
inflight() {
  vd sh "i=0; until [ -s $BIN.$1.out ] || [ \$i -ge 50 ]; do sleep 0.2; i=\$((i+1)); done"
  sleep 1
}
# shellcheck disable=SC2329 # 经 ok 间接调用
reply() { jq -c "select(.id == $2)" "$1"; }
# shellcheck disable=SC2329 # 经 check 间接调用
ok() { test "$(reply "$1" "$2" | jq '.result != null and .result.isError != true')" = true; }
call() { printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$1" "$2" "$3"; }
windows() { android 'dumpsys window windows' | grep -oE 'ZetaCapsule|ZetaEdgeGlow' | sort -u | tr '\n' ' '; }
has() { [[ " $(windows) " == *" $1 "* ]]; }
# shellcheck disable=SC2329 # 经 check 间接调用
hasnt() { ! has "$1"; }

# 一、在主屏（测试屏）上动手：滑一下聊天区的空白处，再等 8 秒，等待期间截图
talk main1 "$(call 2 screen '{"display":"main"}')" "$(call 3 swipe '{"x1":360,"y1":900,"x2":360,"y2":1000,"duration_ms":300}')"
check "在主屏上滑动" ok main1.jsonl 3
talk main2 "$(call 4 wait '{"duration_ms":8000}')" &
waiter=$!
inflight main2
for _ in 1 2 3 4 5 6 7 8 9 10; do has ZetaEdgeGlow && break; done
vd shot main-glow.png
echo "主屏等待中的悬浮窗口：$(windows)"
check "等待中胶囊显示着" has ZetaCapsule
check "上一下动手在主屏上：边缘流光亮着" has ZetaEdgeGlow
wait "$waiter"
check "主屏上的等待做完" ok main2.jsonl 4

# 二、在后台屏幕上开设置，再等 10 秒：胶囊右侧是后台屏幕的缩略画面，第二行「电脑在操作 · 设置」
talk open "$(call 5 open_app '{"app":"com.android.settings"}')"
check "在后台屏幕打开设置" ok open.jsonl 5
talk wait "$(call 6 wait '{"duration_ms":10000}')" &
waiter=$!
inflight wait
for _ in 1 2 3 4 5 6 7 8 9 10; do hasnt ZetaEdgeGlow && break; done
vd shot during.png
echo "后台屏幕等待中的悬浮窗口：$(windows)"
check "等待中胶囊显示着" has ZetaCapsule
check "在后台屏幕上动手：边缘流光不亮" hasnt ZetaEdgeGlow
wait "$waiter"
check "后台屏幕上的等待做完" ok wait.jsonl 6
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
