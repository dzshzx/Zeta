# Zeta 安装 Debian 环境时在 Debian 里面执行的部分（bash）。前半段见 zeta_setup_android.sh。
#
#   tools          （root）装 Claude Code 干活要用的常用工具，包清单照 Eta
#   user <用户名>  （root）建一个普通用户，Claude Code 以它的身份运行
#   claude         （那个用户）用官方安装脚本装 Claude Code
#   python         （那个用户）可选：装 uv 和它管理的最新 Python，并设为这个用户的 python3
#   node           （root）可选：装官方发布的最新 LTS 版 Node.js（带 npm）
#   go             （root）可选：装官方发布的最新稳定版 Go
#   apk            （root）可选：APK 分析工具 jadx、apktool、smali/baksmali；没有 Java 时装 OpenJDK
#   remove <go|node|apk>（root）、remove python（那个用户）  卸载上面装的可选工具
#   owners         （任何用户）三样可选工具是不是 Zeta 装的：owner_<名字>=zeta|other
#
# 可选工具的归属：Zeta 装的时候留一个记号（go、node、apk 在 /var/lib/zeta/tools，python 在用户的
# ~/.local/state/zeta/tools），而且现在生效的还得是它装的那一份，才算 Zeta 的。
# 别处来的（Eta、手动、Homebrew…）不会被覆盖、更新或卸载。
#   ssh-setup  <用户名>            （root）装 openssh-server，没有现成配置时写一份只听本机、只认公钥的
#   ssh-status <用户名>            （root）sshd 实际生效的端口、监听地址，已授权的公钥数
#   ssh-addkey <用户名> <公钥>      （root）把一条公钥加进那个用户的 authorized_keys
#
# 以 "@zeta " 开头的输出行是给应用看的：error <原因>；其余是日志。
set -u
cmd=$1
shift

say() { echo "@zeta $*"; }
fail() { say "error $*"; exit 1; }

# Eta 的包清单，加上 Zeta 自己要用的：sudo、tzdata
PACKAGES="bash ca-certificates coreutils curl diffutils file findutils gawk git grep gzip jq less openssl openssh-client
  patch procps ripgrep rsync sed sqlite3 tar unzip util-linux wget xz-utils zip zstd fd-find sudo tzdata"
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

MARKS=/var/lib/zeta/tools
APK=/opt/zeta/apk-analysis
APK_COMMANDS="apktool jadx smali baksmali"
USER_MARKS="$HOME/.local/state/zeta/tools"

owned() {
  case "$1" in
    go) [ -f "$MARKS/go" ] && [ "$(readlink /usr/local/bin/go 2>/dev/null)" = /usr/local/go/bin/go ] ;;
    node) [ -f "$MARKS/node" ] && [ "$(readlink /usr/local/bin/node 2>/dev/null)" = /usr/local/node/bin/node ] ;;
    python) [ -f "$USER_MARKS/python" ] && [ -x "$HOME/.local/bin/uv" ] ;;
    apk) [ -f "$MARKS/apk" ] && [ "$(readlink /usr/local/bin/apktool 2>/dev/null)" = "$APK/current/bin/apktool" ] ;;
    *) return 1 ;;
  esac
}

# 这些路径上已经有东西，而这样工具不是 Zeta 装的：打印第一个，不动它
foreign() {
  tool=$1
  shift
  owned "$tool" && return 1
  for path in "$@"; do
    { [ -e "$path" ] || [ -L "$path" ]; } && { echo "$path"; return 0; }
  done
  return 1
}

mark() {
  mkdir -p "$1" && printf '%s\n' "installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$3" > "$1/$2"
}

case "$cmd" in
owners)
  for tool in go node python apk; do
    if owned "$tool"; then echo "owner_$tool=zeta"; else echo "owner_$tool=other"; fi
  done
  ;;

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

