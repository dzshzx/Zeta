# shellcheck shell=bash disable=SC2034 # 这里的变量给 source 它的脚本用
# 设备脚本的公用部分。脚本开头 source 它：
#   . "$ZETA_ROOT/tools/phone-tests/lib.sh"
# 用的是 tools/phone-run 给的 vd、android、$ZETA_ROOT；函数在 set -euo pipefail 下也能用。

HERE=$ZETA_ROOT/tools/phone-tests

# 测试版的托管目录：每个对话一个子目录，out.jsonl 是 Claude（或假 Claude）的输出
HOSTS=/data/local/zeta/debian/rootfs/home/dzshzx/.cache/zeta-dev/hosts

# 聊天页的输入框、发送键（整屏 1220×2656 的坐标，冷启动后的新对话页）
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548

# 输入栏的权限模式按钮（第二个圆钮）和它往上弹的菜单；choose_mode <模式> 只改当前这个对话
MODE_X=228 MODE_Y=2542
choose_mode() {
  local y
  case $1 in
    default) y=1863 ;; acceptEdits) y=1993 ;; plan) y=2123 ;; auto) y=2252 ;; bypassPermissions) y=2382 ;;
    *) echo "choose_mode：不认识的模式 $1" >&2; return 1 ;;
  esac
  vd tap $MODE_X $MODE_Y >/dev/null
  sleep 1
  vd tap 220 $y >/dev/null
  sleep 1
}

# 手机上 Claude Code 设置里的默认权限模式（settings.json 的 permissions.defaultMode，没设是 default）；只取这一个键
claude_default_mode() {
  local m
  m=$(android "grep -o '\"defaultMode\" *: *\"[A-Za-z]*\"' ${HOSTS%/.cache/*}/.claude/settings.json" | head -1 | grep -o '"[A-Za-z]*"$' | tr -d '"' || true)
  echo "${m:-default}"
}

# send <文字> [打完字后等几秒，默认 0.5]：在聊天页的输入框里输入并发送（vd text 只能输 ASCII）
send() {
  vd tap $INPUT_X $INPUT_Y
  sleep 1
  vd text "$1"
  sleep "${2:-0.5}"
  vd tap $SEND_X $SEND_Y
}

# 这次对话的托管日志放进 log：起的是假 Claude（init 里有 fake_claude）的、最新的那份。
# NEWER 设成手机上的一个文件时，只看比它新的（先 android "touch <文件>"，别的测试留下的假 Claude 日志就不算）。还没有时 log 是空的。
NEWER=''
log=''
find_log() {
  log=$(android "set -- \$(find $HOSTS -mindepth 2 -maxdepth 2 -name out.jsonl ${NEWER:+-newer $NEWER} 2>/dev/null); [ \$# -gt 0 ] || exit 0; \
set -- \$(grep -l '\"fake_claude\":true' \"\$@\"); [ \$# -gt 0 ] || exit 0; ls -t \"\$@\" | head -n 1" | tr -d '\r' || true)
}

# wait_result <第几轮> <最多等几秒>：等到日志里有第 n 个 result（一轮结束），放进 RESULT。
# 没等到时说一句、返回 1（要不要截图、退出由脚本定：截图可能照到个人数据）
RESULT=''
wait_result() {
  local n=$1 seconds=$2 count=0 i
  for ((i = 0; i < seconds * 2; i++)); do
    [[ -n $log ]] || find_log
    [[ -n $log ]] && count=$(android "grep -c '\"type\":\"result\"' $log" || true)
    if ((${count:-0} >= n)); then
      RESULT=$(android "grep '\"type\":\"result\"' $log | sed -n '${n}p'")
      return 0
    fi
    sleep 0.5
  done
  echo "第 $n 轮 $seconds 秒没等到 result（日志：${log:-还没有假 Claude 的日志，测试版起的可能是真的 claude}）"
  return 1
}

# pref <键>：测试版设置（zeta.xml）里这一项字符串的值，XML 转义已还原；没有这一项时是空的
pref() {
  vd prefs "$1" | sed -n "s#^ *<string name=\"$1\">\(.*\)</string>\$#\1#p" |
    sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g'
}

# task：设置里 id 为 $ID 的那个定时任务（一行 JSON）。脚本先设好 ID
task() {
  pref scheduledTasks | jq -c --arg id "$ID" '.[] | select(.id == $id)'
}

# wait_run <第几次>：等测试任务的下一次运行跑完（lastRun 不再是 running、at 比上一次新），放进 RUN；60 秒没等到就截图、退出
RUN='' last_at=0
wait_run() {
  for _ in $(seq 60); do
    RUN=$(task | jq -c '.lastRun // empty')
    if [[ -n $RUN ]] && (($(jq -r .at <<<"$RUN") > last_at)) && [[ $(jq -r .outcome <<<"$RUN") != running ]]; then
      last_at=$(jq -r .at <<<"$RUN")
      return
    fi
    sleep 1
  done
  echo "第 $1 次运行 60 秒没跑完：$(task)"
  vd shot "run-$1-timeout.png" >/dev/null
  exit 1
}

# control_errors <日志>：假 Claude 的托管日志里控制请求答的错，一行一条（假 Claude 不查订阅额度，那条不算）
control_errors() {
  jq -c 'select(.type == "control_response" and .response.subtype == "error" and .response.error != "假 Claude 不查订阅额度") | .response.error' "$1"
}

# 工具结果的文字：出错时 content 是一段文字，成功时是 [{type: text, text}]
TEXT_OF='.message.content[0].content | if type == "string" then . else ([.[]? | .text // empty] | join("\n")) end'
# 一条工具结果投影成 {text, error}
RESULT_OF="{text: ($TEXT_OF), error: (.message.content[0].is_error // false)}"

# 前台的 Activity（「包名/类名」），不在前台时空
resumed() { android "dumpsys activity activities | grep -E 'ResumedActivity'" | grep -o "$PKG/[A-Za-z0-9_.]*" | head -1; }

# fake_turn <名字（ASCII）> <标题> <步骤的 JSON 数组>：跑一轮假 Claude（步骤后面补一句「好了。」），等它跑完（最多 FAKE_TURN_WAIT 秒，
# 默认 120），这一轮的工具结果按顺序一行一个写进 results-<名字>.jsonl（{text, error}）；result_text、result_error 按序号取。
FAKE_TURNS=0
fake_turn() {
  local name=$1 title=$2 steps=$3 before=0
  jq -nc --arg t "$title" --argjson steps "$steps" '{title: $t, steps: ($steps + [{text: "好了。"}])}' >"turn-$name.jsonl"
  vd fake "$PWD/turn-$name.jsonl" >/dev/null
  [[ -n $log ]] && before=$(android "grep -c '\"type\":\"tool_result\"' $log" || true)
  FAKE_TURNS=$((FAKE_TURNS + 1))
  send "$name"
  wait_result $FAKE_TURNS "${FAKE_TURN_WAIT:-120}" || return 1
  android "grep '\"type\":\"tool_result\"' $log | tail -n +$((${before:-0} + 1))" |
    jq -c "$RESULT_OF" >"results-$name.jsonl"
}

# result_text <名字> <第几个，从 1 起>、result_error <名字> <第几个>：fake_turn 那一轮第 n 个工具结果的文字、是否出错
result_text() { sed -n "${2}p" "results-$1.jsonl" | jq -r .text; }
result_error() { sed -n "${2}p" "results-$1.jsonl" | jq -r .error; }
