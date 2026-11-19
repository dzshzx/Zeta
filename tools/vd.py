#!/usr/bin/env python3
"""后台虚拟屏：在不占用主屏的情况下运行、截图、点按 Zeta。做法取自 Eta 的 VirtualDisplayCommandMain。

在手机 Debian 里直接进 Android 一侧；在工作站上经 ssh 连手机（主机名取 ZETA_PHONE_SSH，默认 xiaomi-17-debian）。

用法：vd.py [--dev] <命令>
  --dev 操作测试版（com.dzshzx.zeta.dev），每条命令都要带（build 除外）。测试版和正式版各有自己的虚拟屏。
  真机测试一般写成脚本交给 tools/phone-run，脚本里的 vd 就是 vd.py --dev。
  不带 --dev 是正式版（com.dzshzx.zeta）和它自己的虚拟屏，只由主会话碰：ZETA_PHONE_OWNER 不设或为 main 时才肯做。
  vd.py build                 编译 VdHold.java 为 dex
  vd.py start [宽 高 dpi]      建虚拟屏并在上面启动 Zeta（默认取主屏尺寸）
  vd.py shot 文件.png          截图
  vd.py tap X Y | swipe X1 Y1 X2 Y2 [毫秒] | text 文字 | key 键码
  vd.py launch                在虚拟屏上重新启动 Zeta（装了新包之后）
  vd.py prefs 键名…            读 Zeta 设置（shared_prefs/zeta.xml）里这几项，只按键名取，不整份输出
  vd.py --dev prefs-set 键[:类型]=值…
                              改测试版的设置（类型 string（默认）、int、long、boolean、float）：force-stop 后原地改写；
                              每个键第一次改时记下原值，stop 时改回去（phoneDisplay 一律删掉）
  vd.py sh '命令'              在 Android 一侧以 root 跑一条 shell 命令，原样输出、带回退出码
  vd.py --dev fake 剧本.jsonl  测试版之后起的对话换用按剧本回话的假 Claude（tools/fake-claude）：把它和剧本放到 Debian 的
                              ~/.config/zeta-dev/fake-claude/，进度从第一轮算起；fake --off 或 stop 删掉，回到真的 claude
  vd.py status | stop         stop 关掉虚拟屏（屏上的应用随屏销毁）；测试版另外 force-stop，撤销它的通知监听、改回 prefs-set 改过的设置，
                              并把它留在用户「最近任务」里的记录全部清掉，假 Claude 的剧本也删掉。
                              force-stop 会让系统把那个包的无障碍服务从启用列表里拿掉，所以不对正式版做
"""
import io, json, os, re, shlex, subprocess, sys, tarfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "vdisplay", "VdHold.java")
OUT = os.path.join(HERE, "vdisplay", "build")
DEX = os.path.join(OUT, "classes.dex")
D8 = os.path.expanduser("~/Android/Sdk/build-tools/37.0.0/lib/d8.jar")
RELEASE = "com.dzshzx.zeta"
# 下面三个由 use() 按测试版还是正式版定：包名、虚拟屏名字（VdHold 的第 6 个参数）、手机上的工作目录
PACKAGE = RELEASE
NAME = "zeta-screen-main"
WORK = "/data/local/tmp/zeta-screen-main"
ACTIVITY = "com.dzshzx.zeta.ui.MainActivity"  # 类名不随包名变：测试版只加了 applicationIdSuffix
PHONE_SSH = os.environ.get("ZETA_PHONE_SSH", "xiaomi-17-debian")
# 假 Claude：测试版的 zeta_host.sh 看到这里有 scenario.jsonl 就起它（Debian 里 Linux 用户的家目录下）
FAKE_DIR = "~/.config/zeta-dev/fake-claude"
FAKE_SCRIPT = os.path.join(HERE, "fake-claude", "fake-claude.sh")
ON_PHONE = "android" in os.uname().release  # 手机 Debian 是 chroot，和 Android 共用内核


