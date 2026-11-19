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
  storage [<id>…]      存储页：各类占多少空间、能清掉多少（<id> 是应用认为正在进行、要保护的会话）
  storage-sessions <项目目录> [<id>…]  存储页：一个项目里每个会话占的空间
  clean <类别> [<id>…]  存储页：清掉一类里能安全删的（规矩见“存储”一节）
  subagent <字节> <行> <tool_use id> <agent id|-> <会话 id>…
                       子代理查看页：发起它的 Agent 调用对应的记录，从这个位置起增量读（见“子代理”一节）
"""
import base64
import datetime
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


def read_pid(path):
    """托管目录里记的进程号（pid、attach 文件）；读不到返回 None。"""
    try:
        with open(path) as f:
            return int(f.read().strip())
    except (OSError, ValueError):
        return None


def proc_argv(pid):
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return f.read().split(b"\0")
    except OSError:
        return None


def claude_argv(pid):
    """进程 pid 是活着的 Claude Code 时返回它的命令行，否则（没有 pid、进程不在了、pid 被别的进程复用）返回 None。"""
    argv = proc_argv(pid) if pid else None
    return argv if argv and any(b"claude" in a for a in argv) else None


def registered_sessions():
    """Claude Code 每个进程都在 ~/.claude/sessions/<pid>.json 登记自己的会话（stream-json 的也一样）。
    逐个给出进程还活着的 (pid, 会话 id)；pid 被别的进程复用了（启动时刻对不上）的不算。"""
    for path in glob.glob(os.path.join(SESSIONS, "*.json")):
        try:
            with open(path, encoding="utf-8") as f:
                info = json.load(f)
            pid = int(info["pid"])
        except (OSError, ValueError, KeyError, TypeError):
            continue
        started = proc_start(pid)
        if started is None or (info.get("procStart") and str(info["procStart"]) != started):
            continue
        yield pid, info.get("sessionId")


def host_pids():
    pids = (read_pid(path) for path in glob.glob(os.path.join(HOSTS, "*", "pid")))
    return {pid for pid in pids if pid is not None}


def external_sessions():
    """正被别的 Claude Code 进程（比如终端里的）占用的会话 id。"""
    mine = host_pids()
    return {session for pid, session in registered_sessions() if pid not in mine}


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
                    event = {"type": "assistant", "message": {"id": message.get("id"), "model": message.get("model"),
                                                              "usage": usage, "content": blocks}}
                    if o.get("effort"):
                        event["effort"] = o["effort"]
                    events.append(event)
                    owners.append(row)
    skipped = max(0, len(events) - MAX_EVENTS)
    header = {"type": "zeta_history", "skipped": skipped, "todo_seed": todo_seed(events[:skipped])}
    header.update(last_answer(events))
    out(header)
    for e in events[skipped:]:
        out(e)


def last_answer(events):
    """主线上（剪切之后、不含子代理）最后一次真实回答用的模型和思考强度，重新打开对话时照它恢复。
    斜杠命令的输出没有模型；Claude Code 合成的回答（出错提示等）模型是 <synthetic>，都跳过。"""
    for e in reversed(events):
        model = e["message"].get("model") if e["type"] == "assistant" else None
        if model and not model.startswith("<"):
            return {"model": model, "effort": e.get("effort")}
    return {}


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


TASK_TOOLS = ("Bash", "Monitor", "Agent", "Task")


def epoch_ms(stamp):
    try:
        return int(datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp() * 1000)
    except (AttributeError, ValueError):
        return None


def scan_host(directory):
    """读一个后台进程的输出日志：到第几行了、是哪个会话、这一轮是否还在进行、有没有等着用户批准的调用、
    还有几个后台任务在跑，以及这些任务的事件（接上后重放给任务清单）。"""
    lines = 0
    session = None
    busy = False
    tasks = 0
    pending = None
    mode = None
    # 后台任务：最后一份清单；各任务的 task_started（带上发起它的工具调用和开始时间）和最近一条 task_progress
    listing = None
    started = {}
    progress = {}
    calls = {}
    stamp = None
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
                    listing = o
                elif o.get("subtype") == "task_started" and o.get("task_id"):
                    call = calls.get(o.get("tool_use_id"))
                    if call:
                        o["zeta_tool"] = call
                    if stamp:
                        o["zeta_at"] = stamp
                    started[o["task_id"]] = o
                elif o.get("subtype") == "task_progress" and o.get("task_id"):
                    progress[o["task_id"]] = o
            elif kind == "command_lifecycle":
                if o.get("state") == "started":
                    busy = True
            elif kind in ("assistant", "user"):
                stamp = epoch_ms(o.get("timestamp")) or stamp
                # 后台子代理的消息在主对话这一轮结束后还会陆续写进来，不代表这一轮还在进行
                if not o.get("parent_tool_use_id"):
                    busy = True
                    if kind == "assistant":
                        for block in (o.get("message") or {}).get("content") or []:
                            if isinstance(block, dict) and block.get("type") == "tool_use" and block.get("name") in TASK_TOOLS:
                                calls[block.get("id")] = {"name": block.get("name"), "input": block.get("input")}
                                if len(calls) > 64:
                                    calls.pop(next(iter(calls)))
                if kind == "user" and pending is not None:
                    content = (o.get("message") or {}).get("content")
                    wanted = (pending.get("request") or {}).get("tool_use_id")
                    if isinstance(content, list) and any(isinstance(b, dict) and b.get("tool_use_id") == wanted for b in content):
                        pending = None
            elif kind == "control_request" and (o.get("request") or {}).get("subtype") == "can_use_tool":
                pending = o
            elif kind == "control_cancel_request" and pending is not None and pending.get("request_id") == o.get("request_id"):
                pending = None
    # 重放的顺序：各任务的 task_started，再是清单（据此列出），最后是进展
    events = []
    if listing is not None:
        ids = [item.get("task_id") for item in listing.get("tasks") or []]
        events = [started[t] for t in ids if t in started] + [listing] + [progress[t] for t in ids if t in progress]
    return lines, session, busy, pending, mode, tasks, events


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def session_title(path):
    """会话记录里的标题，规矩同 summarize：改过的名字（custom-title）压过自动起的（ai-title），不看先后。
    只看这两种行（按字节筛，不解码别的行），没有标题返回 None。"""
    custom = ai = None
    try:
        with open(path, "rb") as f:
            for line in f:
                if b'-title"' not in line:
                    continue
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                if o.get("type") == "custom-title" and o.get("customTitle"):
                    custom = o["customTitle"]
                elif o.get("type") == "ai-title" and o.get("aiTitle"):
                    ai = o["aiTitle"]
    except OSError:
        return None
    return custom or ai


def cmd_hosts():
    prune_uploads()
    for directory in sorted(glob.glob(os.path.join(HOSTS, "*")), key=os.path.getmtime, reverse=True):
        pid = read_pid(os.path.join(directory, "pid"))
        if claude_argv(pid) is None or os.path.exists(os.path.join(directory, "exit")):
            shutil.rmtree(directory, ignore_errors=True)
            continue
        try:
            lines, session, busy, pending, mode, tasks, events = scan_host(directory)
        except OSError:
            continue
        session = session or read_text(os.path.join(directory, "session")) or None
        files = glob.glob(os.path.join(ROOT, "*", session + ".jsonl")) if session else []
        # 会话记录里的标题（改过的名字、自动起的标题）比启动时记下的新；都没有时才整份读一遍取开头那句
        title = (session_title(files[0]) if files else None) or read_text(os.path.join(directory, "title"))
        if not title and files:
            title = ((summarize(files[0]) or {}).get("preview") or "")[:40]
        out({"key": os.path.basename(directory), "pid": pid, "cwd": read_text(os.path.join(directory, "cwd")),
             "title": title, "session": session, "file": files[0] if files else None,
             "lines": lines, "busy": busy, "pending": pending, "mode": mode, "tasks": tasks, "task_events": events})


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
    只认 ~/.claude/projects/<项目>/<会话 id>/ 这一层，项目目录和它都按 safe_child 核（不是符号链接的真目录，
    真实路径就在那一层），记录文件本身也得真在这个项目目录里；别的情况返回 None。"""
    if not path.endswith(".jsonl"):
        return None
    project = os.path.dirname(os.path.abspath(path))
    name = os.path.basename(path)[:-len(".jsonl")]
    if not SESSION_ID.fullmatch(name) or os.path.dirname(os.path.realpath(project)) != os.path.realpath(ROOT):
        return None
    real = safe_child(project, name, True)
    if real is None or os.path.dirname(os.path.realpath(path)) != os.path.dirname(real):
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


