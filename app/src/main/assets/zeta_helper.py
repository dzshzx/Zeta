"""Zeta 在 Debian 里执行的小助手：读 Claude Code 自己保存的会话记录（~/.claude/projects）。

由应用经标准输入送进 `python3 - <命令> [参数]`，输出一行一个 JSON。
  list                 所有会话的概要，新的在前
  history <文件>        一个会话里要显示的消息，形状与 stream-json 输出一致
  delete <文件>         删除一个会话记录
  rename <文件> <标题>   给会话改名（追加一行 custom-title，与 Claude Code 自己的 /rename 相同）
  hosts                仍在后台运行的 Claude Code 进程（应用被杀后重新接上用）；顺手清掉已经结束的
"""
import glob
import json
import os
import shutil
import sys

ROOT = os.path.expanduser("~/.claude/projects")
HOSTS = os.path.expanduser("~/.cache/zeta/hosts")
SESSIONS = os.path.expanduser("~/.claude/sessions")
MAX_TEXT = 6000
MAX_EVENTS = 800


def out(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")


def clip(value):
    if isinstance(value, str):
        return value if len(value) <= MAX_TEXT else value[:MAX_TEXT] + "\n…（已截断）"
    if isinstance(value, list):
        return [clip(v) for v in value]
    if isinstance(value, dict):
        return {k: clip(v) for k, v in value.items()}
    return value


def image_count(content):
    return sum(1 for b in content if isinstance(b, dict) and b.get("type") == "image") if isinstance(content, list) else 0


def proc_start(pid):
    """进程的启动时刻（/proc/<pid>/stat 第 22 项），用来识别 pid 被别的进程复用。"""
    try:
        with open("/proc/%d/stat" % pid) as f:
            return f.read().rsplit(")", 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def host_pids():
    pids = set()
    for path in glob.glob(os.path.join(HOSTS, "*", "pid")):
        try:
            pids.add(int(open(path).read().strip()))
        except (OSError, ValueError):
            pass
    return pids


def external_sessions():
    """正被别的 Claude Code 进程（比如终端里的）占用的会话 id。"""
    mine = host_pids()
    busy = set()
    for path in glob.glob(os.path.join(SESSIONS, "*.json")):
        try:
            info = json.load(open(path))
            pid = int(info["pid"])
        except (OSError, ValueError, KeyError, TypeError):
            continue
        if pid in mine:
            continue
        started = proc_start(pid)
        if started is None or (info.get("procStart") and str(info["procStart"]) != started):
            continue
        busy.add(info.get("sessionId"))
    return busy


def user_text(content):
    """用户亲手输入的文字；工具结果、斜杠命令回显、系统注入的内容返回 None。"""
    if isinstance(content, str):
        text = content
    else:
        text = "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    text = text.strip()
    if not text or text.startswith(("<command-name>", "<local-command", "<system-reminder>", "<task-notification>",
                                    "Caveat:", "[Request interrupted")):
        return None
    return text


def summarize(path):
    title = first = cwd = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            # 先按子串筛，绝大多数行（请求快照等）不必解析
            if '"ai-title"' in line or '"custom-title"' in line:
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                if o.get("type") == "custom-title" and o.get("customTitle"):
                    title = o["customTitle"]
                elif o.get("type") == "ai-title" and o.get("aiTitle"):
                    title = o["aiTitle"]
            elif first is None and '"type":"user"' in line:
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                if o.get("type") != "user" or o.get("isSidechain") or o.get("isMeta"):
                    continue
                cwd = cwd or o.get("cwd")
                content = (o.get("message") or {}).get("content") or ""
                text = user_text(content) or ("[图片]" if image_count(content) else None)
                if text:
                    first = text
            elif cwd is None and '"cwd"' in line:
                try:
                    cwd = json.loads(line).get("cwd")
                except ValueError:
                    pass
    if first is None:
        return None
    return {"id": os.path.basename(path)[:-6], "file": path, "cwd": cwd or "", "title": title or "",
            "preview": first[:200], "updated": int(os.path.getmtime(path) * 1000)}


def cmd_list():
    files = glob.glob(os.path.join(ROOT, "*", "*.jsonl"))
    files.sort(key=os.path.getmtime, reverse=True)
    external = external_sessions()
    for path in files[:300]:
        try:
            item = summarize(path)
        except OSError:
            continue
        if item:
            item["external"] = item["id"] in external
            out(item)


def cmd_history(path):
    events = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if '"type":"user"' not in line and '"type":"assistant"' not in line:
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            kind = o.get("type")
            if kind not in ("user", "assistant") or o.get("isSidechain") or o.get("isMeta"):
                continue
            message = o.get("message") or {}
            content = message.get("content")
            if kind == "user":
                if isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
                    blocks = [{"type": "tool_result", "tool_use_id": b.get("tool_use_id"), "is_error": bool(b.get("is_error")),
                               "content": clip(b.get("content"))}
                              for b in content if isinstance(b, dict) and b.get("type") == "tool_result"]
                    events.append({"type": "user", "message": {"content": blocks}})
                else:
                    if o.get("isCompactSummary"):
                        continue
                    text = user_text(content or "")
                    # 图片本体不回传（一张就是几百 KB），只告诉界面有几张
                    blocks = [{"type": "image"}] * image_count(content)
                    if text:
                        blocks.append({"type": "text", "text": text})
                    if blocks:
                        events.append({"type": "user", "message": {"content": blocks}})
            else:
                blocks = [clip(b) for b in (content or []) if isinstance(b, dict) and b.get("type") in ("text", "thinking", "tool_use")]
                for b in blocks:
                    b.pop("signature", None)
                if blocks:
                    events.append({"type": "assistant", "message": {"id": message.get("id"), "content": blocks}})
    skipped = max(0, len(events) - MAX_EVENTS)
    out({"type": "zeta_history", "skipped": skipped})
    for e in events[skipped:]:
        out(e)


def scan_host(directory):
    """读一个后台进程的输出日志：到第几行了、是哪个会话、这一轮是否还在进行、有没有等着用户批准的调用。"""
    lines = 0
    session = None
    busy = False
    pending = None
    mode = None
    with open(os.path.join(directory, "out.jsonl"), encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.endswith("\n"):
                break  # 还没写完的半行留给之后的跟读
            lines += 1
            if '"stream_event"' in line[:40]:
                busy = True
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            kind = o.get("type")
            if kind == "result":
                busy = False
                pending = None
            elif kind in ("system", "assistant", "user"):
                busy = True
                session = o.get("session_id") or session
                if kind == "system":
                    mode = o.get("permissionMode") or mode
                if kind == "user" and pending is not None:
                    content = (o.get("message") or {}).get("content")
                    wanted = (pending.get("request") or {}).get("tool_use_id")
                    if isinstance(content, list) and any(isinstance(b, dict) and b.get("tool_use_id") == wanted for b in content):
                        pending = None
            elif kind == "control_request" and (o.get("request") or {}).get("subtype") == "can_use_tool":
                pending = o
            elif kind == "control_cancel_request" and pending is not None and pending.get("request_id") == o.get("request_id"):
                pending = None
    return lines, session, busy, pending, mode


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def cmd_hosts():
    for directory in sorted(glob.glob(os.path.join(HOSTS, "*")), key=os.path.getmtime, reverse=True):
        try:
            pid = int(read_text(os.path.join(directory, "pid")))
            with open("/proc/%d/cmdline" % pid, "rb") as f:
                alive = b"claude" in f.read()
        except (OSError, ValueError):
            alive = False
        if not alive or os.path.exists(os.path.join(directory, "exit")):
            shutil.rmtree(directory, ignore_errors=True)
            continue
        try:
            lines, session, busy, pending, mode = scan_host(directory)
        except OSError:
            continue
        session = session or read_text(os.path.join(directory, "session")) or None
        files = glob.glob(os.path.join(ROOT, "*", session + ".jsonl")) if session else []
        out({"key": os.path.basename(directory), "pid": pid, "cwd": read_text(os.path.join(directory, "cwd")),
             "title": read_text(os.path.join(directory, "title")), "session": session, "file": files[0] if files else None,
             "lines": lines, "busy": busy, "pending": pending, "mode": mode})


def cmd_rename(path, title):
    title = " ".join(title.split())[:200]
    if not title:
        sys.exit("empty title")
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        needs_newline = f.tell() > 0 and (f.seek(-1, os.SEEK_END), f.read(1))[1] != b"\n"
    entry = {"type": "custom-title", "customTitle": title, "sessionId": os.path.basename(path)[:-6]}
    with open(path, "a", encoding="utf-8") as f:
        f.write(("\n" if needs_newline else "") + json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")
    out({"type": "zeta_renamed", "title": title})


def inside_root(path):
    real = os.path.realpath(path)
    return real.startswith(os.path.realpath(ROOT) + os.sep) and real.endswith(".jsonl")


def main():
    args = sys.argv[1:]
    if args[:1] == ["list"]:
        cmd_list()
    elif args[:1] == ["history"] and len(args) == 2 and inside_root(args[1]):
        cmd_history(args[1])
    elif args[:1] == ["delete"] and len(args) == 2 and inside_root(args[1]):
        os.remove(args[1])
        out({"type": "zeta_deleted"})
    elif args[:1] == ["rename"] and len(args) == 3 and inside_root(args[1]):
        cmd_rename(args[1], args[2])
    elif args[:1] == ["hosts"]:
        cmd_hosts()
    else:
        sys.exit("usage: list | history <file> | delete <file> | rename <file> <title> | hosts")


main()
