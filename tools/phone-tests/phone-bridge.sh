#!/bin/bash
# 手机工具的外部入口（phone/PhoneBridge.kt）：工作站上的 Claude Code 经 `sudo zeta-helper phone-mcp <包名>` 用手机工具。
# 以 root 跑这棵树编出的 zeta-helper，一次调用连一次（像工作站上那样）：打开应用、重连后读屏、再重连按上次的编号点按；
# 换一个客户端标识是另一份手机工具；再开四个标识，后台屏幕不超过全应用的上限；Debian 里的普通用户连，应该被拒。
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

. "$ZETA_ROOT/tools/phone-tests/lib.sh"
bridge_helper

start=$(date +%s)
bridge_talk bridge-test round1 '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$(bridge_call 3 open_app '{"app":"com.android.settings"}')"
echo "第一轮用了 $(($(date +%s) - start)) 秒，收到 $(wc -l <round1.jsonl) 行"
check "initialize 答的是 Zeta 的手机工具" test "$(bridge_reply round1.jsonl 1 | jq -r '.result.serverInfo.name // empty')" != ""
check "tools/list 里有读屏和点按" test "$(bridge_reply round1.jsonl 2 | jq '[.result.tools[].name] | (index("screen") != null) and (index("tap") != null)')" = true
check "在后台屏幕打开设置" bridge_ok round1.jsonl 3

# 断开再连、同一个标识：后台屏幕还是那块
bridge_talk bridge-test round2 "$(bridge_call 4 screen '{}')"
bridge_reply round2.jsonl 4 | jq -r '.result.content[0].text // .error.message' | head -4
check "重连后读屏读到的是后台屏幕上的设置" test "$(bridge_reply round2.jsonl 4 | jq -r '.result.content[0].text' | grep -c 'com.android.settings')" -gt 0

# 再连一次：上次读屏的控件编号还在
bridge_talk bridge-test round3 "$(bridge_call 5 tap '{"node":0}')"
bridge_reply round3.jsonl 5 | jq -r '.result.content[0].text // .error.message' | head -2
check "重连后按上次的控件编号点按" bridge_ok round3.jsonl 5

# 另一个标识是另一份手机工具：没读过屏，没有编号 0
bridge_talk other round4 "$(bridge_call 6 tap '{"node":0}')"
check "别的标识用不到这份的控件编号" test "$(bridge_reply round4.jsonl 6 | jq '.result.isError')" = true

# 外部客户端的后台屏幕和对话的一起算上限（ConversationHub.MAX_SCREENS，3 块）：再开四个标识、各开一块，满了就腾掉闲着的
screens() { android 'dumpsys display' | grep -oE 'uniqueId="virtual:[^"]*zeta-background[^"]*"' | sort -u | wc -l; }
before=$(screens)
for i in 1 2 3 4; do
  bridge_talk "limit-$i" "limit$i" "$(bridge_call $((10 + i)) open_app '{"app":"com.android.settings"}')"
  check "标识 limit-$i 在后台屏幕打开设置" bridge_ok "limit$i.jsonl" $((10 + i))
done
after=$(screens)
echo "Zeta 的后台屏幕：开之前 $before 块，四个标识各开一块之后 $after 块"
check "外部客户端的后台屏幕不超过上限" test "$after" -le 3

# Debian 里不带 sudo 的进程：入口不认，连上就断，什么也收不到
vd debian --stdin 'cat > /tmp/zeta-bridge-helper && chmod 755 /tmp/zeta-bridge-helper' <"$BRIDGE_HELPER"
denied=$(vd debian "(printf '%s\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'; sleep 3) | timeout 30 /tmp/zeta-bridge-helper phone-mcp $PKG 2>/dev/null; true")
check "普通用户连上也收不到应答" test -z "$denied"

exit $fail
