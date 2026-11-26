# Zeta 自带的 Debian 存在磁盘镜像里时，Android 一侧的部分（root，管理器自带的 BusyBox ash）。
#
# 为什么用镜像：Android 每次开机在 post-fs-data 阶段对 /data 做一遍 restorecon，而装了 KernelSU 模块的设备上总有改不了标签的文件，
# 它就永远不给目录记摘要、每次都全量重扫。Debian 有二十多万个文件，放成普通目录时开机要多等二十来秒；放进一个 ext4 镜像文件里，
# 开机时它只是一个文件。镜像是稀疏文件，用多少占多少；删掉的文件经 fstrim 还给手机。
#
# 镜像在 /data/local/zeta/debian/rootfs.img，挂在全局挂载命名空间的 rootfs/ 上（挂载会传到各个应用的命名空间里），
# 所以 Debian 里、Android 一侧看到的路径都和普通目录一样。Zeta 把这个脚本写到 /data/local/zeta/image.sh，
# 进 Debian 前（LinuxEnv、debian.sh）和开机脚本 89-zeta-image.sh 都经它挂上镜像。
#
#   status                   镜像的状态（每行 键=值）
#   mount                    还没挂上就先检查（e2fsck -p）再挂上
#   boot                     开机时：mount，写好开机脚本，再 fstrim
#   trim                     fstrim：把 Debian 里删掉的文件占的空间还给手机
#   start <操作> [参数]       在后台做一项维护，进度写进状态：
#       convert <GB>         把普通目录的环境转成镜像（上限 GB），原目录改名为 rootfs.old 留作回退
#       check                完整检查并修复文件系统（e2fsck -f -y）
#       resize <GB>          改镜像的上限
#       delete-old           删掉转换前留下的 rootfs.old
#     convert、check、resize 要先停掉 Debian 里的全部程序，做完再执行一遍 /data/adb/service.d 里启动这个环境的开机脚本。
#
# 参数：$1 BusyBox，$2 命令，后面是命令的参数。
# 以 "@zeta " 开头的输出行是给应用看的：error <原因>；其余是日志。
set -u
# 只在 BusyBox 的 ash 下跑：Android 的 /system/bin/sh（mksh）给 exec 打开的 3 号以上描述符设了执行时关闭，flock 就拿不到 9 号锁。
# 从文件起（开机脚本、debian.sh）时换过去；Zeta 自己用 "$BB" sh 起
[ -f "$0" ] && [ -z "${ZETA_IMAGE_ASH:-}" ] && ZETA_IMAGE_ASH=1 exec "$1" sh "$0" "$@"
BB=$1
cmd=$2
shift 2

BASE=/data/local/zeta/debian
R=$BASE/rootfs
IMG=$BASE/rootfs.img
PART=$BASE/rootfs.img.part
OLD=$BASE/rootfs.old
SELF=/data/local/zeta/image.sh
LOCK=$BASE/.image.lock
STATE=$BASE/image.state
LOG=$BASE/image.log
BOOT=/data/adb/service.d/89-zeta-image.sh
# 转换时新镜像先挂在 rootfs 里面这个位置，好在 Debian 里用它自己的 tar 拷过去（打包时 --one-file-system 跳过它）
NEW_IN=/.zeta-image-new
NEW=$R$NEW_IN
LABEL=u:object_r:system_data_file:s0
GB=1073741824
OP=
ON_FAIL=

say() { echo "@zeta $*"; }
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# 做维护时出错：先记进状态，再按 ON_FAIL 收拾（恢复挂载、拉起服务），最后报错退出
fail() {
  log "失败：$*"
  if [ -n "$OP" ]; then
    hook=$ON_FAIL
    ON_FAIL=
    [ -n "$hook" ] && "$hook"
    state error "$*"
  fi
  say "error $*"
  exit 1
}

# 在全局挂载命名空间里执行
g() { "$BB" nsenter -t 1 -m -- "$@"; }