# ---- 子代理 ----
# Claude Code 把每个子代理的记录写在 <项目>/<会话 id>/subagents/agent-<id>.jsonl，旁边的 agent-<id>.meta.json 记着
# 发起它的 Agent 调用（toolUseId）、类型、说明、模型，做完时加 finished，被停时加 stoppedByUser（2.1.328，ClaudeSubagentContractTest 钉住）。

SUBAGENT_ARG = re.compile(r"[A-Za-z0-9_-]{1,128}")
SUBAGENT_CHUNK = 4 * 1024 * 1024  # 一次最多读这么多字节，余下的下次接着读
SUBAGENT_META_KEYS = ("agentType", "description", "model", "finished", "stoppedByUser", "requestShape")


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            value = json.load(f)
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def subagent_dirs(sessions):
    """这些会话的 subagents 目录（真实路径在 ~/.claude/projects 里的）。"""
    root = os.path.realpath(ROOT) + os.sep
    for session in sessions:
        for directory in glob.glob(os.path.join(ROOT, "*", session, "subagents")):
            real = os.path.realpath(directory)
            if real.startswith(root) and os.path.isdir(real):
                yield real


def find_subagent(tool_use, hint, sessions):
    """发起子代理的 Agent 调用 tool_use 对应的记录，返回 (agent id, 记录文件, meta)；找不到返回 None。
    hint 是已经知道的 agent id（task_started 的 task_id），有它就不用逐个翻 meta。"""
    for directory in subagent_dirs(sessions):
        if hint:
            path = os.path.join(directory, "agent-%s.jsonl" % hint)
            meta = read_json(path[:-len(".jsonl")] + ".meta.json") or {}
            if os.path.isfile(path) and meta.get("toolUseId") in (None, tool_use):
                return hint, path, meta
        for name in sorted(os.listdir(directory)):
            if not (name.startswith("agent-") and name.endswith(".meta.json")):
                continue
            meta = read_json(os.path.join(directory, name))
            if meta and meta.get("toolUseId") == tool_use:
                agent = name[len("agent-"):-len(".meta.json")]
                path = os.path.join(directory, "agent-%s.jsonl" % agent)
                if os.path.isfile(path):
                    return agent, path, meta
    return None


