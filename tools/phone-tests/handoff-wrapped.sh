#!/bin/bash
# 登录配置把 claude 包了一层（不 exec 的脚本、shell 函数）时，Zeta 照样认得自己托管的进程：托管目录的 pid 文件记的是外面那层，
# 在 ~/.claude/sessions 登记的 Claude Code 是它的子进程，认人靠进程身上的记号 ZETA_HOST。逐项核对：
#   一、pid 文件记的和登记的不是一个进程（包装确实生效），登记的那个带着本包、本托管目录的记号；
#   二、本包的进程不算别处占用：zeta-helper occupant 说没人占着，list 里这个会话不标 external（侧栏的「在别处运行」看它）；
#       再按会话号打开它，不出「别处运行」卡片，原来那个进程接着答完第二轮
#       （不 force-stop 再重开：测试版冷启动时把闲着的托管进程收掉，下一条消息另起进程，见 ConversationHub.onHostsListed）；
#   三、终端里接回（SessionStart hook 跑 zeta-helper session-start）时，它闲着就照 stop 那样退出，外面那层一起没了。
# 先在设备自己的 Debian 上走一遍（local），再把它当远程主机（127.0.0.1:8022，见 remote-host.sh）走一遍（remote）。
# 截图 <情形>-first.png（第一轮答完）、<情形>-reopened.png（再打开后第二轮答完）、<情形>-released.png（终端接走后）。
#   ZETA_PHONE_OWNER=<任务名> tools/phone-run tools/phone-tests/handoff-wrapped.sh
#
# Claude 是假的（fake/remote.jsonl，两轮）。假 Claude 自己不在 ~/.claude/sessions 登记，这里在它外面套两层：fake-claude.sh 换成不 exec 的
# 包装，里面的 inner.sh 像 Claude Code 那样用自己的进程号登记 --resume 的会话、再 exec 成假 Claude。shell 函数那种包装在
# HostStopTest、HostHandoffTest 里用真的 zeta_host.sh 测（假 Claude 按路径起，套不上叫 claude 的函数）。
# 测试会话放在 Debian 的 $DIR 和 ~/$PROJECT 里，脚本退出时删掉，连同它留下的登记；vd stop（phone-run 收尾）撤掉假 Claude、改回设置。
set -euo pipefail
. "$ZETA_ROOT/tools/phone-tests/lib.sh"

