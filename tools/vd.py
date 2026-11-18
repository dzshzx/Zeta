#!/usr/bin/env python3
"""后台虚拟屏：在不占用主屏的情况下运行、截图、点按 Zeta。做法取自 Eta 的 VirtualDisplayCommandMain。

用法（在 Debian 里，需要 sudo）：
  vd.py build                 编译 VdHold.java 为 dex
  vd.py start [宽 高 dpi]      建虚拟屏并在上面启动 Zeta（默认取主屏尺寸）
  vd.py shot 文件.png          截图
  vd.py tap X Y | swipe X1 Y1 X2 Y2 [毫秒] | text 文字 | key 键码
  vd.py launch                在虚拟屏上重新启动 Zeta（装了新包之后）
  vd.py status | stop
"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "vdisplay", "VdHold.java")
OUT = os.path.join(HERE, "vdisplay", "build")
DEX = os.path.join(OUT, "classes.dex")
D8 = os.path.expanduser("~/Android/Sdk/build-tools/37.0.0/lib/d8.jar")
WORK = "/data/local/tmp/zeta-vd"
NAME = "zeta-vd"  # VdHold 里写死的虚拟屏名字
COMPONENT = os.environ.get("ZETA_COMPONENT", "com.dzshzx.zeta/.ui.MainActivity")


def android(cmd, check=True):
    r = subprocess.run(["sudo", "nsenter", "-t", "1", "-m", "--", "/system/bin/sh", "-c",
                        "export PATH=/system/bin:$PATH; " + cmd], capture_output=True, text=True, errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"android 命令失败（{r.returncode}）：{cmd}\n{r.stderr.strip()}")
    return r.stdout


def display_id():
    out = android("cmd display get-displays 2>/dev/null", check=False)
    for line in out.splitlines():
        if NAME in line:
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
    subprocess.run(["javac", "--release", "11", "-Xlint:-options", "-d", OUT, SRC], check=True)
    classes = [os.path.join(OUT, f) for f in os.listdir(OUT) if f.endswith(".class")]
    subprocess.run(["java", "-cp", D8, "com.android.tools.r8.D8", "--min-api", "33", "--output", OUT] + classes, check=True)
    print("built", DEX)


def launch(did):
    android(f"am start --user 0 --display {did} -n '{COMPONENT}' -f 0x18000000 >/dev/null 2>&1")


def start(args):
    if display_id() is not None:
        sys.exit("虚拟屏已经在了（stop 关闭）")
    if not os.path.exists(DEX):
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
    dex = f"/proc/{os.getpid()}/root{DEX}"
    android(f"mkdir -p {WORK}/out && chmod 755 {WORK} && chown 1000:1000 {WORK}/out && chmod 700 {WORK}/out && "
            f"cp {dex} {WORK}/vdhold.dex && chmod 644 {WORK}/vdhold.dex && cd {WORK} && rm -f ctl log out/*.png && "
            f"mkfifo ctl && chmod 600 ctl && : > log")
    runner = (f"cd {WORK} && exec 3<>ctl && {exports} && export CLASSPATH={WORK}/vdhold.dex && "
              f"exec app_process / VdHold {w} {h} {dpi} 3600 0 <&3 >>log 2>&1")
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
            data = subprocess.run(["sudo", "nsenter", "-t", "1", "-m", "--", "/system/bin/sh", "-c",
                                   f"cat {WORK}/out/shot.png"], capture_output=True).stdout
            with open(path, "wb") as f:
                f.write(data)
            print(path)
            return
        if "VD_ERR:" in tail:
            sys.exit(tail.strip())
        time.sleep(0.25)
    sys.exit("截图超时")


def main():
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    c, rest = a[0], a[1:]
    if c == "build":
        build()
    elif c == "start":
        start(rest)
    elif c == "shot":
        shot(rest[0])
    elif c == "launch":
        launch(need_display())
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
            android(f"am force-stop com.dzshzx.zeta; echo release > {WORK}/ctl", check=False)
        print("stopped")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
