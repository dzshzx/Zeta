#!/bin/bash
# 没人看着的对话放开以后，Claude Code 进程的去留只看主机的闲置超时，和 Zeta 在不在无关：摆一份测试会话，测试版换用假 Claude
# （剧本 fake/remote.jsonl 的两轮），按会话号打开它发一轮、等它答完；按返回键让 Zeta 离开测试屏，几秒后测试版的前台服务应已停下
# （没有对话在忙）、托管的进程还在，再过核心的 30 秒它不再接着、进程照样在（看门狗从这时开始数）；强行停掉测试版，进程照样在；
# 冷启动后按会话号打开、再发一轮，接上的还是同一个进程（同一个托管目录里有第二个 result）。
# 假 Claude 不写会话记录：会话要先摆好，核心才按它带 --resume 起进程，回来时托管脚本才认得出这个会话已有进程在跑。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/release-when-unwatched.sh
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
DIR=/tmp/zeta-release-unwatched
ID=$(cat /proc/sys/kernel/random/uuid)
NEWER=/data/local/tmp/zeta-release-unwatched.mark
trap 'drop_session $ID $DIR; android "rm -f $NEWER" >/dev/null 2>&1 || true' EXIT

# 测试版的前台服务在不在前台
service_up() { android "dumpsys activity services $PKG/com.dzshzx.zeta.core.AgentService" | grep -q 'isForeground=true'; }
# 托管目录里还接着的一方有几个
attachers() { android "ls $dir/attach.d 2>/dev/null | wc -l" | tr -d '\r '; }

android "touch $NEWER"
vd fake "$HERE/fake/remote.jsonl"
seed_session "$ID" "$DIR" "release probe" "probe reply"
open_conversation "$ID" 10 # 冷启动后马上输入会丢字
send "fake-release-1"
wait_result 1 40 || exit 1
dir=${log%/out.jsonl}
pid=$(android "cat $dir/pid" | tr -d '\r')
echo "托管的进程 $pid"

vd key KEYCODE_BACK >/dev/null
sleep 1
[[ -z $(resumed) ]] || vd key KEYCODE_BACK >/dev/null
sleep 1
[[ -z $(resumed) ]] || { echo "按两次返回键，测试版还在前台"; exit 1; }
# 放开要等几秒（ConversationHub.RETIRE_DELAY_MS）
for _ in $(seq 30); do service_up || break; sleep 0.5; done
if service_up; then
  echo "离开屏幕 15 秒、没有对话在忙，前台服务却还在"
  exit 1
fi
alive "$pid" || { echo "放开以后进程没了"; exit 1; }
echo "离开屏幕后前台服务停了，进程还在"
for _ in $(seq 60); do
  left=$(attachers)
  ((left == 0)) && break
  sleep 1
done
((left == 0)) || { echo "放开一分钟后核心还接着进程"; exit 1; }
sleep 2
alive "$pid" || { echo "核心不再接着时把进程结束了"; exit 1; }
echo "核心也不再接着，进程还在：从这时起由主机的闲置超时数"

android "am force-stop $PKG"
sleep 2
alive "$pid" || { echo "强行停掉测试版，进程跟着没了"; exit 1; }
echo "强行停掉测试版，进程照样在"

open_conversation "$ID" 10
send "fake-release-2"
wait_result 2 40 || { vd shot after.png >/dev/null; exit 1; }
[[ $(android "cat $dir/pid" | tr -d '\r') == "$pid" ]] && alive "$pid" || { echo "第二轮不是原来的进程答的"; exit 1; }
echo "回来再发一轮，接上的是原来的进程 $pid"
