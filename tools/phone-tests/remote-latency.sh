#!/bin/bash
# 每条命令要多久：手机本机的 Debian、经手机 Debian 的 OpenSSH 连远程主机、Zeta 直连远程主机，三条路走的都是测试版平常
# 跑命令的那段代码（core/diag/LatencyBench，只在测试版的 manifest 里登记），命令是 true。两条远程的连同一台主机的同一个 sshd。
# 几条路轮流跑：先各预热一次，再复用连接各跑 WARM 次（默认 20，登录 shell 和不登录的各一次：zeta-helper 这类命令不起登录 shell），
# 远程的再各重新握手 COLD 次（默认 5）。不起 Claude，不建会话。
# 模拟器上连回设备自己 Debian 里的 sshd（127.0.0.1:8022，同 remote-host.sh、remote-direct.sh，测试版的公钥由脚本加进去），
# 不经网络，量的是两种连法各自的固定开销。手机上连 Debian 的 ~/.ssh/config 里的别名 LATENCY_ALIAS（或 git config zeta.latencyAlias）：
# 直连的地址取 ssh -G 解析出的用户、主机、端口，主机指纹在手机 Debian 里用 ssh-keyscan 取。
# 手机上测试版的公钥要已经在那台主机上（往主机上加公钥是新增访问权限，脚本不做），不在时直连那一路记成失败。
# 手机上测完再用手机 Debian 里的 tailscale ping 量一组网络往返，记下走的路径（直连的地址或中继），和命令的耗时对照。
# NETWORK=cellular（只在手机上）：先关掉 Wi-Fi，走蜂窝到通了再测，测完开回。关之前在手机上起一个兜底进程，
# 10 分钟后开回 Wi-Fi（工作站和手机失联时也恢复）；正常收尾时删掉它认的标记，它到点就不动。
#   [LATENCY_ALIAS=<别名>] [NETWORK=cellular] ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run tools/phone-tests/remote-latency.sh
# 产物：latency.txt（每条路的次数、中位数、平均、最少、最多，毫秒；网络往返），samples.txt（LatencyBench 的原始输出）。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
WARM=${WARM:-20} COLD=${COLD:-5} NETWORK=${NETWORK:-}
[[ -z $NETWORK || ($NETWORK == cellular && $ZETA_DEVICE == phone) ]] || { echo "NETWORK 只能是 cellular，且只在手机上"; exit 1; }

# 兜底：不会起对话，万一起了也是假 Claude，不起真的 claude、不建真会话
vd fake "$HERE/fake/remote.jsonl"
if [[ $ZETA_DEVICE == emu ]]; then
  ensure_loopback_sshd
  user=$ZETA_LINUX_USER host=127.0.0.1 port=8022 home=/home/$ZETA_LINUX_USER
  debian_target=ssh://$ZETA_LINUX_USER@127.0.0.1:8022
  hostkey=$(android "cut -d' ' -f2 $ROOTFS/etc/ssh/ssh_host_ed25519_key.pub" | tr -d '\r')