python)
  [ "$(id -u)" != 0 ] || fail "这一步要以普通用户执行"
  export PATH="$HOME/.local/bin:$PATH"
  path=$(foreign python "$HOME/.local/bin/uv" "$HOME/.local/bin/uvx") && fail "已经有一份 uv（$path），不是 Zeta 装的，没有动它"
  other=$(command -v uv 2>/dev/null)
  [ -n "$other" ] && [ "$other" != "$HOME/.local/bin/uv" ] && fail "已经有一份 uv（$other），不是 Zeta 装的，没有动它"
  if owned python; then
    uv self update || echo "uv 自身没有更新成功，继续"
  else
    curl -LsSf --connect-timeout 20 --retry 2 https://astral.sh/uv/install.sh -o /tmp/zeta-uv-install.sh \
      || fail "下载 uv 安装脚本失败，请检查网络后重试"
    # ~/.local/bin 本来就在登录 shell 的 PATH 里，不让它去改 .bashrc / .profile
    UV_NO_MODIFY_PATH=1 sh /tmp/zeta-uv-install.sh
    code=$?
    rm -f /tmp/zeta-uv-install.sh
    [ $code = 0 ] || fail "uv 安装脚本没有成功（退出码 $code）"
  fi
  mark "$USER_MARKS" python "uv=$HOME/.local/bin/uv"
  # --default：同时在 ~/.local/bin 放上 python、python3，登录 shell 里的 python3 就是它（带 pip）
  uv python install --default || fail "uv 装上了，但下载 Python 没有成功，请检查网络后重试"
  uv --version
  ;;

node)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  path=$(foreign node /usr/local/node /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack) \
    && fail "已经有一份 Node.js（$path），不是 Zeta 装的，没有动它"
  case "$(dpkg --print-architecture)" in
    arm64) arch=arm64 ;;
    amd64) arch=x64 ;;
    *) fail "暂不支持这个架构：$(dpkg --print-architecture)" ;;
  esac
  # 官方发布的最新 LTS；国内镜像在前
  version=
  for base in https://npmmirror.com/mirrors/node https://nodejs.org/dist; do
    version=$(curl -fsSL --connect-timeout 15 --max-time 40 "$base/index.json" 2>/dev/null | jq -r 'map(select(.lts != false))[0].version' 2>/dev/null)
    case "$version" in v[0-9]*) break ;; esac
    version=
  done
  [ -n "$version" ] || fail "查不到 Node.js 的最新 LTS 版本，请检查网络后重试"
  file="node-$version-linux-$arch.tar.xz"
  if [ "$(/usr/local/node/bin/node --version 2>/dev/null)" != "$version" ]; then
    got=
    for base in https://npmmirror.com/mirrors/node https://nodejs.org/dist; do
      sum=$(curl -fsSL --connect-timeout 15 --max-time 40 "$base/$version/SHASUMS256.txt" 2>/dev/null | awk -v f="$file" '$2 == f {print $1}')
      [ -n "$sum" ] || continue
      echo "下载 $base/$version/$file"
      rm -f /tmp/zeta-node.tar.xz
      curl -fsSL --connect-timeout 20 --max-time 1200 --retry 1 -o /tmp/zeta-node.tar.xz "$base/$version/$file" || continue
      if [ "$(sha256sum /tmp/zeta-node.tar.xz | cut -d' ' -f1)" = "$sum" ]; then got=1; break; fi
      echo "这个地址下到的文件校验不通过"
    done
    [ -n "$got" ] || { rm -f /tmp/zeta-node.tar.xz; fail "下载 Node.js 失败，请检查网络后重试"; }
    echo "解压 Node.js $version"
    rm -rf /usr/local/node.installing
    mkdir -p /usr/local/node.installing
    tar -xJf /tmp/zeta-node.tar.xz -C /usr/local/node.installing --strip-components=1 || { rm -rf /usr/local/node.installing /tmp/zeta-node.tar.xz; fail "解压失败，可能是存储空间不足"; }
    rm -rf /usr/local/node
    mv /usr/local/node.installing /usr/local/node
    rm -f /tmp/zeta-node.tar.xz
  fi
  for tool in node npm npx corepack; do
    [ -e "/usr/local/node/bin/$tool" ] && ln -sfn "/usr/local/node/bin/$tool" "/usr/local/bin/$tool"
  done
  mark "$MARKS" node "version=$version"
  # 官方的二进制在 arm64 上要 libatomic
  if ! /usr/local/bin/node --version >/dev/null 2>&1; then
    [ -x /usr/local/bin/zeta-apt ] || write_apt_helper
    /usr/local/bin/zeta-apt install libatomic1
  fi
  # npm install -g 装到自己的家目录里，不用 sudo
  cat > /etc/profile.d/zeta-node.sh <<'ZETA_NODE_EOF'
