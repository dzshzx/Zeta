# Zeta 在 Debian 里管理多个 Claude 账号的登录状态（bash，要 jq）。以 Claude Code 所属的普通用户身份运行。
#
#   list   <名字>          当前登录（L）、存着的账号（A）、还开着的 Claude Code 进程（P）
#   save   <名字>          把当前登录存一份（没登录就不存）
#   switch <名字> <账号>    换成存着的那个账号：先把当前登录存回去再换；还有 Claude Code 进程开着就不换（BUSY）
#   remove <名字> <账号>    删掉存着的一份（只删副本，不注销那个账号）
#
# 名字是 Zeta 自己在 Linux 里的目录名（正式版 zeta，测试版 zeta-dev）。每个账号存一个文件
# ~/.config/<名字>/accounts/<账号 uuid>.json（目录 700、文件 600），内容是 ~/.claude/.credentials.json 里的
# claudeAiOauth 和 ~/.claude.json 里的 oauthAccount。令牌只在这个脚本里由 jq 从文件读、往文件写，不出现在
# 命令行和输出里；输出只有账号 uuid、邮箱、组织、套餐这些。
#
# 为什么要等进程都退出才换：开着的 Claude Code（哪怕闲着）会在凭据文件变了以后把它记着的旧 oauthAccount
# 写回 ~/.claude.json（2.1.328 实测，见 ClaudeCodeQuotaContractTest），换出来的就是新令牌配旧账号。
#
# 输出先打一行 @zeta，后面每条记录一行、字段用 NUL 分开（同 ClaudeScripts）。
set -u
umask 077
printf '@zeta\n'
z() { printf '%s\0' "$@"; printf '\n'; }
command -v jq >/dev/null 2>&1 || { echo "Linux 环境里没有 jq" >&2; exit 7; }

cmd=${1:-}
N=${2:-zeta}
S=$HOME/.config/$N/accounts
CRED=$HOME/.claude/.credentials.json
CFG=$HOME/.claude.json

valid_id() { case $1 in '' | *[!A-Za-z0-9-]*) return 1 ;; esac; }

logged_in() { [ -f "$CRED" ] && jq -e '.claudeAiOauth.accessToken | strings' "$CRED" >/dev/null 2>&1; }

# 刷新令牌的指纹：只拿来认出当前登录是存着的哪一份，不输出
fp() {
  local t
  t=$(jq -j '.claudeAiOauth.refreshToken // empty' "$1" 2>/dev/null) || return 0
  [ -n "$t" ] && printf '%s' "$t" | sha256sum | cut -d' ' -f1
}

