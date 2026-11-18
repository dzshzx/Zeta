import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;

/**
 * 后台虚拟屏（做法照 Eta 的 VirtualDisplayCommandMain）：以 root 启动，降到 system 身份后建一块受信任、
 * 自带焦点、不抢主屏焦点的虚拟屏，画面进本进程的 ImageReader。
 *
 * 由 xtctool webui-vd 构建、推送与启动，不单独使用。
 * 用法：app_process / VdHold 宽 高 密度 [最长存活秒数，默认 1800] [附加的虚拟屏标志位，默认 0]
 * 标准输出：VD_READY:<displayId>；标准输入逐行命令：
 *   shot <png 路径>   把最近一帧存成 PNG，回 VD_SHOT:<路径> 或 VD_ERR:<原因>
 *   release           释放虚拟屏并退出（标准输入关闭也一样）
 * 到了最长存活时间自动释放：驱动脚本中途死掉也不会把虚拟屏永远留在系统里。
 * 全部用反射，编译不需要 android.jar。
 */
public class VdHold {
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*：与 Eta 的 TRUSTED_INTERACTIVE_FLAGS 相同，另加 DESTROY_CONTENT_ON_REMOVAL（1 << 8）：
    // 不带它，屏移除时系统会把屏上的任务挪到主屏最上面，测完一停屏就打扰用户（Zeta 的 VdHelper 同样带着它）。
    // 附加标志位由 webui-vd 传入（默认带 SHOULD_SHOW_SYSTEM_DECORATIONS，让系统画状态栏）：这一项 Eta 的做法里没有
    static final int FLAGS = (1 << 0) | (1 << 3) | (1 << 6) | (1 << 8) | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 13) | (1 << 14) | (1 << 16);
    static Object reader, vd, latest, handler;
    static int width, height, extraFlags;

    static Class<?> cls(String n) throws Exception { return Class.forName(n); }

    static Method find(Class<?> c, String name, int argc) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == argc) { m.setAccessible(true); return m; }
        throw new IllegalStateException("no method " + c.getName() + "." + name + "/" + argc);
    }

    static Object call(Object o, String name, Object... args) throws Exception {
        return find(o.getClass(), name, args.length).invoke(o, args);
    }

    static Object callStatic(Class<?> c, String name, Object... args) throws Exception {
        return find(c, name, args.length).invoke(null, args);
    }

    public static void main(String[] a) throws Exception {
        width = Integer.parseInt(a[0]);
        height = Integer.parseInt(a[1]);
        int dpi = Integer.parseInt(a[2]);
        extraFlags = a.length > 4 ? Integer.decode(a[4]) : 0;
        try {
            int id = create(dpi);
            System.out.println("VD_READY:" + id);
            System.out.flush();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("VD_ERR:" + c);
            System.out.flush();
            System.exit(1);
        }
        Thread th = new Thread(VdHold::stdinLoop, "vd-stdin");
        th.setDaemon(true);
        th.start();
        final long maxMs = (a.length > 3 ? Long.parseLong(a[3]) : 1800L) * 1000L;
        Thread dog = new Thread(() -> {
            try {
                Thread.sleep(maxMs);
            } catch (InterruptedException ignored) {
                return;
            }
            System.out.println("VD_TIMEOUT");
            release();
        }, "vd-watchdog");
        dog.setDaemon(true);
        dog.start();
        callStatic(cls("android.os.Looper"), "loop");
    }

    static int create(int dpi) throws Exception {
        Class<?> looper = cls("android.os.Looper");
        if (callStatic(looper, "getMainLooper") == null) callStatic(looper, "prepareMainLooper");
        // system 身份才过得了 ADD_TRUSTED_DISPLAY 的校验，且包名 android 与调用方 uid 对得上
        cls("android.system.Os").getMethod("setuid", int.class).invoke(null, 1000);
        Class<?> at = cls("android.app.ActivityThread");
        Object thread = callStatic(at, "systemMain");
        Object ctx = call(thread, "getSystemContext");
        handler = cls("android.os.Handler").getConstructor(looper).newInstance(callStatic(looper, "getMainLooper"));

        Class<?> ir = cls("android.media.ImageReader");
        reader = ir.getMethod("newInstance", int.class, int.class, int.class, int.class).invoke(null, width, height, 1 /* RGBA_8888 */, 3);
        Class<?> lis = cls("android.media.ImageReader$OnImageAvailableListener");
        InvocationHandler onFrame = (proxy, m, args) -> {
            if (m.getName().equals("onImageAvailable")) frame();
            return null;
        };
        ir.getMethod("setOnImageAvailableListener", lis, cls("android.os.Handler"))
            .invoke(reader, Proxy.newProxyInstance(VdHold.class.getClassLoader(), new Class<?>[] {lis}, onFrame), handler);
        Object surface = call(reader, "getSurface");

        Class<?> cb = cls("android.hardware.display.VirtualDisplayConfig$Builder");
        Object b = cb.getConstructor(String.class, int.class, int.class, int.class).newInstance("zeta-vd", width, height, dpi);
        cb.getMethod("setSurface", cls("android.view.Surface")).invoke(b, surface);
        cb.getMethod("setFlags", int.class).invoke(b, FLAGS | extraFlags);
        Object config = call(b, "build");

        Class<?> dmgC = cls("android.hardware.display.DisplayManagerGlobal");
        Object dmg = callStatic(dmgC, "getInstance");
        Class<?> cfgC = cls("android.hardware.display.VirtualDisplayConfig");
        Method create = null;
        for (Method m : dmgC.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("createVirtualDisplay") && p.length >= 3 && p[0] == cls("android.content.Context") && p[2] == cfgC) create = m;
        }
        if (create == null) throw new IllegalStateException("no DisplayManagerGlobal.createVirtualDisplay(Context, MediaProjection, VirtualDisplayConfig, ...)");
        Object[] args = new Object[create.getParameterCount()];
        args[0] = ctx;
        args[2] = config;
        vd = create.invoke(dmg, args);
        if (vd == null) throw new IllegalStateException("createVirtualDisplay returned null");
        Object display = call(vd, "getDisplay");
        int id = (Integer) call(display, "getDisplayId");
        int dflags = (Integer) call(display, "getFlags");
        // Display.FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_STEAL_TOP_FOCUS_DISABLED：少一个就不能保证不打扰主屏
        int need = (1 << 7) | (1 << 11) | (1 << 12);
        if (id <= 0 || (dflags & need) != need) {
            call(vd, "release");
            throw new IllegalStateException("display id=" + id + " flags=0x" + Integer.toHexString(dflags) + " lacks trusted/own-focus/no-steal");
        }
        // 输入法不在这块屏上弹，也不退回主屏弹
        Object binder = cls("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        Object wms = cls("android.view.IWindowManager$Stub").getMethod("asInterface", cls("android.os.IBinder")).invoke(null, binder);
        find(wms.getClass(), "setDisplayImePolicy", 2).invoke(wms, id, 2 /* DISPLAY_IME_POLICY_HIDE */);
        int policy = (Integer) find(wms.getClass(), "getDisplayImePolicy", 1).invoke(wms, id);
        if (policy != 2) {
            call(vd, "release");
            throw new IllegalStateException("ime policy=" + policy);
        }
        return id;
    }

    // 只留最近一帧：不取走的话缓冲占满，合成会停
    static synchronized void frame() {
        try {
            Object img = call(reader, "acquireLatestImage");
            if (img == null) return;
            if (latest != null) call(latest, "close");
            latest = img;
        } catch (Throwable t) {
            System.out.println("VD_WARN:frame " + t);
        }
    }

    static synchronized String shot(String path) {
        try {
            if (latest == null) return "VD_ERR:no frame yet";
            Object plane = ((Object[]) call(latest, "getPlanes"))[0];
            ByteBuffer buf = (ByteBuffer) call(plane, "getBuffer");
            int rowStride = (Integer) call(plane, "getRowStride"), pixelStride = (Integer) call(plane, "getPixelStride");
            Class<?> bmpC = cls("android.graphics.Bitmap");
            Class<?> cfgC = cls("android.graphics.Bitmap$Config");
            Object argb = cfgC.getField("ARGB_8888").get(null);
            Object full = bmpC.getMethod("createBitmap", int.class, int.class, cfgC).invoke(null, rowStride / pixelStride, height, argb);
            buf.rewind();
            bmpC.getMethod("copyPixelsFromBuffer", java.nio.Buffer.class).invoke(full, buf);
            Object bmp = bmpC.getMethod("createBitmap", bmpC, int.class, int.class, int.class, int.class).invoke(null, full, 0, 0, width, height);
            Class<?> fmtC = cls("android.graphics.Bitmap$CompressFormat");
            try (FileOutputStream out = new FileOutputStream(path)) {
                bmpC.getMethod("compress", fmtC, int.class, java.io.OutputStream.class).invoke(bmp, fmtC.getField("PNG").get(null), 100, out);
            }
            return "VD_SHOT:" + path;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "VD_ERR:" + c;
        }
    }

    static void stdinLoop() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.equals("release")) break;
                if (line.startsWith("shot ")) {
                    System.out.println(shot(line.substring(5).trim()));
                    System.out.flush();
                }
            }
        } catch (Throwable ignored) {
            // 管道断开按释放处理
        }
        release();
    }

    static synchronized void release() {
        try {
            if (vd != null) call(vd, "release");
        } catch (Throwable ignored) {
            // 进程退出时系统也会回收
        }
        System.out.println("VD_RELEASED");
        System.out.flush();
        System.exit(0);
    }
}