: "${NPM_CONFIG_PREFIX:=$HOME/.npm-global}"
export NPM_CONFIG_PREFIX
case ":$PATH:" in *":$NPM_CONFIG_PREFIX/bin:"*) ;; *) PATH="$PATH:$NPM_CONFIG_PREFIX/bin" ;; esac
export PATH
ZETA_NODE_EOF
  /usr/local/bin/node --version || fail "Node.js 装上了但运行不起来"
  ;;

go)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  path=$(foreign go /usr/local/go /usr/local/bin/go /usr/local/bin/gofmt) && fail "已经有一份 Go（$path），不是 Zeta 装的，没有动它"
  case "$(dpkg --print-architecture)" in
    arm64) arch=arm64 ;;
    amd64) arch=amd64 ;;
    *) fail "暂不支持这个架构：$(dpkg --print-architecture)" ;;
  esac
  # 发布清单里有最新稳定版的文件名和校验值；国内镜像在前
  info=
  for base in https://golang.google.cn https://go.dev; do
    info=$(curl -fsSL --connect-timeout 15 --max-time 40 "$base/dl/?mode=json" 2>/dev/null | jq -r --arg a "$arch" \
      'map(select(.stable))[0] | .version as $v | .files[] | select(.os=="linux" and .arch==$a and .kind=="archive") | "\($v) \(.filename) \(.sha256)"' 2>/dev/null)
    [ -n "$info" ] && break
  done
  [ -n "$info" ] || fail "查不到 Go 的最新版本，请检查网络后重试"
  set -- $info
  version=$1; file=$2; sum=$3
  if [ "$(/usr/local/go/bin/go env GOVERSION 2>/dev/null)" != "$version" ]; then
    got=
    for base in https://golang.google.cn/dl https://mirrors.aliyun.com/golang https://go.dev/dl; do
      echo "下载 $base/$file"
      rm -f /tmp/zeta-go.tar.gz
      curl -fsSL --connect-timeout 20 --max-time 1200 --retry 1 -o /tmp/zeta-go.tar.gz "$base/$file" || continue
      if [ "$(sha256sum /tmp/zeta-go.tar.gz | cut -d' ' -f1)" = "$sum" ]; then got=1; break; fi
      echo "这个地址下到的文件校验不通过"
    done
    [ -n "$got" ] || { rm -f /tmp/zeta-go.tar.gz; fail "下载 Go 失败，请检查网络后重试"; }
    echo "解压 $version"
    rm -rf /usr/local/go.installing
    mkdir -p /usr/local/go.installing
    tar -xzf /tmp/zeta-go.tar.gz -C /usr/local/go.installing --strip-components=1 || { rm -rf /usr/local/go.installing /tmp/zeta-go.tar.gz; fail "解压失败，可能是存储空间不足"; }
    rm -rf /usr/local/go
    mv /usr/local/go.installing /usr/local/go
    rm -f /tmp/zeta-go.tar.gz
  fi
  ln -sfn /usr/local/go/bin/go /usr/local/bin/go
  ln -sfn /usr/local/go/bin/gofmt /usr/local/bin/gofmt
  mark "$MARKS" go "version=$version"
  # go install 装的程序在 ~/go/bin；模块代理默认用国内的（自己设过 GOPROXY 的不受影响）
  cat > /etc/profile.d/zeta-go.sh <<'ZETA_GO_EOF'
