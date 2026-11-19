#!/bin/bash
# 真机：search_personal 的 clipboard、wechat_images、qq_images，view_media 的 kind wechat（原图是 wxgf 的一张、只有缩略图的一张）、qq。
# 用假 Claude（tools/fake-claude）经 Zeta 真调手机工具，一轮几秒，不等真模型。编号在手机上现挑现算（只看文件名和文件头），
# 剧本按这次的编号生成。核对只从托管日志取每次调用是否出错、第一行、行数、括号里的提示和图片的类型与大小：
# 剪贴板和图片的内容不进产物（summary.txt），也不截图。测完删掉这次的对话目录（托管日志里有工具结果）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/clipboard-chat-images.sh
set -uo pipefail
HOSTS=/data/local/zeta/debian/rootfs/home/dzshzx/.cache/zeta-dev/hosts
ref=/data/local/tmp/zeta-clipboard-chat-images.ref
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548
user=$(android 'am get-current-user' | tr -d '\r')

mine="find $HOSTS -mindepth 1 -maxdepth 1 -type d -newer $ref"
cleanup() {
  android "am startservice -n $PKG/com.dzshzx.zeta.core.AgentService -a $PKG.action.STOP >/dev/null 2>&1"
  sleep 2
  android "for d in \$($mine); do rm -rf \$d && echo 删了对话目录; done; rm -f $ref"
  # 收尾的 final.png 不照到聊天里的工具结果：把 Zeta 压到后面
  android "am start --display $DISPLAY_ID -n com.android.traceur/.MainActivity >/dev/null 2>&1"
}
trap cleanup EXIT
trap 'exit 143' TERM INT
android "touch $ref"

