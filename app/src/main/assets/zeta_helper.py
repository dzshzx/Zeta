"""Zeta 在 Debian 里执行的小助手：读 Claude Code 自己保存的会话记录（~/.claude/projects）。

由应用经标准输入送进 `python3 - <命令> [参数]`，输出一行一个 JSON。
  list                 所有会话的概要，新的在前
  history <文件>        一个会话里要显示的消息，形状与 stream-json 输出一致；用户消息带上它在记录里的 uuid
  delete <文件>...      删除会话记录，连同 Claude Code 存在同名目录里的东西（工具结果、子代理记录等）
  rename <文件> <标题>   给会话改名（追加一行 custom-title，与 Claude Code 自己的 /rename 相同）
  archive <id>... <0|1> 归档或取消归档会话（只是列表里不显示）
  hosts                仍在后台运行的 Claude Code 进程（应用被杀后重新接上用）；顺手清掉已经结束的
  image <文件> <位置>    取回会话记录里的一张图片（位置是 history 给出的“行号.块序号[.内层序号]”）
  file-image <路径>     读取 Linux 环境里的一个图片文件
  search <关键词>       在会话的对话内容里找关键词，每找到一个会话就输出一行（边找边出），最后一行是汇总
  lookup <id>...       按 id 取会话概要（形状同 list），找不到的不输出；置顶的会话不在 list 的范围里时用
  export <文件>...      把会话导出成 Markdown，一个会话一行
  ls <目录>             文件浏览：列出一个目录（第一行概要，之后一行一项）
  read <文件> <字节数>   文件浏览：读一个文本文件的开头，最多这么多字节
  mounts <名字…>        共享文件夹：/mnt/shared 下各项有没有挂上、当前用户能不能读写
"""
import base64
import glob
import json
import os
import re
import shutil
import stat
import sys
import time

ROOT = os.path.expanduser("~/.claude/projects")
# Zeta 自己的目录按包分开（正式版 zeta，测试版 zeta-dev，由应用经 ZETA_NAME 传进来）；Claude Code 的会话两个包共用
NAME = os.environ.get("ZETA_NAME") or "zeta"
HOSTS = os.path.expanduser("~/.cache/%s/hosts" % NAME)
SESSIONS = os.path.expanduser("~/.claude/sessions")
UPLOADS = os.path.expanduser("~/.cache/%s/files" % NAME)
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
               ".gif": "image/gif", ".bmp": "image/bmp", ".heic": "image/heic", ".avif": "image/avif"}
MAX_IMAGE_BYTES = 30 * 1024 * 1024
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
COMMAND_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
COMMAND_ARGS = re.compile(r"<command-args>\s*(.*?)\s*</command-args>", re.S)
COMMAND_OUT = re.compile(r"<local-command-(?:stdout|stderr)>(.*?)</local-command-(?:stdout|stderr)>", re.S)
MAX_TEXT = 6000
MAX_EVENTS = 800
MAX_PATCH_LINES = 2000  # 改文件结果里的对比超过这么多行就不带，界面改用参数里的新旧内容自己比
TODO_TOOLS = ("TodoWrite", "TaskCreate", "TaskUpdate")


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


# 消息开头的附件行（见 Kotlin 的 AttachedPaths）：复制进来的文件 [Attached file: 路径]，
# 引用的路径 [Attached file: @"路径"] 和 [Attached folder: @"路径"]
ATTACHED = re.compile(r'^\[Attached (?:file|folder): (?:@"(.+)"|(.+))\]$')


def attached_name(line):
    """附件行里路径的最后一段；不是附件行返回 None。"""
    m = ATTACHED.match(line)
    if not m:
        return None
    return os.path.basename((m.group(1) or m.group(2)).rstrip("/")) or "/"


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


def rewind_cut(line):
    """Claude Code 剪会话（改写、删除之前的消息，stream-json 的 rewind_conversation 或终端里的 /rewind）时追加的记号：
    一行 last-prompt，explicit 且 rewound。被剪掉的内容还留在文件里，只是不在当前这条线上了。
    返回 (被剪掉的第一条消息的 uuid, 剪完后最后一条消息的 uuid，剪光了是 None)；不是这种行返回 None。"""
    if '"rewound":true' not in line or '"last-prompt"' not in line:
        return None
    try:
        o = json.loads(line)
    except ValueError:
        return None
    if o.get("type") != "last-prompt" or o.get("explicit") is not True or o.get("rewound") is not True:
        return None
    return o.get("cutUuid"), o.get("leafUuid")