case ":$PATH:" in *":$HOME/go/bin:"*) ;; *) PATH="$PATH:$HOME/go/bin" ;; esac
export PATH
: "${GOPROXY:=https://goproxy.cn,direct}"
export GOPROXY
ZETA_GO_EOF
  /usr/local/bin/go version || fail "Go 装上了但运行不起来"
  ;;

apk)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  path=$(foreign apk "$APK" /usr/local/bin/apktool /usr/local/bin/jadx /usr/local/bin/smali /usr/local/bin/baksmali) \
    && fail "已经有一套 APK 分析工具（$path），不是 Zeta 装的，没有动它"
  # 各工具的最新发布：GitHub 的发布接口给出文件名、下载地址和 sha256。接口用不了时退回下面这组已知的版本。
  release() {
    curl -fsSL --connect-timeout 15 --max-time 40 "https://api.github.com/repos/$1/releases/latest" 2>/dev/null | jq -r --arg re "$2" '
      .tag_name as $tag | [.assets[] | select(.name | test($re)) | select(.digest != null)][0]
      | if . == null then empty else "\($tag) \(.name) \(.browser_download_url) \(.digest | sub("^sha256:"; ""))" end' 2>/dev/null
  }
  pick() {
    got=$(release "$1" "$2")
    [ -n "$got" ] && { echo "$got"; return; }
    echo "$3"
  }
  JADX=$(pick skylot/jadx '^jadx-[0-9.]+\.zip$' \
    'v1.5.6 jadx-1.5.6.zip https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip 545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974')
  APKTOOL=$(pick iBotPeaches/Apktool '^apktool_[0-9.]+\.jar$' \
    'v3.0.3 apktool_3.0.3.jar https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423')
  SMALI=$(pick baksmali/smali '^smali-[0-9.]+-fat-release\.jar$' \
    '3.0.10 smali-3.0.10-fat-release.jar https://github.com/baksmali/smali/releases/download/3.0.10/smali-3.0.10-fat-release.jar 32fa0e88a6c397b3922201adf5f3e534fbaed5a663c71d0c558c3ddce0af844a')
  BAKSMALI=$(pick baksmali/smali '^baksmali-[0-9.]+-fat-release\.jar$' \
    '3.0.10 baksmali-3.0.10-fat-release.jar https://github.com/baksmali/smali/releases/download/3.0.10/baksmali-3.0.10-fat-release.jar 37ae4a41a8886e15c20b8362fa4250f96bbdb55e1a608199ad8b5dff068b588f')
  T=/tmp/zeta-apk
  rm -rf "$T" && mkdir -p "$T" || fail "建不了临时目录"
  # 先直连 GitHub，不通再走 gh-proxy；下到的文件都要和 sha256 对得上
  fetch() {
    set -- $1
    for url in "$3" "https://gh-proxy.com/$3"; do
      echo "下载 $url"
      rm -f "$T/$2"
      curl -fsSL --connect-timeout 20 --max-time 1200 --retry 1 -o "$T/$2" "$url" || continue
      [ "$(sha256sum "$T/$2" | cut -d' ' -f1)" = "$4" ] && return 0
      echo "这个地址下到的文件校验不通过"
    done
    return 1
  }
  for item in "$JADX" "$APKTOOL" "$SMALI" "$BAKSMALI"; do
    fetch "$item" || { rm -rf "$T"; fail "下载 $(echo "$item" | cut -d' ' -f2) 失败，请检查网络后重试"; }
  done
  # Java：环境里已经有就用它（比如 Android 构建用的那份），没有才装，并记下装了哪些包，卸载时只删这些
  packages=
  if ! [ -x /usr/bin/java ]; then
    [ -x /usr/local/bin/zeta-apt ] || write_apt_helper || fail "写不了 /usr/local/bin/zeta-apt"
    before=$(dpkg-query -W -f '${Package}\n' | sort)
    /usr/local/bin/zeta-apt install openjdk-25-jdk-headless || /usr/local/bin/zeta-apt install default-jdk-headless \
      || { rm -rf "$T"; fail "安装 Java 失败，请检查网络后重试"; }
    packages=$(dpkg-query -W -f '${Package}\n' | sort | comm -13 <(printf '%s\n' "$before") - | tr '\n' ' ')
  fi
  echo "整理文件"
  N="$APK/current.installing"
  rm -rf "$N" && mkdir -p "$N/bin" "$N/lib" "$N/jadx" || fail "建不了 $N"
  unzip -q "$T/$(echo "$JADX" | cut -d' ' -f2)" -d "$N/jadx" || { rm -rf "$N" "$T"; fail "解压 jadx 失败"; }
  cp "$T/$(echo "$APKTOOL" | cut -d' ' -f2)" "$N/lib/apktool.jar"
  cp "$T/$(echo "$SMALI" | cut -d' ' -f2)" "$N/lib/smali.jar"
  cp "$T/$(echo "$BAKSMALI" | cut -d' ' -f2)" "$N/lib/baksmali.jar"
  rm -rf "$T"
  # 手机上没有 ARM64 的 aapt2，apktool 回编译要自己用 -a/--aapt 指一个能用的 aapt2；只解码、检查不受影响
  cat > "$N/bin/apktool" <<'ZETA_APKTOOL_EOF'
