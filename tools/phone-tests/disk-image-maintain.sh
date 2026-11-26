#!/bin/bash
# 磁盘镜像之二（先跑 disk-image-convert.sh）：检查并修复，调整大小（放大、缩回，缩到 1 GB 放不下时被拒、镜像原样挂回），每步后还能进 Debian；
# 开机那一步把标签不对的工作区按规则重贴。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/disk-image-maintain.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
. "$HERE/disk-image-lib.sh"

status
[[ $(value image) == 1 ]] || { echo "环境还不是镜像，先跑 disk-image-convert.sh"; exit 1; }
image start check
wait_op check
[[ $(value mounted) == 1 ]] || { echo "检查后没挂回去：$STATUS"; exit 1; }
debian_ok check

image start resize 20
wait_op resize
[[ $(value size) == "$(gib 20)" ]] || { echo "没放大到 20 GB：$(value size)"; exit 1; }
image start resize 16
wait_op resize
[[ $(value size) == "$(gib 16)" ]] || { echo "没缩回 16 GB：$(value size)"; exit 1; }
debian_ok resize
# 缩到放不下的大小要拒绝，镜像原样挂回去
image start resize 1
wait_op resize error
[[ $(value size) == "$(gib 16)" && $(value mounted) == 1 ]] || { echo "缩到 1 GB 被拒后镜像不对：$STATUS"; exit 1; }
debian_ok "缩小被拒"


# 开机那一步把标签不对的工作区按规则重贴：放一个文件，整棵改成 /data/local/tmp 的标签（从那里改名过来的就是这样），跑 boot
W=/data/local/zeta/workspace
label_of() { android "ls -Zd $1" | awk '{print $1}'; }
android "mkdir -p $W && echo probe > $W/.label-probe && chcon -R u:object_r:shell_data_file:s0 $W"
[[ $(label_of $W/.label-probe) == *:shell_data_file:* ]] || { echo "没能把工作区改成错的标签"; exit 1; }
image boot
for p in $W $W/.label-probe; do
  [[ $(label_of $p) == *:system_data_file:* ]] || { echo "开机那一步没重贴 $p：$(label_of $p)"; exit 1; }
done
# 目录本身的标签对时什么也不做（只看目录本身，代价是里面个别文件不对时看不出来）
android "chcon u:object_r:shell_data_file:s0 $W/.label-probe"
image boot
[[ $(label_of $W/.label-probe) == *:shell_data_file:* ]] || { echo "目录本身标签对的时候也重贴了"; exit 1; }
android "rm $W/.label-probe"
echo "工作区标签：错的重贴了，对的没动"
