# Zeta 在 Debian 里用来托管 Claude Code 进程的脚本（bash）。
#
# Claude Code 不直接挂在应用的管道上，而是脱离出去：输入来自一个命名管道，输出追加到日志文件。
# 应用这一头只是“接上去”——把自己的标准输入转进管道、把日志跟读到标准输出。
# 这样应用被系统回收或被划掉时，进行到一半的任务照常跑完，重新打开应用再接上即可。
#
#   start  <标识> <工作目录> <标题> <claude 参数…>   启动并接上；接着旧会话（--resume，不分支）而本包已有进程在跑这个会话时
#                                                     不另起，接上那一个（zeta_attached 里的 key 是它的标识）
#   attach <标识> <从第几行开始读>                    接上一个已在运行的
#   stop   <标识>                                     结束会话（等价于终端里的 /exit）并清理，立即返回
#   state  <标识>                                     看门狗眼里的样子：输出 turn=1 表示这一轮在进行，tasks=1 表示有后台任务在跑
#
# 目录按包分开：ZETA_NAME 是 Zeta 自己的目录名（手机正式版和桌面版都是 zeta，测试版 zeta-dev），由应用进环境时传进来。
# 两个包看不到对方的后台进程，也就不会接管或结束对方的对话。同一个包的几个客户端（电脑上的桌面版和手机正式版、两台手机连同一台主机）
# 可以同时接着同一个托管目录：输出各自跟读，输入整行地写进同一个管道。
#
# 托管目录 ~/.cache/<ZETA_NAME>/hosts/<标识>/ 里的文件。start 建目录；Claude Code 退出后由最后离开的接着的一方、stop（没人接着时）
# 或 zeta-helper hosts（进程没了、也没人接着时）删掉，不抢在还接着的一方读到 exit 之前删；
# 存储页（zeta-helper clean）清掉进程和接着的一方都已不在的。各版本的脚本随各自的客户端带着，可能同时操作同一个目录：
# 改这里的文件和规矩时，新旧两边都要能接着用。
#   in         命名管道，Claude Code 的标准输入。start 建，起进程的那段以读写方式一直开着；接着的一方把应用的标准输入一行行转进来；
#              stop、看门狗写 end_session，zeta-helper（releaseSession）写 zeta-release。写的一方都持着 lock 整行写（write_line、pump）。
#   lock       写 in 的锁（flock），写的一方用时建，谁都不删它。几个写的一方各写各的大行（带图片的消息有几百 KB，管道一次收不下）
#              不会互相插断。没有 flock（util-linux）的主机上不加锁。
#   session    start 时要接着的会话号（--resume 的，不分支时才写）。Claude Code 还没输出（第一条消息之前）时，
#              本脚本（host_session）和 zeta-helper（hostSessionNow、hosts）按它认出这个目录托管的是哪个会话；
#              有了输出以输出里最新的 session_id 为准。
#   attach.d/  接着的一方各一个记录：文件名是它的进程号，内容是它的启动时刻（/proc/<pid>/stat 第 22 项）。接上时写，离开时删自己的；
#              进程已不在（或进程号被复用）的记录由看到它的人删（attachers）。看门狗据此知道还有没有应用接着；
#              stop 据此决定删不删目录；zeta-helper（liveAttachers）的 hosts、存储页据此不删还有人接着的目录。
#   out.jsonl  Claude Code 的标准输出（stream-json，一行一个事件，只追加）。接着的一方从指定的行起跟读给核心（helper/host 的 Link）；
#              看门狗看这一轮和后台任务；zeta-helper hosts 摘出行数、会话、这一轮、待批准的调用、权限模式和后台任务，
#              核心起来时据此接回在干活的对话（helper/conversations.go 的 adoptHosts）；zeta-helper 的存储页从开头取会话号。
#   pid        起进程的那段起的进程号，那段写：通常就是 Claude Code；登录配置把 claude 包成 shell 函数或不 exec 的脚本时是外面那层，
#              Claude Code 是它的子进程。它活着就是 Claude Code 还在：本脚本 attach、stop、看门狗，zeta-helper 的 hosts、存储页据此看；
#              应用的 PathCheck（AttachedPaths.kt）经 /proc/<pid>/root 看这个进程的挂载命名空间。
#              认哪个登记的进程是 Zeta 托管的不看它，看进程身上的记号：起进程的那段给 Claude Code 设环境变量 ZETA_HOST=<ZETA_NAME>/<标识>
#              （host_mark 读，样子同 zeta-helper 的 hostMark）。记号会传给 Claude Code 起的子进程（Bash 工具里再起的 claude），
#              所以还要会话对得上：托管目录输出里最新的会话（/clear 会换）就是登记的会话。held_elsewhere、zeta-helper（hostDirOf）都这样认。
#   cwd        启动时的工作目录，start 写。zeta-helper hosts 接回时用。
#   title      启动时的标题，start 写。zeta-helper hosts 在会话记录里没有标题时用；诊断页（DiagScripts.kt）列运行中的日志时带上。
#   exit       Claude Code 的退出码，它退出后起进程的那段写。每个接着的一方见到它都报 zeta_exit；zeta-helper hosts 见到它就当已结束。
#   released   zeta-helper 让出会话（终端里接着了，releaseSession）结束进程前留的记号：接着的一方报 zeta_exit 时带上 "reason":"released"。
#   err.log    Claude Code 的标准错误输出，起进程的那段追加。诊断页读运行中的；Claude Code 退出时留一份到 ~/.cache/<ZETA_NAME>/stderr/；
#              接着的一方报 zeta_exit 时带上最后一段，进程没起来时打出来。
#   user_mode  用户在这个对话里的权限模式（进程现在的模式可能是定时任务等后台运行的），Go 核心（helper/conv 的 keepUserMode）
#              变了就写，目录已经没了时不写；zeta-helper hosts 读，核心重启后接回进程时照它恢复。本脚本不读不写。
#   client     最近一次在这个对话里发消息（或要起进程）的客户端是哪台设备（一行设备名，客户端连上核心时用 core.client 报的），
#              Go 核心（helper/conv 的 keepDevice）变了就写，目录已经没了时不写；另一个包的 zeta-helper 查别处占用时读
#              （occupantwhere.go 的 deviceOf），「别处运行」卡片说「在哪台设备的 Zeta 里」。本脚本不读不写。
#
# 托管目录旁边的 hosts/.lock：start 找现成的进程和起新进程之间持着它（flock），几个客户端同时 start 同一个会话时只起一个。
# 谁都不删它；zeta-helper 列托管目录时跳过点开头的名字。
set -u
mode=$1
D="$HOME/.cache/${ZETA_NAME:-zeta}/hosts/$2"

