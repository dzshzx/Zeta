#!/system/bin/sh
# 一次性迁移：Linux 里的 /workspace 原来绑的是 /data/local/tmp/eta（和 Eta 共用的目录），现在绑 Zeta 自己的
# /data/local/zeta/workspace（LinuxEnv.WORKSPACE）。在已有旧工作区的设备上跑一次，把它整体搬过去。
# Zeta 自己不做这件事：别的设备上的 /data/local/tmp/eta 可能是 Eta 正在用的工作区。
#
#   status    看两处现在的样子，不改东西（不给参数时也是它）
#   migrate   同一分区上改名（不复制，属主、权限、SELinux 标签跟着目录走），在旧位置留一个指向新目录的符号链接：
#             还没升级的旧版本照旧 mkdir -p 再 mount -o bind /data/local/tmp/eta，绑到的是同一份文件；
#             正在跑的进程绑的是这个目录本身，改名后照样看得到。已经搬过的再跑什么也不做。
#             新目录已经在、里面有东西时停下报错，不合并；是空目录（新版本先进过环境、建了它）就删掉它再搬。
#   rollback  撤回：删掉符号链接，把目录改名回去。之后新版本进环境时会另建一个空的新目录，
#             所以只在装回旧版本时用。
#
# 在 Android 一侧以 root、全局挂载命名空间里跑，手机和模拟器通用。在工作站上：
#   tools/vd sh "set -- migrate; $(cat tools/migrate-workspace.sh)"     （ZETA_DEVICE=phone 时是手机）
#   设备脚本里（tools/phone-run）：android "set -- migrate; $(cat "$ZETA_ROOT/tools/migrate-workspace.sh")"
#   或经 ssh 进手机 Debian：ssh <主机> 'sudo -n timeout 60 nsenter -t 1 -m -- /system/bin/sh -s migrate' < tools/migrate-workspace.sh
set -u
export PATH="/system/bin:$PATH"
OLD=/data/local/tmp/eta
NEW=/data/local/zeta/workspace

fail() {
  echo "migrate-workspace: $*" >&2
  exit 1
}

# 一行说明：符号链接指向哪，目录的属主、权限、SELinux 标签和里面有几项
describe() {
  if [ -L "$1" ]; then
    echo "$1 -> $(readlink "$1")（符号链接）"
  elif [ -d "$1" ]; then
    echo "$1 目录 $(stat -c '%u:%g %a %C' "$1")，里面 $(ls -A "$1" | wc -l) 项"
  elif [ -e "$1" ]; then
    echo "$1 在，但不是目录"
  else
    echo "$1 不在"
  fi
}

migrated() { [ -L "$OLD" ] && [ "$(readlink "$OLD")" = "$NEW" ] && [ -d "$NEW" ] && [ ! -L "$NEW" ]; }

[ "$(id -u)" = 0 ] || fail "要以 root 跑"

case ${1:-status} in
status)
  describe "$OLD"
  describe "$NEW"
  if migrated; then echo "已迁移"; elif [ -d "$OLD" ] && [ ! -L "$OLD" ]; then echo "还没迁移"; else echo "不是迁移前后的样子"; fi
  ;;

migrate)
  if migrated; then
    echo "已经迁移过了"
    describe "$NEW"
    exit 0
  fi
  [ -L "$OLD" ] && fail "$OLD 是指向 $(readlink "$OLD") 的符号链接，不是这个脚本留下的，不动它"
  [ -d "$OLD" ] || fail "没有目录 $OLD，没有要迁的"
  grep -q " $OLD " /proc/self/mountinfo && fail "$OLD 是挂载点，改不了名"
  if [ -e "$NEW" ] || [ -L "$NEW" ]; then
    { [ -d "$NEW" ] && [ ! -L "$NEW" ] && [ -z "$(ls -A "$NEW")" ]; } || fail "$NEW 已经在了，而且不是空目录；不合并，先看看里面是什么"
    rmdir "$NEW" || fail "删不掉空的 $NEW"
    echo "删掉了先建好的空目录 $NEW"
  fi
  mkdir -p "${NEW%/*}" || fail "建不了 ${NEW%/*}"
  # 先拿一个空目录试一次：跨分区或跨挂载时 mv 会退回成复制再删除，那样属主、标签都会变，也可能很慢
  probe=$OLD.zeta-probe.$$
  mkdir "$probe" || fail "建不了试探用的 $probe"
  inode=$(stat -c %i "$probe")
  if ! mv -T "$probe" "$NEW.zeta-probe.$$" || [ "$(stat -c %i "$NEW.zeta-probe.$$")" != "$inode" ]; then
    rm -rf "$probe" "$NEW.zeta-probe.$$"
    fail "$OLD 和 $NEW 不在同一个挂载上，改名做不到；不复制"
  fi
  rmdir "$NEW.zeta-probe.$$"
  before=$(stat -c '%i %u:%g %a %C' "$OLD")
  mv -T "$OLD" "$NEW" || fail "改名失败，什么也没动"
  # 符号链接先建在旁边再改名过去：要是旧版本刚好在这一刻 mkdir 了旧位置，这里报错，不会把链接放进那个目录里
  if ! { ln -s "$NEW" "$OLD.zeta-link.$$" && mv -T "$OLD.zeta-link.$$" "$OLD"; }; then
    fail "目录已经搬到 $NEW，但没能在 $OLD 留下符号链接；回退：rm -f $OLD.zeta-link.$$; mv -T $NEW $OLD"
  fi
  after=$(stat -c '%i %u:%g %a %C' "$NEW")
  [ "$after" = "$before" ] || fail "搬过去以后 inode、属主、权限或标签变了：之前 $before，之后 $after"
  migrated || fail "搬完的样子不对：$(describe "$OLD")；$(describe "$NEW")"
  echo "迁移完成"
  describe "$OLD"
  describe "$NEW"
  echo "回退：同一个脚本带 rollback"
  ;;

rollback)
  if [ -d "$OLD" ] && [ ! -L "$OLD" ]; then
    echo "$OLD 已经是目录，没有要回退的"
    exit 0
  fi
  migrated || fail "现在不是迁移后的样子，不回退：$(describe "$OLD")；$(describe "$NEW")"
  rm "$OLD" || fail "删不掉符号链接 $OLD"
  mv -T "$NEW" "$OLD" || fail "删了符号链接，但没能把 $NEW 改名回去；手动：mv -T $NEW $OLD"
  echo "回退完成"
  describe "$OLD"
  ;;

*)
  fail "用法：migrate-workspace.sh [status|migrate|rollback]"
  ;;
esac
