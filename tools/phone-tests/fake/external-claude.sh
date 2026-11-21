#!/bin/bash
# 假的“别处的 Claude Code”（终端、tmux 里开着的那种），给 end-external.sh、handoff-external.sh 用：
# external-claude.sh <会话 id> [忙闲，默认 idle]。
# 像 Claude Code 那样在 ~/.claude/sessions/<pid>.json 登记自己占着这个会话（带启动时刻，zeta-helper 据此认人；status 是忙闲，
# 测试脚本改这个文件来模拟它忙完了）。收到 SIGTERM 删掉登记再退出（交互式 Claude Code 也是这样）；
# 收到 SIGINT 照 /exit 退出（退出码 0，zeta-helper handoff 发的就是它）。
reg=$HOME/.claude/sessions/$$.json
start=$(sed 's/.*) //' /proc/$$/stat | cut -d' ' -f20)
mkdir -p "${reg%/*}"
printf '{"pid":%s,"sessionId":"%s","procStart":"%s","kind":"interactive","status":"%s"}' $$ "$1" "$start" "${2:-idle}" >"$reg"
trap 'rm -f "$reg"; exit 143' TERM
trap 'rm -f "$reg"; exit 0' INT
while :; do
  sleep 1 &
  wait $!
done