#!/bin/sh
case "${1:-}" in
  b|build)
    case " $* " in
      *" -a "*|*" --aapt "*|*" --aapt="*) ;;
      *) echo "apktool：这台手机上没有 ARM64 的 aapt2，回编译（b）要用 -a <aapt2 路径> 指定一个能用的；解码（d）等不受影响。" >&2; exit 64 ;;
    esac
    ;;
esac
exec java -jar /opt/zeta/apk-analysis/current/lib/apktool.jar "$@"
ZETA_APKTOOL_EOF
  printf '%s\n' '#!/bin/sh' 'exec java -jar /opt/zeta/apk-analysis/current/lib/smali.jar "$@"' > "$N/bin/smali"
  printf '%s\n' '#!/bin/sh' 'exec java -jar /opt/zeta/apk-analysis/current/lib/baksmali.jar "$@"' > "$N/bin/baksmali"
  ln -s ../jadx/bin/jadx "$N/bin/jadx"
  chown -R root:root "$N" && chmod -R u=rwX,go=rX "$N" && chmod 0755 "$N/bin/apktool" "$N/bin/smali" "$N/bin/baksmali" "$N/jadx/bin/jadx" "$N/jadx/bin/jadx-gui" 2>/dev/null
  # 换上新的；验证不过就退回原来那份
  rm -rf "$APK/previous"
  [ -d "$APK/current" ] && mv "$APK/current" "$APK/previous"
  mv "$N" "$APK/current" || fail "放不到 $APK/current"
  for tool in $APK_COMMANDS; do ln -sfn "$APK/current/bin/$tool" "/usr/local/bin/$tool"; done
  for tool in apktool jadx smali baksmali; do
    if ! out=$("/usr/local/bin/$tool" --version 2>&1 | head -n 1); then
      rm -rf "$APK/current"
      if [ -d "$APK/previous" ]; then mv "$APK/previous" "$APK/current"; else rm -f $(for t in $APK_COMMANDS; do echo "/usr/local/bin/$t"; done); fi
      fail "$tool 运行不起来：$out"
    fi
    echo "$tool $out"
  done
  rm -rf "$APK/previous"
  [ -f "$MARKS/apk" ] && [ -z "$packages" ] && packages=$(sed -n 's/^packages=//p' "$MARKS/apk")
  mark "$MARKS" apk "jadx=$(echo "$JADX" | cut -d' ' -f1 | sed 's/^v//')