def subagent_event(o, number):
    """子代理记录里的一行，转成和 history 一样形状的事件；不显示的行返回 None。number 是行号（图片的位置要用）。"""
    kind = o.get("type")
    if kind not in ("user", "assistant") or o.get("isMeta"):
        return None
    message = o.get("message") or {}
    content = message.get("content")
    if kind == "assistant":
        blocks = [clip(b) for b in (content or []) if isinstance(b, dict) and b.get("type") in ("text", "thinking", "tool_use")]
        for b in blocks:
            b.pop("signature", None)
        return {"type": "assistant", "message": {"id": message.get("id"), "content": blocks}} if blocks else None
    if isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
        blocks = [{"type": "tool_result", "tool_use_id": b.get("tool_use_id"), "is_error": bool(b.get("is_error")),
                   "content": result_content(b.get("content"), "%d.%d" % (number, index))}
                  for index, b in enumerate(content) if isinstance(b, dict) and b.get("type") == "tool_result"]
        event = {"type": "user", "message": {"content": blocks}}
        patch = edit_patch(o.get("toolUseResult"))
        if patch:
            event["tool_use_result"] = patch
        return event
    # 交给它的指令（第一行），以及之后续派给它的消息
    text = user_text(content or "")
    blocks = [{"type": "image", "ref": "%d.%d" % (number, index)}
              for index, b in enumerate(content if isinstance(content, list) else [])
              if isinstance(b, dict) and b.get("type") == "image"]
    if text:
        blocks.append({"type": "text", "text": clip(text)})
    if not blocks:
        return None
    event = {"type": "user", "message": {"content": blocks}}
    if number == 0:
        event["prompt"] = True
    return event


def cmd_subagent(offset, line, tool_use, hint, sessions):
    """子代理查看页：从字节 offset（第 line 行）起读一段记录。第一行是概要（记录文件、meta、读到哪了、这次读满了没有、多久没写了），
    之后一行一个事件，形状同 history，交给它的指令带 prompt。只交出写完了的整行，半行留给下次。找不到记录时只有一行 missing。"""
    found = find_subagent(tool_use, None if hint == "-" else hint, sessions)
    if found is None:
        out({"type": "zeta_subagent", "missing": True})
        return
    agent, path, meta = found
    reset = offset > os.path.getsize(path)  # 记录只追加，变短了就是换了文件：从头读
    if reset:
        offset, line = 0, 0
    with open(path, "rb") as f:
        f.seek(offset)
        data = f.read(SUBAGENT_CHUNK)
        full = len(data) == SUBAGENT_CHUNK
        end = data.rfind(b"\n") + 1
        if end == 0 and full:
            # 一行比一次能读的还长（大块工具结果）：跳过这一行，下次从它后面接着读
            skip = len(data)
            while True:
                piece = f.read(SUBAGENT_CHUNK)
                if not piece:
                    skip = 0  # 这一行还没写完
                    break
                if b"\n" in piece:
                    skip += piece.index(b"\n") + 1
                    break
                skip += len(piece)
            if skip:
                offset, line = offset + skip, line + 1
            data, end = b"", 0
    events = []
    for raw in data[:end].split(b"\n")[:-1]:
        number, line = line, line + 1
        if b'"type":"user"' not in raw and b'"type":"assistant"' not in raw:
            continue
        try:
            o = json.loads(raw.decode("utf-8", "replace"))
        except ValueError:
            continue
        event = subagent_event(o, number) if isinstance(o, dict) else None
        if event:
            events.append(event)
    offset += end
    skipped = max(0, len(events) - MAX_EVENTS)
    # 结局（meta 的 finished、输出流的 task_notification）可能先于最后几行写下：记录和 meta 多久都没动了，
    # 界面据此判断收齐了没有（子代理等一个很久的回答时记录早就不动了，结束时 meta 才刚写）
    stamps = [os.path.getmtime(p) for p in (path, path[:-len(".jsonl")] + ".meta.json") if os.path.exists(p)]
    idle = max(0, int((time.time() - max(stamps)) * 1000))
    out({"type": "zeta_subagent", "file": path, "agent_id": agent, "meta": {k: meta[k] for k in SUBAGENT_META_KEYS if k in meta},
         "offset": offset, "line": line, "more": full, "skipped": skipped, "reset": reset, "idle_ms": idle})
    for e in events[skipped:]:
        out(e)


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


