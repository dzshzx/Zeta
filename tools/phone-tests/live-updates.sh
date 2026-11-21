#!/bin/bash
# 实时通知（Live Updates）：测试版跑一轮回答，期间每隔几秒看 dumpsys，系统有没有把常驻通知提升
# （记录的 flags 里有 PROMOTED_ONGOING），状态栏小胶囊的字、有任务清单时的进度条；一轮结束后退回普通常驻通知。
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

# 只留本包的通知记录：dumpsys 里还有别的应用的通知内容，不落盘
ours() {
  android "dumpsys notification --noredact" | awk -v p="pkg=$PKG " '
    /NotificationRecord\(/ { keep = index($0, p) > 0; ind = match($0, /[^ ]/); if (keep) print; next }
    keep { if (match($0, /[^ ]/) <= ind) keep = 0; else print }'
}

# 一条记录的摘要：是否提升、样式和进度、标题、正文、小胶囊的字。flags 取记录自己那一行
# （dumpsys 按名字列出，如 ONGOING_EVENT|FOREGROUND_SERVICE|PROMOTED_ONGOING；别的行也有 flags=，不能取第一个）
summary() {
  local f=$1 flags
  flags=$(grep -m1 -E '^ +flags=' "$f" | sed 's/^ *flags=//' || true)
  [[ -n $flags ]] || { echo "没有本包的通知"; return; }
  local promoted=否
  if [[ $flags == *PROMOTED_ONGOING* ]] || { [[ $flags == 0x* ]] && ((flags & PROMOTED)); }; then promoted=是; fi
  printf '提升=%s 申请=%s %s %s | %s | %s | 小胶囊=%s\n' "$promoted" \
    "$(grep -m1 -oE 'android\.requestPromotedOngoing=[^ ]+ \([a-z]+\)' "$f" | grep -oE '(true|false)' || echo 无)" \
    "$(grep -m1 -oE 'android\.template=String \([^)]*\)' "$f" | grep -oE '[A-Za-z]+Style' || echo 无样式)" \
    "$(grep -m1 -oE 'android\.progress=Integer \([0-9]+\)' "$f" | grep -oE '[0-9]+' || true)/$(grep -m1 -oE 'android\.progressMax=Integer \([0-9]+\)' "$f" | grep -oE '[0-9]+' || true)" \
    "$(grep -m1 -oE 'android\.title=.*' "$f" || true)" \
    "$(grep -m1 -oE 'android\.text=.*' "$f" || true)" \
    "$(grep -m1 -oE 'android\.shortCriticalText=.*' "$f" | sed 's/.*String (\(.*\))$/\1/' || true)"
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

android "dumpsys package $PKG" | grep -E 'POST_PROMOTED_NOTIFICATIONS|POST_NOTIFICATIONS' | sed 's/^ *//' | sort -u || true
ours >notif-before.txt
echo "发消息前：$(summary notif-before.txt)"

# 用任务清单工具（不要权限确认）分三项写，通知上能看到进度条；不用别的工具
send "Use your task list tools to create 3 tasks, then do them one at a time and mark each completed. Each task: write one short sentence about a lighthouse. Use no other tools. Tag: $MARK" 1

seen=0 progress=0 ended=0 i=0
start=$SECONDS
while ((SECONDS - start < LOOP_SECONDS)); do
  sleep 3
  i=$((i + 1))
  ours >"notif-$i.txt"
  line=$(summary "notif-$i.txt")
  echo "$i: $line"
  [[ $i == 4 ]] && vd shot mid.png >/dev/null
  if [[ $line == *提升=是* ]]; then seen=$((seen + 1)); fi
  if [[ $line == *提升=是*ProgressStyle* ]]; then progress=$((progress + 1)); fi
  # 一轮结束：标题回到“会话保持中”
  if [[ $line == *会话保持中* ]]; then
    ended=1
    break
  fi
done

((ended)) || echo "一轮没在 $LOOP_SECONDS 秒内结束，收尾时叫停"
ours >notif-after.txt
after=$(summary notif-after.txt)
echo "结束后：$after"
echo "提升过的次数：$seen，其中带进度条的：$progress"
((seen > 0)) || { echo "系统一次也没有提升这条通知"; exit 1; }
if ((ended)) && [[ $after == *提升=是* ]]; then
  echo "一轮结束后还是实时通知"
  exit 1
fi
