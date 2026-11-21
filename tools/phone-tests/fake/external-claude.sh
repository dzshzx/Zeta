#!/bin/bash
# 假的“别处的 Claude Code”（终端、tmux 里开着的那种），给 end-external.sh 用：external-claude.sh <会话 id>。
# 像 Claude Code 那样在 ~/.claude/sessions/<pid>.json 登记自己占着这个会话（带启动时刻，zeta-helper 据此认人），
# 收到 SIGTERM 删掉登记再退出（交互式 Claude Code 也是这样）。
reg=$HOME/.claude/sessions/$$.json
start=$(sed 's/.*) //' /proc/$$/stat | cut -d' ' -f20)
mkdir -p "${reg%/*}"
printf '{"pid":%s,"sessionId":"%s","procStart":"%s","kind":"interactive"}' $$ "$1" "$start" >"$reg"
trap 'rm -f "$reg"; exit 143' TERM
while :; do
  sleep 1 &
  wait $!
done
