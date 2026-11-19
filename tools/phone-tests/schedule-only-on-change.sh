#!/bin/bash
# 定时任务“只在有变化时提醒”：测试版换用假 Claude（剧本 fake/schedule-only-on-change.jsonl），先 prefs-set 放一个关着的、
# 只在有变化时提醒的定时任务（关着就不排闹钟），再在聊天里让 Claude 经手机工具 run_scheduled_task 立即运行它两次：
#   第 1 次后台运行上报 no_change：运行记录记成 no_change，不发通知（也没有“需要你确认”：上报工具由 Zeta 自动放行）；
#   第 2 次上报 deliver：记成 done，发通知，正文是上报的摘要。
# 顺带核对工具清单：后台运行的进程只看到 report_scheduled_run，看不到新建等管理定时任务的工具；平常的对话相反。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/schedule-only-on-change.sh
# 假 Claude 不写会话记录，没有要删的测试会话；任务由收尾的 vd stop 随 prefs-set 改回去，通知随 force-stop 收掉。
set -euo pipefail
HERE=$ZETA_ROOT/tools/phone-tests
HOSTS=/data/local/zeta/debian/rootfs/home/dzshzx/.cache/zeta-dev/hosts
MARK=/data/local/tmp/zeta-schedule-only-on-change.mark
# 后台运行的对话跑完几秒就被收掉，托管日志随之删掉：测试期间每秒把这次起的日志抄一份到这里
COPIES=/data/local/tmp/zeta-schedule-only-on-change.logs
ID=ztoocheck
# 聊天页的输入框、发送键（整屏 1220×2656 的坐标，冷启动后的新对话页）
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548

SNAP=''
finish() {
  [[ -z $SNAP ]] || kill "$SNAP" 2>/dev/null || true
  android "rm -rf $MARK $COPIES" >/dev/null 2>&1 || true
}
trap finish EXIT

send() {
  vd tap $INPUT_X $INPUT_Y
  sleep 1
  vd text "$1"
  sleep 0.5
  vd tap $SEND_X $SEND_Y
}

# 测试任务现在的样子（设置里那份 JSON 里 id 为 $ID 的一项）
task() {
  vd prefs scheduledTasks | sed -e 's/^ *<string name="scheduledTasks">//' -e 's#</string>$##' \
    -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g' |
    jq -c --arg id "$ID" '.[] | select(.id == $id)'
}

# 等第 $1 次运行跑完（lastRun 不再是 running、at 比上一次新），放进 RUN；60 秒没等到就失败
last_at=0
wait_run() {
  for _ in $(seq 60); do
    RUN=$(task | jq -c '.lastRun // empty')
    if [[ -n $RUN ]] && (($(jq -r .at <<<"$RUN") > last_at)) && [[ $(jq -r .outcome <<<"$RUN") != running ]]; then
      last_at=$(jq -r .at <<<"$RUN")
      return
    fi
    sleep 1
  done
  echo "第 $1 次运行 60 秒没跑完：$(task)"
  vd shot "run-$1-timeout.png"
  exit 1
}

# 测试任务的通知（tag schedule:$ID）：存下整份 dumpsys，打出每条的正文
notes() {
  android "dumpsys notification --noredact" >"notifications-$1.txt"
  awk -v pkg="pkg=$PKG " -v tag="tag=schedule:$ID " '
    /NotificationRecord\(/ { on = index($0, pkg) && index($0, tag) }
    on && /android\.(title|text)=/ { sub(/^ */, ""); print }
  ' "notifications-$1.txt"
}

android "rm -rf $COPIES; mkdir -p $COPIES; touch $MARK"
(
  while :; do
    android "for f in \$(find $HOSTS -name out.jsonl -newer $MARK 2>/dev/null); do cp \$f $COPIES/\$(basename \$(dirname \$f)).jsonl; done" >/dev/null 2>&1 || true
    sleep 1
  done
) &
SNAP=$!
task_json='[{"id":"'$ID'","name":"假降价监控","prompt":"fake-schedule-check","rule":{"kind":"daily","time":"03:00"},"enabled":false,"newSession":true,"onlyOnChange":true,"created":0}]'
vd prefs-set "scheduledTasks=$task_json"
vd fake "$HERE/fake/schedule-only-on-change.jsonl"
vd launch
sleep 10 # prefs-set 停过测试版：冷启动后马上输入会丢字
[[ $(task | jq -r .onlyOnChange) == true ]] || { echo "测试任务没放进设置：$(task)"; exit 1; }

send "fake-run-1"
wait_run 1
echo "第 1 次：$RUN"
sleep 3 # 跑完到发不发通知在同一步里，再等一下界面和通知栏
vd shot run-1.png >/dev/null
[[ $(jq -r .outcome <<<"$RUN") == no_change && $(jq -r .summary <<<"$RUN") == *2999* ]] || { echo "第 1 次应记成没有变化"; exit 1; }
n1=$(notes 1)
[[ -z $n1 ]] || { echo "没有变化不该有通知（含“需要你确认”），却有：$n1"; exit 1; }
echo "第 1 次没有通知"

send "fake-run-2"
wait_run 2
echo "第 2 次：$RUN"
sleep 3
vd shot run-2.png >/dev/null
[[ $(jq -r .outcome <<<"$RUN") == done && $(jq -r .summary <<<"$RUN") == *2799* ]] || { echo "第 2 次应记成完成、摘要是上报的那段"; exit 1; }
n2=$(notes 2)
echo "第 2 次的通知：${n2:-没有}"
[[ $n2 == *"降到 2799"* ]] || { echo "有结果应发通知，正文是上报的摘要"; exit 1; }

# 工具清单：这次测试起的假 Claude 进程（抄下来的日志），按 init 里的手机工具分两类
kill "$SNAP" 2>/dev/null || true
SNAP=''
logs=$(android "ls $COPIES/*.jsonl 2>/dev/null" || true)
runs=0 chats=0
for log in $logs; do
  tools=$(android "grep -m 1 '\"subtype\":\"init\"' $log" |
    jq -c '[.tools[] | select(startswith("mcp__phone__")) | ltrimstr("mcp__phone__") | select(test("schedul"))]' || true)
  [[ -n $tools ]] || continue # 起了进程还没有一轮的
  echo "$(basename "$log" .jsonl)：$tools"
  if [[ $tools == '["report_scheduled_run"]' ]]; then
    runs=$((runs + 1))
  elif [[ $tools == *create_scheduled_task*run_scheduled_task* && $tools != *report_scheduled_run* ]]; then
    chats=$((chats + 1))
  fi
done
((runs == 2 && chats >= 1)) || { echo "工具清单不对：后台运行 $runs 个（应为 2）、平常对话 $chats 个（应至少 1）"; exit 1; }
echo "没有变化不通知、有结果通知，工具清单也对"
