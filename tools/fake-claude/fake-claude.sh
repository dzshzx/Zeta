#!/bin/bash
# 假 Claude：按剧本回话的 `claude -p --input-format stream-json --output-format stream-json` 替身，只给测试版的设备测试用。
# 不连模型、不写会话记录、不读 ~/.claude；照剧本输出 Claude Code 2.1.328 的事件。事件的样子照真 claude 在临时 HOME 里
# 连假 Messages API 录下来的写，FakeClaudeContractTest 拿同一段对话的真输出和本脚本的输出逐条比对。
#
# 怎么接进来：测试版（ZETA_NAME=zeta-dev）的 zeta_host.sh 看到 ~/.config/zeta-dev/fake-claude/scenario.jsonl 就起本脚本，
# 正式版永远起真的 claude。剧本由 `vd.py fake <剧本>` 连同本脚本放进去，`vd.py stop` 删掉；
# 剧本删掉后，闲着或在等 Zeta 应答的假 Claude 一秒内自己退出。
#
# 剧本（scenario.jsonl）一行一轮，按收到用户消息的先后依次取用；同时开着的几个对话共用一份进度（记在 scenario.jsonl.turn）：
#   {"title":"一轮后 Zeta 要标题时答的标题（可省）","steps":[步骤, …]}
# 步骤是下面几种之一：
#   {"text":"回答","chunk":8,"delay":40}         流式文字：每段 chunk 个字（默认 8），段间隔 delay 毫秒（默认 40）
#   {"thinking":"思考内容"}                       思考块，同样分段流出
#   {"phone":"open_app","input":{…},"ask":false}  调一次手机工具：经控制通道向 Zeta 的 phone 服务器发 tools/call，
#                                                 等 Zeta 应答，把结果写回 tool_result。ask 为 true 时先发 can_use_tool 等批准
#   {"tool":"Bash","input":{…},"result":"输出","is_error":false,"ms":0,"ask":false}
#                                                 Claude Code 自己的工具：结果照剧本写，不经 Zeta；ms 是工具跑多久
#   {"pause":1500}                                停顿（毫秒）
#   {"error":"API Error: 500 …","status":500}     这一轮出错结束（result 的 is_error 为 true）
#   {"emit":{…}}                                  原样输出一行（没写 session_id 的补上），用来造后台任务之类的事件
#   {"exit":1}                                    进程立即退出，测进程意外结束
# 剧本用完后再来的消息按出错结束。流文字、停顿、等工具时都能被 interrupt 打断，收尾和真 Claude Code 一样。
# 控制请求（initialize、get_context_usage、set_model 等）随到随答；end_session 照真 Claude Code 结束会话：进行中的这一轮按打断收尾，然后退出。一轮进行中发来的消息排队（command_lifecycle queued），
# 这一轮结束后另起一轮；真 Claude Code 会在下一个工具边界把它并进当前这一轮，这里不模拟。
#
# 在工作站上直接跑（测试用）：FAKE_CLAUDE_SCENARIO=<剧本> tools/fake-claude/fake-claude.sh -p …
set -u
export LC_ALL=C.UTF-8 TZ=UTC
HERE=$(cd "$(dirname "$0")" && pwd)
SCENARIO=${FAKE_CLAUDE_SCENARIO:-$HERE/scenario.jsonl}
PROGRESS=$SCENARIO.turn
VERSION=2.1.328
MODEL=claude-fake
WINDOW=200000

SESSION='' FORK='' MODE='' EFFORT=medium PHONE=''
while [ $# -gt 0 ]; do
  case $1 in
    --resume | --session-id) SESSION=${2:-}; shift ;;
    --fork-session) FORK=1 ;;
    --permission-mode) MODE=${2:-default}; shift ;;
    --effort) EFFORT=${2:-medium}; shift ;;
    --mcp-config) case ${2:-} in *'"phone"'*) PHONE=1 ;; esac; shift ;;
    --model | --name | --append-system-prompt | --system-prompt | --input-format | --output-format | --permission-prompt-tool | \
      --max-turns | --allowedTools | --disallowedTools | --add-dir | --settings) shift ;;
  esac
  shift
