#!/bin/bash
# 手机工具的几样：app_control 的 install、clear_data、uninstall（含 PROTECTED 拒绝），tap 的 count（双击），
# swipe 的 hold_ms（拖动），screen_record（后台屏幕由 Zeta 自己编码，主屏用系统的 screenrecord）。
# 拿现编的探针应用 com.dzshzx.zeta.probe（probe/：整屏一行字，数单击、双击和拖动）试，假 Claude 经 Zeta 真调手机工具：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/phone-tool-gaps.sh
# 后台屏幕的录像取回本机：recording.mp4 和从中截的 recording-*.png。最后一段把 Zeta 的「主屏」临时指到 0 号屏
# 录一段主屏（只录、不动它），所以只在模拟器上跑；vd stop（phone-run 收尾）改回设置。
set -uo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
[[ $ZETA_DEVICE == emu ]] || { echo "最后一段要录 0 号屏，只在模拟器上跑"; exit 2; }
PROBE=com.dzshzx.zeta.probe
FAILED=''
fail() { echo "不对：$*"; FAILED=1; }
# expect <名字> <第几个> <true|false 是否出错> <要含的字>：核对 fake_turn 那一轮的一个工具结果
expect() {
  local text error
  text=$(result_text "$1" "$2")
  error=$(result_error "$1" "$2")
  if [[ $error == "$3" && $text == *"$4"* ]]; then
    echo "  ok $1#$2：$(head -c 160 <<<"$text" | tr '\n' ' ')"
  else
    fail "$1#$2 要${4}（出错=$3），实际（出错=$error）：$(head -c 400 <<<"$text" | tr '\n' ' ')"
  fi
}
cleanup() {
  android "pm uninstall $PROBE >/dev/null 2>&1; true" >/dev/null
  vd debian "rm -f /tmp/zeta-probe.apk" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$HERE/probe/build.sh" "$PWD/probe.apk" >/dev/null || { echo "探针应用没编出来"; exit 1; }
cleanup
# Claude 给的是它在 Linux 里看到的路径：放在 Debian 的 /tmp（Android 一侧在 rootfs 里）
vd debian --stdin 'cat > /tmp/zeta-probe.apk' <probe.apk

echo "== 安装、打开"
fake_turn install 装探针 "$(jq -nc --arg p $PROBE '[
  {phone: "app_control", input: {action: "install", path: "/tmp/zeta-probe.apk"}},
  {phone: "find_apps", input: {query: "Zeta Probe"}},
  {phone: "open_app", input: {app: $p}}]')" || { echo "安装这一轮没跑完"; exit 1; }
expect install 1 false "installed Zeta Probe ($PROBE) version 1.0"
expect install 2 false "$PROBE"
expect install 3 false "probe taps=0 doubles=0 drags=0"
size=$(result_text install 3 | grep -o '[0-9]*×[0-9]*（截图坐标）' | head -n 1)
w=${size%%×*} h=${size#*×} h=${h%%（*}
[[ -n $w && -n $h ]] || { echo "读不出后台屏幕的大小：$(result_text install 3 | head -n 1)"; exit 1; }
cx=$((w / 2)) cy=$((h / 2))
echo "  后台屏幕 ${w}×${h}（截图坐标）"

echo "== 录屏、双击、拖动"
fake_turn gestures 双击拖动录屏 "$(jq -nc --argjson cx $cx --argjson cy $cy '[
  {phone: "screen_record", input: {action: "start", time_limit_seconds: 30}},
  {pause: 800},
  {phone: "tap", input: {match: "probe taps", count: 2}},
  {pause: 600},
  {phone: "swipe", input: {x1: $cx, y1: ($cy - 250), x2: $cx, y2: ($cy + 250), duration_ms: 500, hold_ms: 800}},
  {pause: 800},
  {phone: "screen_record", input: {action: "stop"}}]')" || { echo "手势这一轮没跑完"; exit 1; }
expect gestures 1 false "开始录屏：后台屏幕"
# 双击被认成双击，不是两次单击
expect gestures 2 false "probe taps=0 doubles=1 drags=0"
expect gestures 3 false "doubles=1 drags=1"
expect gestures 4 false "录屏已结束："
video=$(result_text gestures 4 | grep -o '/home/[^（]*\.mp4' | head -n 1)
if [[ -n $video ]]; then
  echo "  录像：$video，Linux 里：$(vd debian "stat -c '%U %a %s 字节' '$video'")"
  vd debian "base64 -w0 '$video'" | base64 -d >recording.mp4
  echo "  $(ffprobe -v error -show_entries stream=codec_name,width,height,nb_frames:format=duration -of compact=p=0:nk=0 recording.mp4 | tr '\n' ' ')"
  for t in 0.5 1.6 3.5; do ffmpeg -v error -y -ss "$t" -i recording.mp4 -frames:v 1 "recording-$t.png" || true; done
  [[ -s recording.mp4 ]] || fail "取回的录像是空的"
  vd debian "rm -rf '${video%/*}'" >/dev/null
