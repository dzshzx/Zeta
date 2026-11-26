#!/bin/bash
# 对话那条通知（id=2）上的当前步骤：测试版跑一轮回答、发完消息压到后台（看着这个对话时不发），期间每隔几秒看 dumpsys，
# 那条通知有没有带超级岛的焦点参数（认 miui.focus.pics：模拟器的 dumpsys 不列出 miui.focus.param 这个 JSON 字符串，三项是一起写的）、
# 大岛左边的短词、有任务清单时的进度条；一轮结束后同一条换成“已完成”。前台服务那条（id=1）任何时候都不上岛；
# 哪条都不该带 Android 16 的实时通知标记（flags 里的 PROMOTED_ONGOING，见 docs/assistant.md「超级岛」）。
# 只用任务清单工具、不碰主屏。不论正常结束还是被打断，收尾都叫停这一轮、删掉带本脚本记号的测试会话
# （也删以前被打断的几次留下的），不碰别的会话。超级岛、锁屏上的样子要人看。
# 用法：ZETA_PHONE_OWNER=<任务名> ZETA_PHONE_RUN_TIMEOUT=300 tools/phone-run tools/phone-tests/live-updates.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
MARK=$(printf 'zlive%05d%05d' $RANDOM $RANDOM)
# 认测试会话的记号：提示里的“Tag: zlive”加至少四位数字（脚本自己的源码对不上这个式子）
MARKED='Tag: zlive[0-9]{4,}'
PROJECTS=/data/local/zeta/debian/rootfs/home/$ZETA_LINUX_USER/.claude/projects
PROMOTED=$((0x40000))
LOOP_SECONDS=190

# 一条记录的摘要：带没带焦点参数、有没有实时通知标记、样式和进度、标题、正文、大岛左边的字。flags 取记录自己那一行
# （dumpsys 按名字列出，如 ONGOING_EVENT|FOREGROUND_SERVICE|PROMOTED_ONGOING；别的行也有 flags=，不能取第一个）
summary() {
  local f=$1 flags
  flags=$(grep -m1 -E '^ +flags=' "$f" | sed 's/^ *flags=//' || true)
  [[ -n $flags ]] || { echo "没有本包的通知"; return; }
  local promoted=否 island=否
  if [[ $flags == *PROMOTED_ONGOING* ]] || { [[ $flags == 0x* ]] && ((flags & PROMOTED)); }; then promoted=是; fi
  grep -q 'miui\.focus\.pics=' "$f" && island=是
  printf '上岛=%s 实时标记=%s %s %s | %s | %s | 大岛左边=%s\n' "$island" "$promoted" \
    "$(grep -m1 -oE 'android\.template=String \([^)]*\)' "$f" | grep -oE '[A-Za-z]+Style' || echo 无样式)" \
    "$(grep -m1 -oE 'android\.progress=Integer \([0-9]+\)' "$f" | grep -oE '[0-9]+' || true)/$(grep -m1 -oE 'android\.progressMax=Integer \([0-9]+\)' "$f" | grep -oE '[0-9]+' || true)" \
    "$(grep -m1 -oE 'android\.title=.*' "$f" || true)" \
    "$(grep -m1 -oE 'android\.text=.*' "$f" || true)" \
    "$(grep -m1 -oE '"aodTitle":"[^"]*"' "$f" | sed 's/.*:"\(.*\)"$/\1/' || true)"
}

# 收尾：叫停还在跑的这一轮（免得删了又写出来），等起标题写完，删掉带记号的测试会话
cleanup() {
  set +e
  trap - EXIT
  android "am startservice -n $PKG/com.dzshzx.zeta.core.AgentService -a $PKG.action.STOP" >/dev/null 2>&1
  sleep 8
  local f
  for f in $(android "grep -lE '$MARKED' $PROJECTS/*/*.jsonl 2>/dev/null"); do
    android "rm -f '$f'; rm -rf '${f%.jsonl}'"
    echo "删掉了测试会话 $(basename "$f")"
  done
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

android "dumpsys package $PKG" | grep -E 'POST_NOTIFICATIONS' | sed 's/^ *//' | sort -u || true
notice 2 >notif-before.txt
echo "发消息前：$(summary notif-before.txt)"

# 用任务清单工具（不要权限确认）分三项写，通知上能看到进度条；不用别的工具
send "Use your task list tools to create 3 tasks, then do them one at a time and mark each completed. Each task: write one short sentence about a lighthouse. Use no other tools. Tag: $MARK" 1
background

seen=0 progress=0 promoted=0 service=0 ended=0 i=0
start=$SECONDS
while ((SECONDS - start < LOOP_SECONDS)); do
  sleep 3
  i=$((i + 1))
  ours >"all-$i.txt"
  pick 2 <"all-$i.txt" >"notif-$i.txt"
  pick 1 <"all-$i.txt" >"service-$i.txt"
  line=$(summary "notif-$i.txt")
  echo "$i: $line"
  if grep -q 'miui\.focus\.pics=' "service-$i.txt"; then service=$((service + 1)); fi
  if grep -q PROMOTED_ONGOING "service-$i.txt"; then promoted=$((promoted + 1)); fi
  [[ $i == 4 ]] && vd shot mid.png >/dev/null
  if [[ $line == *上岛=是* ]]; then seen=$((seen + 1)); fi
  if [[ $line == *上岛=是*ProgressStyle* ]]; then progress=$((progress + 1)); fi
  if [[ $line == *实时标记=是* ]]; then promoted=$((promoted + 1)); fi
  # 一轮结束：同一条换成结束的那句（已完成、出错了、已中断）
  if [[ $line =~ android\.text=String\ \((已完成|出错了|已中断)\) ]]; then
    ended=1
    break
  fi
done

((ended)) || echo "一轮没在 $LOOP_SECONDS 秒内结束，收尾时叫停"
notice 2 >notif-after.txt
after=$(summary notif-after.txt)
echo "结束后：$after"
echo "上岛的次数：$seen，其中带进度条的：$progress"
((promoted == 0)) || { echo "通知带了实时通知标记 $promoted 次"; exit 1; }
((service == 0)) || { echo "保活那条带了焦点参数 $service 次"; exit 1; }
((seen > 0)) || { echo "对话那条一次也没有带焦点参数"; exit 1; }
((ended)) || exit 1
[[ $after == *'android.text=String (已完成)'* ]] || { echo "这一轮没正常答完（看测试版的 out.jsonl，多半是账号或网络）"; exit 1; }