# 当前登录是哪个账号。刷新令牌和存着的某一份一样就是那个（~/.claude.json 里的 oauthAccount 可能被开着的进程写回了旧的）；
# 令牌刷新过、对不上时看 ~/.claude.json
live_id() {
  logged_in || return 0
  local lf r
  lf=$(fp "$CRED")
  if [ -n "$lf" ]; then
    for r in "$S"/*.json; do
      [ -f "$r" ] || continue
      if [ "$(fp "$r")" = "$lf" ]; then
        jq -r '.oauthAccount.accountUuid // empty' "$r"
        return 0
      fi
    done
  fi
  jq -r '.oauthAccount.accountUuid // empty' "$CFG" 2>/dev/null
}

# 账号 $1 的 oauthAccount（一行 JSON）：~/.claude.json 里记的正是它就用那份（最新），否则用存着的
account_of() {
  local cfg_id
  cfg_id=$(jq -r '.oauthAccount.accountUuid // empty' "$CFG" 2>/dev/null)
  if [ -n "$1" ] && [ "$cfg_id" = "$1" ]; then
    jq -c '.oauthAccount' "$CFG"
  elif [ -n "$1" ] && [ -f "$S/$1.json" ]; then
    jq -c '.oauthAccount' "$S/$1.json"
  fi
}

# 一条账号记录的字段：uuid、邮箱、组织、显示名、套餐名、订阅类型、刷新令牌到期（毫秒）、存下的时刻（毫秒），
# 用 \x1f 分开（不用 Tab：read 会把连着的几个 Tab 当成一个，空字段就错位了）。标准输入是记录。
fields() {
  jq -r '[(.oauthAccount.accountUuid // ""), (.oauthAccount.emailAddress // ""), (.oauthAccount.organizationName // ""),
          (.oauthAccount.displayName // ""), (.oauthAccount.planDisplayName // ""), (.claudeAiOauth.subscriptionType // ""),
          (.claudeAiOauth.refreshTokenExpiresAt // ""), (.savedAt // "")]
         | map(tostring | gsub("[\n\r\u001f]"; " ")) | join("\u001f")'
}

emit() {
  local tag=$1 id email org name plan sub exp saved
  IFS=$'\x1f' read -r id email org name plan sub exp saved || return 0
  z "$tag" "$id" "$email" "$org" "$name" "$plan" "$sub" "$exp" "$saved"
}

# 还活着的 Claude Code 进程：pid、归属（哪个 Zeta 包的后台对话，别处的为空）、状态（idle、busy…）、种类
procs() {
  local f pid want have owner h
  for f in "$HOME"/.claude/sessions/*.json; do
    [ -f "$f" ] || continue
    pid=$(jq -r '.pid // empty' "$f" 2>/dev/null)
    case $pid in '' | *[!0-9]*) continue ;; esac
    [ -r "/proc/$pid/stat" ] || continue
    # pid 被别的进程复用了：启动时刻对不上
    want=$(jq -r '.procStart // empty | tostring' "$f" 2>/dev/null)
    have=$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | cut -d' ' -f20)
    [ -n "$want" ] && [ "$want" != "$have" ] && continue
    owner=
    for h in "$HOME"/.cache/*/hosts/*/pid; do
      [ -f "$h" ] || continue
      if [ "$(cat "$h" 2>/dev/null)" = "$pid" ]; then
        owner=${h#"$HOME"/.cache/}
        owner=${owner%%/*}
        break
      fi
    done
    z P "$pid" "$owner" "$(jq -r '.status // ""' "$f" 2>/dev/null)" "$(jq -r '.kind // ""' "$f" 2>/dev/null)"
  done
}

# 把当前登录存进 $S/<uuid>.json，输出 uuid
save_live() {
  logged_in || return 1
  local id acct tmp
  id=$(live_id)
  valid_id "$id" || return 2
  acct=$(account_of "$id")
  [ -n "$acct" ] || return 2
  mkdir -p "$S" && chmod 700 "$S" || return 3
  tmp=$S/.$id.$$.tmp
  if jq --argjson a "$acct" --argjson t "$(date +%s%3N)" '{oauthAccount: $a, claudeAiOauth: .claudeAiOauth, savedAt: $t}' "$CRED" >"$tmp"; then
    mv -f "$tmp" "$S/$id.json" || { rm -f "$tmp"; return 3; }
  else
    rm -f "$tmp"
    return 3
  fi
  printf '%s' "$id"
}

# 用记录 $3 改 JSON 文件 $1（jq 表达式 $2，记录在 $r[0]）：写到同目录的临时文件再改名，权限照旧；是符号链接时改它指向的文件
write_json() {
  local t tmp
  t=$(readlink -f -- "$1" 2>/dev/null) || t=$1
  [ -n "$t" ] || t=$1
  mkdir -p -- "${t%/*}" || return 1
  tmp=${t%/*}/.${t##*/}.zeta-$$
  if [ -f "$t" ]; then
    jq --slurpfile r "$3" "$2" "$t" >"$tmp" || { rm -f "$tmp"; return 1; }
    chmod --reference="$t" "$tmp" 2>/dev/null
  else
    jq -n --slurpfile r "$3" "{} | $2" >"$tmp" || { rm -f "$tmp"; return 1; }
  fi
  mv -f -- "$tmp" "$t" || { rm -f "$tmp"; return 1; }
}

case $cmd in
  list)
    if logged_in; then
      id=$(live_id)
      acct=$(account_of "$id")
      jq -n --slurpfile c "$CRED" --argjson a "${acct:-null}" \
        '{oauthAccount: ($a // {}), claudeAiOauth: ($c[0].claudeAiOauth | {subscriptionType, refreshTokenExpiresAt})}' | fields | emit L
    else
      z L
    fi
    for r in "$S"/*.json; do
      [ -f "$r" ] || continue
      fields <"$r" | emit A
    done
    procs
    ;;
  save)
    if id=$(save_live); then z OK "$id"; else z FAILED "$?"; fi
    ;;
  switch)
    id=${3:-}
    if ! valid_id "$id" || [ ! -f "$S/$id.json" ]; then
      z MISSING
      exit 0
    fi
    # Zeta 刚叫停的自己的对话要一点时间退出：等到一个都不剩，最多约 8 秒
    for _ in $(seq 40); do
      [ -z "$(procs)" ] && break
      sleep 0.2
    done
    if [ -n "$(procs)" ]; then
      procs
      z BUSY
      exit 0
    fi
    if logged_in && ! save_live >/dev/null; then
      z FAILED save
      exit 0
    fi
    # 先改 ~/.claude.json 的 oauthAccount，再换凭据；凭据文件只换 claudeAiOauth，别的键（MCP 服务器的 OAuth 等）照旧
    write_json "$CFG" '.oauthAccount = $r[0].oauthAccount' "$S/$id.json" || { z FAILED config; exit 0; }
    write_json "$CRED" '.claudeAiOauth = $r[0].claudeAiOauth' "$S/$id.json" || { z FAILED credentials; exit 0; }
    chmod 600 "$CRED"
    z OK "$id"
    ;;
  remove)
    id=${3:-}
    valid_id "$id" && rm -f -- "$S/$id.json"
    z OK
    ;;
  *)
    echo "zeta_accounts: 不认识的命令 $cmd" >&2
    exit 2
    ;;
esac