done
# 没带 --permission-mode 时和真的一样，用 settings.json 里的默认（permissions.defaultMode）
[ -n "$MODE" ] || MODE=$(jq -r '.permissions.defaultMode // empty' "${HOME:-/nonexistent}/.claude/settings.json" 2>/dev/null)
MODE=${MODE:-default}
MODE=${MODE//[!A-Za-z]/} EFFORT=${EFFORT//[!A-Za-z]/}

# ---- 小工具：不起子进程的随机 id、时刻、输出 ----

uuid() {
  printf -v U '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x' $RANDOM $RANDOM $RANDOM $((RANDOM & 0xfff)) \
    $(((RANDOM & 0x3fff) | 0x8000)) $RANDOM $RANDOM $RANDOM
}
now() { local t=${EPOCHREALTIME//[!0-9]/}; NOW=$((10#$t / 1000)); }
stamp() {
  local t=${EPOCHREALTIME//[!0-9]/}
  printf -v T '%(%Y-%m-%dT%H:%M:%S)T.%03dZ' "${t:0:${#t}-6}" "$((10#${t: -6} / 1000))"
}
out() { printf '%s\n' "$1"; }
case $SESSION in '' | *[!0-9a-fA-F-]*) SESSION='' ;; esac
[ -n "$SESSION" ] && [ -z "$FORK" ] || { uuid; SESSION=$U; }

# ---- 读标准输入：控制请求随到随答，用户消息排队 ----

declare -A AWAIT=() RESP=()
QUEUE=()
BUF='' EOF='' BUSY='' INTERRUPTED='' REJECTED='' END_REPLY='' TITLE=''

# 读一行标准输入并处理，最多等 $1 毫秒
poll() {
  local t=$1 line secs
  ((t < 1)) && t=1
  if [ -n "$EOF" ]; then
    printf -v secs '%d.%03d' $((t / 1000)) $((t % 1000))
    sleep "$secs"
    return
  fi
  printf -v secs '%d.%03d' $((t / 1000)) $((t % 1000))
  if IFS= read -r -t "$secs" line; then
    line=$BUF$line BUF=''
    [ -n "$line" ] && handle "$line"
  elif (($? > 128)); then
    BUF+=$line # 超时：没读完的半行留着
  else
    EOF=1
    line=$BUF$line BUF=''
    [ -n "$line" ] && handle "$line"
  fi
}

# 停 $1 毫秒，其间照常处理输入；被打断返回 1
pause() {
  now
  local until=$((NOW + $1))
  while :; do
    [ -n "$INTERRUPTED" ] && return 1
    now
    ((NOW >= until)) && return 0
    poll $((until - NOW))
  done
}

handle() {
  local fields typ rid sub id
  fields=$(jq -r '[.type // "", .request_id // .response.request_id // "", .request.subtype // .response.subtype // "", .uuid // ""] | join("\u001f")' <<<"$1" 2>/dev/null) || return
  IFS=$'\x1f' read -r typ rid sub id <<<"$fields"
  case $typ in
    user)
      case $id in '' | *[!0-9a-fA-F-]*) uuid; id=$U ;; esac
      lifecycle "$id" queued
      QUEUE+=("$id")
      ;;
    control_request) control "$rid" "$sub" "$1" ;;
    control_response) [ -n "${AWAIT[$rid]:-}" ] && RESP[$rid]=$1 ;;
  esac
}

reply() {
  jq -nc --arg id "$1" --argjson r "$2" '{type: "control_response", response: {subtype: "success", request_id: $id, response: $r}}'
}
# 没有 response 一项的 success 应答
ok() {
  jq -nc --arg id "$1" '{type: "control_response", response: {subtype: "success", request_id: $id}}'
}
refuse() {
  jq -nc --arg id "$1" --arg e "$2" --arg code "${3:-}" \
    '{type: "control_response", response: ({subtype: "error", request_id: $id, error: $e} + if $code == "" then {} else {error_code: $code} end)}'
}

queued_json() { printf '%s\n' "${QUEUE[@]}" | jq -Rsc 'split("\n") | map(select(. != ""))'; }

