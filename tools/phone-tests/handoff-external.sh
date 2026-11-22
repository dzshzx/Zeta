#!/bin/bash
# 把终端里开着的会话接到 Zeta：会话正被 Zeta 之外的 Claude Code 占着时，聊天页底部是「别处运行」卡片。
# 那边正忙时按钮是灰的、点了不动它；那边闲下来（卡片每 3 秒查一次）后点「接管会话」，那边照 /exit 退出、登记删掉，
# 卡片收起，再发一条消息由假 Claude 接着这个会话答完一轮。
# 先在设备自己的 Debian 上走一遍（local），再把它当远程主机（127.0.0.1:8022，要先准备好，见 remote-host.sh）走一遍（remote）。
# 截图 <情形>-busy.png（灰按钮）、<情形>-idle.png（能点）、<情形>-handed.png（卡片收起）、<情形>-replied.png（接着答完）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/handoff-external.sh
#
# “别处的 Claude Code”是假的（fake/external-claude.sh），接过来以后的 Claude 也是假的（fake/text.jsonl）。
# 测试会话放在 Debian 的 $DIR 和 ~/.claude/projects 下对应的目录里，脚本退出时删掉；会话按会话号打开。vd stop（phone-run 收尾）改回 remoteHosts、backend。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-handoff-external
IDS=()
# 卡片上「接管会话」按钮的中心（按 JVM 截图 chat-external-idle 量的，按钮贴着卡片底边，说明几行都不影响它）
HANDOFF_X=300 HANDOFF_Y=2512

drop_sessions() { for id in "${IDS[@]}"; do drop_session "$id" $DIR; done; }
trap drop_sessions EXIT

vd fake "$HERE/fake/text.jsonl" >/dev/null
vd debian --stdin "mkdir -p $DIR && cat > $DIR/claude && chmod +x $DIR/claude" <"$HERE/fake/external-claude.sh" >/dev/null

alive() { vd debian "kill -0 $1" >/dev/null 2>&1; }

# handoff <情形>：起一个正忙的假 claude，打开会话，点灰按钮不该动它；改成闲着，等卡片查到后点按钮，核对它退出、卡片收起、接着答一轮
handoff() {
  local name=$1 pid result
  # 每遍一个新会话：上一遍接过来的会话背后还接着 Zeta 自己的进程（没人拿着时核心 30 秒后才断开，看门狗约十分钟后收掉），
  # 真的终端里 --resume 时 SessionStart hook 会让那个进程退出，这里假的 claude 不跑 hook，共用会话号就成了两个进程同占一个会话
  ID=$(cat /proc/sys/kernel/random/uuid)
  IDS+=("$ID")
  seed_session $ID $DIR "handoff probe" "probe reply"
  # 后台起的命令 SIGINT 是忽略的，忽略的信号脚本里 trap 不回来：用 env 恢复成默认，像终端里起的 claude 那样收得到 SIGINT
  pid=$(vd debian "setsid env --default-signal=INT nohup $DIR/claude $ID busy </dev/null >/dev/null 2>&1 & p=\$!; \
for _ in \$(seq 50); do [ -f ~/.claude/sessions/\$p.json ] && echo \$p && exit; sleep 0.1; done" | tr -d '\r')
  [[ -n $pid ]] || { echo "$name：假 claude 5 秒没登记上"; exit 1; }
  open_conversation $ID 6
  vd shot "$name-busy.png" >/dev/null
  vd tap $HANDOFF_X $HANDOFF_Y >/dev/null
  sleep 2
  alive "$pid" || { echo "$name：那边正忙，点了灰按钮却把它停了"; exit 1; }
  vd debian "sed -i 's/\"status\":\"busy\"/\"status\":\"idle\"/' ~/.claude/sessions/$pid.json" >/dev/null
  sleep 5
  vd shot "$name-idle.png" >/dev/null
  vd tap $HANDOFF_X $HANDOFF_Y >/dev/null
  result=$(vd debian "for _ in \$(seq 200); do kill -0 $pid 2>/dev/null || break; sleep 0.1; done
if kill -0 $pid 2>/dev/null; then echo 还在跑; elif [ -e ~/.claude/sessions/$pid.json ]; then echo 进程没了但登记还在; else echo handed; fi" | tr -d '\r')
  [[ $result == handed ]] || { echo "$name：点了「接管会话」后假 claude（$pid）$result"; exit 1; }
  sleep 2
  vd shot "$name-handed.png" >/dev/null
  android "touch /data/local/tmp/zeta-handoff-mark"
  NEWER=/data/local/tmp/zeta-handoff-mark log=''
  send "continue here"
  wait_result 1 60 || { vd shot "$name-timeout.png" >/dev/null; exit 1; }
  sleep 1
  vd shot "$name-replied.png" >/dev/null
  # 接上的是原来那个会话（--resume），不是新开的
  android "grep -q '\"session_id\":\"$ID\"' $log" || { echo "$name：接着答的不是原会话"; exit 1; }
  echo "$name：那边闲下来后接了过来，接着原会话答完一轮"
}

# 一、设备自己的 Debian（phone-run 已经冷启动了测试版）
handoff local

# 二、经 ssh 连回自己的远程主机（prefs-set 停了测试版，按会话号打开时冷启动）
use_loopback_host r-handoff
vd fake "$HERE/fake/text.jsonl" >/dev/null
handoff remote