# Claude Code 用的语言环境：和这台主机上终端里起的一样，一台主机一个（Zeta 自己跑的命令一律 C.UTF-8，它们的输出要解析）。
# 先看客户端带来的 ZETA_LANG（客户端连进来那一刻登录环境里的 LANG，见 LinuxShell.remoteCommand、hostlink.RemoteCommand），
# 没有就看主机的 /etc/default/locale（手机自带的 Debian 走这条）；都没有、是 C 或主机上没装这个语言环境时用 C.UTF-8
# （硬设没装的会退回 C，中文就乱了）。
host_lang() {
  local l want
  l=${ZETA_LANG:-}
  [ -n "$l" ] || l=$(unset LANG; . /etc/default/locale 2>/dev/null; printf '%s' "${LANG-}")
  case $l in '' | C | C.* | POSIX) echo C.UTF-8; return ;; esac
  if command -v locale >/dev/null 2>&1; then
    # locale -a 列的是 zh_CN.utf8 这种写法：比较时都转小写、去掉连字符
    want=$(printf '%s' "$l" | tr 'A-Z' 'a-z' | tr -d '-')
    locale -a 2>/dev/null | tr 'A-Z' 'a-z' | tr -d '-' | grep -qxF "$want" || { echo C.UTF-8; return; }
  fi
  echo "$l"
}

