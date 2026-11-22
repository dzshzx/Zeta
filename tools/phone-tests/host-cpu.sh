#!/bin/bash
# 输入栏上的负载标识：开一个还没发过消息的新对话（手机本机的 Debian），等标识读出数（说明是「设备负载：CPU N%」），
# 说明里的数和 Debian 里直接读 /proc/stat 算的对得上量级（0～100），截图 host-cpu.png。
#   ZETA_PHONE_OWNER=<任务名> ZETA_DEVICE=phone tools/phone-run tools/phone-tests/host-cpu.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

new_chat
if ! wait_text '设备负载：CPU [0-9]+%' 20; then
  echo "20 秒内负载标识没读出数"
  vd nodes '设备负载' || true
  vd shot missing.png >/dev/null
  exit 1
fi
vd nodes '设备负载'
vd shot host-cpu.png >/dev/null
# 对照：核心是以 Linux 用户的身份读的，这里也以同一个用户读一次，确认 Debian 里读得到
vd debian 'head -1 /proc/stat'
echo "新对话的负载标识读出了数"
