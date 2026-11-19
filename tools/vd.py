#!/usr/bin/env python3
"""后台虚拟屏：在不占用主屏的情况下运行、截图、点按 Zeta。做法取自 Eta 的 VirtualDisplayCommandMain。

在手机 Debian 里直接进 Android 一侧；在工作站上经 ssh 连手机（主机名取 ZETA_PHONE_SSH，默认 xiaomi-17-debian）。

用法：vd.py [--dev] <命令>
  --dev 操作测试版（com.dzshzx.zeta.dev），每条命令都要带（build 除外）。测试版和正式版各有自己的虚拟屏。
  真机测试一般写成脚本交给 tools/phone-run，脚本里的 vd 就是 vd.py --dev。
  不带 --dev 是正式版（com.dzshzx.zeta）和它自己的虚拟屏，只由负责合入的会话碰：ZETA_PHONE_OWNER 不设或为 main 时才肯做。
  vd.py build                 编译 VdHold.java 为 dex
  vd.py start [宽 高 dpi]      建虚拟屏并在上面启动 Zeta（默认取主屏尺寸）
  vd.py shot 文件.png          截图
  vd.py tap X Y | swipe X1 Y1 X2 Y2 [毫秒] | text 文字 | key 键码
  vd.py launch                在虚拟屏上重新启动 Zeta（装了新包之后）
  vd.py prefs 键名…            读 Zeta 设置（shared_prefs/zeta.xml）里这几项，只按键名取，不整份输出
  vd.py status | stop         stop 关掉虚拟屏（屏上的应用随屏销毁）；测试版另外 force-stop，不在后台常驻。
                              force-stop 会让系统把那个包的无障碍服务从启用列表里拿掉，所以不对正式版做
"""
import os, re, shlex, subprocess, sys, time

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
ON_PHONE = "android" in os.uname().release  # 手机 Debian 是 chroot，和 Android 共用内核


def android(cmd, check=True, data=None, binary=False):
    """在 Android 一侧以 root 运行 cmd，data 经标准输入传过去。远端加 timeout：ssh 断开后远端进程不会自己停。"""
    argv = ["sudo", "-n", "timeout", "120", "nsenter", "-t", "1", "-m", "--", "/system/bin/sh", "-c",
            "export PATH=/system/bin:$PATH; " + cmd]
    if not ON_PHONE:
        argv = ["ssh", PHONE_SSH, shlex.join(argv)]
    io = {"input": data} if data is not None else {"stdin": subprocess.DEVNULL}
    r = subprocess.run(argv, capture_output=True, **io)
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
    android(f"am start --user 0 --display {did} -n '{PACKAGE}/{ACTIVITY}' -f 0x18000000 >/dev/null 2>&1")


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
    if not dev and owner != "main" and c != "build":
        sys.exit(f"正式版只由负责合入的会话碰（现在 ZETA_PHONE_OWNER={owner}）；开发测试用测试版，每条命令都带 --dev：vd.py --dev {c}")
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
    elif c == "tap":
        android(f"input -d {need_display()} tap {int(rest[0])} {int(rest[1])}")
    elif c == "swipe":
        android(f"input -d {need_display()} swipe " + " ".join(str(int(x)) for x in rest))
    elif c == "text":
        android(f"input -d {need_display()} text '" + rest[0].replace("'", "'\\''") + "'")
    elif c == "key":
        android(f"input -d {need_display()} keyevent {rest[0]}")
    elif c == "status":
        did = display_id()
        print("虚拟屏不在" if did is None else f"虚拟屏 {did}")
    elif c == "stop":
        did = display_id()
        if did is not None:
            # force-stop 会让系统把这个包的无障碍服务从启用列表里拿掉（AOSP onPackagesForceStoppedLocked）：
            # 只对测试版做，顺带让它不在后台常驻；用户的正式版不碰
            kill = f"am force-stop {PACKAGE}; " if PACKAGE != RELEASE else ""
            android(f"{kill}echo release > {WORK}/ctl", check=False)
        print("stopped")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
