#!/bin/bash
# 实时图片只带键（打开对话时声明 options.image_refs）：经分享把一张 PNG 发进新对话，假 Claude 答完后，核心推来的条目里这张图
# 只有键，聊天里要经核心的 conv.image 取回才画得出来。核对画出来了（说明是「图片」的控件），没有「图片无法显示」。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/chat-image-refs.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

ref=/data/local/tmp/zeta-chat-image-refs.ref
NEWER=$ref
image=/data/user/0/$PKG/files/zeta-image-refs.png
cleanup() {
  android "rm -f $image $ref"
  # 这次对话的托管目录（假 Claude 不写会话记录，只有它）
  if [[ $log == "$HOSTS"/*/out.jsonl ]]; then
    android "rm -r '${log%/out.jsonl}'" && echo "删了这次对话的托管目录"
  fi
}
trap cleanup EXIT
trap 'exit 143' TERM INT
android "touch $ref"

vd fake "$HERE/fake/image-refs.jsonl" >/dev/null
new_chat

# 一张 8×8 的 PNG 放进测试版自己的 files 目录（属主和 SELinux 标签照它的目录），经分享交给聊天页的输入栏
png=iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC
android "echo $png | base64 -d > $image && chown \$(stat -c %u:%g ${image%/*}) $image && restorecon $image"
android "am start --display $DISPLAY_ID -n $PKG/com.dzshzx.zeta.ui.MainActivity -a android.intent.action.SEND -t image/png \
--eu android.intent.extra.STREAM file://$image" >/dev/null
sleep 2
send "look"
wait_result 1 60 || { vd shot timeout.png >/dev/null; exit 1; }

if ! wait_text '^图片$' 15; then
  echo "聊天里没画出这张图：$(vd nodes '图片' | head -5)"
  vd shot no-image.png >/dev/null
  exit 1
fi
if [[ -n $(vd nodes '图片无法显示') ]]; then
  echo "这张图显示为取不到"
  vd shot broken-image.png >/dev/null
  exit 1
fi
echo "图画出来了"