# ---- 存储：设置里“存储”页的统计和清理 ----
# 统计只看大小和个数，不读文件内容（只有两处例外：在进行中的会话记录里找它用到的附件目录，在 settings.json 里取 cleanupPeriodDays）。
# 清理只删各类 plan_* 列出的候选，删之前每一项再照 safe_child 核一遍，规矩同 session_dir：只认预期的那一层、
# 不跟符号链接、真实路径落在预期目录下。正在进行的对话一律不碰：Claude Code 进程还活着的会话（终端里的、
# 两个 Zeta 包的都算）加上应用传进来的。会话记录本身（<项目>/<id>.jsonl）不在任何一类里，删会话走 delete。

CLAUDE_HOME = os.path.expanduser("~/.claude")
UUID_TEXT = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
UUID_NAME = re.compile(UUID_TEXT)
FRESH_SECONDS = 600          # 十分钟内动过的不删：可能正在写
UPLOAD_KEEP_SECONDS = 3600   # 附件一小时内的不删：可能还在输入框里，没发出去
TMP_IDLE_SECONDS = 86400     # /tmp 里一天内动过的不删
TOOL_BUSY_SECONDS = 600      # 十分钟内启动的 npm 进程可能正在往缓存里写


def storage_tmp():
    """Debian 的 /tmp。单元测试经 ZETA_STORAGE_TEST_TMP 换成临时目录：名字必须以 zeta-test-tmp 开头，设错了也落不到别处。"""
    test = os.environ.get("ZETA_STORAGE_TEST_TMP") or ""
    if os.path.isabs(test) and os.path.basename(test.rstrip("/")).startswith("zeta-test-tmp"):
        return test
    return "/tmp"


def children(directory):
    try:
        with os.scandir(directory) as it:
            return sorted(entry.name for entry in it)
    except OSError:
        return []


def usage(path, seen):
    """path 占的磁盘空间（不跟符号链接），算法同 du：按块计，硬链接只算一次。
    返回 (字节, 文件数, 最近一次修改的时刻, 里面有没有套接字、管道之类的特殊文件)。"""
    total = files = 0
    newest = 0.0
    special = False
    stack = [path]
    while stack:
        current = stack.pop()
        try:
            info = os.lstat(current)
        except OSError:
            continue
        mode = info.st_mode
        if info.st_nlink > 1 and not stat.S_ISDIR(mode):
            key = (info.st_dev, info.st_ino)
            if key in seen:
                continue
            seen.add(key)
        total += info.st_blocks * 512
        newest = max(newest, info.st_mtime)
        if stat.S_ISDIR(mode):
            try:
                with os.scandir(current) as it:
                    stack.extend(entry.path for entry in it)
            except OSError:
                pass
        elif stat.S_ISREG(mode):
            files += 1
        elif not stat.S_ISLNK(mode):
            special = True
    return total, files, newest, special


def safe_child(root, name, directory):
    """root 下名叫 name 的一项能不能删，规矩照 session_dir：只认 root 的直接下一层；它自己和 root 都不是符号链接；
    种类和预期一致（directory 为真要真目录，否则要普通文件）；真实路径就是 root 的真实路径下的 name。
    能删返回真实路径，否则 None。"""
    if not name or name in (".", "..") or "/" in name or "\0" in name:
        return None
    if os.path.islink(root) or not os.path.isdir(root):
        return None
    path = os.path.join(root, name)
    try:
        mode = os.lstat(path).st_mode
    except OSError:
        return None
    if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
        return None
    real = os.path.realpath(path)
    if real != os.path.join(os.path.realpath(root), name):
        return None
    return real


def remove_child(root, name, directory, size):
    """删一个候选，删之前再核一遍。目录用 rmtree：Linux 上它按文件描述符逐层删，不跟进符号链接，
    删到一半被人换成链接也删不到外面去。返回腾出的字节数；没删成返回 None。"""
    real = safe_child(root, name, directory)
    if real is None:
        return None
    if directory:
        if not shutil.rmtree.avoids_symlink_attacks:
            return None
        shutil.rmtree(real, ignore_errors=True)
    else:
        try:
            os.unlink(real)
        except OSError:
            pass
    if not os.path.lexists(real):
        return size
    return max(0, size - usage(real, set())[0])


def proc_started(pid):
    """进程启动的时刻（秒，Unix 时间）；进程不在了返回 None。"""
    ticks = proc_start(pid)
    if ticks is None:
        return None
    try:
        with open("/proc/stat") as f:
            boot = next(int(line.split()[1]) for line in f if line.startswith("btime "))
        return boot + int(ticks) / os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError, StopIteration):
        return 0.0  # 算不出来就当它很早以前就在了：按时间判断的东西一律不删


def host_alive(directory):
    """Zeta 托管的一个对话还在不在：Claude Code 进程活着，或者应用那头的接入脚本还连着。"""
    if claude_argv(read_pid(os.path.join(directory, "pid"))):
        return True
    attach = read_pid(os.path.join(directory, "attach"))
    argv = proc_argv(attach) if attach else None
    return bool(argv) and b"zeta-host" in argv