# ---- 挑图、算编号（编号的算法同 ChatImages.wechatId、qqId）----
wx_root="/data/user/$user/com.tencent.mm/MicroMsg/*/image2"
wx_full=$(android "for f in \$(find $wx_root -name '*.jpg' -mmin -10080 2>/dev/null); do [ \"\$(head -c 4 \$f)\" = wxgf ] && { echo \${f##*/}; break; }; done" | tr -d '\r')
wx_thumb=$(android "for f in \$(find $wx_root -name 'th_*' ! -name '*hd' -mmin -10080 2>/dev/null); do b=\${f##*/th_}; case \$b in *[!0-9a-f]*) continue;; esac; [ -e \${f%/*}/\$b.jpg ] || [ -e \${f%/*}/\$b.png ] || [ -e \${f}hd ] || { echo \$b; break; }; done" | tr -d '\r')
qq_rel=$(android "cd /data/media/$user/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic && find chatimg chatraw -type f -mmin -43200 2>/dev/null | head -n 1" | tr -d '\r')
wx_id=${wx_full:+$((16#${wx_full:0:13}))}
wx_thumb_id=${wx_thumb:+$((16#${wx_thumb:0:13}))}
qq_id=''
if [[ -n $qq_rel ]]; then
  qq_id=$(python3 -I -c 'import hashlib,sys; r=sys.argv[1]; h=int.from_bytes(hashlib.sha1(r.encode()).digest()[:5],"big"); print((h<<12)|int(r.split("/")[1],16))' "$qq_rel")
fi
echo "挑到：微信 wxgf 原图 ${wx_id:-没有}，微信只有缩略图 ${wx_thumb_id:-没有}，QQ ${qq_id:-没有}"

# ---- 剧本：一轮里依次调用 ----
steps='[{"text":"开始。"}'
call() { steps+=",{\"phone\":\"$1\",\"input\":$2}"; }
call search_personal '{"source":"clipboard","limit":5}'
call search_personal '{"source":"clipboard","query":"zeta-probe-no-such-text"}'
call search_personal '{"source":"wechat_images","limit":5}'
call search_personal '{"source":"qq_images","limit":5}'
call search_personal '{"source":"wechat_images","query":"x"}'
[[ -n $wx_id ]] && call search_personal "{\"source\":\"wechat_images\",\"id\":$wx_id}"
[[ -n $wx_id ]] && call view_media "{\"kind\":\"wechat\",\"id\":$wx_id,\"max_edge\":512}"
[[ -n $wx_thumb_id ]] && call view_media "{\"kind\":\"wechat\",\"id\":$wx_thumb_id,\"max_edge\":512}"
[[ -n $qq_id ]] && call view_media "{\"kind\":\"qq\",\"id\":$qq_id,\"max_edge\":512}"
call view_media '{"kind":"qq","id":4095}'
steps+=',{"text":"完了。"}]'
printf '{"title":"剪贴板与聊天图片","steps":%s}\n' "$steps" > scenario.jsonl
calls=$(grep -o '"phone":' scenario.jsonl | wc -l)

vd fake "$PWD/scenario.jsonl"
vd tap $INPUT_X $INPUT_Y
sleep 1
vd text "fake-clipboard-chat-images"
sleep 0.5
vd tap $SEND_X $SEND_Y

log=''
result=''
for _ in $(seq 60); do
  sleep 2
  [[ -n $log ]] || log=$(android "ls -t \$(grep -l '\"fake_claude\":true' \$($mine | sed 's#\$#/out.jsonl#') 2>/dev/null) 2>/dev/null | head -n 1" | tr -d '\r')
  [[ -n $log ]] && result=$(android "grep '\"type\":\"result\"' $log | tail -n 1")
  [[ -n $result ]] && break
done
if [[ -z $result ]]; then
  echo "120 秒内没等到这一轮结束（日志：${log:-没有}）"
  exit 1
fi

# 每次调用一行：出错与否、文字的行数、第一行；看图的再加全部文字（只有编号、尺寸、格式）和图片的类型、base64 长度
android "grep '\"tool_result\"' $log" | jq -r '
  .message.content[0] as $r
  | if ($r.is_error // false) then "出错 | " + ($r.content | tostring | .[0:240])
    else ([$r.content[] | select(.type == "text") | .text] | join("\n") | split("\n")) as $t
      | ([$r.content[] | select(.type == "image") | "\(.mimeType) \(.data | length)"] | join(",")) as $img
      | "成功 | \($t | length) 行 | \($t[0])"
        + ([$t[] | select(startswith("（"))] | if length > 0 then " | 提示：" + join(" ") else "" end)
        + (if $img != "" then " | " + ($t[1:] | join(" / ")) + " | 图片 " + $img else "" end)
    end' > summary.txt
cat summary.txt
echo "这一轮：$(jq -r '"subtype=\(.subtype) is_error=\(.is_error)"' <<<"$result")"

fail=0
[[ $(wc -l < summary.txt) -eq $calls ]] || { echo "调用 $calls 次，结果 $(wc -l < summary.txt) 条"; fail=1; }
check() { grep -q -- "$1" summary.txt || { echo "缺：$2"; fail=1; }; }
check '^成功 | .* | 剪贴板历史 [0-9]* 条' "剪贴板列表"
check '^成功 | 1 行 | 没有符合条件的剪贴板记录' "剪贴板查不到时说没有"
check '^成功 | .* | 微信图片 [0-9]* 条' "微信图片列表"
check '^成功 | .* | QQ 图片 [0-9]* 条' "QQ 图片列表"
check '^出错 | wechat_images 只能按时间查' "微信图片拒绝 query"
check '^出错 | 没有编号为 4095 的 QQ 图片' "QQ 编号不存在时报错"
[[ -n $wx_id ]] && check "^成功 | .* | 微信图片 #$wx_id .*原图.*wxgf.*image/jpeg" "wxgf 原图转成 JPEG"
[[ -n $wx_thumb_id ]] && check "^成功 | .* | 微信图片 #$wx_thumb_id .*缩略图.*image/jpeg" "只有缩略图的微信图"
[[ -n $qq_id ]] && check "^成功 | .* | QQ 图片 #$qq_id .*image/jpeg" "QQ 图"
exit $fail
