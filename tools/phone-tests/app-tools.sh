#!/bin/bash
# 真机：系统应用开给系统智能体的工具（app_tools、app_tool）。要在小米手机上跑：工具服务是 HyperOS 的系统应用带的。
# 多数应用的工具服务要求签名级的绑定权限，要 Zeta 的 LSPosed 模块放行（xposed/AppToolBindHook.kt），没装上时那些应用会报出来。
# 一轮假 Claude 经 Zeta 真调：app_tools 列出全部应用，每个应用看一遍工具说明、各调一个只读的工具，
# 被 Zeta 自己的工具取代的（时钟的闹钟、便签的整篇写入）要被拒绝并指向该用的工具；
# 第二轮把桌面的一项设置设成它现在的值再读回，走一遍写的路，不改任何东西。
# 只读工具的结果可能有个人数据（录音、相册、便签文件夹、SIM 卡）：只打印成没成、多长和 JSON 的键，不打印内容。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/app-tools.sh
set -uo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
FAKE_TURN_WAIT=200
FAILED='' value=''
trap 'rm -f results-*.jsonl' EXIT

# 每个应用调的只读工具：应用|工具|参数|结果有没有个人数据（有就只打印键）
probes='com.android.calendar|query_legal_holidays|{"year":"2026","month":"10"}|public
com.android.phone|phone_network_settings|{"action":"get"}|personal
com.android.soundrecorder|manage_recording|{"action":"get_status"}|public
com.android.soundrecorder|search_recordings|{"limit":1}|personal
com.milink.service|milink_cast_settings|{"action":"get_status","target":"screen_projection"}|public
com.mipay.wallet|get_bookkeeping_categories|{"type":"expense"}|public
com.miui.bugreport|get_domains|{}|public
com.miui.gallery|gallery_list_collection|{}|personal
com.miui.home|home_settings|{"action":"get","target":"lock_layout"}|public
com.miui.mishare.connectivity|manage_file_transfer|{"action":"toggle_switch","switch_status":"query"}|public
com.miui.notes|folder_list|{}|personal
com.miui.voiceassist|get_current_package|{}|public
com.miui.weather2|func_version_message|{}|public
com.xiaomi.aicr|get_support_event_detail|{}|public'
apps=$(cut -d'|' -f1 <<<"$probes" | sort -u)
# 该被拒绝的：应用|工具|拒绝时要指向的工具
refusals='com.android.deskclock|deskclock_add_alarm|clock
com.miui.notes|note_write|edit_note'

steps=$(jq -nc --arg apps "$apps" --arg probes "$probes" --arg refusals "$refusals" '
  [{phone: "app_tools", input: {}}]
  + [$apps | split("\n")[] | {phone: "app_tools", input: {app: .}}]
  + [$probes | split("\n")[] | split("|") | {phone: "app_tool", input: {app: .[0], tool: .[1], arguments: (.[2] | fromjson)}}]
  + [$refusals | split("\n")[] | split("|") | {phone: "app_tool", input: {app: .[0], tool: .[1], arguments: {}}}]')
fake_turn read 系统应用工具测试 "$steps" || { echo "读的这一轮没跑完"; exit 1; }

echo "== app_tools"
result_text read 1
n=1
for app in $apps; do
  n=$((n + 1))
  text=$(result_text read $n)
  if [[ $(result_error read $n) == true || $text != "$app ("* ]]; then
    echo "说明 $app：出错 $(head -c 200 <<<"$text")"
    FAILED=1
  else
    echo "说明 $app：$(grep -c '^## ' <<<"$text") 个工具"
  fi
done
echo "== 只读调用"
while IFS='|' read -r app tool _ kind; do
  n=$((n + 1))
  text=$(result_text read $n)
  [[ $app == com.miui.home ]] && value=$(tr -d '[:space:]' <<<"$text")
  if [[ $(result_error read $n) == true ]]; then
    echo "失败 $app $tool：$(head -c 300 <<<"$text")"
    FAILED=1
  elif [[ $kind == public ]]; then
    echo "成功 $app $tool（${#text} 字）：$(tr '\n' ' ' <<<"$text" | head -c 300)"
  else
    echo "成功 $app $tool（${#text} 字），键：$(jq -rc '[paths | map(if type == "number" then "#" else . end) | join(".")] | unique | join(" ")' <<<"$text" 2>/dev/null | head -c 300 || echo 不是 JSON)"
  fi
done <<<"$probes"
echo "== 被取代的"
while IFS='|' read -r app tool use; do
  n=$((n + 1))
  text=$(result_text read $n)
  if [[ $(result_error read $n) == true && $text == *"use $use"* ]]; then
    echo "$app $tool 被拒绝，指向 $use"
  else
    echo "$app $tool 没有被拒绝：$(head -c 200 <<<"$text")"
    FAILED=1
  fi
done <<<"$refusals"

# 写：桌面「锁定桌面布局」设成它现在的值，再读回
if [[ $value == true || $value == false ]]; then
  fake_turn write 系统应用工具测试 "$(jq -nc --arg v "$value" '[
    {phone: "app_tool", input: {app: "com.miui.home", tool: "home_settings", arguments: {action: "set", target: "lock_layout", value: $v}}},
    {phone: "app_tool", input: {app: "系统桌面", tool: "home_settings", arguments: {action: "get", target: "lock_layout"}}}]')" || exit 1
  after=$(result_text write 2 | tr -d '[:space:]')
  if [[ $(result_error write 1) == false && $after == "$value" ]]; then
    echo "桌面设置写回原值（$value）后读回一致：$(result_text write 1 | head -c 120)"
  else
    echo "桌面设置写入不对：写 $(result_text write 1 | head -c 200)；读回 $after"
    FAILED=1
  fi
else
  echo "没读到桌面设置的现值（$value），跳过写入"
  FAILED=1
fi

[[ -z $FAILED ]] || { echo "有应用没调通"; exit 1; }
echo "全部调通"