# Zeta 发来的控制请求。应答的样子照真 Claude Code 录的；commands、models 不给，免得 Zeta 拿假的去刷新它缓存的列表
control() {
  local rid=$1 sub=$2 line=$3 value
  case $sub in
    initialize)
      out "$(reply "$rid" "$(jq -nc --arg mode "$MODE" --arg v "$VERSION" --argjson pid $$ \
        '{agents: [], output_style: "default", account: {tokenSource: "none", apiKeySource: "none", apiProvider: "firstParty"},
          pid: $pid, claude_code_version: $v, current_permission_mode: $mode, session_state: "idle", fake_claude: true}')")"
      ;;
    get_context_usage)
      out "$(reply "$rid" "{\"totalTokens\":$CTX,\"maxTokens\":$WINDOW,\"rawMaxTokens\":$WINDOW,\"percentage\":$((CTX * 100 / WINDOW)),\"model\":\"$MODEL\",\"categories\":[],\"memoryFiles\":[],\"mcpTools\":[],\"agents\":[],\"autoCompactThreshold\":$((WINDOW - 33000)),\"isAutoCompactEnabled\":true,\"apiUsage\":null}")"
      ;;
    get_settings)
      out "$(reply "$rid" "$(jq -nc --arg m "$MODEL" --arg e "$EFFORT" '{effective: {}, sources: [], applied: {model: $m, effort: $e}}')")"
      ;;
    get_usage) out "$(refuse "$rid" "假 Claude 不查订阅额度")" ;;
    set_model)
      value=$(jq -r '.request.model // "default"' <<<"$line")
      uuid
      stamp
      jq -nc --arg m "$value" --arg s "$SESSION" --arg u "$U" --arg t "$T" \
        '{type: "user", message: {role: "user", content: "<local-command-stdout>Set model to `\($m)`</local-command-stdout>"},
          session_id: $s, parent_tool_use_id: null, uuid: $u, timestamp: $t, isReplay: true}'
      out "$(reply "$rid" "$(jq -nc --arg m "$value" '{model: $m}')")"
      ;;
    set_permission_mode)
      value=$(jq -r '.request.mode // "default"' <<<"$line")
      case $value in *[!A-Za-z]*) ;; *) MODE=$value ;; esac
      out "$(reply "$rid" "{\"mode\":\"$MODE\"}")"
      uuid
      out "{\"type\":\"system\",\"subtype\":\"status\",\"status\":null,\"permissionMode\":\"$MODE\",\"uuid\":\"$U\",\"session_id\":\"$SESSION\"}"
      ;;
    apply_flag_settings)
      value=$(jq -r '.request.settings.effortLevel // empty' <<<"$line")
      case $value in *[!A-Za-z]*) ;; ?*) EFFORT=$value ;; esac
      out "$(ok "$rid")"
      ;;
    generate_session_title) out "$(reply "$rid" "$(jq -nc --arg t "${TITLE:-假 Claude 对话}" '{title: $t}')")" ;;
    interrupt)
      # 这一轮进行中的，收尾时再答（真 Claude Code 先发 notifications/cancelled 之类，再答 interrupt）
      if [ -n "$BUSY" ] && [ -z "$INTERRUPTED" ]; then
        INTERRUPTED=${rid:-interrupt}
      else
        out "$(reply "$rid" "{\"still_queued\":$(queued_json)}")"
      fi
      ;;
    cancel_async_message)
      value=$(jq -r '.request.message_uuid // ""' <<<"$line")
      local i kept=() found=''
      for i in "${QUEUE[@]}"; do if [ "$i" = "$value" ] && [ -z "$found" ]; then found=1; else kept+=("$i"); fi; done
      QUEUE=("${kept[@]}")
      if [ -n "$found" ]; then
        lifecycle "$value" cancelled
        out "$(reply "$rid" "$(jq -nc --arg u "$value" '{cancelled: true, message_uuid: $u}')")"
      else
        out "$(reply "$rid" '{"cancelled":false}')"
      fi
      ;;
    rewind_conversation)
      if [ -n "$BUSY" ]; then
        out "$(reply "$rid" '{"rewound":false,"reason":"turn_running"}')"
      else
        out "$(reply "$rid" "$(jq -c '{rewound: true, targetMessageUuid: .request.target_message_uuid, prefillText: "", precedingAssistantUuid: null}' <<<"$line")")"
      fi
      ;;
    stop_task) out "$(reply "$rid" '{}')" ;;
    end_session)
      # 结束会话（zeta_host.sh 的 end_claude 发的）：闲着的答 success 后退出码 0；这一轮进行中的照打断收尾，
      # 应答放在打断时答 interrupt 的位置（手机工具调用中先发 notifications/cancelled），收完退出码 1
      [ -n "$BUSY" ] || { out "$(ok "$rid")"; exit 0; }
      END_REPLY=$(ok "$rid")
      if [ -n "$INTERRUPTED" ]; then out "$END_REPLY"; else INTERRUPTED=end_session; fi
      ;;
    get_task_output) out "$(refuse "$rid" "get_task_output: no shell or Monitor task with that task_id in this session")" ;;
    *) out "$(refuse "$rid" "Unsupported control request subtype: $sub" unsupported_request)" ;;
  esac
}

# 向 Zeta 发控制请求（$1 是 request 一项），请求号放进 ASKED；应答由 await_zeta 等
tell_zeta() {
  uuid
  ASKED=$U
  AWAIT[$ASKED]=1
  out "{\"type\":\"control_request\",\"request_id\":\"$ASKED\",\"request\":$1}"
}

# 发控制请求并等应答，见 await_zeta
ask_zeta() { tell_zeta "$1" && await_zeta "${2:-}"; }

# 等 Zeta 对 ASKED 的 control_response，放进 ANSWER；$1 是最多等几毫秒（默认一直等）。
# 被打断、剧本被删、标准输入关了或者等超时返回 1
await_zeta() {
  now
  local until=$((NOW + ${1:-999999999}))
  while [ -z "${RESP[$ASKED]+x}" ]; do
    if [ -n "$INTERRUPTED" ] || [ -n "$EOF" ] || [ ! -f "$SCENARIO" ] || { now; ((NOW >= until)); }; then
      unset "AWAIT[$ASKED]"
      return 1
    fi
    poll 1000
  done
  ANSWER=${RESP[$ASKED]}
  unset "AWAIT[$ASKED]" "RESP[$ASKED]"
}

