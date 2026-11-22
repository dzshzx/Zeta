#!/bin/bash
# 会话列表跟着主机上的核心报的忙闲变（conv.activity）：侧栏开着时，Debian 里另起一个客户端（zeta-helper serve，像 Windows 版那样）
# 经同一个核心接着一个会话发一条消息，假 Claude 答到一半停住，侧栏里那个会话显示「回答中」；放它答完，变成「新回答」
# （不是这边开的对话、也不在眼前）。截图 answering.png、unread.png。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/session-activity.sh
#
# 测试会话放在 Debian 的 $DIR 对应的项目目录里，脚本退出时删掉，连同那个客户端。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-session-activity
ID=$(cat /proc/sys/kernel/random/uuid)
PANE_X=116 PANE_Y=84 # 顶栏左边的侧栏按钮
OUT=/tmp/zeta-session-activity.out
trap 'vd debian "[ -f $OUT.pid ] && kill -- -\$(cat $OUT.pid) 2>/dev/null; rm -f $OUT $OUT.pid $OUT.req $OUT.sh" >/dev/null 2>&1 || true; drop_session $ID $DIR' EXIT

jq -nc '{title: "假 Claude · 别处的一轮", steps: [{text: "前半"}, {wait: "go", ms: 60000}, {text: "后半"}]}' >turn.jsonl
vd fake "$PWD/turn.jsonl" >/dev/null
seed_session "$ID" "$DIR" "activity probe" "probe reply"

# wait_for <正则> <几秒> <说明>：等虚拟屏上出现文字对得上的控件
wait_for() {
  local tries=0
  until vd nodes "$1" | grep -q .; do
    ((++tries <= $2 * 2)) || {
      echo "$3：$2 秒内侧栏里没出现 $1；别处的客户端收到的："
      vd debian "cat $OUT 2>/dev/null | cut -c1-400" || true
      vd shot "missing-$3.png" >/dev/null
      exit 1
    }
    sleep 0.5
  done
}

tries=0
until vd nodes '^新对话$' | grep -q .; do
  ((++tries <= 10)) || { echo "点了 10 次侧栏按钮，侧栏没打开"; exit 1; }
  vd tap $PANE_X $PANE_Y >/dev/null
  sleep 1
done
wait_for 'activity probe' 15 列出
echo "侧栏里有测试会话"

# 别处的客户端：测试版装好的 zeta-helper serve（最新装的那个），标准输入是两条请求再空等 90 秒（关了输入 serve 就退出，那一轮照样在核心里跑完）。
# 整段放进 Debian 里的一个脚本、三个标准流都不连着 vd：不然 sleep 连着 vd 的标准错误，vd debian 要等它 90 秒才返回
jq -nc --arg s "$ID" --arg d "$DIR" '{id: 1, method: "conv.open", conv: "x", session: $s, cwd: $d}' >requests.jsonl
jq -nc '{id: 2, method: "conv.send", conv: "x", text: "别处发的一条"}' >>requests.jsonl
vd debian --stdin "cat > $OUT.req" <requests.jsonl >/dev/null
# shellcheck disable=SC2016 # 整段交给 Debian 里的 shell 展开
vd debian "cat > $OUT.sh <<'SH'
f=\$(ls -t \$HOME/.cache/zeta-dev/bin/zeta-helper-* | head -1)
{ cat $OUT.req; sleep 90; } | ZETA_NAME=zeta-dev \"\$f\" serve
SH
setsid bash $OUT.sh </dev/null >$OUT 2>&1 & echo \$! >$OUT.pid" >/dev/null

wait_for '回答中' 20 回答中
vd shot answering.png >/dev/null
echo "别处的客户端开的一轮，侧栏显示回答中"

mark go
wait_for '新回答' 20 新回答
vd shot unread.png >/dev/null
vd debian "grep -c '\"type\":\"zeta_reply\"' $OUT" | tr -d '\r' | grep -qx 2 || { echo "别处的客户端没收齐两个应答："; vd debian "cat $OUT"; exit 1; }
echo "答完了，侧栏显示新回答"
