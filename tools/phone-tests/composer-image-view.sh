#!/bin/bash
# 输入栏里待发的图片点一下能全屏看：经分享把一张 PNG 放进输入栏，点它的缩略图，核对看图页开了（底栏有「保存到相册」），
# 返回后缩略图还在输入栏里（没被当成移除）。再点移除：复制进 Debian 的那份（~/.cache/zeta-dev/files/<目录>/）跟着删掉。
# 不发消息，不起对话。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/composer-image-view.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

name=zeta-composer-view.png
image=/data/user/0/$PKG/files/$name
trap 'android "rm -f $image"' EXIT
trap 'exit 143' TERM INT
# 这张图复制进 Debian 后在哪些目录里（一行一个）
FILES=${HOSTS%/hosts}/files
copies() { android "ls -d $FILES/*/$name 2>/dev/null; true" | tr -d '\r'; }
# 以前的测试留下的同名副本先清掉，下面按「有没有」判断
android "for f in $FILES/*/$name; do [ -f \"\$f\" ] && rm -rf \"\${f%/*}\"; done; true"

new_chat

# 一张 8×8 的 PNG 放进测试版自己的 files 目录（属主和 SELinux 标签照它的目录），经分享交给聊天页的输入栏
png=iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC
android "echo $png | base64 -d > $image && chown \$(stat -c %u:%g ${image%/*}) $image && restorecon $image"
android "am start --display $DISPLAY_ID -n $PKG/com.dzshzx.zeta.ui.MainActivity -a android.intent.action.SEND -t image/png \
--eu android.intent.extra.STREAM file://$image" >/dev/null

# 缩小图做好之前输入栏里先是带文件名的小标签（名字是文字），做好了才是缩略图（名字是说明，第四列）：等到缩略图再点
thumbnail() {
  local nodes
  nodes=$(vd nodes "^$name\$")
  [[ -n $(awk -F'\t' '$4 != ""' <<<"$nodes") ]]
}
for ((i = 0; i < 30; i++)); do
  thumbnail && break
  sleep 0.5
done
if ! thumbnail; then
  echo "输入栏里没出现这张图的缩略图：$(vd nodes | head -20)"
  vd shot no-thumbnail.png >/dev/null
  exit 1
fi
vd tap-text "^$name\$" >/dev/null
if ! wait_text '^保存到相册$' 10; then
  echo "点了缩略图没开看图页：$(vd nodes | head -20)"
  vd shot no-viewer.png >/dev/null
  exit 1
fi
vd shot viewer.png >/dev/null
vd key KEYCODE_BACK >/dev/null
# 看图页淡出要一会儿：等它没了再看输入栏
for ((i = 0; i < 20; i++)); do
  [[ -z $(vd nodes '^保存到相册$') ]] && break
  sleep 0.5
done
if [[ -n $(vd nodes '^保存到相册$') ]] || ! thumbnail; then
  echo "返回后不在输入栏：$(vd nodes | head -20)"
  vd shot after-back.png >/dev/null
  exit 1
fi
# 复制进 Debian 的那份到了，再把这张图从输入栏拿掉（不留在草稿里）：那份跟着删掉
for ((i = 0; i < 30; i++)); do
  [[ -n $(copies) ]] && break
  sleep 0.5
done
if [[ -z $(copies) ]]; then
  echo "这张图没复制进 Debian：$(android "ls $FILES | tail -5")"
  exit 1
fi
for ((i = 0; i < 5; i++)); do
  [[ -z $(vd nodes '^移除$') ]] && break
  vd tap-text '^移除$' >/dev/null
  sleep 0.5
done
if [[ -n $(vd nodes '^移除$') ]]; then
  echo "输入栏里的图没移掉：$(vd nodes | head -20)"
  exit 1
fi
for ((i = 0; i < 20; i++)); do
  [[ -z $(copies) ]] && break
  sleep 0.5
done
if [[ -n $(copies) ]]; then
  echo "移除后 Debian 里那份还在：$(copies)"
  exit 1
fi
echo "待发的图片点开看了，返回后还在输入栏；移除后复制进 Debian 的那份删掉了"