mounted() { "$BB" grep -q " $1 " /proc/1/mounts; }

# 状态文件：哪项维护、做到哪一步。started 是这项维护开始的时刻（start 记下、经环境变量传给后台进程），
# step 是这一步开始的时刻，界面拿它们显示已用时间、算复制速度
state() {
  now=$(date +%s)
  STARTED=${STARTED:-$now}
  [ "$2" = "${STEP_MSG:-}" ] || {
    STEP_MSG=$2
    STEP_AT=$now
  }
  {
    echo "op=$OP"
    echo "phase=$1"
    echo "msg=$2"
    echo "progress=${3:-}"
    echo "time=$now"
    echo "started=$STARTED"
    echo "step=$STEP_AT"
  } > "$STATE.new" && "$BB" mv "$STATE.new" "$STATE"
}

# 维护时拿着的锁（fd 9）。挂载时短暂地拿；做维护的整段都拿着，期间别处挂不上，免得和 e2fsck、resize2fs 同时动镜像
lock_now() {
  "$BB" mkdir -p "$BASE" || fail "建不了目录 $BASE"
  exec 9> "$LOCK"
  "$BB" flock -n 9
}

running_op() { [ -f "$STATE" ] && "$BB" grep -qx 'phase=running' "$STATE"; }

# 等锁：正在做维护时马上失败，否则（比如开机脚本和 Zeta 同时来挂）最多等 30 秒
lock_wait() {
  n=0
  until lock_now; do
    running_op && fail "正在维护 Debian 的磁盘镜像，请等它做完"
    n=$((n + 1))
    [ $n -lt 60 ] || fail "等不到磁盘镜像的锁"
    "$BB" usleep 500000
  done
}