DIR=/tmp/zeta-handoff-wrapped
PROJECT=.claude/projects/${DIR//\//-}
ID=$(cat /proc/sys/kernel/random/uuid)
FAKE=.config/zeta-dev/fake-claude
HELPER=.cache/zeta-dev/bin/zeta-helper

cleanup() {
  vd debian "for f in ~/.claude/sessions/*.json; do grep -qs $ID \$f || continue; kill \$(basename \$f .json) 2>/dev/null; rm -f \$f; done; \
rm -rf ~/$PROJECT $DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT

line() {
  jq -nc --arg type "$1" --arg uuid "$2" --argjson message "$3" --arg id "$ID" --arg cwd "$DIR" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" '{type: $type, cwd: $cwd, sessionId: $id, uuid: $uuid, timestamp: $ts, message: $message}'
}
{
  line user u1 '{"role": "user", "content": "wrapped probe"}'
  line assistant a1 '{"role": "assistant", "model": "claude-sonnet-5-5", "content": [{"type": "text", "text": "probe reply"}]}'
} | vd debian --stdin "mkdir -p $DIR ~/$PROJECT && cat > ~/$PROJECT/$ID.jsonl" >/dev/null

# 放好假 Claude，再在外面套两层（每次 vd fake 都会把 fake-claude.sh 换回原样，所以每种情形都重套）
wrapped_fake() {
  vd fake "$HERE/fake/remote.jsonl" >/dev/null
  vd debian --stdin "cd ~/$FAKE && mv fake-claude.sh real.sh && cat > inner.sh && chmod +x inner.sh && \
printf '#!/bin/bash\n\"\$(dirname \"\$0\")/inner.sh\" \"\$@\"\n' > fake-claude.sh && chmod +x fake-claude.sh" >/dev/null <<'EOF'
#!/bin/bash
# 像 Claude Code 那样用自己的进程号登记 --resume 的会话，再 exec 成假 Claude（进程号不变）
id='' prev=''
for a in "$@"; do
  [ "$prev" = --resume ] && id=$a
  prev=$a
done
reg=$HOME/.claude/sessions/$$.json
start=$(sed 's/.*) //' /proc/$$/stat | cut -d' ' -f20)
mkdir -p "${reg%/*}"
printf '{"pid":%s,"sessionId":"%s","procStart":"%s","kind":"sdk-cli","status":"idle"}' $$ "$id" "$start" >"$reg"
exec "$(dirname "$0")/real.sh" "$@"
EOF
}

open_session() {
  android "am start --user 0 --display $DISPLAY_ID --activity-single-top -n $PKG/com.dzshzx.zeta.ui.MainActivity \
--es com.dzshzx.zeta.extra.CONVERSATION $ID >/dev/null"
  sleep 8
}

# 登记着这个会话、进程还活着的那个进程号
registered() {
  vd debian "for f in ~/.claude/sessions/*.json; do grep -qs '\"sessionId\":\"$ID\"' \$f || continue; p=\$(basename \$f .json); \
kill -0 \$p 2>/dev/null && echo \$p; done" | tr -d '\r'
}

check() {
  local name=$1 dir key outer claude mark occupant external result
  wrapped_fake
  android "touch /data/local/tmp/zeta-wrapped-mark"
  NEWER=/data/local/tmp/zeta-wrapped-mark log=''
  open_session
  send "first"
  wait_result 1 60 || { vd shot "$name-first-timeout.png" >/dev/null; exit 1; }
  sleep 1
  vd shot "$name-first.png" >/dev/null

  # 一、包装生效：pid 文件记外面那层，登记的是它的子进程，带着记号
  dir=${log%/out.jsonl}
  key=${dir##*/}
  outer=$(android "cat $dir/pid" | tr -d '\r')
  claude=$(registered)
  [[ -n $claude && $claude != *$'\n'* ]] || { echo "$name：登记着这个会话的活进程不是一个（$claude）"; exit 1; }
  [[ $outer != "$claude" ]] || { echo "$name：pid 文件记的就是登记的进程，包装没生效"; exit 1; }
  mark=$(vd debian "tr '\\0' '\\n' < /proc/$claude/environ | grep '^ZETA_HOST='" | tr -d '\r')
  [[ $mark == "ZETA_HOST=zeta-dev/$key" ]] || { echo "$name：登记的进程身上的记号是「$mark」，该是 zeta-dev/$key"; exit 1; }
  echo "$name：pid 文件记外面那层 $outer，登记的 Claude Code 是 $claude，记号 $mark"

  # 二、本包的进程不算别处占用：helper 说没人占着、列表不标 external；再按会话号打开，原来那个进程接着答完第二轮
  occupant=$(vd debian "ZETA_NAME=zeta-dev ~/$HELPER occupant $ID" | tr -d '\r')
  [[ $occupant == *'"status":null'* ]] || { echo "$name：zeta-helper occupant 把自己的进程当成别处的：$occupant"; exit 1; }
  external=$(vd debian "ZETA_NAME=zeta-dev ~/$HELPER list" | tr -d '\r' | jq -r --arg id "$ID" 'select(.id == $id) | .external')
  [[ $external == false ]] || { echo "$name：会话列表里这个会话的 external 是「$external」，该是 false"; exit 1; }
  open_session
  send "second"
  wait_result 2 60 || { vd shot "$name-reopened-timeout.png" >/dev/null; echo "$name：第二轮没答完（多半是出了「别处运行」卡片）"; exit 1; }
  sleep 1
  vd shot "$name-reopened.png" >/dev/null
  [[ $(registered) == "$claude" ]] || { echo "$name：接着答的不是原来那个进程"; exit 1; }
  echo "$name：没当成别处占用（occupant 无人、external 为 false），原来的进程接着答完第二轮"

  # 三、终端里接回：hook 让 Zeta 这边闲着的进程退出，外面那层一起没了
  vd debian "echo '{\"session_id\":\"$ID\",\"source\":\"resume\"}' | ZETA_NAME=zeta-dev ~/$HELPER session-start" >/dev/null
  result=$(vd debian "for _ in \$(seq 150); do kill -0 $claude 2>/dev/null || kill -0 $outer 2>/dev/null || break; sleep 0.1; done
if kill -0 $claude 2>/dev/null; then echo Claude Code 还在; elif kill -0 $outer 2>/dev/null; then echo 外面那层还在; else echo released; fi" | tr -d '\r')
  [[ $result == released ]] || { echo "$name：终端接回后 $result"; exit 1; }
  sleep 2
  vd shot "$name-released.png" >/dev/null
  echo "$name：终端接回时 Zeta 这边的进程（连同外面那层）退出了"
}

# 一、设备自己的 Debian（phone-run 已经冷启动了测试版）
check local

# 二、经 ssh 连回自己的远程主机
use_loopback_host r-wrapped
check remote