# 测试版停屏时的收尾。force-stop 会让系统把它的无障碍服务从启用列表里拿掉（AOSP onPackagesForceStoppedLocked），
# 通知监听不会，要自己撤。prefs-set 改过的设置另由 prefs_restore 改回去
DEV_CLEANUP = r"""
for c in $(settings get secure enabled_notification_listeners | tr ':' ' '); do
  case $c in @PKG@/*) cmd notification disallow_listener $c && echo "撤销了通知监听 $c" ;; esac
done
# 屏随着关掉、包也 force-stop 了，任务记录却还留在用户的「最近任务」里（HyperOS 实测），一条条移除
n=0
for id in $(dumpsys activity recents | grep -oE 'Task\{[0-9a-f]+ #[0-9]+ [^}]* A=[0-9]+:@PKG@\}' | grep -oE ' #[0-9]+ ' | tr -d ' #'); do
  am stack remove $id && n=$((n+1))
done
[ $n -gt 0 ] && echo "从最近任务里清掉了 $n 条记录"
true"""

def run(cmd, data=None):
    """在 Android 一侧以 root 运行 cmd，data 经标准输入传过去。远端加 timeout：ssh 断开后远端进程不会自己停。"""
    argv = ["sudo", "-n", "timeout", "120", "nsenter", "-t", "1", "-m", "--", "/system/bin/sh", "-c",
            "export PATH=/system/bin:$PATH; " + cmd]
    if not ON_PHONE:
        argv = ["ssh", PHONE_SSH, shlex.join(argv)]
    io = {"input": data} if data is not None else {"stdin": subprocess.DEVNULL}
    return subprocess.run(argv, capture_output=True, **io)


def debian(cmd, data=None):
    """在手机 Debian 里以 Linux 用户（Zeta 起 Claude Code 用的那个）跑 cmd；工作站上经 ssh。远端加 timeout。"""
    argv = ["timeout", "60", "sh", "-c", cmd]
    if not ON_PHONE:
        argv = ["ssh", PHONE_SSH, shlex.join(argv)]
    io_ = {"input": data} if data is not None else {"stdin": subprocess.DEVNULL}
    r = subprocess.run(argv, capture_output=True, **io_)
    if r.returncode != 0:
        sys.exit(f"Debian 里的命令失败（{r.returncode}）：{cmd}\n{r.stderr.decode(errors='replace').strip()}")
    return r.stdout.decode(errors="replace")


def fake(path):
    """放上假 Claude 和剧本（先在本地核一遍：每行一个带 steps 数组的 JSON）；--off 删掉。"""
    if path == "--off":
        print(debian(f"rm -rf {FAKE_DIR} && echo 假 Claude 已撤下"), end="")
        return
    with open(path, encoding="utf-8") as f:
        lines = [line for line in f.read().splitlines() if line.strip()]
    for i, line in enumerate(lines, 1):
        try:
            turn = json.loads(line)
        except ValueError as e:
            sys.exit(f"{path} 第 {i} 行不是 JSON：{e}")
        if not isinstance(turn, dict) or not isinstance(turn.get("steps"), list):
            sys.exit(f"{path} 第 {i} 行没有 steps 数组")
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w") as tar:
        # 脚本先于剧本落地：zeta_host.sh 看到剧本时脚本已经在了
        for name, data, mode in (("fake-claude.sh", open(FAKE_SCRIPT, "rb").read(), 0o755),
                                 ("scenario.jsonl", ("\n".join(lines) + "\n").encode(), 0o644)):
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), mode, int(time.time())
            tar.addfile(info, io.BytesIO(data))
    debian(f"mkdir -p {FAKE_DIR} && rm -f {FAKE_DIR}/scenario.jsonl.turn && tar -xf - -C {FAKE_DIR} --no-same-owner",
           data=archive.getvalue())
    print(f"假 Claude 已就位：剧本 {os.path.basename(path)}，{len(lines)} 轮；之后测试版起的对话都用它")


