#!/bin/bash
# 手机换网络时，经 Debian 连的远程主机马上重连：测试版的侧栏切到设备自己的 Debian（经 Debian 的 OpenSSH，同 remote-host.sh），
# 假 Claude（剧本 fake/remote.jsonl）答一轮；然后开关一次飞行模式（默认网络换成新的一个），核对 Zeta 自己关掉了
# Debian 里 ssh 的主连接（连的是 127.0.0.1，换网络本身断不了它，主连接没了只能是 Zeta 关的），
# 再发一条，核对两轮都答完、托管的还是同一个进程。截图 first.png、reconnected.png。
# 开关飞行模式会断掉设备的网络，所以只在模拟器上跑。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/remote-network-change.sh
#
# 设备上的准备同 remote-host.sh（Debian 里装好 sshd，Linux 用户的公钥在自己的 authorized_keys 里）。
# 假 Claude 不写会话记录，没有要删的测试会话；vd stop 改回 remoteHosts、backend 两项设置，飞行模式在退出时关掉。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
[[ $ZETA_DEVICE == emu ]] || { echo "这个测试要开关飞行模式，只在模拟器上跑"; exit 2; }
NEWER=/data/local/tmp/zeta-remote-network.mark
trap 'android "rm -f $NEWER; cmd connectivity airplane-mode disable" >/dev/null 2>&1 || true' EXIT

android "touch $NEWER"
vd fake "$HERE/fake/remote.jsonl"
use_loopback_host r-phonetest debian
vd launch
sleep 10 # 冷启动后马上输入会丢字

# Debian 里 Zeta 的 ssh 主连接：OpenSSH 把它的进程标题改成「ssh: <ControlPath> [mux]」
masters() {
  android "for d in /proc/[0-9]*; do case \"\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null)\" in 'ssh: '*'/.ssh/zeta-'*' [mux]'*) echo \${d#/proc/};; esac; done; true" |
    tr -d '\r' | xargs
}

switch_network() {
  local before gone p
  before=$(masters)
  [[ -n $before ]] || { echo "Debian 里没有 Zeta 的 ssh 主连接：对话没走 Debian 的 OpenSSH"; return 1; }
  echo "开关一次飞行模式；Zeta 的 ssh 主连接：$before"
  android "cmd connectivity airplane-mode enable"
  sleep 2
  android "cmd connectivity airplane-mode disable"
  for _ in $(seq 40); do
    gone=1
    for p in $before; do alive "$p" && gone=0; done
    ((gone)) && break
    sleep 0.5
  done
  ((gone)) || { echo "换网络 20 秒后原来的主连接（$before）还在：Zeta 没关它"; return 1; }
  echo "原来的主连接已由 Zeta 关掉"
}
two_turns_across_drop switch_network
echo "换网络后经 Debian 的连接马上断开重连，接回原来的进程，第二轮照常答完"