# 这一轮在不在进行：看最后一条开始或结束事件，是开始就在进行，一条都没有就是没在进行。
# 开始是 system init（每一轮开头都有，后台任务做完后 Claude Code 自己起的一轮也只有它）和
# command_lifecycle started（Zeta 发的消息被取走，插话并进这一轮时也有），结束是 result。
# 别的行都不算：result 之后还会写 command_lifecycle completed、task_notification、background_tasks_changed 等收尾事件，
# 后台子代理内部的行（assistant、user，带 parent_tool_use_id）在这一轮的 result 之后还会陆续写来。
# result 行的键顺序不固定，type 不一定在行首；内容里的引号都转义过，不会误中。
# Conversation.handle 和 zeta-helper 的 scan_host 是同一口径（Conversation 另外认它自己发出、还在排队的消息），
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

# 进程 $1 身上的记号 ZETA_HOST 的值（<包>/<托管目录名>：包名以 zeta 开头，目录名不空、不含 /、不以 . 开头）；没有、样子不对时不输出。
host_mark() {
  local -a vars
  local v
  mapfile -d '' -t vars 2>/dev/null <"/proc/$1/environ" || return 0
  for v in "${vars[@]}"; do
    [[ $v =~ ^ZETA_HOST=(zeta[^/]*/[^/.][^/]*)$ ]] && { echo "${BASH_REMATCH[1]}"; return 0; }
  done
}

# 进程 $1 的 /proc/<pid>/stat 里括号中进程名之后的各项，放进调用方的数组 STAT（[0] 状态、[1] 父进程号、[19] 启动时刻）；
# 进程不在了时失败。调用方先 local -a STAT。
stat_of() {
  local line
  read -r line 2>/dev/null <"/proc/$1/stat" || return 1
  read -ra STAT <<<"${line##*) }"
}

# 会话 $1 正被 Zeta 之外（不是本包托管）的 Claude Code 进程占着，比如终端里开着的 claude：
# 和 zeta-helper 的 outsideRegistrations 同一口径看 ~/.claude/sessions 的登记：进程还在、启动时刻（procStart）对得上、
# 不是本包托管的（见开头 pid 一项：带着本包的记号、那个托管目录输出里最新的会话就是 $1）。
# 两个进程同时写一份会话记录会错乱，所以这时不起进程，由 Zeta 先把会话从那边接过来（zeta-helper handoff）。
held_elsewhere() {
  local f pid m
  local -a STAT
  while read -r f; do
    pid=${f##*/}
    pid=${pid%.json}
    case $pid in '' | *[!0-9]*) continue ;; esac
    stat_of "$pid" || continue
    grep -qF '"procStart"' "$f" && ! grep -qF "\"procStart\":\"${STAT[19]}\"" "$f" && continue
    m=$(host_mark "$pid")
    [ "${m%%/*}" = "${ZETA_NAME:-zeta}" ] && [ "$(host_session "${D%/*}/${m#*/}")" = "$1" ] && continue
    return 0
  done < <(grep -lsF "\"sessionId\":\"$1\"" "$HOME"/.claude/sessions/*.json)
  return 1
}

# 托管目录 $1 的 Claude Code 现在的会话：输出里最新的 session_id（/clear 会换）；还没有输出时是 session 文件记的。
# 同 zeta-helper 的 hostSessionNow。
host_session() {
  local s
  s=$(tail -c 65536 "$1/out.jsonl" 2>/dev/null | grep -o '"session_id":"[^"]*"' | tail -n 1)
  s=${s#\"session_id\":\"}
  s=${s%\"}
  [ -n "$s" ] || s=$(cat "$1/session" 2>/dev/null)
  printf '%s' "$s"
}

# 本包另一个托管目录（不是 $D）里还在跑的 Claude Code 现在托管着会话 $1（host_session）时，输出那个目录的标识；没有时不输出。
# start 据此接上它，不另起一个进程。
hosted_for() {
  local d
  for d in "${D%/*}"/*/; do
    d=${d%/}
    [ "$d" != "$D" ] && [ ! -f "$d/exit" ] && running "$(cat "$d/pid" 2>/dev/null)" || continue
    [ "$(host_session "$d")" = "$1" ] && { echo "${d##*/}"; return 0; }
  done
}

# 托管目录 $1 里还接着的一方的进程号，一行一个。看 attach.d 的记录（进程还在、启动时刻对得上），
# 对不上的记录顺手删掉。
# idle_limit：看门狗闲置多少轮（每轮 ZETA_IDLE_STEP 秒，默认 60，所以就是分钟数）就结束进程，0 是不自动结束。
# 每台主机一份，在 ~/.config/<ZETA_NAME>/idle-minutes（设置页经核心的 system.set_idle 写，见 helper/idle.go），每轮重读，改了马上生效；
# 没有或不是整数时用 ZETA_IDLE_LIMIT（测试用），再没有是 10。
idle_limit() {
  local v
  v=$(cat "$HOME/.config/${ZETA_NAME:-zeta}/idle-minutes" 2>/dev/null) || v=
  case $v in
    '' | *[!0-9]*) echo "${ZETA_IDLE_LIMIT:-10}" ;;
    *) echo "$v" ;;
  esac
}

