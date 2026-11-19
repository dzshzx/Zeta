#!/bin/bash
# 真机：search_personal 的音频、文件、下载记录、健康数据。测试版里真跑一轮对话，让 Claude 逐个调用，只回条数、首行的标题和字段名，
# 不回内容；产物里只有这段汇总（summary.txt）。健康权限用权限健康页一键授予的同一条命令授予。
# 测完删掉这次建的会话和测试版的对话目录，permissionMode 恢复原样。用法：tools/phone-run tools/phone-tests/personal-sources.sh
set -uo pipefail
mark="ZPROBE$(date +%s)"
home=/data/local/zeta/debian/rootfs/home/dzshzx
hosts=$home/.cache/zeta-dev/hosts
ref=/data/local/tmp/zeta-personal-sources.ref
prefs=/data/user/0/$PKG/shared_prefs/zeta.xml
user=$(android 'am get-current-user' | tr -d '\r')

perms="READ_STEPS READ_SLEEP READ_EXERCISE READ_HEART_RATE READ_RESTING_HEART_RATE READ_WEIGHT READ_OXYGEN_SATURATION READ_HEALTH_DATA_IN_BACKGROUND READ_HEALTH_DATA_HISTORY"
android "for p in $perms; do pm grant --user $user $PKG android.permission.health.\$p; done"
granted=$(android "dumpsys package $PKG | grep -c 'android.permission.health.*granted=true'" | tr -d '\r')
echo "健康权限已授予 $granted 项（应为 9）"

# 不让工具调用停在批准上：这一轮用 bypassPermissions，收尾时改回去。设置文件原地写回，属主和 SELinux 标签不变
old=$(vd prefs permissionMode | sed -n 's/.*<string name="permissionMode">\(.*\)<\/string>.*/\1/p')
setmode() {
  local add=''
  [[ -n $1 ]] && add="sed -i 's#</map>#<string name=\"permissionMode\">$1</string></map>#' /data/local/tmp/zeta-prefs.tmp;"
  android "am force-stop $PKG; grep -v 'name=\"permissionMode\"' $prefs > /data/local/tmp/zeta-prefs.tmp; $add \
    cat /data/local/tmp/zeta-prefs.tmp > $prefs; rm -f /data/local/tmp/zeta-prefs.tmp"
}
# 这一轮的对话目录：测试版里在 ref 之后建的（测试版的租约在手，别人不会同时在测试版里开对话）
mine="find $hosts -mindepth 1 -maxdepth 1 -type d -newer $ref"
cleanup() {
  android "am startservice -n $PKG/com.dzshzx.zeta.core.AgentService -a $PKG.action.STOP >/dev/null 2>&1"
  sleep 3
  # 会话记录按 init 里的 session_id 找，里面要有本次的标记才删
  android "for d in \$($mine); do sid=\$(grep -o '\"session_id\":\"[^\"]*\"' \$d/out.jsonl | head -1 | cut -d'\"' -f4); \
    for f in $home/.claude/projects/*/\$sid.jsonl; do [ -n \"\$sid\" ] && grep -qs '$mark' \$f && rm -rf \$f \${f%.jsonl} $home/.claude/todos/\$sid* && echo 删了会话; done; \
    rm -rf \$d && echo 删了对话目录; done; rm -f $ref"
  setmode "$old"
  echo "permissionMode 已恢复为 ${old:-（未设置）}"
}
trap cleanup EXIT
trap 'exit 143' TERM INT # 超时被结束时也收尾（phone-run 先发 TERM，10 秒后才 KILL）
android "touch $ref"
setmode bypassPermissions
vd launch
sleep 12

prompt="$mark Test of the phone tool search_personal. Call it with limit 3 for each source: audio, files, downloads, health. \
Then for audio call it again with the id of the first item that is a recording (marked luyin), if any. Never show me any content from the results. \
Reply only with one line per call: source, list or id, number of lines in the result, the first line of the result cut before the first \
parenthesis, isError yes or no. For health also list the words before the first full-width colon on each later line, joined by slashes. \
For the id call instead list which of these Chinese labels appear at the start of a line, written in pinyin: luyinji beizhu, \
luyinji zhaiyao, luyinji zhuanxie (voice recorder note, summary, transcript), or the line saying they could not be read, nothing else."
vd tap 319 2413
sleep 1
vd text "${prompt// /%s}"
sleep 1
vd tap 1089 2543

# 等这一轮的 result（最多 200 秒，整个脚本要在 phone-run 的 300 秒内跑完），只取最后那段回答
out=''
for _ in $(seq 50); do
  sleep 4
  out=$(android "for d in \$($mine); do grep '\"type\":\"result\"' \$d/out.jsonl | tail -1; done")
  [[ -n $out ]] && break
done
if [[ -z $out ]]; then
  echo "200 秒内没有等到回答"
  exit 1
fi
jq -r '.result // .subtype' <<<"$out" > summary.txt
cat summary.txt
echo "收尾前健康权限仍授予 $(android "dumpsys package $PKG | grep -c 'android.permission.health.*granted=true'" | tr -d '\r') 项"
grep -qiE 'iserror:? *yes|, *yes *$' summary.txt && exit 1
exit 0
