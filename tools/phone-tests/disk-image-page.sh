#!/bin/bash
# 磁盘镜像之三（先跑 disk-image-convert.sh）：删除转换前的目录（还在的话），在测试屏上打开「磁盘镜像」页截图（image-page.png），
# 再用假 Claude 跑一轮，确认做完维护后 Zeta 还能用这个环境。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/disk-image-page.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
. "$HERE/disk-image-lib.sh"

status
if [[ $(value old) == 1 ]]; then
  image start delete-old
  wait_op delete-old
  android "[ ! -e /data/local/zeta/debian/rootfs.old ]" || { echo "旧目录还在"; exit 1; }
fi

# tap_row <正则> <名字>：往下滚到这一行（最多滚 8 次）再点；找不到就截图退出
tap_row() {
  for _ in $(seq 8); do
    if vd nodes "$1" | grep -q .; then
      vd tap-text "$1" >/dev/null
      sleep 1.5
      return 0
    fi
    vd swipe 610 2000 610 900 >/dev/null
    sleep 1
  done
  vd shot "no-$2.png" >/dev/null
  echo "滚到底也没找到「$1」"
  exit 1
}

# 「磁盘镜像」页：设置首页最上面的「手机」› Linux 环境 › 磁盘镜像（深链开手机操作页、返回到设置首页，坐标同 settings-pages.sh）
android "am start --display $DISPLAY_ID --activity-single-top -n $PKG/com.dzshzx.zeta.ui.MainActivity --es com.dzshzx.zeta.extra.SETTINGS_PAGE Phone >/dev/null"
sleep 2
vd key 4 >/dev/null
sleep 1.5
vd tap 365 633 >/dev/null
sleep 1.5
tap_row '^Linux 环境$' linux
tap_row '^磁盘镜像$' image
wait_text '^镜像上限$' 15 || { vd shot image-page-missing.png >/dev/null; echo "磁盘镜像页没显示镜像"; exit 1; }
vd shot image-page.png >/dev/null
for _ in 1 2 3 4; do
  vd key 4 >/dev/null
  sleep 1
done

# 做完维护后 Zeta 还能在这个环境里跑一轮（核心断开后自己重连）
vd launch >/dev/null
sleep 10
new_chat
vd fake "$HERE/fake/text.jsonl"
send "fake-after-image"
wait_result 1 60 || { vd shot after-image.png >/dev/null; exit 1; }
[[ $(jq -r .is_error <<<"$RESULT") == false ]] || { echo "维护后这一轮出错：$RESULT"; exit 1; }
echo "维护后假 Claude 照常跑完一轮"