else
  . "$ZETA_ROOT/tools/config.sh"
  alias=$(zeta_config LATENCY_ALIAS latencyAlias)
  [[ -n $alias ]] || { echo "手机上要连哪台主机：设 LATENCY_ALIAS，或 git config zeta.latencyAlias <手机 Debian 里的 ssh 别名>"; exit 1; }
  read -r user host port jump < <(vd debian "ssh -G -- $alias" |
    awk '$1 == "user" { u = $2 } $1 == "hostname" { h = $2 } $1 == "port" { p = $2 } $1 == "proxyjump" { j = $2 }
      END { print u, h, p, (j == "" ? "none" : j) }')
  [[ $jump == none ]] || { echo "$alias 经跳板（$jump）：直连连不到同一处，量不了"; exit 1; }
  home=$(vd debian "ssh -o BatchMode=yes -- $alias 'printf %s \"\$HOME\"'")
  debian_target=$alias
  hostkey=$(vd debian "ssh-keyscan -p $port -t ed25519 -- $host 2>/dev/null" | awk '$2 == "ssh-ed25519" { print $3 }')
fi
[[ $host == *:* ]] && direct_target="$user@[$host]:$port" || direct_target="$user@$host:$port"
[[ $hostkey =~ ^AAAAC3NzaC1lZDI1NTE5[A-Za-z0-9+/=]+$ ]] || { echo "取不到 $direct_target 的 Ed25519 主机公钥"; exit 1; }
[[ -n $home ]] || { echo "经 Debian 连 $debian_target 取不到家目录"; exit 1; }
echo "经 Debian：$debian_target；直连：$direct_target；家目录 $home"

entry() { echo "{\"id\":\"$1\",\"name\":\"$2\",\"target\":\"$3\",\"home\":\"$home\",\"cwd\":\"$home\",\"via\":\"$4\"${5:+,\"hostKey\":\"$5\"}}"; }
vd prefs-set "remoteHosts=[$(entry r-latdebian 延迟-经Debian "$debian_target" debian),$(entry r-latdirect 延迟-直连 "$direct_target" direct "$hostkey")]"
vd launch
[[ $ZETA_DEVICE != emu ]] || authorize_test_key
sleep 8 # 启动时连远程主机的那几条命令（会话列表、默认值）跑完，不和计时的抢

if [[ $NETWORK == cellular ]]; then
  guard=/data/local/tmp/zeta-latency-wifi-guard
  [[ $(android 'settings get global wifi_on' | tr -d '\r') == 1 ]] || { echo "Wi-Fi 本来就关着，不动"; exit 1; }
  # 兜底到点时标记还是这次写的才开回 Wi-Fi；正常收尾删掉标记，它到点什么都不做（不靠发信号结束它）
  token=$(date +%s%N)
  android "echo $token > $guard; setsid sh -c 'sleep 600; [ \"\$(cat $guard 2>/dev/null)\" = $token ] && cmd wifi set-wifi-enabled enabled && rm -f $guard' </dev/null >/dev/null 2>&1 &"
  restore_wifi() { android "rm -f $guard; cmd wifi set-wifi-enabled enabled" >/dev/null || true; }
  trap restore_wifi EXIT
  trap 'exit 143' TERM INT
  android 'cmd wifi set-wifi-enabled disabled' >/dev/null
  # 等 Wi-Fi 真关了、经蜂窝连得上那台主机
  for _ in $(seq 30); do
    [[ $(android 'settings get global wifi_on' | tr -d '\r') == 0 ]] && vd debian "ssh -o BatchMode=yes -o ConnectTimeout=10 -- $alias true" >/dev/null 2>&1 && break
    sleep 2
  done
  [[ $(android 'settings get global wifi_on' | tr -d '\r') == 0 ]] || { echo "Wi-Fi 没关掉"; exit 1; }
  echo "已关 Wi-Fi，走蜂窝"
  sleep 3 # Zeta 收到换网络、断开旧连接
fi

t0=$(android 'date +%s' | tr -d '\r')
android "am broadcast -n $PKG/com.dzshzx.zeta.core.diag.LatencyBench --ei warm $WARM --ei cold $COLD" >/dev/null
for _ in $(seq 240); do
  android "logcat -d -v raw -s ZetaLatency -t '$t0.000'" | tr -d '\r' >samples.txt
  grep -qx "done" samples.txt && break
  sleep 0.5
done
grep -qx "done" samples.txt || { echo "LatencyBench 两分钟没跑完"; cat samples.txt; exit 1; }
# 网络往返：tailscale ping 不停在打通直连那一下，每次都记
[[ $ZETA_DEVICE == emu ]] || vd debian "tailscale ping -c 20 --until-direct=false -- $host" 2>&1 | tr -d '\r' >ping.txt || true

# 一列毫秒数 → 次数、中位数、平均、最少、最多
stats() {
  sort -n | awk '{ a[NR] = $1; s += $1 }
    END { if (NR) printf "%d 次，中位 %.0f，平均 %.0f，最少 %.0f，最多 %.0f\n", NR, NR % 2 ? a[(NR + 1) / 2] : (a[NR / 2] + a[NR / 2 + 1]) / 2, s / NR, a[1], a[NR] }'
}
{
  echo "每条命令（true）从发出到收齐结果，毫秒；经 Debian：$debian_target，直连：$direct_target；网络：${NETWORK:-默认}"
  for via in local debian direct; do
    case $via in local) name=手机本机Debian ;; debian) name=经手机Debian ;; direct) name=Zeta直连 ;; esac
    for kind in warm plain cold; do
      case $kind in warm) what=复用连接 ;; plain) what=复用连接不登录 ;; cold) what=重新握手 ;; esac
      line=$(awk -v v=$via -v k=$kind '$1 == "sample" && $2 == v && $3 == k && $5 == 0 { print $4 }' samples.txt | stats)
      [[ -z $line ]] || echo "$name $what：$line"
    done
  done
  if [[ -s ping.txt ]]; then
    line=$(sed -n 's/^pong from .* in \([0-9.]*\)ms$/\1/p' ping.txt | stats)
    echo "网络往返（tailscale ping）：${line:-没有应答}；路径：$(sed -n 's/^pong from .* via \(.*\) in [0-9.]*ms$/\1/p' ping.txt | sort | uniq -c | sort -rn | awk '{ printf "%s%s×%s", (NR > 1 ? "，" : ""), $2, $1 }')"
  fi
} | tee latency.txt

bad=$(grep -E '^(skip|stderr) ' samples.txt || true)
failed=$(awk '$1 == "sample" && $5 != 0' samples.txt)
if [[ -n $bad$failed ]]; then
  echo "有的路没量成："
  printf '%s\n' "$bad" "$failed" | grep -v '^$'
  exit 1
fi
