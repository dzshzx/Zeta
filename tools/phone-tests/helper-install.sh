#!/bin/bash
# zeta-helper 装进设备的 Debian：删掉测试版已装的、放一个旧版本的假文件，冷启动后应用第一次用它（读会话列表）时
# 按那边的 uname -m 把对应的一份经标准输入传进去装上。核对：只剩一个新版本、旧的删了；装好的程序在 Debian 里
# 以 Linux 用户直接跑 list 退出码是 0。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/helper-install.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
ROOTFS=${HOSTS%/home/*}
BIN=${HOSTS%/hosts}/bin
OLD=zeta-helper-0000000000000000

echo "Debian 的 CPU：$(android "chroot $ROOTFS /usr/bin/uname -m")"
android "rm -rf $BIN && mkdir -m 755 $BIN && printf old > $BIN/$OLD && chmod 644 $BIN/$OLD && chown -R 5000:5000 $BIN"
# vd launch 只把界面再开一次；要冷启动（读会话列表时才第一次用到 zeta-helper），先停掉测试版
android "am force-stop $PKG"
vd launch

installed=
for _ in $(seq 60); do
  installed=$(android "ls $BIN" | grep -v "^$OLD\$" | grep -E '^zeta-helper-[0-9a-f]{16}$' || true)
  [[ -n $installed ]] && break
  sleep 0.5
done
[[ -n $installed ]] || { echo "冷启动 30 秒后还没装上 zeta-helper：$(android "ls -la $BIN")"; exit 1; }
echo "装上了：$installed"

# 装的那条命令删旧版本在改名之后，稍等再看
sleep 1
left=$(android "ls -A $BIN")
[[ $left == "$installed" ]] || { echo "$BIN 里应只剩新版本，实际是：$left"; exit 1; }
android "test -x $BIN/$installed" || { echo "$installed 没有执行权限"; exit 1; }

android "chroot $ROOTFS /usr/bin/setpriv --reuid=5000 --regid=5000 --init-groups /usr/bin/env -i HOME=/home/$ZETA_LINUX_USER ZETA_NAME=zeta-dev ${BIN#"$ROOTFS"}/$installed list >/dev/null" ||
  { echo "在 Debian 里直接跑 $installed list 失败"; exit 1; }
echo "在 Debian 里以 $ZETA_LINUX_USER 跑 list：退出码 0"
vd shot installed.png >/dev/null
