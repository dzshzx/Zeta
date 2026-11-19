#!/bin/bash
# 真机：search_personal 的 photo_content（照片按图里的文字、画面标签、拍摄地点找，读小米澎湃 AI 引擎在本机的相册索引）。
# 用假 Claude（fake/photo-content.jsonl）经 Zeta 真调手机工具：标签「猫」两页、文字「微信」、地点或文字「市」、不给 query 的出错；
# 第二轮用第一页的第一个编号调 view_media，确认编号能接着看图。照片和识别出的文字是个人数据：只输出条数、各类命中的个数、
# 结果首行去掉括号里的说明，不输出内容；测完删掉这次对话的托管日志（里面有工具结果）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/photo-content.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

# 这次对话的托管日志：起的是假 Claude、在 ref 之后建的（别的测试留下的假 Claude 日志不算）
ref=/data/local/tmp/zeta-photo-content.ref
NEWER=$ref
cleanup() {
  # 这次对话的托管目录：里面的工具结果有照片里的文字
  if [[ $log == "$HOSTS"/*/out.jsonl ]]; then
    android "rm -r '${log%/out.jsonl}'" && echo "删了这次对话的托管日志"
  fi
  android "rm -f $ref"
  rm -f view-media.jsonl
}
trap cleanup EXIT
trap 'exit 143' TERM INT

# 第 $1 个工具结果（从 1 数）：错误标记和拼起来的文字
result() { android "grep '\"type\":\"tool_result\"' $log | sed -n '$1p'" | jq -c '.message.content[0]'; }
text_of() { jq -r 'if (.content | type) == "string" then .content else ([.content[] | .text // empty] | join("\n")) end' <<<"$1"; }

# 一次搜索的摘要：首行去掉括号里的说明、列出几条、各类命中几条；编号放进 IDS，不输出
IDS=''
summary() {
  local label=$1 r=$2 text
  text=$(text_of "$r")
  IDS=$(grep -oE '^#[0-9]+' <<<"$text" | tr -d '#' | tr '\n' ' ' || true)
  printf '%s：出错=%s，首行「%s」，列出 %s 条；文字 %s、标签 %s、地点 %s、视频 %s、命中处超出 %s\n' "$label" \
    "$(jq -r '.is_error // false' <<<"$r")" "$(head -n 1 <<<"$text" | sed -E 's/（[^）]*）//')" \
    "$(grep -cE '^#[0-9]+' <<<"$text" || true)" "$(grep -c '文字「' <<<"$text" || true)" "$(grep -cE '— (.*；)?标签 ' <<<"$text" || true)" \
    "$(grep -c '；*地点 ' <<<"$text" || true)" "$(grep -c '\[视频' <<<"$text" || true)" "$(grep -c '命中处在取到的' <<<"$text" || true)"
}

android "touch $ref"
vd fake "$HERE/fake/photo-content.jsonl"
start=$SECONDS
send "photo-content-1"
# 一次按内容搜要起两次 root 辅助进程，多等一会
wait_result 1 90 || exit 1
echo "第 1 轮：$((SECONDS - start)) 秒"

fail=0
r=$(result 1); summary "标签「猫」第一页" "$r"; page1=$IDS
[[ $(jq -r '.is_error // false' <<<"$r") == false ]] || fail=1
r=$(result 2); summary "标签「猫」第二页" "$r"; page2=$IDS
[[ $(jq -r '.is_error // false' <<<"$r") == false ]] || fail=1
for id in $page1; do
  if [[ " $page2 " == *" $id "* ]]; then echo "两页有重复的编号"; fail=1; fi
done
r=$(result 3); summary "文字「微信」" "$r"; [[ $(jq -r '.is_error // false' <<<"$r") == false ]] || fail=1
r=$(result 4); summary "「市」" "$r"; [[ $(jq -r '.is_error // false' <<<"$r") == false ]] || fail=1
r=$(result 5)
echo "不给 query：出错=$(jq -r '.is_error // false' <<<"$r")，$(text_of "$r")"
[[ $(jq -r '.is_error' <<<"$r") == true && $(text_of "$r") == *"要给 query"* ]] || fail=1

first=${page1%% *}
if [[ -n $first ]]; then
  # 第二轮：用第一页的第一个编号看图
  jq -cn --argjson id "$first" '{title: "假 Claude · 看照片", steps: [{phone: "view_media", input: {id: $id, max_edge: 256}}, {text: "看完了。"}]}' >view-media.jsonl
  vd fake "$PWD/view-media.jsonl"
  send "photo-content-2"
  wait_result 2 90 || exit 1
  r=$(result 6)
  echo "view_media 第一页第一个编号：出错=$(jq -r '.is_error // false' <<<"$r")，图片 $(jq '[.content[]? | select(.type == "image")] | length' <<<"$r") 张，$(text_of "$r" | grep -oE '^画面 [0-9]+x[0-9]+' || echo 没有画面行)"
  [[ $(jq -r '.is_error // false' <<<"$r") == false && $(jq '[.content[]? | select(.type == "image")] | length' <<<"$r") == 1 ]] || fail=1
else
  echo "「猫」没有结果，跳过 view_media"
fi
exit $fail
