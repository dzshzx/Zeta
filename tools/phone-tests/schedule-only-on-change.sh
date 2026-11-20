#!/bin/bash
# 定时任务“只在有变化时提醒”：测试版换用假 Claude（剧本 fake/schedule-only-on-change.jsonl），先 prefs-set 放一个关着的、
# 只在有变化时提醒的定时任务（关着就不排闹钟），再在聊天里让 Claude 经手机工具 run_scheduled_task 立即运行它两次：
#   第 1 次后台运行上报 no_change：运行记录记成 no_change，不发通知（也没有“需要你确认”）；
#   第 2 次上报 deliver：记成 done，发通知，正文是上报的摘要。
# 顺带核对后台运行的进程收到了权限规则（apply_flag_settings）并且正常应答。假 Claude 不实现 Claude Code 的权限系统：
# 剧本里上报不弹确认，是照真 Claude Code 在规则下的样子写的；规则拦不拦得住由 UnattendedRulesContractTest 在工作站上验。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/schedule-only-on-change.sh
# 假 Claude 不写会话记录，没有要删的测试会话；任务由收尾的 vd stop 随 prefs-set 改回去，通知随 force-stop 收掉。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
MARK=/data/local/tmp/zeta-schedule-only-on-change.mark
# 后台运行的对话跑完几秒就被收掉，托管日志随之删掉：测试期间每秒把这次起的日志抄一份到这里
COPIES=/data/local/tmp/zeta-schedule-only-on-change.logs
ID=ztoocheck

SNAP=''
finish() {
  [[ -z $SNAP ]] || kill "$SNAP" 2>/dev/null || true
  android "rm -rf $MARK $COPIES" >/dev/null 2>&1 || true
}
trap finish EXIT

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

# 权限规则：这次测试起的假 Claude 进程（抄下来的日志）里，后台运行的（回答里说到价格的）两个都答过 apply_flag_settings
# （假 Claude 只有这个应答是不带内容的 success），也没有答过别的错（不查额度那条除外）
kill "$SNAP" 2>/dev/null || true
SNAP=''
logs=$(android "ls $COPIES/*.jsonl 2>/dev/null" || true)
runs=0
for log in $logs; do
  android "cat $log" >run.jsonl
  grep -q '看了一下价格' run.jsonl || continue
  applied=$(jq -c 'select(.type == "control_response" and .response.subtype == "success" and (.response | has("response") | not))' run.jsonl | wc -l)
  errors=$(control_errors run.jsonl)
  echo "$(basename "$log" .jsonl)：apply_flag_settings 应答 $applied 次${errors:+，出错：$errors}"
  ((applied >= 1)) && [[ -z $errors ]] && runs=$((runs + 1))
done
rm -f run.jsonl
((runs == 2)) || { echo "后台运行的进程应有 2 个答过权限规则，实际 $runs 个"; exit 1; }
echo "没有变化不通知、有结果通知，后台运行的进程都收到了权限规则"
