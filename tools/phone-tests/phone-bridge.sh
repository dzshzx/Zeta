#!/bin/bash
# 手机工具的外部入口（phone/PhoneBridge.kt）：工作站上的 Claude Code 经 `sudo zeta-helper phone-mcp <包名>` 用手机工具。
# 以 root 跑这棵树编出的 zeta-helper，一次调用连一次（像工作站上那样）：打开应用、重连后读屏、再重连按上次的编号点按；
# 换一个客户端标识是另一份手机工具；Debian 里的普通用户连，应该被拒。
# 用法：ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/phone-bridge.sh
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
BIN=/data/local/tmp/zeta-bridge-helper
vd sh --stdin "cat > $BIN && chmod 755 $BIN" <"$HELPER"

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"phone-bridge-test","version":"1"}}}'
# talk <标识> <输出文件> <请求…>：在设备上以 root 跑一次 phone-mcp（一次连接，像工作站上的一次调用），发 initialize 和这些请求，
# 收齐应答再关输入。请求先放进设备上的文件：模拟器的 adb shell 读到输入结束会把远端命令整个结束掉
talk() {
  local key=$1 out=$2
  shift 2
  printf '%s\n' "$INIT" '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$@" | vd sh --stdin "cat > $BIN.req"
  local want=$(($# + 1))
  vd sh ": > $BIN.out; (cat $BIN.req; i=0; while [ \$(wc -l < $BIN.out) -lt $want ] && [ \$i -lt 200 ]; do sleep 0.3; i=\$((i+1)); done) | timeout 90 $BIN phone-mcp $PKG $key > $BIN.out 2>$BIN.err; cat $BIN.out" >"$out" || true
}
reply() { jq -c "select(.id == $2)" "$1"; }
# shellcheck disable=SC2329 # 经 check 间接调用
ok() { test "$(reply "$1" "$2" | jq '.result != null and .result.isError != true')" = true; }
call() { printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$1" "$2" "$3"; }

start=$(date +%s)
talk bridge-test round1.jsonl '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$(call 3 open_app '{"app":"com.android.settings"}')"
echo "第一轮用了 $(($(date +%s) - start)) 秒，收到 $(wc -l <round1.jsonl) 行"
check "initialize 答的是 Zeta 的手机工具" test "$(reply round1.jsonl 1 | jq -r '.result.serverInfo.name // empty')" != ""
check "tools/list 里有读屏和点按" test "$(reply round1.jsonl 2 | jq '[.result.tools[].name] | (index("screen") != null) and (index("tap") != null)')" = true
check "在后台屏幕打开设置" ok round1.jsonl 3

# 断开再连、同一个标识：后台屏幕还是那块
talk bridge-test round2.jsonl "$(call 4 screen '{}')"
reply round2.jsonl 4 | jq -r '.result.content[0].text // .error.message' | head -4
check "重连后读屏读到的是后台屏幕上的设置" test "$(reply round2.jsonl 4 | jq -r '.result.content[0].text' | grep -c 'com.android.settings')" -gt 0

# 再连一次：上次读屏的控件编号还在
talk bridge-test round3.jsonl "$(call 5 tap '{"node":0}')"
reply round3.jsonl 5 | jq -r '.result.content[0].text // .error.message' | head -2
check "重连后按上次的控件编号点按" ok round3.jsonl 5

# 另一个标识是另一份手机工具：没读过屏，没有编号 0
talk other round4.jsonl "$(call 6 tap '{"node":0}')"
check "别的标识用不到这份的控件编号" test "$(reply round4.jsonl 6 | jq '.result.isError')" = true

# Debian 里不带 sudo 的进程：入口不认，连上就断，什么也收不到
vd debian --stdin 'cat > /tmp/zeta-bridge-helper && chmod 755 /tmp/zeta-bridge-helper' <"$HELPER"
denied=$(vd debian "(printf '%s\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'; sleep 3) | timeout 30 /tmp/zeta-bridge-helper phone-mcp $PKG 2>/dev/null; true")
check "普通用户连上也收不到应答" test -z "$denied"

exit $fail
