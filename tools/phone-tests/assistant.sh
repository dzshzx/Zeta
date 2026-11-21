#!/bin/bash
# 数字助理：服务、元数据和 ASSIST 入口注册上了；ASSIST 在测试屏上打开浮窗（预填问题），发出去新开对话、等到回答；
# 点「在 Zeta 中继续」打开 Zeta 接着这个对话。会真跑一轮 Claude；测试建的会话收尾时删掉，接着它的后台进程一起结束。
# 数字助理单独定的模型和思考强度：测试时临时设成 haiku、low，从 Zeta 诊断日志里这次起 Claude 进程的记录核对（vd stop 改回去）。
# 用法：ZETA_PHONE_OWNER=<任务名> ZETA_PHONE_RUN_TIMEOUT=300 tools/phone-run tools/phone-tests/assistant.sh（vd、android、$PKG、$DISPLAY_ID 由 phone-run 提供）。
# 点按坐标按测试屏（1220×2656，没有系统栏）上浮窗的布局算，见 AssistantShots 的 assistant-entry 图。
#
# 不点建议问题：ASSIST 这条路没有截图，问「这页讲了什么」时 Claude 会用手机工具去读主屏，那是用户正在用的屏幕。
# 也不点输入框：测试屏上弹的键盘会弹到用户的主屏上。所以用 ASSIST 的 query 预填一个不用工具的问题，只点发送键。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
ROOTFS=/data/local/zeta/debian/rootfs
MARK=/data/local/tmp/zeta-assistant-test.mark
QUESTION='Reply with the single word OK. Do not use any tools.'
EVENTS=/data/user/0/$PKG/files/diag/events.jsonl

# 删掉标记 $1 之后新建、带屏幕内容的测试会话，并结束接着它的后台进程（后台进程的输出开头记着会话 ID）。
# 只删会话文件不够：进程还在等确认或还在跑的话，测试版下次冷启动会把它接回来，别人的测试就停在这个对话上。
reap() {
  android "for f in \$(find $ROOTFS/home/*/.claude/projects -name '*.jsonl' -newer $1 2>/dev/null | xargs grep -l '<screen_context>' 2>/dev/null); do
    id=\${f##*/}; id=\${id%.jsonl}; n=0
    for o in \$(grep -l \"\$id\" $ROOTFS/home/*/.cache/zeta-dev/hosts/*/out.jsonl 2>/dev/null); do
      d=\${o%/out.jsonl}; p=\$(cat \$d/pid 2>/dev/null)
      if [ -n \"\$p\" ]; then kill \$p 2>/dev/null; sleep 1; kill -9 \$p 2>/dev/null; fi
      rm -rf \$d; n=1; echo \"$2结束后台进程 \${d##*/}\"; done
    [ \$n = 0 ] && echo \"$2没有接着它的后台进程（已经结束）\"
    rm -rf \"\$f\" \"\${f%.jsonl}\"; echo \"$2删掉测试会话 \$id\"; done"
}

# 上一次被打断（丢了租约、超时）时标记还在：先收掉那次留下的，再重新打标记
if android "[ -e $MARK ]"; then reap "$MARK" "上次留下的："; fi
android "touch $MARK"
cleanup() {
  set +e
  reap "$MARK" ""
  android "rm -f $MARK"
}
trap cleanup EXIT


echo "== 注册"
android "cmd package query-services --components -a android.service.voice.VoiceInteractionService" | tee vis.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.ZetaVoiceInteractionService"
android "cmd package query-services --components -a android.speech.RecognitionService" | tee recognition.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.ZetaRecognitionService"
android "cmd package query-activities --components -a android.intent.action.ASSIST" | tee assist.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.AssistantActivity"
android "dumpsys package $PKG" > package.txt
grep -q 'android.permission.BIND_VOICE_INTERACTION' package.txt
echo "服务、识别服务、ASSIST 入口都在"

echo "== 数字助理单独定模型和思考强度：临时设成 haiku、low"
vd prefs-set assistantModel=haiku assistantEffort=low
since=$(($(android 'date +%s' | tr -d '\r') * 1000))
echo "== ASSIST 打开浮窗，预填问题（测试屏 $DISPLAY_ID，不进最近任务）"
android "am start --display $DISPLAY_ID -f 0x18800000 -a android.intent.action.ASSIST -n $PKG/com.dzshzx.zeta.assistant.AssistantActivity --es android.intent.extra.ASSIST_PACKAGE com.android.settings --es query '$QUESTION' >/dev/null"
sleep 3
vd shot panel.png
[[ $(resumed) == "$PKG/com.dzshzx.zeta.assistant.AssistantActivity" ]] || { echo "浮窗没在前台：$(resumed)"; exit 1; }

echo "== 点发送键"
vd tap 1062 2504
for i in $(seq 40); do
  sleep 3
  # 只回传这一轮的结局那一行
  result=$(android "find $ROOTFS/home/*/.cache/zeta-dev/hosts -name out.jsonl -newer $MARK 2>/dev/null | xargs grep -h '\"type\":\"result\"' 2>/dev/null" || true)
  if [[ -n $result ]]; then
    echo "等了 $((i * 3)) 秒，这一轮结束了"
    break
  fi
  ((i == 40)) && { vd shot reply-timeout.png; echo "120 秒没等到回答"; exit 1; }
done
sleep 2
vd shot reply.png
# 这一轮是出错结束的（比如网络被拦、额度用完）：先报出来，不往下核对
error=$(grep '"is_error":true' <<<"$result" | head -n 1 || true)
[[ -z $error ]] || { echo "Claude 这一轮出错了，不是数字助理的问题：$(jq -r '.result // .subtype' <<<"$error" | head -c 200)"; exit 1; }
android "find $ROOTFS/home/*/.claude/projects -name '*.jsonl' -newer $MARK | xargs grep -l '<screen_context>'" | grep -q jsonl || { echo "没找到带屏幕内容的新会话"; exit 1; }
echo "新会话的第一条消息带着屏幕内容"
# Zeta 每次起 Claude 进程都在诊断日志里记下启动参数（DiagEvents.claudeLaunched：「模型 haiku，强度 low」）。
# 设备上只按英文的分类筛，中文交给 jq：模拟器上的 grep 匹配不了中文
launches=$(android "grep '\"c\":\"claude\"' $EVENTS" |
  jq -r --argjson since "$since" 'select(.t >= $since) | .m | select(startswith("起 Claude 进程"))')
echo "这次起的 Claude 进程：$launches"
grep -q '模型 haiku.*强度 low' <<<"$launches" || { echo "数字助理的对话没用上单独定的模型和思考强度"; exit 1; }

echo "== 点「在 Zeta 中继续」，打开 Zeta 接着这个对话"
# 链接总在输入栏正上方靠右（发送键在 1062,2504）；有回答以后整列上推不再打开 Zeta，要在最高档从把手往上拉
vd tap 1000 2350
sleep 4
vd shot zeta.png
[[ $(resumed) == "$PKG/com.dzshzx.zeta.ui.MainActivity" ]] || { echo "没打开 Zeta：$(resumed)"; exit 1; }
echo "通过"
