#!/bin/bash
# 没有 Debian 也能连远程主机：测试版的 Linux 环境指到一个不存在的地方（手机本机用不了），侧栏切到一台 Zeta 直连的主机，
# 假 Claude（剧本 fake/remote.jsonl）答一轮；然后在那台主机上掐断这条 SSH 连接，等 Zeta 自己重连，再发一条，
# 核对两轮都答完、托管的还是同一个进程，测试版底下始终没有 ssh 进程（连接在应用里，不经 Debian 的 OpenSSH）。
# 开始前删掉测试版装的 zeta-helper：手机本机用不了，它只能经直连（压缩开着）装进那台主机，最后核对装上了。
# 截图 first.png（第一轮答完）、reconnected.png（重连后第二轮答完）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/remote-direct.sh
#
# 远程主机是设备自己的 Debian（dzshzx@127.0.0.1:8022，sshd 的准备见 lib.sh 的 ensure_loopback_sshd），家目录相同，
# 托管目录、假 Claude 的剧本都在老地方。测试版的公钥（应用私有目录的 zeta_ssh.pub，密钥第一次用时写出）
# 加进 dzshzx 自己的 authorized_keys（同一个注释的旧行先删掉）：只听本机的 sshd，测试设备自己连自己。
# 主机指纹取 sshd 的 Ed25519 主机密钥：手机上验 Ed25519 主机签名靠 BouncyCastle，这里一并测到。
# 假 Claude 不写会话记录，没有要删的测试会话；vd stop 改回 remoteHosts、backend、rootfs 三项设置。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-remote-direct.mark
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

android "touch $NEWER"
vd fake "$HERE/fake/remote.jsonl"
ensure_loopback_sshd
hostkey=$(android "cut -d' ' -f2 $ROOTFS/etc/ssh/ssh_host_ed25519_key.pub" | tr -d '\r')
[[ -n $hostkey ]] || { echo "读不到 sshd 的 Ed25519 主机密钥"; exit 1; }
id=r-phonedirect
vd prefs-set "remoteHosts=[{\"id\":\"$id\",\"name\":\"直连回环\",\"target\":\"dzshzx@127.0.0.1:8022\",\"home\":\"/home/dzshzx\",\"cwd\":\"/home/dzshzx\",\"via\":\"direct\",\"hostKey\":\"$hostkey\"}]" \
  "backend=$id" "rootfs=/data/local/zeta/no-debian-for-this-test"
# 测试版起来就去接回直连主机上的进程，这时取密钥（没有就生成）、写出公钥；先删掉旧的公钥文件，等这次写出的
pub=/data/user/0/$PKG/files/zeta_ssh.pub
android "rm -f $pub"
helper_bin=${HOSTS%/hosts}/bin
android "rm -rf $helper_bin"
vd launch

# 把公钥加进 authorized_keys（已经在的不重复加）
for _ in $(seq 40); do android "[ -s $pub ]" && break; sleep 0.5; done
android "[ -s $pub ]" || { echo "测试版没写出公钥 $pub"; exit 1; }
android "k=\$(cat $pub); c=\${k##* }; f=$ROOTFS/home/dzshzx/.ssh/authorized_keys; grep -qxF \"\$k\" \$f || \
  { grep -v \" \$c\\\$\" \$f > \$f.new; echo \"\$k\" >> \$f.new; cat \$f.new > \$f; rm -f \$f.new; }"
# 加公钥以前那几次登录失败会让 sshd 一阵子不理 127.0.0.1，重启它清掉
restart_loopback_sshd
sleep 6 # 冷启动后马上输入会丢字

# 连接在应用里，不经 Debian 的 OpenSSH
no_app_ssh() { [[ -z $(app_ssh_pids) ]] || { echo "测试版底下有 ssh 进程：走的是 Debian 的 OpenSSH，不是直连"; return 1; }; }
# 在那台主机上掐断连接：结束 sshd 给每条连接开的 sshd-session（监听的 sshd 留着），托管的 Claude 自成一组，不受影响
cut_sessions() {
  local sessions
  sessions=$(debian_pids sshd-session)
  [[ -n $sessions ]] || { echo "设备的 sshd 上没有连接：对话没走直连的主机"; return 1; }
  echo "掐断直连的 SSH 连接（sshd-session $sessions）"
  android "kill $sessions"
}
two_turns_across_drop cut_sessions no_app_ssh
installed=$(android "ls $helper_bin 2>/dev/null" | grep -E '^zeta-helper-[0-9a-f]{16}$' || true)
[[ -n $installed ]] || { echo "直连的主机上没装上 zeta-helper"; exit 1; }
echo "zeta-helper 经直连装进了那台主机：$installed"
echo "手机本机没有 Debian 时，直连的远程主机照常对话，断线后接回原来的进程"
