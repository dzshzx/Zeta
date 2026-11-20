# Zeta 在 Debian 里用来托管 Claude Code 进程的脚本（bash）。
#
# Claude Code 不直接挂在应用的管道上，而是脱离出去：输入来自一个命名管道，输出追加到日志文件。
# 应用这一头只是“接上去”——把自己的标准输入转进管道、把日志跟读到标准输出。
# 这样应用被系统回收或被划掉时，进行到一半的任务照常跑完，重新打开应用再接上即可。
#
#   start  <标识> <工作目录> <标题> <claude 参数…>   启动并接上
#   attach <标识> <从第几行开始读>                    接上一个已在运行的
#   stop   <标识>                                     结束会话（等价于终端里的 /exit）并清理，立即返回
#   state  <标识>                                     看门狗眼里的样子：输出 turn=1 表示这一轮在进行，tasks=1 表示有后台任务在跑
#
# 目录按包分开：ZETA_NAME 是 Zeta 自己的目录名（正式版 zeta，测试版 zeta-dev），由应用进环境时传进来。
# 两个包看不到对方的后台进程，也就不会接管或结束对方的对话。
#
# 托管目录 ~/.cache/<ZETA_NAME>/hosts/<标识>/ 里的文件。start 建目录；stop、接着的一方见到 exit、zeta_helper.py hosts 见到进程没了时删掉，
# 存储页（zeta_helper.py clean）清掉进程和接着的一方都已不在的。
#   in         命名管道，Claude Code 的标准输入。start 建，起进程的那段以读写方式一直开着；接着的一方把应用的标准输入转进来；stop 往里写 end_session。
#   out.jsonl  Claude Code 的标准输出（stream-json，一行一个事件，只追加）。接着的一方从指定的行起跟读给应用（ClaudeProcess）；
#              看门狗看这一轮和后台任务；zeta_helper.py hosts 摘出行数、会话、这一轮、待批准的调用、权限模式和后台任务，
#              应用接回时据此建 HostInfo；zeta_helper.py 的存储页从开头取会话号。
#   pid        Claude Code 的进程号，起进程的那段写。本脚本 attach、stop 用；zeta_helper.py 据此认还活着的托管进程
#              （hosts、存储页、别处占用的会话）；zeta_accounts.sh 据此认出进程是哪个包托管的；
#              应用的 PathCheck（AttachedPaths.kt）经 /proc/<pid>/root 看这个进程的挂载命名空间。
#   attach     正接着的那一方（本脚本 start、attach 之后的部分）的进程号，接上时写。看门狗据此知道有没有应用接着；
#              zeta_helper.py 的存储页据此不清还连着的目录。
#   cwd        启动时的工作目录，start 写。zeta_helper.py hosts 接回时用。
#   title      启动时的标题，start 写。zeta_helper.py hosts 在会话记录里没有标题时用；诊断页（DiagScripts.kt）列运行中的日志时带上。
#   exit       Claude Code 的退出码，它退出后起进程的那段写。接着的一方见到它就报 zeta_exit 并删掉目录；zeta_helper.py hosts 见到它就当已结束。
#   err.log    Claude Code 的标准错误输出，起进程的那段追加。诊断页读运行中的；Claude Code 退出时留一份到 ~/.cache/<ZETA_NAME>/stderr/；
#              接着的一方报 zeta_exit 时带上最后一段，进程没起来时打出来。
set -u
mode=$1
D="$HOME/.cache/${ZETA_NAME:-zeta}/hosts/$2"

