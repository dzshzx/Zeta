# Zeta 在 Debian 里用来托管 Claude Code 进程的脚本（bash）。
#
# Claude Code 不直接挂在应用的管道上，而是脱离出去：输入来自一个命名管道，输出追加到日志文件。
# 应用这一头只是“接上去”——把自己的标准输入转进管道、把日志跟读到标准输出。
# 这样应用被系统回收或被划掉时，进行到一半的任务照常跑完，重新打开应用再接上即可。
#
#   start  <标识> <工作目录> <标题> <claude 参数…>   启动并接上
#   attach <标识> <从第几行开始读>                    接上一个已在运行的
#   stop   <标识>                                     结束并清理
set -u
mode=$1
D="$HOME/.cache/zeta/hosts/$2"

if [ "$mode" = stop ]; then
  if [ -s "$D/pid" ]; then
    P=$(cat "$D/pid")
    kill "$P" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$P" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$P" 2>/dev/null
  fi
  rm -rf "$D"
  exit 0
fi

if [ "$mode" = start ]; then
  cwd=$3
  title=$4
  shift 4
  cd "$cwd" 2>/dev/null || { echo "zeta: 进不去工作目录 $cwd" >&2; exit 97; }
  rm -rf "$D"
  mkdir -p "$D" && mkfifo "$D/in" || { echo "zeta: 建不了 $D" >&2; exit 97; }
  : > "$D/out.jsonl"
  printf '%s' "$cwd" > "$D/cwd"
  printf '%s' "$title" > "$D/title"
  # 管道用读写方式打开并一直持有：写的一方来来去去，Claude Code 都不会读到“输入结束”。
  setsid bash -c '
    D=$1; shift
    exec 3<>"$D/in"
    claude "$@" <&3 >>"$D/out.jsonl" 2>>"$D/err.log" &
    P=$!
    echo $P > "$D/pid"
    # 看门狗：没有应用接着、这一轮又已经结束的进程，闲置约十分钟后自行结束，
    # 免得应用被杀之后它一直占着内存。还在干活或在等用户确认的不动。
    (
      n=0
      while [ -d "$D" ] && kill -0 $P 2>/dev/null; do
        sleep "${ZETA_IDLE_STEP:-60}"
        A=$(cat "$D/attach" 2>/dev/null)
        if [ -n "$A" ] && kill -0 "$A" 2>/dev/null; then
          n=0
        elif tail -n 1 "$D/out.jsonl" | grep -q "\"type\":\"result\""; then
          n=$((n + 1))
          [ $n -ge "${ZETA_IDLE_LIMIT:-10}" ] && kill $P
        else
          n=0
        fi
      done
    ) &
    W=$!
    wait $P
    code=$?
    kill $W 2>/dev/null
    [ -d "$D" ] && echo $code > "$D/exit"
  ' zeta-run "$D" "$@" </dev/null >/dev/null 2>&1 &
  for _ in $(seq 100); do [ -s "$D/pid" ] && break; sleep 0.05; done
  from=1
else
  from=$3
fi

[ -s "$D/pid" ] || { echo "zeta: 进程没有启动" >&2; cat "$D/err.log" >&2 2>/dev/null; exit 98; }
P=$(cat "$D/pid")
echo $$ > "$D/attach"

# 两头任一结束就整体退出：应用没了（输入结束）只是断开；Claude Code 没了（跟读结束）则报告退出码。
tail -n "+$from" --pid="$P" -f "$D/out.jsonl" &
T=$!
cat <&0 > "$D/in" &
C=$!
wait -n
kill "$T" "$C" 2>/dev/null
if [ -f "$D/exit" ]; then
  python3 -c '
import json, sys
d = sys.argv[1]
def read(name):
    try:
        return open(d + "/" + name, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""
print(json.dumps({"type": "zeta_exit", "code": int(read("exit").strip() or 1), "stderr": read("err.log")[-2000:]}))
' "$D"
  rm -rf "$D"
fi
