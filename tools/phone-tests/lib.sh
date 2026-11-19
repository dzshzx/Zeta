# shellcheck shell=bash disable=SC2034 # 这里的变量给 source 它的脚本用
# 真机脚本的公用部分。脚本开头 source 它：
#   . "$ZETA_ROOT/tools/phone-tests/lib.sh"
# 用的是 tools/phone-run 给的 vd、android、$ZETA_ROOT；函数在 set -euo pipefail 下也能用。

HERE=$ZETA_ROOT/tools/phone-tests

# 测试版的托管目录：每个对话一个子目录，out.jsonl 是 Claude（或假 Claude）的输出
HOSTS=/data/local/zeta/debian/rootfs/home/dzshzx/.cache/zeta-dev/hosts

# 聊天页的输入框、发送键（整屏 1220×2656 的坐标，冷启动后的新对话页）
INPUT_X=320 INPUT_Y=2418 SEND_X=1091 SEND_Y=2548

# send <文字> [打完字后等几秒，默认 0.5]：在聊天页的输入框里输入并发送（vd text 只能输 ASCII）
send() {
  vd tap $INPUT_X $INPUT_Y
  sleep 1
  vd text "$1"
  sleep "${2:-0.5}"
  vd tap $SEND_X $SEND_Y
}

# 这次对话的托管日志放进 log：起的是假 Claude（init 里有 fake_claude）的、最新的那份。
# NEWER 设成手机上的一个文件时，只看比它新的（先 android "touch <文件>"，别的测试留下的假 Claude 日志就不算）。还没有时 log 是空的。
NEWER=''
log=''
find_log() {
  log=$(android "set -- \$(find $HOSTS -mindepth 2 -maxdepth 2 -name out.jsonl ${NEWER:+-newer $NEWER} 2>/dev/null); [ \$# -gt 0 ] || exit 0; \
set -- \$(grep -l '\"fake_claude\":true' \"\$@\"); [ \$# -gt 0 ] || exit 0; ls -t \"\$@\" | head -n 1" | tr -d '\r' || true)
}

# wait_result <第几轮> <最多等几秒>：等到日志里有第 n 个 result（一轮结束），放进 RESULT。
# 没等到时说一句、返回 1（要不要截图、退出由脚本定：截图可能照到个人数据）
RESULT=''
wait_result() {
  local n=$1 seconds=$2 count=0 i
  for ((i = 0; i < seconds * 2; i++)); do
    [[ -n $log ]] || find_log
    [[ -n $log ]] && count=$(android "grep -c '\"type\":\"result\"' $log" || true)
    if ((${count:-0} >= n)); then
      RESULT=$(android "grep '\"type\":\"result\"' $log | sed -n '${n}p'")
      return 0
    fi
    sleep 0.5
  done
  echo "第 $n 轮 $seconds 秒没等到 result（日志：${log:-还没有假 Claude 的日志，测试版起的可能是真的 claude}）"
  return 1
}
