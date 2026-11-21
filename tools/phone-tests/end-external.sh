#!/bin/bash
# 结束别处的会话：会话正被 Zeta 之外的 Claude Code（终端、tmux 里开着的）占着时，聊天页底部是提示条；
# 顶栏“更多”里的“结束对话”先弹确认，确认后那个进程结束、登记删掉，侧栏不再标“别处运行中”，会话还在。
# 先在设备自己的 Debian 上走一遍（local），再把它当远程主机（127.0.0.1:8022，要先准备好，见 remote-host.sh）走一遍（remote）。
# 截图 <情形>-opened.png（提示条）、<情形>-confirm.png（确认框）、<情形>-ended.png（结束后的侧栏）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/end-external.sh
#
# “别处的 Claude Code”是假的（fake/external-claude.sh）。测试会话和它放在 Debian 的 $DIR 和 ~/$PROJECT 里，脚本退出时删掉；
# 会话按会话号打开（和顶部胶囊的“在 Zeta 中打开”同一个入口），不点侧栏。vd stop（phone-run 收尾）改回 remoteHosts、backend。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-end-external
PROJECT=.claude/projects/${DIR//\//-}
ID=$(cat /proc/sys/kernel/random/uuid)
PANE_X=116 PANE_Y=84 # 顶栏左边的侧栏按钮

cleanup() {
  vd debian "for f in ~/.claude/sessions/*.json; do grep -qs $ID \$f && kill \$(basename \$f .json); done; \
rm -rf ~/$PROJECT $DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 测试会话（一问一答）和假的 claude 放进 Debian
vd debian --stdin "mkdir -p $DIR && cat > $DIR/claude && chmod +x $DIR/claude" <"$HERE/fake/external-claude.sh" >/dev/null
line() {
  jq -nc --arg type "$1" --arg uuid "$2" --argjson message "$3" --arg id "$ID" --arg cwd "$DIR" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" '{type: $type, cwd: $cwd, sessionId: $id, uuid: $uuid, timestamp: $ts, message: $message}'
}
{
  line user u1 '{"role": "user", "content": "end external probe"}'
  line assistant a1 '{"role": "assistant", "model": "claude-sonnet-5-5", "content": [{"type": "text", "text": "probe reply"}]}'
} | vd debian --stdin "mkdir -p ~/$PROJECT && cat > ~/$PROJECT/$ID.jsonl" >/dev/null

# external <情形>：起一个占着测试会话的假 claude（等它登记好），按会话号打开测试会话，结束、确认，核对它结束了、登记删了
external() {
  local name=$1 pid result
  pid=$(vd debian "setsid nohup $DIR/claude $ID </dev/null >/dev/null 2>&1 & p=\$!; \
for _ in \$(seq 50); do [ -f ~/.claude/sessions/\$p.json ] && echo \$p && exit; sleep 0.1; done" | tr -d '\r')
  [[ -n $pid ]] || { echo "$name：假 claude 5 秒没登记上"; exit 1; }
  android "am start --user 0 --display $DISPLAY_ID --activity-single-top -n $PKG/com.dzshzx.zeta.ui.MainActivity \
--es com.dzshzx.zeta.extra.CONVERSATION $ID >/dev/null"
  sleep 4
  vd shot "$name-opened.png" >/dev/null
  menu_item $END_X $END_Y
  sleep 1.5
  vd shot "$name-confirm.png" >/dev/null
  vd debian "kill -0 $pid" >/dev/null 2>&1 || { echo "$name：还没确认，假 claude 就没了"; exit 1; }
  vd tap $CONFIRM_X $CONFIRM_Y >/dev/null
  # 15 秒内结束（zeta-helper end 先 SIGTERM，10 秒不退才 SIGKILL）
  result=$(vd debian "for _ in \$(seq 150); do kill -0 $pid 2>/dev/null || break; sleep 0.1; done
if kill -0 $pid 2>/dev/null; then echo 还在跑; elif [ -e ~/.claude/sessions/$pid.json ]; then echo 进程没了但登记还在; else echo ended; fi" | tr -d '\r')
  [[ $result == ended ]] || { echo "$name：确认后假 claude（$pid）$result"; exit 1; }
  sleep 1.5 # 确认框收起的动画放完前点侧栏按钮会被吞掉
  vd tap $PANE_X $PANE_Y >/dev/null
  sleep 2
  vd shot "$name-ended.png" >/dev/null
  echo "$name：确认后别处的进程结束了"
}

# 一、设备自己的 Debian（phone-run 已经冷启动了测试版）
external local

# 二、经 ssh 连回自己的远程主机（prefs-set 停了测试版，按会话号打开时冷启动）
use_loopback_host r-endext
external remote
