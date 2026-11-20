#!/bin/bash
# 假 Claude 的回归，也是写假 Claude 测试的样例。测试版换用按剧本回话的假 Claude（tools/fake-claude），
# 在一个对话里连发三条消息，每条换一份剧本：纯文字（fake/text.jsonl）、调一次手机工具在后台屏幕上开系统设置
# （fake/phone-tool.jsonl，走 Zeta 真的 phone 服务器）、出错结束（fake/error.jsonl）。每轮从托管日志核对这一轮的结局，
# 截一张图（turn-1.png…），记下用了几秒。不等真模型，整段几十秒：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/fake-claude.sh
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本，测试版回到真的 claude。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

# 悬浮层的“主屏”phone-run 已经指到测试屏（phoneDisplay），胶囊、卡片都画在测试屏上，不碰真屏

turn() {
  local n=$1 scenario=$2 start=$SECONDS
  vd fake "$HERE/fake/$scenario"
  send "fake-turn-$n"
  wait_result "$n" 20 || { vd shot "turn-$n-timeout.png"; exit 1; }
  sleep 1.5 # 等界面跟上
  vd shot "turn-$n.png" >/dev/null
  echo "第 $n 轮（$scenario）：$((SECONDS - start)) 秒，$(jq -r '"subtype=\(.subtype) is_error=\(.is_error) terminal_reason=\(.terminal_reason)"' <<<"$RESULT")"
}

turn 1 text.jsonl
[[ $(jq -r .is_error <<<"$RESULT") == false && $(jq -r .result <<<"$RESULT") == *"假 Claude 照剧本"* ]] || { echo "纯文字这一轮不对：$RESULT"; exit 1; }

turn 2 phone-tool.jsonl
# 手机工具真经 Zeta 执行：tool_result 是 Zeta 的 phone 服务器答的，不是出错；系统设置开在后台屏幕上，不在真屏（0）；手机和模拟器上都有它
tool=$(android "grep '\"type\":\"tool_result\"' $log | tail -n 1")
jq -e '.message.content[0] | .is_error != true and (.content | type) == "array"' <<<"$tool" >/dev/null ||
  { echo "手机工具这一轮的结果不对：$tool"; exit 1; }
echo "open_app 的结果：$(jq -r '[.message.content[0].content[] | .text // empty] | join(" ") | .[:160]' <<<"$tool")"
# dumpsys 按屏列任务（“Display #<屏号>”开头），看设置落在哪几块屏上
displays=$(android "dumpsys activity activities" | awk '/^ *Display #[0-9]+/ { d = $2 } /com\.android\.settings\// && d != "" { print d }' | sort -u | tr '\n' ' ')
echo "设置所在的屏：${displays:-没查到}（测试屏是 #$DISPLAY_ID）"
[[ -n $displays && " $displays" != *" #0 "* ]] || { echo "设置没开在后台屏幕上"; exit 1; }

turn 3 error.jsonl
[[ $(jq -r .is_error <<<"$RESULT") == true && $(jq -r .terminal_reason <<<"$RESULT") == api_error ]] || { echo "出错这一轮不对：$RESULT"; exit 1; }
echo "三轮都照剧本走完"
