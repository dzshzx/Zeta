#!/bin/bash
# README 的截图：测试版换用假 Claude（剧本 fake/readme.jsonl），在新对话里走两轮，点开第一轮的步骤，再看设置首页，每一步截一张。
# 用户消息经分享 Intent 送进输入栏（vd text 只能输 ASCII），所以界面上是中文。画面里只有剧本里的假内容和测试设备自己的信息。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/readme-shots.sh
# 产物在 phone-run 的产物目录：01-chat.png、02-steps.png、03-phone-tool.png、04-settings.png，缩到一半（610×1328）存进 docs/screenshots/
# （去掉编号）之前逐张看一遍，确认画面里没有设备上的真实数据。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

COMPONENT=$PKG/com.dzshzx.zeta.ui.MainActivity

# share <文字>：像别的应用分享文字那样交给 Zeta；空白的新对话直接放进输入栏，否则弹「放进哪个对话」
share() {
  android "am start --display $DISPLAY_ID -n $COMPONENT -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$1' >/dev/null"
  sleep 2
}

# 上次没跑完留在输入栏里的草稿会和这次分享的文字拼在一起：先删掉测试版的草稿，再冷启动
android "am force-stop $PKG; rm -f /data/user/0/$PKG/shared_prefs/zeta_drafts.xml"
vd launch >/dev/null
sleep 10
vd fake "$HERE/fake/readme.jsonl"
NEWER=/data/local/tmp/zeta-readme-shots.mark
android "touch $NEWER"
new_chat
share '看一下这个项目最近的提交，再告诉我怎么在手机上构建测试版。'
vd shot 00-composer.png >/dev/null
# 负载高时一下可能没点上：假 Claude 的日志没出现（这一轮还没开始）才补点，开始了再点就成了「停止」
for _ in 1 2 3; do
  vd tap $SEND_X $SEND_Y >/dev/null
  for _ in $(seq 1 20); do find_log; [[ -n $log ]] && break; sleep 0.5; done
  [[ -n $log ]] && break
done
wait_result 1 30 || { vd shot 01-timeout.png >/dev/null; exit 1; }
sleep 2
vd shot 01-chat.png >/dev/null
# 点开「已完成 2 个步骤」：思考、Bash 和 Read 的时间线；截完再点一下收起来
vd tap 540 556 >/dev/null
sleep 1.5
vd shot 02-steps.png >/dev/null
vd tap 540 556 >/dev/null
sleep 1

share '打开系统设置'
vd shot 01b-intake.png >/dev/null
# 「放进哪个对话」对话框：右边的「接着这个对话」
vd tap "${KEEP_X:-850}" "${KEEP_Y:-2440}" >/dev/null
sleep 1.5
vd shot 01c-composer.png >/dev/null
vd tap $SEND_X $SEND_Y >/dev/null
wait_result 2 40 || { vd shot 03-timeout.png >/dev/null; exit 1; }
sleep 2
vd shot 03-phone-tool.png >/dev/null

# 设置首页：深链打开一个子页，返回一层就是首页
android "am start --display $DISPLAY_ID --activity-single-top -n $COMPONENT --es com.dzshzx.zeta.extra.SETTINGS_PAGE Permissions >/dev/null"
sleep 2
vd key 4 >/dev/null
sleep 1.5
vd shot 04-settings.png >/dev/null
android "rm -f $NEWER"
echo "截图都在产物目录"