apktool=$(echo "$APKTOOL" | cut -d' ' -f1 | sed 's/^v//')
smali=$(echo "$SMALI" | cut -d' ' -f1 | sed 's/^v//')
packages=$packages"
  ;;

remove)
  # 只动 Zeta 自己装的那一份（见文件开头的“归属”）
  case "${1:-}" in
  go)
    [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
    owned go || fail "这里的 Go 不是 Zeta 装的，没有动它"
    for tool in go gofmt; do
      [ "$(readlink "/usr/local/bin/$tool")" = "/usr/local/go/bin/$tool" ] && rm -f "/usr/local/bin/$tool"
    done
    rm -f /etc/profile.d/zeta-go.sh "$MARKS/go"
    rm -rf /usr/local/go /usr/local/go.installing
    echo "Go 已卸载（~/go 里下载过的模块和装过的程序没有动）"
    ;;
  node)
    [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
    owned node || fail "这里的 Node.js 不是 Zeta 装的，没有动它"
    for tool in node npm npx corepack; do
      [ "$(readlink "/usr/local/bin/$tool")" = "/usr/local/node/bin/$tool" ] && rm -f "/usr/local/bin/$tool"
    done
    rm -f /etc/profile.d/zeta-node.sh "$MARKS/node"
    rm -rf /usr/local/node /usr/local/node.installing
    echo "Node.js 已卸载（~/.npm-global 里装过的全局包没有动）"
    ;;
  python)
    [ "$(id -u)" != 0 ] || fail "这一步要以普通用户执行"
    export PATH="$HOME/.local/bin:$PATH"
    owned python || fail "这里的 uv 不是 Zeta 装的，没有动它"
    uv python uninstall --all
    cache=$(uv cache dir 2>/dev/null)
    uv cache clean >/dev/null 2>&1
    rm -rf "$(uv python dir 2>/dev/null)" "$(uv tool dir 2>/dev/null)"
    [ -n "$cache" ] && rm -rf "$cache"
    rm -f "$HOME/.local/bin/uv" "$HOME/.local/bin/uvx" "$HOME/.config/uv/uv-receipt.json"
    rmdir "$HOME/.config/uv" "$HOME/.local/share/uv" 2>/dev/null
    rm -f "$USER_MARKS/python"
    echo "uv 和它管理的 Python 已卸载（系统自带的 python3 不受影响）"
    ;;
  apk)
    [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
    owned apk || fail "这里的 APK 分析工具不是 Zeta 装的，没有动它"
    packages=$(sed -n 's/^packages=//p' "$MARKS/apk")
    for tool in $APK_COMMANDS; do
      [ "$(readlink "/usr/local/bin/$tool")" = "$APK/current/bin/$tool" ] && rm -f "/usr/local/bin/$tool"
    done
    rm -rf "$APK"
    rmdir /opt/zeta 2>/dev/null
    if [ -n "$packages" ]; then
      echo "卸载 Zeta 当初为它装的 Java：$packages"
      # shellcheck disable=SC2086
      DEBIAN_FRONTEND=noninteractive apt-get purge -y $packages || echo "Java 没有卸干净，可以稍后用“修复”再处理"
    fi
    rm -f "$MARKS/apk"
    echo "APK 分析工具已卸载"
    ;;
  *) fail "不认识的可选工具：${1:-}" ;;
  esac
  ;;

