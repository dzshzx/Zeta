# shellcheck shell=bash disable=SC2034 # 这里的变量给 source 它的脚本用
# 设备脚本的公用部分。脚本开头 source 它：
#   . "$ZETA_ROOT/tools/phone-tests/lib.sh"
# 用的是 tools/phone-run 给的 vd、android、$ZETA_ROOT；函数在 set -euo pipefail 下也能用。

HERE=$ZETA_ROOT/tools/phone-tests

# 测试版的托管目录：每个对话一个子目录，out.jsonl 是 Claude（或假 Claude）的输出
HOSTS=/data/local/zeta/debian/rootfs/home/$ZETA_LINUX_USER/.cache/zeta-dev/hosts

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
# “结束对话”弹出的确认框里右边的“结束”
CONFIRM_X=866 CONFIRM_Y=2430
# 冷启动可能停在别的对话上（还可能正在跑）：开一个空的新对话
new_chat() {
  menu_item $NEW_X $NEW_Y
  sleep 1.5
}

# 顶栏左边的侧栏按钮；open_pane：点它直到侧栏真打开（冷启动后头几秒的点按会丢），看到“新对话”这一项为止，点 10 次还没开就截图退出
PANE_X=116 PANE_Y=84
open_pane() {
  local tries=0
  until vd nodes '^新对话$' | grep -q .; do
    ((++tries <= 10)) || { echo "点了 10 次侧栏按钮，侧栏没打开"; vd shot pane-closed.png >/dev/null; exit 1; }
    vd tap $PANE_X $PANE_Y >/dev/null
    sleep 1
  done
}

# gone <托管目录>：10 秒内目录没了（zeta_host.sh stop 收掉进程后删掉它）；还在时说一声、返回 1
gone() {
  for _ in $(seq 20); do
    android "[ -d $1 ]" || return 0
    sleep 0.5
  done
  echo "托管目录还在：$1"
  return 1
}

