# Zeta 安装 Debian 环境时在 Debian 里面执行的部分（bash）。前半段见 zeta_setup_android.sh。
#
#   tools          （root）装 Claude Code 干活要用的常用工具，包清单照 Eta
#   user <用户名>  （root）建一个普通用户，Claude Code 以它的身份运行
#   claude         （那个用户）用官方安装脚本装 Claude Code
#
# 以 "@zeta " 开头的输出行是给应用看的：error <原因>；其余是日志。
set -u
cmd=$1
shift

say() { echo "@zeta $*"; }
fail() { say "error $*"; exit 1; }

# Eta 的包清单，加上 Zeta 自己要用的：python3（读会话记录的助手脚本）、sudo、tzdata
PACKAGES="bash ca-certificates coreutils curl diffutils file findutils gawk git grep gzip jq less openssl openssh-client
  patch procps ripgrep rsync sed sqlite3 tar unzip util-linux wget xz-utils zip zstd fd-find python3 sudo tzdata"
TOOLS_REVISION=1

# 逐个尝试镜像，把能用的写回 sources.list；装之前和之后都检查一遍 dpkg 的状态。照 Eta 的 eta-apt。
write_apt_helper() {
  cat > /usr/local/bin/zeta-apt <<'ZETA_APT_EOF'
#!/bin/sh
set -u
export LC_ALL=C DEBIAN_FRONTEND=noninteractive
run_apt() { apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 "$@"; }
write_sources() {
  case "$1" in
    tuna) archive=https://mirrors.tuna.tsinghua.edu.cn/debian ;;
    official) archive=https://deb.debian.org/debian ;;
    *) return 64 ;;
  esac
  printf '%s\n' "deb $archive trixie main" "deb $archive trixie-updates main" \
    "deb https://security.debian.org/debian-security trixie-security main" > /etc/apt/sources.list
}
healthy() {
  audit=$(dpkg --audit) || return 1
  [ -z "$audit" ] || { printf '%s\n' "$audit" >&2; return 1; }
  run_apt check
}
repair() {
  healthy && return 0
  if dpkg --configure -a && healthy; then return 0; fi
  run_apt --fix-broken --no-remove install -y --no-install-recommends || return 1
  dpkg --configure -a && healthy
}
case "${1:-}" in
  install) action=install; shift; [ "$#" -gt 0 ] || exit 64 ;;
  update) action=update ;;
  *) echo 'usage: zeta-apt install PACKAGE... | update' >&2; exit 64 ;;
esac
for mirror in tuna official; do
  write_sources "$mirror" || exit 65
  run_apt --error-on=any update || continue
  [ "$action" != update ] || exit 0
  repair || exit 72
  if run_apt install -y --no-install-recommends "$@"; then
    healthy || exit 72
    exit 0
  fi
  healthy || exit 72
done
exit 1
ZETA_APT_EOF
  chmod 0755 /usr/local/bin/zeta-apt
}

case "$cmd" in
tools)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  export DEBIAN_FRONTEND=noninteractive
  mkdir -p /usr/local/bin
  write_apt_helper || fail "写不了 /usr/local/bin/zeta-apt"
  # shellcheck disable=SC2086
  /usr/local/bin/zeta-apt install $PACKAGES
  code=$?
  [ $code = 72 ] && fail "软件包数据库有问题，自动修复没有成功"
  [ $code = 0 ] || fail "安装软件包失败，请检查网络后重试"
  command -v fdfind >/dev/null 2>&1 && ln -sf /usr/bin/fdfind /usr/local/bin/fd
  printf '%s\n' 'LANG=C.UTF-8' > /etc/default/locale
  printf '%s\n' "toolset=$TOOLS_REVISION" > /.zeta-tools
  ;;

user)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  name=$1
  if ! id "$name" >/dev/null 2>&1; then
    # 5000 落在 Android 留给厂商、实际没人用的 uid 段里，不会和应用（10000 起）或系统账号撞上
    uid=5000
    while getent passwd "$uid" >/dev/null 2>&1; do uid=$((uid + 1)); done
    useradd -m -U -u "$uid" -s /bin/bash "$name" || fail "建不了用户 $name"
  fi
  home=$(getent passwd "$name" | cut -d: -f6)
  [ -d "$home" ] || fail "用户 $name 没有家目录"
  # Claude Code 装软件、改系统配置要用 sudo；手机本来就已经把 root 交给了这个应用
  usermod -aG sudo "$name" 2>/dev/null
  printf '%s\n' "$name ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/zeta-$name" && chmod 0440 "/etc/sudoers.d/zeta-$name"
  # 官方安装脚本把 claude 放在 ~/.local/bin；目录先建好，登录 shell 才会把它加进 PATH
  install -d -o "$name" -g "$name" "$home/.local" "$home/.local/bin"
  # 工作区是空的（全新的设备）就交给这个用户；里面已有东西则不动它的归属
  if [ -d /workspace ] && [ -z "$(ls -A /workspace 2>/dev/null)" ]; then chown "$name:$name" /workspace; fi
  ;;

claude)
  [ "$(id -u)" != 0 ] || fail "这一步要以普通用户执行"
  if ! [ -x "$HOME/.local/bin/claude" ]; then
    curl -fsSL --connect-timeout 20 --retry 2 https://claude.ai/install.sh -o /tmp/zeta-claude-install.sh \
      || fail "下载 Claude Code 安装脚本失败，请检查网络后重试"
    bash /tmp/zeta-claude-install.sh
    code=$?
    rm -f /tmp/zeta-claude-install.sh
    [ $code = 0 ] || fail "Claude Code 安装脚本没有成功（退出码 $code）"
  fi
  "$HOME/.local/bin/claude" --version || fail "Claude Code 装上了但运行不起来"
  ;;

*)
  fail "未知的命令 $cmd"
  ;;
esac
exit 0
