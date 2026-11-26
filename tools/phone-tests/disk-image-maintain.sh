#!/bin/bash
# 磁盘镜像之二（先跑 disk-image-convert.sh）：检查并修复，调整大小（放大、缩回，缩到 1 GB 放不下时被拒、镜像原样挂回），每步后还能进 Debian。
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