check_log_size() {
  [ "$("$BB" stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ] && : > "$LOG"
  return 0
}

do_mount() {
  mounted "$R" && return 0
  [ -f "$IMG" ] || fail "没有磁盘镜像 $IMG"
  check_log_size
  log "检查 $IMG"
  /system/bin/e2fsck -p "$IMG" >> "$LOG" 2>&1
  rc=$?
  # 0 没问题，1、2 发现并修好了；4 起是自动修不了的
  [ $rc -lt 4 ] || fail "磁盘镜像里有自动修不了的错误（e2fsck 退出码 $rc），请在 Zeta 的「磁盘镜像」里做一次「检查并修复」"
  "$BB" mkdir -p "$R"
  g "$BB" mount -t ext4 -o loop,noatime "$IMG" "$R" 9>&- || fail "挂不上磁盘镜像"
  log "已挂上"
}

write_boot() {
  # root 管理器（KernelSU、Magisk、APatch）开机都执行这个目录里的脚本，只是不一定建好了；没有开机脚本，重启后要等 Zeta 来挂镜像
  "$BB" mkdir -p /data/adb/service.d || fail "建不了 /data/adb/service.d，开机挂不上镜像"
  {
    echo '#!/system/bin/sh'
    echo '# 由 Zeta 生成（设置 → Linux 环境 → 磁盘镜像）。开机时检查并挂上 Debian 的磁盘镜像，再把删掉的文件占的空间还给手机。'
    echo 'for BB in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do [ -x "$BB" ] && break; done'
    echo "[ -f $SELF ] && [ -f $IMG ] && \"\$BB\" sh $SELF \"\$BB\" boot > /dev/null 2>&1 &"
  } > "$BOOT.new" && "$BB" chmod 0755 "$BOOT.new" && "$BB" mv "$BOOT.new" "$BOOT"
}

trim() {
  out=$(g "$BB" fstrim -v "$R" 9>&- 2>&1) || {
    log "fstrim 失败：$out"
    return 1
  }
  log "$out"
  echo "$out" | "$BB" awk '{for (i = 1; i < NF; i++) if ($(i + 1) == "bytes") print "trimmed=" $i}'
}

# 根目录在这个环境里的进程（Debian 里的全部程序）。各个环境和 Android 共用一个进程表，看 /proc/<pid>/root
debian_pids() {
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = $$ ] && continue
    r=$("$BB" readlink "$p/root" 2>/dev/null) || continue
    case "$r" in
    "$R" | "$R"/*) echo "$pid" ;;
    esac
  done
}

wait_no_debian() {
  n=0
  while [ $n -lt "$1" ]; do
    [ -z "$(debian_pids)" ] && return 0
    "$BB" usleep 250000
    n=$((n + 1))
  done
  return 1
}

stop_debian() {
  pids=$(debian_pids)
  [ -z "$pids" ] && return 0
  log "停止 Debian 里的程序：$(echo $pids)"
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null
  wait_no_debian 40 && return 0
  # shellcheck disable=SC2086
  kill -9 $(debian_pids) 2>/dev/null
  wait_no_debian 20 || fail "Debian 里有程序停不下来"
}

# 卸下镜像，并等 loop 设备放开：放开了才说明没有别的挂载命名空间还挂着它，这时才能动镜像文件
unmount_image() {
  if mounted "$R"; then
    g "$BB" umount "$R" 9>&- || fail "卸不下磁盘镜像：Android 一侧还有程序在用 $R 里的文件"
  fi
  n=0
  while "$BB" losetup -a 2>/dev/null | "$BB" grep -qF "$IMG"; do
    n=$((n + 1))
    [ $n -lt 40 ] || fail "磁盘镜像还被别处挂着"
    "$BB" usleep 250000
  done
}

# 重新执行 service.d 里启动这个环境的开机脚本（SSH 服务等）。它们自己会等环境就绪、在后台跑
restart_services() {
  for f in /data/adb/service.d/*.sh; do
    [ -f "$f" ] && [ "$f" != "$BOOT" ] || continue
    "$BB" grep -qF "$R" "$f" 2>/dev/null || continue
    log "执行 $f"
    "$BB" setsid sh "$f" < /dev/null > /dev/null 2>&1 9>&- &
  done
}

# 维护失败时：镜像能挂就挂回去，服务照样拉起
recover() {
  (do_mount) > /dev/null 2>&1
  restart_services
}

size_gb() {
  case "$1" in
  '' | *[!0-9]*) fail "大小要是整数 GB" ;;
  esac
  [ "$1" -ge 1 ] && [ "$1" -le 4096 ] || fail "大小要在 1 到 4096 GB 之间"
}

op_check() {
  [ -f "$IMG" ] || fail "没有磁盘镜像"
  ON_FAIL=recover
  state running stop
  stop_debian
  unmount_image
  state running check
  log "完整检查 $IMG"
  /system/bin/e2fsck -f -y "$IMG" >> "$LOG" 2>&1
  rc=$?
  [ $rc -lt 4 ] || fail "文件系统里有修不了的错误（e2fsck 退出码 $rc），详情见 $LOG"
  do_mount
  ON_FAIL=
  restart_services
  if [ $rc = 0 ]; then state ok clean; else state ok fixed; fi
}

op_resize() {
  size_gb "$1"
  [ -f "$IMG" ] || fail "没有磁盘镜像"
  want=$(($1 * GB))
  have=$("$BB" stat -c %s "$IMG")
  [ "$want" = "$have" ] && {
    state ok "$1"
    return 0
  }
  ON_FAIL=recover
  state running stop
  stop_debian
  unmount_image
  state running check
  /system/bin/e2fsck -f -y "$IMG" >> "$LOG" 2>&1
  rc=$?
  [ $rc -lt 4 ] || fail "文件系统里有修不了的错误（e2fsck 退出码 $rc），先做一次「检查并修复」"
  state running resize
  log "镜像上限 $have → $want 字节"
  if [ "$want" -gt "$have" ]; then
    "$BB" truncate -s "$want" "$IMG" || fail "放大不了镜像文件"
    /system/bin/resize2fs "$IMG" >> "$LOG" 2>&1 || fail "resize2fs 没能扩大文件系统，详情见 $LOG"
  else
    /system/bin/resize2fs "$IMG" "$1G" >> "$LOG" 2>&1 || fail "缩不到 $1 GB：Debian 里的文件放不下"
    "$BB" truncate -s "$want" "$IMG" || fail "缩小不了镜像文件"
  fi
  do_mount
  ON_FAIL=
  restart_services
  state ok "$1"
}

# 转换中途失败：新镜像丢掉，原目录没动过
convert_cleanup() {
  mounted "$NEW" && g "$BB" umount "$NEW" 9>&-
  "$BB" rmdir "$NEW" 2>/dev/null
  "$BB" rm -f "$PART"
  restart_services
}

# 原目录已经改名以后失败：改回去
convert_rollback() {
  mounted "$R" && g "$BB" umount "$R" 9>&-
  "$BB" rmdir "$R" 2>/dev/null && "$BB" mv "$OLD" "$R" && "$BB" mv "$IMG" "$PART" && "$BB" rm -f "$PART"
  restart_services
}

op_convert() {
  size_gb "$1"
  [ -f "$IMG" ] && fail "已经是磁盘镜像了"
  mounted "$R" && fail "$R 上已经挂着别的东西"
  [ -x "$R/bin/bash" ] || fail "$R 里没有可以转换的环境"
  [ -x "$R/bin/tar" ] || fail "Debian 里没有 tar"
  [ -e "$OLD" ] && fail "上次转换留下的 $OLD 还在，先删掉它"
  "$BB" rm -f "$PART"
  ON_FAIL=restart_services
  state running stop
  stop_debian
  state running measure
  need=$("$BB" du -sxk "$R" | "$BB" awk '{print $1}')
  free=$("$BB" df -kP "$BASE" | "$BB" awk 'NR == 2 {print $4}')
  log "Debian 占 $need KB，/data 剩 $free KB，镜像上限 $1 GB"
  [ $((need + 1048576)) -le $(($1 * 1048576)) ] || fail "镜像上限太小：Debian 现在占 $((need / 1048576 + 1)) GB"
  [ "$free" -ge $((need + 1048576)) ] || fail "存储空间不够：转换时要临时多占和 Debian 一样大的空间"
  ON_FAIL=convert_cleanup
  "$BB" truncate -s "$1G" "$PART" || fail "建不了镜像文件"
  /system/bin/mke2fs -q -F -t ext4 -m 0 -L zeta-debian "$PART" >> "$LOG" 2>&1 || fail "mke2fs 没能建好文件系统"
  "$BB" mkdir -p "$NEW"
  g "$BB" mount -t ext4 -o loop,noatime "$PART" "$NEW" 9>&- || fail "挂不上新镜像"
  # 镜像里的文件跟着上级目录的标签走，根目录标成和原来的环境一样
  "$BB" chcon "$LABEL" "$NEW" 2>/dev/null || chcon "$LABEL" "$NEW" || fail "改不了新镜像的标签"
  "$BB" chmod 0755 "$NEW"
  state running copy "0 $need"
  log "复制文件"
  # 用 Debian 自己的 GNU tar：保留硬链接、属主、权限和文件能力（security.capability）；SELinux 标签不拷，随上级目录
  (
    set -o pipefail
    g "$BB" chroot "$R" /bin/tar -C / --one-file-system --numeric-owner --xattrs --xattrs-include='*' \
      --xattrs-exclude=security.selinux --exclude=".$NEW_IN" -cpf - . 9>&- |
      g "$BB" chroot "$R" /bin/tar -C "$NEW_IN" --numeric-owner --xattrs --xattrs-include='*' \
        --xattrs-exclude=security.selinux -xpf - 9>&-
  ) >> "$LOG" 2>&1 &
  tar_pid=$!
  while kill -0 $tar_pid 2>/dev/null; do
    used=$("$BB" df -kP "$NEW" | "$BB" awk 'NR == 2 {print $3}')
    state running copy "${used:-0} $need"
    "$BB" sleep 2
  done
  wait $tar_pid || fail "复制没有成功，详情见 $LOG"
  state running finish
  [ -x "$NEW/bin/bash" ] || fail "复制出来的环境不完整"
  g "$BB" umount "$NEW" 9>&- || fail "卸不下新镜像"
  "$BB" rmdir "$NEW"
  "$BB" mv "$PART" "$IMG" || fail "放不好镜像文件"
  "$BB" mv "$R" "$OLD" || {
    "$BB" mv "$IMG" "$PART"
    fail "原目录改不了名"
  }
  ON_FAIL=convert_rollback
  "$BB" mkdir "$R" && "$BB" chmod 0755 "$R" && { "$BB" chcon "$LABEL" "$R" 2>/dev/null || chcon "$LABEL" "$R"; }
  do_mount
  write_boot
  ON_FAIL=
  restart_services
  state ok converted
}

op_delete_old() {
  [ -d "$OLD" ] || fail "没有转换前留下的目录"
  mounted "$R" || fail "磁盘镜像没挂上，先确认 Debian 正常再删"
  state running delete
  log "删除 $OLD"
  "$BB" rm -rf "$OLD" || fail "没删干净 $OLD"
  state ok deleted
}

case "$cmd" in
status)
  if [ -f "$IMG" ]; then
    echo "image=1"
    echo "size=$("$BB" stat -c %s "$IMG")"
    echo "alloc=$(($("$BB" stat -c %b "$IMG") * $("$BB" stat -c %B "$IMG")))"
  else
    echo "image=0"
    [ -x "$R/bin/bash" ] && echo "convertible=1"
  fi
  if mounted "$R"; then
    echo "mounted=1"
    "$BB" df -kP "$R" | "$BB" awk 'NR == 2 {print "used=" $3 * 1024; print "avail=" $4 * 1024}'
  else
    echo "mounted=0"
  fi
  [ -d "$OLD" ] && echo "old=1"
  "$BB" df -kP "$BASE" 2>/dev/null | "$BB" awk 'NR == 2 {print "free=" $4 * 1024}'
  if [ -f "$STATE" ]; then
    "$BB" cat "$STATE"
    # 状态说在做、却没人拿着锁：做的途中被打断了（比如重启）
    running_op && (lock_now) 2> /dev/null && echo "interrupted=1"
  fi
  ;;

mount)
  lock_wait
  do_mount
  [ -f "$BOOT" ] || write_boot
  ;;

boot)
  lock_wait
  do_mount
  write_boot
  trim > /dev/null
  ;;

trim)
  lock_now || fail "正在维护 Debian 的磁盘镜像，请等它做完"
  mounted "$R" || fail "磁盘镜像没挂上"
  trim || fail "fstrim 没有成功，详情见 $LOG"
  ;;

start)
  op=${1:-}
  case "$op" in
  convert | resize) size_gb "${2:-}" ;;
  check | delete-old) ;;
  *) fail "未知的操作 $op" ;;
  esac
  [ -f "$SELF" ] || fail "找不到 $SELF"
  (lock_now) || fail "正在做另一项维护"
  OP=$op
  STARTED=
  state running start
  export STARTED
  OP=
  check_log_size
  log "开始 $*"
  "$BB" setsid "$BB" sh "$SELF" "$BB" run "$@" < /dev/null >> "$LOG" 2>&1 &
  ;;

run)
  OP=$1
  shift
  lock_now || fail "正在做另一项维护"
  case "$OP" in
  convert) op_convert "$@" ;;
  check) op_check ;;
  resize) op_resize "$@" ;;
  delete-old) op_delete_old ;;
  esac
  log "完成 $OP"
  ;;

*)
  fail "未知的命令 $cmd"
  ;;
esac
exit 0