def host_session(directory, argv):
    """托管对话的会话 id：命令行里 --resume 的，或者输出开头 init 里的 session_id。"""
    for flag in (b"--resume", b"--session-id"):
        if flag in argv[:-1]:
            value = argv[argv.index(flag) + 1].decode("utf-8", "replace")
            if UUID_NAME.fullmatch(value):
                return value
    try:
        with open(os.path.join(directory, "out.jsonl"), "rb") as f:
            m = re.search(rb'"session_id":"(' + UUID_TEXT.encode() + rb')"', f.read(65536))
            return m.group(1).decode() if m else None
    except OSError:
        return None


class StorageContext:
    """一次统计或清理共用的信息：要保护的会话、还有记录的会话、还活着的 Claude Code 进程里最早的启动时刻。"""

    def __init__(self, keep):
        self.now = time.time()
        self.seen = set()
        self.keep = set(keep)
        self.oldest = None
        self.transcripts = {os.path.basename(p)[:-6] for p in glob.glob(os.path.join(ROOT, "*", "*.jsonl"))}
        for pid, session in registered_sessions():
            self.live(pid, session)
        # Zeta 托管的对话，两个包的目录都看：登记文件还没写出来时也认得
        for directory in glob.glob(os.path.expanduser("~/.cache/zeta*/hosts/*")):
            pid = read_pid(os.path.join(directory, "pid"))
            argv = claude_argv(pid)
            if argv:
                self.live(pid, host_session(directory, argv))

    def live(self, pid, session):
        if isinstance(session, str) and session:
            self.keep.add(session)
        started = proc_started(pid)
        if started is not None:
            self.oldest = started if self.oldest is None else min(self.oldest, started)

    def maybe_live(self, moment):
        """moment 时刻建的东西可能属于还活着的进程（在最早那个进程启动之后），或者是刚写的。"""
        if self.now - moment < FRESH_SECONDS:
            return True
        return self.oldest is not None and moment >= self.oldest - 60

    def uploads_in_use(self):
        """进行中的会话的记录里提到的附件目录（~/.cache/<NAME>/files/<目录>/…）。"""
        pattern = re.compile(rb"/\.cache/" + re.escape(NAME.encode()) + rb"/files/([0-9a-f]{8})/")
        used = set()
        for session in self.keep:
            if not UUID_NAME.fullmatch(session):
                continue
            for path in glob.glob(os.path.join(ROOT, "*", session + ".jsonl")):
                try:
                    with open(path, "rb") as f:
                        tail = b""
                        for chunk in iter(lambda: f.read(1 << 20), b""):
                            data = tail + chunk
                            used.update(m.decode() for m in pattern.findall(data))
                            tail = data[-256:]
                except OSError:
                    continue
        return used


class Plan:
    """一类东西：一共占多少，能删的候选（根目录, 名字, 是不是目录, 字节数），按来源分的小计。"""

    def __init__(self, ctx):
        self.ctx = ctx
        self.bytes = self.files = 0
        self.items = []
        self.parts = {}
        self.busy = None
        self.prune = []  # 清完以后试着删掉的空目录（根目录, 名字）

    def count(self, path):
        size, files, newest, special = usage(path, self.ctx.seen)
        self.bytes += size
        self.files += files
        return size, newest, special

    def add(self, root, name, directory, size, part=None):
        if safe_child(root, name, directory) is None:
            return
        self.items.append((root, name, directory, size))
        if part:
            total = self.parts.setdefault(part, [0, 0])
            total[0] += size
            total[1] += 1

    def report(self, key):
        return {"type": "zeta_storage", "key": key, "bytes": self.bytes, "files": self.files,
                "freeable": sum(i[3] for i in self.items), "items": len(self.items), "busy": self.busy,
                "parts": [{"name": k, "bytes": v[0], "items": v[1]} for k, v in sorted(self.parts.items())]}


def is_dir(path):
    return os.path.isdir(path) and not os.path.islink(path)


def plan_zeta_files(ctx):
    """应用送进来的附件：一小时内的、进行中的对话用到的不删。"""
    plan = Plan(ctx)
    root = UPLOADS
    used = ctx.uploads_in_use()
    for name in children(root):
        size, newest, _ = plan.count(os.path.join(root, name))
        if re.fullmatch(r"[0-9a-f]{8}", name) and name not in used and ctx.now - newest >= UPLOAD_KEEP_SECONDS:
            plan.add(root, name, True, size)
    return plan


def plan_zeta_hosts(ctx):
    """后台对话的输出目录：进程和接入脚本都已经不在的。"""
    plan = Plan(ctx)
    root = HOSTS
    for name in children(root):
        path = os.path.join(root, name)
        size, newest, _ = plan.count(path)
        if ctx.now - newest >= FRESH_SECONDS and not host_alive(path):
            plan.add(root, name, True, size)
    return plan