def cut_at(owners, cut, leaf):
    """照剪切记号算出前面保留几项（owners 是每一项出自哪一行的 uuid）：从被剪的那条消息起都去掉；
    找不到它时保留到剪完后的最后一条消息；剪光了一项不留。"""
    if cut in owners:
        return owners.index(cut)
    if leaf is None:
        return 0
    if leaf in owners:
        return len(owners) - owners[::-1].index(leaf)
    return len(owners)


def summarize(path):
    custom = ai = first = prompt = cwd = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            # 先按子串筛，绝大多数行（请求快照等）不必解析
            if '"ai-title"' in line or '"custom-title"' in line:
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                # 和 Claude Code 一样：改过的名字（custom-title）压过自动起的（ai-title），不看先后；
                # Claude Code 补写元数据时会把两行按 custom、ai 的顺序再追加一遍
                if o.get("type") == "custom-title" and o.get("customTitle"):
                    custom = o["customTitle"]
                elif o.get("type") == "ai-title" and o.get("aiTitle"):
                    ai = o["aiTitle"]
            elif '"leafUuid":null' in line and rewind_cut(line) is not None:
                # 剪到了第一条消息之前：之前的提问不算了，预览改用之后的第一句
                first = prompt = None
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
                    lines = [l for l in text.split("\n") if attached_name(l) is None]
                    text = "\n".join(lines).strip() or attached_name(text.split("\n")[0]) or text
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
    return {"id": os.path.basename(path)[:-6], "file": path, "cwd": cwd or "", "title": custom or ai or "",
            "preview": (prompt or first)[:200], "updated": int(os.path.getmtime(path) * 1000)}


ARCHIVE = os.path.expanduser("~/.config/%s/archived" % NAME)


def archived_sessions():
    """归档的会话只是不在列表里显示，记录文件原样留着；这里存的是它们的 id，一行一个。"""
    try:
        with open(ARCHIVE, encoding="utf-8") as f:
            return {line.strip() for line in f if line.strip()}
    except OSError:
        return set()


def cmd_archive(sessions, on):
    ids = archived_sessions()
    # 顺手清掉记录文件已经不在的
    alive = {os.path.basename(p)[:-6] for p in glob.glob(os.path.join(ROOT, "*", "*.jsonl"))}
    ids = (ids & alive) | set(sessions) if on else (ids & alive) - set(sessions)
    os.makedirs(os.path.dirname(ARCHIVE), exist_ok=True)
    with open(ARCHIVE + ".tmp", "w", encoding="utf-8") as f:
        f.write("".join(i + "\n" for i in sorted(ids)))
    os.replace(ARCHIVE + ".tmp", ARCHIVE)
    for session in sessions:
        out({"type": "zeta_archived", "id": session, "archived": bool(on)})


def cmd_list():
    files = glob.glob(os.path.join(ROOT, "*", "*.jsonl"))
    files.sort(key=os.path.getmtime, reverse=True)
    external = external_sessions()
    archived = archived_sessions()
    for path in files[:300]:
        try:
            item = summarize(path)
        except OSError:
            continue
        if item:
            item["external"] = item["id"] in external
            item["archived"] = item["id"] in archived
            out(item)


USAGE_KEYS = ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")


