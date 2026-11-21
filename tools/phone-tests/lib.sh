# shellcheck shell=bash disable=SC2034 # 这里的变量给 source 它的脚本用
# 设备脚本的公用部分。脚本开头 source 它：
#   . "$ZETA_ROOT/tools/phone-tests/lib.sh"
# 用的是 tools/phone-run 给的 vd、android、$ZETA_ROOT；函数在 set -euo pipefail 下也能用。

HERE=$ZETA_ROOT/tools/phone-tests

# 测试版的托管目录：每个对话一个子目录，out.jsonl 是 Claude（或假 Claude）的输出
HOSTS=/data/local/zeta/debian/rootfs/home/dzshzx/.cache/zeta-dev/hosts

# 聊天页的输入框、发送键（整屏 1220×2656 的坐标，冷启动后的新对话页）
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548

# 顶栏右边的“更多”和它弹出的菜单：第一项“新对话”，第二项“结束对话”
MORE_X=1102 MORE_Y=84 NEW_X=863 NEW_Y=268 END_X=863 END_Y=398
# menu_item <x> <y>：打开“更多”，点菜单里的一项
menu_item() {
  vd tap $MORE_X $MORE_Y >/dev/null
  sleep 1
  vd tap "$1" "$2" >/dev/null
}
# 冷启动可能停在别的对话上（还可能正在跑）：开一个空的新对话
new_chat() {
  menu_item $NEW_X $NEW_Y
  sleep 1.5
}

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

# 设备上 Debian 的根目录
ROOTFS=/data/local/zeta/debian/rootfs

# 设备 Debian 里进程名（/proc/<pid>/comm）是 $1 的进程号，空格分开（OpenSSH 改了进程标题，pidof 认不出）
debian_pids() {
  android "for d in /proc/[0-9]*; do [ \"\$(cat \$d/comm 2>/dev/null)\" = $1 ] && [ \"\$(readlink \$d/root)\" = $ROOTFS ] && echo \${d#/proc/}; done; true" | tr -d '\r' | xargs
}

# 远程主机的测试把设备自己的 Debian 当远程主机：那里的 sshd 只听 127.0.0.1:8022。装了没起（模拟器重启过）就起上，
# 用 vd 放好的进入脚本进 Debian，sshd 自己转到后台；没装就说明怎么准备。在 vd fake 之后调（它会放好进入脚本）。
# （设备上的 netstat 加了 -l 也列已经连上的：列全部、只认 LISTEN）
loopback_sshd_listening() { android "netstat -atn 2>/dev/null | grep -q '127.0.0.1:8022 .*LISTEN'"; }
ensure_loopback_sshd() {
  loopback_sshd_listening || android "[ -x $ROOTFS/usr/sbin/sshd ] && [ -f /data/local/tmp/zeta-enter.sh ] && \
    sh /data/local/tmp/zeta-enter.sh /bin/bash -c 'mkdir -p /run/sshd && /usr/sbin/sshd'" || true
  loopback_sshd_listening || { echo "设备上 127.0.0.1:8022 没有 sshd：在测试版的设置 › Linux 环境 › SSH 服务里装上"; return 1; }
}

# 测试版底下名字是 ssh 的子孙进程（经手机 Debian 的 OpenSSH 连远程主机时才有）
app_ssh_pids() {
  local app
  app=$(android "pidof $PKG" | tr -d '\r')
  android "ps -A -o PID,PPID,NAME" | awk -v root="$app" '
    NR > 1 { parent[$1] = $2; name[$1] = $3 }
    END {
      for (p in parent) {
        if (name[p] != "ssh") continue
        for (q = parent[p]; q != "" && q != 0 && q != 1; q = parent[q]) if (q == root) { print p; break }
      }
    }'
}

# 重启设备 Debian 里的 sshd：OpenSSH 会记下认证失败过的来源，一阵子不让它连（PerSourcePenalties），重启就清掉
restart_loopback_sshd() {
  local listeners
  listeners=$(debian_pids sshd)
  [[ -z $listeners ]] || android "kill $listeners"
  # 等旧的真的不听了再起新的，否则 ensure_loopback_sshd 以为它还在
  for _ in $(seq 20); do loopback_sshd_listening || break; sleep 0.5; done
  ensure_loopback_sshd
}

# 测试版的公钥（应用私有目录的 zeta_ssh.pub，密钥第一次用时写出）：等它写出来，加进设备 Debian 里 dzshzx 自己的
# authorized_keys（已经在的不重复加，同一个注释的旧行先删掉），再重启回环的 sshd（加公钥以前那几次登录失败会让它一阵子
# 不理 127.0.0.1）。只给回环的 sshd 用：往别的主机加公钥是新增访问权限，脚本不做。
TEST_PUB=/data/user/0/$PKG/files/zeta_ssh.pub
authorize_test_key() {
  for _ in $(seq 40); do android "[ -s $TEST_PUB ]" && break; sleep 0.5; done
  android "[ -s $TEST_PUB ]" || { echo "测试版没写出公钥 $TEST_PUB"; return 1; }
  android "k=\$(cat $TEST_PUB); c=\${k##* }; f=$ROOTFS/home/dzshzx/.ssh/authorized_keys; grep -qxF \"\$k\" \$f || \
    { grep -v \" \$c\\\$\" \$f > \$f.new; echo \"\$k\" >> \$f.new; cat \$f.new > \$f; rm -f \$f.new; }"
  restart_loopback_sshd
}

# 远程主机上的对话断线重连（剧本 fake/remote.jsonl）：发一轮、截 first.png，调 $1 掐断 Zeta 的连接，再发一轮、截 reconnected.png；
# 核对托管的还是同一个进程、两轮都答完。$2 是两轮答完时各查一次的检查（可省）。结果留在 run.jsonl。
two_turns_across_drop() {
  local cut=$1 check=${2:-true} host_dir pid after results
  send "remote-1"
  wait_result 1 40 || { vd shot first-timeout.png >/dev/null; return 1; }
  sleep 1.5
  vd shot first.png >/dev/null
  host_dir=${log%/out.jsonl}
  pid=$(android "cat $host_dir/pid" | tr -d '\r')
  "$check" || return 1
  "$cut" || return 1
  sleep 3 # 重连第一次在 1 秒后
  send "remote-2"
  wait_result 2 60 || { vd shot second-timeout.png >/dev/null; return 1; }
  sleep 1.5
  vd shot reconnected.png >/dev/null
  after=$(android "cat $host_dir/pid" | tr -d '\r')
  [[ $after == "$pid" ]] || { echo "托管进程换了（$pid → $after）：不是接回原来的那个"; return 1; }
  "$check" || return 1
  android "cat $log" >run.jsonl
  results=$(jq -r 'select(.type == "result") | .result' run.jsonl | paste -sd '|')
  echo "两轮的回答：$results"
  [[ $results == *"第一轮"*"|"*"第二轮"* ]] || { echo "两轮回答不对"; return 1; }
}