# 发给 Zeta 的 phone 服务器一条 MCP 消息，不等应答
mcp_tell() { tell_zeta "{\"subtype\":\"mcp_message\",\"server_name\":\"phone\",\"message\":$1}"; }

# 发给 Zeta 的 phone 服务器一条 MCP 消息并等应答
mcp() { mcp_tell "$1" && await_zeta "${2:-}"; }

# ---- 事件 ----

lifecycle() {
  uuid
  out "{\"type\":\"command_lifecycle\",\"command_uuid\":\"$1\",\"state\":\"$2\",\"uuid\":\"$U\",\"session_id\":\"$SESSION\"}"
}

# 一条 stream_event，$1 是 event 一项
sev() {
  uuid
  out "{\"type\":\"stream_event\",\"event\":$1,\"session_id\":\"$SESSION\",\"parent_tool_use_id\":null,\"uuid\":\"$U\",\"api_message_id\":\"$MSG\"}"
}

usage() { echo "{\"input_tokens\":$CTX,\"output_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}"; }

# 一条 assistant 消息，$1 是 content，$2 是追加的字段（以逗号开头）
assistant() {
  uuid
  stamp
  out "{\"type\":\"assistant\",\"message\":{\"id\":\"$MSG\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"$MODEL\",\"content\":$1,\"stop_reason\":null,\"stop_sequence\":null,\"usage\":$(usage),\"context_management\":null},\"parent_tool_use_id\":null,\"session_id\":\"$SESSION\",\"uuid\":\"$U\",\"timestamp\":\"$T\",\"user_message_uuid\":\"$CMD\",\"user_message_uuids\":[\"$CMD\"]${2:-}}"
}

# 一条 user 消息（工具结果、打断说明），$1 是 content，$2 是追加的字段（以逗号开头）
user() {
  uuid
  stamp
  out "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":$1},\"parent_tool_use_id\":null,\"session_id\":\"$SESSION\",\"uuid\":\"$U\",\"timestamp\":\"$T\"${2:-}}"
}

# 模型的一次回复（一个 API 消息）：开头先报 requesting
open_message() {
  [ -n "$MSG" ] && return
  uuid
  out "{\"type\":\"system\",\"subtype\":\"status\",\"status\":\"requesting\",\"session_id\":\"$SESSION\",\"uuid\":\"$U\",\"user_message_uuids\":[\"$CMD\"]}"
  uuid
  MSG=msg_fake_${U//-/}
  MSG=${MSG:0:33}
  BLOCK=0
  REQUESTS=$((REQUESTS + 1))
  CTX=$((CTX + 300))
  IN_T=$((IN_T + CTX))
  sev "{\"type\":\"message_start\",\"message\":{\"id\":\"$MSG\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"$MODEL\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":$(usage)}}"
}

# $1 是 stop_reason（JSON）
close_message() {
  [ -n "$MSG" ] || return 0
  sev "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":$1,\"stop_sequence\":null},\"usage\":{\"output_tokens\":$OUT_T}}"
  sev '{"type":"message_stop"}'
  MSG=''
}

# result：$1 subtype，$2 is_error，$3 stop_reason（JSON），$4 terminal_reason，$5 追加的字段（以逗号开头）
result() {
  now
  local took=$((NOW - TURN_T0))
  SUM_IN=$((SUM_IN + IN_T)) SUM_OUT=$((SUM_OUT + OUT_T))
  # modelUsage 是整个会话的累计；还没用过模型（比如第一轮就出错）时是空的
  local models={}
  ((SUM_IN + SUM_OUT > 0)) && models="{\"$MODEL\":{\"inputTokens\":$SUM_IN,\"outputTokens\":$SUM_OUT,\"cacheReadInputTokens\":0,\"cacheCreationInputTokens\":0,\"webSearchRequests\":0,\"costUSD\":0,\"contextWindow\":$WINDOW,\"maxOutputTokens\":32000}}"
  uuid
  out "{\"type\":\"result\",\"subtype\":\"$1\",\"is_error\":$2,\"duration_ms\":$took,\"duration_api_ms\":$took,\"num_turns\":$REQUESTS,\"stop_reason\":$3,\"session_id\":\"$SESSION\",\"total_cost_usd\":0,\"usage\":{\"input_tokens\":$IN_T,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":$OUT_T},\"modelUsage\":$models,\"permission_denials\":$DENIALS,\"terminal_reason\":\"$4\",\"uuid\":\"$U\",\"user_message_uuid\":\"$CMD\",\"user_message_uuids\":[\"$CMD\"]${5:-}}"
}

# ---- 步骤：返回 1 表示这一轮到此为止（被打断或出错结束） ----

