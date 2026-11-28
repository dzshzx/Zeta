# shellcheck shell=bash
# 本机的开发配置，由 tools/ 下的脚本 source。每一项先看环境变量，再看本仓的 git config（zeta.<键>），都没有就用默认值。
# git config 写在本仓的 .git/config 里，不进提交，各个 worktree 共用，设一次就行，例如：
#   git config zeta.phoneSsh my-phone     # 手机 Debian 的 ssh 别名（ZETA_DEVICE=phone 时要）
# 各项的含义和默认值见 AGENTS.md「本机配置」。
#
# 用法：zeta_config <环境变量名> <git config 键> [默认值]，打印取到的值（可能是空的）。
zeta_config() {
  local value=${!1:-}
  [[ -n $value ]] || value=$(git -C "$(dirname "${BASH_SOURCE[0]}")" config --get "zeta.$2" 2>/dev/null || true)
  printf '%s' "${value:-${3:-}}"
}

# 手机 Debian 的 ssh 别名；没配时报错退出（只在真要连手机时调）。
zeta_phone_ssh() {
  local alias
  alias=$(zeta_config ZETA_PHONE_SSH phoneSsh)
  [[ -n $alias ]] || { echo "连手机要知道它 Debian 的 ssh 别名：设 ZETA_PHONE_SSH，或 git config zeta.phoneSsh <别名>" >&2; exit 2; }
  printf '%s' "$alias"
}