def cmd_history(path):
    events = []
    owners = []  # 每一项出自哪一行（uuid），遇到剪切记号时据此去掉被剪的部分
    compacted = False
    with open(path, encoding="utf-8", errors="replace") as f:
        for number, line in enumerate(f):
            if '"type":"user"' not in line and '"type":"assistant"' not in line and '"local_command"' not in line \
                    and '"queued_command"' not in line and '"rewound":true' not in line \
                    and '"compact_boundary"' not in line:
                continue
            cut = rewind_cut(line)
            if cut is not None:
                keep = cut_at(owners, *cut)
                del events[keep:], owners[keep:]
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            kind = o.get("type")
            row = o.get("uuid")
            if kind == "system" and o.get("subtype") == "compact_boundary":
                # 压缩：界面显示一条提示，用量统计据此把上下文占用换成压缩后的大小
                meta = o.get("compactMetadata") or {}
                events.append({"type": "system", "subtype": "compact_boundary", "compact_metadata": {
                    "trigger": meta.get("trigger"), "pre_tokens": meta.get("preTokens"), "post_tokens": meta.get("postTokens")}})
                owners.append(row)
                compacted = True
                continue
            if kind == "system" and o.get("subtype") == "local_command":
                # 斜杠命令的输出（/context、/usage 等），当作一段回答显示
                text = "\n".join(part.strip() for part in COMMAND_OUT.findall(o.get("content") or ""))
                text = ANSI.sub("", text).strip()
                if text:
                    events.append({"type": "assistant", "message": {"id": o.get("uuid"), "content": [{"type": "text", "text": clip(text)}]}})
                    owners.append(row)
                continue
            if kind == "attachment":
                # 回答进行中插进来的消息：记成 queued_command 附件，不是普通的 user 条目
                attachment = o.get("attachment") or {}
                if attachment.get("type") != "queued_command" or o.get("isSidechain"):
                    continue
                kind, content = "user", attachment.get("prompt")
            elif kind not in ("user", "assistant") or o.get("isSidechain") or o.get("isMeta"):
                continue
            else:
                content = (o.get("message") or {}).get("content")
            message = o.get("message") or {}
            if kind == "user":
                if isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
                    # 图片本体不回传（一张就是几百 KB），只给出它在记录里的位置，界面要看时再用 image 命令取
                    blocks = [{"type": "tool_result", "tool_use_id": b.get("tool_use_id"), "is_error": bool(b.get("is_error")),
                               "content": result_content(b.get("content"), "%d.%d" % (number, index))}
                              for index, b in enumerate(content) if isinstance(b, dict) and b.get("type") == "tool_result"]
                    event = {"type": "user", "message": {"content": blocks}}
                    patch = edit_patch(o.get("toolUseResult"))
                    if patch:
                        event["tool_use_result"] = patch
                    events.append(event)
                    owners.append(row)
                else:
                    if o.get("isCompactSummary"):
                        continue
                    text = user_text(content or "")
                    # 记录里 /compact 命令写在压缩提示之后，那条提示已经说明了，不再显示一遍命令
                    if compacted and text and text.split(" ")[0] == "/compact":
                        compacted = False
                        continue
                    if text:
                        compacted = False
                    blocks = [{"type": "image", "ref": "%d.%d" % (number, index)}
                              for index, b in enumerate(content if isinstance(content, list) else [])
                              if isinstance(b, dict) and b.get("type") == "image"]
                    if text:
                        blocks.append({"type": "text", "text": text})
                    if blocks:
                        event = {"type": "user", "message": {"content": blocks}}
                        # 改写、分支时要用它告诉 Claude Code 剪哪条（插话并进来的附件行不算一轮的开头，不给）
                        if o.get("type") == "user" and row:
                            event["uuid"] = row
                        events.append(event)
                        owners.append(row)
            else:
                blocks = [clip(b) for b in (content or []) if isinstance(b, dict) and b.get("type") in ("text", "thinking", "tool_use")]
                for b in blocks:
                    b.pop("signature", None)
                usage = message.get("usage")
                usage = {k: usage[k] for k in USAGE_KEYS if isinstance(usage, dict) and k in usage} or None
                if blocks or usage:
                    events.append({"type": "assistant", "message": {"id": message.get("id"), "model": message.get("model"),
                                                                    "usage": usage, "content": blocks}})
                    owners.append(row)
    skipped = max(0, len(events) - MAX_EVENTS)
    out({"type": "zeta_history", "skipped": skipped, "todo_seed": todo_seed(events[:skipped])})
    for e in events[skipped:]:
        out(e)


def edit_patch(result):
    """改文件工具（Edit、Write）的结构化结果里界面要用的部分：带真实行号的对比，以及是不是新建。"""
    if not isinstance(result, dict) or not isinstance(result.get("structuredPatch"), list):
        return None
    patch = result["structuredPatch"]
    if sum(len(h.get("lines") or []) for h in patch if isinstance(h, dict)) > MAX_PATCH_LINES:
        return None
    return {"type": result.get("type"), "structuredPatch": clip(patch)}


