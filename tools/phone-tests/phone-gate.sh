#!/bin/bash
# 手机工具的闸门和后台屏幕的睡与醒。用假 Claude 的剧本 fake/phone-gate.jsonl 在一个对话里跑三轮：
#   1. 在后台屏幕上开系统设置，然后 Claude Code 进程意外退出：对话把后台屏幕留着、让它睡下；
#   2. 新进程接着会话再调 screen：后台屏幕被唤醒，结果里提一句“刚从休眠中唤醒”；
#   3. 三次 screen 之间剧本等脚本放的标记，脚本经顶部胶囊点“暂停”、再在展开画面上点“接管”，各放一次标记：两次都核对
#      下一次调用停在了闸门前（测试版的日志里有那一行、还没有结果），点“继续”后才执行、不出错。
# 胶囊上的按钮用 vd tap-text 按说明文字找；找不到时这一段写“没验到”，留下截图和当时读得到的控件，不算失败。
# 暂停、接管的闸门由 ControlGateTest 覆盖。
# 不等真模型，一分多钟：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/phone-gate.sh
# 假 Claude 不写会话记录，没有要删的测试会话；phone-run 收尾的 vd stop 删掉剧本和后台屏幕。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

# 这个脚本开始以来假 Claude 交回的工具结果数。按 tool_use_id 记：进程换了以后同一个对话的 out.jsonl 会重写，
# 不能直接数行，每次看到的新 id 都记进 gate-new-ids，开始时已有的（gate-base-ids）不算
ids() {
  android "cat $HOSTS/*/out.jsonl 2>/dev/null | grep '\"type\":\"tool_result\"' | grep -o '\"tool_use_id\":\"[^\"]*\"' | sort -u" | tr -d '\r' || true
}
results() {
  { ids | grep -vxF -f gate-base-ids || true; cat gate-new-ids; } | sort -u >gate-new-ids.tmp
  mv gate-new-ids.tmp gate-new-ids
  wc -l <gate-new-ids
}
# wait_results <至少几个> <最多等几秒>
wait_results() {
  local i
  for ((i = 0; i < $2 * 2; i++)); do
    (($(results) >= $1)) && return 0
    sleep 0.5
  done
  return 1
}
# 最后一个工具结果（最近写过的那份日志里）：文字和是否出错
last_result() {
  android "grep '\"type\":\"tool_result\"' \$(ls -t $HOSTS/*/out.jsonl | head -n 1) | tail -n 1" | jq -c "$RESULT_OF"
}

# ui_tap <说明或文字的正则> [expand]：点测试屏上第一个说明或文字对得上的控件，返回 0；找不到返回 1。
# 带 expand 时找不到就先点开胶囊再找一次
ui_tap() {
  vd tap-text "$1" >/dev/null && return 0
  [[ ${2:-} == expand ]] && vd tap-text '点按展开控制' >/dev/null || return 1
  sleep 1.5
  vd tap-text "$1" >/dev/null
}

vd fake "$HERE/fake/phone-gate.jsonl"
ids >gate-base-ids
: >gate-new-ids
base=0

# ---- 1、2：进程退出后后台屏幕睡下，再用时唤醒 ----
send gate-sleep
wait_results $((base + 1)) 30 || { vd shot sleep-timeout.png >/dev/null; echo "第一轮的 open_app 30 秒没有结果"; exit 1; }
[[ $(last_result | jq -r .error) == false ]] || { echo "open_app 出错：$(last_result)"; exit 1; }
sleep 5 # 进程退出，对话让后台屏幕睡下
send gate-wake
wait_results $((base + 2)) 30 || { vd shot wake-timeout.png >/dev/null; echo "第二轮的 screen 30 秒没有结果"; exit 1; }
woke=$(last_result)
echo "接着会话后的 screen：$(jq -r '.text[:120]' <<<"$woke")"
[[ $(jq -r .error <<<"$woke") == false && $(jq -r .text <<<"$woke") == *"刚从休眠中唤醒"* ]] ||
  { echo "后台屏幕没有睡下再唤醒（结果里没有“刚从休眠中唤醒”）"; exit 1; }
echo "后台屏幕睡下后再用时唤醒了"
sleep 3

# ---- 3：暂停、接管时下一次调用等着，继续后执行 ----
# 剧本在每次 screen 之前等脚本放的标记（{"wait":…}），不和固定的停顿赛跑：设备忙时找、点按钮慢多少都不影响结果。
# mark <名字>：放标记，剧本接着发出下一次调用
mark() { vd debian "touch ~/.config/zeta-dev/fake-claude/$1" >/dev/null; }
# gate_waiting <从什么时候起>：测试版的日志里有没有调用停在闸门前（ControlGate.await 打的那一行），最多等 30 秒
gate_waiting() {
  local i
  for ((i = 0; i < 60; i++)); do
    android "logcat -d -T '$1' --pid=\$(pidof $PKG) -s ZetaPhone:I" | grep -q 'gate: phone tool call waiting' && return 0
    sleep 0.5
  done
  return 1
}
# held <名字> <按钮正则> <已有几个结果> <标记>：点下按钮再放标记；下一次调用应该停在闸门前、没有结果；再点“继续”，它才执行
held() {
  local name=$1 button=$2 have=$3 since
  if ! ui_tap "$button" expand; then
    vd shot "$name-no-button.png" >/dev/null
    vd nodes >"$name-no-button.tsv" || true # 当时读得到的控件
    echo "$name：胶囊上没找到按钮（$button），这一段没验到"
    return 2
  fi
  since=$(android "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
  mark "$4"
  gate_waiting "$since" || { vd shot "$name.png" >/dev/null; echo "$name：放了标记 30 秒，下一次调用没有停在闸门前（结果数 $(results)，之前 $have）"; return 1; }
  vd shot "$name.png" >/dev/null
  if (($(results) > have)); then
    echo "$name：点了以后下一次调用照样执行了，闸门没拦住"
    return 1
  fi
  ui_tap '^继续$|交还给 Claude 继续' expand || { echo "$name：没找到“继续”"; return 1; }
  wait_results $((have + 1)) 30 || { echo "$name：点了继续，30 秒没有结果"; return 1; }
  [[ $(last_result | jq -r .error) == false ]] || { echo "$name：继续后的调用出错：$(last_result)"; return 1; }
  echo "$name：闸门拦住了下一次调用，继续后照常执行"
}

send gate-hold
wait_results $((base + 3)) 30 || { vd shot hold-timeout.png >/dev/null; echo "第三轮的第一次 screen 30 秒没有结果"; exit 1; }
status=0
held 暂停 '^暂停$' $((base + 3)) paused || status=$?
((status == 1)) && exit 1
if ((status == 0)); then
  held 接管 '^接管$' $((base + 4)) took || status=$?
  ((status == 1)) && exit 1
fi
((status == 0)) || { mark paused; mark took; } # 没验到时也让剧本走完
wait_results $((base + 5)) 40 || true
echo "三轮走完（暂停、接管那段的结果见上面）"
