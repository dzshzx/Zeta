#!/bin/bash
# 数字助理：服务、元数据和 ASSIST 入口注册上了；ASSIST 在测试屏上打开浮窗（预填问题），发出去新开对话、等到回答；
# 上推浮窗打开 Zeta 接着这个对话。会真跑一轮 Claude；测试建的会话收尾时删掉，接着它的后台进程一起结束。
# 用法：ZETA_PHONE_OWNER=<任务名> ZETA_PHONE_RUN_TIMEOUT=300 tools/phone-run tools/phone-tests/assistant.sh（vd、android、$PKG、$DISPLAY_ID 由 phone-run 提供）。
# 点按坐标按测试屏（1220×2656，没有系统栏）上浮窗的布局算，见 AssistantShots 的 assistant-entry 图。
#
# 不点建议问题：ASSIST 这条路没有截图，问「这页讲了什么」时 Claude 会用手机工具去读主屏，那是用户正在用的屏幕。
# 也不点输入框：测试屏上弹的键盘会弹到用户的主屏上。所以用 ASSIST 的 query 预填一个不用工具的问题，只点发送键。
set -euo pipefail
ROOTFS=/data/local/zeta/debian/rootfs
MARK=/data/local/tmp/zeta-assistant-test.mark
QUESTION='Reply with the single word OK. Do not use any tools.'

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

resumed() { android "dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'" | grep -o "$PKG/[A-Za-z0-9_.]*" | head -1; }

echo "== 注册"
android "cmd package query-services --components -a android.service.voice.VoiceInteractionService" | tee vis.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.ZetaVoiceInteractionService"
android "cmd package query-services --components -a android.speech.RecognitionService" | tee recognition.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.ZetaRecognitionService"
android "cmd package query-activities --components -a android.intent.action.ASSIST" | tee assist.txt | grep -q "$PKG/com.dzshzx.zeta.assistant.AssistantActivity"
android "dumpsys package $PKG" > package.txt
grep -q 'android.permission.BIND_VOICE_INTERACTION' package.txt
echo "服务、识别服务、ASSIST 入口都在"

echo "== ASSIST 打开浮窗，预填问题（测试屏 $DISPLAY_ID，不进最近任务）"
android "am start --display $DISPLAY_ID -f 0x18800000 -a android.intent.action.ASSIST -n $PKG/com.dzshzx.zeta.assistant.AssistantActivity --es android.intent.extra.ASSIST_PACKAGE com.android.settings --es query '$QUESTION' >/dev/null"
sleep 3
vd shot panel.png
[[ $(resumed) == "$PKG/com.dzshzx.zeta.assistant.AssistantActivity" ]] || { echo "浮窗没在前台：$(resumed)"; exit 1; }

echo "== 点发送键"
vd tap 1062 2504
for i in $(seq 40); do
  sleep 3
  if android "find $ROOTFS/home/*/.cache/zeta-dev/hosts -name out.jsonl -newer $MARK 2>/dev/null | xargs cat 2>/dev/null" | grep -q '"type":"result"'; then
    echo "等了 $((i * 3)) 秒，这一轮结束了"
    break
  fi
  ((i == 40)) && { vd shot reply-timeout.png; echo "120 秒没等到回答"; exit 1; }
done
sleep 2
vd shot reply.png
android "find $ROOTFS/home/*/.claude/projects -name '*.jsonl' -newer $MARK | xargs grep -l '<screen_context>'" | grep -q jsonl || { echo "没找到带屏幕内容的新会话"; exit 1; }
echo "新会话的第一条消息带着屏幕内容"

echo "== 上推浮窗，打开 Zeta 接着这个对话"
vd swipe 610 2600 610 1600 400 # 从面板底边的空白处往上拖，不压在发送键上
sleep 4
vd shot zeta.png
[[ $(resumed) == "$PKG/com.dzshzx.zeta.ui.MainActivity" ]] || { echo "没打开 Zeta：$(resumed)"; exit 1; }
echo "通过"