else
  fail "结果里没有录像的路径"
fi

echo "== 清除数据、主屏是测试屏时录不了"
before=$(android "ls /data/user/0/$PROBE/files 2>/dev/null" | tr -d '\r')
[[ $before == *opened.txt* ]] || fail "探针启动后没在 files/ 里留下 opened.txt：$before"
fake_turn clear 清数据 "$(jq -nc --arg p $PROBE '[
  {phone: "app_control", input: {action: "clear_data", package: $p}},
  {phone: "screen", input: {display: "main"}},
  {phone: "screen_record", input: {action: "start"}},
  {phone: "screen_record", input: {action: "stop"}}]')" || { echo "清数据这一轮没跑完"; exit 1; }
expect clear 1 false "cleared data of Zeta Probe ($PROBE)"
after=$(android "ls /data/user/0/$PROBE/files 2>/dev/null" | tr -d '\r')
[[ $after != *opened.txt* ]] || fail "清除数据后 opened.txt 还在"
expect clear 3 true "主屏现在指到了 display"
expect clear 4 true "现在没有在录屏"

echo "== 卸载和拒绝"
fake_turn remove 卸载 "$(jq -nc --arg p $PROBE --arg self "$PKG" '[
  {phone: "app_control", input: {action: "uninstall", package: $p}},
  {phone: "app_control", input: {action: "uninstall", package: $p}},
  {phone: "app_control", input: {action: "uninstall", package: $self}},
  {phone: "app_control", input: {action: "clear_data", package: "com.android.systemui"}},
  {phone: "app_control", input: {action: "install", path: "/tmp/zeta-nope.apk"}},
  {phone: "tap", input: {x: 1, y: 1, count: 5}}]')" || { echo "卸载这一轮没跑完"; exit 1; }
expect remove 1 false "uninstalled Zeta Probe ($PROBE)"
expect remove 2 true "没有安装 $PROBE"
expect remove 3 true "离不开的包，不卸载"
expect remove 4 true "离不开的包，不清除数据"
expect remove 5 true "手机上没有这个文件：/tmp/zeta-nope.apk"
expect remove 6 true "count 只能是 1 到 3"
[[ -z $(android "pm list packages $PROBE" | tr -d '\r') ]] || fail "卸载后 $PROBE 还在"

echo "== 主屏录屏（0 号屏，到时限自己停）"
vd prefs-set phoneDisplay:int=0 >/dev/null
vd launch >/dev/null
sleep 10
android "touch /data/local/tmp/zeta-gaps.mark"
# shellcheck disable=SC2034 # lib.sh 的 find_log、fake_turn 读它们
NEWER=/data/local/tmp/zeta-gaps.mark log='' FAKE_TURNS=0
new_chat
fake_turn main 录主屏 "$(jq -nc '[
  {phone: "screen", input: {display: "main"}},
  {phone: "screen_record", input: {action: "start", time_limit_seconds: 3}},
  {pause: 4500},
  {phone: "screen_record", input: {action: "stop"}}]')" || { echo "主屏录屏这一轮没跑完"; exit 1; }
expect main 2 false "开始录屏：主屏"
expect main 3 false "到了时限自己停的"
video=$(result_text main 3 | grep -o '/home/[^（]*\.mp4' | head -n 1)
if [[ -n $video ]]; then
  vd debian "base64 -w0 '$video'" | base64 -d >main.mp4
  # screenrecord 只在画面变了时出帧：0 号屏没人动时可能只有一帧
  echo "  主屏录像：$(ffprobe -v error -select_streams v -show_entries stream=codec_name,width,height,nb_frames -of compact=p=0:nk=0 main.mp4 | tr '\n' ' ')"
  ffmpeg -v error -y -i main.mp4 -frames:v 1 main-0.png || fail "主屏录像里没有画面"
  vd debian "rm -rf '${video%/*}'" >/dev/null
else
  fail "主屏录屏的结果里没有路径"
fi
android "rm -f /data/local/tmp/zeta-gaps.mark"

[[ -z $FAILED ]] && echo "全部对上" || exit 1