def todo_seed(skipped):
    """没载入的那部分历史里的清单工具调用和它们的结果，界面据此接上更早建的任务清单。"""
    seed, ids = [], set()
    for e in skipped:
        message = e.get("message") or {}
        content = [b for b in message.get("content") or [] if isinstance(b, dict)]
        if e["type"] == "assistant":
            uses = [b for b in content if b.get("type") == "tool_use" and b.get("name") in TODO_TOOLS]
            ids.update(b.get("id") for b in uses)
            if uses:
                seed.append({"type": "assistant", "message": {"id": message.get("id"), "content": uses}})
        elif e["type"] == "user":
            results = [b for b in content if b.get("type") == "tool_result" and b.get("tool_use_id") in ids]
            if results:
                seed.append({"type": "user", "message": {"content": results}})
    return seed


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
    o = json.loads(line)
    content = (o.get("message") or {}).get("content") or (o.get("attachment") or {}).get("prompt")
    block = content[index[1]]
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
    """读一个后台进程的输出日志：到第几行了、是哪个会话、这一轮是否还在进行、有没有等着用户批准的调用、
    还有几个后台任务在跑。"""
    lines = 0
    session = None
    busy = False
    tasks = 0
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
            session = o.get("session_id") or session
            if kind == "result":
                busy = False
                pending = None
            elif kind == "system":
                mode = o.get("permissionMode") or mode
                # 一轮结束后还会有 task_notification、background_tasks_changed、status 之类的收尾事件，
                # 只有 init 表示新的一轮开始了（后台任务跑完后 Claude Code 自己起的一轮也以 init 开头）
                if o.get("subtype") == "init":
                    busy = True
                elif o.get("subtype") == "background_tasks_changed":
                    # 每次都是完整清单：最后一条就是现在的情况。这一轮结束了它们也照样在跑
                    tasks = len(o.get("tasks") or [])
            elif kind == "command_lifecycle":
                if o.get("state") == "started":
                    busy = True
            elif kind in ("assistant", "user"):
                # 后台子代理的消息在主对话这一轮结束后还会陆续写进来，不代表这一轮还在进行
                if not o.get("parent_tool_use_id"):
                    busy = True
                if kind == "user" and pending is not None:
                    content = (o.get("message") or {}).get("content")
                    wanted = (pending.get("request") or {}).get("tool_use_id")
                    if isinstance(content, list) and any(isinstance(b, dict) and b.get("tool_use_id") == wanted for b in content):
                        pending = None
            elif kind == "control_request" and (o.get("request") or {}).get("subtype") == "can_use_tool":
                pending = o
            elif kind == "control_cancel_request" and pending is not None and pending.get("request_id") == o.get("request_id"):
                pending = None
    return lines, session, busy, pending, mode, tasks


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
            lines, session, busy, pending, mode, tasks = scan_host(directory)
        except OSError:
            continue
        session = session or read_text(os.path.join(directory, "session")) or None
        files = glob.glob(os.path.join(ROOT, "*", session + ".jsonl")) if session else []
        # 会话记录里的标题（改过的名字、自动起的标题）比启动时记下的新
        summary = (summarize(files[0]) or {}) if files else {}
        title = summary.get("title") or read_text(os.path.join(directory, "title")) or (summary.get("preview") or "")[:40]
        out({"key": os.path.basename(directory), "pid": pid, "cwd": read_text(os.path.join(directory, "cwd")),
             "title": title, "session": session, "file": files[0] if files else None,
             "lines": lines, "busy": busy, "pending": pending, "mode": mode, "tasks": tasks})


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


SEARCH_SECONDS = 10
SEARCH_LIMIT = 80
SESSION_ID = re.compile(r"[0-9a-fA-F-]{8,64}")


