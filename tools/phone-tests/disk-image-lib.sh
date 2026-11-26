# shellcheck shell=bash
# 磁盘镜像几个设备脚本（disk-image-*.sh）的公用部分，在 lib.sh 之后 source。维护要停掉 Debian 里的全部程序，所以只在模拟器上跑。
[[ ${ZETA_DEVICE:-emu} != phone ]] || { echo "会停掉手机上 Debian 里的全部程序，只在模拟器上跑"; exit 2; }

BB=/data/adb/ksu/bin/busybox
R=$ROOTFS
image() { android "$BB sh /data/local/zeta/image.sh $BB $*"; }
status() { STATUS=$(image status); }
# value <键>：状态里这一项的值
value() { grep -m1 "^$1=" <<<"$STATUS" | cut -d= -f2- || true; }

# wait_op <操作> [期望的结果，默认 ok]：等后台的维护做完（最多 4 分钟：设备脚本一共只给 300 秒）
wait_op() {
  local start=$SECONDS want=${2:-ok}
  while :; do
    status
    [[ $(value op) == "$1" ]] || { echo "状态里的操作不是 $1：$STATUS"; exit 1; }
    [[ $(value phase) == running && -z $(value interrupted) ]] || break
    ((SECONDS - start < 240)) || { echo "$1 4 分钟还没做完：$STATUS"; exit 1; }
    sleep 2
  done
  if [[ $(value phase) != "$want" ]]; then
    echo "$1 的结果不是 $want：$(value phase) $(value msg)"
    android "tail -n 20 /data/local/zeta/debian/image.log"
    exit 1
  fi
  # 界面拿这两个时刻显示已用时间：开始时刻要从 start 传到后台进程，步骤时刻不晚于最后更新
  local started step time
  started=$(value started) step=$(value step) time=$(value time)
  [[ $started =~ ^[0-9]+$ && $step =~ ^[0-9]+$ ]] || { echo "状态里没有开始时刻：$STATUS"; exit 1; }
  ((started <= step && step <= time)) || { echo "时刻不对：started=$started step=$step time=$time"; exit 1; }
  echo "$1：$(value phase) $(value msg)，用了 $((SECONDS - start)) 秒"
}

# 经进入脚本（zeta_enter.sh：镜像没挂上时它先挂上）还能以 Linux 用户进 Debian，家目录还在
debian_ok() {
  vd debian 'test -x /bin/bash && test -d "$HOME"' || { echo "$1 后进不去 Debian 了"; exit 1; }
}

gib() { echo $(($1 << 30)); }