attachers() {
  local d=$1 f p
  local -a STAT
  for f in "$d"/attach.d/*; do
    p=${f##*/}
    case $p in '' | *[!0-9]*) continue ;; esac
    if stat_of "$p" && [ "${STAT[0]}" != Z ] && [ "$(cat "$f" 2>/dev/null)" = "${STAT[19]}" ]; then
      echo "$p"
    else
      rm -f "$f"
    fi
  done
  return 0
}

# 往托管目录 $1 的输入管道写一整行 $2，持着 lock（见开头 lock 一项）。管道由起进程的那段以读写方式一直开着，写不会卡住；
# 那段刚好不在了、或锁一直被占着时由 timeout 兜底，最多 2 秒。
write_line() {
  local w='printf "%s\n" "$1" > "$2"'
  if command -v flock >/dev/null 2>&1; then
    timeout 2 flock "$1/lock" bash -c "$w" zeta-write "$2" "$1/in"
  else
    timeout 2 bash -c "$w" zeta-write "$2" "$1/in"
  fi 2>/dev/null
}

# 接着的一方把标准输入一行行转进托管目录 $1 的输入管道，每行持着 lock 整行写进去；没有 flock 时原样转。
# 连接断在一行中间时，那半行不写。
pump() {
  local line
  exec 9>"$1/in" || return
  if command -v flock >/dev/null 2>&1 && exec 8>>"$1/lock"; then
    while IFS= read -r line; do
      flock 8 && printf '%s\n' "$line" >&9
      flock -u 8
    done
  else
    cat >&9
  fi
}

