#!/bin/bash
# 远程主机：测试版的侧栏切到一台远程主机，新对话开在它上面，假 Claude（剧本 fake/remote.jsonl）答一轮；
# 然后掐断 Zeta 的 ssh 连接，等它自己重连，再发一条，核对两轮都答完、托管的还是同一个进程。
# 截图 first.png（第一轮答完）、reconnected.png（重连后第二轮答完）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/remote-host.sh
#
# 远程主机是设备自己的 Debian：经 127.0.0.1:8022 的 sshd 连回自己（ssh://<Linux 用户>@127.0.0.1:8022），家目录一样，
# 所以托管目录、假 Claude 的剧本都在本机的老地方，lib.sh 的 find_log、wait_result 照用。设备上要先准备好（一次）：
# 在 Debian 里装好并起着 sshd（设置 › Linux 环境 › SSH 服务），Linux 用户有一把密钥、它的公钥在自己的 authorized_keys 里。
# 假 Claude 不写会话记录，没有要删的测试会话；vd stop 改回 remoteHosts、backend 两项设置。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
NEWER=/data/local/tmp/zeta-remote-host.mark
trap 'android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

android "touch $NEWER"
vd fake "$HERE/fake/remote.jsonl"
use_loopback_host r-phonetest
vd launch
sleep 10 # 冷启动后马上输入会丢字

# Zeta 这一头的 ssh（ControlMaster 的主连接自己脱离出去了，不在里面）
cut_ssh() {
  local ssh_pids
  ssh_pids=$(app_ssh_pids | xargs)
  [[ -n $ssh_pids ]] || { echo "测试版底下没有 ssh 进程：对话没走远程主机"; return 1; }
  echo "掐断 Zeta 的 ssh：$ssh_pids"
  android "kill $ssh_pids"
}
two_turns_across_drop cut_ssh
echo "远程主机上的对话断线后接回原来的进程，第二轮照常答完"