# exited <托管目录>：10 秒内托管的进程退出了（目录没了，或目录里 pid 文件记的进程不在了）；还在时说一声、返回 1。
# 结束一个这边没开着的对话（sessions.end）时进程马上退出，目录要等最后一个接着的一方离开、或核心下次扫描时才删，所以不等目录
exited() {
  for _ in $(seq 20); do
    android "[ -d $1 ] && [ -d /proc/\$(cat $1/pid 2>/dev/null || echo 0) ]" || return 0
    sleep 0.5
  done
  echo "托管的进程还在：$1"
  return 1
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

# mark <名字>：放下剧本里 {"wait":"<名字>"} 等的标记，假 Claude 接着往下走。剧本在要和脚本对上的地方等标记，不用固定的停顿：
# 设备忙时脚本慢多少都不影响结果
mark() { vd fake --mark "$1" >/dev/null; }

# wait_text <正则> <最多等几秒>：等测试屏上出现文字或说明对得上的控件（vd nodes）；没等到返回 1（要不要截图、退出由脚本定）
wait_text() {
  local i
  for ((i = 0; i < $2 * 2; i++)); do
    [[ -n $(vd nodes "$1") ]] && return 0
    sleep 0.5
  done
  return 1
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

# 收掉会话记录里带 $1（grep -E 的模式）的测试对话：先按会话 ID 找到接着它的托管进程并结束，再删托管目录和会话记录
# （托管进程的输出里没有用户发的话，只有会话 ID）。
# 只删会话记录不够：进程还在跑或在等确认时，测试版下次冷启动会把它接回来放在后台，挡住别人的测试，
# 账号页也会因为「还有对话在进行」不让换号。进程先收 TERM（Claude Code 借此放掉手里的锁），5 秒内没退出再 KILL。
reap_marked() {
  android "for f in \$(grep -lE '$1' $ROOTFS/home/$ZETA_LINUX_USER/.claude/projects/*/*.jsonl 2>/dev/null); do
    id=\${f##*/}; id=\${id%.jsonl}
    for o in \$(grep -l \"\$id\" $HOSTS/*/out.jsonl 2>/dev/null); do
      d=\${o%/out.jsonl}; p=\$(cat \$d/pid 2>/dev/null)
      if [ -n \"\$p\" ] && kill \$p 2>/dev/null; then
        n=0; while kill -0 \$p 2>/dev/null && [ \$n -lt 50 ]; do sleep 0.1; n=\$((n + 1)); done
        kill -9 \$p 2>/dev/null
      fi
      rm -rf \$d; echo \"结束托管进程 \${d##*/}\"; done
    rm -rf \"\$f\" \"\${f%.jsonl}\"; echo \"删掉测试会话 \$id\"; done; true"
}

# alive <进程号>：设备上这个进程还在（Android 一侧看 /proc，Debian 里的进程也看得到）
alive() { android "[ -d /proc/$1 ]"; }

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

# use_loopback_host <id> [连法]：起好回环的 sshd，把测试版的远程主机设成只有这一台“回环”（ssh://<Linux 用户>@127.0.0.1:8022，
# 经手机 Debian 的 OpenSSH；连法给了就写进 via），侧栏切到它。prefs-set 会先 force-stop 测试版，之后要 vd launch。
# vd stop（phone-run 收尾）改回 remoteHosts、backend。
use_loopback_host() {
  local via=${2:+",\"via\":\"$2\""}
  ensure_loopback_sshd
  vd prefs-set "remoteHosts=[{\"id\":\"$1\",\"name\":\"回环\",\"target\":\"ssh://$ZETA_LINUX_USER@127.0.0.1:8022\",\"home\":\"/home/$ZETA_LINUX_USER\",\"cwd\":\"/home/$ZETA_LINUX_USER\"$via}]" "backend=$1" >/dev/null
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

# 测试版的公钥（应用私有目录的 zeta_ssh.pub，密钥第一次用时写出）：等它写出来，加进设备 Debian 里 Linux 用户自己的
# authorized_keys（已经在的不重复加，同一个注释的旧行先删掉），再重启回环的 sshd（加公钥以前那几次登录失败会让它一阵子
# 不理 127.0.0.1）。只给回环的 sshd 用：往别的主机加公钥是新增访问权限，脚本不做。
TEST_PUB=/data/user/0/$PKG/files/zeta_ssh.pub
authorize_test_key() {
  for _ in $(seq 40); do android "[ -s $TEST_PUB ]" && break; sleep 0.5; done
  android "[ -s $TEST_PUB ]" || { echo "测试版没写出公钥 $TEST_PUB"; return 1; }
  android "k=\$(cat $TEST_PUB); c=\${k##* }; f=$ROOTFS/home/$ZETA_LINUX_USER/.ssh/authorized_keys; grep -qxF \"\$k\" \$f || \
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

# ours：dumpsys 里本包的通知记录（一条记录连同它下面缩进的几行）；别的应用的通知内容不落盘
ours() {
  android "dumpsys notification --noredact" | awk -v p="pkg=$PKG " '
    /NotificationRecord\(/ { keep = index($0, p) > 0; ind = match($0, /[^ ]/); if (keep) print; next }
    keep { if (match($0, /[^ ]/) <= ind) keep = 0; else print }'
}

# notice <id>：本包 id 为 <id> 的通知记录（1 是前台服务保活的那条，2 是对话那条；几个对话时有几条），没有时是空的。
# 系统替几条通知自动建的组摘要（tag 里带 g:Aggregate_，id 也可能是 1）不算
notice() { ours | awk -v id=" id=$1 " '/NotificationRecord\(/ { keep = index($0, id) > 0 && index($0, "g:Aggregate_") == 0 } keep'; }

# 把测试版压到后台：测试屏上开另一个应用（NEW_TASK | EXCLUDE_FROM_RECENTS，不在「最近任务」里留记录），确认测试版不在前台了。
# 「系统跟踪」要开着开发者选项，否则一启动就退出，压不住
background() {
  local i
  for i in 1 2 3; do
    android "am start --display $DISPLAY_ID -f 0x10800000 -n com.android.traceur/.MainActivity >/dev/null 2>&1"
    sleep 1
    [[ -z $(resumed) ]] && return 0
  done
  echo "测试版退不到后台：$(resumed)（开发者选项开着吗？）"
  return 1
}

# seed_session <会话号> <工作目录> <问> <答>：在 Debian 里摆一份一问一答的测试会话（工作目录和 ~/.claude/projects 下对应的目录都建好）
seed_session() {
  local project=.claude/projects/${2//\//-}
  jq -nc --arg id "$1" --arg cwd "$2" --arg q "$3" --arg a "$4" --arg ts "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" '
    {type: "user", cwd: $cwd, sessionId: $id, uuid: "u1", timestamp: $ts, message: {role: "user", content: $q}},
    {type: "assistant", cwd: $cwd, sessionId: $id, uuid: "a1", timestamp: $ts,
     message: {role: "assistant", model: "claude-sonnet-5-5", content: [{type: "text", text: $a}]}}' |
    vd debian --stdin "mkdir -p $2 ~/$project && cat > ~/$project/$1.jsonl" >/dev/null
}

# drop_session <会话号> <工作目录>：收掉 seed_session 摆的会话，连同还登记着它的进程和登记（脚本退出时用）
drop_session() {
  vd debian "for f in ~/.claude/sessions/*.json; do grep -qs $1 \$f || continue; kill \$(basename \$f .json) 2>/dev/null; rm -f \$f; done; \
rm -rf ~/.claude/projects/${2//\//-} $2" >/dev/null 2>&1 || true
}

# open_conversation <会话号> [等几秒，默认 6]：在测试屏上按会话号打开对话（没开着就冷启动），等界面起来
open_conversation() {
  android "am start --user 0 --display $DISPLAY_ID --activity-single-top -n $PKG/com.dzshzx.zeta.ui.MainActivity \
--es com.dzshzx.zeta.extra.CONVERSATION $1 >/dev/null"
  sleep "${2:-6}"
}

# ---- 外部入口（phone/PhoneBridge.kt）：像工作站上的 `phone ui` 那样，以 root 跑这棵树编出的 zeta-helper phone-mcp，一次调用连一次 ----
BRIDGE_BIN=/data/local/tmp/zeta-bridge-helper
# bridge_helper：把这棵树编出的、设备架构的 zeta-helper 推到 $BRIDGE_BIN（本机上那份的路径记在 BRIDGE_HELPER），清掉上次留下的请求和应答
bridge_helper() {
  local arch
  case $(android 'uname -m' | tr -d '\r') in
    aarch64) arch=arm64 ;;
    x86_64) arch=amd64 ;;
    *) echo "不认识的架构" >&2; return 1 ;;
  esac
  BRIDGE_HELPER=$ZETA_ROOT/app/build/generated/zeta-helper/zeta-helper/linux-$arch
  vd sh --stdin "cat > $BRIDGE_BIN && chmod 755 $BRIDGE_BIN" <"$BRIDGE_HELPER"
  vd sh "rm -f $BRIDGE_BIN.*.req $BRIDGE_BIN.*.out $BRIDGE_BIN.*.err"
}
BRIDGE_INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"phone-tests","version":"1"}}}'
# bridge_talk <客户端标识> <名字> <请求…>：一次连接，发 initialize 和这些请求，收齐应答再关输入，应答存到 <名字>.jsonl。
# 每个名字用设备上自己的一组文件，可以放后台和别的调用、截图同时跑。请求先放进设备上的文件：模拟器的 adb shell 读到输入结束会把远端命令整个结束掉
bridge_talk() {
  local key=$1 tag=$2
  shift 2
  local req=$BRIDGE_BIN.$tag.req res=$BRIDGE_BIN.$tag.out want=$(($# + 1))
  printf '%s\n' "$BRIDGE_INIT" '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$@" | vd sh --stdin "cat > $req"
  vd sh ": > $res; (cat $req; i=0; while [ \$(wc -l < $res) -lt $want ] && [ \$i -lt 200 ]; do sleep 0.3; i=\$((i+1)); done) | timeout 90 $BRIDGE_BIN phone-mcp $PKG $key > $res 2>$res.err; cat $res" >"$tag.jsonl" || true
}
# bridge_answered <名字>：等放在后台的 bridge_talk 答完 initialize（请求是一起发出的，之后的调用随即开始处理），最多 10 秒
bridge_answered() { vd sh "i=0; until [ -s $BRIDGE_BIN.$1.out ] || [ \$i -ge 50 ]; do sleep 0.2; i=\$((i+1)); done"; }
bridge_call() { printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$1" "$2" "$3"; }
# bridge_reply <应答文件> <id>：那个请求的应答；bridge_ok：它成功了（有 result、不是工具出错）
bridge_reply() { jq -c "select(.id == $2)" "$1"; }
bridge_ok() { test "$(bridge_reply "$1" "$2" | jq '.result != null and .result.isError != true')" = true; }

# cpu_warm_up <剧本>：量 CPU 的脚本的开头：用假 Claude 的 <剧本> 开个新对话、记下测试版的进程号 pid，
# 答完第一轮（预热）再清零帧数，之后发的第二轮由脚本用 measure_cpu 量
cpu_warm_up() {
  NEWER=/data/local/tmp/zeta-$(basename "$0" .sh).mark
  trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT
  vd fake "$1"
  android "touch $NEWER"
  new_chat
  pid=$(android "pidof $PKG" | tr -d '\r')
  [[ -n $pid ]] || { echo "测试版没在跑"; exit 1; }
  send warm
  wait_result 1 60
  sleep 3
  android "dumpsys gfxinfo $PKG reset" >/dev/null
}
# app_threads：测试版（进程号 $pid）每个线程的「线程号 名字 CPU 节拍（用户态加内核态）」
app_threads() {
  android "cd /proc/$pid/task && for t in *; do echo \"\$t \$(tr ' ' _ < \$t/comm) \$(sed 's/.*) //' \$t/stat | cut -d' ' -f12,13)\"; done" |
    tr -d '\r' | awk '{ print $1, $2, $3 + $4 }'
}
# measure_cpu <序号> <标题>：等第 <序号> 轮答完，打印这段时间按线程名合计的 CPU（节拍是 10 毫秒）和前 6 名
measure_cpu() {
  local start=$SECONDS
  app_threads >before
  wait_result "$1" 90
  app_threads >after
  awk -v title="$2" -v secs=$((SECONDS - start)) 'NR == FNR { a[$1] = $3; next } { d = $3 - a[$1]; if (d > 0) { by[$2] += d; total += d } }
    END { printf "%s（%s 秒）：合计 %d 毫秒；", title, secs, total * 10; n = 0; PROCINFO["sorted_in"] = "@val_num_desc"
      for (k in by) { if (n++ == 6) break; printf "%s %d，", k, by[k] * 10 } }' before after
}
# frames：测试版自上次 dumpsys gfxinfo reset 以来画了多少帧
frames() { android "dumpsys gfxinfo $PKG | grep -m1 'Total frames rendered'" | tr -d '\r' | grep -o '[0-9]*$'; }