# 文字、思考块：$1 是 text 或 thinking，$2 是步骤
step_stream() {
  local kind=$1 lines delay full chunk
  mapfile -t lines < <(jq -r --arg k "$kind" '(.delay // 40), (.[$k] | tojson),
    ((.chunk // 8) as $n | .[$k] as $s | range(0; $s | length; $n) as $i | $s[$i:$i + $n] | tojson)' <<<"$2")
  delay=${lines[0]//[!0-9]/} full=${lines[1]}
  open_message
  STREAMING=$kind PARTIAL=()
  if [ "$kind" = text ]; then
    sev "{\"type\":\"content_block_start\",\"index\":$BLOCK,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"
  else
    sev "{\"type\":\"content_block_start\",\"index\":$BLOCK,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}"
  fi
  for chunk in "${lines[@]:2}"; do
    pause "${delay:-40}" || return 1
    if [ "$kind" = text ]; then
      sev "{\"type\":\"content_block_delta\",\"index\":$BLOCK,\"delta\":{\"type\":\"text_delta\",\"text\":$chunk}}"
    else
      uuid
      out "{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens\":$(((${#PARTIAL[@]} + 1) * 3)),\"estimated_tokens_delta\":3,\"session_id\":\"$SESSION\",\"uuid\":\"$U\",\"user_message_uuid\":\"$CMD\"}"
      sev "{\"type\":\"content_block_delta\",\"index\":$BLOCK,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":$chunk}}"
    fi
    PARTIAL+=("$chunk")
    OUT_T=$((OUT_T + 3))
  done
  if [ "$kind" = text ]; then
    assistant "[{\"type\":\"text\",\"text\":$full}]"
    LAST_TEXT=$full
  else
    sev "{\"type\":\"content_block_delta\",\"index\":$BLOCK,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"ZmFrZQ==\"}}"
    assistant "[{\"type\":\"thinking\",\"thinking\":$full,\"signature\":\"ZmFrZQ==\"}]"
  fi
  sev "{\"type\":\"content_block_stop\",\"index\":$BLOCK}"
  BLOCK=$((BLOCK + 1)) STREAMING=''
}

# 工具调用的开头：tool_use 块。$1 工具名，$2 input（JSON），$3 input 的 JSON 文本（JSON 字符串），$4 追加给 assistant 的字段
tool_use() {
  open_message
  uuid
  TOOL_ID=toolu_fake_${U//-/}
  TOOL_ID=${TOOL_ID:0:35}
  sev "{\"type\":\"content_block_start\",\"index\":$BLOCK,\"content_block\":{\"type\":\"tool_use\",\"id\":\"$TOOL_ID\",\"name\":\"$1\",\"input\":{}}}"
  sev "{\"type\":\"content_block_delta\",\"index\":$BLOCK,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":$3}}"
  local extra=${4:-}
  assistant "[{\"type\":\"tool_use\",\"id\":\"$TOOL_ID\",\"name\":\"$1\",\"input\":$2}]" "${extra//@ID@/$TOOL_ID}"
  sev "{\"type\":\"content_block_stop\",\"index\":$BLOCK}"
  BLOCK=$((BLOCK + 1))
  OUT_T=$((OUT_T + 20))
}

# 问 Zeta 批不批（can_use_tool）：$1 工具名，$2 input，$3 追加的字段。批了返回 0，INPUT 换成批准时给的；
# 拒了写 tool_result 并返回 2；被打断、或拒绝时带 interrupt（这一轮停下，照真 Claude Code 收尾）返回 1
permission() {
  PHASE=ask
  ask_zeta "{\"subtype\":\"can_use_tool\",\"tool_name\":\"$1\"$3,\"input\":$2,\"permission_suggestions\":[{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"$1\"}],\"behavior\":\"allow\",\"destination\":\"localSettings\"}],\"tool_use_id\":\"$TOOL_ID\"}" || return 1
  PHASE=''
  local decision
  decision=$(jq -c '.response.response // {}' <<<"$ANSWER")
  if [ "$(jq -r '.behavior // "deny"' <<<"$decision")" = allow ]; then
    INPUT=$(jq -c --argjson old "$2" '.updatedInput // $old' <<<"$decision")
    DECIDED='{"decision":"accept","source":"user_temporary"}'
    return 0
  fi
  close_message '"tool_use"'
  DENIALS=$(jq -c --arg n "$1" --arg id "$TOOL_ID" --argjson i "$2" '. + [{tool_name: $n, tool_use_id: $id, tool_input: $i}]' <<<"$DENIALS")
  if [ "$(jq -r '.interrupt // false' <<<"$decision")" = true ]; then
    user "[{\"type\":\"tool_result\",\"content\":\"The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed.\",\"is_error\":true,\"tool_use_id\":\"$TOOL_ID\"}]" \
      ",\"tool_use_result\":\"User rejected tool use\",\"tool_result_meta\":[{\"id\":\"$TOOL_ID\",\"non_execution_kind\":\"user-rejected\"}]"
    user '[{"type":"text","text":"[Request interrupted by user for tool use]"}]'
    REJECTED=1
    return 1
  fi
  local message
  message=$(jq -c '.message // "用户拒绝了这次操作"' <<<"$decision")
  user "[{\"type\":\"tool_result\",\"content\":$message,\"is_error\":true,\"tool_use_id\":\"$TOOL_ID\"}]" \
    ",\"tool_use_result\":$(jq -c '"Error: " + .' <<<"$message"),\"tool_result_meta\":[{\"id\":\"$TOOL_ID\",\"non_execution_kind\":\"permission-rule\",\"permission_decision\":{\"decision\":\"reject\",\"source\":\"user_reject\"}}]"
  return 2
}

# 手机工具：真的经控制通道交给 Zeta 执行
step_phone() {
  local f name display ask
  mapfile -t f < <(jq -r '.phone, (.input // {} | tojson), (.input // {} | tojson | tojson), (.ask // false),
    (.phone | split("_") | map((.[:1] | ascii_upcase) + .[1:]) | join(" "))' <<<"$1")
  name=${f[0]//[!A-Za-z0-9_]/} INPUT=${f[1]} ask=${f[3]} display=${f[4]//\"/}
  local tool=mcp__phone__$name
  tool_use "$tool" "$INPUT" "${f[2]}" \
    ",\"tool_use_meta\":[{\"id\":\"@ID@\",\"display_name\":\"$display\",\"server_display_name\":\"phone\",\"tool_name\":\"$name\"}]"
  DECIDED='{"decision":"accept","source":"config","reason_type":"mode"}'
  if [ "$ask" = true ]; then
    permission "$tool" "$INPUT" ",\"mcp_server\":{\"name\":\"phone\",\"source\":\"sdk\"},\"display_name\":\"$display\""
    case $? in 1) return 1 ;; 2) return 0 ;; esac
    close_message '"tool_use"'
  fi
  if ! jq -e --arg n "$name" 'any(.[]; . == $n)' <<<"$PHONE_TOOLS" >/dev/null; then
    close_message '"tool_use"'
    user "[{\"type\":\"tool_result\",\"content\":\"<tool_use_error>Error: No such tool available: $tool</tool_use_error>\",\"is_error\":true,\"tool_use_id\":\"$TOOL_ID\"}]" \
      ",\"tool_use_result\":\"Error: No such tool available: $tool\""
    return 0
  fi
  # 不用问的工具，真 Claude Code 在这条回复收尾（message_delta）之前就开始调用
  PHASE=tool MCP_CALL=$MCP_NEXT
  MCP_NEXT=$((MCP_NEXT + 1))
  mcp_tell "{\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$INPUT,\"_meta\":{\"claudecode/toolUseId\":\"$TOOL_ID\",\"progressToken\":$MCP_CALL}},\"jsonrpc\":\"2.0\",\"id\":$MCP_CALL}"
  close_message '"tool_use"'
  await_zeta || return 1
  PHASE=''
  # 出错的结果（isError）和真 Claude Code 一样：内容拼成一段文字，tool_use_result 前面加 Error:
  mapfile -t f < <(jq -c --arg id "$TOOL_ID" '.response.response.mcp_response
    | (.result // {content: [{type: "text", text: (.error.message // "MCP error")}], isError: true})
    | ([.content[]? | .text // empty] | join("\n")) as $text
    | if .isError then [{type: "tool_result", content: $text, is_error: true, tool_use_id: $id}], "Error: " + $text
      else [{tool_use_id: $id, type: "tool_result", content: (.content // [])}], (.content // []) end' <<<"$ANSWER")
  user "${f[0]}" ",\"tool_use_result\":${f[1]},\"tool_result_meta\":[{\"id\":\"$TOOL_ID\",\"permission_decision\":$DECIDED}]"
}

# Claude Code 自己的工具：结果照剧本写
step_tool() {
  local f name ask ms
  mapfile -t f < <(jq -r '.tool, (.input // {} | tojson), (.input // {} | tojson | tojson), (.ask // false), (.ms // 0),
    ([{type: "tool_result", content: (.result // ""), is_error: (.is_error // false), tool_use_id: "@ID@"}] | tojson),
    (if .is_error then "Error: " + (.result | if type == "string" then . else tojson end)
     elif has("tool_use_result") then .tool_use_result
     elif .tool == "Bash" then {stdout: (.result // ""), stderr: "", interrupted: false, isImage: false, noOutputExpected: false}
     else .result // "" end | tojson)' <<<"$1")
  name=${f[0]//[!A-Za-z0-9_]/} INPUT=${f[1]} ask=${f[3]} ms=${f[4]//[!0-9]/}
  tool_use "$name" "$INPUT" "${f[2]}"
  DECIDED='{"decision":"accept","source":"config","reason_type":"mode"}'
  if [ "$ask" = true ]; then
    permission "$name" "$INPUT" ""
    case $? in 1) return 1 ;; 2) return 0 ;; esac
  fi
  close_message '"tool_use"'
  PHASE=tool MCP_CALL=''
  pause "${ms:-0}" || return 1
  PHASE=''
  user "${f[5]//@ID@/$TOOL_ID}" ",\"tool_use_result\":${f[6]},\"tool_result_meta\":[{\"id\":\"$TOOL_ID\",\"permission_decision\":$DECIDED}]"
}

# API 出错：真 Claude Code 补一条 <synthetic> 的 assistant 消息，result 的 is_error 为 true；
# 写了 subtype（如 error_max_turns）的照那种结局，错误放在 errors 里
step_error() {
  local f
  mapfile -t f < <(jq -r '(.error | tojson), (.status // null | tojson), (.subtype // "success")' <<<"$1")
  close_message null
  uuid
  out "{\"type\":\"system\",\"subtype\":\"status\",\"status\":\"requesting\",\"session_id\":\"$SESSION\",\"uuid\":\"$U\",\"user_message_uuids\":[\"$CMD\"]}"
  if [ "${f[2]}" = success ]; then
    uuid
    MSG=$U
    assistant "[{\"type\":\"text\",\"text\":${f[0]}}]" ",\"error\":\"unknown\",\"is_api_error_message\":true,\"api_error_status\":${f[1]}"
    MSG=''
    result success true '"stop_sequence"' api_error ",\"api_error_status\":${f[1]},\"result\":${f[0]}"
  else
    result "${f[2]//[!a-z_]/}" true null "${f[2]//[!a-z_]/}" ",\"errors\":[${f[0]}]"
  fi
  lifecycle "$CMD" cancelled
}

# 被打断后的收尾，照真 Claude Code：流到一半的文字、在跑的工具、等批准时各有各的样子
interrupted() {
  local tool_text='"The user doesn'"'"'t want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed. Correction to the text above: this call was stopped while running, not refused before it ran. It may have run in part or in full, and its changes may have been written. No permission prompt was involved. Work it started elsewhere (a remote command, a background process) may still be running, so check before starting the same work again. The instruction to stop and wait still stands."'
  local answer
  answer=$END_REPLY
  [ "$INTERRUPTED" = end_session ] || answer=$(reply "$INTERRUPTED" "{\"still_queued\":$(queued_json)}")
  case $PHASE in
    ask | tool)
      if [ "$PHASE" = ask ]; then
        out "$answer"
        out "{\"type\":\"control_cancel_request\",\"request_id\":\"$ASKED\"}"
        close_message '"tool_use"'
      else
        if [ -n "$MCP_CALL" ]; then
          uuid
          out "{\"type\":\"control_request\",\"request_id\":\"$U\",\"request\":{\"subtype\":\"mcp_message\",\"server_name\":\"phone\",\"message\":{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":$MCP_CALL,\"reason\":\"AbortError: remote-cancel\"}}}}"
        fi
        out "$answer"
      fi
      user "[{\"type\":\"tool_result\",\"content\":$tool_text,\"is_error\":true,\"tool_use_id\":\"$TOOL_ID\"}]"
      user '[{"type":"text","text":"[Request interrupted by user for tool use]"}]'
      result error_during_execution true '"tool_use"' aborted_tools ',"errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=tool_use"]'
      ;;
    *)
      out "$answer"
      if [ -n "$STREAMING" ] && [ ${#PARTIAL[@]} -gt 0 ]; then
        local partial
        partial=$(printf '%s\n' "${PARTIAL[@]}" | jq -sc 'add')
        if [ "$STREAMING" = text ]; then
          assistant "[{\"type\":\"text\",\"text\":$partial}]" ',"aborted":true'
        else
          assistant "[{\"type\":\"thinking\",\"thinking\":$partial,\"signature\":\"\"}]" ',"aborted":true'
        fi
      fi
      user '[{"type":"text","text":"[Request interrupted by user]"}]'
      if [ -n "$MSG" ]; then
        [ -n "$STREAMING" ] && sev "{\"type\":\"content_block_stop\",\"index\":$BLOCK}"
        close_message null
      fi
      result error_during_execution true null aborted_streaming ',"errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"]'
      ;;
  esac
  lifecycle "$CMD" cancelled
}

# 剧本里下一轮是第几行（几个假 Claude 进程共用，按到来顺序）
next_turn() {
  local n=0
  {
    flock 9
    n=$(cat "$PROGRESS" 2>/dev/null)
    n=$((${n:-0} + 1))
    echo "$n" >"$PROGRESS"
  } 9<"$SCENARIO"
  TURN_NO=$n
}

turn() {
  CMD=${QUEUE[0]}
  QUEUE=("${QUEUE[@]:1}")
  BUSY=1 INTERRUPTED='' REJECTED='' MSG='' BLOCK=0 PHASE='' STREAMING='' PARTIAL=() REQUESTS=0 IN_T=0 OUT_T=0 DENIALS='[]' LAST_TEXT=null
  now
  TURN_T0=$NOW
  lifecycle "$CMD" started
  uuid
  jq -nc --arg cwd "$PWD" --arg s "$SESSION" --arg m "$MODEL" --arg mode "$MODE" --arg v "$VERSION" --arg u "$U" --arg cmd "$CMD" \
    --argjson phone "$PHONE_TOOLS" --arg sdk "$PHONE" \
    '{type: "system", subtype: "init", cwd: $cwd, session_id: $s,
      tools: (["Task", "AskUserQuestion", "Bash", "Edit", "Glob", "Grep", "Read", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
               "WebFetch", "WebSearch", "Write"] + ($phone | map("mcp__phone__" + .))),
      mcp_servers: (if $sdk == "" then [] else [{name: "phone", status: "connected", source: "sdk"}] end),
      model: $m, permissionMode: $mode, slash_commands: [], apiKeySource: "none", claude_code_version: $v, output_style: "default",
      agents: [], skills: [], plugins: [], uuid: $u, fast_mode_state: "off", user_message_uuids: [$cmd], fake_claude: true}'
  local line='' steps=() kinds=() i
  if [ -f "$SCENARIO" ]; then
    next_turn
    line=$(sed -n "${TURN_NO}p" "$SCENARIO")
  fi
  if [ -n "$line" ] && jq -e '.steps | type == "array"' <<<"$line" >/dev/null 2>&1; then
    mapfile -t steps < <(jq -c '.steps[]' <<<"$line")
    mapfile -t kinds < <(jq -r '.steps[] | first(keys_unsorted[] | select(IN("text", "thinking", "phone", "tool", "pause", "error", "emit", "exit"))) // "?"' <<<"$line")
    TITLE=$(jq -r '.title // empty' <<<"$line")
  else
    steps=("$(jq -nc --arg n "${TURN_NO:-?}" --arg f "$SCENARIO" '{error: "假 Claude：剧本 \($f) 里没有第 \($n) 轮"}')")
    kinds=(error)
  fi
  local ended=''
  for i in "${!steps[@]}"; do
    case ${kinds[i]} in
      text | thinking) step_stream "${kinds[i]}" "${steps[i]}" || ended=1 ;;
      phone) step_phone "${steps[i]}" || ended=1 ;;
      tool) step_tool "${steps[i]}" || ended=1 ;;
      pause) pause "$(jq -r '.pause' <<<"${steps[i]}")" || ended=1 ;;
      error)
        step_error "${steps[i]}"
        BUSY='' INTERRUPTED=''
        return
        ;;
      emit) jq -c --arg s "$SESSION" '.emit | if has("session_id") then . else . + {session_id: $s} end' <<<"${steps[i]}" ;;
      exit)
        echo "fake-claude: 剧本要求退出" >&2
        exit "$(jq -r '.exit' <<<"${steps[i]}")"
        ;;
      *) echo "fake-claude: 第 $TURN_NO 轮第 $((i + 1)) 步不认识：${steps[i]}" >&2 ;;
    esac
    [ -n "$ended" ] && break
  done
  if [ -n "$INTERRUPTED" ]; then
    interrupted
  elif [ -n "$REJECTED" ]; then
    result error_during_execution true '"tool_use"' aborted_tools ',"errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=tool_use"]'
    lifecycle "$CMD" cancelled
  else
    close_message '"end_turn"'
    result success false '"end_turn"' completed ",\"result\":$(jq -c '. // ""' <<<"$LAST_TEXT")"
    lifecycle "$CMD" completed
  fi
  BUSY='' INTERRUPTED='' REJECTED=''
  [ -z "$END_REPLY" ] || exit 1
}

# ---- 启动：和真 Claude Code 一样先跟 Zeta 的 phone 服务器握手、取工具清单 ----

CTX=12000 SUM_IN=0 SUM_OUT=0 MCP_NEXT=2 PHONE_TOOLS='[]' MSG='' CMD=''
if [ -n "$PHONE" ]; then
  if mcp "{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"claude-code\",\"title\":\"Claude Code\",\"version\":\"$VERSION\"}},\"jsonrpc\":\"2.0\",\"id\":0}" 30000 &&
    mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' 30000 &&
    mcp '{"method":"tools/list","jsonrpc":"2.0","id":1}' 30000; then
    PHONE_TOOLS=$(jq -c '[.response.response.mcp_response.result.tools[]?.name]' <<<"$ANSWER")
  fi
fi

while :; do
  if [ ${#QUEUE[@]} -gt 0 ]; then
    turn
  elif [ -n "$EOF" ]; then
    exit 0
  else
    poll 1000
    # 剧本删了（vd.py stop）：闲着的假 Claude 自己退出，之后再起的就是真的 claude
    [ -f "$SCENARIO" ] || [ ${#QUEUE[@]} -gt 0 ] || exit 0
  fi
done