def spoken_text(o):
    """一条记录里用户或 Claude 亲口说的话，返回 (角色, 文字)；工具调用、工具结果、系统注入的内容返回 (None, None)。"""
    kind = o.get("type")
    if o.get("isSidechain") or o.get("isMeta"):
        return None, None
    if kind == "attachment":
        attachment = o.get("attachment") or {}
        if attachment.get("type") != "queued_command" or not attachment.get("prompt"):
            return None, None
        return "user", user_text(attachment["prompt"])
    content = (o.get("message") or {}).get("content")
    if kind == "user":
        if o.get("isCompactSummary") or (isinstance(content, list) and any(
                isinstance(b, dict) and b.get("type") == "tool_result" for b in content)):
            return None, None
        return "user", user_text(content or "")
    if kind == "assistant" and isinstance(content, list):
        text = "\n".join(b.get("text") or "" for b in content if isinstance(b, dict) and b.get("type") == "text").strip()
        return "assistant", text or None
    return None, None


def snippet(text, at, length):
    """命中处前面留一点、后面多留些，折成一行，侧栏里两行放得下。"""
    start = max(0, at - 20)
    end = min(len(text), at + length + 70)
    body = " ".join(text[start:end].split())
    return ("…" if start > 0 else "") + body + ("…" if end < len(text) else "")


def cmd_search(query):
    """只看用户和 Claude 说的话。逐行先按子串粗筛（工具结果、请求快照这类大行不解析），每个会话取第一处命中就换下一个；
    限时限量，免得几百 MB 的记录把手机拖住。"""
    needle = query.strip().lower()
    if not needle:
        sys.exit("empty query")
    # 记录里的字符串是 JSON 转义过的：粗筛也用转义后的样子去比
    escaped = json.dumps(needle, ensure_ascii=False)[1:-1]
    files = glob.glob(os.path.join(ROOT, "*", "*.jsonl"))
    files.sort(key=os.path.getmtime, reverse=True)
    files = files[:300]
    deadline = time.monotonic() + SEARCH_SECONDS
    scanned = matched = 0
    complete = True
    for path in files:
        if time.monotonic() > deadline or matched >= SEARCH_LIMIT:
            complete = False
            break
        scanned += 1
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    if '"type":"user"' in line:
                        if '"tool_result"' in line:
                            continue
                    elif '"type":"assistant"' in line:
                        if '"type":"text"' not in line:
                            continue
                    elif '"queued_command"' not in line:
                        continue
                    if escaped not in line.lower():
                        continue
                    try:
                        role, text = spoken_text(json.loads(line))
                    except ValueError:
                        continue
                    if text and "[Attached " in text:
                        # 附件行只留文件名，摘要里不出现一长串路径
                        text = "\n".join("附件 " + attached_name(l) if attached_name(l) else l for l in text.split("\n"))
                    at = text.lower().find(needle) if text else -1
                    if at < 0:
                        continue  # 只是命中了路径、id 之类的字段
                    matched += 1
                    out({"type": "zeta_match", "id": os.path.basename(path)[:-6], "role": role,
                         "snippet": snippet(text, at, len(needle))})
                    sys.stdout.flush()
                    break
        except OSError:
            continue
    out({"type": "zeta_search_done", "scanned": scanned, "total": len(files), "matched": matched, "complete": complete})


def cmd_lookup(ids):
    external = external_sessions()
    archived = archived_sessions()
    for session in ids:
        for path in glob.glob(os.path.join(ROOT, "*", session + ".jsonl")):
            try:
                item = summarize(path)
            except OSError:
                continue
            if item:
                item["external"] = item["id"] in external
                item["archived"] = item["id"] in archived
                out(item)
                break


def one_line(value, limit=120):
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[:limit] + "…"


def tool_detail(name, data):
    """工具调用的一行摘要：命令、路径、关键词之类，照 Eta 导出里的“参数”。"""
    if not isinstance(data, dict):
        return ""
    for key in {"Bash": ("command",), "Read": ("file_path",), "Write": ("file_path",), "Edit": ("file_path",),
                "MultiEdit": ("file_path",), "NotebookEdit": ("notebook_path",), "Grep": ("pattern",),
                "Glob": ("pattern",), "WebSearch": ("query",), "WebFetch": ("url",), "Task": ("description", "prompt"),
                "Agent": ("description", "prompt"), "Skill": ("skill", "command")}.get(name, ()):
        if data.get(key):
            # 多行命令只取第一行
            first = str(data[key]).strip().split("\n")[0]
            return one_line(first + (" …" if "\n" in str(data[key]).strip() else ""))
    return one_line(json.dumps(data, ensure_ascii=False, separators=(", ", ": "))) if data else ""