# Claude Code 按会话 id 存的东西：(~/.claude 下的目录, 名字的样子（第一组是会话 id）, 是不是目录)
RESIDUE_PLACES = (
    ("file-history", re.compile("(%s)" % UUID_TEXT), True),
    ("session-env", re.compile("(%s)" % UUID_TEXT), True),
    ("tasks", re.compile("(%s)" % UUID_TEXT), True),
    ("uploads", re.compile("(%s)" % UUID_TEXT), True),
    ("todos", re.compile(r"(%s)(?:-agent-[0-9a-f-]+)?\.json" % UUID_TEXT), False),
)


def plan_residue(ctx):
    """已经没有会话记录的会话留下的东西。只算残留，不算还有记录的会话的。"""
    plan = Plan(ctx)

    def consider(root, name, session, directory, part):
        path = os.path.join(root, name)
        if session in ctx.transcripts or not os.path.lexists(path):
            return
        size, newest, _ = plan.count(path)
        if session not in ctx.keep and ctx.now - newest >= FRESH_SECONDS:
            plan.add(root, name, directory, size, part)

    for place, pattern, directory in RESIDUE_PLACES:
        root = os.path.join(CLAUDE_HOME, place)
        for name in children(root):
            m = pattern.fullmatch(name)
            if m:
                consider(root, name, m.group(1), directory, place)
    # 项目目录里记录已经不在的同名目录（工具结果、子代理记录等）
    for project in children(ROOT):
        root = os.path.join(ROOT, project)
        if not is_dir(root):
            continue
        for name in children(root):
            if UUID_NAME.fullmatch(name):
                consider(root, name, name, True, "projects")
    return plan


def plan_named(ctx, place, pattern, directory, live_by_time=False):
    """~/.claude/<place> 里名字合 pattern 的项。名字里带会话 id 的（第一组），那个会话在进行就不删；
    live_by_time 为真时，最早那个活着的进程启动以后建的也不删（看不出属于哪个进程的东西）。"""
    plan = Plan(ctx)
    root = os.path.join(CLAUDE_HOME, place)
    for name in children(root):
        size, newest, _ = plan.count(os.path.join(root, name))
        m = pattern.fullmatch(name)
        if not m:
            continue
        if m.groups() and m.group(1) in ctx.keep:
            continue
        if ctx.now - newest < FRESH_SECONDS or (live_by_time and ctx.maybe_live(newest)):
            continue
        plan.add(root, name, directory, size)
    return plan


SNAPSHOT_NAME = re.compile(r"snapshot-[A-Za-z0-9]+-(\d{10,16})-[A-Za-z0-9]+(?:-[A-Za-z0-9._-]+)?\.sh")


def plan_snapshots(ctx):
    """Shell 快照：每个 Claude Code 进程一份，执行命令前先载入它。不删还活着的进程可能在用的（建于最早那个进程启动之后）。"""
    plan = Plan(ctx)
    root = os.path.join(CLAUDE_HOME, "shell-snapshots")
    for name in children(root):
        size, newest, _ = plan.count(os.path.join(root, name))
        m = SNAPSHOT_NAME.fullmatch(name)
        if m and not ctx.maybe_live(int(m.group(1)) / 1000) and not ctx.maybe_live(newest):
            plan.add(root, name, False, size)
    return plan


def running_tool(match, max_age=None):
    """有没有本用户看得见的进程合 match（参数是命令行各段）；max_age 给了时只看这么多秒内启动的。"""
    for entry in children("/proc"):
        if not entry.isdigit():
            continue
        argv = proc_argv(int(entry))
        if not argv or not argv[0] or not match([a.decode("utf-8", "replace") for a in argv if a]):
            continue
        if max_age is None:
            return True
        started = proc_started(int(entry))
        if started is not None and time.time() - started < max_age:
            return True
    return False


def command_names(argv):
    """命令行前两段的文件名（node、python 起的脚本，名字在第二段）；node 改过进程标题时第一段是“npm install …”，取第一个词。"""
    return [os.path.basename(a.split(" ")[0]) for a in argv[:2]]


def is_npm(argv):
    return any(n in ("npm", "npx", "npm-cli.js", "npx-cli.js") for n in command_names(argv))


def is_pip(argv):
    names = command_names(argv)
    return any(re.fullmatch(r"pip[0-9.]*", n) for n in names) or ("-m" in argv[1:3] and "pip" in argv[1:4]) \
        or (names[0] == "uv" and "pip" in argv[1:3])


def plan_home_cache(ctx, directory, names, busy_check, busy_name):
    """家目录下的一个包管理器缓存：directory 下 names 里的项（None 表示全部）能删；包管理器在跑时整类不删。"""
    plan = Plan(ctx)
    for name in children(directory):
        size, _, _ = plan.count(os.path.join(directory, name))
        if names is None or name in names:
            plan.add(directory, name, is_dir(os.path.join(directory, name)), size)
    if plan.items and busy_check():
        plan.busy = busy_name
    return plan


def plan_npm(ctx):
    # _cacache 是下载缓存（npm cache clean 删的就是它），_logs 是日志；_npx 里是 npx 装的包，MCP 服务可能正从那里运行，不删
    return plan_home_cache(ctx, os.path.expanduser("~/.npm"), ("_cacache", "_logs"),
                           lambda: running_tool(is_npm, TOOL_BUSY_SECONDS), "npm")


