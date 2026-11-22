#!/bin/bash
# 会话列表跟着 Debian 那边变（zeta-helper serve 的订阅）：侧栏开着、不重开，在 Debian 里新建一个会话，它自己出现在侧栏里；
# 再给它改名（和 Claude Code 的 /rename 一样追加一行 custom-title），新名字也自己出现。核对 Debian 里正有一个 zeta-helper serve 在跑；
# 测试版退到后台后它退出，回到前台再起一个。截图 appeared.png、renamed.png。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/session-list-live.sh
#
# 测试会话放在 Debian 的 $DIR 对应的项目目录里，脚本退出时删掉。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-session-list-live
ID=$(cat /proc/sys/kernel/random/uuid)
PROJECT=.claude/projects/${DIR//\//-}
PANE_X=116 PANE_Y=84 # 顶栏左边的侧栏按钮
trap 'drop_session $ID $DIR' EXIT

# 这个包的 zeta-helper serve 进程有几个（Debian 里）：可执行文件是测试版装好的 zeta-helper（~/.cache/zeta-dev/bin 下，
# 从这里看带着 rootfs 在 Android 里的前缀），参数只有 serve
# shellcheck disable=SC2016 # 整段交给 Debian 里的 shell 展开
serves() {
  vd debian 'n=0; for p in /proc/[0-9]*; do e=$(readlink "$p/exe" 2>/dev/null) || continue
case $e in *"$HOME"/.cache/zeta-dev/bin/zeta-helper-*) ;; *) continue ;; esac
[ "$(tr "\0" " " <"$p/cmdline" 2>/dev/null | cut -d" " -f2-)" = "serve " ] && n=$((n+1)); done; echo $n' | tr -d '\r'
}

# wait_for <正则> <几秒> <说明>：等虚拟屏上出现文字对得上的控件
wait_for() {
  local tries=0
  until vd nodes "$1" | grep -q .; do
    ((++tries <= $2 * 2)) || { echo "$3：$2 秒内侧栏里没出现 $1"; vd shot "missing.png" >/dev/null; exit 1; }
    sleep 0.5
  done
}

# 侧栏要真打开（冷启动后头几秒的点按会丢）：看到“新对话”这一项为止
tries=0
until vd nodes '^新对话$' | grep -q .; do
  ((++tries <= 10)) || { echo "点了 10 次侧栏按钮，侧栏没打开"; exit 1; }
  vd tap $PANE_X $PANE_Y >/dev/null
  sleep 1
done
for _ in $(seq 20); do [[ $(serves) -ge 1 ]] && break; sleep 0.5; done
[[ $(serves) -ge 1 ]] || { echo "侧栏开着，Debian 里却没有 zeta-helper serve"; exit 1; }
echo "侧栏开着：Debian 里有 $(serves) 个 zeta-helper serve"

seed_session "$ID" "$DIR" "live list probe" "probe reply"
wait_for 'live list probe' 15 新会话
vd shot appeared.png >/dev/null
echo "新建的会话自己出现在侧栏里"

vd debian --stdin "cat >> ~/$PROJECT/$ID.jsonl" <<<'{"type":"custom-title","customTitle":"renamed live probe"}' >/dev/null
wait_for 'renamed live probe' 15 改名
vd shot renamed.png >/dev/null
echo "改的名字自己出现在侧栏里"

# 退到后台：订阅断开，那边的 serve 退出
android "am start --display $DISPLAY_ID -n com.android.traceur/.MainActivity >/dev/null"
for _ in $(seq 20); do [[ $(serves) -eq 0 ]] && break; sleep 0.5; done
[[ $(serves) -eq 0 ]] || { echo "测试版退到后台 10 秒了，Debian 里还有 $(serves) 个 zeta-helper serve"; exit 1; }
echo "退到后台：zeta-helper serve 退出了"

vd launch >/dev/null
for _ in $(seq 20); do [[ $(serves) -ge 1 ]] && break; sleep 0.5; done
[[ $(serves) -ge 1 ]] || { echo "回到前台 10 秒了，Debian 里没有再起 zeta-helper serve"; exit 1; }
echo "回到前台：又起了 zeta-helper serve"