def quote_block(lines):
    return "\n".join("> " + line if line else ">" for line in "\n\n".join(lines).split("\n"))


def cmd_export(path):
    """照 Eta 的会话导出：标题，然后按顺序是用户和助手的话；工具调用只留一行摘要，思考过程折叠起来，图片只记张数。"""
    summary = summarize(path) or {}
    title = " ".join((summary.get("title") or (summary.get("preview") or "")[:40] or "对话").split())
    blocks = []  # (种类, 内容)；工具调用的结果在后面几行，先占位，最后一起写
    owners = []  # 每一块出自哪一行（uuid），遇到剪切记号时据此去掉被剪的部分
    results = {}
    row = None

    def add(block):
        blocks.append(block)
        owners.append(row)

    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if '"type":"user"' not in line and '"type":"assistant"' not in line and '"queued_command"' not in line \
                    and '"compact_boundary"' not in line and '"rewound":true' not in line:
                continue
            cut = rewind_cut(line)
            if cut is not None:
                keep = cut_at(owners, *cut)
                del blocks[keep:], owners[keep:]
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            kind = o.get("type")
            row = o.get("uuid")
            if o.get("isSidechain"):
                continue
            if kind == "system":
                if o.get("subtype") == "compact_boundary":
                    add(("notice", "上下文已压缩"))
                continue
            content = (o.get("message") or {}).get("content")
            if kind == "user" and isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        results[b.get("tool_use_id")] = b
                continue
            if kind == "user" and not o.get("isMeta") and not o.get("isCompactSummary"):
                content = content if isinstance(content, (str, list)) else ""
                raw = user_text(content)
                if raw is None and '"[Request interrupted' in line:
                    add(("notice", "已停止"))
                    continue
                images = image_count(content)
                if raw or images:
                    add(("user", (raw or "", images)))
                continue
            if kind == "attachment":
                role, text = spoken_text(o)
                if text:
                    add(("user", (text, image_count((o.get("attachment") or {}).get("prompt")))))
                continue
            if kind != "assistant" or not isinstance(content, list):
                continue
            for b in content:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text" and (b.get("text") or "").strip():
                    add(("text", b["text"].strip()))
                elif b.get("type") == "thinking" and (b.get("thinking") or "").strip():
                    add(("thinking", b["thinking"].strip()))
                elif b.get("type") in ("tool_use", "server_tool_use"):
                    add(("tool", (b.get("id"), b.get("name") or "工具", tool_detail(b.get("name"), b.get("input")))))
                elif b.get("tool_use_id") and b.get("type", "").endswith("_tool_result"):
                    results[b["tool_use_id"]] = {}  # 服务端工具（advisor、网页搜索）的结果跟在同一条消息里
    parts = ["# " + title]
    answering = False
    for kind, value in blocks:
        if kind == "user":
            text, images = value
            lines = text.split("\n")
            files = [m.group(1) or m.group(2) for m in map(ATTACHED.match, lines) if m]
            body = "\n".join(l for l in lines if not ATTACHED.match(l)).strip()
            block = "## 用户"
            if body:
                block += "\n\n" + body
            for name in files:
                block += "\n\n- " + name
            if images:
                # 图片本体太大，只记张数
                block += "\n\n_图片：%d 张_" % images
            parts.append(block)
            answering = False
            continue
        if kind == "notice":
            parts.append(quote_block([value]))
            answering = False
            continue
        # 一轮回答里的文字、思考和工具调用归在同一个“助手”标题下
        if not answering:
            parts.append("## 助手")
            answering = True
        if kind == "text":
            parts.append(value)
        elif kind == "thinking":
            parts.append("<details>\n<summary>思考过程</summary>\n\n" + value + "\n\n</details>")
        else:
            tool_id, name, detail = value
            result = results.get(tool_id)
            status = "结果未知" if result is None else "失败" if result.get("is_error") else "已完成"
            lines = ["工具：%s（%s）" % (name, status)]
            if detail:
                lines.append("参数：" + detail)
            if result is not None and result.get("is_error"):
                error = result.get("content")
                if isinstance(error, list):
                    error = " ".join(b.get("text", "") for b in error if isinstance(b, dict) and b.get("type") == "text")
                if one_line(error):
                    lines.append("结果：" + one_line(ANSI.sub("", str(error))))
            parts.append(quote_block(lines))
    out({"type": "zeta_export", "file": path, "title": title, "markdown": "\n\n".join(parts) + "\n"})