ssh-setup)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  name=$1
  home=$(getent passwd "$name" | cut -d: -f6)
  [ -d "$home" ] || fail "没有用户 $name"
  if ! [ -x /usr/sbin/sshd ]; then
    # 先写好配置，再装包：只在本机监听，只认公钥，只让这个用户登录
    mkdir -p /etc/ssh/sshd_config.d
    if [ -z "$(ls /etc/ssh/sshd_config.d/*.conf 2>/dev/null)" ]; then
      printf '%s\n' '# 由 Zeta 写入：只在本机监听。远程访问经 Tailscale 之类转到这个端口。' \
        'Port 8022' 'ListenAddress 127.0.0.1' 'PermitRootLogin no' 'PasswordAuthentication no' \
        'KbdInteractiveAuthentication no' 'PubkeyAuthentication yes' "AllowUsers $name" 'X11Forwarding no' \
        > /etc/ssh/sshd_config.d/10-zeta.conf
    fi
    # 安装脚本会想顺手把服务启动起来；这里没有 init，拦住它，由 Zeta 来启动
    policy=
    if ! [ -e /usr/sbin/policy-rc.d ]; then
      printf '%s\n' '#!/bin/sh' 'exit 101' > /usr/sbin/policy-rc.d && chmod 0755 /usr/sbin/policy-rc.d && policy=1
    fi
    [ -x /usr/local/bin/zeta-apt ] || write_apt_helper || fail "写不了 /usr/local/bin/zeta-apt"
    /usr/local/bin/zeta-apt install openssh-server
    code=$?
    [ -n "$policy" ] && rm -f /usr/sbin/policy-rc.d
    [ $code = 72 ] && fail "软件包数据库有问题，自动修复没有成功"
    [ $code = 0 ] || fail "安装 openssh-server 失败，请检查网络后重试"
  fi
  ssh-keygen -A >/dev/null || fail "生成不了主机密钥"
  install -d -m 0700 -o "$name" -g "$name" "$home/.ssh"
  [ -e "$home/.ssh/authorized_keys" ] || install -m 0600 -o "$name" -g "$name" /dev/null "$home/.ssh/authorized_keys"
  mkdir -p /run/sshd
  /usr/sbin/sshd -t || fail "sshd 的配置有问题，见上面的输出"
  echo "SSH 服务已就绪"
  ;;

ssh-status)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  [ -x /usr/sbin/sshd ] || { echo "installed=0"; exit 0; }
  echo "installed=1"
  mkdir -p /run/sshd
  /usr/sbin/sshd -T 2>/dev/null | awk '
    $1 == "port" && !p { print "port=" $2; p = 1 }
    $1 == "listenaddress" && !l { print "listen=" $2; l = 1 }
    $1 == "passwordauthentication" { print "password=" $2 }'
  home=$(getent passwd "$1" | cut -d: -f6)
  count=$(grep -cE '^[^#[:space:]]' "$home/.ssh/authorized_keys" 2>/dev/null)
  echo "keys=${count:-0}"
  ;;

ssh-addkey)
  [ "$(id -u)" = 0 ] || fail "这一步要以 root 执行"
  name=$1
  key=$2
  home=$(getent passwd "$name" | cut -d: -f6)
  [ -d "$home" ] || fail "没有用户 $name"
  tmp=$(mktemp) || fail "建不了临时文件"
  printf '%s\n' "$key" > "$tmp"
  if ! ssh-keygen -l -f "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    fail "这不是一条有效的 SSH 公钥（应该是 ssh-ed25519 AAAA… 这样的一行）"
  fi
  rm -f "$tmp"
  install -d -m 0700 -o "$name" -g "$name" "$home/.ssh"
  file="$home/.ssh/authorized_keys"
  [ -e "$file" ] || install -m 0600 -o "$name" -g "$name" /dev/null "$file"
  body=$(printf '%s\n' "$key" | awk '{print $1 " " $2}')
  if grep -qF "$body" "$file"; then
    echo "这条公钥已经在里面了"
  else
    # 原文件末尾没有换行的话先补上，免得两条公钥连成一行
    [ -s "$file" ] && [ -n "$(tail -c 1 "$file")" ] && echo >> "$file"
    printf '%s\n' "$key" >> "$file"
    echo "已添加"
  fi
  ;;

*)
  fail "未知的命令 $cmd"
  ;;
esac
exit 0
