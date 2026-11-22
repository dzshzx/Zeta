#!/bin/bash
# 输入栏上的负载标识只在对话里有了消息以后显示：还没发过消息的新对话里没有它；打开一个有消息的会话（手机本机的 Debian），
# 标识读出数（说明是「设备负载：CPU N%」），截图 host-cpu.png。
#   ZETA_PHONE_OWNER=<任务名> ZETA_DEVICE=phone tools/phone-run tools/phone-tests/host-cpu.sh
#
# 测试会话放在 Debian 的 $DIR 对应的项目目录里，脚本退出时删掉。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-host-cpu
ID=$(cat /proc/sys/kernel/random/uuid)
trap 'drop_session $ID $DIR' EXIT

new_chat
# 新对话：等几秒（够问好几次），标识一直不该出现
if wait_text '设备负载' 6; then
  echo "还没发过消息的新对话里出现了负载标识"
  vd shot new-chat.png >/dev/null
  exit 1
fi
echo "新对话里没有负载标识"

seed_session "$ID" "$DIR" "host cpu probe" "probe reply"
open_conversation "$ID"
if ! wait_text '设备负载：CPU [0-9]+%' 20; then
  echo "有消息的对话里，20 秒内负载标识没读出数"
  vd nodes '设备负载' || true
  vd shot missing.png >/dev/null
  exit 1
fi
vd nodes '设备负载'
vd shot host-cpu.png >/dev/null
echo "有消息的对话里负载标识读出了数"