# 这一轮在不在进行：看最后一条开始或结束事件，是开始就在进行，一条都没有就是没在进行。
# 开始是 system init（每一轮开头都有，后台任务做完后 Claude Code 自己起的一轮也只有它）和
# command_lifecycle started（Zeta 发的消息被取走，插话并进这一轮时也有），结束是 result。
# 别的行都不算：result 之后还会写 command_lifecycle completed、task_notification、background_tasks_changed 等收尾事件，
# 后台子代理内部的行（assistant、user，带 parent_tool_use_id）在这一轮的 result 之后还会陆续写来。
# result 行的键顺序不固定，type 不一定在行首；内容里的引号都转义过，不会误中。
# Conversation.handle 和 zeta_helper.py 的 scan_host 是同一口径（Conversation 另外认它自己发出、还在排队的消息），
# HostTurnTest 拿同一组输出日志逐行核对这三处。
turn_running() {
  grep -E '"type":"result"|^[{]"type":"system","subtype":"init"|^[{]"type":"command_lifecycle".*"state":"started"' "$1" \
    | tail -n 1 | grep -qv '"type":"result"'
}

# 还有后台任务（后台命令、子代理等）在跑：最后一条 background_tasks_changed 的清单不空。
# 它们在这一轮结束后照样跑，跑完 Claude Code 还会自己另起一轮。
tasks_running() {
  grep '^[{]"type":"system","subtype":"background_tasks_changed"' "$1" | tail -n 1 | grep -qF '"tasks":[{'
}

if [ "$mode" = state ]; then
  turn_running "$D/out.jsonl" && t=1 || t=0
  tasks_running "$D/out.jsonl" && b=1 || b=0
  echo "turn=$t tasks=$b"
  exit 0
fi

# 结束会话：往输入管道写控制请求 end_session，由 Claude Code 自己结束，等价于终端里的 /exit（-p 模式不认 /exit）：
# 进行中的这一轮停下，SessionEnd hook 照跑，会话记录留着（ClaudeCodeContractTest 钉住）。等它退出最多 10 秒，
# 不退（卡住了）再 SIGTERM，还不退就 SIGKILL，然后删托管目录。等待和清理放在单独的会话（setsid）里，本命令写完请求就返回，
# Zeta 这时被杀也照样做完。
if [ "$mode" = stop ]; then
  P=$(cat "$D/pid" 2>/dev/null)
  if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then
    # 管道由起进程的那段以读写方式一直开着，写不会卡住；那段刚好不在了时由 timeout 兜底
    timeout 2 bash -c 'printf "%s\n" "$1" > "$2"' zeta-end \
      '{"type":"control_request","request_id":"zeta-end","request":{"subtype":"end_session"}}' "$D/in" 2>/dev/null
  fi
  setsid bash -c '
    D=$1 P=$2
    if [ -n "$P" ]; then
      for _ in $(seq 50); do kill -0 "$P" 2>/dev/null || break; sleep 0.2; done
      if kill "$P" 2>/dev/null; then
        for _ in $(seq 25); do kill -0 "$P" 2>/dev/null || break; sleep 0.2; done
        kill -9 "$P" 2>/dev/null
      fi
      # 等起进程的那段留下退出码和标准错误输出的副本（诊断页看）
      for _ in $(seq 10); do [ -f "$D/exit" ] && break; sleep 0.1; done
    fi
    rm -rf "$D"
  ' zeta-stop "$D" "$P" </dev/null >/dev/null 2>&1 &
  exit 0
fi

