"""Zeta 在 Debian 里执行的小助手：读 Claude Code 自己保存的会话记录（~/.claude/projects）。

由应用经标准输入送进 `python3 - <命令> [参数]`，输出一行一个 JSON。
  list                 所有会话的概要，新的在前
  history <文件>        一个会话里要显示的消息，形状与 stream-json 输出一致
  delete <文件>         删除一个会话记录
  rename <文件> <标题>   给会话改名（追加一行 custom-title，与 Claude Code 自己的 /rename 相同）
  hosts                仍在后台运行的 Claude Code 进程（应用被杀后重新接上用）；顺手清掉已经结束的
  image <文件> <位置>    取回会话记录里的一张图片（位置是 history 给出的“行号.块序号[.内层序号]”）
  file-image <路径>     读取 Linux 环境里的一个图片文件
"""
import base64
import glob
import json
import os
import re
import shutil
import sys
import time

ROOT = os.path.expanduser("~/.claude/projects")
HOSTS = os.path.expanduser("~/.cache/zeta/hosts")
SESSIONS = os.path.expanduser("~/.claude/sessions")
UPLOADS = os.path.expanduser("~/.cache/zeta/files")
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
               ".gif": "image/gif", ".bmp": "image/bmp", ".heic": "image/heic", ".avif": "image/avif"}
MAX_IMAGE_BYTES = 30 * 1024 * 1024
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
COMMAND_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
COMMAND_ARGS = re.compile(r"<command-args>\s*(.*?)\s*</command-args>", re.S)
COMMAND_OUT = re.compile(r"<local-command-(?:stdout|stderr)>(.*?)</local-command-(?:stdout|stderr)>", re.S)
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
    """用户亲手输入的文字（斜杠命令还原成 /名字 参数）；工具结果、系统注入的内容返回 None。"""
    if isinstance(content, str):
        text = content
    else:
        text = "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    text = text.strip()
    if text.startswith("<command-name>"):
        name = COMMAND_NAME.search(text)
        args = COMMAND_ARGS.search(text)
        if not name:
            return None
        command = name.group(1) if name.group(1).startswith("/") else "/" + name.group(1)
        return (command + " " + (args.group(1) if args else "")).strip()
    if not text or text.startswith(("<command-name>", "<local-command", "<system-reminder>", "<task-notification>",
                                    "Caveat:", "[Request interrupted")):
        return None
    return text


def summarize(path):
    title = first = prompt = cwd = None
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
            elif prompt is None and '"type":"user"' in line:
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
                    # 附件行不当预览；斜杠命令开头的会话，预览优先用后面第一句真正的话
                    lines = [l for l in text.split("\n") if not l.startswith("[Attached file: ")]
                    text = "\n".join(lines).strip() or os.path.basename(text.split("\n")[0][16:].rstrip("]"))
                    first = first or text
                    if not text.startswith("/"):
                        prompt = text
            elif cwd is None and '"cwd"' in line:
                try:
                    cwd = json.loads(line).get("cwd")
                except ValueError:
                    pass
    if first is None:
        return None
    return {"id": os.path.basename(path)[:-6], "file": path, "cwd": cwd or "", "title": title or "",
            "preview": (prompt or first)[:200], "updated": int(os.path.getmtime(path) * 1000)}


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
        for number, line in enumerate(f):
            if '"type":"user"' not in line and '"type":"assistant"' not in line and '"local_command"' not in line:
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            kind = o.get("type")
            if kind == "system" and o.get("subtype") == "local_command":
                # 斜杠命令的输出（/context、/usage 等），当作一段回答显示
                text = "\n".join(part.strip() for part in COMMAND_OUT.findall(o.get("content") or ""))
                text = ANSI.sub("", text).strip()
                if text:
                    events.append({"type": "assistant", "message": {"id": o.get("uuid"), "content": [{"type": "text", "text": clip(text)}]}})
                continue
            if kind not in ("user", "assistant") or o.get("isSidechain") or o.get("isMeta"):
                continue
            message = o.get("message") or {}
            content = message.get("content")
            if kind == "user":
                if isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
                    # 图片本体不回传（一张就是几百 KB），只给出它在记录里的位置，界面要看时再用 image 命令取
                    blocks = [{"type": "tool_result", "tool_use_id": b.get("tool_use_id"), "is_error": bool(b.get("is_error")),
                               "content": result_content(b.get("content"), "%d.%d" % (number, index))}
                              for index, b in enumerate(content) if isinstance(b, dict) and b.get("type") == "tool_result"]
                    events.append({"type": "user", "message": {"content": blocks}})
                else:
                    if o.get("isCompactSummary"):
                        continue
                    text = user_text(content or "")
                    blocks = [{"type": "image", "ref": "%d.%d" % (number, index)}
                              for index, b in enumerate(content if isinstance(content, list) else [])
                              if isinstance(b, dict) and b.get("type") == "image"]
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


def result_content(content, position):
    if not isinstance(content, list):
        return clip(content)
    blocks = []
    for index, b in enumerate(content):
        if isinstance(b, dict) and b.get("type") == "image":
            blocks.append({"type": "image", "ref": "%s.%d" % (position, index)})
        elif isinstance(b, dict) and b.get("type") == "text":
            blocks.append({"type": "text", "text": clip(b.get("text") or "")})
    return blocks


def cmd_image(path, position):
    """按 history 给出的位置取回一张图片。记录只追加不改写，所以行号是稳定的。"""
    index = [int(part) for part in position.split(".")]
    with open(path, encoding="utf-8", errors="replace") as f:
        for number, line in enumerate(f):
            if number == index[0]:
                break
        else:
            sys.exit("no such line")
    block = (json.loads(line).get("message") or {}).get("content")[index[1]]
    if len(index) > 2:
        block = block["content"][index[2]]
    source = block.get("source") or {}
    if block.get("type") != "image" or source.get("type") != "base64":
        sys.exit("not an image")
    out({"type": "zeta_image", "media_type": source.get("media_type"), "data": source.get("data")})


def cmd_file_image(path):
    path = os.path.expanduser(path)
    kind = IMAGE_TYPES.get(os.path.splitext(path)[1].lower())
    if kind is None or not os.path.isfile(path) or os.path.getsize(path) > MAX_IMAGE_BYTES:
        sys.exit("not a readable image")
    with open(path, "rb") as f:
        out({"type": "zeta_image", "media_type": kind, "data": base64.b64encode(f.read()).decode("ascii")})


def prune_uploads():
    """应用送进来的附件留七天，够会话用完；之后清掉。"""
    limit = time.time() - 7 * 86400
    for directory in glob.glob(os.path.join(UPLOADS, "*")):
        try:
            if os.path.getmtime(directory) < limit:
                shutil.rmtree(directory, ignore_errors=True)
        except OSError:
            pass


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
    prune_uploads()
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
        title = read_text(os.path.join(directory, "title"))
        if not title and files:
            summary = summarize(files[0]) or {}
            title = summary.get("title") or (summary.get("preview") or "")[:40]
        out({"key": os.path.basename(directory), "pid": pid, "cwd": read_text(os.path.join(directory, "cwd")),
             "title": title, "session": session, "file": files[0] if files else None,
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
    elif args[:1] == ["image"] and len(args) == 3 and inside_root(args[1]):
        cmd_image(args[1], args[2])
    elif args[:1] == ["file-image"] and len(args) == 2:
        cmd_file_image(args[1])
    else:
        sys.exit("usage: list | history <file> | delete <file> | rename <file> <title> | hosts | image <file> <ref> | file-image <path>")


main()
