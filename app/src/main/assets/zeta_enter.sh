#!/system/bin/sh
# 由 Zeta 写入，每次启动 SSH 服务时会更新，不要手改。
# 以 root 进入 Zeta 的 Debian 环境：su -c sh /data/local/zeta/debian.sh [命令…]
# 不带命令时打开一个登录 shell。挂载都做在独立的挂载命名空间里，不影响 Android，也不用卸载。
# 环境不在默认位置时用 ZETA_ROOTFS 指定。
R=${ZETA_ROOTFS:-/data/local/zeta/debian/rootfs}
BB=
for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
  [ -x "$b" ] && BB=$b && break
done
[ "$(id -u)" = 0 ] || { echo "需要 root：su -c sh $0" >&2; exit 1; }
[ -n "$BB" ] || { echo "找不到 root 管理器自带的 BusyBox" >&2; exit 1; }
# Zeta 的环境在磁盘镜像里、还没挂上（刚开机、做完维护）时先挂上，见 /data/local/zeta/image.sh
[ -f /data/local/zeta/debian/rootfs.img ] && ! "$BB" grep -q ' /data/local/zeta/debian/rootfs ' /proc/1/mounts &&
  "$BB" sh /data/local/zeta/image.sh "$BB" mount >&2
[ -x "$R/bin/bash" ] || { echo "环境不存在：$R" >&2; exit 1; }

if [ "${ZETA_INNS:-}" != 1 ]; then
  export ZETA_INNS=1
  exec "$BB" nsenter -t 1 -m -- "$BB" unshare -m --propagation private "$BB" sh "$0" "$@"
fi

# Android 把 /data 挂成 nosuid；把 rootfs 再绑一次并打开 suid，sudo 才能用（只在这个命名空间里）
"$BB" mount -o bind "$R" "$R" && "$BB" mount -o remount,bind,suid "$R" "$R"
"$BB" mount -t proc proc "$R/proc"
"$BB" mount -o rbind /dev "$R/dev"
"$BB" mount -o rbind /sys "$R/sys" 2>/dev/null
[ -d /storage/emulated/0 ] && "$BB" mount -o bind /storage/emulated/0 "$R/storage/emulated/0" 2>/dev/null
# /workspace 是用户的工作区，和 Zeta 进环境时绑的一样（LinuxEnv.WORKSPACE）
"$BB" mkdir -p "$R/data/local/tmp" /data/local/zeta/workspace "$R/workspace"
"$BB" mount -o bind /data/local/tmp "$R/data/local/tmp"
"$BB" mount -o bind /data/local/zeta/workspace "$R/workspace"

unset ZETA_INNS
[ $# -eq 0 ] && set -- /bin/bash -l
exec "$BB" chroot "$R" /usr/bin/env -i \
  HOME=/root TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 \
  PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  "$@"