if [ "$mode" = start ]; then
  cwd=$3
  title=$4
  shift 4
  cd "$cwd" 2>/dev/null || { echo "zeta: 进不去工作目录 $cwd" >&2; exit 97; }
  rm -rf "$D"
  mkdir -p "$D" && mkfifo "$D/in" || { echo "zeta: 建不了 $D" >&2; exit 97; }
  : > "$D/out.jsonl"
  printf '%s' "$cwd" > "$D/cwd"
  printf '%s' "$title" > "$D/title"
  # Claude Code 对新模型默认不开任务清单工具（TaskCreate、TaskUpdate 等），聊天页的任务清单面板靠它们，这里打开。
  # 环境变量或 settings.json 的 env 里明确设过的照那边的。
  export CLAUDE_CODE_ENABLE_TODO_TOOLS="${CLAUDE_CODE_ENABLE_TODO_TOOLS:-1}"
  # 起哪个程序：正式版（ZETA_NAME=zeta）永远是真的 claude。
  # 只有测试版在设备测试里放了剧本时换成按剧本回话的假 Claude（tools/fake-claude；vd.py fake 放进来，vd.py stop 删掉）。
  run=claude
  if [ "${ZETA_NAME:-zeta}" = zeta-dev ] && [ -f "$HOME/.config/zeta-dev/fake-claude/scenario.jsonl" ]; then
    run="$HOME/.config/zeta-dev/fake-claude/fake-claude.sh"
  fi
  # 管道用读写方式打开并一直持有：写的一方来来去去，Claude Code 都不会读到“输入结束”。
  # 看门狗的两个判定函数写在上面，经第一个参数带进去（这一段在单引号里）
  setsid bash -c '
    eval "$1"; D=$2; R=$3; shift 3
    exec 3<>"$D/in"
    "$R" "$@" <&3 >>"$D/out.jsonl" 2>>"$D/err.log" &
    P=$!
    echo $P > "$D/pid"
    # 看门狗：没有应用接着、这一轮又已经结束的进程，闲置约十分钟后自行结束，
    # 免得应用被杀之后它一直占着内存。还在干活、在等用户确认或还有后台任务在跑的不动。
    (
      n=0
      while [ -d "$D" ] && kill -0 $P 2>/dev/null; do
        sleep "${ZETA_IDLE_STEP:-60}"
        A=$(cat "$D/attach" 2>/dev/null)
        if [ -n "$A" ] && kill -0 "$A" 2>/dev/null; then
          n=0
        # 这一轮结束了、也没有后台任务在跑才算闲着（在等用户确认时这一轮还没结束）
        elif ! turn_running "$D/out.jsonl" && ! tasks_running "$D/out.jsonl"; then
          n=$((n + 1))
          [ $n -ge "${ZETA_IDLE_LIMIT:-10}" ] && kill $P
        else
          n=0
        fi
      done
    ) &
    W=$!
    wait $P
    code=$?
    kill $W 2>/dev/null
    # 标准错误输出留一份给 Zeta 的诊断页：托管目录随后会被删掉。只留最近 20 份，每份最后 64 KB
    if [ -s "$D/err.log" ]; then
      L="${D%/hosts/*}/stderr"
      T="$L/.${D##*/}.part"
      mkdir -p "$L" && { echo "# zeta: claude 退出码 $code，$(date "+%F %T %z")"; tail -c 65536 "$D/err.log"; } > "$T" &&
        mv -f "$T" "$L/$(date +%Y%m%d-%H%M%S)-${D##*/}.log"
      ls -1t "$L" 2>/dev/null | tail -n +21 | while read -r f; do rm -f "$L/$f"; done
    fi
    [ -d "$D" ] && echo $code > "$D/exit"
  ' zeta-run "$(declare -f turn_running tasks_running)" "$D" "$run" "$@" </dev/null >/dev/null 2>&1 &
  for _ in $(seq 100); do [ -s "$D/pid" ] && break; sleep 0.05; done
  from=1
else
  from=$3
fi

[ -s "$D/pid" ] || { echo "zeta: 进程没有启动" >&2; cat "$D/err.log" >&2 2>/dev/null; exit 98; }
P=$(cat "$D/pid")
echo $$ > "$D/attach"

# 两头任一结束就整体退出：应用没了（输入结束）只是断开；Claude Code 没了（跟读结束）则报告退出码。
tail -n "+$from" --pid="$P" -f "$D/out.jsonl" &
T=$!
cat <&0 > "$D/in" &
C=$!
wait -n
kill "$T" "$C" 2>/dev/null
if [ -f "$D/exit" ]; then
  python3 -c '
import json, sys
d = sys.argv[1]
def read(name):
    try:
        return open(d + "/" + name, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""
print(json.dumps({"type": "zeta_exit", "code": int(read("exit").strip() or 1), "stderr": read("err.log")[-2000:]}))
' "$D"
  rm -rf "$D"
fi
