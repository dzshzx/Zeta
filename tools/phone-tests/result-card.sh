#!/bin/bash
# 结果卡片：Claude 在后台屏上用过手机工具、一轮结束时 Zeta 不在前台，测试屏底部弹出结果卡片，完成通知归进不提醒的组；
# 再按 CLOSE 关掉它：open（默认，点“回到对话”，Zeta 回到前台、停在这个对话上）、swipe（从标题行往下拖）、back（返回键）。
# 悬浮层画在测试屏上（phoneDisplay 指到 $DISPLAY_ID），不碰真屏。Claude 真跑一轮，约 1–3 分钟：
#   ZETA_PHONE_OWNER=<任务名> ZETA_PHONE_RUN_TIMEOUT=300 [CLOSE=swipe] tools/phone-run tools/phone-tests/result-card.sh
# 截图（sent.png、card.png、after-*.png、back.png）在产物目录里，看卡片的位置、样子和回到的是不是这个对话。
# 测试建的会话按标记找出来，收尾时删掉。手机工具要已在 Claude Code 的设置里放行，否则这一轮会停在“需要你确认”上。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

MARK="zeta-result-card-test-$(date +%s)"
CLOSE=${CLOSE:-open}
CLAUDE_HOME=/data/local/zeta/debian/rootfs/home/$ZETA_LINUX_USER/.claude

cleanup() {
  set +e
  android "am force-stop $PKG"
  # 测试建的会话：记录文件和同名的附属目录
  android "for f in \$(grep -l '$MARK' $CLAUDE_HOME/projects/*/*.jsonl 2>/dev/null); do echo \"删掉测试会话 \$(basename \$f)\"; rm -rf \"\$f\" \"\${f%.jsonl}\"; done"
}
trap cleanup EXIT

# 悬浮层的“主屏”phone-run 已经指到测试屏（phoneDisplay）

W=1220
H=2656

card_frame() {
  # 卡片窗口的范围：l t r b（没有就空）。awk 读完全部输入再退出：提前 exit 会写断 vd 的管道（退出码 120）
  android "dumpsys window windows" | awk '/Window\{.*ZetaResultCard/ { found = 1 } found && !done && /frame=\[/ { match($0, /frame=\[[-0-9]+,[-0-9]+\]\[[-0-9]+,[-0-9]+\]/); f = substr($0, RSTART + 6, RLENGTH - 6); gsub(/[\[\]]/, " ", f); gsub(/,/, " ", f); print f; done = 1 }'
}

send_turn() {
  send "$1" 1
  sleep 2
  vd shot sent.png
  # Zeta 退到后台：测试屏上开另一个应用压在上面（NEW_TASK | EXCLUDE_FROM_RECENTS：不在用户的「最近任务」里留记录）
  android "am start --display $DISPLAY_ID -f 0x10800000 -n com.android.traceur/.MainActivity >/dev/null 2>&1"
}

wait_card() {
  local frame=''
  for _ in $(seq 1 60); do
    frame=$(card_frame)
    [[ -n $frame ]] && break
    sleep 2
  done
  if [[ -z $frame ]]; then
    echo "等了约 3 分钟没等到结果卡片"
    vd shot no-card.png
    # 进程、无障碍服务还在不在，系统有没有杀掉或冻结它；再把 Zeta 拉回来看这一轮停在哪
    echo "进程：$(android "pidof $PKG" || echo 不在)"
    echo "无障碍服务启用着：$(android "settings get secure enabled_accessibility_services" | tr ':' '\n' | grep -c "$PKG/" || true)"
    android "logcat -d -t 3000 | grep -E '$PKG' | grep -iE 'kill|died|freez|greeze|frozen|anr|unbind|disconnect' | tail -20" || true
    vd launch
    sleep 3
    vd shot no-card-chat.png
    exit 1
  fi
  sleep 1.5
  vd shot card.png
  echo "卡片的窗口：$frame"
  FRAME=($frame)
}

gone() {
  sleep 1.5
  [[ -z $(card_frame) ]] || { echo "卡片没收起（$1）"; vd shot "after-$1.png"; exit 1; }
  vd shot "after-$1.png"
  echo "卡片已收起（$1）"
}

silent_notice() {
  # 完成通知（id 2）还在通知栏里，但归在不提醒的组里（弹了卡片就不响铃）。只看键、组这几项，不看通知内容
  android "dumpsys notification" >notifications.txt || true
  echo "完成通知的记录："
  grep -A40 "NotificationRecord.*$PKG.*id=2 " notifications.txt | grep -E "NotificationRecord|groupKey|mGroup|group=|gr[ou]*pAlert|flags=" | head -12 || true
  rm -f notifications.txt
}

# 一轮：在后台屏上开计算器
send_turn "$MARK:%sUse%sthe%sphone%stools%sto%sopen%sthe%sCalculator%sapp%son%sthe%sbackground%sscreen,%sthen%sreply%swith%sone%sshort%ssentence." x
wait_card x
silent_notice
case $CLOSE in
  swipe)
    vd swipe $((W / 2)) $((FRAME[1] + 150)) $((W / 2)) $((H - 20)) 250
    gone swipe
    ;;
  back)
    vd key 4
    gone back
    ;;
  *)
    # 按钮行：卡片下沿往上 12dp 边距 + 20dp 留白 + 半个按钮（22dp），520dpi 下 1dp = 3.25px；“回到对话”在右半边
    vd tap $((W * 3 / 4)) $((FRAME[3] - 176))
    gone open
    sleep 2
    vd shot back.png
    echo "前台：$(resumed)"
    ;;
esac
