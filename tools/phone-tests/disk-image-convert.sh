#!/bin/bash
# 磁盘镜像（assets/zeta_image_android.sh）之一：测试版起来后磁盘上的 image.sh 是这一版的；环境还是普通目录时转成 16 GB 的镜像
# （之后这台设备的环境一直是镜像，再跑时只说一声、不再转），核对文件数、标签和开机脚本；再写入又删掉 64 MB，释放空间要放出空间。
# 接着跑 disk-image-maintain.sh、disk-image-page.sh：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/disk-image-convert.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
. "$HERE/disk-image-lib.sh"

# 测试版就绪时把应用里的这份脚本写到磁盘上
for _ in $(seq 30); do
  android "[ -f /data/local/zeta/image.sh ]" && break
  sleep 1
done
android "cat /data/local/zeta/image.sh" | cmp -s - "$ZETA_ROOT/app/src/main/assets/zeta_image_android.sh" ||
  { echo "磁盘上的 image.sh 和这一版的不一样"; exit 1; }

status
if [[ $(value image) == 0 ]]; then
  [[ $(value convertible) == 1 ]] || { echo "没有可以转换的环境：$STATUS"; exit 1; }
  files=$(android "find $R -xdev | wc -l")
  image start convert 16
  wait_op convert
  [[ $(value image) == 1 && $(value mounted) == 1 && $(value old) == 1 ]] || { echo "转换后的状态不对：$STATUS"; exit 1; }
  [[ $(value size) == "$(gib 16)" ]] || { echo "镜像上限不是 16 GB：$(value size)"; exit 1; }
  copied=$(android "find $R -xdev | wc -l")
  # 原来的每个文件都在（镜像根目录多一个 lost+found）
  ((copied >= files)) || { echo "转换前 $files 个文件，镜像里只有 $copied 个"; exit 1; }
  # 文件跟着上级目录标成和原来一样的 system_data_file
  label=$(android "ls -Zd $R/usr/bin $R/home/$ZETA_LINUX_USER" | awk '{print $1}' | sort -u)
  [[ $label == u:object_r:system_data_file:s0 ]] || { echo "镜像里的标签不对：$label"; exit 1; }
  echo "转换前后各 $files、$copied 个文件"
else
  echo "环境已经是镜像，不再转换（convert 只在第一次跑时验）"
fi
debian_ok convert
# 开机脚本：转换时写；早先转的、缺了的由 mount 补上
image mount
android "[ -f /data/adb/service.d/89-zeta-image.sh ]" || { echo "没有开机脚本"; exit 1; }

status
before=$(value alloc)
android "$BB dd if=/dev/urandom of=$R/tmp/zeta-image-test bs=1048576 count=64 2>/dev/null && sync && rm $R/tmp/zeta-image-test && sync"
out=$(image trim)
trimmed=$(sed -n 's/^trimmed=//p' <<<"$out")
((${trimmed:-0} > 0)) || { echo "fstrim 没放出空间：$out"; exit 1; }
status
echo "释放空间：放出 $trimmed 字节，镜像实占 $before → $(value alloc) 字节"

