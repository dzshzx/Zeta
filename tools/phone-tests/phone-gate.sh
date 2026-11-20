#!/bin/bash
# 手机工具的闸门和后台屏幕的睡与醒。用假 Claude 的剧本 fake/phone-gate.jsonl 在一个对话里跑三轮：
#   1. 在后台屏幕上开系统设置，然后 Claude Code 进程意外退出：对话把后台屏幕留着、让它睡下；
#   2. 新进程接着会话再调 screen：后台屏幕被唤醒，结果里提一句“刚从休眠中唤醒”；
#   3. 三次 screen 之间停顿，脚本经顶部胶囊点“暂停”、再在展开画面上点“接管”：两次都核对下一次调用在闸门前等着
#      （停顿过了也没有结果），点“继续”后才执行、不出错。
# 胶囊上的按钮用 uiautomator 按说明文字找（测试屏上的悬浮层）；找不到时这一段写“没验到”，不算失败。
# 模拟器上 uiautomator dump --windows 导不出无障碍服务画的悬浮窗（测试屏上只有 Zeta 自己的窗口），这一段在模拟器上总是“没验到”；
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
  android "grep '\"type\":\"tool_result\"' \$(ls -t $HOSTS/*/out.jsonl | head -n 1) | tail -n 1" | jq -c "{text: ($TEXT_OF), error: (.message.content[0].is_error // false)}"
}

# ui_find <说明或文字的正则>：测试屏上（含悬浮层）第一个 content-desc 或 text 对得上的控件的中心，“x y”；找不到时空
ui_find() {
  android "rm -f /data/local/tmp/zeta-ui.xml; uiautomator dump --windows /data/local/tmp/zeta-ui.xml >/dev/null 2>&1 || uiautomator dump /data/local/tmp/zeta-ui.xml >/dev/null 2>&1; cat /data/local/tmp/zeta-ui.xml 2>/dev/null; rm -f /data/local/tmp/zeta-ui.xml" |
    python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
want, display = re.compile(sys.argv[1]), sys.argv[2]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(0)
def walk(e, d):
    if e.tag == "display":
        d = e.get("id")
    for k in ("content-desc", "text"):
        if want.search(e.get(k) or "") and (d is None or d == display):
            m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", e.get("bounds") or "")
            if m:
                l, t, r, b = map(int, m.groups())
                print((l + r) // 2, (t + b) // 2)
                sys.exit(0)
    for c in e:
        walk(c, d)
walk(root, None)
' "$1" "$DISPLAY_ID"
}
# ui_tap <正则> [要先展开胶囊]：找到就点，返回 0；找不到返回 1
ui_tap() {
  local at
  at=$(ui_find "$1")
  if [[ -z $at && ${2:-} == expand ]]; then
    at=$(ui_find '点按展开控制') && [[ -n $at ]] && { vd tap $at >/dev/null; sleep 1.5; at=$(ui_find "$1"); }
  fi
  [[ -n $at ]] || return 1
  vd tap $at >/dev/null
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
# held <名字> <按钮正则> <已有几个结果>：点下按钮，等过剧本里 15 秒的停顿，下一次调用应该还没有结果；再点“继续”，它才执行
held() {
  local name=$1 button=$2 have=$3
  if ! ui_tap "$button" expand; then
    vd shot "$name-no-button.png" >/dev/null
    echo "$name：胶囊上没找到按钮（$button），这一段没验到"
    return 2
  fi
  sleep 17
  vd shot "$name.png" >/dev/null
  if (($(results) > have)); then
    echo "$name：点了以后下一次调用照样执行了，闸门没拦住"
    return 1
  fi
  ui_tap '^继续$|交还给 Claude 继续' expand || { echo "$name：没找到“继续”"; return 1; }
  wait_results $((have + 1)) 15 || { echo "$name：点了继续，15 秒没有结果"; return 1; }
  [[ $(last_result | jq -r .error) == false ]] || { echo "$name：继续后的调用出错：$(last_result)"; return 1; }
  echo "$name：闸门拦住了下一次调用，继续后照常执行"
}

send gate-hold
wait_results $((base + 3)) 30 || { vd shot hold-timeout.png >/dev/null; echo "第三轮的第一次 screen 30 秒没有结果"; exit 1; }
status=0
held 暂停 '^暂停$' $((base + 3)) || status=$?
((status == 1)) && exit 1
if ((status == 0)); then
  held 接管 '^接管$' $((base + 4)) || status=$?
  ((status == 1)) && exit 1
fi
wait_results $((base + 5)) 40 || true
echo "三轮走完（暂停、接管那段的结果见上面）"