# 托管目录 $1 的 Claude Code：~/.claude/sessions 里登记的、身上带着这个目录记号的进程，一行一个（它起的、也登记了的子进程也在内，
# 一并结束无妨）。还没登记（刚起来）时什么也不输出。
hosted_claude() {
  local n=${1%/hosts/*} f
  local -a files=()
  for f in "$HOME"/.claude/sessions/*.json; do
    f=${f##*/}
    f=${f%.json}
    case $f in '' | *[!0-9]*) continue ;; esac
    files+=("/proc/$f/environ")
  done
  [ ${#files[@]} -gt 0 ] || return 0
  grep -lszxF "ZETA_HOST=${n##*/}/${1##*/}" "${files[@]}" | while read -r f; do
    f=${f#/proc/}
    echo "${f%/environ}"
  done
}

# 进程 $@ 里还有在跑的（僵尸不算，同 zeta-helper 的 running）。不用 kill -0：它对僵尸也成功。包了一层时 Claude Code 是外面那层的子进程，
# 两个一起收到 SIGTERM 后它的僵尸改挂到祖先里的 subreaper（或 init）名下，那边不收尸时它会一直留着。
running() {
  local p
  local -a STAT
  for p; do
    stat_of "$p" || continue
    [ "${STAT[0]}" != Z ] && return 0
  done
  return 1
}

# 结束 Claude Code（托管目录 $1、pid 文件记的进程 $2），等价于终端里的 /exit（-p 模式不认 /exit）：往输入管道写控制请求 end_session，
# 由它自己停下进行中的这一轮、跑 SessionEnd hook、退出，会话记录留着（ClaudeCodeContractTest 钉住）。
# 等它退出最多 ZETA_STOP_GRACE 秒（默认 10），不退（卡住了）再 SIGTERM，还不退就 SIGKILL：信号发给 $2 和 hosted_claude 找到的进程
# （$2 可能只是外面那层，见开头 pid 一项）。经 SIGTERM 结束时，返回时它们都已不在跑。stop 和看门狗都用它。
end_claude() {
  local d=$1 p=$2 i
  running "$p" || return 0
  write_line "$d" '{"type":"control_request","request_id":"zeta-end","request":{"subtype":"end_session"}}'
  for ((i = 0; i < ${ZETA_STOP_GRACE:-10} * 5; i++)); do running "$p" || return 0; sleep 0.2; done
  set -- "$p" $(hosted_claude "$d") # 一行一个进程号，按词分开
  kill "$@" 2>/dev/null
  for ((i = 0; i < 25; i++)); do running "$@" || return 0; sleep 0.2; done
  kill -9 "$@" 2>/dev/null
}

if [ "$mode" = state ]; then
  turn_running "$D/out.jsonl" && t=1 || t=0
  tasks_running "$D/out.jsonl" && b=1 || b=0
  echo "turn=$t tasks=$b"
  exit 0
fi

# 结束会话（end_claude），没人接着时删托管目录（还有人接着时由最后离开的那个在读到 exit 后删）。
# 整个放在单独的会话（setsid）里，本命令立即返回，Zeta 这时被杀也照样做完。
if [ "$mode" = stop ]; then
  setsid bash -c '
    eval "$1"; D=$2; P=$3
    if [ -n "$P" ]; then
      end_claude "$D" "$P"
      # 等起进程的那段留下退出码和标准错误输出的副本（诊断页看）
      for _ in $(seq 10); do [ -f "$D/exit" ] && break; sleep 0.1; done
    fi
    [ -n "$(attachers "$D")" ] || rm -rf "$D"
  ' zeta-stop "$(declare -f stat_of running hosted_claude write_line end_claude attachers)" "$D" "$(cat "$D/pid" 2>/dev/null)" \
    </dev/null >/dev/null 2>&1 &
  exit 0
fi

joined=
if [ "$mode" = start ]; then
  cwd=$3
  title=$4
  shift 4
  cd "$cwd" 2>/dev/null || { echo "zeta: 进不去工作目录 $cwd" >&2; exit 97; }
  # 接着旧会话（--resume <id>，不是分支出新会话）时先看本包是不是已经有进程在跑它，再看它是不是正被别处占着
  resume="" fork=0 model=0 prev=""
  for a in "$@"; do
    [ "$prev" = --resume ] && resume=$a
    case $a in --fork-session) fork=1 ;; --model) model=1 ;; esac
    prev=$a
  done
  [ $fork = 1 ] && resume=""
  # 找现成的和起新的之间持着 hosts/.lock：几个客户端同时 start 同一个会话时，后来的找得到先来的起的那个（session 文件在放锁前写好）
  mkdir -p "${D%/*}"
  if command -v flock >/dev/null 2>&1 && exec 7>>"${D%/*}/.lock"; then flock -w 10 7; fi
  [ -n "$resume" ] && joined=$(hosted_for "$resume")
  if [ -n "$resume" ] && [ -z "$joined" ] && held_elsewhere "$resume"; then
    echo '{"type":"zeta_exit","code":0,"reason":"held_elsewhere","stderr_base64":""}'
    exit 0
  fi
fi

