# Zeta 在 Debian 里管 Claude 账号用的小脚本（bash）。以 Claude Code 所属的普通用户身份运行。
#
# 账号本身由 claude-switch 管（开源程序 github.com/dzshzx/claude-switch，Zeta 带着它，第一次用时装进
# ~/.cache/<ZETA_NAME>/bin，见 HelperProgram）：存档、换号、加号、删号、有 Claude Code 进程在跑时拒绝，都是它的事。
# 这个脚本只做两件事：替 Zeta 跑 claude-switch、把输出和退出码转成记录；列出还开着的 Claude Code 进程和它们归哪个 Zeta 包，
# 界面据此说清是谁挡着。
#
#   <程序> list          还开着的 Claude Code 进程（P），再跑 claude-switch --json
#   <程序> run <参数…>   跑 claude-switch <参数…>；换号和 add 之前先等进程都退出（Zeta 刚叫停的自己的对话要几秒），最多约 8 秒
#
# <程序> 是装好的 claude-switch 相对家目录的路径（HelperProgram.relativePath），带着版本。
# claude-switch 的结果是三条记录：O 标准输出、E 标准错误（都是 base64，可能有多行）、X 退出码；没成功时再列一遍进程（P）。
# 这个版本还没装上时只有一条 MISSING，带 uname -m：Zeta 把对应的一份装上后再跑一次。claude-switch 的输出里没有令牌。
#
# 输出先打一行 @zeta，后面每条记录一行、字段用 NUL 分开（同 ClaudeScripts）。
set -u
umask 077
printf '@zeta\n'
z() { printf '%s\0' "$@"; printf '\n'; }

CS=$HOME/${1:-}
[ $# -gt 0 ] && shift

# 还活着的 Claude Code 进程：pid、归属（哪个 Zeta 包的后台对话，别处的为空）、状态（idle、busy…）、种类。没有 jq 时不列
procs() {
  command -v jq >/dev/null 2>&1 || return 0
  local f pid want status kind have owner v
  local -a vars
  for f in "$HOME"/.claude/sessions/*.json; do
    [ -f "$f" ] || continue
    # 一个文件只跑一次 jq：pid、启动时刻、状态、种类，用 \x1f 分开（不用 Tab：read 会把连着的几个 Tab 当成一个，空字段就错位了）
    IFS=$'\x1f' read -r pid want status kind < <(jq -r '[(.pid // ""), (.procStart // ""), (.status // ""), (.kind // "")]
      | map(tostring | gsub("[\n\r\u001f]"; " ")) | join("\u001f")' "$f" 2>/dev/null) || continue
    case $pid in '' | *[!0-9]*) continue ;; esac
    [ -r "/proc/$pid/stat" ] || continue
    # pid 被别的进程复用了：启动时刻对不上
    have=$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | cut -d' ' -f20)
    [ -n "$want" ] && [ "$want" != "$have" ] && continue
    # 归属看进程身上 zeta_host.sh 设的记号 ZETA_HOST=<包>/<标识>（样子同那边的 host_mark），不看托管目录的 pid 文件
    owner=
    vars=()
    mapfile -d '' -t vars 2>/dev/null <"/proc/$pid/environ"
    for v in "${vars[@]}"; do
      [[ $v =~ ^ZETA_HOST=(zeta[^/]*)/[^/.][^/]*$ ]] && owner=${BASH_REMATCH[1]}
    done
    z P "$pid" "$owner" "$status" "$kind"
  done
}

# 跑 claude-switch，输出 O、E、X；没成功时再列进程
switch_cmd() {
  local err out code
  err=$(mktemp) || { echo "建不了临时文件" >&2; exit 3; }
  out=$("$CS" "$@" 2>"$err")
  code=$?
  z O "$(printf '%s' "$out" | base64 -w0)"
  z E "$(base64 -w0 <"$err")"
  z X "$code"
  rm -f "$err"
  [ "$code" = 0 ] || procs
}

cmd=${1:-}
[ $# -gt 0 ] && shift
case $cmd in
  list | run)
    if [ ! -f "$CS" ] || [ ! -x "$CS" ]; then
      z MISSING "$(uname -m)"
      exit 0
    fi
    ;;
  *)
    echo "zeta_accounts: 不认识的命令 $cmd" >&2
    exit 2
    ;;
esac

if [ "$cmd" = list ]; then
  procs
  switch_cmd --json
  exit 0
fi
case ${1:-} in
  --json | remove) ;;
  *)
    for _ in $(seq 40); do
      [ -z "$(procs)" ] && break
      sleep 0.2
    done
    ;;
esac
switch_cmd "$@"
