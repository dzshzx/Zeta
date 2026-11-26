#!/bin/bash
# 新对话里选的模型在核心里那份没了以后还在（conv.open 的 choices，docs/core.md「对话」）：在新对话里选 Sonnet，结束核心进程
# （还没发过消息、没有 Claude Code 进程的对话随它没了，客户端重连后按 key 打不开、重新开一个），再发消息，
# 核对托管的 Claude Code 进程带着 --model sonnet。用假 Claude，不联网。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/new-chat-model.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
LOCK=${HOSTS%/hosts}/core/core.lock

jq -nc '{steps: [{text: "ok"}]}' >turn.jsonl
vd fake "$PWD/turn.jsonl" >/dev/null
NEWER=/data/local/tmp/zeta-new-chat-model.start
android "touch $NEWER"

new_chat
wait_text '^Claude · ' 15 || { echo "新对话页没有模型胶囊"; vd shot chip-missing.png >/dev/null; exit 1; }
vd tap-text '^Claude · ' >/dev/null
wait_text '^Sonnet' 15 || { echo "模型菜单里没有 Sonnet"; vd shot menu.png >/dev/null; exit 1; }
vd tap-text '^Sonnet' >/dev/null
wait_text '^Claude · Sonnet' 15 || { echo "选了 Sonnet，顶栏没变"; vd shot chip.png >/dev/null; exit 1; }

# 结束核心：进程号写在它持着的 core.lock 里
core=$(android "cat $LOCK" | tr -dc 0-9)
[[ -n $core ]] || { echo "core.lock 里没有进程号"; exit 1; }
android "kill $core; n=0; while [ -d /proc/$core ] && [ \$n -lt 100 ]; do sleep 0.1; n=\$((n + 1)); done"
alive "$core" && { echo "核心 $core 收到 TERM 后没退出"; exit 1; }
# 等客户端重连上新核心：换了进程号的 core.lock，重连提示也没了
for _ in $(seq 60); do
  now=$(android "cat $LOCK 2>/dev/null" | tr -dc 0-9 || true)
  [[ -n $now && $now != "$core" ]] && ! vd nodes '正在重连' | grep -q . && break
  sleep 0.5
done
[[ -n ${now:-} && $now != "$core" ]] || { echo "30 秒内没起新核心"; vd shot reconnect.png >/dev/null; exit 1; }
echo "核心换了：$core → $now"

send hi
wait_result 1 60 || { vd shot turn.png >/dev/null; exit 1; }
pid=$(android "cat ${log%/out.jsonl}/pid" | tr -dc 0-9)
args=$(android "tr '\\0' ' ' </proc/$pid/cmdline")
echo "起进程的参数：$args"
[[ $args == *"--model sonnet "* ]] || { echo "起的进程没带 --model sonnet"; exit 1; }
echo "新对话选的模型在换过核心后还在"