def plan_pip(ctx):
    return plan_home_cache(ctx, os.path.expanduser("~/.cache/pip"), None, lambda: running_tool(is_pip), "pip")


def plan_apt(ctx):
    """apt 下载的软件包和包索引缓存（apt-get clean 删的那些）。要 root 删，由应用以 root 执行 apt-get clean，这里只统计。"""
    plan = Plan(ctx)
    root = "/var/cache/apt"
    for name in children(root):
        path = os.path.join(root, name)
        size, _, _ = plan.count(path)
        if name.endswith(".bin") and safe_child(root, name, False):
            plan.items.append((root, name, False, size))
    archives = os.path.join(root, "archives")
    for name in children(archives):
        if name.endswith(".deb") and safe_child(archives, name, False):
            plan.items.append((archives, name, False, usage(os.path.join(archives, name), set())[0]))
    return plan


def open_paths(root):
    """本用户的进程打开着、当作工作目录、或映射进内存的，在 root 下面的路径。"""
    prefix = os.path.realpath(root).rstrip("/") + "/"
    paths = set()
    for entry in children("/proc"):
        if not entry.isdigit():
            continue
        base = "/proc/" + entry
        links = [base + "/cwd"]
        try:
            links += [base + "/fd/" + fd for fd in os.listdir(base + "/fd")]
        except OSError:
            continue
        for link in links:
            try:
                target = os.readlink(link)
            except OSError:
                continue
            if target.startswith(prefix):
                paths.add(target)
        try:
            with open(base + "/maps", encoding="utf-8", errors="replace") as f:
                for line in f:
                    fields = line.rstrip("\n").split(None, 5)
                    if len(fields) == 6 and fields[5].startswith(prefix):
                        paths.add(fields[5])
        except OSError:
            pass
    return paths


# /tmp 里常驻服务的目录（tmux、ssh-agent 的套接字，运行时目录，Java 进程的性能数据），即使一天没动也不删
TMP_SERVICE_PREFIXES = (".", "tmux-", "ssh-", "runtime-", "systemd-", "hsperfdata_")


def plan_tmp(ctx):
    """Debian 的 /tmp：本用户的、一天没动过的、没有进程在用（打开、当工作目录、映射）的、里面没有套接字和管道的。
    Claude Code 自己的 claude-<uid>/<工作目录>/<会话 id> 按会话看：会话在进行就不删。"""
    plan = Plan(ctx)
    root = storage_tmp()
    real_root = os.path.realpath(root)
    in_use = open_paths(root)
    uid = os.getuid()

    def removable(parent, name, part):
        path = os.path.join(parent, name)
        try:
            info = os.lstat(path)
        except OSError:
            return
        size, newest, special = plan.count(path)
        if info.st_uid != uid or special or ctx.now - newest < TMP_IDLE_SECONDS:
            return
        real = os.path.join(os.path.realpath(parent), name)
        if any(p == real or p.startswith(real + "/") for p in in_use):
            return
        plan.add(parent, name, stat.S_ISDIR(info.st_mode), size, part)

    mine = "claude-%d" % uid
    for name in children(root):
        claude = os.path.join(root, name)
        if name == mine and is_dir(claude):
            for project in children(claude):
                parent = os.path.join(claude, project)
                if not is_dir(parent):
                    plan.count(parent)
                    continue
                entries = children(parent)
                before = len(plan.items)
                for entry in entries:
                    if entry in ctx.keep:
                        plan.count(os.path.join(parent, entry))
                    else:
                        removable(parent, entry, "claude")
                # 里面的都要删、自己也一天没变过（目录的修改时刻随增删项变）的工作目录，删完把空目录也去掉
                try:
                    info = os.lstat(parent)
                except OSError:
                    continue
                if info.st_uid == uid and ctx.now - info.st_mtime >= TMP_IDLE_SECONDS and len(plan.items) - before == len(entries):
                    plan.prune.append((claude, project))
            continue
        if name.startswith(TMP_SERVICE_PREFIXES):
            plan.count(claude)
            continue
        removable(root, name, "other")
    if os.path.islink(root) or real_root == "/":
        plan.items, plan.prune = [], []
    return plan


PLANS = {
    "zeta-files": plan_zeta_files,
    "zeta-hosts": plan_zeta_hosts,
    "claude-residue": plan_residue,
    "claude-debug": lambda ctx: plan_named(ctx, "debug", re.compile(r"(%s)\.txt" % UUID_TEXT), False),
    "claude-snapshots": plan_snapshots,
    "claude-paste": lambda ctx: plan_named(ctx, "paste-cache", re.compile(r"[0-9a-f]{16}\.txt"), False, live_by_time=True),
    "claude-image-cache": lambda ctx: plan_named(ctx, "image-cache", re.compile("(%s)" % UUID_TEXT), True),
    "claude-telemetry": lambda ctx: plan_named(ctx, "telemetry", re.compile(r"1p_failed_events\.(%s)\.[0-9a-f-]+\.json" % UUID_TEXT), False),
    "apt": plan_apt,
    "npm": plan_npm,
    "pip": plan_pip,
    "tmp": plan_tmp,
}
CLEANABLE = set(PLANS) - {"apt"}