if [ -n "$joined" ]; then
  # 接上本包已在跑这个会话的进程：从它现有输出的下一行读起（之前的内容应用从会话记录里读），不另起进程
  exec 7>&-
  D=${D%/*}/$joined
  from=$(wc -l < "$D/out.jsonl" 2>/dev/null)
  from=$((${from:-0} + 1))
elif [ "$mode" = start ]; then
  rm -rf "$D"
  mkdir -p "$D" && mkfifo "$D/in" || { echo "zeta: 建不了 $D" >&2; exit 97; }
  : > "$D/out.jsonl"
  printf '%s' "$cwd" > "$D/cwd"
  printf '%s' "$title" > "$D/title"
  [ -n "$resume" ] && printf '%s' "$resume" > "$D/session"
  # Claude Code 对新模型默认不开任务清单工具（TaskCreate、TaskUpdate 等），聊天页的任务清单面板靠它们，这里打开。
  # 环境变量或 settings.json 的 env 里明确设过的照那边的。
  export CLAUDE_CODE_ENABLE_TODO_TOOLS="${CLAUDE_CODE_ENABLE_TODO_TOOLS:-1}"
  # 起哪个程序：正式版（ZETA_NAME=zeta）永远是真的 claude，参数原样。
  # 只有测试版在设备测试里放了剧本时换成按剧本回话的假 Claude（tools/fake-claude；vd fake 放进来，vd stop 删掉）。
  # 测试版起真的 claude 时新开、没指定模型的对话补 Haiku 5.5，规则见 CLAUDE.md「真跑用 Haiku 5.5」。
  # 只补 --model，不设 ANTHROPIC_MODEL：接着旧会话时那个变量让请求换成它、init 却还报记录里的模型（2.1.330 实测）。
  run=claude
  if [ "${ZETA_NAME:-zeta}" = zeta-dev ]; then
    if [ -f "$HOME/.config/zeta-dev/fake-claude/scenario.jsonl" ]; then
      run="$HOME/.config/zeta-dev/fake-claude/fake-claude.sh"
    elif [ -z "$resume" ] && [ $fork = 0 ] && [ $model = 0 ]; then
      set -- "$@" --model claude-haiku-5-5
    fi
  fi
  # 管道用读写方式打开并一直持有：写的一方来来去去，Claude Code 都不会读到“输入结束”。
  # 看门狗的两个判定函数和结束用的 end_claude（连同它用的 running、hosted_claude）写在上面，经第一个参数带进去（这一段在单引号里）
  ZETA_CLAUDE_LANG=$(host_lang) setsid bash -c '
    eval "$1"; D=$2; R=$3; shift 3
    L=$ZETA_CLAUDE_LANG; unset ZETA_CLAUDE_LANG
    exec 3<>"$D/in"
    # 记号：Claude Code 归哪个包的哪个托管目录，包了几层都传得过去（见开头 pid 一项）
    N=${D%/hosts/*}
    export ZETA_HOST="${N##*/}/${D##*/}"
    LANG=$L "$R" "$@" <&3 >>"$D/out.jsonl" 2>>"$D/err.log" &
    P=$!
    echo $P > "$D/pid"
    # 进程起来以后就待在托管目录里（进程的工作目录在起它时已经定了）：同一个标识的目录随后被删掉重建时
    # （stop 后马上又 start），下面的 exit 写进的是已删掉的这一个、写不成，不会把新进程报成已结束
    mine=
    cd "$D" && mine=1
    # 看门狗：没有应用接着（attachers 一个也没有）、这一轮又已经结束的进程，闲置满 idle_limit 轮（默认十分钟，每台主机可设）后
    # 照 stop 那样结束（end_claude），免得应用被杀之后它一直占着内存。还在干活、在等用户确认或还有后台任务在跑的不动。
    (
      n=0
      while [ -d "$D" ] && kill -0 $P 2>/dev/null; do
        sleep "${ZETA_IDLE_STEP:-60}"
        if [ -n "$(attachers "$D")" ]; then
          n=0
        # 这一轮结束了、也没有后台任务在跑才算闲着（在等用户确认时这一轮还没结束）
        elif ! turn_running "$D/out.jsonl" && ! tasks_running "$D/out.jsonl"; then
          n=$((n + 1))
          limit=$(idle_limit)
          [ "$limit" -gt 0 ] && [ $n -ge "$limit" ] && end_claude "$D" $P
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
    [ -n "$mine" ] && echo $code > exit
  ' zeta-run "$(declare -f turn_running tasks_running stat_of running hosted_claude write_line end_claude attachers idle_limit)" "$D" "$run" "$@" \
    </dev/null >/dev/null 2>&1 7>&- &
  for _ in $(seq 100); do [ -s "$D/pid" ] && break; sleep 0.05; done
  exec 7>&-
  from=1
else
  from=$3
fi

[ -s "$D/pid" ] || { echo "zeta: 进程没有启动" >&2; cat "$D/err.log" >&2 2>/dev/null; exit 98; }
P=$(cat "$D/pid")
# 起进程的那段（P 的父进程）：P 退出后由它写 exit，下面等它
R=
stat_of "$P" && R=${STAT[1]}
# 登记自己是接着的一方（见开头 attach.d 一项）：先写临时文件再改名，别人看到的记录总是写完了的
stat_of $$ && mkdir -p "$D/attach.d" && echo "${STAT[19]}" > "$D/attach.d/.$$" && mv -f "$D/attach.d/.$$" "$D/attach.d/$$"
# 接上了：之后标准输出的每一行都是 out.jsonl 的一行（从第 from 行起），直到 zeta_exit。
# 接着的一方按这个数记读到了第几行（helper/host 的 Link）；之前的行是登录 shell 打的别的东西。
# key 是接上的托管目录（start 接上本包现成的进程时不是应用给的那个）
echo "{\"type\":\"zeta_attached\",\"from\":$from,\"key\":\"${D##*/}\"}"

# 两头任一结束就整体退出：应用没了（输入结束）只是断开；Claude Code 没了（跟读结束）则报告退出码。
# 只等跟读这一个进程，转输入的那段结束时顺手结束跟读。不用 wait -n：子进程恰好在它开始阻塞之前结束时 bash 会漏掉，
# 一直等下去（bash 5.3 实测，负载高时约千分之一），接着的一方就挂着不走，看门狗也一直当还有人接着
tail -n "+$from" --pid="$P" -f "$D/out.jsonl" &
T=$!
{ pump "$D"; kill "$T" 2>/dev/null; } <&0 &
C=$!
wait "$T"
kill "$C" 2>/dev/null
# Claude Code 没了：那段先收尸、留标准错误输出的副本，再写 exit，跟读却在进程一没就结束了。等它写完（或它也不在了）再看，
# 否则 exit 晚到一步就漏报 zeta_exit：应用拿不到退出码和 released
if ! running "$P"; then
  while [ ! -f "$D/exit" ] && [ -d "$D" ] && running "$R"; do sleep 0.05; done
fi
if [ -f "$D/exit" ]; then
  # 退出报告一行 JSON：退出码，标准错误输出的最后 2000 字节（base64，不用转义；核心那头解码，见 helper/host 的 decodeStderr）。
  # 只用 coreutils，远程主机上不用装别的
  code=$(tr -cd '0-9' < "$D/exit")
  # reason：released 是会话交回终端接着了（见上面的 released 文件）；没起进程时的 held_elsewhere 在 start 里报
  [ -f "$D/released" ] && reason='"released"' || reason=null
  printf '{"type":"zeta_exit","code":%s,"reason":%s,"stderr_base64":"%s"}\n' "${code:-1}" "$reason" \
    "$(tail -c 2000 "$D/err.log" 2>/dev/null | base64 | tr -d '\n')"
fi
# 离开：删自己的记录。
# Claude Code 已经退出而别的接着的一方都走了时，删掉托管目录
rm -f "$D/attach.d/$$"
[ -f "$D/exit" ] && [ -z "$(attachers "$D")" ] && rm -rf "$D"
exit 0
