#!/system/bin/sh
# shellcheck disable=SC2016
# Zeta 安装 Debian 环境时在 Android 一侧执行的部分（root，管理器自带的 BusyBox ash）。
# 做法照 Eta：下载 proot-distro 发布的固定版本 Debian rootfs，校验后解压，写好 DNS 和 apt 源。
# 之后的步骤（装工具、建用户、装 Claude Code）在 Debian 里面做，见 zeta_setup_debian.sh。
#
#   status   <目录>                         这个目录里的环境装到哪一步了
#   check    <目录>                         安装前的检查
#   download <目录> <sha256> <字节数> <地址…>  下载 rootfs 压缩包（逐个地址尝试）
#   extract  <目录> <sha256> <版本>          解压并做基础配置
#
# 参数：$1 BusyBox，$2 命令，$3 环境目录（rootfs 在它下面的 rootfs/）。
# 以 "@zeta " 开头的输出行是给应用看的：progress <已完成> <总量>、error <原因>；其余是日志。
set -u
BB=$1
cmd=$2
BASE=$3
shift 3
R="$BASE/rootfs"
ARCHIVE="$BASE/download/rootfs.tar.xz"

say() { echo "@zeta $*"; }
fail() { say "error $*"; exit 1; }
sha_of() { "$BB" sha256sum "$1" 2>/dev/null | "$BB" awk '{print $1}'; }

case "$cmd" in
status)
  # 只认通用的迹象，不认 Zeta 自己的标记：不是安装向导解压出来的环境（自己放到这个位置的）也要认得
  [ -x "$R/bin/bash" ] && [ -e "$R/usr/bin/setpriv" ] || { echo "base=0"; exit 0; }
  echo "base=1"
  [ -e "$R/usr/bin/sudo" ] && echo "tools=1" || echo "tools=0"
  while IFS=: read -r name _ uid gid _ home _; do
    [ "$uid" -ge 1000 ] 2>/dev/null && [ "$uid" -lt 60000 ] || continue
    echo "user=$name:$uid:$gid:$home"
    { [ -e "$R$home/.local/bin/claude" ] || [ -L "$R$home/.local/bin/claude" ]; } && echo "claude=1" || echo "claude=0"
    { [ -e "$R$home/.local/bin/agy" ] || [ -L "$R$home/.local/bin/agy" ] || [ -x "$R/usr/local/bin/agy" ] || [ -x "$R/usr/bin/agy" ]; } && echo "agy=1" || echo "agy=0"
    break
  done < "$R/etc/passwd"
  ;;

check)
  [ "$(id -u)" = 0 ] || fail "没有 root 权限"
  for applet in awk chroot df mount nsenter sha256sum stat tar unshare xz; do
    "$BB" --list | "$BB" grep -qx "$applet" || fail "root 管理器自带的 BusyBox 缺少 $applet"
  done
  [ -x /system/bin/curl ] || "$BB" --list | "$BB" grep -qx wget || fail "系统里没有可用的下载工具（curl 或 wget）"
  "$BB" unshare -m --propagation private "$BB" chroot / /system/bin/sh -c ':' || fail "这台设备上建不了独立的挂载环境"
  [ -e "$R/bin/bash" ] || {
    "$BB" mkdir -p "$BASE" || fail "建不了目录 $BASE"
    free=$("$BB" df -k "$BASE" | "$BB" awk 'NR==2 {print $4}')
    [ "${free:-0}" -ge 3145728 ] || fail "存储空间不足：至少需要 3 GB 可用空间"
  }
  ;;

download)
  sha=$1
  size=$2
  shift 2
  "$BB" mkdir -p "$BASE/download" || fail "建不了目录 $BASE/download"
  if [ -f "$ARCHIVE" ] && [ "$(sha_of "$ARCHIVE")" = "$sha" ]; then
    say "progress $size $size"
    exit 0
  fi
  for url in "$@"; do
    "$BB" rm -f "$ARCHIVE"
    echo "下载 $url"
    if [ -x /system/bin/curl ]; then
      /system/bin/curl -fsSL --connect-timeout 20 --max-time 1200 --retry 1 -o "$ARCHIVE" "$url" &
    else
      "$BB" wget -q -T 30 -O "$ARCHIVE" "$url" &
    fi
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
      say "progress $("$BB" stat -c %s "$ARCHIVE" 2>/dev/null || echo 0) $size"
      "$BB" sleep 1
    done
    if wait "$pid" && [ "$(sha_of "$ARCHIVE")" = "$sha" ]; then
      say "progress $size $size"
      exit 0
    fi
    echo "这个地址没有成功"
  done
  "$BB" rm -f "$ARCHIVE"
  fail "下载失败，或者下到的文件校验不通过。请检查网络后重试"
  ;;

extract)
  sha=$1
  version=$2
  [ -e "$R/bin/bash" ] && fail "$R 里已经有一个环境，不会覆盖它"
  [ "$(sha_of "$ARCHIVE")" = "$sha" ] || fail "压缩包校验不通过，请重新下载"
  T="$BASE/rootfs.installing"
  "$BB" rm -rf "$T"
  "$BB" mkdir -p "$T" || fail "建不了目录 $T"
  "$BB" tar -xJf "$ARCHIVE" -C "$T" --strip-components=1 || { "$BB" rm -rf "$T"; fail "解压失败，可能是存储空间不足"; }
  [ -x "$T/bin/bash" ] || { "$BB" rm -rf "$T"; fail "压缩包里不是一个完整的 Debian 系统"; }
  "$BB" mkdir -p "$T/proc" "$T/sys" "$T/dev" "$T/workspace" "$T/storage/emulated/0" "$T/data/local/tmp" "$T/tmp" \
    "$T/etc/apt/apt.conf.d" "$T/usr/local/bin"
  "$BB" chmod 1777 "$T/tmp"
  "$BB" rm -f "$T/sdcard"
  "$BB" ln -s /storage/emulated/0 "$T/sdcard"
  # Android 没有 /etc/resolv.conf 可继承，写固定的 DNS
  "$BB" rm -f "$T/etc/resolv.conf"
  printf '%s\n' 'nameserver 223.5.5.5' 'nameserver 119.29.29.29' 'nameserver 1.1.1.1' > "$T/etc/resolv.conf"
  printf '%s\n' 'Acquire::Retries "2";' 'Acquire::http::Pipeline-Depth "0";' 'Acquire::https::Pipeline-Depth "0";' \
    > "$T/etc/apt/apt.conf.d/99zeta-network"
  printf '%s\n' \
    'deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie main' \
    'deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie-updates main' \
    'deb https://security.debian.org/debian-security trixie-security main' > "$T/etc/apt/sources.list"
  "$BB" rm -f "$T/etc/apt/sources.list.d/"*.sources 2>/dev/null
  # 主机名沿用 Android 的；不写进 hosts 的话 sudo 每次都会抱怨解析不了
  host=$("$BB" hostname 2>/dev/null)
  [ "$host" = localhost ] && host=
  printf '%s\n' "127.0.0.1	localhost${host:+ $host}" '::1		localhost ip6-localhost ip6-loopback' > "$T/etc/hosts"
  printf '%s\n' "version=$version" 'distribution=debian' "sha256=$sha" > "$T/.zeta-environment"
  "$BB" chmod 0644 "$T/.zeta-environment" "$T/etc/resolv.conf" "$T/etc/hosts"
  "$BB" mv "$T" "$R" || fail "放不到 $R"
  "$BB" rm -rf "$BASE/download"
  ;;

*)
  fail "未知的命令 $cmd"
  ;;
esac
exit 0
