#!/system/bin/sh
# shellcheck disable=SC2015,SC2016
# Zeta 的 SSH 服务在 Android 一侧的部分（root，管理器自带的 BusyBox ash）。
# 装包和配置在 Debian 里面做，见 zeta_setup_debian.sh 的 ssh-setup / ssh-status / ssh-addkey。
#
#   status    <rootfs>                      sshd 是否在跑、是否开机自启
#   start     <rootfs> <进入脚本的内容>       启动 sshd（已经在跑就不动）
#   stop      <rootfs>                      停掉监听进程；已经连上的会话不受影响
#   autostart <rootfs> on <进入脚本的内容>    写开机脚本
#   autostart <rootfs> off                  删开机脚本
#
# 参数：$1 BusyBox，$2 命令，$3 rootfs。
set -u
BB=$1
cmd=$2
R=$3
shift 3
BASE=/data/local/zeta
ENTER=$BASE/debian.sh
BOOT=/data/adb/service.d/90-zeta-sshd.sh
LOG=$BASE/sshd.log
RUN_SSHD='mkdir -p /run/sshd; exec /usr/sbin/sshd -D -e'

fail() { echo "@zeta error $*"; exit 1; }

# 这个环境里的 sshd 监听进程。各个 Debian 环境和 Android 共用一个进程表，所以要看进程的根目录是不是这个 rootfs；
# 父进程也是 sshd 的是某个会话，不是监听进程。
listener() {
  for pid in $("$BB" pidof sshd); do
    [ "$("$BB" readlink "/proc/$pid/root" 2>/dev/null)" = "$R" ] || continue
    parent=$("$BB" awk '/^PPid:/ {print $2}' "/proc/$pid/status" 2>/dev/null)
    [ "$("$BB" cat "/proc/$parent/comm" 2>/dev/null)" = sshd ] && continue
    echo "$pid"
    return 0
  done
  return 1
}

write_enter() {
  "$BB" mkdir -p "$BASE" || fail "建不了目录 $BASE"
  printf '%s\n' "$1" > "$ENTER.new" && "$BB" chmod 0755 "$ENTER.new" && "$BB" mv "$ENTER.new" "$ENTER" || fail "写不了 $ENTER"
}

case "$cmd" in
status)
  pid=$(listener) && echo "running=1" && echo "pid=$pid" || echo "running=0"
  [ -f "$BOOT" ] && "$BB" grep -qF "R=$R" "$BOOT" && echo "autostart=1" || echo "autostart=0"
  ;;

start)
  [ -x "$R/usr/sbin/sshd" ] || fail "这个环境里还没有装 SSH 服务"
  write_enter "$1"
  listener >/dev/null && exit 0
  # 日志太大就从头记
  [ "$("$BB" stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ] && : > "$LOG"
  echo "$(date) starting sshd" >> "$LOG"
  ZETA_ROOTFS="$R" "$BB" setsid "$BB" sh "$ENTER" /bin/sh -c "$RUN_SSHD" >> "$LOG" 2>&1 < /dev/null &
  n=0
  while [ $n -lt 20 ]; do
    listener >/dev/null && exit 0
    "$BB" usleep 250000
    n=$((n + 1))
  done
  fail "sshd 没有启动起来：$("$BB" tail -n 3 "$LOG" | "$BB" tr '\n' ' ')"
  ;;

stop)
  pid=$(listener) || exit 0
  kill "$pid" 2>/dev/null
  n=0
  while [ $n -lt 20 ]; do
    listener >/dev/null || exit 0
    "$BB" usleep 250000
    n=$((n + 1))
  done
  fail "sshd 没有停下来"
  ;;

autostart)
  if [ "$1" = on ]; then
    [ -d /data/adb/service.d ] || fail "这台设备的 root 管理器没有 /data/adb/service.d，没法设开机自启"
    write_enter "$2"
    {
      echo '#!/system/bin/sh'
      echo '# 由 Zeta 生成（设置 → Linux 环境 → SSH 服务 → 开机自动启动）。开机后启动 Zeta 的 Debian 里的 sshd。'
      echo "R=$R"
      echo "LOG=$LOG"
      echo '('
      echo '  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done'
      echo '  until [ -x "$R/usr/sbin/sshd" ]; do sleep 10; done'
      echo '  sleep 5'
      echo '  echo "$(date) starting sshd" >> "$LOG"'
      echo "  ZETA_ROOTFS=\"\$R\" sh $ENTER /bin/sh -c '$RUN_SSHD' >> \"\$LOG\" 2>&1"
      echo ') &'
    } > "$BOOT.new" && "$BB" chmod 0755 "$BOOT.new" && "$BB" mv "$BOOT.new" "$BOOT" || fail "写不了 $BOOT"
  else
    "$BB" rm -f "$BOOT"
  fi
  ;;

*)
  fail "未知的命令 $cmd"
  ;;
esac
exit 0