def android(cmd, check=True, data=None, binary=False):
    r = run(cmd, data)
    if check and r.returncode != 0:
        sys.exit(f"android 命令失败（{r.returncode}）：{cmd}\n{r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def use(slot):
    """切到测试版（dev）或正式版（main）。"""
    global PACKAGE, NAME, WORK
    if slot == "main":
        PACKAGE, NAME = RELEASE, "zeta-screen-main"
    else:
        PACKAGE, NAME = RELEASE + ".dev", "zeta-vd"
    WORK = "/data/local/tmp/" + NAME


def display_id():
    out = android("cmd display get-displays 2>/dev/null", check=False)
    for line in out.splitlines():
        if '{"' + NAME + '"' in line:  # 带引号比整个名字：zeta-vd 不能认成别的屏
            m = re.search(r"Display id (\d+)", line) or re.search(r"displayId[ =](\d+)", line)
            if m:
                return int(m.group(1))
    return None


def need_display():
    did = display_id()
    if did is None:
        sys.exit("虚拟屏不在：先运行 start")
    return did


def build():
    os.makedirs(OUT, exist_ok=True)
    jdk = subprocess.run([os.path.join(HERE, "java-home")], capture_output=True, text=True, check=True).stdout.strip()
    subprocess.run([f"{jdk}/bin/javac", "--release", "11", "-Xlint:-options", "-d", OUT, SRC], check=True)
    classes = [os.path.join(OUT, f) for f in os.listdir(OUT) if f.endswith(".class")]
    subprocess.run([f"{jdk}/bin/java", "-cp", D8, "com.android.tools.r8.D8", "--min-api", "33", "--output", OUT] + classes,
                   check=True)
    print("built", DEX)


def launch(did):
    # NEW_TASK | MULTIPLE_TASK：在虚拟屏上另起一个任务，不把主屏上的那份挪过来；
    # EXCLUDE_FROM_RECENTS：每次冷启动都是新任务，不加的话屏关掉后它们一条条留在用户的「最近任务」里
    android(f"am start --user 0 --display {did} -n '{PACKAGE}/{ACTIVITY}' -f 0x18800000 >/dev/null 2>&1")


def prefs_file():
    return f"/data/user/0/{PACKAGE}/shared_prefs/zeta.xml"


def prefs_orig_file():
    """prefs-set 记下的原值：{键: 原来那一项的 XML，没有这一项时为 null}。"""
    return f"/data/local/tmp/{PACKAGE}-prefs-orig.json"


def pref_pattern(key):
    """设置文件里键 key 那一项（含前面的缩进和后面的换行；值可以跨行）。"""
    k = re.escape(key)
    return re.compile(rf'[ \t]*<(\w+) name="{k}"(?:[^>]*/>|>.*?</\1>)\n?', re.S)


def pref_put(text, key, element):
    """去掉键 key 原来的那一项；element 不为 None 时放到 </map> 前。"""
    text = pref_pattern(key).sub("", text)
    return text if element is None else text.replace("</map>", f"    {element.strip()}\n</map>", 1)


def write_root(path, text):
    """以 root 原地写回（不换文件：属主和 SELinux 标签不变）。"""
    android(f"cat > {path}", data=text.encode())


def write_prefs(text):
    """写回测试版的设置文件。测试版被 force-stop 后会马上被系统拉起来（通知监听重新绑定），拉起来的进程要是先读了旧设置，
    之后自己保存时会把这次写的冲掉；所以停、写、再停放在同一条命令里，下次启动读到的一定是新文件。"""
    android(f"am force-stop {PACKAGE}; cat > {prefs_file()}; am force-stop {PACKAGE}", data=text.encode())


def prefs_set(assignments):
    if PACKAGE == RELEASE:
        sys.exit("prefs-set 只改测试版：vd.py --dev prefs-set …")
    usage = "用法：vd.py --dev prefs-set 键[:string|int|long|boolean|float]=值…"
    parsed = [re.fullmatch(r"([A-Za-z0-9_]+)(?::(string|int|long|boolean|float))?=(.*)", a, re.S) for a in assignments]
    if not assignments or not all(parsed):
        sys.exit(usage)
    path = prefs_file()
    text = android(f"cat {path}")
    if "</map>" not in text:
        sys.exit(f"设置文件不完整：{path}")
    orig = json.loads(android(f"cat {prefs_orig_file()} 2>/dev/null", check=False) or "{}")
    for key, kind, value in (m.groups() for m in parsed):
        if key not in orig:
            found = pref_pattern(key).search(text)
            orig[key] = found.group(0) if found else None
        kind = kind or "string"
        if kind == "string":
            element = f'<string name="{key}">' + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</string>"
        else:
            element = f'<{kind} name="{key}" value="{value}" />'
        text = pref_put(text, key, element)
    write_prefs(text)
    write_root(prefs_orig_file(), json.dumps(orig, ensure_ascii=False))
    print("改了 " + "、".join(m.group(1) for m in parsed))


def prefs_restore():
    """改回 prefs-set 改过的键；phoneDisplay 指着刚关掉的屏，一律删掉。在 force-stop 之后调。"""
    path, orig_path = prefs_file(), prefs_orig_file()
    orig = json.loads(android(f"cat {orig_path} 2>/dev/null", check=False) or "{}")
    text = android(f"cat {path} 2>/dev/null", check=False)
    if "</map>" in text:
        changed = text
        for key, element in orig.items():
            changed = pref_put(changed, key, element)
        changed = pref_put(changed, "phoneDisplay", None)
        if changed != text:
            write_prefs(changed)
            print("设置改回测试前：" + "、".join(sorted(set(orig) | {"phoneDisplay"})))
    android(f"rm -f {orig_path}", check=False)


def prefs(keys):
    if not keys or not all(re.fullmatch(r"[A-Za-z0-9_]+", k) for k in keys):
        sys.exit("用法：vd.py [--dev] prefs 键名…（键名只含字母、数字、下划线）")
    pattern = "|".join(keys)
    print(android(f"grep -E 'name=\"({pattern})\"' /data/user/0/{PACKAGE}/shared_prefs/zeta.xml", check=False), end="")


def start(args):
    if display_id() is not None:
        sys.exit("虚拟屏已经在了（stop 关闭）")
    if not os.path.exists(DEX) or os.path.getmtime(DEX) < os.path.getmtime(SRC):
        build()
    if len(args) == 3:
        w, h, dpi = map(int, args)
    else:
        w, h = map(int, re.search(r"(\d+)x(\d+)", android("wm size")).groups())
        dpi = int(re.findall(r"(\d+)", android("wm density"))[-1])
    pid = android("pidof system_server").split()[0]
    env = android(f"cat /proc/{pid}/environ").split("\0")
    keys = {"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_STORAGE",
            "ANDROID_ASSETS", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "STANDALONE_SYSTEMSERVER_JARS"}
    exports = "; ".join(f"export {k}='{v}'" for k, _, v in (e.partition("=") for e in env) if k in keys and "'" not in v)
    if "BOOTCLASSPATH" not in exports:
        sys.exit("读不到 BOOTCLASSPATH")
    with open(DEX, "rb") as f:
        dex = f.read()
    android(f"mkdir -p {WORK}/out && chmod 755 {WORK} && chown 1000:1000 {WORK}/out && chmod 700 {WORK}/out && "
            f"cat > {WORK}/vdhold.dex && chmod 644 {WORK}/vdhold.dex && cd {WORK} && rm -f ctl log out/*.png && "
            f"mkfifo ctl && chmod 600 ctl && : > log", data=dex)
    runner = (f"cd {WORK} && exec 3<>ctl && {exports} && export CLASSPATH={WORK}/vdhold.dex && "
              f"exec app_process / VdHold {w} {h} {dpi} 3600 0 {NAME} <&3 >>log 2>&1")
    android('nohup sh -c "' + runner.replace('"', '\\"') + '" >/dev/null 2>&1 &')
    for _ in range(40):
        m = re.search(r"^VD_READY:(\d+)$", android(f"cat {WORK}/log"), re.M)
        if m:
            did = int(m.group(1))
            launch(did)
            print(f"虚拟屏 {did}（{w}x{h} @{dpi}）")
            return
        time.sleep(0.5)
    sys.exit("虚拟屏没有就绪：\n" + android(f"cat {WORK}/log"))


def shot(path):
    need_display()
    before = len(android(f"cat {WORK}/log"))
    android(f"echo 'shot {WORK}/out/shot.png' > {WORK}/ctl")
    for _ in range(40):
        tail = android(f"cat {WORK}/log")[before:]
        if "VD_SHOT:" in tail:
            data = android(f"cat {WORK}/out/shot.png", binary=True)
            with open(path, "wb") as f:
                f.write(data)
            print(path)
            return
        if "VD_ERR:" in tail:
            sys.exit(tail.strip())
        time.sleep(0.25)
    sys.exit("截图超时")


def lease(kind):
    """按包拿租约（见 tools/phone-lock），拿不到就退出；返回 dev 或 main。在手机 Debian 里直接跑时不协调。"""
    if not ON_PHONE:
        code = subprocess.run([os.path.join(HERE, "phone-lock"), kind]).returncode
        if code:
            sys.exit(code)
    return kind

def main():
    a = sys.argv[1:]
    dev = "--dev" in a
    a = [x for x in a if x != "--dev"]
    if not a:
        sys.exit(__doc__)
    c, rest = a[0], a[1:]
    owner = os.environ.get("ZETA_PHONE_OWNER") or "main"
    if c == "fake" and not dev:
        sys.exit("假 Claude 只给测试版用：vd.py --dev fake <剧本>")
    if not dev and owner != "main" and c != "build":
        sys.exit(f"正式版只由主会话碰（现在 ZETA_PHONE_OWNER={owner}）；开发测试用测试版，每条命令都带 --dev：vd.py --dev {c}")
    if c != "build":
        use(lease("dev" if dev else "main"))
    if c == "build":
        build()
    elif c == "start":
        start(rest)
    elif c == "shot":
        shot(rest[0])
    elif c == "launch":
        launch(need_display())
    elif c == "prefs":
        prefs(rest)
    elif c == "prefs-set":
        prefs_set(rest)
    elif c == "tap":
        android(f"input -d {need_display()} tap {int(rest[0])} {int(rest[1])}")
    elif c == "swipe":
        android(f"input -d {need_display()} swipe " + " ".join(str(int(x)) for x in rest))
    elif c == "text":
        android(f"input -d {need_display()} text '" + rest[0].replace("'", "'\\''") + "'")
    elif c == "key":
        android(f"input -d {need_display()} keyevent {rest[0]}")
    elif c == "fake":
        if len(rest) != 1:
            sys.exit("用法：vd.py --dev fake <剧本.jsonl> | --off")
        fake(rest[0])
    elif c == "status":
        did = display_id()
        print("虚拟屏不在" if did is None else f"虚拟屏 {did}")
    elif c == "sh":
        r = run(rest[0])
        sys.stdout.buffer.write(r.stdout)
        sys.stderr.buffer.write(r.stderr)
        sys.exit(r.returncode)
    elif c == "stop":
        cmds = [f"echo release > {WORK}/ctl"] if display_id() is not None else []
        if PACKAGE != RELEASE:
            # 测试版另外收拾干净；用户的正式版只关屏，不碰（force-stop 会让系统拿掉它的无障碍服务）。
            # force-stop 放在关屏之后：先 force-stop 再关屏，测试版会被重新拉起来
            cmds = cmds + [f"am force-stop {PACKAGE}", DEV_CLEANUP.replace("@PKG@", PACKAGE)]
        if cmds:
            print(android("\n".join(cmds), check=False), end="")
        if PACKAGE != RELEASE:
            prefs_restore()
            # 假 Claude 撤下：之后测试版起的都是真的 claude，闲着的假 Claude 看到剧本没了自己退出
            print(debian(f"if [ -d {FAKE_DIR} ]; then rm -rf {FAKE_DIR} && echo 删掉了假 Claude 的剧本; fi"), end="")
        print("stopped")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
