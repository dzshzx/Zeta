#!/bin/bash
# 按住说话：在空的新对话里长按发送键（麦克风）6 秒，系统的识别服务要开始收音（日志“可以说话了”），
# 中途服务因为没人说话自己结束一句（约 4 秒）后要接着听，松手后收尾。没人说话时识别不出字，
# 正常的结局是“结束，0 字”和“没有听到说话”的提示；识别出字的那一半要人亲口试。
# 用法：ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/voice-input.sh（vd、android、$PKG 由 phone-run 提供）
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"
android "pm grant $PKG android.permission.RECORD_AUDIO"
# 冷启动可能停在别的对话上（正在跑时发送键是停止键）：先开新对话
new_chat
vd shot before.png >/dev/null
t=$(android 'date +%s')
vd swipe 1088 2546 1088 2546 6000 # 输入卡片右下角的麦克风，按住 6 秒
sleep 4
android "logcat -d -v time -t '$t.000' -s Zeta:V" | grep -E '\[app\] 语音识别' >voice.log || true
cat voice.log
grep -q '可以说话了' voice.log || { echo "识别服务没有开始收音"; exit 1; }
! grep -qE '语音识别：.*（-?[0-9]+）$' voice.log || { echo "识别出错了"; exit 1; }
grep -q '语音识别：结束' voice.log || { echo "松手后没有收尾"; exit 1; }
