#!/bin/bash
# 进程里没有任何界面时悬浮层照样出来：测试版被 force-stop 后不打开界面，以 root 直接发定时任务的到点广播，
# 在后台跑一轮假 Claude（剧本 fake/overlays-cold.jsonl：先在主屏上开系统设置，再在后台屏幕上开，然后结束，两步后面各等脚本放的标记；
# 不写 display 时 open_app 默认开在后台屏幕上，边缘流光只在主屏操作时出现，所以第一步写明 main）。
# 期间测试屏上应先后出现顶部胶囊、边缘流光（窗口标题 ZetaCapsule、ZetaEdgeGlow），一轮结束后有结果卡片（ZetaResultCard），
# 全程没有 Zeta 的 Activity 窗口。后台运行的对话跑完几秒就被收掉，托管日志也随之删掉，所以出错时看运行记录和系统日志。
# 这时悬浮层收得到状态变化全靠无障碍服务连上时挂的快照通知（ui/overlay/SnapshotNotifier.kt）；
# force-stop 让系统把无障碍服务从启用列表里拿掉，Zeta 起来时由 AccessibilityGuard 经 root 加回去。几十秒：
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/overlays-cold.sh
# 定时任务用 prefs-set 放进去，收尾的 vd stop 改回去；假 Claude 不写会话记录。截图 glow.png、card.png 在产物目录里。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
ID=ztocold
MARK=/data/local/tmp/zeta-overlays-cold.mark
trap 'android "rm -f $MARK" >/dev/null 2>&1 || true' EXIT

# 此刻的 Zeta 悬浮窗口（标题），空格分开
overlays() { android "dumpsys window windows | grep -oE 'Zeta(Capsule|EdgeGlow|ResultCard)' | sort -u | tr '\n' ' '; true"; }
# Zeta 自己的 Activity 窗口有几个（悬浮窗口不算；force-stop 后窗口还要挂几秒 EXITING 才撤掉，正在撤的也不算）
activities() { android "dumpsys window windows | grep 'Window{.* $PKG/' | grep -vc EXITING; true"; }
# 没等到时说明停在哪：定时任务这次跑没跑（运行记录、跳过次数）、假 Claude 起没起、日志里相关的几行
explain() {
  echo "测试任务：$(task | jq -c '{lastRun, skips}' 2>/dev/null || echo 读不到)"
  find_log
  echo "假 Claude 的日志：${log:-没有（这次没起假 Claude）}"
  [[ -z $log ]] || android "tail -c 1500 $log"
  echo "无障碍服务启用着：$(android "settings get secure enabled_accessibility_services" | tr ':' '\n' | grep -c "$PKG/" || true)"
  android "logcat -d -t 3000 | grep -iE 'zeta|schedule|AndroidRuntime' | grep -v ' HWUI ' | tail -25" || true
}
task_json='[{"id":"'$ID'","name":"假冷启动悬浮层","prompt":"fake-overlays-cold","rule":{"kind":"daily","time":"03:00"},"enabled":true,"newSession":true,"created":0}]'
vd prefs-set "scheduledTasks=$task_json" >/dev/null
vd fake "$HERE/fake/overlays-cold.jsonl" >/dev/null
android "am force-stop $PKG; touch $MARK"
NEWER=$MARK
sleep 1
# 界面窗口都没了才算数。进程本身可以在：测试版是默认数字助理时，系统会马上为语音交互服务把它拉起来
(($(activities) == 0)) || { echo "force-stop 后还有 Zeta 的界面窗口"; exit 1; }
# 接收器不导出，以 root 发；被 force-stop 的包要带 FLAG_INCLUDE_STOPPED_PACKAGES（0x20）才收得到
android "am broadcast -f 0x20 -n $PKG/com.dzshzx.zeta.core.schedule.ScheduleReceiver -a $PKG.action.SCHEDULE_FIRE --es task $ID --el at $(date +%s)000" >/dev/null
sleep 2
echo "广播后进程：$(android "pidof $PKG" || echo 不在)"

# 一轮里悬浮窗口先后出现又收起：每秒看一次，记下出现过的（第一次出现时截图），直到结果卡片出来或 60 秒到了。
# 边缘流光、胶囊各在第一次看到时放标记，剧本才往下走：它们不会在两次查看之间出现又收起。
# 同时记下 Zeta 的 Activity 窗口最多时有几个
seen='' acts=0
for _ in $(seq 60); do
  for w in $(overlays); do
    [[ " $seen " == *" $w "* ]] && continue
    seen+="$w "
    echo "$(date +%T) 出现 $w"
    case $w in
      ZetaEdgeGlow) vd shot glow.png >/dev/null; mark glow ;;
      ZetaCapsule) mark capsule ;;
      ZetaResultCard) sleep 1; vd shot card.png >/dev/null ;;
    esac
  done
  a=$(activities)
  ((a > acts)) && acts=$a
  [[ $seen == *ZetaResultCard* ]] && break
  sleep 1
done
echo "出现过的悬浮窗口：${seen:-无}"
missing=''
for w in ZetaCapsule ZetaEdgeGlow ZetaResultCard; do [[ $seen == *$w* ]] || missing+="$w "; done
if [[ -n $missing ]]; then
  echo "没等到：$missing"
  vd shot missing.png >/dev/null
  explain
  exit 1
fi
((acts == 0)) || { echo "期间有 Zeta 的界面窗口（最多 $acts 个），不算没开界面"; exit 1; }
echo "没开界面，胶囊、边缘流光、结果卡片都出来了"