def cleanup_period():
    """~/.claude/settings.json 里的 cleanupPeriodDays（只取这一项）；没设或读不了是 None。"""
    try:
        with open(os.path.join(CLAUDE_HOME, "settings.json"), encoding="utf-8") as f:
            value = json.load(f).get("cleanupPeriodDays")
    except (OSError, ValueError, AttributeError):
        return None
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def project_cwd(directory, sessions):
    """项目对应的工作目录：最新那个会话记录开头的 cwd 字段。"""
    for name in sorted(sessions, key=lambda n: os.path.getmtime(os.path.join(directory, n)), reverse=True)[:3]:
        try:
            with open(os.path.join(directory, name), "rb") as f:
                m = re.search(rb'"cwd":("(?:[^"\\]|\\.)*")', f.read(65536))
            if m:
                return json.loads(m.group(1).decode("utf-8", "replace"))
        except (OSError, ValueError):
            continue
    return ""


def cmd_storage(keep):
    ctx = StorageContext(keep)
    for key, plan_of in PLANS.items():
        try:
            out(plan_of(ctx).report(key))
        except Exception:  # 一类没统计成不影响别的
            out({"type": "zeta_storage", "key": key, "error": True})
        sys.stdout.flush()
    for project in children(ROOT):
        path = os.path.join(ROOT, project)
        if not is_dir(path):
            continue
        size, files, _, _ = usage(path, ctx.seen)
        sessions = [n for n in children(path) if n.endswith(".jsonl")]
        out({"type": "zeta_storage_project", "dir": project, "bytes": size, "files": files, "sessions": len(sessions),
             "cwd": project_cwd(path, sessions)})
    out({"type": "zeta_storage_settings", "cleanupPeriodDays": cleanup_period()})
    out({"type": "zeta_storage_done"})


def cmd_storage_sessions(project, keep):
    """一个项目里每个会话占多少：记录文件加上同名目录。别的（自动记忆等）合成一行 rest。"""
    directory = safe_child(ROOT, project, True)
    if directory is None:
        sys.exit("no such project")
    ctx = StorageContext(keep)
    rest = 0
    names = children(directory)
    sessions = {n[:-6] for n in names if n.endswith(".jsonl") and UUID_NAME.fullmatch(n[:-6])}
    for name in names:
        path = os.path.join(directory, name)
        if name[:-6] in sessions and name.endswith(".jsonl"):
            session = name[:-6]
            size = usage(path, ctx.seen)[0]
            if session_dir(os.path.join(ROOT, project, name)):
                size += usage(os.path.join(directory, session), ctx.seen)[0]
            out({"type": "zeta_storage_session", "id": session, "file": os.path.join(ROOT, project, name), "bytes": size,
                 "updated": int(os.path.getmtime(path) * 1000), "live": session in ctx.keep})
        elif name not in sessions:
            rest += usage(path, ctx.seen)[0]
    out({"type": "zeta_storage_rest", "bytes": rest})


def cmd_clean(key, keep):
    plan = PLANS[key](StorageContext(keep))
    freed = removed = failed = 0
    if plan.busy is None:
        for root, name, directory, size in plan.items:
            done = remove_child(root, name, directory, size)
            if done is None:
                failed += 1
            else:
                freed += done
                removed += 1
        for root, name in plan.prune:
            if safe_child(root, name, True) and not children(os.path.join(root, name)):
                try:
                    os.rmdir(os.path.join(root, name))
                except OSError:
                    pass
    out({"type": "zeta_cleaned", "key": key, "freed": freed, "removed": removed, "failed": failed, "busy": plan.busy})


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
    elif args[:1] == ["storage"] and all(SESSION_ID.fullmatch(a) for a in args[1:]):
        cmd_storage(args[1:])
    elif args[:1] == ["storage-sessions"] and len(args) >= 2 and all(SESSION_ID.fullmatch(a) for a in args[2:]):
        cmd_storage_sessions(args[1], args[2:])
    elif args[:1] == ["clean"] and len(args) >= 2 and args[1] in CLEANABLE and all(SESSION_ID.fullmatch(a) for a in args[2:]):
        cmd_clean(args[1], args[2:])
    elif args[:1] == ["subagent"] and len(args) >= 6 and args[1].isdigit() and args[2].isdigit() \
            and all(SUBAGENT_ARG.fullmatch(a) for a in args[3:5]) and all(SESSION_ID.fullmatch(a) for a in args[5:]):
        cmd_subagent(int(args[1]), int(args[2]), args[3], args[4], args[5:])
    else:
        sys.exit("usage: list | history <file> | delete <file>... | rename <file> <title> | archive <id>... <0|1> | hosts"
                 " | image <file> <ref> | file-image <path> | search <query> | lookup <id>... | export <file>..."
                 " | ls <dir> | read <file> <bytes> | mounts <name…> | storage [<id>…]"
                 " | storage-sessions <project> [<id>…] | clean <kind> [<id>…]"
                 " | subagent <offset> <line> <tool_use> <agent|-> <session>…")


main()