def inside_root(path):
    real = os.path.realpath(path)
    return real.startswith(os.path.realpath(ROOT) + os.sep) and real.endswith(".jsonl")


def session_dir(path):
    """会话记录 <项目>/<会话 id>.jsonl 旁边 Claude Code 自己建的同名目录（tool-results、subagents 等）。
    只认 ~/.claude/projects/<项目>/<会话 id>/ 这一层、本身不是符号链接的真目录；别的情况返回 None。"""
    if not path.endswith(".jsonl"):
        return None
    root = os.path.realpath(ROOT)
    base = os.path.abspath(path)[:-len(".jsonl")]
    if not SESSION_ID.fullmatch(os.path.basename(base)) or os.path.islink(base) or not os.path.isdir(base):
        return None
    real = os.path.realpath(base)
    if os.path.dirname(os.path.dirname(real)) != root or os.path.basename(real) != os.path.basename(base):
        return None
    if os.path.dirname(os.path.realpath(path)) != os.path.dirname(real):
        return None
    return real


def cmd_delete(paths):
    for path in paths:
        directory = session_dir(path)
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        # rmtree 删到符号链接只删链接本身，不跟进去
        cleaned = False
        if directory:
            shutil.rmtree(directory, ignore_errors=True)
            cleaned = not os.path.lexists(directory)
        out({"type": "zeta_deleted", "file": path, "dir": cleaned})


def cmd_export_many(paths):
    for path in paths:
        try:
            cmd_export(path)
        except Exception:  # 一个会话导不出来不影响别的
            out({"type": "zeta_export_failed", "file": path})


MAX_LIST = 2000


def display_name(name):
    """文件名里不是 UTF-8 的字节换成替代字符，免得输出 JSON 时出错（这样的项只能看，进不去）。"""
    return name.encode("utf-8", "surrogateescape").decode("utf-8", "replace")


def path_error(error):
    if isinstance(error, FileNotFoundError):
        return "missing"
    if isinstance(error, NotADirectoryError):
        return "not_dir"
    if isinstance(error, PermissionError):
        return "denied"
    return "error"


def cmd_ls(path):
    """列目录。先只取名字和类型排好序，再给前 MAX_LIST 项取大小和时间：几万个文件的目录也不会输出太多。"""
    path = os.path.normpath(path)
    try:
        with os.scandir(path) as it:
            items = []
            for entry in it:
                try:
                    directory = entry.is_dir()  # 指向目录的符号链接也算目录，可以点进去
                except OSError:
                    directory = False
                items.append((entry, directory))
    except OSError as error:
        out({"type": "zeta_ls", "path": path, "error": path_error(error)})
        return
    items.sort(key=lambda item: (not item[1], item[0].name.lower(), item[0].name))
    out({"type": "zeta_ls", "path": path, "total": len(items), "truncated": len(items) > MAX_LIST})
    for entry, directory in items[:MAX_LIST]:
        item = {"name": display_name(entry.name), "dir": directory, "kind": "other", "size": 0, "mtime": 0}
        try:
            link = entry.is_symlink()
            info = entry.stat(follow_symlinks=not link)
            if link:
                item["kind"] = "link"
                item["target"] = display_name(os.readlink(entry.path))
                try:
                    info = entry.stat()  # 链接本身的大小没有意义，取它指向的
                except OSError:
                    item["broken"] = True
            elif directory:
                item["kind"] = "dir"
            elif entry.is_file(follow_symlinks=False):
                item["kind"] = "file"
            item["size"] = info.st_size if stat.S_ISREG(info.st_mode) else 0
            item["mtime"] = int(info.st_mtime)
        except OSError:
            pass
        out(item)


def decode_text(data, truncated):
    """先按 UTF-8；截断处切开的半个字不算错；不是 UTF-8 再试 GB18030（中文 Windows 那边来的文件多半是这个）。"""
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as error:
        if truncated and error.start >= len(data) - 3:
            try:
                return data[:error.start].decode("utf-8")
            except UnicodeDecodeError:
                pass
    try:
        return data.decode("gb18030")
    except UnicodeDecodeError:
        return data.decode("utf-8", "replace")


def cmd_read(path, limit):
    limit = max(1, min(int(limit), 4 * 1024 * 1024))
    try:
        info = os.stat(path)
        if not stat.S_ISREG(info.st_mode):
            out({"type": "zeta_read", "error": "not_file"})
            return
        with open(path, "rb") as f:
            data = f.read(limit + 1)
    except OSError as error:
        out({"type": "zeta_read", "error": path_error(error)})
        return
    truncated = len(data) > limit
    data = data[:limit]
    text = None if b"\0" in data else decode_text(data, truncated)
    # 没有 NUL 的二进制文件也常见：解出来一堆控制字符或替代字符的，同样当二进制
    if text:
        odd = sum(1 for c in text if c == "\ufffd" or (c < " " and c not in "\t\n\r\f\v\b\x1b"))
        if odd * 20 > len(text):
            text = None
    if text is None:
        out({"type": "zeta_read", "size": info.st_size, "binary": True})
        return
    out({"type": "zeta_read", "size": info.st_size, "truncated": truncated, "text": text})


def mount_points():
    """本进程看到的挂载点（mountinfo 第 5 列，空格等字符写成 \\ooo）。
    共享文件夹和 /workspace 往往在同一个分区上，os.path.ismount 认不出这种绑定挂载，只能查这里。"""
    points = set()
    try:
        with open("/proc/self/mountinfo", encoding="utf-8", errors="replace") as f:
            for line in f:
                fields = line.split(" ")
                if len(fields) > 4:
                    points.add(re.sub(r"\\([0-7]{3})", lambda m: chr(int(m.group(1), 8)), fields[4]))
    except OSError:
        pass
    return points


def cmd_mounts(names):
    points = mount_points()
    for name in names:
        path = os.path.join("/mnt/shared", name)
        out({"name": name, "mounted": path in points, "read": os.access(path, os.R_OK | os.X_OK),
             "write": os.access(path, os.W_OK)})


def main():
    args = sys.argv[1:]
    if args[:1] == ["list"]:
        cmd_list()
    elif args[:1] == ["history"] and len(args) == 2 and inside_root(args[1]):
        cmd_history(args[1])
    elif args[:1] == ["delete"] and len(args) >= 2 and all(inside_root(a) for a in args[1:]):
        cmd_delete(args[1:])
    elif args[:1] == ["rename"] and len(args) == 3 and inside_root(args[1]):
        cmd_rename(args[1], args[2])
    elif args[:1] == ["archive"] and len(args) >= 3 and args[-1] in ("0", "1") and all(SESSION_ID.fullmatch(a) for a in args[1:-1]):
        cmd_archive(args[1:-1], args[-1] == "1")
    elif args[:1] == ["hosts"]:
        cmd_hosts()
    elif args[:1] == ["image"] and len(args) == 3 and inside_root(args[1]):
        cmd_image(args[1], args[2])
    elif args[:1] == ["file-image"] and len(args) == 2:
        cmd_file_image(args[1])
    elif args[:1] == ["search"] and len(args) == 2:
        cmd_search(args[1])
    elif args[:1] == ["lookup"] and all(SESSION_ID.fullmatch(a) for a in args[1:]):
        cmd_lookup(args[1:])
    elif args[:1] == ["export"] and len(args) >= 2 and all(inside_root(a) for a in args[1:]):
        cmd_export_many(args[1:])
    elif args[:1] == ["ls"] and len(args) == 2 and args[1].startswith("/"):
        cmd_ls(args[1])
    elif args[:1] == ["read"] and len(args) == 3 and args[1].startswith("/") and args[2].isdigit():
        cmd_read(args[1], args[2])
    elif args[:1] == ["mounts"]:
        cmd_mounts([n for n in args[1:] if re.fullmatch(r"[A-Za-z0-9._-]{1,48}", n) and n not in (".", "..")])
    else:
        sys.exit("usage: list | history <file> | delete <file>... | rename <file> <title> | archive <id>... <0|1> | hosts"
                 " | image <file> <ref> | file-image <path> | search <query> | lookup <id>... | export <file>..."
                 " | ls <dir> | read <file> <bytes> | mounts <name…>")


main()
