package com.deepseekharness.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心控制器：分步安装（rootfs / 基础工具 / Node / deepseek-harness，每步可单独
 * 重装或更新，多镜像自动测速选最快源）、一键补装、Web UI 启停。
 *
 * 进度/错误状态保存在单例中，通过 {@link StateListener} 通知 UI；
 * Fragment 切换时状态不丢失，避免异步回调打到已销毁视图上。
 */
public class HarnessController {

    // ================= 分步安装步骤 =================
    public static final int STEP_ROOTFS = 1;
    public static final int STEP_TOOLS = 2;
    public static final int STEP_NODE = 3;
    public static final int STEP_PNPM = 4;
    public static final int STEP_HARNESS = 5;
    public static final int STEP_GUARD = 6;

    // ================= 下载任务（用于源选择记忆） =================
    public static final int TASK_ROOTFS = 1;
    public static final int TASK_NODE = 2;
    public static final int TASK_HARNESS = 3;

    private static final String PREFS = "deepseekharness";

    // ================= 统一 GitHub 加速代理 =================
    /** 用户指定的 GitHub 反向代理前缀（前缀式拼接，如: <代理>/https://github.com/...）。 */
    public static final String GH_PROXY = "https://gh.fplj123580.qzz.io/";
    private static final String[] GH_PROXY_HOSTS = {
            "https://github.com/",
            "https://raw.githubusercontent.com/",
            "https://api.github.com/",
            "https://codeload.github.com/",
    };

    /** 给 github 系链接加统一代理前缀；非 github / 已带代理前缀的保持原样。 */
    public static String gitHubProxy(String u) {
        if (u == null) return u;
        if (u.startsWith(GH_PROXY)) return u;
        for (String host : GH_PROXY_HOSTS) {
            if (u.startsWith(host)) return GH_PROXY + u;
        }
        return u;
    }

    /** 市场索引本地缓存新鲜期（命中直接秒开，不请求网络）。 */
    private static final long MARKET_CACHE_TTL_MS = 6L * 3600 * 1000;

    private static HarnessController instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ProotBootstrap proot;

    // ================= 进度/错误状态（跨 Fragment 保持） =================
    public interface StateListener {
        void onStateChanged();
    }

    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String stage = "";
    private volatile int percent = 0;
    private volatile String message = "";
    private volatile String error = "";
    private volatile boolean busy = false;
    /** busy 置位时间戳：超时自愈用（任务卡死 >10 分钟强制释放，防 App 假死） */
    private volatile long busySince = 0;
    /** busy 超时阈值：安装/启动单步不应超过 10 分钟 */
    private static final long BUSY_STALE_MS = 10 * 60 * 1000L;
    private volatile int currentStep = 0;
    private volatile Process webProcess;
    /** Web 进程“代际”/硬重启计数：让启动页感知重启并刷新预览（拿到最新 manifest/插件） */
    private volatile long webEpoch = System.currentTimeMillis();
    private volatile long hardRestartEpoch = 0;
    /** 强重启/深停机配套 */
    private final java.util.concurrent.atomic.AtomicBoolean webRestartLock = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object webStartLock = new Object();
    private boolean webStarting = false;
    private final java.util.Set<Process> webProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 会话自愈进行中：期间禁止 prewarm/keepAlive 拉起 web，防边修边写。 */
    private static volatile boolean healingSession = false;
    public static boolean isHealingSession() { return healingSession; }

    public long getWebEpoch() { return webEpoch; }
    public void bumpWebEpoch() { webEpoch = System.currentTimeMillis(); }
    public long getHardRestartEpoch() { return hardRestartEpoch; }
    public void bumpHardRestart() { hardRestartEpoch = System.currentTimeMillis(); }
    public boolean isWebRestartLocked() { return webRestartLock.get(); }
    public boolean tryAcquireWebRestartLock() { return webRestartLock.compareAndSet(false, true); }
    public void releaseWebRestartLock() { webRestartLock.set(false); }

    /** 端口探测：ms 超时内是否可连接 Web 端口 */
    /** 端口探测的三态结果：UP / DOWN / UNKNOWN。
     *
     *  以前一律 catch 成 false，把「不知道」当成了「没起来」：连接被拒确实说明
     *  端口上没人听，但超时可能只是启动慢或系统正忙 —— 两者混在一起，UI 就会
     *  在服务其实正在起的时候报「启动失败」，用户白白重试。 */
    private String probeWebPort(int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", parsePort()), timeoutMs);
            return "UP";
        } catch (java.net.ConnectException e) {
            return "DOWN";     // 明确被拒 = 这个端口上确实没有人在监听
        } catch (java.net.SocketTimeoutException e) {
            return "UNKNOWN";  // 超时：可能还在启动，也可能系统正忙
        } catch (Exception e) {
            return "UNKNOWN";  // 其它异常同样不构成「没起来」的证据
        }
    }

    private boolean isWebPortUp(int timeoutMs) {
        return "UP".equals(probeWebPort(timeoutMs));
    }

    /** 轮询等待 Web 端口彻底关闭；maxMs 内仍被占用返回 false */
    private boolean waitPortClosed(long maxMs) {
        int port = parsePort();
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            } catch (Exception e) {
                return true; // 端口已不可达
            }
        }
        return false;
    }

    /** 轮询等待 Web 端口就绪；maxMs 内仍不可达返回 false（启动超时） */
    private boolean waitWebPortUp(long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (isWebPortUp(300)) return true;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    private void destroyAllWebProcesses() {
        for (Process p : webProcesses) {
            try { p.destroy(); } catch (Throwable ignored) {
            }
        }
        try { webProcesses.clear(); } catch (Throwable ignored) {
        }
        synchronized (webStartLock) {
            webProcess = null;
        }
    }

    /** 同步停止（等端口关透）：供强重启/插件变更使用；常规杀不净则宽杀 node */
    public void stopWebAndWait() {
        try {
            destroyAllWebProcesses();
            proot.execAndRead(stopWebCommand());
            if (!waitPortClosed(5000)) {
                // 只杀 dsh web 相关进程（bin.js web / dsh web），不裸杀 node
                // （裸 pkill -f node 会误杀 agent/用户跑的其他 node 进程！）
                proot.execAndRead("pkill -TERM -f 'bin.js web' 2>/dev/null; pkill -TERM -f 'dsh web' 2>/dev/null; "
                        + "sleep 3; "
                        + "pkill -9 -f 'bin.js web' 2>/dev/null; pkill -9 -f 'dsh web' 2>/dev/null; "
                        + "sleep 1; echo done");
                waitPortClosed(5000);
            }
            // Web 停了桥也没用：停桥（幂等，HarnessService.onDestroy 也会停）
            LanProxyService.stop();
        } catch (Throwable ignored) {
        }
    }

    /** 强重启（进程级，杀干净）：先深停 web → 杀 App 进程 → Alarm 拉起全新进程 */
    public void restartAppProcess(final android.content.Context ctx) {
        new Thread(() -> {
            try {
                destroyAllWebProcesses();
                proot.execAndRead(stopWebCommand());
                waitPortClosed(6000);
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
            }
            try {
                android.app.AlarmManager am = (android.app.AlarmManager)
                        ctx.getSystemService(android.content.Context.ALARM_SERVICE);
                android.content.Intent i = new android.content.Intent(ctx, MainActivity.class);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        ctx, 0, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 350, pi);
            } catch (Throwable ignored) {
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                }
            }, 250);
        }).start();
    }

    /** 是否正在“自动补构建”（缺 bin.js 时启动触发） */
    public boolean isBuilding() {
        try {
            return new java.io.File(proot.getRootfsDir(), "root/.dsha-building").isFile();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 解析 rootfs ~/dsh-web.log 尾部，抽取关键错误给启动页展示 */
    public String diagnoseWebFailure() {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsh-web.log");
            if (!f.isFile() || f.length() == 0) return "未找到 WebUI 日志（~/dsh-web.log）";
            long len = Math.min(f.length(), 16384);
            byte[] bytes;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                bytes = new byte[(int) len];
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            String log = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Cannot find module '([^']+)'").matcher(log);
            if (m.find()) {
                String mod = m.group(1);
                if (mod.endsWith("apps/cli/lib/bin.js")) {
                    return "入口文件缺失：" + mod + "\n（deepseek-harness 未构建成功；请点「重启」自动补构建，或重跑安装步骤⑤）";
                }
                return "缺少模块：" + mod + "\n（依赖安装不完整，已自动重装；仍失败请重跑步骤⑤）";
            }
            // 一类会把用户锁在外面的死锁：dsh 的插件列表里会列出它自己的依赖
            // cordis-plugin-hmr（热重载），而那个插件要求 node 带 --expose-internals。
            // 用户在 WebUI 里手一点启用，重启后整个 profile 加载失败 → Web 起不来
            // → 进不去 WebUI → 也就没法把它关掉。自动在 patch 层禁用它。
            if (log.contains("--expose-internals is required")
                    || (log.contains("failed to apply loader entry") && log.contains("plugin-hmr"))) {
                String heal = runHealProfileBoot();
                if (heal != null && heal.contains("HEAL_PROFILE_OK: 禁用")) {
                    return "已自动关闭热重载插件（cordis-plugin-hmr）—— 它需要 Node 的 "
                            + "--expose-internals 启动参数，缺了会让整个 profile 加载失败。\n"
                            + "点「重启」即可正常进入。普通使用不需要热重载；想恢复可编辑 "
                            + "~/.dsh/profiles/web/cordis.patch.yml 删掉那几行。";
                }
                return "WebUI 启动失败：启用了需要 Node --expose-internals 的插件"
                        + "（cordis-plugin-hmr 热重载）。\n自动关闭没成功，可手动编辑 "
                        + "~/.dsh/profiles/web/cordis.patch.yml，在末尾加：\n"
                        + "- id: <日志里 entry 后面那串 id>\n  disabled: true";
            }
            if (log.contains("MODULE_NOT_FOUND")) {
                return "MODULE_NOT_FOUND：入口依赖缺失，请重跑安装步骤⑤（应用已自动尝试自愈）";
            }
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("ValidationError[^\\n]*").matcher(log);
            if (vm.find()) {
                return "配置校验失败：" + vm.group().trim() + "\n（已自动钳制超限配置；仍有问题可重置配置）";
            }
            String tail = log.trim();
            int nl = tail.lastIndexOf('\n');
            if (nl >= 0) tail = tail.substring(nl + 1);
            if (log.contains("dsh web:")) {
                // 探测结果分三种，措辞也要分三种 —— 以前「超时」被说成「未就绪」，
                // 而那时候服务往往正在起，用户被引导去做多余的重启
                String probe = probeWebPort(600);
                if ("UP".equals(probe)) {
                    return "Web 服务正在运行但页面探测失败，可点「打开预览」或「重启」再试。";
                }
                if ("UNKNOWN".equals(probe)) {
                    return "Web 已打印启动地址，但探测端口超时（没被拒绝，也没连上）——"
                            + "通常是还在加载依赖或系统正忙。\n先等十几秒再点「打开预览」；"
                            + "一直这样再看 ~/dsh-web.log。";
                }
                return "Web 启动过（已打印 URL），但端口 " + parsePort()
                        + " 上没有服务在监听（连接被拒）：\n"
                        + "多为进程随后退出、端口被别的应用占用，或依赖加载失败。\n"
                        + "点「重启」再试；仍不行请查看 ~/dsh-web.log 完整内容。";
            }
            return tail.isEmpty() ? "WebUI 异常退出（日志为空）" : "WebUI 异常退出：\n" + tail;
        } catch (Exception e) {
            return "无法解析 WebUI 日志：" + e.getMessage();
        }
    }
    /** 启动失败自愈：把「启用了却跑不起来」的 loader entry 在 profile 的 patch 层禁用。
     *  只针对已知需要特殊 node 标志的插件；别的失败原因（缺依赖、配置错）不该靠禁用掩盖。 */
    private String runHealProfileBoot() {
        try {
            String script = readAsset("heal-profile-boot.py");
            if (script == null || script.isEmpty()) return null;
            java.io.File dst = new java.io.File(proot.getRootfsDir(), "root/.dsha-heal-profile.py");
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(),
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String out = proot.execAndRead(
                    "python3 /root/.dsha-heal-profile.py --log /root/dsh-web.log"
                            + " --profile /root/.dsh/profiles/web 2>&1; "
                            + "rm -f /root/.dsha-heal-profile.py", 60_000);
            android.util.Log.i("DSHA", "profile 启动自愈: " + (out == null ? "无输出" : out.trim()));
            return out;
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "profile 启动自愈失败: " + e);
            return null;
        }
    }

    /** 局域网 0.0.0.0 放行补丁是否已就绪（防重复打补丁） */
    private volatile boolean lanBindReady = false;
    /** 进度持久化节流用时间戳 */
    private volatile long lastStageWriteTs = 0;

    // ===== 步骤状态缓存（UI 频繁查询，其内部会起 proot 检查，慢） =====
    /** 步骤缓存时间戳，-1 表示无效需重算 */
    private volatile long stepCacheTs = -1;
    private final boolean[] stepCache = new boolean[7];
    /** 步骤"可更新"缓存（装了旧版但未达标：⑤ dsh 旧版 / ⑥ 守卫版本旧）；
     *  与 stepCache 同一次 proot 查询算出，同生命周期。 */
    private final boolean[] updatableCache = new boolean[7];

    /** 使步骤缓存失效：安装结束/空闲时调用，让 UI 拿到最新状态 */
    private void invalidateStepCache() {
        stepCacheTs = -1;
    }

    /** 供 UI 层（如卸载环境后）强制刷新步骤状态 */
    public void invalidateSteps() {
        invalidateStepCache();
    }

    // ===== 下载源自选（测速 → 弹窗等待用户选择） =====
    private final Object sourceLock = new Object();
    private volatile boolean awaitingSource = false;
    private volatile int sourceChoice = -1;
    private volatile int pendingTask = 0;
    private volatile String[] pendingUrls = null;
    private volatile long[] pendingLat = null;

    public static synchronized HarnessController get(Context ctx) {
        if (instance == null) {
            instance = new HarnessController(ctx.getApplicationContext());
        }
        return instance;
    }

    private HarnessController(Context ctx) {
        this.appContext = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.proot = new ProotBootstrap(ctx);
    }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public String getStage() { return stage; }
    public int getPercent() { return percent; }
    public String getMessage() { return message; }
    public String getError() { return error; }
    /** 是否忙碌。带超时自愈：busy 卡住超过阈值视为假死，自动释放并记录。 */
    public boolean isBusy() {
        if (busy && busySince > 0 && System.currentTimeMillis() - busySince > BUSY_STALE_MS) {
            // 自愈：任务卡死超时，强制释放 busy，避免后续操作全部被挡（App 假死）
            android.util.Log.w("DSHA", "busy 超时自愈：任务卡死超过 " + (BUSY_STALE_MS / 60000) + " 分钟，强制释放");
            busy = false;
            busySince = 0;
            error = "上一次操作超时被自愈（可能是网络/环境问题），请重试";
        }
        return busy;
    }

    /** 尝试原子获取 busy（防并发重入）；获取失败返回 false */
    public boolean tryBeginBusy() {
        if (isBusy()) return false; // isBusy 内部已处理超时自愈
        synchronized (this) {
            if (busy) return false;
            busy = true;
            busySince = System.currentTimeMillis();
            return true;
        }
    }

    /** 当前正在执行的步骤（0 = 空闲） */
    public int getCurrentStep() { return currentStep; }

    private void setState(String stage, int percent, String msg, String err, boolean b) {
        this.stage = stage;
        this.percent = percent;
        this.message = msg;
        this.error = err;
        this.busy = b;
        this.busySince = b ? System.currentTimeMillis() : 0;
        // 持久化进度，闪退后下次启动可定位中断步骤（节流：最多 2 秒写一次，避免磁盘 IO 卡顿）
        if (!stage.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - lastStageWriteTs > 2000) {
                lastStageWriteTs = now;
                prefs.edit().putString("last_stage", stage + " " + percent + "%").apply();
            }
        }
        if (err != null && !err.isEmpty()) {
            prefs.edit().putString("last_error", err).apply();
        }
        // 状态可能在 IO 线程变更，回调需切回主线程再通知 UI
        mainHandler.post(() -> {
            for (StateListener l : stateListeners) l.onStateChanged();
        });
        // 空闲时（busy=false）步骤状态可能已变化，失效缓存让 UI 下次查到最新值；
        // busy 期间步骤不变，保留缓存避免每次进度广播都重查 proot（否则极卡）
        if (!b) {
            invalidateStepCache();
        }
    }

    public String getLastStage() { return prefs.getString("last_stage", ""); }
    public String getLastError() { return prefs.getString("last_error", ""); }

    private void setProgress(String stage, int percent) {
        setState(stage, percent, "", "", true);
    }

    /** 生成可读的错误描述（含异常类名与堆栈首帧，便于排查） */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append("\n    at ").append(st[0].toString());
        }
        return sb.toString();
    }

    /** 报错文案统一附加 App 版本号（方便确认用户是否用新版 APK） */
    private String errMsg(String prefix, Throwable e) {
        String v = "?";
        try {
            v = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return prefix + "（DSHA v" + v + "）" + describe(e);
    }

    /** 步骤显示名 */
    public static String stepName(int step) {
        switch (step) {
            case STEP_ROOTFS: return "① Linux 环境（rootfs）";
            case STEP_TOOLS: return "② 基础工具（apt）";
            case STEP_NODE: return "③ Node.js";
            case STEP_PNPM: return "④ Node 附加工具（pnpm/node-gyp）";
            case STEP_HARNESS: return "⑤ deepseek-harness";
            case STEP_GUARD: return "⑥ 安全与补丁（守卫/dsh命令/polyfill）";
        }
        return "步骤 " + step;
    }

    /** 从 URL 取主机名（进度显示用） */
    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ================= 配置读写 =================
    public String getApiKey() { return prefs.getString("api_key", ""); }
    public void setApiKey(String v) { prefs.edit().putString("api_key", v).apply(); }

    public String getPort() {
        // 兜底校验：空/非数字/越界全部回退默认 3080（否则 --port 后是空串导致启动失败）
        String p = prefs.getString("port", "3080");
        if (p == null) return "3080";
        String t = p.trim();
        if (t.isEmpty()) return "3080";
        try {
            int n = Integer.parseInt(t);
            if (n < 1 || n > 65535) return "3080";
            return String.valueOf(n);
        } catch (Exception e) {
            return "3080";
        }
    }

    private int parsePort() {
        try {
            return Integer.parseInt(getPort());
        } catch (Exception e) {
            return 3080;
        }
    }
    /** 保存端口时就校验，避免 UI 显示已保存但启动时静默回退。 */
    public void setPort(String v) {
        try {
            int n = Integer.parseInt(v == null ? "" : v.trim());
            if (n < 1 || n > 65535 || n == LanProxyService.LAN_PORT) return;
            prefs.edit().putString("port", String.valueOf(n)).apply();
        } catch (Exception ignored) {
        }
    }

    public String getModel() { return prefs.getString("model", "deepseek-v4-flash"); }
    public void setModel(String v) {
        if (v == null) return;
        String t = v.trim();
        if (t.isEmpty() || t.length() > 128 || !t.matches("[A-Za-z0-9._:/-]+")) return;
        prefs.edit().putString("model", t).apply();
    }

    private static final java.util.Set<String> PERMISSION_MODES =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "danger-full-access", "workspace-write", "read-only")));

    public String getPermissionMode() {
        String mode = prefs.getString("permission_mode", "danger-full-access");
        return PERMISSION_MODES.contains(mode) ? mode : "danger-full-access";
    }
    public void setPermissionMode(String v) {
        if (v != null && PERMISSION_MODES.contains(v)) {
            prefs.edit().putString("permission_mode", v).apply();
        }
    }

    /** agent 是否被允许使用 root shell（--su 提权）。默认关，配置页手动授权。 */
    public boolean isRootShellAllowed() {
        return prefs.getBoolean("allow_root_shell", false);
    }
    public void setRootShellAllowed(boolean v) { prefs.edit().putBoolean("allow_root_shell", v).apply(); }

    public String getWorkdir() {
        String value = prefs.getString("workdir", "deepseek-harness");
        if (!isSafeWorkdir(value)) {
            // 兼容旧版本已经写入的损坏值：回退并持久化，不能继续拼进 shell。
            prefs.edit().putString("workdir", "deepseek-harness").apply();
            value = "deepseek-harness";
        }
        // ===== 工作区名自适应 =====
        // 配置值目录不存在源码入口（apps/cli/lib/bin.js 或 lib/bin.js）时，
        // 自动扫描 /root 认唯一含源码入口的目录并回写——这样「启动/看门狗/
        // 备份/重置/守卫补丁」全部跟随真实目录，不再各自用旧配置值。
        // RC6 全局 npm 模式（无源码树）扫不到 → 保持配置值不变。
        if (!workdirExists(value)) {
            String detected = scanWorkdirSource();
            if (detected != null) {
                prefs.edit().putString("workdir", detected).apply();
                return detected;
            }
        }
        return value;
    }

    /** 扫描 rootfs /root 下含 harness 源码入口（apps/cli/lib/bin.js / lib/bin.js）的目录名；无则 null。 */
    private String scanWorkdirSource() {
        try {
            java.io.File root = new java.io.File(proot.getRootfsDir(), "root");
            java.io.File[] dirs = root.isDirectory() ? root.listFiles(java.io.File::isDirectory) : null;
            if (dirs != null) for (java.io.File d : dirs) {
                if (new java.io.File(d, "apps/cli/lib/bin.js").isFile()
                        || new java.io.File(d, "lib/bin.js").isFile()) {
                    return d.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 设置工作目录：只允许安全字符（字母数字下划线连字符），防 shell 注入。 */
    public void setWorkdir(String v) {
        if (v == null) return;
        String t = v.trim();
        if (!isSafeWorkdir(t)) return; // 非法：拒绝（保持原值）
        prefs.edit().putString("workdir", t).apply();
    }

    private static boolean isSafeWorkdir(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}")
                && !".".equals(value) && !"..".equals(value);
    }

    /** 供局域网桥使用的规范化端口。 */
    public int getPortInt() {
        return parsePort();
    }

    /** 局域网模式是否开启（App 设置项） */
    public boolean isLanMode() {
        return prefs.getBoolean("lan_mode", false);
    }

    /** rootfs 绝对路径（供桥等写日志） */
    public String getRootfsDirPath() {
        return proot.getRootfsDir().getAbsolutePath();
    }

    /** 实际工作目录自愈：直接委托 getWorkdir()（其内部已含“配置缺失→扫描 /root
     *  认源码目录并回写”的自适应逻辑）。 */
    public String detectWorkdir() {
        return getWorkdir();
    }

    private boolean workdirExists(String wd) {
        try {
            return new java.io.File(proot.getRootfsDir(), "root/" + wd + "/apps/cli/lib/bin.js").isFile()
                    || new java.io.File(proot.getRootfsDir(), "root/" + wd + "/lib/bin.js").isFile();
        } catch (Exception e) {
            return false;
        }
    }

    public String effectiveApiKey() {
        return getApiKey();
    }

    /** shell 单引号转义：API key 等用户输入嵌入 shell 命令时防注入/防破坏
     *  （key 含 ' 时未转义会导致启动命令断裂，Web 起不来） */
    private static String escShell(String s) {
        if (s == null) return "";
        return s.replace("'", "'\\''");
    }

    /** 写入 .env 时清理非法字符（换行/控制符会破坏 env 文件解析） */
    private static String cleanEnvValue(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\u0000]", "").trim();
    }

    public ProotBootstrap getProot() { return proot; }

    /** 是否已安装 deepseek-harness（跟随自定义工作区路径；RC6 模式检查 dsh 命令） */
    public boolean isHarnessInstalled() {
        if (proot.isHarnessInstalled(getWorkdir())) return true;
        try {
            String r = proot.execAndRead("command -v dsh 2>/dev/null || echo MISSING");
            return r != null && !r.startsWith("ERROR") && !r.contains("MISSING") && !r.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Web UI 进程是否在运行 */
    public boolean isWebRunning() {
        return webProcess != null && webProcess.isAlive();
    }


    /** 重启 Web UI：原子完成 深停→等端口关透→拉起（复用 stopWebAndWait + startWeb）。
     *  webRestartLock 防重复点击；与 stop 排队语义确定。 */
    public void restartWeb() {
        if (!tryAcquireWebRestartLock()) return; // 正在重启，忽略重复
        IO.execute(() -> {
            try {
                setProgress("正在重启 Web UI（先停止）", 0);
                stopWebAndWait(); // 深停：destroy + pkill 看门狗/web + 等端口关透 + 宽杀兜底
                setProgress("正在重启 Web UI（再启动）", 0);
                startWeb();
            } catch (Throwable e) {
                setState("", 0, "", errMsg("重启出错：", e), false);
            } finally {
                releaseWebRestartLock();
            }
        });
    }

    /** 用户手动停止后，keepAlive 是否应暂停自动拉起（直到再次 startWeb） */
    public boolean isKeepAlivePaused() {
        return prefs.getBoolean("keepalive_paused", false);
    }

    /** 预启动阈值：距上次手动停止小于该值则尊重用户、不自动拉起（ms） */
    private static final long PREWARM_STOP_GUARD_MS = 90_000;

    /**
     * 自动后台预启动（进入启动页/App 前台时调用）：
     * 环境就绪 && web 未运行 && 用户近期未手动停止 → 后台静默 startWeb()，
     * 让用户点「启动」时基本秒开。幂等：web 已在跑/启动中自动跳过。
     */
    /** 确保配置自愈脚本已写入 rootfs（启动前把超限 timeoutMs 钳回合法值，防 ValidationError 崩溃 WebUI） */
    /** 每次启动前校验内置 bundle（mobile-adapt / device-shell-guide）：
     *  若被 dsh plugin reconcile 清掉/链接丢失，自动补回注册（幂等，秒级）。
     *  防止"插件莫名其妙消失导致没效果"。 */
    private void ensureBuiltinBundles() {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            if (root.optJSONObject("dsh") == null) return;
            org.json.JSONObject profObj = root.optJSONObject("dsh").optJSONObject("profile");
            if (profObj == null) return;
            org.json.JSONArray bundles = profObj.optJSONArray("bundles");
            if (bundles == null) return;
            String[][] builtins = {
                    {"dsh-client-ui-mobile-adapt", "/root/dsha-mobile-adapt"},
                    {"dsh-device-shell-guide", "/root/dsha-device-shell-guide"},
                    {"dsh-task-notifier", "/root/dsha-task-notifier"},
            };
            boolean changed = false;
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) {
                deps = new org.json.JSONObject();
                root.put("dependencies", deps);
            }
            for (String[] b : builtins) {
                String name = b[0], real = b[1];
                boolean inBundles = false;
                for (int i = 0; i < bundles.length(); i++) {
                    if (name.equals(bundles.optString(i, "").trim())) { inBundles = true; break; }
                }
                boolean dirOk = new java.io.File(proot.getRootfsDir(), "root" + real.substring(4)).isDirectory();
                // 实体目录不见了（被清理/从没装成）→ 先把 assets 里的补出来再谈注册，
                // 否则这里只会一路 return，用户看到的就是「插件没自动安装」
                if (!dirOk && "dsh-device-shell-guide".equals(name)) {
                    ensureDeviceShellGuide();
                    dirOk = new java.io.File(proot.getRootfsDir(), "root" + real.substring(4)).isDirectory();
                }
                // 用户主动禁用（.disabled 存在）→ 跳过补回（尊重用户）
                boolean userDisabled = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/profiles/web/node_modules/" + name + ".disabled").exists();
                if (dirOk && !userDisabled && !inBundles) {
                    bundles.put(name);
                    changed = true;
                    android.util.Log.w("DSHA", "内置插件 " + name + " 被清掉，已自动补回");
                }
                // 关键：dsh 的 reconcile 会把「bundles 里有名字、但 dependencies 没有
                // 对应声明」的项判为无法解析并摘掉。以前这里只补 bundles 和符号链接，
                // 于是形成「补回 → 被摘 → 再补回」的死循环，用户看到的就是插件一直没生效
                // （实测现场：实体在、node_modules 链接在，bundles 与 dependencies 双缺）。
                if (dirOk && !userDisabled && !deps.has(name)) {
                    deps.put(name, "link:" + real);
                    changed = true;
                    android.util.Log.w("DSHA", "内置插件 " + name + " 缺 dependencies 声明，已补 link:");
                }
                java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/profiles/web/node_modules/" + name);
                if (dirOk && !userDisabled && !nmLink.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(nmLink.toPath(), java.nio.file.Paths.get(real));
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (changed) {
                java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }


    /** 清理极简模式自定义预设（dsha-minimal 已废弃，删除残留文件） */

    private void ensureConfigFixAsset() {
        try {
            String js = readAsset("config-fix.js");
            if (js == null || js.isEmpty()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsh-config-fix.js");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), js.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            f.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    /** 确保前端"插件失败降级"热补丁已应用（对编译产物打，幂等，RC6/源码通用）：
     *  坏插件不再卡死整个 WebUI 启动。 */
    private void ensureWebUiDegrade() {
        runAssetScript("webui-degrade-patch.sh", "dsha-degrade.sh", 60_000);
    }

    /**
     * 校验并补装 dsh 全局包缺失的 @deepseek-ai/* 子包依赖。
     * 背景（dsh-issue-report）：npmmirror 镜像元数据缓存不一致导致 rc.8 部分子包
     * （dsh-client-ui-slots / dsh-client-ui-primitives 等）声明了依赖但安装时未解析，
     * 服务端插件 require 时 Cannot find module。幂等：全就绪秒回（只读检查）。
     * 在 IO 线程执行（起 proot 子进程，不能卡主线程）。
     */
    public void maybeHealDshDeps() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                String script = readAsset("dsh-deps-heal.sh");
                if (script == null || script.isEmpty()) return;
                java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-deps-heal.sh");
                f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
                String r = proot.execAndRead("bash /root/dsha-deps-heal.sh; rm -f /root/dsha-deps-heal.sh");
                if (r != null && (r.contains("HEAL_OK") || r.contains("HEAL_DONE"))) {
                    android.util.Log.i("DSHA", "dsh 子包依赖自愈: " + (r.contains("HEAL_DONE") ? "已补装" : "已就绪"));
                } else if (r != null && r.contains("HEAL_PARTIAL")) {
                    android.util.Log.w("DSHA", "dsh 子包依赖部分缺失（构建期包，可忽略）: " + r.trim());
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 确保 WebUI 老浏览器兼容补丁已应用（AbortSignal.any/timeout polyfill，幂等）。
     *  老版本 Android System WebView（< Chrome 116）没有 AbortSignal.any/timeout，
     *  dsh 前端在选择工作区等带取消的 remote 调用上会抛 "AbortSignal.any is not a function"。
     *  RC6 / 源码构建通用；失败不阻塞启动。 */
    /** 后台静默补 write 发布补丁（开 App 即跑，不必等点「启动」——
     *  agent 在已运行的 WebUI 里就可能用 write 工具，等启动就晚了）。 */
    public void maybeFixFsWrite() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                ensureFsWritePatch();
            } catch (Throwable ignored) {
            }
        });
    }

    /** 一键自检：注入 selftest.py 跑一遍**只读**检查（环境/3090 桥/ADB/引导插件/
     *  write 补丁/会话/bundles/备份/守卫/版本标记），返回给用户看的报告。
     *  期望版本由这里传进脚本，避免版本号在两边各写一份。 */
    public String runSelfTest() {
        if (!proot.isInstalled()) {
            return "环境未就绪：请先完成内置环境的解压/安装，再运行自检。";
        }
        try {
            String py = readAsset("selftest.py");
            if (py == null || py.isEmpty()) return "自检脚本缺失（APK 资源异常）";
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-selftest.py");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), py.getBytes(StandardCharsets.UTF_8));
            String args = " --script-ver " + AdbBridge.scriptVersion()
                    + " --guard-ver " + GUARD_VERSION
                    + " --step6 " + STEP6_VERSION
                    + " --assets " + BUILTIN_ASSET_VERSION
                    + " --guide-ver " + builtinGuideVersion()
                    + " --adb-on " + (prefs.getBoolean(Constants.KEY_ADB_ENABLED, false) ? "1" : "0")
                    + " --battery-opt " + (batteryOptWhitelisted() ? "1" : "0");
            String out = proot.execAndRead(
                    "python3 /root/dsha-selftest.py" + args
                            + " 2>&1; rm -f /root/dsha-selftest.py", 180_000);
            if (out == null || out.trim().isEmpty()) {
                return "自检没有输出：rootfs 内的 python3 可能不可用（可重跑步骤②安装基础工具）。";
            }
            return out.trim();
        } catch (Throwable e) {
            return "自检失败：" + describe(e);
        }
    }

    /** 插件是否真的注册进 web profile（bundles 里有名字 + node_modules 链接在） */
    private boolean guideRegistered(String name) {
        try {
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + name);
            if (!nmLink.exists()) return false;
            java.io.File pf = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return false;
            org.json.JSONObject root = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8));
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray bundles = prof == null ? null : prof.optJSONArray("bundles");
            if (bundles == null) return false;
            boolean inBundles = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (name.equals(bundles.optString(i, "").trim())) {
                    inBundles = true;
                    break;
                }
            }
            // dependencies 声明缺失时，dsh reconcile 会把它从 bundles 里摘掉 ——
            // 所以「注册成功」必须两者都在，只看 bundles 会误判为已就绪
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            boolean inDeps = deps != null && deps.optString(name, "").startsWith("link:");
            return inBundles && inDeps;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 启动自愈：确保内置插件（设备引导 / 任务通知 / 移动端适配）实体在位且注册有效。
     *  不再只依赖步骤⑥ —— ⑥ 可能跑在 profile 生成之前，也可能被版本标记判定跳过。 */
    public void ensureBuiltinPluginsReady() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                ensureDeviceShellGuide();
                ensureTaskNotifier();
                ensureBuiltinBundles();
                if (!guideRegistered("dsh-device-shell-guide")) {
                    boolean disabled = new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/profiles/web/node_modules/dsh-device-shell-guide.disabled").exists();
                    if (!disabled) {
                        android.util.Log.w("DSHA", "设备引导插件仍未注册（profile 可能还没生成，"
                                + "启动一次 WebUI 后会自动补上）");
                    }
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "内置插件自愈失败（不影响启动）: " + e);
            }
        });
    }

    /** 是否已加入电池优化白名单（自检据此判断保活能不能真正生效） */
    private boolean batteryOptWhitelisted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    appContext.getSystemService(android.content.Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(appContext.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** assets 里内置引导插件的版本号（自检据此对账 rootfs 内已装的那份） */
    private String builtinGuideVersion() {
        try {
            String json = readAsset("device-shell-guide/package.json");
            if (json == null || json.isEmpty()) return "";
            return new org.json.JSONObject(json).optString("version", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 启动前自愈：dsh 的 write 工具在 proot 下新建文件会变悬空链接
     *  （dsh 用 link(临时文件,目标) 发布 + 删临时目录，撞上 proot 的 --link2symlink）。
     *  给 fs-local 打「目标不存在改用 rename 发布」的补丁，幂等，命中已打过时 0.05 秒返回。 */
    public void ensureFsWritePatch() {
        String r = runAssetScript("fs-write-patch.sh", "dsha-fs-write-patch.sh", 90_000);
        android.util.Log.i("DSHA", "write 发布补丁: " + (r == null ? "无输出" : r.trim()));
    }

    /** 启动前自愈：老 WebView 兼容补丁（AbortSignal.any/timeout polyfill，幂等） */
    private void ensureWebUiPolyfill() {
        // dsh 的 Web 服务本身没有鉴权（上游只绑 127.0.0.1，而 Android 上任何 App
        // 都能访问回环且不需要权限）→ 给它加 token 校验，否则随便一个应用就能读走
        // 全部会话、建会话让 agent 执行 bash。
        runAssetScript("webserver-auth-patch.sh", "dsha-webserver-auth.sh", 60_000);
        runAssetScript("webui-polyfill.sh", "dsha-webui-polyfill.sh", 60_000);
    }

    /** 确保外部浏览器 /api 403 修复已应用（Chrome 150+ Origin 省略端口，幂等）。
     *  dsh-client-connection 的 isTrustedApiRequest 用 new URL(origin).host === hostUrl.host
     *  比较 Origin 与 Host；Chrome 150+ 对 loopback 同源请求发送的 Origin 省略端口，
     *  导致 127.0.0.1:3080 页面所有 /api 请求被 403 拒绝，表现为外部浏览器无法连接网络。
     *  修复为只比较 hostname；失败不阻塞启动。 */
    private void ensureWebUiOriginPatch() {
        runAssetScript("webui-origin-port-patch.sh", "dsha-origin-port-patch.sh", 60_000);
    }

    public void maybePrewarmWeb() {
        if (isHealingSession()) return; // 自愈进行中不预启动（防写会话文件）
        try {
            ensureWebUiDegrade(); // 每次启动前置自愈（幂等秒回，防插件失败卡启动）
        } catch (Throwable ignored) {
        }
        try {
            if (!proot.isInstalled() || !isHarnessInstalled()) return; // 环境/harness 未装
            if (webProcess != null && webProcess.isAlive()) return;    // 已在运行
            // 用户主动停止过（keepalive_paused=true）→ 不自动拉起，尊重用户（除非再次点启动）
            if (isKeepAlivePaused()) return;
            // 尊重用户：90s 内手动停止过 → 不自动拉起
            long lastStop = prefs.getLong("last_web_stop", 0);
            if (System.currentTimeMillis() - lastStop < PREWARM_STOP_GUARD_MS) return;
            android.util.Log.i("DSHA", "[预启动] 后台预热 Web UI…");
            startWeb();
        } catch (Throwable ignored) {
        }
    }

    // ================= 分步安装 =================

    /** 一键安装：按顺序补装尚未完成的步骤 */
    public void install() {
        if (!tryBeginBusy()) return;
        invalidateStepCache(); // 重新判定安装状态
        IO.execute(() -> {
            try {
                for (int s = STEP_ROOTFS; s <= STEP_GUARD; s++) {
                    if (!isStepDone(s)) runInstallStep(s);
                }
                setState("", 100, "全部安装完成，可到「启动」页启动 Web UI", "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("安装出错：", e), false);
            }
        });
    }

    /** 单独执行一个步骤（已完成则视为重装/更新） */
    public void installStep(int step) {
        if (!tryBeginBusy()) return;
        invalidateStepCache(); // 重新判定安装状态
        IO.execute(() -> {
            try {
                runInstallStep(step);
                setState("", 100, "「" + stepName(step) + "」完成", "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("安装出错：", e), false);
            }
        });
    }

    /** 步骤是否已完成（UI 打勾用）。内部全部走缓存：步骤一旦装完在缓存有效期内不变 */
    public boolean isStepDone(int step) {
        return stepDoneSnapshot()[step];
    }

    /**
     * 批量查询 4 个步骤是否完成（下标 1~4 对应 STEP_*；0 恒 false）。
     * 结果带缓存：busy 期间避免重复起 proot 检查（起一次 rootfs 子进程很慢）；
     * 缓存 5 秒自然过期，或安装结束（busy=false）被 setState 主动失效。
     */
    public boolean[] stepDoneSnapshot() {
        return stepDoneSnapshot(true);
    }

    /** 只读当前步骤缓存（不触发重算，主线程安全零耗时）；未初始化时返回全 false */
    public boolean[] peekStepCache() {
        synchronized (stepCache) {
            return stepCache.clone();
        }
    }

    /** 只读当前"可更新"缓存（装了旧版但未达标：⑤ dsh 旧版 / ⑥ 守卫版本旧）。
     *  与 peekStepCache 同生命周期（同一次 proot 查询算出）。 */
    public boolean[] peekUpdatableCache() {
        synchronized (stepCache) {
            return updatableCache.clone();
        }
    }

    /**
     * 批量查询 4 个步骤是否完成（下标 1~4 对应 STEP_*；0 恒 false）。
     * 结果带缓存：busy 期间避免重复起 proot 检查（起一次 rootfs 子进程很慢）；
     * 缓存 5 秒自然过期，或安装结束（busy=false）被 setState 主动失效。
     * @param allowCompute 是否允许缓存过期时重算（false 时只返回缓存，不重算）
     */
    private boolean[] stepDoneSnapshot(boolean allowCompute) {
        long ts = stepCacheTs;
        if (ts >= 0 && System.currentTimeMillis() - ts < 5000) {
            return stepCache.clone(); // 缓存内，直接返回副本
        }
        if (!allowCompute) {
            synchronized (stepCache) { // 只读：短锁，零耗时
                return stepCache.clone();
            }
        }
        synchronized (stepCache) {
            // 双重检查（短锁）
            long ts2 = stepCacheTs;
            if (ts2 >= 0 && System.currentTimeMillis() - ts2 < 5000) {
                return stepCache.clone();
            }
        }
        // 重算：不持锁！proot 子进程很慢（1~3 秒），持锁会把主线程 peek 一起卡死
        boolean r1 = proot.isInstalled();
        // 优化：②④⑤⑥ 四项 rootfs 检查合并为【单次 proot 进程】执行，
        // 原来各起一个子进程（串行 4~12s），现在 1~3s 搞定。
        // U=EF 附加"可更新"检测：E=装了旧版 dsh（rc<8），F=守卫版本旧（.version≠当前）。
        String merged = proot.execAndRead(
                "A=$(command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 " +
                "&& command -v python3 >/dev/null 2>&1 && command -v make >/dev/null 2>&1 " +
                "&& command -v gcc >/dev/null 2>&1 && command -v xz >/dev/null 2>&1 && echo 1 || echo 0); " +
                "B=$(command -v pnpm >/dev/null 2>&1 && command -v node-gyp >/dev/null 2>&1 && echo 1 || echo 0); " +
                "C=$(command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE); " +
                "D=$(test -f /root/dsh-guard.sh && test -d /root/dsh-bin && test -f /root/dsh-bin/.version && echo 1 || echo 0); " +
                "E=$(command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE); " +
                "F=$(test -f /root/dsh-bin/.version && V2=$(cat /root/dsh-bin/.version 2>/dev/null) " +
                "&& [ -n \"$V2\" ] && [ \"$V2\" != \"" + GUARD_VERSION + "\" ] && echo 1 || echo 0); " +
                // C/E 是完整版本号（如 0.1.1-rc.2），不能拼进 R/U（会破坏位解析）！
                // 转为 0/1 位：C_OK=dsh 存在（具体版本判定在 Java 侧），
                // R 用 A/B/C_OK/D，U 用 E_OK/F
                "C_OK=$(command -v dsh >/dev/null 2>&1 && echo 1 || echo 0); " +
                "E_OK=$(command -v dsh >/dev/null 2>&1 && echo 1 || echo 0); " +
                "echo V=\"$C\" \"$E\"; " +
                "echo R=$A$B$C_OK$D U=$E_OK$F");
        // 解析 R=ABCD：不用 matches() 正则（全匹配会被 echo 末尾换行坑到，之前
        // 因此②④⑤⑥全显示未安装）——直接用 indexOf + substring 取 4 位
        boolean[] bits = new boolean[4];
        int ri = merged == null ? -1 : merged.indexOf("R=");
        if (ri >= 0 && ri + 6 <= merged.length()) {
            String b = merged.substring(ri + 2, ri + 6);
            for (int i = 0; i < 4; i++) bits[i] = b.charAt(i) == '1';
        }
        // 解析 U=EF（可更新标记）
        boolean[] upd = new boolean[2];
        int ui = merged == null ? -1 : merged.indexOf("U=");
        if (ui >= 0 && ui + 4 <= merged.length()) {
            String b = merged.substring(ui + 2, ui + 4);
            upd[0] = b.charAt(0) == '1';
            upd[1] = b.charAt(1) == '1';
        }
        // 版本号从 V= 行提取（C/E 是完整版本如 0.1.1-rc.2，命令里单独 echo V=$C|$E；
        // 之前 C 直接拼进 R 导致 R=110.1.1-rc.21 位解析错乱 → 步骤⑤永远未安装）
        String dshVer = "";
        int vi = merged == null ? -1 : merged.indexOf("V=");
        if (vi >= 0) {
            String vv = merged.substring(vi + 2);
            int amp = vv.indexOf(' ');
            if (amp >= 0) vv = vv.substring(0, amp); // 取 $C（空格分隔）
            int nl2 = vv.indexOf('\n');
            if (nl2 >= 0) vv = vv.substring(0, nl2);
            dshVer = vv.trim();
        }
        boolean dshReady = dshVersionScore(dshVer) >= dshVersionScore("0.1.0-rc.8");
        boolean dshOld = !dshVer.isEmpty() && !"NONE".equals(dshVer)
                && dshVersionScore(dshVer) < dshVersionScore("0.1.0-rc.8");
        boolean r2 = bits[0];
        boolean r4 = bits[1];
        // r5 由 dshReady 决定（不再用 bits[2]——那是 C_OK=dsh 存在位）
        boolean r5;
        boolean r6 = bits[3];
        r5 = dshReady; // ⑤ dsh 已就绪（完整版本判定）
        updatableCache[STEP_HARNESS] = dshOld; // ⑤ 装了旧版 → 可更新
        boolean r3 = new File(proot.getRootfsDir(), "usr/local/bin/node").exists()
                && new File(proot.getRootfsDir(), "usr/local/bin/npm").exists();
        synchronized (stepCache) {
            stepCache[STEP_ROOTFS] = r1;
            stepCache[STEP_TOOLS] = r2;
            stepCache[STEP_NODE] = r3;
            stepCache[STEP_PNPM] = r4;
            stepCache[STEP_HARNESS] = r5;
            stepCache[STEP_GUARD] = r6;
            stepCache[0] = false;
            // 可更新标记：⑥ 守卫版本旧（⑤ dsh 旧版已在上面处理）
            updatableCache[STEP_GUARD] = upd[1];
            stepCacheTs = System.currentTimeMillis();
            return stepCache.clone();
        }
    }

    /** 第 4 步完成判定：已装 + node-pty 就绪。RC6 用一次 proot 进程查完（省一次子进程，降低卡顿） */
    private boolean isHarnessReady() {
        if (useRc6()) {
            try {
                String r = proot.execAndRead(
                        "command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE");
                if (r == null || r.startsWith("ERROR") || r.contains("NONE")) return false;
                String v = r.trim();
                // 最低要求：0.1.0-rc.8（含）以上。完整版本比较（只看 rc 号会把
                // 0.1.1-rc.2 误判旧版 → 永远重装循环）
                return dshVersionScore(v) >= dshVersionScore("0.1.0-rc.8");
            } catch (Exception e) {
                return false;
            }
        }
        return proot.isHarnessInstalled(getWorkdir()) && hasPtyNode();
    }

    /** 检查 node-pty 编译产物（pty.node）是否就绪（RC6 模式查 npm 包，源码模式查项目目录） */
    private boolean hasPtyNode() {
        try {
            if (useRc6()) {
                String r = proot.execAndRead(
                        "find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty/build/Release/pty.node' 2>/dev/null | head -1; " +
                        "find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty/prebuilds/linux-arm64/pty.node' 2>/dev/null | head -1");
                // execAndRead 出错返回 "ERROR: ..." 前缀，须排除（不能把执行失败当成有 pty.node）
                return r != null && !r.startsWith("ERROR") && !r.trim().isEmpty();
            }
            File wdDir = new File(proot.getRootfsDir(), "root/" + getWorkdir());
            File pnpmDir = new File(wdDir, "node_modules/.pnpm");
            if (!pnpmDir.isDirectory()) return false;
            File[] ptyDirs = pnpmDir.listFiles((d, n) -> n.startsWith("node-pty@"));
            if (ptyDirs == null) return false;
            for (File d : ptyDirs) {
                File base = new File(d, "node_modules/node-pty");
                if (new File(base, "build/Release/pty.node").isFile()) return true;
                if (new File(base, "prebuilds/linux-arm64/pty.node").isFile()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 检查文件是否为有效的 xz 压缩包（魔数 FD 37 7A 58 5A） */
    public boolean validXz(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 0xfd && in.read() == 0x37 && in.read() == 0x7a
                    && in.read() == 0x58 && in.read() == 0x5a;
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查文件是否为有效的 gzip 包（校验魔数 0x1f 0x8b） */
    private boolean validGzip(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (Exception e) {
            return false;
        }
    }

    /** rootfs 内基础工具是否齐备 */
    private boolean toolsInstalled() {
        try {
            String out = proot.execAndRead(
                    "command -v curl >/dev/null && command -v git >/dev/null && " +
                    "command -v python3 >/dev/null && command -v make >/dev/null && " +
                    "command -v gcc >/dev/null && command -v xz >/dev/null && echo TOOLS_OK || echo TOOLS_MISSING");
            return out != null && out.contains("TOOLS_OK");
        } catch (Throwable e) {
            return false;
        }
    }

    private void runInstallStep(int step) throws Exception {
        currentStep = step;
        try {
            switch (step) {
                case STEP_ROOTFS: installRootfs(); break;
                case STEP_TOOLS: installTools(); break;
                case STEP_NODE: installNode(); break;
                case STEP_PNPM: installPnpmExtras(); break;
                case STEP_HARNESS: installHarness(); break;
                case STEP_GUARD: installGuard(); break;
                default: throw new Exception("未知步骤：" + step);
            }
        } finally {
            currentStep = 0;
        }
    }

    private void requireRootfs() throws Exception {
        if (!proot.isInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ① Linux 环境（rootfs）");
        }
    }

    private void requireTools() throws Exception {
        if (!toolsInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ② 基础工具（apt）");
        }
    }

    /** ① rootfs：测速下载 → 解压 → 冒烟测试（全局进度 0~59） */
    /** ④ Node 附加工具（pnpm + node-gyp）安装：独立可重跑步骤 */
    private void installPnpmExtras() throws Exception {
        requireRootfs();
        requireTools();
        setProgress("安装 Node 附加工具（pnpm / node-gyp）", 90);
        runStep("安装 pnpm", 91,
                "(pnpm -v >/dev/null 2>&1 && echo 'pnpm 已就绪，跳过安装') || " +
                "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com 2>&1 | tail -3");
        runStep("安装 node-gyp（node-pty 编译必需）", 95,
                "(node-gyp --version >/dev/null 2>&1 && echo 'node-gyp 已就绪') || " +
                "npm install -g node-gyp --registry=https://registry.npmmirror.com 2>&1 | tail -3");
        setProgress("Node 附加工具就绪", 100);
    }

    /** ④ pnpm / node-gyp 是否就绪 */
    private boolean pnpmExtrasReady() {
        try {
            String r = proot.execAndRead(
                    "command -v pnpm >/dev/null 2>&1 && command -v node-gyp >/dev/null 2>&1 && echo OK || echo NO");
            return r != null && !r.startsWith("ERROR") && r.contains("OK");
        } catch (Exception e) {
            return false;
        }
    }

    /** ⑥ 安全与补丁：守卫包装器 + bash 守卫补丁 + 运行环境补丁 + 看门狗文件（全幂等） */
    private void installGuard() throws Exception {
        requireRootfs();
        setProgress("安装安全守卫与补丁", 91);
        // 内置插件资产版本自愈：资产变更时删 marker 强制重注入（老用户拿到新 UI/引导）。
        // 注意：删 marker 与写版本分离（版本在末尾 runStep 写）——中途失败则版本未写，
        // 下次启动版本不一致仍会重跑⑥，自愈闭环不中断。
        refreshBuiltinAssetMarkers();
        ensureDangerGuard();   // PATH 包装器（rm/adb 等 15 命令）
        // 守卫开关标记同步：confirm_shell=true → 写标记（adb-shell 设备命令弹确认）
        try {
            boolean confirmOn = prefs.getBoolean("confirm_shell", true);
            proot.execAndRead(confirmOn
                    ? "mkdir -p /root/.dsh && touch /root/.dsh/confirm-shell-enabled && echo ok"
                    : "rm -f /root/.dsh/confirm-shell-enabled && echo ok");
        } catch (Throwable ignored) {
        }
        ensureBashGuardPatch(); // bash 工具 lib 强制加载 dsh-guard
        try {
            proot.ensureRuntimeFiles(); // 运行环境文件
        } catch (Throwable ignored) {
        }
        ensureWatchdogFiles();  // 看门狗 + 重启命令（最新端口）
        try {
            ensureWebUiPolyfill(); // WebView 老版本 AbortSignal.any/timeout polyfill（幂等）
        } catch (Throwable ignored) {
        }
        try {
            ensureWebUiOriginPatch(); // 外部浏览器 /api 403 修复（Chrome 150+ Origin 省略端口）
        } catch (Throwable ignored) {
        }
        try {
            ensureFsWritePatch(); // write 工具悬空链接（重装 dsh 后补丁会丢，⑥ 里补回）
        } catch (Throwable ignored) {
        }
        // ===== 原生内置移动端 UI 适配（免第三方插件） =====
        // 把 dsh-client-ui-mobile-adapt 的 client 产物直接注入 web-app 前端，
        // 手机端单栏/抽屉/汉堡/全屏设置开箱即用。幂等，失败不阻塞安装。
        try {
            // 布局迁移：官方版改 lib/ 子目录布局。旧 rootfs（根目录布局）marker 存在
            // 会跳过重注入 → 检测 lib/client.js 不存在（旧布局/缺失）时删 marker 强制重注入；
            // 新布局（含离线预置）存在则保留 marker，维持「解压即用」零注入。
            java.io.File mobileNew = new java.io.File(proot.getRootfsDir(),
                    "root/dsha-mobile-adapt/lib/client.js");
            if (!mobileNew.isFile()) {
                java.io.File mobileMarker = new java.io.File(proot.getRootfsDir(),
                        "root/dsha-mobile-adapt-installed");
                if (mobileMarker.exists()) mobileMarker.delete();
                android.util.Log.i("DSHA", "mobile-adapt 布局升级：删 marker 强制重注入官方版");
            }
            ensureNativeMobileAdapt();
        } catch (Throwable ignored) {
        }
        // 设备 Shell 引导插件（rc.8 bundle 模式）：让 agent 系统提示里知道可用 ADB
        try {
            ensureDeviceShellGuide();
        } catch (Throwable ignored) {
        }
        // 任务完成通知插件：turn/end → 3090 桥发 App 通知（替代轮询）
        try {
            ensureTaskNotifier();
        } catch (Throwable ignored) {
        }
        // 极简模式设备引导已并入 device-shell-guide 插件（home patch 覆盖官方极简 bash 描述）
        // 内置插件快照：只录实体目录（排除符号链接=用户安装插件），安装完成时最干净基线
        // 快照缺失时才生成（后续沿用；想重扫可删 /root/dsha-builtin.txt）
        runStep("生成内置插件快照", 98,
                "if [ ! -f /root/dsha-builtin.txt ]; then " +
                "find /root/.dsh/profiles/web/node_modules/ -maxdepth 1 \\( -type d -o -type f \\) ! -type l 2>/dev/null " +
                "| sed 's|.*/||' | grep -v '^\\.' | grep -v '\\.disabled$' > /root/dsha-builtin.txt; " +
                "echo '内置快照：'$(wc -l < /root/dsha-builtin.txt 2>/dev/null)' 项'; " +
                "else echo '内置插件快照已存在，沿用'; fi");
        // 写入步骤⑥版本标记（启动时对比，不符自动重跑⑥）
        runStep("写入⑥版本标记", 99,
                "printf '%s' '" + STEP6_VERSION + "' > /root/.dsh/step6.version; " +
                "printf '%s' '" + BUILTIN_ASSET_VERSION + "' > /root/.dsh/builtin-assets.version; " +
                "echo '⑥版本: " + STEP6_VERSION + " 资产版本: " + BUILTIN_ASSET_VERSION + "'");
        setProgress("安全守卫与补丁就绪", 100);
    }

    /** ⑥ 守卫是否就绪（包装器 + dsh-guard.sh） */
    private boolean guardReady() {
        try {
            String r = proot.execAndRead(
                    "test -f /root/dsh-guard.sh && test -d /root/dsh-bin && test -f /root/dsh-bin/.version && echo OK || echo NO");
            return r != null && !r.startsWith("ERROR") && r.contains("OK");
        } catch (Exception e) {
            return false;
        }
    }

    private void installRootfs() throws Exception {
        setProgress("准备 proot 运行时", 2);
        proot.ensureRuntimeFiles();

        File tarball = new File(proot.getRootfsDir().getParentFile(), "rootfs.tar.gz");
        File doneMark = new File(tarball.getAbsolutePath() + ".done");

        // 已有完整下载则跳过；否则断点续传（downloadRootfs 内部 Range 续传）
        boolean haveComplete = doneMark.exists() && tarball.exists()
                && tarball.length() > 15L * 1024 * 1024;
        if (!haveComplete) {
            downloadWithPick(TASK_ROOTFS, ProotBootstrap.ROOTFS_URLS,
                    "下载 Ubuntu rootfs（~30MB）", tarball, 4, 2);
        }

        setProgress("rootfs 下载完成，正在解压（约 5~15 分钟，进度会暂时停住，请勿关闭 App）", 57);
        proot.extractRootfs(tarball);
        proot.setupResolvConf();

        // proot 冒烟测试：确认能进入 rootfs 执行命令
        String smoke = proot.smokeTest();
        if (smoke == null || !smoke.contains("SMOKE_OK")) {
            throw new Exception("proot 进入 rootfs 失败（bash 无法执行）：\n"
                    + (smoke == null ? "" : smoke)
                    + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }

        proot.markInstalled();
        // 解压成功后清理 tarball 与标记，释放空间
        //noinspection ResultOfMethodCallIgnored
        tarball.delete();
        //noinspection ResultOfMethodCallIgnored
        doneMark.delete();
        setProgress("环境安装完成", 59);
    }

    /** ② 基础工具：apt 换国内源 + 安装 curl/git/python3/make/xz（全局进度 60~69） */
    private void installTools() throws Exception {
        requireRootfs();
        // 先把 apt 源换成国内镜像（直连 ports.ubuntu.com 在国内常被重置）
        setProgress("替换 apt 国内源", 60);
        proot.execAndRead(
                "sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; " +
                "s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' " +
                "/etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true");
        try {
            runStep("更新 apt 源", 62, "apt-get update -y");
            runStep("安装基础工具（curl/git/python3/make/gcc/xz）", 65,
                    "apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils");
        } catch (Throwable e) {
            throw new Exception(e.getMessage() + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }
        // ca-certificates 的 postinst 在 proot 下必失败，装完基础工具后单独处理，
        // 强制移除避免 dpkg broken 状态阻塞后续 apt 操作
        try {
            proot.execAndRead(
                "apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true; " +
                "dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true; " +
                "dpkg --configure -a 2>/dev/null || true");
        } catch (Throwable ignored) {
        }
        setProgress("基础工具就绪", 69);
    }

    /** ③ Node.js：测速下载到 rootfs /tmp → 解压（全局进度 70~89） */
    private void installNode() throws Exception {
        requireRootfs();
        requireTools();
        File nodePkg = new File(proot.getRootfsDir(), "tmp/node.tar.xz");
        // 完整性检查：大小 ≥40MB 且 xz 魔数正确（防下载中断的截断文件混过检查导致解压 EOF）
        boolean haveGood = nodePkg.exists() && nodePkg.length() >= 40L * 1024 * 1024
                && validXz(nodePkg);
        if (haveGood) {
            setProgress("Node.js 安装包已存在，跳过下载", 71);
        } else {
            if (nodePkg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                nodePkg.delete(); // 清掉截断/损坏的旧包
            }
            downloadWithPick(TASK_NODE, ProotBootstrap.NODE_URLS, "下载 Node.js", nodePkg, 71, 6);
        }
        // 解压失败自动重下一次（npmmirror）再试一次；仍失败则抛错中断
        // 注意：不使用 `cd /tmp` —— 部分云手机/翻译层（如卓易通）对 chdir(/tmp)
        // 返回 ENOSYS("Function not implemented")，必须全程绝对路径。
        runStep("安装 Node.js", 88,
                "mkdir -p /tmp /usr/local 2>/dev/null; "
                        + "(tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 || "
                        + "(echo '安装包损坏，自动重新下载…'; rm -f /tmp/node.tar.xz; "
                        + "curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz && "
                        + "tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1))");
        setProgress("Node.js 就绪", 89);
    }

    /** ④ deepseek-harness：预构建包 或 直连源码构建（全局进度 90~100） */
    private void installHarness() throws Exception {
        requireRootfs();
        // 预构建包源已暂停（catbox 匿名站包体被污染/损坏，含 WSL 脚本非官方产物）。
        // 当前唯一可靠路径 = 直连 GitHub 源码构建（多镜像 fallback + 工具链齐全，已验证稳定）。
        installHarnessFromSource();
        // ===== 预构建包线路（暂停，代码保留供恢复源后使用） =====
        /*
        String apiKey = effectiveApiKey();
        String[] urls = ProotBootstrap.HARNESS_URLS;
        setProgress("测速中…（含直连选项）", 91);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress("请选择安装方式（预构建包 / 直连源码）", 91);
        String[] ordered = waitUserPick(TASK_HARNESS, urls, lat);
        if (ordered[0].startsWith("git://")) {
            installHarnessFromSource();
            return;
        }
        // ... 预构建包解压逻辑见历史版本 ...
        */
    }

    /** 是否使用 RC6 版本。已改为“始终最新 RC”（@deepseek-ai/dsh@rc），无开关。 */
    public boolean useRc6() {
        return true;
    }

    /** 字节格式化：134833152 -> "134.8MB"，1.33GB -> "1.33GB" */
    public static String fmtBytes(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2fGB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
        if (bytes >= 1024)
            return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        return bytes + "B";
    }

    /** 速率文案：3.2MB/s、850KB/s；未知/为零时给 "—"（别让界面显示 0.0MB/s 让人以为卡死） */
    public static String fmtRate(double bytesPerSec) {
        if (bytesPerSec <= 1) return "—";
        return fmtBytes((long) bytesPerSec) + "/s";
    }

    /** 剩余时间文案：45 秒 / 2 分 10 秒 / 1 小时 5 分（负数或算不出给 "—"） */
    public static String fmtEta(long seconds) {
        if (seconds < 0) return "—";
        if (seconds < 60) return seconds + " 秒";
        long m = seconds / 60;
        long s = seconds % 60;
        if (m < 60) return s == 0 ? m + " 分" : m + " 分 " + s + " 秒";
        long h = m / 60;
        m %= 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /** 进度采样器：把「已完成字节」序列换算成平滑速率与剩余时间。
     *  指数平滑（EMA）避免数字每次刷新都乱跳；解压页/下载共用一套算法。 */
    public static final class RateMeter {
        private long lastBytes = -1;
        private long lastAt = 0;
        private double rate = 0; // 字节/秒

        /** 喂入最新的累计字节数，返回当前平滑速率（字节/秒，未知为 0） */
        public synchronized double feed(long done) {
            long now = System.currentTimeMillis();
            if (lastBytes < 0) {
                lastBytes = done;
                lastAt = now;
                return 0;
            }
            long dt = now - lastAt;
            if (dt < 200) return rate; // 采样太密：噪声大，沿用上次
            double inst = (done - lastBytes) * 1000.0 / dt;
            if (inst < 0) inst = 0; // 断点续传等导致回退：忽略
            rate = rate <= 0 ? inst : rate * 0.7 + inst * 0.3;
            lastBytes = done;
            lastAt = now;
            return rate;
        }

        /** 当前平滑速率（字节/秒） */
        public synchronized double rate() {
            return rate;
        }

        /** 剩余秒数（算不出返回 -1） */
        public synchronized long eta(long done, long total) {
            if (total <= 0 || done >= total || rate <= 1) return -1;
            return (long) ((total - done) / rate);
        }
    }

    private void installHarnessRc6() throws Exception {
        requireRootfs();
        requireTools();
        // 先写入依赖自愈脚本（安装末尾的"校验 dsh 子包依赖完整性"步骤要用；
        // 若安装中途失败，启动时的 maybeHealDshDeps 也会重写）
        try {
            String heal = readAsset("dsh-deps-heal.sh");
            if (heal != null && !heal.isEmpty()) {
                java.io.File hf = new java.io.File(proot.getRootfsDir(), "root/dsha-deps-heal.sh");
                hf.getParentFile().mkdirs();
                java.nio.file.Files.write(hf.toPath(), heal.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        setProgress("安装 deepseek-harness 最新 RC（npm 全局）", 91);
        runStep("RC 安装环境准备", 92,
                // 先写 registry 再追加 allow-scripts：顺序反了会把前者 printf 覆盖掉
                "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc; " +
                "npm config set allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs --location=user 2>/dev/null; " +
                "echo '--- /root/.npmrc ---'; cat /root/.npmrc");
        runStep("安装 @deepseek-ai/dsh 最新 RC", 95,
                // 优先 @next（官方最新 rc）；npmmirror 镜像同步滞后时回退 pin rc.8，再回退官方源
                "(npm install -g @deepseek-ai/dsh@next --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@0.1.1-rc.2 --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@rc --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@next --force --registry=https://registry.npmjs.org 2>&1) | tail -25; " +
                "echo \">> npm 退出码: ${PIPESTATUS[0]}\"; " +
                // 强制 RC8/npm 路线：失败直接退出（不 fallback clone——手机 clone GitHub
                // 几乎必失败且会留下空源码目录，用户看到"源码是空的"）
                "if [ \"${PIPESTATUS[0]}\" != 0 ]; then echo 'RC 安装失败：npm 三个源都不通，请检查网络后重试'; exit 1; fi");
        // 预下载 Node headers（node-gyp 编译 node-pty 必需；否则 node-gyp 默认访问
        // nodejs.org 下载，国内手机网络不通 → undici 报错 → 退出码 1）
        runStep("准备 Node headers（node-gyp 编译依赖）", 96,
                "NGV=$(node -v | sed 's/^v//'); " +
                "if [ ! -f /root/.cache/node-gyp/$NGV/include/node/node.h ]; then " +
                "mkdir -p /root/.cache/node-gyp/$NGV; cd /root/.cache/node-gyp/$NGV; " +
                "(curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL --retry 3 https://mirrors.huaweicloud.com/nodejs/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL --retry 3 https://nodejs.org/dist/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz) && " +
                "tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && echo 'Node headers 已准备' || " +
                "{ echo 'Node headers 下载失败（node-gyp 编译将无法进行）'; exit 1; }; " +
                "else echo 'Node headers 已缓存'; fi; " +
                "export npm_config_nodedir=/root/.cache/node-gyp/$NGV; " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node");
        runStep("编译 node-pty 原生模块", 98,
                "node-gyp --version >/dev/null 2>&1 || npm install -g node-gyp --registry=https://registry.npmmirror.com 2>&1 | tail -2; " +
                "NGV=$(node -v | sed 's/^v//'); " +
                "export npm_config_nodedir=/root/.cache/node-gyp/$NGV; " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node; " +
                "npty_dir=$(find /usr/local/lib/node_modules -maxdepth 6 -path '*/node-pty' -type d 2>/dev/null | head -1); " +
                "if [ -z \"$npty_dir\" ]; then " +
                "echo '未找到 node-pty（说明 dsh 包没装上）'; " +
                "echo '--- /usr/local/lib/node_modules ---'; ls /usr/local/lib/node_modules 2>&1; " +
                "echo '--- @deepseek-ai 目录 ---'; ls /usr/local/lib/node_modules/@deepseek-ai/ 2>&1; " +
                "echo '--- dsh 命令 ---'; command -v dsh || echo 'dsh 不存在'; " +
                "exit 1; fi; " +
                "if [ ! -f \"$npty_dir/build/Release/pty.node\" ]; then " +
                "(cd \"$npty_dir\" && node-gyp rebuild > /tmp/rc6-gyp.log 2>&1) || " +
                "{ echo 'node-pty 编译失败：'; tail -10 /tmp/rc6-gyp.log 2>&1; exit 1; }; fi; " +
                "ls \"$npty_dir/build/Release/pty.node\" >/dev/null 2>&1 && echo 'pty.node 已就绪' && command -v dsh && echo 'RC 安装完成'");
        // 依赖完整性自愈：npmmirror 元数据不一致可能导致 @deepseek-ai/* 子包
        // 声明了但没装上（Cannot find module）——安装时强制校验补装一次
        runStep("校验 dsh 子包依赖完整性", 99,
                "if [ -f /root/dsha-deps-heal.sh ]; then bash /root/dsha-deps-heal.sh; rm -f /root/dsha-deps-heal.sh; " +
                "else echo 'dsha-deps-heal.sh 未就位，跳过'; fi; tail -3 /root/dsh-deps-heal.log 2>/dev/null || true");
        // 立即打老 WebView 兼容补丁：单独重装⑤（dsh 更新）时不会走⑥，这里保证 RC6 路径也生效
        try {
            ensureWebUiPolyfill();
        } catch (Throwable ignored) {
        }
        try {
            ensureWebUiOriginPatch();
        } catch (Throwable ignored) {
        }
        setProgress("RC 安装完成", 100);
    }

    /** 直连 GitHub 源码构建（clone 多通道 fallback + npmmirror 依赖/headers 源） */
    private void installHarnessFromSource() throws Exception {
        if (useRc6()) {
            installHarnessRc6();
            return;
        }
        String wd = getWorkdir();

        // 已装环境不会重跑 setupResolvConf：这里强制重写 DNS（223.5.5.5 等国内源），
        // 否则 git clone / curl 全域名解析失败
        requireRootfs();
        proot.setupResolvConf();
        if (!toolsInstalled()) {
            setProgress("自动补装基础工具（gcc/g++ 等）", 91);
            installTools();
        }

        setProgress("启用 pnpm", 92);
        // 不依赖 corepack（新版 Node 常缺失）：直接用 npm 安装 pnpm@11.7.0（与项目 packageManager 匹配）
        // 已安装则跳过（否则 npm 报 EEXIST 导致重装失败）
        runStep("启用 pnpm", 92,
                "(pnpm -v >/dev/null 2>&1 && echo 'pnpm 已就绪，跳过安装') || "
                        + "if command -v npm >/dev/null 2>&1; then "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "else "
                        + "echo 'npm 缺失，自动补装 Node.js'; "
                        + "[ -s /tmp/node.tar.xz ] || curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz; "
                        + "mkdir -p /tmp /usr/local 2>/dev/null; "
                        + "tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 && "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "fi");

        setProgress("获取 deepseek-harness 源码", 93);
        runStep("获取 deepseek-harness 源码", 93,
                "cd /root && " +
                "if [ -d " + wd + " ] && [ -f " + wd + "/package.json ]; then " +
                "echo '源码已存在，跳过克隆（尝试增量更新）'; " +
                "(cd " + wd + " && git pull --ff-only 2>/dev/null || true); " +
                "else rm -rf " + wd + " && ( " +
                "git clone --depth 1 " + gitHubProxy("https://github.com/deepseek-ai/deepseek-harness.git") + " " + wd + " || " +
                "git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gitclone.com/github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://ghfast.top/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gh-proxy.com/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://ghproxy.net/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gitcode.com/gh_mirrors/de/deepseek-harness.git " + wd + " ) || " +
                "(echo 'git 克隆失败，改用源码包下载…'; rm -rf " + wd + " && " +
                "(curl -kfsSL --retry 3 -m 300 " + gitHubProxy("https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master") + " -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghfast.top/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://gh-proxy.com/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghproxy.net/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz) && " +
                "tar -xzf dsh-src.tar.gz && (mv deepseek-harness-master 2>/dev/null || mv deepseek-harness-main " + wd + " ) && rm -f dsh-src.tar.gz); fi");

        // 应用 WebUI 移动端补丁（移除“打开/收起侧边栏”按钮）；失败不阻塞安装
        try {
            String patchStr = readAsset("webui-sidebar.patch");
            if (!patchStr.isEmpty()) {
                java.io.File patchFile = new java.io.File(proot.getRootfsDir(), "root/dsha-webui.patch");
                patchFile.getParentFile().mkdirs();
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(patchFile)) {
                    fo.write(patchStr.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 WebUI 移动端补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-webui.patch 2>/dev/null && " +
                        "git apply /root/dsha-webui.patch && echo '补丁已应用（已移除侧边栏开关按钮）') || " +
                        "echo '补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // 应用 bash 安全守卫补丁（bash 工具每次执行前 source dsh-guard.sh，防环境白名单绕过）；失败不阻塞
        try {
            String bgPatch = readAsset("bash-guard.patch");
            if (!bgPatch.isEmpty()) {
                java.io.File bgFile = new java.io.File(proot.getRootfsDir(), "root/dsha-bash-guard.patch");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(bgFile)) {
                    fo.write(bgPatch.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 bash 守卫补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-bash-guard.patch 2>/dev/null && " +
                        "git apply /root/dsha-bash-guard.patch && echo 'bash 守卫补丁已应用') || " +
                        "echo 'bash 守卫补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // WebUI 老浏览器兼容补丁：AbortSignal.any/timeout polyfill（Chrome 118 以下会报 "is not a function"）
        try {
            String poly = readAsset("webui-polyfill.sh");
            if (!poly.isEmpty()) {
                java.io.File polyFile = new java.io.File(proot.getRootfsDir(), "root/dsha-webui-polyfill.sh");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(polyFile)) {
                    fo.write(poly.getBytes(StandardCharsets.UTF_8));
                }
                runStep("WebUI 浏览器兼容补丁", 93, "bash /root/dsha-webui-polyfill.sh; rm -f /root/dsha-webui-polyfill.sh");
            }
        } catch (Exception ignored) {
        }

        // 安装危险命令确认包装器（rootfs 内 rm/dd 等先弹确认，防止 agent/终端误删）
        // 失败必须中断：安全功能没装好，安装不算完成
        {
            String inst = readAsset("rootfs-confirm-install.sh");
            if (!inst.isEmpty()) {
                java.io.File instFile = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(instFile)) {
                    fo.write(inst.getBytes(StandardCharsets.UTF_8));
                }
                runStep("安装危险命令确认包装器", 93,
                        "bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
            }
        }

        // 准备 Node headers（已缓存则跳过；node-gyp 现场下载会连 nodejs.org，国内被墙）
        runStep("准备 Node headers", 94,
                "if [ ! -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then " +
                "mkdir -p /root/.cache/node-gyp/24.19.0 && cd /root/.cache/node-gyp/24.19.0 && " +
                "(curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz) && " +
                "tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && touch .install-stamp; " +
                "else echo 'Node headers 已缓存，跳过下载'; fi");

        // 关键：pnpm 10/11 默认忽略依赖构建脚本（node-pty 的 node-gyp 编译会被跳过），
        // 必须把 node-pty 加入 onlyBuiltDependencies 白名单才会执行
        runStep("允许原生模块构建（node-pty）", 94,
                "cd /root/" + wd + " && " +
                "(grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || " +
                "printf '\\nonlyBuiltDependencies:\\n  - node-pty\\n' >> pnpm-workspace.yaml) && " +
                // Ubuntu 24.04 无 /usr/bin/python（只有 python3），部分构建工具死认 python 命令
                "(command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python || true)");

        // 先把 pnpm 的硬链接导入关掉：proot 下 link() 只是 symlink 模拟，
        // 留下的悬空链会让后续安装报各种莫名的 ENOENT
        runAssetScript("pnpm-env-fix.sh", "dsha-pnpm-env-fix.sh", 60_000);
        setProgress("安装依赖 pnpm install（npmmirror 源）", 95);
        try {
            // 注意：npm 11 的 `npm config set disturl` 会报 "not a valid npm option"，
            // 所以直接写 .npmrc 文件 + 环境变量（不经过 npm 配置校验）
            // pnpm 会持续打印进度，五分钟一声不吭基本就是网络挂了
            runStep("安装依赖 pnpm install", 95,
                    "cd /root/" + wd + " && " +
                    // 三项一次写全。以前只写 registry 且用 > 覆盖，会把
                    // pnpm-env-fix.sh 刚配好的 package-import-method 冲掉 ——
                    // 于是 pnpm 又回去用硬链接，proot 下就是 .l2s 悬空链那套老问题
                    "printf 'registry=https://registry.npmmirror.com\\n"
                    + "package-import-method=copy\\nside-effects-cache=false\\n'"
                    + " > /root/.npmrc && " +
                    "pnpm install",
                    // pnpm 会持续打印包名，五分钟一声不吭基本就是网络挂了
                    300_000L);
        } catch (Exception e) {
            throw new Exception(e.getMessage() + "\n\n[原生模块编译失败提示]\n"
                    + "1. Node headers 已预下载到 node-gyp 缓存（npmmirror 源），不依赖 nodejs.org\n"
                    + "2. 工具链已自动补装（gcc/g++/make/python3），可重试本步骤\n"
                    + "3. 若仍失败，可能是设备内存不足，可改选「预构建包」方式");
        }

        // 直接调用 node-gyp 编译 node-pty（绕开 npm/pnpm 的构建脚本管理）：
        // 有预编译产物（prebuilds/linux-arm64/pty.node）则直接跳过编译；
        // 编译失败时输出完整诊断日志（便于定位根因）
        runStep("编译 node-pty（node-gyp）", 96,
                "cd /root/" + wd + " && " +
                "NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1) && " +
                "if [ -f \"$NP/prebuilds/linux-arm64/pty.node\" ]; then " +
                "echo '检测到 node-pty 预编译产物，跳过 node-gyp 编译'; " +
                "else cd \"$NP\" && " +
                "GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js && " +
                "if [ ! -f \"$GYP\" ]; then GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1); fi && " +
                "echo \"node-gyp 路径: $GYP\" && " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node && " +
                "(node \"$GYP\" rebuild > /tmp/node-gyp.log 2>&1 || " +
                "{ echo '--- node-gyp 编译失败，诊断信息 ---'; " +
                "grep -E 'gyp ERR!|Error|error:|fatal' /tmp/node-gyp.log | head -25; exit 1; }); fi");

        // 验证 node-pty 编译产物确实生成了（否则启动 Web UI 时必炸）
        // 全局 dsh 命令：符号链接到 /usr/local/bin（终端可直接敲 dsh）
        try {
            runStep("安装 dsh 命令", 99,
                    "ln -sf /root/" + wd + "/apps/cli/lib/bin.js /usr/local/bin/dsh && " +
                    "chmod +x /usr/local/bin/dsh 2>/dev/null; echo 'dsh 命令已安装'");
        } catch (Exception ignored) {
        }

        runStep("验证 pty.node 产物", 97,
                "cd /root/" + wd + " && " +
                "P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node 2>/dev/null | head -1); " +
                "if [ -z \"$P\" ]; then P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/prebuilds/linux-arm64/pty.node 2>/dev/null | head -1); fi; " +
                "if [ -z \"$P\" ]; then " +
                "echo 'ERROR: pty.node 未生成，node-pty 目录内容：'; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/ 2>/dev/null; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/ 2>/dev/null; " +
                "exit 1; fi; " +
                "echo \"pty.node 已就绪: $P\"");

        setProgress("构建 deepseek-harness", 97);
        runStep("构建 pnpm run build", 97, "cd /root/" + wd + " && pnpm run build");

        runStep(effectiveApiKey().isEmpty() ? "写入配置（API key 留空）" : "写入 API key", 99,
                "cd /root/" + wd + " && " + envWriteCommand());
        setProgress("deepseek-harness 构建完成", 99);
    }

    // ================= 下载源：测速 + 用户自选 =================

    /** 统一下载流程：测速 → 弹窗自选源 → 下载（失败自动 fallback 其他源） */
    private void downloadWithPick(int task, String[] urls, String what, File dest,
                                  int pBase, int pDiv) throws Exception {
        setProgress(what + "：测速中…（" + urls.length + " 个源并行测速）", pBase);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress(what + "：请在弹窗中选择下载源", pBase + 1);
        String[] ordered = waitUserPick(task, urls, lat);

        boolean ok = false;
        String lastErr = "";
        for (String url : ordered) {
            final RateMeter meter = new RateMeter();
            try {
                proot.downloadRootfs(url, dest, (down, total) -> {
                    double rate = meter.feed(down);
                    if (total <= 0) {
                        // 源没给 Content-Length：至少把已下大小与速率显示出来，别让用户以为卡死
                        setProgress(what + "… " + fmtBytes(down) + " · " + fmtRate(rate)
                                + "（源未提供大小 · " + hostOf(url) + "）", Math.min(99, pBase + 1));
                    } else {
                        int pct = (int) (down * 100 / total);
                        long eta = meter.eta(down, total);
                        setProgress(what + " " + fmtBytes(down) + "/" + fmtBytes(total)
                                        + "（" + pct + "%）· " + fmtRate(rate)
                                        + (eta >= 0 ? " · 剩余 " + fmtEta(eta) : "")
                                        + " · 源 " + hostOf(url),
                                Math.min(99, pBase + 1 + pct / pDiv));
                    }
                });
                ok = true;
                break;
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        if (!ok) {
            throw new Exception(what + " 下载失败: " + lastErr
                    + "\n\n可尝试：切换网络 / 开启代理");
        }
    }

    /** IO 线程阻塞等待用户在 UI 弹窗中选择下载源（2 分钟超时后自动选最快） */
    private String[] waitUserPick(int task, String[] urls, long[] lat) throws Exception {
        pendingTask = task;
        pendingUrls = urls;
        pendingLat = lat;
        sourceChoice = -1;
        awaitingSource = true;
        setState("请选择下载源（测速完成）", percent, "", "", true);
        synchronized (sourceLock) {
            long deadline = System.currentTimeMillis() + 120_000;
            while (awaitingSource) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                sourceLock.wait(remain);
            }
        }
        awaitingSource = false;

        // 确定首选：用户选择优先，否则自动选测速最快的
        int first = -1;
        if (sourceChoice >= 0 && sourceChoice < urls.length) {
            first = sourceChoice;
            prefs.edit().putString("src_" + task, urls[sourceChoice]).apply();
        } else {
            for (int i = 0; i < lat.length; i++) {
                if (lat[i] >= 0 && (first < 0 || lat[i] < lat[first])) first = i;
            }
            if (first < 0) first = 0;
        }
        String[] ordered = new String[urls.length];
        ordered[0] = urls[first];
        int k = 1;
        for (int i = 0; i < urls.length; i++) {
            if (i != first) ordered[k++] = urls[i];
        }
        return ordered;
    }

    /** UI 调用：用户已选择（index>=0 选中项；-1 自动选最快） */
    public void onSourceChosen(int index) {
        sourceChoice = index;
        awaitingSource = false;
        synchronized (sourceLock) {
            sourceLock.notifyAll();
        }
    }

    public boolean isAwaitingSourceChoice() { return awaitingSource; }

    /** 待选源的展示文案（名称 + 延迟；git:// 为直连源码构建选项） */
    public String[] getPendingSourceLabels() {
        if (pendingUrls == null || pendingLat == null) return new String[0];
        String[] labels = new String[pendingUrls.length];
        for (int i = 0; i < pendingUrls.length; i++) {
            String u = pendingUrls[i];
            if (u.startsWith("git://")) {
                labels[i] = "⚡ 直连 GitHub 源码构建（clone + 本地构建，无需预构建包）";
                continue;
            }
            long l = pendingLat[i];
            labels[i] = sourceLabel(u) + (l >= 0 ? "   延迟 " + l + "ms" : "   不可用 ✗");
        }
        return labels;
    }

    /** 弹窗默认选中项：上次选择 > 测速最快 */
    public int getPendingDefaultIndex() {
        String saved = pendingUrls != null
                ? prefs.getString("src_" + pendingTask, "") : "";
        for (int i = 0; pendingUrls != null && i < pendingUrls.length; i++) {
            if (pendingUrls[i].equals(saved)) return i;
        }
        int best = 0;
        // 防御：pendingLat 必须与 pendingUrls 等长（否则越界/选错）
        if (pendingLat == null || pendingUrls == null
                || pendingLat.length != pendingUrls.length) {
            return 0;
        }
        for (int i = 1; i < pendingLat.length; i++) {
            if (pendingLat[i] >= 0 && (pendingLat[best] < 0 || pendingLat[i] < pendingLat[best])) best = i;
        }
        return best;
    }

    private static String sourceLabel(String url) {
        String h = hostOf(url);
        if (h.startsWith("cdn.npmmirror")) return "npmmirror CDN（" + h + "）";
        if (h.contains("npmmirror")) return "npmmirror（" + h + "）";
        if (h.contains("tuna")) return "清华镜像（" + h + "）";
        if (h.contains("aliyun")) return "阿里云镜像（" + h + "）";
        if (h.contains("huaweicloud")) return "华为云镜像（" + h + "）";
        if (h.contains("tencent")) return "腾讯云镜像（" + h + "）";
        if (h.contains("nju.edu")) return "南京大学镜像（" + h + "）";
        if (h.contains("hit.edu")) return "哈工大镜像（" + h + "）";
        if (h.contains("bfsu")) return "北外镜像（" + h + "）";
        if (h.contains("sjtu")) return "上海交大镜像（" + h + "）";
        if (h.contains("nodejs.org")) return "Node 官方（" + h + "）";
        if (h.contains("cdimage")) return "Ubuntu 官方（" + h + "）";
        if (h.contains("catbox")) return "catbox 网盘（" + h + "）";
        return h;
    }

    /**
     * 执行单个安装步骤：输出重定向到日志文件（避免大量输出走 proot 管道导致崩溃），
     * 失败时输出日志尾部以便定位。
     */
    private void runStep(String stage, int percent, String cmd) throws Exception {
        runStep(stage, percent, cmd, 0L);
    }

    /** @param stallMs 大于 0 时启用静默看门狗：这么久一个字都不吐就判卡死。
     *                 只给会持续打印进度的命令用（pnpm/apt/npm）；tar、xz 正常
     *                 也会长时间静默，传 0 走无超时的老路，免得误杀。 */
    private void runStep(String stage, int percent, String cmd, long stallMs) throws Exception {
        setProgress(stage, percent);
        // 输出重定向到日志、失败时回吐尾部 —— 但这样一来标准输出就没内容了，
        // 静默看门狗看不到进度。所以启用看门狗时改成 tee：日志照留，同时有输出可判活。
        String fullCmd = stallMs > 0
                ? "(" + cmd + ") 2>&1 | tee /root/dsh-step.log; "
                + "test ${PIPESTATUS[0]} -eq 0 || { echo '--- 日志尾部 ---'; "
                + "tail -100 /root/dsh-step.log; exit 1; }"
                : "(" + cmd + ") >/root/dsh-step.log 2>&1"
                + " || { echo '--- 日志尾部 ---'; tail -100 /root/dsh-step.log; exit 1; }";
        if (stallMs > 0) {
            proot.execCheckedStall(fullCmd, stallMs);
        } else {
            proot.execChecked(fullCmd);
        }
    }

    // ================= 脚本与命令 =================
    /** 注入并执行 assets 里的一次性 bash 脚本（跑完即删），返回合并输出；出错静默返回 null。
     *  这些补丁/自愈脚本的调用模式完全一致，集中在这里，省掉六份复制粘贴。 */
    private String runAssetScript(String assetName, String remoteName, long timeoutMs) {
        try {
            String script = readAsset(assetName);
            if (script == null || script.isEmpty()) return null;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/" + remoteName);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            return proot.execAndRead(
                    "bash /root/" + remoteName + "; rm -f /root/" + remoteName, timeoutMs);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "脚本 " + assetName + " 执行失败（不影响主流程）: " + e);
            return null;
        }
    }

    public String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 二进制读取 assets（如 whl 等）；失败返回 null */
    private byte[] readAssetBytes(String name) {
        try (java.io.InputStream in = appContext.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** .env 写入命令。没填 key 就只写一行注释——写 DEEPSEEK_API_KEY= 空值的话，
     *  dsh 会认为「配了一个无效 key」而直接报错；变量完全不存在它才会回退到
     *  自己的设置（WebUI 里可以配官方或第三方服务商）。 */
    private String envWriteCommand() {
        String k = effectiveApiKey();
        if (k.isEmpty()) {
            return "printf '%s\\n' "
                    + "'# DEEPSEEK_API_KEY 未配置：可在 WebUI 的设置里配置官方或第三方 API' > .env";
        }
        return "printf 'DEEPSEEK_API_KEY=%s\\n' '" + escShell(k) + "' > .env";
    }

    /** 启动命令里的 key 导出片段（含尾部 " && "）；未配置则返回空串，不导出空值 */
    private String apiKeyExportChain() {
        String k = effectiveApiKey();
        return k.isEmpty() ? "" : "export DEEPSEEK_API_KEY='" + escShell(k) + "' && ";
    }

    /** 重启脚本里的 key 导出行（含换行）；未配置则返回空串 */
    private String apiKeyExportLine() {
        String k = effectiveApiKey();
        return k.isEmpty() ? "" : "export DEEPSEEK_API_KEY='" + escShell(k) + "'\n";
    }

    private String buildInstallScript() {
        String s = readAsset("install.sh");
        return s.replace("@@API_KEY@@", effectiveApiKey())
                .replace("@@PORT@@", getPort())
                .replace("@@MODEL@@", getModel())
                .replace("@@PERMISSION_MODE@@", getPermissionMode());
    }

    public String startWebCommand() {
        // 启动前自愈：确保配置修复脚本已就位（钳制超限 timeoutMs）
        ensureConfigFixAsset();
        // 启动前自愈：老 WebView 兼容补丁（AbortSignal.any/timeout polyfill，幂等）
        try {
            ensureWebUiPolyfill();
        } catch (Throwable ignored) {
        }
        // 启动前自愈：外部浏览器 /api 403 修复（Chrome 150+ Origin 省略端口，幂等）
        try {
            ensureWebUiOriginPatch();
        } catch (Throwable ignored) {
        }
        // 启动前自愈：write 工具新建文件变悬空链接（proot l2s 与 dsh link 发布冲突，幂等）
        try {
            ensureFsWritePatch();
        } catch (Throwable ignored) {
        }
        // 启动前自愈：内置插件（mobile-adapt/device-shell-guide）注册校验，
        // 被 dsh plugin reconcile 清掉/丢失时自动补回（幂等）
        try {
            ensureBuiltinBundles();
        } catch (Throwable ignored) {
        }
        // 启动前自愈：清理无法解析的 stale bundle（防 cannot resolve profile bundle 启动崩溃）
        runAssetScript("fix-stale-bundles.sh", "dsha-fix-stale-bundles.sh", 60_000);
        // 局域网访问：deepseek-harness 官方 CLI 默认拒绝 --host 0.0.0.0，
        // 需先打 lan-bind-patch.sh 放行（失败则回落到 127.0.0.1，服务保证能起）。
        boolean lan = appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        boolean lanReady = lan && tryEnableLanBind();
        StringBuilder sb = new StringBuilder();
        sb.append("export DSH_HOME=/root/.dsh && ")
          .append(apiKeyExportChain())
          .append("export DSH_PERMISSION_MODE=").append(shellArg(getPermissionMode())).append(" && ")
          // 危险命令确认：agent 在 rootfs 内的 rm/dd 等操作需用户确认
          // PATH 包装器 + bash 工具 lib 补丁加载守卫（双保险；不设 BASH_ENV——它会污染
          // RC6 插件初始化时子 shell 的环境，导致 dsh web 加载插件失败(index 24 崩溃)）
          .append("export DSH_CONFIRM=1 && ")
          // 预创建常见插件数据目录：只建 /root/.dsh/plugins（无副作用）。
          // 注意：不再建 /root/.codex/pets —— deepseek-pet 插件把「空 pets 目录」
          // 当错误（no pet packages found）→ 整个插件树加载失败！
          // 改为：若 pets 目录存在但为空则删除（让插件走「无 pet」正常分支）。
          .append("mkdir -p /root/.dsh/plugins 2>/dev/null; "
                  + "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] "
                  + "&& rmdir /root/.codex/pets 2>/dev/null; ")
          // 局域网模式：补丁成功后绑定 0.0.0.0 并打印访问地址；失败只提示，不影响启动
          .append(lanReady ? "echo '[DSHA] 局域网访问(App桥): http://$(hostname -I 2>/dev/null | cut -d' ' -f1):3081' && "
                  : lan ? "echo '[DSHA] 局域网未开启(官方 0.0.0.0 未放行)，仅本机可访问' && " : "")
          // 先拉起看门狗（后台），再 exec WebUI（前台阻塞）——顺序不能反，否则看门狗永不启动
          .append("nohup bash /root/dsh-watchdog.sh >> /root/dsh-watchdog.log 2>&1 & ")
          // 核心命令：源码目录存在走 node，否则自动回退全局 dsh（含 exec + 日志重定向）
          .append(runCoreCommand(lanReady));
        // 写入看门狗（重启脚本 = 启动核心命令），并拉起看门狗守护
        writeWatchdogFiles(runCoreCommand(lanReady), parsePort());
        return sb.toString();
    }

    /** 依赖自愈命令片段：源码构建模式下，workspace 关键包缺失时自动重跑 pnpm install。
     *  k 先探 require.resolve（毫秒级），只有缺失才修复（--offline 用本机 store，失败回落 npmmirror）。 */
    private String depsSelfHeal() {
        if (useRc6()) return ""; // 预构建包（全局 node_modules）不走源码仓库结构
        String wd = getWorkdir();
        // 关键 workspace 包清单：任一 require.resolve 失败即视为依赖缺失，自动 pnpm install
        // 保底超时：offline 90s / 联网 180s，避免长卡拖崩启动
        return "node -e \"['@deepseek-ai/dsh-app-boot','@deepseek-ai/dsh-workspace','@deepseek-ai/dsh-session','@deepseek-ai/dsh-base'].forEach(function(m){try{require.resolve(m)}catch(e){process.exit(1)}})\" 2>/dev/null || "
                + "{ echo '[DSHA] 检测到 harness 依赖缺失，正在自动修复…'; "
                + "(timeout 90 pnpm install --offline 2>/dev/null || timeout 180 pnpm install) >> /root/deps-selfheal.log 2>&1; }; ";
    }

    /** WebUI 实际启动命令核心（看门狗重启与正常启动共用）。
     *  自动判断：源码目录存在 → cd + 依赖自愈 + node apps/cli/lib/bin.js web；
     *  否则回退全局 dsh web（预构建/目录缺失场景）。含 exec 与日志重定向。 */
    private String runCoreCommand(boolean lanReady) {
        int port = parsePort();
        // 默认端口(3080)不显式传 --port —— 彻底避免 commander 报 'argument missing'；
        // 只有用户自定义端口才追加 --port
        String opts = "";
        if (port != 3080) opts += " --port " + port;
        if (lanReady) opts += " --host 0.0.0.0" + lanTrustArgs(); // 0.0.0.0 + 信任本机所有 IP（Host 头校验放行）
        String wd = detectWorkdir();
        return "node /root/dsh-config-fix.js 2>/dev/null || true; "
                // 判定源码模式必须认启动入口 bin.js：RC6 模式下工作区目录也存在（只是没有源码），
                // 只认 -d 会把空工作区误判成源码树 → 启动失败
                + "if [ -f /root/" + wd + "/apps/cli/lib/bin.js ]; then cd /root/" + wd + "; " + depsSelfHeal()
                + "exec node --expose-internals apps/cli/lib/bin.js web" + opts + " > ~/dsh-web.log 2>&1; "
                + "else "
                + "if command -v dsh >/dev/null 2>&1 && test -f \"$(command -v dsh)\"; then "
                // RC6 模式没有源码树，但工作区目录必须存在并作为运行目录：
                // 1) 否则用户在 MT/工作区页看不到 deepseek-harness 文件夹（"下载完没有工作区"）
                // 2) agent 产物/上传文件有固定落点，备份功能才能带上
                + "mkdir -p /root/" + wd + " && cd /root/" + wd + " && "
                // 必须带 --expose-internals：dsh 的 profile-boot 会无条件创建
                // @deepseek-ai/cordis-plugin-hmr（它要靠 HMR 去监听用户 patch 文件），
                // 而那个插件第一行就检查 loader.internal，没有这个标志直接抛
                // 「--expose-internals is required for HMR service」把整个启动带崩。
                // NODE_OPTIONS 传不了这个标志（node 明确拒绝），只能作为命令行参数；
                // 而 dsh 是个 wrapper，所以先 readlink 出真正的 bin.js 再交给 node。
                + "DSH_REAL=$(readlink -f \"$(command -v dsh)\" 2>/dev/null || command -v dsh); "
                + "exec node --expose-internals \"$DSH_REAL\" web" + opts + " > ~/dsh-web.log 2>&1; "
                + "else echo '[DSHA] 全局 dsh 不可用（悬空链接或未安装），请到分步安装页重装 ⑤ deepseek-harness'; exit 1; fi; fi";
    }

    /** 生成 --trusted-host 参数：枚举本机所有非 loopback IPv4（WiFi/热点/有线），供 LAN Host 头校验放行 */
    private String lanTrustArgs() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            if (nis != null) while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                if (addrs != null) while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        sb.append(" --trusted-host ").append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 用当前配置刷新看门狗文件（启动页可见时调用，确保旧坏命令被覆盖） */
    public void ensureWatchdogFiles() {
        boolean lan = appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        // 与 startWebCommand 一致：只有补丁真的打上（lanReady=true）才用 0.0.0.0，
        // 否则看门狗重启命令带 --host 0.0.0.0 会被官方拒绝 → 重启失败
        boolean lanReady = lan && tryEnableLanBind();
        writeWatchdogFiles(runCoreCommand(lanReady), parsePort());
    }

    /**
     * 看门狗：WebUI 崩溃/卡死（失联 3 次，约 90 秒）自动重启。
     * 写入 /root/dsh-web-restart.sh + /root/dsh-cmd.txt（重启命令，含 cd+env）。
     * 看门狗重启时读 dsh-cmd.txt（永远拿到最新命令，避免旧坏命令反复触发）。
     * 幂等：watchdog 自身已在运行则直接退出。
     */
    private void writeWatchdogFiles(String restartCmd, int port) {
        try {
            java.io.File wdDir = new java.io.File(proot.getRootfsDir(), "root");
            String restart =
                    "#!/bin/bash\n" +
                    "export DSH_HOME=/root/.dsh\n" +
                    apiKeyExportLine() +
                    "export DSH_PERMISSION_MODE=" + shellArg(getPermissionMode()) + "\n" +
                    "export DSH_CONFIRM=1\n" +
                    // 工作区目录先于 cd 创建：RC6 模式没有源码树，不建目录的话
                    // 看门狗重启第一步 cd || exit 1 必失败 → 自动重启形同虚设。
                    // 不建 /root/.codex/pets（deepseek-pet 空目录会崩插件树）：
                    // 空则删，让插件走「无 pet」正常分支。
                    "mkdir -p /root/'" + getWorkdir() + "' /root/.dsh/plugins 2>/dev/null\n" +
                    "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] " +
                    "&& rmdir /root/.codex/pets 2>/dev/null || true\n" +
                    "cd /root/'" + getWorkdir() + "' || exit 1\n" +
                    restartCmd + "\n";
            String watchdog =
                    "#!/bin/bash\n" +
                    "# DSHA 看门狗：WebUI 失联 3 次（约 90 秒）自动重启\n" +
                    "# 幂等：已有看门狗实例则退出（[d] 技巧避免匹配到 pgrep 自身）\n" +
                    "if pgrep -f '[d]sh-watchdog.sh' >/dev/null 2>&1; then exit 0; fi\n" +
                    "PORT=" + port + "\n" +
                    "FAIL=0\n" +
                    "while true; do\n" +
                    "  if curl -s -m 5 -o /dev/null \"http://127.0.0.1:$PORT/\"; then\n" +
                    "    FAIL=0\n" +
                    "  else\n" +
                    "    FAIL=$((FAIL+1))\n" +
                    "    echo \"$(date '+%F %T') WebUI 失联 $FAIL 次\" >> /root/dsh-watchdog.log\n" +
                    "    if [ \"$FAIL\" -ge 3 ]; then\n" +
                    "      echo \"$(date '+%F %T') WebUI 已失联，自动重启\" >> /root/dsh-watchdog.log\n" +
                    "      pkill -f 'bin.js web' 2>/dev/null; pkill -f 'dsh web' 2>/dev/null\n" +
                    "      # 关键：等端口彻底关闭再重启（旧进程可能还在写 SQLite，\n" +
                    "      # 立即重启会双进程写同一会话 → seq 重复 → 会话损坏（官方#420）\n" +
                    "      for i in $(seq 1 20); do\n" +
                    "        curl -s -m 2 -o /dev/null http://127.0.0.1:$PORT/ 2>/dev/null && sleep 1 || break\n" +
                    "      done\n" +
                    "      sleep 1\n" +
                    "      nohup bash /root/dsh-cmd.txt >> /root/dsh-watchdog-restart.log 2>&1 &\n" +
                    "      FAIL=0\n" +
                    "    fi\n" +
                    "  fi\n" +
                    "  sleep 30\n" +
                    "done\n";
            java.io.File wdScript = new java.io.File(wdDir, "dsh-watchdog.sh");
            java.io.File rstScript = new java.io.File(wdDir, "dsh-web-restart.sh");
            java.io.File cmdFile = new java.io.File(wdDir, "dsh-cmd.txt");
            try (java.io.FileOutputStream a = new java.io.FileOutputStream(wdScript);
                 java.io.FileOutputStream b = new java.io.FileOutputStream(rstScript);
                 java.io.FileOutputStream cc = new java.io.FileOutputStream(cmdFile)) {
                a.write(watchdog.getBytes(StandardCharsets.UTF_8));
                b.write(restart.getBytes(StandardCharsets.UTF_8));
                cc.write(restart.getBytes(StandardCharsets.UTF_8)); // 与 restart.sh 同内容，watchdog 读它
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 局域网放行：把 assets 里的 lan-bind-patch.sh 写入 rootfs 执行，
     * 移除 deepseek-harness CLI 对 --host 0.0.0.0 的拒绝（底层 webServer 本就支持）。
     * 幂等；返回 true 表示本次可用 0.0.0.0。
     */
    /** 内置移动端 UI 适配：把 dsh-client-ui-mobile-adapt 的 client 产物注入 web-app 前端。
     *  原则：
     *  - 不依赖第三方插件仓库（assets 里自带完整 client.js/index.js/cordis.patch.yml）；
     *  - 注入点是「web-app 的 dist 静态目录 + cordis.patch insert」；
     *  - 幂等：已注入（/root/dsha-mobile-adapt-installed 标记）则跳过；
     *  - 失败绝不影响安装（catch 吞掉）。
     */
    private void ensureNativeMobileAdapt() {
        try {
            final String NAME = "dsh-client-ui-mobile-adapt";
            // 用户主动禁用（.disabled 存在）→ 只更新实体文件（assets 新版本写到
            // 实体目录，重新启用时拿到的是新版），不 touch marker / 不注册 bundle /
            // 不建链接（否则资产版本变化删 marker 后会把禁用的插件强制重新启用）。
            boolean userDisabled = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME + ".disabled").exists();
            java.io.File aDir = new java.io.File(proot.getRootfsDir(), "root/dsha-mobile-adapt");
            aDir.mkdirs();
            // 实体始终更新（幂等，秒级）
            writeAssetTo("mobile-adapt/lib/client.js", new java.io.File(aDir, "lib/client.js"));
            writeAssetTo("mobile-adapt/lib/index.js", new java.io.File(aDir, "lib/index.js"));
            writeAssetTo("mobile-adapt/cordis.patch.yml", new java.io.File(aDir, "cordis.patch.yml"));
            writeAssetTo("mobile-adapt/package.json", new java.io.File(aDir, "package.json"));
            if (userDisabled) {
                android.util.Log.i("DSHA", "mobile-adapt 已被用户禁用：仅更新实体，跳过注册/注入");
                return;
            }
            // 1) 注入脚本（保留幂等标记；手动 cp 已废弃——双通道加载会冲突导致
            //    "facade is missing"。加载统一走 registerMobileAdaptBundle（link: bundle）。
            //    老用户残留的手动注入文件在这里清理。
            String script =
                    "set -e; " +
                    "DST=$(find /usr/local/lib/node_modules /root -maxdepth 14 " +
                    "  \\( -path '*dsh-client-connection/lib/client' -o -path '*dsh-web-app/dist*/client' -o -path '*dsh-web-app/lib/client' \\) " +
                    "  -type d 2>/dev/null | head -1); " +
                    "if [ -z \"$DST\" ]; then " +
                    "echo 'NOT_FOUND: 未找到 web-app client 目录 '$(date) >> /root/dsha-mobile-adapt.log; " +
                    "echo '[DSHA] 未找到 web-app client 目录，跳过移动端适配'; exit 0; fi; " +
                    "if [ -n \"$DST\" ] && [ -f \"$DST/dsh-client-ui-mobile-adapt.js\" ]; then " +
                    "rm -f \"$DST/dsh-client-ui-mobile-adapt.js\" && echo '[DSHA] 已清理旧手动注入（改用 bundle 注册）'; fi; " +
                    "echo 'CLEANED: '$(date) >> /root/dsha-mobile-adapt.log; " +
                    "touch /root/dsha-mobile-adapt-installed && echo OK";
            java.io.File sF = new java.io.File(proot.getRootfsDir(), "root/dsha-mobile-inject.sh");
            java.nio.file.Files.write(sF.toPath(), script.getBytes(StandardCharsets.UTF_8));
            // 3) 执行注入（幂等标记存在则跳过）
            String r = proot.execAndRead(
                    "if [ -f /root/dsha-mobile-adapt-installed ]; then echo ALREADY; "
                    + "else bash /root/dsha-mobile-inject.sh; fi; "
                    + "rm -f /root/dsha-mobile-inject.sh");
            // 4) 【新增】profile 注册：把移动端适配作为 web profile 的 bundle 挂上
            //    （仅当 manifest 还没包含时追加；dependencies 用 file: 指向本机目录，零网络）
            if (r != null && (r.contains("OK") || r.contains("ALREADY"))) {
                registerMobileAdaptBundle();
            }
        } catch (Throwable ignored) {
        }
    }

    /** profile 注册移动端适配 bundle：手写 link: 依赖 + bundles（不跑 pnpm/dsh plugin，
     *  避免 pnpm 重装破坏 profile node_modules 导致其他插件异常）。
     *  幂等：已在 bundles 则跳过。配合启动前 fix-stale-bundles.sh 自愈兜底。 */
    private void registerMobileAdaptBundle() {
        try {
            final String NAME = "dsh-client-ui-mobile-adapt";
            final String REAL = "/root/dsha-mobile-adapt";
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            // dependencies 加 link: 指向我们注入的目录（官方认可的本地依赖语义，零网络）
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
            if (!deps.has(NAME)) deps.put(NAME, "link:" + REAL);
            // dsh.profile.bundles 追加
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) {
                prof = new org.json.JSONObject();
                if (dsh == null) dsh = new org.json.JSONObject();
                dsh.put("profile", prof);
                root.put("dsh", dsh);
            }
            org.json.JSONArray bundles = prof.optJSONArray("bundles");
            if (bundles == null) { bundles = new org.json.JSONArray(); prof.put("bundles", bundles); }
            boolean has = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (NAME.equals(bundles.optString(i, "").trim())) { has = true; break; }
            }
            if (!has) bundles.put(NAME);
            String s;
            try { s = root.toString(2); } catch (Throwable e) { s = root.toString(); }
            java.nio.file.Files.write(pf.toPath(), s.getBytes(StandardCharsets.UTF_8));
            // 关键：确保 node_modules 里有可解析的链接（link: 语义 = 建符号链接即可，
            // 不跑 pnpm 以免破坏 profile 依赖结构）
            java.io.File nmDir = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules");
            if (nmDir.getParentFile() != null) nmDir.mkdirs();
            java.io.File link = new java.io.File(nmDir, NAME);
            if (!link.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(),
                            java.nio.file.Paths.get(REAL));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 assets 内文本资源写入 rootfs 指定文件（目录自动建） */
    /** 确保「设备 Shell 引导」插件已注入 rootfs 并注册为 web profile bundle。
     *  让 agent 在系统提示里知道可用 /root/dsh-bin/adb-shell 干预手机。
     *  rc.8 全局 npm 模式适配（不走 packages/host，直接 file: bundle 挂载）。
     *  装到 node_modules/dsh-device-shell-guide（符号链接→实体目录），
     *  这样「已装插件」列表可见、togglePlugin 开关可生效（改名链接）。
     *  幂等：已注册跳过；失败不影响安装。 */
    /** 确保「任务完成通知」插件已注入 rootfs 并注册为 web profile bundle：
     *  监听 turn/end → 3090 桥发 App 通知（替代轮询会话文件，更准）。
     *  幂等：marker 存在跳过；失败不影响安装。 */
    private void ensureTaskNotifier() {
        try {
            final String NAME = "dsh-task-notifier";
            final String REAL = "/root/dsha-task-notifier";
            java.io.File realDir = new java.io.File(proot.getRootfsDir(), "root/dsha-task-notifier");
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME);
            java.io.File marker = new java.io.File(proot.getRootfsDir(), "root/dsha-task-notifier-installed");
            // 用户禁用 → 仅更新实体不注册
            if (new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME + ".disabled").exists()) {
                writeAssetTo("task-notifier/package.json", new java.io.File(realDir, "package.json"));
                writeAssetTo("task-notifier/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
                writeAssetTo("task-notifier/lib/index.js", new java.io.File(realDir, "lib/index.js"));
                return;
            }
            if (marker.exists() && nmLink.exists()) {
                // 语法自愈：JS 语法错误（漏 + 连接符等）→ 删 marker 强制重注入
                String syn = proot.execAndRead(
                        "node --check /root/dsha-task-notifier/lib/index.js 2>&1 | head -2; echo SYNTAX=${PIPESTATUS[0]}");
                if (syn != null && syn.contains("SYNTAX=1")) {
                    android.util.Log.w("DSHA", "task-notifier JS 语法错误，删 marker 强制重注入");
                    marker.delete();
                } else {
                    return; // 已注入且语法正常
                }
            }
            // 1) 注入实体
            writeAssetTo("task-notifier/package.json", new java.io.File(realDir, "package.json"));
            writeAssetTo("task-notifier/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
            writeAssetTo("task-notifier/lib/index.js", new java.io.File(realDir, "lib/index.js"));
            // 2) node_modules 符号链接
            if (nmLink.getParentFile() != null) nmLink.getParentFile().mkdirs();
            if (!nmLink.exists()) {
                java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                        java.nio.file.Paths.get(REAL));
            }
            // 3) 注册 profile（dependencies + bundles）
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (pf.isFile()) {
                String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                org.json.JSONObject root = new org.json.JSONObject(txt);
                org.json.JSONObject deps = root.optJSONObject("dependencies");
                if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
                if (!deps.has(NAME)) deps.put(NAME, "link:" + REAL);
                org.json.JSONObject dshObj = root.optJSONObject("dsh");
                if (dshObj == null) { dshObj = new org.json.JSONObject(); root.put("dsh", dshObj); }
                org.json.JSONObject profile = dshObj.optJSONObject("profile");
                if (profile == null) { profile = new org.json.JSONObject(); dshObj.put("profile", profile); }
                org.json.JSONArray bundles = profile.optJSONArray("bundles");
                if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
                boolean found = false;
                for (int i = 0; i < bundles.length(); i++) {
                    if (NAME.equals(bundles.optString(i, ""))) { found = true; break; }
                }
                if (!found) bundles.put(NAME);
                java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            java.nio.file.Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "任务通知插件已注册");
        } catch (Throwable ignored) {
        }
    }

    private void ensureDeviceShellGuide() {
        try {
            final String NAME = "dsh-device-shell-guide";
            final String REAL = "/root/dsha-device-shell-guide";
            java.io.File realDir = new java.io.File(proot.getRootfsDir(), "root/dsha-device-shell-guide");
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME);
            // 用户主动禁用（开关 → .disabled）：尊重用户，不再自动补回！
            // （否则启动时 ensureDeviceShellGuide 发现"注册缺失"会把禁用覆盖掉，
            //   表现就是"内置插件不能禁用"）
            if (new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME + ".disabled").exists()) {
                // 仅更新实体文件（assets 新版本写到实体目录，重新启用时拿到新版），
                // 不 touch marker / 不注册 / 不建链接
                writeAssetTo("device-shell-guide/package.json", new java.io.File(realDir, "package.json"));
                writeAssetTo("device-shell-guide/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
                writeAssetTo("device-shell-guide/lib/index.js", new java.io.File(realDir, "lib/index.js"));
                android.util.Log.i("DSHA", "device-shell-guide 已被用户禁用：仅更新实体，跳过注册");
                return;
            }
            java.io.File marker = new java.io.File(proot.getRootfsDir(), "root/dsha-device-shell-guide-installed");
            // 语法自愈：marker 存在但 JS 语法错误（如漏 + 连接符 → Unexpected string 崩溃）
            // → 删 marker 强制重注入修复版（不依赖版本 bump）
            if (marker.exists()) {
                String syn = proot.execAndRead(
                        "node --check /root/dsha-device-shell-guide/lib/index.js 2>&1 | head -2; echo SYNTAX=${PIPESTATUS[0]}");
                if (syn != null && syn.contains("SYNTAX=1")) {
                    android.util.Log.w("DSHA", "device-shell-guide JS 语法错误，删 marker 强制重注入");
                    marker.delete();
                }
            }
            // 版本自愈：marker 存在但插件版本旧（缺 cordis.entry 等修复）→ 删除 marker 强制重注入
            if (marker.exists()) {
                String curVer = "";
                try {
                    java.io.File pf2 = new java.io.File(realDir, "package.json");
                    if (pf2.isFile()) {
                        curVer = new org.json.JSONObject(
                                new String(java.nio.file.Files.readAllBytes(pf2.toPath()), StandardCharsets.UTF_8))
                                .optString("version", "");
                    }
                } catch (Throwable ignored) {
                }
                // 这里以前写死 "0.1.5"，插件版本一升就永远走「删 marker 重做」分支。
                // 改成对账 assets 里的真实版本。
                if (!builtinGuideVersion().equals(curVer)) {
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete();
                } else {
                    // 版本对但可能之前注册失败（NPE 旧版）：校验注册是否真生效，
                    // 没生效（bundles 缺/链接缺）→ 删 marker 重做
                    boolean registered = false;
                    try {
                        java.io.File pfV = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
                        if (pfV.isFile()) {
                            String tv = new String(java.nio.file.Files.readAllBytes(pfV.toPath()), StandardCharsets.UTF_8);
                            org.json.JSONObject rv = new org.json.JSONObject(tv);
                            org.json.JSONArray bs = rv.optJSONObject("dsh") == null ? null
                                    : rv.optJSONObject("dsh").optJSONObject("profile") == null ? null
                                    : rv.optJSONObject("dsh").optJSONObject("profile").optJSONArray("bundles");
                            if (bs != null) {
                                for (int i = 0; i < bs.length(); i++) {
                                    if (NAME.equals(bs.optString(i, "").trim())) { registered = true; break; }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (registered && nmLink.exists()) {
                        return; // 真的注册好了
                    }
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete(); // 注册缺失 → 重做
                }
            }
            // 1) 注入插件包实体（assets 三件套）
            writeAssetTo("device-shell-guide/package.json", new java.io.File(realDir, "package.json"));
            writeAssetTo("device-shell-guide/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
            writeAssetTo("device-shell-guide/lib/index.js", new java.io.File(realDir, "lib/index.js"));
            // 1.5) 清理旧痕迹（旧版 file: 依赖/手建链接会干扰 dsh plugin add）：
            //      移除旧依赖声明 + 删旧符号链接，让 dsh plugin 走干净状态
            try {
                java.io.File pf0 = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
                if (pf0.isFile()) {
                    String t0 = new String(java.nio.file.Files.readAllBytes(pf0.toPath()), StandardCharsets.UTF_8);
                    org.json.JSONObject r0 = new org.json.JSONObject(t0);
                    org.json.JSONObject d0 = r0.optJSONObject("dependencies");
                    if (d0 != null && d0.has(NAME)) d0.remove(NAME);
                    java.nio.file.Files.write(pf0.toPath(), r0.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            try {
                if (nmLink.exists()) nmLink.delete();
            } catch (Throwable ignored) {
            }
            // 2) node_modules 符号链接 → 实体目录（togglePlugin 靠改链接名开关）
            if (nmLink.getParentFile() != null) nmLink.getParentFile().mkdirs();
            if (!nmLink.exists()) {
                java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                        java.nio.file.Paths.get(REAL));
            }
            // 3) 注册到 web profile：手写 link: 依赖 + bundles（不跑 pnpm/dsh plugin，
            //    避免重装破坏 profile node_modules 导致其他插件异常；配合启动前
            //    fix-stale-bundles.sh 自愈兜底）
            {
                java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
                if (pf.isFile()) {
                    String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                    org.json.JSONObject root = new org.json.JSONObject(txt);
                    org.json.JSONObject deps = root.optJSONObject("dependencies");
                    if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
                    if (!deps.has(NAME)) deps.put(NAME, "link:" + REAL);
                    // 空安全：dsh/profile 可能不存在（全新 profile 或被 reconcile 清空）→ 需创建
                    org.json.JSONObject dshObj = root.optJSONObject("dsh");
                    if (dshObj == null) { dshObj = new org.json.JSONObject(); root.put("dsh", dshObj); }
                    org.json.JSONObject profile = dshObj.optJSONObject("profile");
                    if (profile == null) { profile = new org.json.JSONObject(); dshObj.put("profile", profile); }
                    org.json.JSONArray bundles = profile.optJSONArray("bundles");
                    if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
                    boolean found = false;
                    for (int i = 0; i < bundles.length(); i++) {
                        if (NAME.equals(bundles.optString(i, ""))) { found = true; break; }
                    }
                    if (!found) bundles.put(NAME);
                    java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                // 确保 node_modules 有可解析链接（link: 语义 = 符号链接）
                if (!nmLink.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                                java.nio.file.Paths.get(REAL));
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 只有「真的注册进 bundles 且链接在」才写 marker。
            // 以前无条件写：步骤⑥往往跑在 dsh 首次启动之前，那时
            // /root/.dsh/profiles/web/package.json 还不存在 → 注册段被 if 跳过 →
            // marker 却已落地 → 之后永远认为装好了，agent 一直拿不到设备提示词。
            if (!guideRegistered(NAME)) {
                android.util.Log.w("DSHA", "device-shell-guide 实体已就位，但 profile 尚未生成："
                        + "本次不写 marker，等 profile 出现后由 ensureBuiltinBundles/下次启动补注册");
                return;
            }
            java.nio.file.Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "设备 Shell 引导插件已注册（link: 依赖 + 符号链接）");
            // 双保险：home 级 cordis.patch.yml 覆盖【官方极简】的 bash 工具描述，
            // 让极简模式下 agent 也能看到 ADB 提示（和本插件联动的开关控制）
            try {
                java.io.File hp = new java.io.File(proot.getRootfsDir(), "root/.dsh/cordis.patch.yml");
                String hpText = hp.isFile()
                        ? new String(java.nio.file.Files.readAllBytes(hp.toPath()), StandardCharsets.UTF_8) : "";
                if (!hpText.contains("dsha-device-guide-bash")) {
                    String patchBlock =
                            "\n# DSHA device guide (dsha-device-guide-bash) - 官方极简模式 bash 工具描述注入\n"
                            + "- update:\n"
                            + "  - id: persistent-bash\n"
                            + "    name: '@deepseek-ai/dsh-tool-bash-persistent'\n"
                            + "    config:\n"
                            + "      description: |+\n"
                            + "        Run commands in a bash shell\n"
                            + "        * 设备操作：/root/dsh-bin/adb-shell \"命令\"（唯一可用通道，uid=2000，已配对）\n"
                            + "        * 不要用裸 adb（守卫脚本，会失败）；Shizuku 桥备用 curl -s \"http://127.0.0.1:3090/exec?cmd=...&token=$(cat /root/.dsh/.bridge_token)\"（漏 token 一律 UNAUTHORIZED）\n"
                            + "        * 与用户交流请用中文回复\n";
                    hpText += patchBlock;
                    java.nio.file.Files.write(hp.toPath(), hpText.getBytes(StandardCharsets.UTF_8));
                    android.util.Log.i("DSHA", "home patch 已注入官方极简 bash 描述");
                }
            } catch (Throwable ignored) {
            }
            // 清理旧版 dsha-minimal 独立预设（已合并到本插件）
            try {
                java.io.File oldPreset = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/.agent-presets/dsha-minimal");
                if (oldPreset.exists()) {
                    deleteRecursively(oldPreset);
                    android.util.Log.i("DSHA", "已清理旧版 dsha-minimal 预设");
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeAssetTo(String assetName, java.io.File dst) {
        try {
            String s = readAsset(assetName);
            if (s == null || s.isEmpty()) return;
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(), s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private boolean tryEnableLanBind() {
        // 缓存只在进程内有效：dsh 重装⑤/文件被覆盖后补丁可能丢失，
        // 不能永久信任 lanBindReady —— 每次调用重新校验补丁是否还在（幂等）。
        // 优化：上次成功且文件仍带 dsha-lan 标记 → 快速返回 true（秒级）。
        if (lanBindReady) {
            try {
                // 快速校验：补丁文件是否仍被改过（找 startup.js 带 dsha-lan 标记）
                String check = proot.execAndRead(
                        "grep -rl 'dsha-lan' /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js "
                        + "2>/dev/null | head -1 || grep -rl 'dsha-lan' "
                        + "/usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js 2>/dev/null | head -1");
                if (check != null && !check.trim().isEmpty()) return true;
                // 补丁丢了 → 重置缓存，重新打
                lanBindReady = false;
            } catch (Throwable ignored) {
                return lanBindReady; // 校验失败保守放行
            }
        }
        try {
            String script = readAsset("lan-bind-patch.sh");
            if (script.isEmpty()) return false;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-lan-patch.sh");
            f.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(script.getBytes(StandardCharsets.UTF_8));
            }
            String r = proot.execAndRead("bash /root/dsha-lan-patch.sh; rm -f /root/dsha-lan-patch.sh");
            lanBindReady = r != null && (r.contains("LAN_PATCHED") || r.contains("LAN_ALREADY"));
            return lanBindReady;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 检测本机局域网 IPv4 地址（免权限，NetworkInterface 枚举） */
    public static String getLanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String stopWebCommand() {
        // 兼容源码模式（bin.js web）与 RC6 模式（dsh web）
        // 先杀看门狗，否则 watchdog 会把 WebUI 又拉起来
        // 优雅退出：先 SIGTERM（让 dsh 优雅 flush SQLite 会话），等 3s 再 SIGKILL 兜底。
        // 直接 SIGKILL 会导致 SQLite 写一半 → 会话损坏（用户反馈：
        // "历史加载失败 SessionPersistenceCorruptionError"）
        return "pkill -f dsh-watchdog.sh 2>/dev/null; "
             + "pkill -TERM -f 'bin.js web' 2>/dev/null; pkill -TERM -f 'dsh web' 2>/dev/null; "
             + "sleep 3; "
             + "pkill -9 -f 'bin.js web' 2>/dev/null; pkill -9 -f 'dsh web' 2>/dev/null; "
             + "echo stopped";
    }

    private String statusCommand() {
        return "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:" + getPort() + "/ 2>/dev/null || echo 000";
    }

    // ================= Termux 模式 =================
    public boolean isTermuxInstalled() { return TermuxBridge.isInstalled(appContext); }
    public void openTermuxInstall() { TermuxBridge.openInstall(appContext); }

    private String buildTermuxInstallScript() {
        String s = readAsset("install-termux.sh");
        return s.replace("@@API_KEY@@", effectiveApiKey())
                .replace("@@PERMISSION_MODE@@", getPermissionMode());
    }

    /** 通过 Termux 安装 deepseek-harness */
    public void installViaTermux() {
        setProgress("提交安装任务到 Termux", 5);
        try {
            TermuxBridge.runScript(appContext, buildTermuxInstallScript(), null);
            setState("", 30, "已提交到 Termux 执行，请切到 Termux 查看进度", "", false);
        } catch (Throwable e) {
            setState("", 0, "", errMsg("提交失败：", e), false);
        }
    }

    private String startWebTermuxCommand() {
            // key 嵌在双引号里：转义 \\ 和 \"（防特殊字符破坏 Termux 命令）
            String k = effectiveApiKey().replace("\\", "\\\\").replace("\"", "\\\"");
            String pm = getPermissionMode(); // 白名单枚举，仍补引号
            StringBuilder sb = new StringBuilder();
            sb.append("export PATH=$HOME/dsh-bin:$PATH && ")
              .append("cd ~/").append(getWorkdir()).append(" && ")
              .append("export DEEPSEEK_API_KEY=\"").append(k).append("\" && ")
              .append("export DSH_PERMISSION_MODE=\"").append(pm).append("\" && ")
              .append("nohup node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1 & echo started");
            return sb.toString();
        }

    /** 通过 Termux 启动 Web UI */
    public void startWebViaTermux() {
        setProgress("正在启动 Web UI", 0);
        try {
            TermuxBridge.runScript(appContext, startWebTermuxCommand(), null);
            setState("", 100, "已提交启动，稍候在「启动」页打开预览", "", false);
        } catch (Throwable e) {
            setState("", 0, "", errMsg("启动失败：", e), false);
        }
    }

    public void stopWebViaTermux() {
        try {
            TermuxBridge.runScript(appContext,
                    "pkill -f 'bin.js web' 2>/dev/null; echo stopped", null);
        } catch (Throwable ignored) {
        }
    }

    // ================= 启动 / 停止 =================
    // ================= 版本与自愈常量 =================
    // 注意：GUARD_VERSION 必须与 assets/rootfs-confirm-install.sh 末尾写入的
    // /root/dsh-bin/.version 数字一致！曾出现 8 vs 9 不匹配 → 每次启动都强制
    // rm -rf 重装守卫（幂等但白干 + 可能打断进行中的命令）。
    private static final String GUARD_VERSION = "11";
    /** 步骤⑥整体版本号：内置插件/补丁/极简 preset 任一变更时 +1，
     *  启动时对比 rootfs 标记（step6.version），不符则自动重跑⑥（防"改了不生效"）。
     *  由 installGuard 末尾的 runStep 写入（先删 marker 后写版本 → 中途失败
     *  版本未写，下次启动版本不一致仍会重跑，自愈闭环不中断）。 */
    private static final String STEP6_VERSION = "4";
    /** 内置插件资产版本：mobile-adapt / device-shell-guide 的 client.js 等
     *  资产内容变更时 +1（marker 存在会导致重跑⑥时跳过重注入，
     *  必须靠版本标记删 marker 强制重注入，老用户才能拿到新资产）。
     *  与 STEP6_VERSION 一起写入 builtin-assets.version（installGuard 末尾）。 */
    private static final String BUILTIN_ASSET_VERSION = "10";

    /** 内置插件资产版本自愈（检查 + 删 marker；版本标记写入在 installGuard
     *  末尾 runStep 里——若中途失败版本未写，下次启动版本不一致会重跑⑥重注入，
     *  保证自愈闭环不因"先删后写"断裂）。
     *  幂等：版本一致秒回。 */
    private void refreshBuiltinAssetMarkers() {
        try {
            if (!proot.isInstalled()) return;
            String r = proot.execAndRead(
                    "cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE");
            if (r != null && r.trim().equals(BUILTIN_ASSET_VERSION)) return;
            proot.execAndRead(
                    "rm -f /root/dsha-mobile-adapt-installed /root/dsha-device-shell-guide-installed; "
                    + "echo refreshed");
            android.util.Log.i("DSHA", "内置插件资产版本变化 → 已删 marker，本次⑥将重注入新资产");
        } catch (Throwable ignored) {
        }
    }

    /** 确保 rootfs 内危险命令确认包装器已部署（版本不匹配则强制重装，幂等） */
    public void ensureDangerGuard() {
        try {
            // 版本标记：旧版包装器/守卫不升级是之前漏拦截的根因，必须强制刷新
            String ver = proot.execAndRead("cat /root/dsh-bin/.version 2>/dev/null || echo 0");
            if (ver != null && ver.trim().equals(GUARD_VERSION)) return;
            String inst = readAsset("rootfs-confirm-install.sh");
            if (inst.isEmpty()) return;
            // 清掉旧版（含旧 dsh-bin/守卫脚本），避免残留旧包装器
            proot.execChecked("rm -rf /root/dsh-bin /root/dsh-guard.sh /root/dsh-confirm.sh && echo CLEARED");
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(inst.getBytes(StandardCharsets.UTF_8));
            }
            proot.execChecked("bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
        } catch (Exception ignored) {
            // 环境未安装等场景静默
        }
    }

    private static final String KEY_GUARD_PATCH = "guard_patch_state";

    /** bash 工具守卫补丁的状态：ok / no_lib / patch_failed / unknown（还没启动过 Web）。
     *  （吸收上游 PR#24）配置页据此显示，别让用户以为确认仍是双保险。 */
    public String guardPatchState() {
        return prefs.getString(KEY_GUARD_PATCH, "unknown");
    }

    /** 给已构建的 bash 工具 lib 直接打补丁（强制每次执行前加载守卫，不依赖重新 build）。
     *
     *  这是全项目对 dsh 内部实现最脆弱的耦合：sed 匹配的是已构建代码里的字面串
     *  `command: request.command`，而 dsh 走「始终最新 RC」自动升级 —— 上游一改这行，
     *  这层保险就静默失配。所以每个分支都要 echo 到 stdout 让 Java 侧判得出成败，
     *  结果记进 prefs 供配置页展示（以前只写个用户永远看不到的日志文件）。 */
    public void ensureBashGuardPatch() {
        String state = "unknown";
        try {
            // RC6（npm 全局安装，依赖可能是嵌套或扁平布局，用 find 通配兼容两种）；
            // 源码版：packages/shell/bash-local
            String wd = getWorkdir();
            String out = proot.execAndRead(
                    "F=$(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-bash-local/lib/index.js' 2>/dev/null | head -1); " +
                    "if [ -z \"$F\" ]; then F=/root/" + wd + "/packages/shell/bash-local/lib/index.js; fi; " +
                    "if [ ! -f \"$F\" ]; then echo 'NO_LIB 守卫补丁: 未找到 bash 工具 lib' | tee /root/dsh-guard-patch.log; " +
                    "elif grep -q 'dsh-guard' \"$F\"; then echo LIB_ALREADY; " +
                    "else sed -i 's|command: request\\.command|command: `source /root/dsh-guard.sh 2>/dev/null; ${request.command}`|' \"$F\" " +
                    "&& grep -q 'dsh-guard' \"$F\" && echo LIB_PATCHED || " +
                    "echo 'PATCH_FAILED 守卫补丁: sed 未匹配（dsh 可能已升级改动代码）' | tee /root/dsh-guard-patch.log; fi");
            if (out != null) {
                if (out.contains("LIB_ALREADY") || out.contains("LIB_PATCHED")) state = "ok";
                else if (out.contains("NO_LIB")) state = "no_lib";
                else if (out.contains("PATCH_FAILED")) state = "patch_failed";
            }
        } catch (Exception ignored) {
        }
        try {
            if (!"unknown".equals(state) && !state.equals(prefs.getString(KEY_GUARD_PATCH, ""))) {
                prefs.edit().putString(KEY_GUARD_PATCH, state).apply();
                if (!"ok".equals(state)) {
                    android.util.Log.w("DSHA", "bash 工具守卫补丁未生效：" + state
                            + "（危险命令仍有 PATH 包装器拦截，但少一层兜底）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void startWeb() {
        // ===== 会话自愈必跑点：无论 web 是否已经在运行，都先提交一次自愈（幂等，秒级）。
        // 若把 heal 放在 IO 启动任务里，web 已存活时 startWeb 提前 return 会导致修复永不执行。
        maybeHealSessionCorruption();
        synchronized (webStartLock) {
            if (webProcess != null && webProcess.isAlive()) {
                return; // 已在运行，避免重复启动
            }
            if (webStarting) return; // 已有启动在进行（防 keepAlive/手动并发起第二个实例 → EADDRINUSE）
            webStarting = true;
        }
        // 局域网模式：桥跟随 WebUI 启动（不依赖 HarnessService——预启动/
        // 保活重启路径不经过 HarnessService，之前桥没起导致局域网访问不了）
        if (isLanMode()) {
            try {
                LanProxyService.start(getRootfsDirPath(), appContext, getPortInt());
            } catch (Throwable ignored) {
            }
        }
        IO.execute(() -> {
            boolean started = false;
            try {
                // 启动前预检：端口仍被占 → 深杀残留（根治 EADDRINUSE）
                if (isWebPortUp(400)) {
                    destroyAllWebProcesses();
                    proot.execAndRead(stopWebCommand());
                    if (!waitPortClosed(4000)) {
                        proot.execAndRead("pkill -TERM -f node 2>/dev/null; pkill -TERM -f 'bin.js' 2>/dev/null; "
                                + "sleep 3; pkill -9 -f node 2>/dev/null; pkill -9 -f 'bin.js' 2>/dev/null; sleep 1; echo done");
                        waitPortClosed(4000);
                    }
                }
                // 会话损坏自愈：web 拉起前先修（无门槛全量扫描，幂等；修复后用户刷新即恢复历史）
                doHealSessionCorruption();
                setProgress("正在启动 Web UI", 0);
                proot.ensureRuntimeFiles();
                ensureDangerGuard(); // 安全包装器缺失则自动补装
                ensureBashGuardPatch(); // bash 工具 lib 强制加载守卫（不依赖重装）
                Process p = proot.execRootfs(startWebCommand());
                webProcesses.add(p);
                synchronized (webStartLock) {
                    webProcess = p;
                }
                // web 已由用户/预启动成功拉起 → 解除 keepAlive 暂停（恢复崩溃自愈）
                prefs.edit().putBoolean("keepalive_paused", false).apply();
                bumpWebEpoch(); // 新 web 进程已起：通知预览端刷新
                // 关键：Web 启动中 busy=true 会让安装/重装按钮全灰。
                // 端口就绪即视为启动完成 → 释放 busy（不能等 drainOutput 阻塞返回，
                // 否则 Web 运行期间 busy 永远 true → 重装按钮永远灰色）。
                // 用独立线程阻塞 drain（保持 proot+node 进程存活，后台 nohup 会被 --kill-on-exit 杀掉），
                // 本线程继续等待端口就绪后释放 busy 并处理退出诊断。
                Thread drainer = new Thread(() -> {
                    try {
                        String out = proot.drainOutput(p);
                        // 进程退出：非用户主动停止 → 交给 keepAlive/自动重试
                        if (!isKeepAlivePaused()) {
                            String low = out == null ? "" : out.toLowerCase();
                            boolean configErr = low.contains("invalid api key") || low.contains("validationerror")
                                    || low.contains("api key") && low.contains("missing");
                            if (!configErr && autoRetryWebOnce()) return;
                            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
                            setState("", 0, "", "Web UI 意外退出：\n" + tail, false);
                        } else {
                            setState("", 0, "已停止后台服务", "", false);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (webStartLock) {
                            webStarting = false;
                        }
                    }
                }, "dsha-web-drain");
                drainer.setDaemon(true);
                drainer.start();
                // 端口等待放独立线程：IO 是单线程执行器，若在此阻塞 60s，
                // stopWeb/restartWeb/install 全部排队卡死（用户点停止没反应）
                Thread waiter = new Thread(() -> {
                    try {
                        if (waitWebPortUp(60_000)) {
                            setState("", 100, "Web UI 已启动", "", false);
                        } else {
                            // 超时：释放 busy（否则一直卡灰，靠 10 分钟自愈太慢）
                            setState("", 0, "", "Web UI 启动超时（60s 端口未就绪）\n"
                                    + "可稍后点「重启」，或查看启动页日志尾部", false);
                        }
                    } catch (Throwable ignored) {
                    }
                }, "dsha-web-portwait");
                waiter.setDaemon(true);
                waiter.start();
            } catch (Throwable e) {
                setState("", 0, "", errMsg("启动出错：", e), false);
            } finally {
                synchronized (webStartLock) {
                    webStarting = false; // 无论成功失败都要释放，否则后续启动全被挡
                }
            }
        });
    }

    /** Web 意外退出自动重试（限 1 次，防抖动死循环）。返回 true=已重试 */
    private volatile long lastAutoRetryAt = 0;
    private boolean autoRetryWebOnce() {
        long now = System.currentTimeMillis();
        if (now - lastAutoRetryAt < 30_000) return false; // 30s 内不重复重试
        lastAutoRetryAt = now;
        android.util.Log.w("DSHA", "Web 意外退出，30s 后自动重试 1 次");
        prefs.edit().putLong("web_auto_retry_at", now).apply();
        new Handler(Looper.getMainLooper()).postDelayed(this::startWeb, 30_000);
        return true;
    }

    public void stopWeb() {
        // 记录手动停止时间：最近停止后 90s 内关闭自动预启动（尊重用户）
        prefs.edit().putLong("last_web_stop", System.currentTimeMillis()).apply();
        // 标记"用户主动停止"：keepAlive 暂停自动拉起，直到用户/预启动再次 startWeb
        prefs.edit().putBoolean("keepalive_paused", true).apply();
        IO.execute(() -> {
            try {
                Process p = webProcess;
                if (p != null) {
                    p.destroy();
                    webProcess = null;
                }
                proot.execAndRead(stopWebCommand());
                // Web 停了桥也没用：停桥（幂等）
                LanProxyService.stop();
                setState("", 0, "已停止后台服务", "", false);
            } catch (Exception ignored) {
            }
        });
    }

    public void checkStatus() {
        IO.execute(() -> {
            try {
                proot.ensureRuntimeFiles();
                String out = proot.execAndRead(statusCommand());
                setState("", 0, "状态码：" + out.trim(), "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("检查失败：", e), false);
            }
        });
    }

    // ================= 配置备份 / 重置（防死机无法恢复） =================

    private File rootfsFile(String rel) {
        return new File(proot.getRootfsDir(), rel);
    }

    /**
     * 备份关键配置到 App 私有目录（可通过 MT 管理器 data/files/backup 拷出）。
     * 备份内容：.env + 整个 .dsh（含 settings.yaml、对话记录等）。
     * 返回备份目录绝对路径；失败返回 null。
     */
    /** 备份到外部存储（手动，时间戳命名）；返回路径或 null */
    public String backupConfig() {
        try {
            return BackupManager.backupToExternal(appContext, this);
        } catch (Exception e) {
            return null;
        }
    }

    /** 更新前自动存档：检测到新版本时静默备份一次（防覆盖安装丢数据）。
     *  节流：同一目标版本只备份一次（backup_before_update_tag 记录），
     *  rootfs 未就绪/备份失败静默跳过，不阻塞更新流程。 */
    public void backupBeforeUpdate(String targetVersion) {
        try {
            if (targetVersion == null || targetVersion.isEmpty()) return;
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            if (targetVersion.equals(prefs.getString("backup_before_update_tag", ""))) return; // 已备份过该版本
            prefs.edit().putString("backup_before_update_tag", targetVersion).apply();
            new Thread(() -> {
                try {
                    if (proot.isInstalled()) {
                        String p = BackupManager.backupToExternal(appContext, this);
                        if (p != null) {
                            android.util.Log.i("DSHA", "更新前自动存档完成（准备升级 " + targetVersion + "）: " + p);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "dsha-backup-before-update").start();
        } catch (Throwable ignored) {
        }
    }

    // ================= 升级自动备份 / 恢复（防卸载重装丢数据） =================

    /** 当前 App 的 versionCode；读取失败返回 0 */
    private int currentVersionCode() {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 升级/首次启动自愈（幂等）：版本号比上次运行时提升 → 后台自动把旧环境
     * （.dsh 配置+对话记录 + .env）备份到外部 Download/DSHA，
     * 避免后续覆盖安装/卸载重装导致数据丢失。
     * @return true = 本次为升级或首次启动（调用方可据此检测"全新环境可恢复"）
     */
    public boolean upgradeGuard() {
        final int cur = currentVersionCode();
        if (cur <= 0) return false;
        final SharedPreferences prefs =
                appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        final int last = prefs.getInt("last_version_code", 0);
        if (cur <= last) return false; // 版本未变，幂等返回
        prefs.edit().putInt("last_version_code", cur).apply();
        final int from = last; // 迁移起点（0=全新安装）
        if (last > 0) { // 真升级（非全新安装）：后台自动备份旧环境 + 版本迁移
            IO.execute(() -> {
                try {
                    // rootfs 已就绪（有 bash）才备份；未解压/未安装时跳过
                    if (proot.isInstalled() && rootfsFile("root/.dsh").isDirectory()) {
                        String p = BackupManager.backupToExternal(appContext, HarnessController.this);
                        if (p != null) android.util.Log.i("DSHA", "升级自动备份完成: " + p);
                    }
                } catch (Throwable ignored) {
                }
                // ===== 低版本安装适配：老 rootfs 结构差异集中迁移 =====
                // 按来源版本分层，逐层升级（幂等，每层只做该层需要的事）：
                // 老版本（versionCode<=21，即 v1.1.1 及更早）需要补适配。
                try {
                    if (from <= 21 && proot.isInstalled()) {
                        // 1) 老版本无 builtin-assets.version → 删旧 marker 强制重注入官方版
                        //    （老 rootfs 的 mobile-adapt 是旧布局/旧内容，靠 STEP6 版本变化
                        //    重跑⑥时 refreshBuiltinAssetMarkers 已处理；这里兜底删 marker）
                        String r = proot.execAndRead(
                                "cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE");
                        if (r == null || !r.trim().equals(BUILTIN_ASSET_VERSION)) {
                            proot.execAndRead(
                                    "rm -f /root/dsha-mobile-adapt-installed /root/dsha-device-shell-guide-installed; echo cleaned");
                            android.util.Log.i("DSHA", "迁移(≤v1.1.1)：已删内置插件 marker，等待重注入");
                        }
                        // 2) 老版本无离线包版本标记 → 写当前（避免误弹升级提示）
                        //    installedOfflineVersion()=="0" 且 bundled>"0" 时
                        //    用户会收到一次升级提示（合理）；这里不主动写，保持提示语义。
                        // 3) 老版本工作区 .env 若在默认目录 → 已由数据保护覆盖
                        android.util.Log.i("DSHA", "迁移(≤v1.1.1)完成");
                    }
                    // 更早版本（v1.0.x，versionCode<=19）可能有旧 profile 结构
                    if (from <= 19 && proot.isInstalled()) {
                        // 旧版 profile 可能缺 dependencies 字段 / 用 file: 依赖，
                        // 触发一次 fix-stale-bundles 自愈（App 启动时会跑，这里显式跑一次）
                        proot.execAndRead(
                                "rm -f /root/dsha-mobile-adapt-installed /root/dsha-device-shell-guide-installed; "
                                + "echo 'v1.0.x 迁移：删 marker 强制重注入'");
                        android.util.Log.i("DSHA", "迁移(v1.0.x)完成");
                    }
                } catch (Throwable ignored) {
                    // 迁移失败不影响使用（幂等，下次启动 STEP6 变化仍会自愈）
                }
            });
        }
        return true;
    }



    /** 清理损坏会话（供工作区页按钮调用）：
     *  把「无法解码/极小」的会话移到 .dsh/corrupt-backup/（不删除可恢复）。
     *  返回处理结果文案。 */
    public String cleanCorruptSessions() {
        try {
            if (!proot.isInstalled()) return "环境未就绪";
            String out = proot.execAndRead(
                    "mkdir -p /root/.dsh/corrupt-backup; "
                    + "find /root/.dsh/sessions -name 'session.jsonl.zstd' -size -100c 2>/dev/null "
                    + "| while read f; do "
                    + "d=$(dirname \"$f\"); id=$(basename \"$d\"); "
                    + "mkdir -p /root/.dsh/corrupt-backup/\"$id\"; "
                    + "mv \"$f\" /root/.dsh/corrupt-backup/\"$id\"/ 2>/dev/null && echo \"已隔离: $id\"; done");
            if (out == null || !out.contains("已隔离")) return "未发现损坏会话（<100字节的极小文件）";
            return out.trim();
        } catch (Throwable e) {
            return "清理失败: " + e.getMessage();
        }
    }

    /** 启动时自愈：删除空的 /root/.codex/pets（deepseek-pet 插件把空目录当错误
     *  → 整个插件树加载失败）。老版本预创建过空目录，需清理。幂等、后台静默。 */
    public void maybeCleanEmptyPets() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                proot.execAndRead(
                        "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] "
                        + "&& rmdir /root/.codex/pets 2>/dev/null; echo cleaned");
            } catch (Throwable ignored) {
            }
        });
    }

    /** 会话损坏自愈：启动时检测 dsh-web.log 里的 SessionPersistenceCorruptionError
     *  （中途强杀导致 SQLite 写一半损坏，用户反馈"历史加载失败"）。
     *  检测到 → 备份损坏 .db 并删除（dsh 重建），老会话丢失但 App 可用。
     *  配合停止命令 SIGTERM 优雅退出（减少写入中断）。幂等、后台静默。 */
    public void maybeHealSessionCorruption() {
        IO.execute(this::doHealSessionCorruption);
    }

    /** 同步执行会话自愈（可在 IO 线程/启动链路内联调用）：
     *  1) 修复前先停 Web（否则写入中的会话文件边修边坏）；
     *  2) heal-sessions.py（Python os.walk + 流式 zstd 解码）无门槛全量扫描修复。
     *  幂等：正常文件秒过。 */
    private void doHealSessionCorruption() {
        healingSession = true;
        try {
            if (!proot.isInstalled()) return;
            // 先停 Web / 等端口关透，防止修复期间 dsh 进程继续写同一会话文件
            try {
                if (isWebPortUp(300)) {
                    destroyAllWebProcesses();
                    proot.execAndRead(stopWebCommand());
                    waitPortClosed(5000);
                }
            } catch (Throwable ignored) {
            }
            String script = readAsset("heal-session.sh");
            if (script == null || script.isEmpty()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-heal-session.sh");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            // 注入 Python 自愈主程序（os.walk 扫描，不依赖 find/bash glob）
            String healPy = readAsset("heal-sessions.py");
            if (healPy != null && !healPy.isEmpty()) {
                java.io.File hp = new java.io.File(proot.getRootfsDir(), "root/.dsh/heal-sessions.py");
                if (hp.getParentFile() != null) hp.getParentFile().mkdirs();
                java.nio.file.Files.write(hp.toPath(), healPy.getBytes(StandardCharsets.UTF_8));
            }
            // 注入 zstandard 离线 wheel（在线安装的 rootfs 无 zstd、pip 可能没网时，
            // heal 用它 --no-index 本地装，保证能解压修复会话）
            try {
                String wheelName = "zstandard-0.25.0-cp312-cp312-manylinux_2_17_aarch64.whl";
                byte[] wheel = readAssetBytes(wheelName);
                if (wheel != null && wheel.length > 0) {
                    java.io.File whl = new java.io.File(proot.getRootfsDir(), "root/.dsh/" + wheelName);
                    if (whl.getParentFile() != null) whl.getParentFile().mkdirs();
                    java.nio.file.Files.write(whl.toPath(), wheel);
                }
            } catch (Throwable ignored) {
            }
            String r = proot.execAndRead("bash /root/dsha-heal-session.sh; rm -f /root/dsha-heal-session.sh");
            if (r != null && r.contains("SESSION_HEALED")) {
                android.util.Log.w("DSHA", "会话损坏自愈：已修复/隔离损坏会话（详情见 heal 输出）");
            }
        } catch (Throwable ignored) {
        } finally {
            healingSession = false;
        }
    }

    /** 启动时对比步骤⑥版本标记（step6.version + builtin-assets.version）：
     *  任一与当前不符 → 自动重跑⑥（守卫/补丁/内置插件资产更新自动适配）。
     *  幂等、后台静默。注意：版本检查（execAndRead 起 proot）丢 IO 线程，不能在主线程跑。
     *  全新离线包（预置 marker 但无版本标记）首启也会重跑⑥——幂等无害，可接受
     *  （离线预置与在线⑥内容一致，重跑只是把版本标记补齐）。 */
    public void maybeRefreshStep6() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                String r = proot.execAndRead(
                        "S=$(cat /root/.dsh/step6.version 2>/dev/null || echo NONE); "
                        + "A=$(cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE); "
                        + "echo \"$S|$A\"");
                String want = STEP6_VERSION + "|" + BUILTIN_ASSET_VERSION;
                if (r != null && r.trim().equals(want)) return; // 版本一致
                android.util.Log.i("DSHA", "步骤⑥/资产版本变化（rootfs=" + (r == null ? "?" : r.trim())
                        + " 期望=" + want + "），自动重跑⑥");
                if (tryBeginBusy()) {
                    runInstallStep(STEP_GUARD);
                    setState("", 100, "已自动更新安全守卫与内置插件（⑥）", "", false);
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "自动重跑⑥失败（不影响使用）: " + e);
                setState("", 0, "", "", false);
            }
        });
    }

    /** 主动检测 dsh 新版本：已装版本 vs npm 最新 rc（dist-tags.next，24h 节流），
     *  npm 查询失败静默跳过（网络/镜像问题），版本比较只升不降。 */
    private String queryLatestDshRc() {
        try {
            // 节流：24h 内不重复查（避免每次启动都打 registry）。
            // 注意：只在「真正执行了 npm 查询」后更新时间戳——网络故障/命令失败
            // 时不更新，下次启动仍会重试（否则一次失败会哑 24h）。
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            long last = prefs.getLong("last_dsh_rc_check_ts", 0);
            if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return null;
            // npmmirror 优先（国内快），失败回退官方源；只取 dist-tags.next（最新 rc）
            String r = proot.execAndRead(
                    "timeout 20 npm view @deepseek-ai/dsh dist-tags.next --registry=https://registry.npmmirror.com 2>/dev/null "
                    + "|| timeout 20 npm view @deepseek-ai/dsh dist-tags.next --registry=https://registry.npmjs.org 2>/dev/null");
            if (r == null || r.startsWith("ERROR") || r.contains("NONE")) return null;
            // 查询真正执行且有输出（哪怕没解析出 rc）→ 记时间戳
            prefs.edit().putLong("last_dsh_rc_check_ts", System.currentTimeMillis()).apply();
            String v = r.trim();
            // 返回完整版本（0.1.1-rc.2）——旧实现截成 rc.2 无法区分 0.1.0-rc.2 和
            // 0.1.1-rc.2（跨小版本同 rc 号会误判/漏判）。只认含 rc 的版本，防 stable。
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d+\\.\\d+\\.\\d+-rc\\.\\d+)").matcher(v);
            return m.find() ? m.group(1) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** rc 号比较：a > b 返回 true（只升不降，防镜像回退触发重装） */
    /** dsh 版本比较（完整版本，支持 0.1.0-rc.8 与 0.1.1-rc.2 这种跨小版本）。
     *  仅比较 rc 号会误判（rc.2 < rc.8 → 0.1.1-rc.2 被当旧版 → 不升级）！
     *  格式：<major>.<minor>.<patch>-rc.<n>，缺省段按 0。a > b 返回 true。 */
    private static boolean dshVersionNewer(String a, String b) {
        try {
            return dshVersionScore(a) > dshVersionScore(b);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析单个版本字符串（X.Y.Z-rc.N）为分数 */
    private static long scoreOf(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*?rc\\.(\\d+)").matcher(v);
        if (m.find()) {
            long major = Long.parseLong(m.group(1));
            long minor = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
            long patch = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
            long rc = Long.parseLong(m.group(4));
            return ((major * 1000 + minor) * 1000 + patch) * 1000 + rc;
        }
        return 0;
    }

    /** 解析 dsh 版本为可比较的整数分数：主版本段 * 1000 + rc 号（rc 号权重最大）。 */
    private static long dshVersionScore(String v) {
        if (v == null) return 0;
        String t = v.trim().toLowerCase();
        // 剥离 ANSI 颜色码（[...m）——部分 dsh 版本 --version 带颜色输出
        t = t.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "");
        // 找所有形如 X.Y.Z-rc.N 的完整版本段，取【最大】的（dsh 可能输出多个版本，
        // 如 "0.1.1-rc.2 (compat 0.1.0-rc.8)" —— 取第一个会误判旧版）
        java.util.regex.Matcher full = java.util.regex.Pattern.compile(
                "(\\d+\\.\\d+\\.\\d+-rc\\.\\d+)").matcher(t);
        String best = null;
        long bestScore = -1;
        while (full.find()) {
            long sc = scoreOf(full.group(1));
            if (sc > bestScore) { bestScore = sc; best = full.group(1); }
        }
        if (best != null) t = best;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*?rc\\.(\\d+)").matcher(t);
        if (m.find()) {
            long major = Long.parseLong(m.group(1));
            long minor = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
            long patch = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
            long rc = Long.parseLong(m.group(4));
            return ((major * 1000 + minor) * 1000 + patch) * 1000 + rc;
        }
        // 无 rc 段（如 stable 版本）：按纯数字段比较
        String[] parts = t.replaceAll("[^0-9.]", "").split("\\.");
        long score = 0;
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            if (!parts[i].isEmpty()) score = score * 1000 + Long.parseLong(parts[i]);
        }
        return score * 1000; // rc 段视为 0（stable 高于同版本 rc）
    }

    /** 兼容旧调用（rcNewer 改名保留，内部走完整比较） */
    private static boolean rcNewer(String a, String b) {
        return dshVersionNewer(a, b);
    }

    /** 启动时检测 dsh 新版本：主动查 npm 最新 rc，比已装新 → 自动【重装⑤+⑥】；
     *  兼容被动场景（离线包/手动重装导致已装版本变化 → 同样适配）。
     *  先装新版再适配，一气呵成；幂等、后台静默；失败不影响启动。 */
    public void maybeAutoReinstallGuardOnDshUpdate() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                final SharedPreferences prefs =
                        appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
                String installed = proot.execAndRead(
                        "command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE");
                if (installed == null || installed.contains("NONE") || installed.startsWith("ERROR")) return;
                String installedRc = installed.trim(); // 完整版本，如 0.1.1-rc.2
                String last = prefs.getString("last_dsh_rc", "");
                String target = null;
                // 1) 主动：npm 最新 rc > 已装 → 目标 = 最新（真正"检测到新版自动升级"）
                String latest = queryLatestDshRc();
                if (latest != null && dshVersionNewer(latest, installedRc)) {
                    target = latest;
                }
                // 2) 被动：已装版本比上次记录**更新**（离线包带新版/手动升级）→ 适配已装版本。
                //    只升不降：防离线包回退旧版触发降级重装（重装 @next 又升回去 → 反复重装）
                if (target == null && !last.isEmpty() && dshVersionNewer(installedRc, last)) {
                    target = installedRc;
                }
                if (target == null) {
                    // 首次检测只记录基线
                    if (last.isEmpty()) prefs.edit().putString("last_dsh_rc", installedRc).apply();
                    return;
                }
                // 升级前快照 + 记录目标版本
                if (!last.isEmpty()) prefs.edit().putString("prev_dsh_rc", last).apply();
                prefs.edit().putString("last_dsh_rc", target).apply();
                android.util.Log.i("DSHA", "检测到 dsh 新版本 " + installedRc + " → " + target + "，自动重装⑤+⑥");
                if (tryBeginBusy()) {
                    // ⑤：重装最新 RC（npm @next 跟随官方，npmmirror 同步滞后时回退官方源）
                    runInstallStep(STEP_HARNESS);
                    // ⑥：守卫/补丁/内置插件/极简preset 适配新版
                    runInstallStep(STEP_GUARD);
                    setState("", 100, "已自动升级 dsh 并完成适配（⑤+⑥）", "", false);
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "自动升级⑤+⑥失败（不影响使用）: " + e);
                setState("", 0, "", "", false);
            }
        });
    }

    /** 启动时全链路自动体检+自愈（打开即用，用户无感）：
     *  脚本注入→依赖→包装命令→连接，缺啥修啥；ADB 开关没开则跳过。 */
    public void maybeAdbSelfHeal() {
        try {
            if (!DeviceBridgeService.isAdbEnabled(appContext)) return; // 尊重开关
            if (!proot.isInstalled()) return;
            IO.execute(() -> {
                try {
                    // 1) 脚本版本不符 → 重注入
                    if (!AdbBridge.injected(proot)) {
                        AdbBridge.inject(appContext, proot);
                    }
                    // 2) wheels 缺失 → 注入
                    if (!AdbBridge.wheelsPresent(proot)) {
                        AdbBridge.injectWheels(appContext, proot);
                    }
                    // 3) 依赖/密钥/包装命令 任一缺失 → 完整 setup
                    if (!AdbBridge.keyPresent(proot) || !AdbBridge.depsOk(proot)
                            || !AdbBridge.wrapperPresent(proot)) {
                        String setup = AdbBridge.setup(proot);
                        android.util.Log.i("DSHA-ADB", "启动自愈 setup: " + setup);
                    }
                    // 4) 有密钥有依赖 → 探一次连接（失败交给看门狗周期重连）
                    if (AdbBridge.keyPresent(proot) && AdbBridge.depsOk(proot)) {
                        String r = proot.execAndRead("DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py id 2>&1 | head -2");
                        if (r != null && r.contains("uid=")) {
                            android.util.Log.i("DSHA-ADB", "启动体检：ADB 连接正常");
                        } else {
                            android.util.Log.i("DSHA-ADB", "启动体检：未连接（看门狗将自动重连）" + r);
                        }
                    }
                } catch (Throwable e) {
                    android.util.Log.w("DSHA-ADB", "启动自愈异常（忽略）: " + e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 启动计数自动备份：每启动 N 次触发一次自动备份（固定名自动覆盖上一个自动备份）。
     *  N 从配置项 auto_backup_launches 读取（默认 5，0=关闭）。
     *  与手动备份独立（手动每次保留时间戳文件）。幂等、后台执行、失败静默。
     *  计数器独立（backup_launch_count），不与其他启动计数功能（备份提醒）共用。 */
    public void maybeAutoBackupOnLaunch() {
        try {
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            final int interval = prefs.getInt("auto_backup_launches", 5);
            if (interval <= 0) return; // 配置为 0 = 关闭自动备份
            final int n = prefs.getInt("backup_launch_count", 0) + 1;
            prefs.edit().putInt("backup_launch_count", n).apply();
            if (n % interval != 0) return; // 每 N 次才备份
            IO.execute(() -> {
                try {
                    if (proot.isInstalled() && rootfsFile("root/.dsh").isDirectory()) {
                        String p = BackupManager.backupToExternalAuto(appContext, HarnessController.this);
                        if (p != null) android.util.Log.i("DSHA", "第 " + n + " 次启动，自动备份完成: " + p);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 外部下载目录 Download/DSHA 里最新的 DSHA 备份；没有返回 null */
    public File findLatestExternalBackup() {
        // Android 10+：优先 MediaStore 查询（无需「所有文件访问」权限）。分区存储下直接
        // File.listFiles() 枚举不到 Download/DSHA —— 而 DSHA 从不请求 MANAGE_EXTERNAL_STORAGE
        // （只在 manifest 声明 + isExternalStorageManager 检测），于是 maybePromptRestore 的
        // 自动恢复在大多数设备上永远发现不了备份（issue #22）。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                File dirBase = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String relPath = android.os.Environment.DIRECTORY_DOWNLOADS + "/DSHA/";
                try (android.database.Cursor cur = appContext.getContentResolver().query(
                        col,
                        new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                                android.provider.MediaStore.MediaColumns.DATE_MODIFIED},
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{relPath}, null)) {
                    if (cur != null) {
                        String bestName = null;
                        long bestTime = -1;
                        while (cur.moveToNext()) {
                            String dn = cur.getString(0);
                            if (dn == null) continue;
                            String low = dn.toLowerCase();
                            if (!low.startsWith("dsha-backup-") && !low.startsWith("dsha-migration-")) continue;
                            if (!low.endsWith(".tar.gz") && !low.endsWith(".tgz")) continue;
                            long t = cur.getLong(1);
                            if (t > bestTime) { bestTime = t; bestName = dn; }
                        }
                        if (bestName != null) {
                            // MediaStore 的 DATE_MODIFIED 是秒；这里只与同单位的值比较。
                            File f = new File(dirBase, "DSHA/" + bestName);
                            if (f.isFile() && f.length() > 0) return f;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 兜底：老路径直接列目录（已授予「所有文件访问」的设备也能枚举到）
        try {
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "DSHA");
            // 宽容：手动/自动备份（DSHA-backup-*）与升级迁移包（DSHA-migration-*）都认，
            // .tgz 后缀也接受（用户手工改名/其他工具打的包）
            File[] fs = dir.listFiles((d, n) -> {
                if (n == null) return false;
                String low = n.toLowerCase();
                boolean nameOk = low.startsWith("dsha-backup-") || low.startsWith("dsha-migration-");
                return nameOk && (low.endsWith(".tar.gz") || low.endsWith(".tgz"));
            });
            if (fs == null || fs.length == 0) return null;
            File best = null;
            for (File f : fs) {
                if (f.length() <= 0) continue; // 跳过空/半截文件
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从外部备份 tar.gz 恢复：宽容优先——能恢复多少就恢复多少，绝不因一处不认识就整包失败。
     *
     *  流程：宽松解压到 stage → restore-merge.py 做布局识别 / 工作目录重映射 /
     *  本机路径插件重建 / bundle 预检（解析不了的先摘掉，保证 dsh web 能启动）。
     *  脚本不可用（老 rootfs 无 python3 等）时退回「整包直接铺到 /root」的老行为。
     *  兼容：.dsh 在包里任意层级、备份工作目录名与本机不同、无清单的老备份。 */
    public String restoreFromBackup(File backup) {
        try {
            if (!proot.isInstalled()) return "环境未就绪，请先完成环境解压/安装后再恢复";
            File rootDir = new File(proot.getRootfsDir(), "root");
            File stage = new File(rootDir, ".dsha-restore-stage");
            deleteRecursively(stage);
            //noinspection ResultOfMethodCallIgnored
            stage.mkdirs();
            File tmp = rootfsFile("root/.dsha-restore.tar.gz");
            File src = backup;
            boolean copied = false;
            // 调用方可能已经把包放在 rootfs 内（工作区页的恢复）：同路径就别自己拷自己
            if (!backup.getAbsolutePath().equals(tmp.getAbsolutePath())) {
                copyFile(backup, tmp);
                src = tmp;
                copied = true;
            }
            try {
                TarGzipExtractor.extractLenient(src, stage);
            } finally {
                if (copied) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            String script = readAsset("restore-merge.py");
            String out = null;
            if (script != null && !script.isEmpty()) {
                java.io.File sf = new java.io.File(rootDir, ".dsha-restore-merge.py");
                java.nio.file.Files.write(sf.toPath(), script.getBytes(StandardCharsets.UTF_8));
                out = proot.execAndRead("python3 /root/.dsha-restore-merge.py"
                        + " --stage /root/.dsha-restore-stage --root /root --workdir "
                        + shellArg(getWorkdir())
                        + " 2>&1; rm -f /root/.dsha-restore-merge.py", 240_000);
            }
            boolean ok = out != null && out.contains("RESTORE_OK");
            boolean partial = out != null && out.contains("RESTORE_PARTIAL");
            if (!ok && !partial) {
                // 兜底（宽容）：整理脚本没跑成 → 老行为，把包内容直接铺到 /root
                proot.execAndRead("cp -a /root/.dsha-restore-stage/. /root/ 2>/dev/null; "
                        + "rm -rf /root/.dsha-restore-stage; echo DONE");
                deleteRecursively(stage);
                syncApiKeyFromRootfs();
                return "恢复完成（基础模式：整包还原）\n"
                        + "若启动报插件缺失，可到「市场」重新安装该插件。重启 WebUI 生效";
            }
            String body = out.replace("RESTORE_OK", "").replace("RESTORE_PARTIAL", "")
                    .replace("RESTORE_EMPTY", "").trim();
            // 解压阶段跳过的异常条目也如实告知（宽容 ≠ 悄悄丢东西）
            if (TarGzipExtractor.lastSkipped > 0) {
                body += "\n· 备份里有 " + TarGzipExtractor.lastSkipped
                        + " 个异常条目已跳过：" + TarGzipExtractor.lastSkipNote;
            }
            // 缺失插件：后台静默补装（不阻塞恢复结果返回，失败无感）
            java.util.List<String> missing = parseMissingPlugins(out);
            body = stripMachineLines(body);
            if (!missing.isEmpty()) {
                autoInstallPluginsSilently(missing);
                body += "\n· " + missing.size() + " 个插件正在后台补装，装好后重启 WebUI 即回到启用状态";
            }
            deleteRecursively(stage);
            invalidateSteps();
            // issue #22：恢复数据落位后回读备份里的 .dsha-apikey 并回填配置页——
            // 否则离线包用户（无 .env）走自动/迁移恢复后配置页 key 为空、启动注入空 key。
            syncApiKeyFromRootfs();
            return (ok ? "恢复完成" : "恢复完成（部分内容已跳过，详见下方）")
                    + (body.isEmpty() ? "" : "\n" + body)
                    + "\n重启 WebUI 生效";
        } catch (Exception e) {
            return "恢复失败: " + e.getMessage();
        }
    }

    /** issue #22：把 rootfs 里恢复出的 .dsha-apikey 回填到 App 配置页（与 WorkspaceFragment.doRestore 的手动恢复一致）。
     *  .env 只在「在线安装」最后一步写，离线包用户恢复后没有它，key 在备份的 .dsh/.dsha-apikey 里。 */
    private void syncApiKeyFromRootfs() {
        try {
            String k = proot.execAndRead("cat /root/.dsh/.dsha-apikey 2>/dev/null");
            if (k == null) return;
            k = k.trim();
            // 只接受形如密钥的值（避免把脚本输出/错误信息当 key 写进配置）
            if (!k.isEmpty() && !k.contains(" ") && k.length() >= 8) {
                setApiKey(k);
                android.util.Log.i("DSHA", "恢复后已从 .dsha-apikey 回填 API key（issue #22）");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 从整理脚本输出里取出可自动补装的插件名（机器可读行 `MISSING_PLUGINS: a,b`） */
    private static java.util.List<String> parseMissingPlugins(String out) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (out == null) return list;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("MISSING_PLUGINS:")) continue;
            for (String raw : s.substring("MISSING_PLUGINS:".length()).split(",")) {
                String name = raw.trim();
                if (!name.isEmpty() && isValidPluginSpec(name) && !list.contains(name)) {
                    list.add(name);
                }
            }
        }
        return list;
    }

    /** 去掉给程序看的行，只留给用户看的报告正文 */
    private static String stripMachineLines(String body) {
        if (body == null || body.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.trim().startsWith("MISSING_PLUGINS:")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /** 恢复后台静默补装缺失插件：逐个 dsh plugin add，不弹窗不打扰；
     *  结果写 logcat 与 .dsh/restore-report.txt。装不上也不影响已恢复的数据。 */
    private void autoInstallPluginsSilently(final java.util.List<String> names) {
        Thread t = new Thread(() -> {
            StringBuilder log = new StringBuilder("== 后台补装插件 ==\n");
            int ok = 0;
            for (String name : names) {
                try {
                    String r = installPlugin(name);
                    boolean good = r != null && r.contains("INSTALL_EXIT=0");
                    if (good) ok++;
                    log.append(good ? "✓ " : "✗ ").append(name).append("：")
                            .append(r == null ? "无输出" : tail(r.trim(), 200)).append('\n');
                } catch (Throwable e) {
                    log.append("✗ ").append(name).append("：").append(describe(e)).append('\n');
                }
            }
            log.append("小结：成功 ").append(ok).append('/').append(names.size()).append('\n');
            android.util.Log.i("DSHA", "恢复后补装插件完成 " + ok + "/" + names.size());
            try {
                ensureBuiltinBundles(); // 顺带把内置插件校验补回
            } catch (Throwable ignored) {
            }
            try {
                java.io.File rp = rootfsFile("root/.dsh/restore-report.txt");
                if (rp.getParentFile() != null && rp.getParentFile().isDirectory()) {
                    java.nio.file.Files.write(rp.toPath(),
                            log.toString().getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Throwable ignored) {
            }
        }, "dsha-restore-plugins");
        t.setDaemon(true);
        t.start();
    }

    /** 取字符串尾部 n 个字符（日志用，避免把整段安装输出塞进报告） */
    private static String tail(String s, int n) {
        if (s == null) return "";
        String one = s.replace("\n", " ");
        return one.length() <= n ? one : "…" + one.substring(one.length() - n);
    }

    /**
     * 全新环境检测：若 rootfs 尚无数据（卸载重装后的空环境）且 Download/DSHA 存在旧备份，
     * 弹窗询问是否恢复。仅在 rootfs 已就绪后由调用方触发（避免解压前误弹）。
     */
    public void maybePromptRestore(final android.app.Activity act) {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return; // rootfs 未就绪（未解压/未安装）不弹
                if (rootfsFile("root/.dsh").isDirectory()) return; // 已有数据，不打扰
                final File b = findLatestExternalBackup();
                if (b == null) return;
                new Handler(Looper.getMainLooper()).post(() -> {
                    new android.app.AlertDialog.Builder(act)
                            .setTitle("检测到旧版备份")
                            .setMessage("发现备份：\n" + b.getName()
                                    + "\n\n是否恢复到当前环境？\n（恢复配置、API Key 与对话记录）")
                            .setPositiveButton("恢复", (d, w) -> {
                                String r = restoreFromBackup(b);
                                android.widget.Toast.makeText(act, r, android.widget.Toast.LENGTH_LONG).show();
                            })
                            .setNegativeButton("忽略", null)
                            .show();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    /** 离线包升级感知：APK 内置离线包版本 > rootfs 已解压版本 → 弹窗提示升级
     * （重解压自带数据保护：.dsh/.env 自动备份还原，用户确认后跳转强制解压页）。
     * 用户点"忽略"记录版本，下次不弹。rootfs 未解压/无内置包/版本相同 → 静默。 */
    public void maybeOfferOfflineUpgrade(final android.app.Activity act) {
        IO.execute(() -> {
            try {
                if (!proot.isOfflineExtracted()) return; // 首启未解压：走正常解压流程，不提示
                final String bundled = proot.bundledOfflineVersion();
                final String installed = proot.installedOfflineVersion();
                if ("0".equals(bundled) || bundled.equals(installed)) return; // 无内置包/无更新
                final SharedPreferences prefs =
                        appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
                if (bundled.equals(prefs.getString("ignored_offline_version", ""))) return; // 已忽略
                new Handler(Looper.getMainLooper()).post(() -> {
                    new android.app.AlertDialog.Builder(act)
                            .setTitle("发现新版内置环境 v" + bundled)
                            .setMessage("当前已解压环境：v" + installed + "\n\n"
                                    + "新版包含：预置内置插件 / 组件更新 / 修复。\n"
                                    + "升级将重新解压（约数分钟），配置、API Key 与对话记录会自动保留。\n\n"
                                    + "是否现在升级？")
                            .setPositiveButton("升级", (d, w) -> {
                                try {
                                    Intent i = new Intent(act, ExtractActivity.class);
                                    i.putExtra("force_extract", true);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    act.startActivity(i);
                                } catch (Throwable ignored) {
                                }
                            })
                            .setNegativeButton("忽略", (d, w) ->
                                    prefs.edit().putString("ignored_offline_version", bundled).apply())
                            .setNeutralButton("稍后", null)
                            .show();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    /** 重置配置：删除 settings.yaml + .env（保留对话记录），并重写 .env。 */
    public String resetConfig() {
        try {
            boolean any = false;
            File settings = rootfsFile("root/.dsh/settings.yaml");
            if (settings.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
                any = true;
            }
            File env = rootfsFile("root/" + getWorkdir() + "/.env");
            if (env.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                env.delete();
                any = true;
            }
            writeEnvFile();
            return any
                    ? "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
                    : "没有可重置的配置（.env 已重写）";
        } catch (Exception e) {
            return errMsg("重置失败：", e);
        }
    }

    /** 用当前 App 配置重写 rootfs 内的 .env */
    private void writeEnvFile() throws Exception {
        File env = rootfsFile("root/" + getWorkdir() + "/.env");
        if (env.getParentFile() != null) env.getParentFile().mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(env)) {
            out.write(("DEEPSEEK_API_KEY=" + cleanEnvValue(effectiveApiKey()) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void copyDir(File srcDir, File dstDir) throws Exception {
        File[] children = srcDir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                copyDir(c, new File(dstDir, c.getName()));
            } else {
                copyFile(c, new File(dstDir, c.getName()));
            }
        }
    }
    // ================= 插件控制器 =================
    /** 已装插件目录候选（out-of-tree 插件经符号链接加载）；均为 rootfs 内绝对路径。
     *  只扫 web profile（dsh plugin --profile web add 的真正安装目录）。
     *  其余（.dsh/node_modules 等）是框架依赖目录，不能当"已装插件"显示。 */
    public static final String[] PLUGIN_DIRS = {
            "/root/.dsh/profiles/web/node_modules",
    };
    private final java.util.Map<String, String[]> repoCache = new java.util.concurrent.ConcurrentHashMap<>();

    private String getVersionName() {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.1.0";
        }
    }

    /** 供 UI 层组装 User-Agent 用（如插件市场 star 刷新请求） */
    public String getVersionNameForUa() {
        return getVersionName();
    }


    /** 列出已装插件：返回 [名称, 状态(启用/禁用)] 数组（合并所有候选目录，先去重） */
    public String[][] listPlugins() {
        return listPlugins(false);
    }

    /**
     * 已装插件：manifest 的 dsh.profile.bundles（系统插件层）+ 实际声明 dsh 元数据的包（用户实装）。
     * 不把 node_modules 顶层普通依赖（react 等）误当插件；隐藏自带后仅剩用户实装。
     */
    public String[][] listPlugins(boolean hideBuiltin) {
        java.util.Set<String> builtin = hideBuiltin ? readBuiltinSnapshot() : new java.util.HashSet<>();
        try {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            names.addAll(readBundles());            // manifest dsh.profile.bundles（系统插件层）
            names.addAll(scanDshDeclaredPlugins()); // package.json 带 dsh 字段的包（用户实装）
            if (hideBuiltin) names.removeAll(builtin);
            names.removeIf(n -> n == null || n.startsWith("."));
            if (names.isEmpty()) return new String[0][];
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (String n : names) {
                list.add(new String[]{n, isPluginDisabled(n) ? "禁用" : "启用"});
            }
            return list.toArray(new String[0][]);
        } catch (Exception ignored) {
        }
        return new String[0][];
    }

    /** 读 profile package.json 的 dsh.profile.bundles（官方插件层清单） */
    private java.util.Set<String> readBundles() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return set;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            org.json.JSONObject dshObj = root.optJSONObject("dsh");
            if (dshObj == null) return set;
            org.json.JSONObject profObj = dshObj.optJSONObject("profile");
            if (profObj == null) return set;
            org.json.JSONArray bundles = profObj.optJSONArray("bundles");
            if (bundles != null) for (int i = 0; i < bundles.length(); i++) {
                String v = bundles.optString(i, "").trim();
                if (!v.isEmpty()) set.add(v);
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** 扫描所有已装包（node_modules 顶层 + .pnpm），返回 package.json 声明了 dsh 字段的包名（dsh 插件的判定标准）。
     *  注意：禁用 = 改名 .disabled，其 package.json 读不到（实体改名后路径没了 / 链接悬空）。
     *  所以 .disabled 条目用「.dsha-src 源记录 或 实体目录仍存在」判定为已装插件，
     *  否则禁用后插件从列表消失（用户反馈：禁用就原地消失）。 */
    private java.util.Set<String> scanDshDeclaredPlugins() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String d : PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            // 顶层
            java.io.File[] top = base.isDirectory() ? base.listFiles() : null;
            if (top != null) for (java.io.File f : top) {
                String n = f.getName();
                String plain = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n;
                if (plain.startsWith(".")) continue;
                if (hasDshField(f)) {
                    set.add(plain);
                } else if (n.endsWith(".disabled")) {
                    // 禁用条目：实体 package.json 读不到，但只要有 .dsha-src 源记录
                    // （togglePlugin 禁用时保存）或实体目录仍在 → 仍算已装（禁用态）
                    java.io.File srcRec = new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/profiles/web/.dsha-src-" + plain);
                    java.io.File onDir = new java.io.File(base, plain);
                    if (srcRec.isFile() || onDir.exists()) set.add(plain);
                }
            }
            // .pnpm 虚拟目录
            java.io.File pnpm = new java.io.File(base, ".pnpm");
            java.io.File[] es = pnpm.isDirectory() ? pnpm.listFiles(java.io.File::isDirectory) : null;
            if (es == null) continue;
            for (java.io.File e : es) {
                java.io.File nm = new java.io.File(e, "node_modules");
                java.io.File[] pkgs = nm.isDirectory() ? nm.listFiles() : null;
                if (pkgs == null) continue;
                for (java.io.File p : pkgs) {
                    String n = p.getName();
                    if (n.startsWith(".")) continue;
                    if (hasDshField(p)) set.add(n);
                }
            }
        }
        return set;
    }

    /** 判断包目录的 package.json 是否声明 dsh 元数据（顶层 "dsh" 对象存在） */
    private boolean hasDshField(java.io.File pkgDir) {
        try {
            java.io.File pf = new java.io.File(pkgDir, "package.json");
            if (!pf.isFile() || pf.length() > 300000) return false;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            return root.has("dsh");
        } catch (Exception e) {
            return false;
        }
    }

    /** 读 profile package.json 声明：dsh.profile.bundles + dependencies 中插件特征包 */
    private java.util.Set<String> readDeclaredPlugins() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return set;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            // dsh.profile.bundles（官方插件层列表，最权威）
            try {
                org.json.JSONObject dshObj2 = root.optJSONObject("dsh");
                org.json.JSONObject profObj2 = dshObj2 == null ? null : dshObj2.optJSONObject("profile");
                org.json.JSONArray bundles = profObj2 == null ? null : profObj2.optJSONArray("bundles");
                if (bundles != null) for (int i = 0; i < bundles.length(); i++) {
                    String v = bundles.optString(i, "").trim();
                    if (!v.isEmpty()) set.add(v);
                }
            } catch (Exception ignored) {
            }
            // dependencies 全键（dsh profile 的依赖即插件清单，与 dsh/WebUI 统计口径一致）
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps != null) {
                java.util.Iterator<String> it = deps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k != null && !k.trim().isEmpty()) set.add(k);
                }
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** 扫描 node_modules 顶层实体（目录/文件，排除隐藏项） */
    private java.util.Set<String> scanNodeModulesTop() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String d : PLUGIN_DIRS) {
            java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
            java.io.File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files == null) continue;
            for (java.io.File f : files) {
                String n = f.getName();
                if (!n.startsWith(".")) set.add(n);
            }
        }
        return set;
    }

    /** 扫描 .pnpm/ 虚拟目录里的插件包实体（pnpm 把所有包塞这里，App 之前漏掉了） */
    private java.util.Set<String> scanPnpmStore() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String d : PLUGIN_DIRS) {
            java.io.File pnpm = new java.io.File(proot.getRootfsDir(), d.substring(1) + "/.pnpm");
            if (!pnpm.isDirectory()) continue;
            java.io.File[] entries = pnpm.listFiles();
            if (entries == null) continue;
            for (java.io.File e : entries) {
                if (!e.isDirectory()) continue;
                // 形如 <name>@<ver> 或 @scope+name@<ver>
                java.io.File nm = new java.io.File(e, "node_modules");
                if (!nm.isDirectory()) continue;
                java.io.File[] pkgs = nm.listFiles();
                if (pkgs == null) continue;
                for (java.io.File p : pkgs) {
                    String n = p.getName();
                    if (!n.startsWith(".")) set.add(n);
                }
            }
        }
        return set;
    }

    /** 判断插件当前启用/禁用：存在 <name>.disabled 则禁用（顶层或 .pnpm 内精确匹配）。
     *  注意：必须用 existsOrBrokenLink（悬空链接的 File.exists() 返回 false，
     *  会把禁用误判成启用——用户反馈"切页回来按钮显示启用"）。 */
    private boolean isPluginDisabled(String name) {
        for (String d : PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            if (existsOrBrokenLink(new java.io.File(base, name + ".disabled"))) return true;
            java.io.File pnpm = new java.io.File(base, ".pnpm");
            if (!pnpm.isDirectory()) continue;
            java.io.File[] es = pnpm.listFiles(java.io.File::isDirectory);
            if (es == null) continue;
            for (java.io.File e : es) {
                java.io.File nm = new java.io.File(e, "node_modules");
                if (!nm.isDirectory()) continue;
                if (existsOrBrokenLink(new java.io.File(nm, name + ".disabled"))) return true;
            }
        }
        return false;
    }

    /** 文件条目是否存在（含悬空符号链接）：
     *  File.exists() 跟随链接，实体缺失时悬空链接返回 false —— 但禁用/启用
     *  状态由链接本身（条目）决定，悬空也要识别（否则「操作失败」）。 */
    private boolean existsOrBrokenLink(java.io.File f) {
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return true;
        } catch (Throwable ignored) {
        }
        return f.exists();
    }

    /** 读取内置插件快照（rootfs /root/dsha-builtin.txt，安装时生成）；缺失时用内置兜底名单 */
    private java.util.Set<String> readBuiltinSnapshot() {
        java.util.Set<String> set = null;
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-builtin.txt");
            if (f.isFile()) {
                set = new java.util.HashSet<>();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        String t = l.trim();
                        if (!t.isEmpty()) set.add(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 快照缺失/为空时兜底：profile 已知自带项（确保"隐藏自带"随时可用）
        if (set == null || set.isEmpty()) {
            set = new java.util.HashSet<>(java.util.Arrays.asList(
                    "@deepseek-ai", "@standard-schema", "persona-settings", "ui-scale"));
        }
        return set;
    }

    /** 校验可进入 shell 的插件 spec：npm 名 / github: / link: / file: 路径。 */
    private static boolean isValidPluginSpec(String spec) {
        if (spec == null) return false;
        String s = spec.trim();
        if (s.isEmpty() || s.length() > 256) return false;
        if (s.startsWith("github:")) {
            return s.length() > "github:".length()
                    && s.substring("github:".length()).matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
        }
        if (s.startsWith("link:") || s.startsWith("file:")) {
            return s.substring(5).matches("[A-Za-z0-9@._:/\\-]+");
        }
        return s.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+");
    }

    /** POSIX 单引号 shell 参数（转义 ' 为 '\\''）。 */
    private static String shellArg(String v) {
        if (v == null) return "''";
        return "'" + v.replace("'", "'\\''") + "'";
    }

    /** 启用/禁用插件：禁用=从 dependencies+bundles 移除声明并改名；启用=还原（避开引号嵌套：用 heredoc 临时脚本）。
     *  注意：禁用/启用状态由链接条目决定（含悬空链接）——实体缺失时 File.exists()
     *  返回 false 会导致「操作失败」，必须用 existsOrBrokenLink 判断。 */
    public boolean togglePlugin(String name, boolean enable) {
        try {
            final String PKG = "/root/.dsh/profiles/web/package.json";
            for (String d : PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                java.io.File on = new java.io.File(dir, name);
                java.io.File off = new java.io.File(dir, name + ".disabled");
                if (enable && existsOrBrokenLink(off)) {
                    String src = readPluginSrc(name);
                    // 源记录缺失：内置插件（dsh-client-ui-mobile-adapt / dsh-device-shell-guide，
                    // 注意名字不带 dsha- 前缀！旧判断 name.startsWith("dsha-") 永远不命中）
                    // 兜底回 file: 路径；普通插件兜底 "*"（包体在磁盘即可加载）
                    if (src == null || src.isEmpty() || "null".equals(src)) {
                        // 内置插件（link: 指向实体目录，与 registerMobileAdaptBundle 语义一致）
                        src = isBuiltinPlugin(name)
                                ? "link:" + builtinRealPath(name)
                                : "*";
                    }
                    if (!"*".equals(src) && !isValidPluginSpec(src)) {
                        return false; // 脏/恶意源记录：拒绝写入 dependencies
                    }
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js " + shellArg(PKG) + " " + shellArg(name)
                                    + " on " + shellArg(src) + " && " +
                            "rm -f /root/dsha-toggle.js && ( mv " + shellArg(d + "/" + name + ".disabled")
                                    + " " + shellArg(d + "/" + name) + " 2>/dev/null || touch "
                                    + shellArg(d + "/" + name) + " ) && echo OK");
                    return r != null && r.contains("OK");
                } else if (!enable) {
                    // 禁用：不依赖 on 存在（链接缺失/悬空也执行）——
                    // 移除声明 + 改名；改名失败（链接缺失）则 touch .disabled 占位，
                    // 让 ensureDeviceShellGuide/ensureBuiltinBundles 识别「用户已禁用」跳过补回。
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js " + shellArg(PKG) + " " + shellArg(name) + " off && " +
                            "rm -f /root/dsha-toggle.js && " +
                            "( mv " + shellArg(d + "/" + name) + " " + shellArg(d + "/" + name + ".disabled")
                                    + " 2>/dev/null || touch " + shellArg(d + "/" + name + ".disabled") + " ) && echo OK");
                    boolean ok = r != null && r.contains("OK");
                    if (!ok) {
                        android.util.Log.w("DSHA", "禁用插件失败 " + name + " 输出: " + (r == null ? "null" : r));
                    }
                    return ok;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 生成修改 package.json 的临时脚本（heredoc，避免嵌套引号） */
    private String toggleScript() {
        return "cat > /root/dsha-toggle.js <<'EOF'\n" +
                "const fs=require('fs');\n" +
                "const pkg=process.argv[2]||'';const pn=process.argv[3]||'';const mode=process.argv[4]||'off';const src=process.argv[5]||'';\n" +
                "if(!pkg||!pn)process.exit(1);\n" +
                "const p=JSON.parse(fs.readFileSync(pkg,'utf-8'));\n" +
                "if(!p.dependencies)p.dependencies={};\n" +
                "if(mode==='on'){\n" +
                "  if(src&&src!=='null'&&src!=='*')p.dependencies[pn]=src;\n" +
                "  if(p.dsh&&p.dsh.profile&&Array.isArray(p.dsh.profile.bundles)&&p.dsh.profile.bundles.indexOf(pn)<0)p.dsh.profile.bundles.push(pn);\n" +
                "}else{\n" +
                "  if(p.dependencies[pn])fs.writeFileSync('/root/.dsh/profiles/web/.dsha-src-'+pn,String(p.dependencies[pn]));\n" +
                "  delete p.dependencies[pn];\n" +
                "  if(p.dsh&&p.dsh.profile&&Array.isArray(p.dsh.profile.bundles))p.dsh.profile.bundles=p.dsh.profile.bundles.filter(function(x){return x!==pn;});\n" +
                "}\n" +
                "fs.writeFileSync(pkg,JSON.stringify(p,null,2));\n" +
                "EOF\n";
    }

    /** 判断是否为 App 内置插件（名称不带 dsha- 前缀！）。用于启用时依赖源兜底。 */
    private boolean isBuiltinPlugin(String name) {
        return "dsh-client-ui-mobile-adapt".equals(name)
                || "dsh-device-shell-guide".equals(name);
    }

    /** 内置插件实体目录真实路径（name ≠ 目录名：
     *  dsh-client-ui-mobile-adapt → /root/dsha-mobile-adapt；
     *  旧实现 "dsha-"+name 会拼成不存在的路径）。 */
    private String builtinRealPath(String name) {
        if ("dsh-client-ui-mobile-adapt".equals(name)) return "/root/dsha-mobile-adapt";
        if ("dsh-device-shell-guide".equals(name)) return "/root/dsha-device-shell-guide";
        return "/root/dsha-" + name;
    }

    /** 读取曾禁用的插件原安装源（启用时还原到 package.json） */
    private String readPluginSrc(String name) {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/.dsha-src-" + name);
            if (!f.isFile()) return "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                // 源记录只有一行；旧实现 `readLine()==null ? "" : readLine()` 会读两行
                // 导致永远返回第二行（通常 null）→ 启用时依赖源恢复失败，只能走 "*" 兜底
                String line = br.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 导出已启用插件为 tar.gz（Android Download/DSHA 目录，MediaStore）
     *  返回：文件路径=成功 / "NO_PLUGINS"=没有可导出插件 / null=失败 */
    public String exportPlugins() {
        try {
            // rootfs 内中转文件（先打包到 rootfs，再从宿主路径读出来拷贝到 Download）
            java.io.File outHost = new java.io.File(proot.getRootfsDir(), "root/plugins-export.tar.gz");
            final String OUT_GUEST = "/root/plugins-export.tar.gz";
            for (String d : PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                // 有可导出条目才打包（空目录/无启用插件直接跳过）
                String has = proot.execAndRead("cd '" + d + "' && ls 2>/dev/null | grep -v disabled | grep -v '^$' | head -1");
                if (has == null || has.trim().isEmpty()) continue;
                String r = proot.execAndRead(
                        "cd '" + d + "' && " +
                        "tar -czhf '" + OUT_GUEST + "' $(ls | grep -v disabled) 2>&1; echo TAR_EXIT=$?");
                if (r == null || !r.contains("TAR_EXIT=0") || !outHost.isFile()) continue;
                String name = "DSHA-plugins-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".tar.gz";
                String path = copyToDownloads(outHost, name);
                if (path != null) return path;
            }
            return "NO_PLUGINS";
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 导入插件包：先安全解压到 staging（拒绝路径穿越/符号链接/硬链接），再原子移入插件目录。
     *  只解压不注册会导致插件「列表可见但不生效」，因此解压成功后统一注册。 */
    public boolean importPlugins(java.io.File tarGz) {
        try {
            java.io.File staging = new java.io.File(proot.getRootfsDir(),
                    "root/plugins-import-stage-" + System.currentTimeMillis());
            staging.mkdirs();
            boolean moved = false;
            java.util.Set<String> importedNames = new java.util.LinkedHashSet<>();
            try {
                // 安全解压：拒绝绝对路径/..（宽松仅限备份恢复）；链接类条目一律丢弃，防止逃逸
                TarGzipExtractor.extractSafe(tarGz, staging);
                for (String d : PLUGIN_DIRS) {
                    java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                    if (!dir.isDirectory()) dir.mkdirs();
                    java.io.File[] children = staging.listFiles();
                    if (children == null) continue;
                    for (java.io.File c : children) {
                        String n = c.getName();
                        if (n.startsWith(".") || n.endsWith(".disabled")) continue;
                        if (!n.matches("[A-Za-z0-9@._+\\-]+")) continue; // 非法包名直接忽略
                        java.io.File target = new java.io.File(dir, n);
                        deleteRecursively(target); // 只删目标目录内的同名旧条目
                        boolean ok = c.renameTo(target);
                        if (!ok && c.isDirectory()) ok = copyRecursivelySafe(c, target);
                        if (ok) {
                            moved = true;
                            if (target.isDirectory() && new java.io.File(target, "package.json").isFile()) {
                                importedNames.add(n);
                            }
                        }
                    }
                }
            } finally {
                deleteRecursively(staging);
            }
            if (moved && !importedNames.isEmpty()) {
                for (String name : importedNames) {
                    registerImportedPlugin(name);
                }
                android.util.Log.i("DSHA", "插件导入完成并注册: " + importedNames);
            }
            return moved;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 只允许写入目标目录内的递归拷贝（导入 staging→final 用；拒绝跟随符号链接）。 */
    private boolean copyRecursivelySafe(java.io.File src, java.io.File dst) {
        try {
            java.nio.file.Path root = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules").toPath().toAbsolutePath().normalize();
            java.nio.file.Path target = dst.toPath().toAbsolutePath().normalize();
            if (!target.startsWith(root)) return false;
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) return false;
            if (src.isDirectory()) {
                if (!dst.isDirectory() && !dst.mkdirs()) return false;
                java.io.File[] cs = src.listFiles();
                if (cs != null) for (java.io.File c : cs) {
                    if (!copyRecursivelySafe(c, new java.io.File(dst, c.getName()))) return false;
                }
                return true;
            }
            if (src.isFile()) {
                copyFile(src, dst);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把导入的插件注册进 web profile（dependencies + bundles + node_modules 链接/实体）。
     *  幂等：已在 bundles 则跳过。与 registerMobileAdaptBundle 思路一致（不跑 pnpm，
     *  避免破坏 profile node_modules）。 */
    private void registerImportedPlugin(String name) {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
            if (!deps.has(name)) {
                // 实体已在 node_modules，用本地引用（零网络）；scoped 包名原样保留
                deps.put(name, "file:./node_modules/" + name);
            }
            // dsh.profile.bundles 追加
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject profile = dsh == null ? null : dsh.optJSONObject("profile");
            if (profile == null) {
                profile = new org.json.JSONObject();
                if (dsh == null) dsh = new org.json.JSONObject();
                dsh.put("profile", profile);
                root.put("dsh", dsh);
            }
            org.json.JSONArray bundles = profile.optJSONArray("bundles");
            if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
            boolean has = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (name.equals(bundles.optString(i, "").trim())) { has = true; break; }
            }
            if (!has) bundles.put(name);
            java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 拉取插件市场快照 JSON（GitHub API 列最新快照 → jsdelivr/raw 下载），返回 JSON 文本 */
    /** 拉取插件市场索引：PLUGINS-ALL.md（jsdelivr 优先，含多镜像）；失败时回退本地缓存 */
    public String fetchMarketIndex() {
        // 未过期缓存直接秒开（不请求网络）；失败再回退旧缓存
        String fresh = readMarketCache(true);
        if (fresh != null) return fresh;
        String[] urls = {
                gitHubProxy("https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md"),
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@master/PLUGINS-ALL.md",
                "https://gcore.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://fastly.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://ghfast.top/https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://ghproxy.net/https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/README.md"
        };
        String cached = readMarketCache(false); // 全部源失败时回退旧缓存（离线可浏览）
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 1200000) break;
                }
                conn.disconnect();
                String j = sb.toString();
                // 兼容两代索引格式：旧版折叠块（<summary><b>）/ 新版分组列表（"- `[状态]` [name](url)"，2026-08 起）
                boolean ok = j.length() > 8000 && j.indexOf("](http") >= 0
                        && (j.indexOf("- `[") >= 0
                            || (j.indexOf("<summary>") >= 0 && j.indexOf("<b>") >= 0));
                if (ok) {
                    writeMarketCache(j); // 拉成功即缓存，网络抽风时也能秒开
                    return j;
                }
            } catch (Exception ignored) {
            }
        }
        // 全部源失败：回退本地缓存（离线下仍可浏览上次成功的 1998 条）
        if (cached != null && cached.length() > 8000) return cached;
        return null;
    }

    /** 读市场索引本地缓存（App 私有目录） */
    /** 读市场索引本地缓存。freshOnly=true 仅当未超 {@link #MARKET_CACHE_TTL_MS} 才返回（过期则由调用方决定是否用旧缓存）。 */
    private String readMarketCache(boolean freshOnly) {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            if (f.isFile() && f.length() > 8000) {
                if (freshOnly && System.currentTimeMillis() - f.lastModified() > MARKET_CACHE_TTL_MS) {
                    return null; // 已过期：需要去拉取刷新
                }
                return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 市场索引缓存已有多旧（ms），供 UI 显示“缓存于 N 分钟前”；无缓存返回 -1 */
    public long getMarketCacheAgeMs() {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            if (f.isFile() && f.length() > 8000) return System.currentTimeMillis() - f.lastModified();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 强制刷新市场索引：先清本地缓存，下次拉取即走网络 */
    public void refreshMarketIndex() {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 写市场索引缓存 */
    private void writeMarketCache(String s) {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 解析市场索引（PLUGINS-ALL.md 列表式 / README 旧表格式）为 [name, star, owner, 兼容, 分类, 说明, url] */
    /** 非插件黑名单：客户端壳/桌面端/TUI/Docker/合集/启动器 等不是 dsh 插件的东西。
     * 上游 awesome-dsh-plugins 的 PLUGINS-ALL.md 是全量清单，混入了大量
     * Desktop/TUI/Docker/awesome 合集条目，不滤掉市场页会"乱拉"。
     * 只匹配条目名（desc/url 不参与，避免"支持 macOS"这类描述误伤真插件）。 */
    private static final String[] NON_PLUGIN_NAME_KEYS = {
            "desktop", "tui", "docker", "electron", "launcher",
            "windows", "macos", "swiftui"
    };

    /** 判断条目是否像"非插件"（客户端壳/合集/主仓库/市场UI）。只查名字与URL，命中即隐藏。
     * 规则分层（用真实索引 1940 条验证过）：
     * 1. 客户端壳关键词（desktop/tui/docker/...）
     * 2. 官方主仓库本体 deepseek-ai/deepseek-harness（DSH 本身不是插件）
     * 3. 市场 UI 插件（plugin-market/plugin-hub/... 几十个重复的市场入口，App 自带市场，滤掉）；
     *    注意别误伤 dsh-stock-market(股票)/dsh-webui-market-plugin(真插件)
     * 4. awesome- 合集 */
    private static boolean isLikelyNonPlugin(String name, String url, String desc) {
        String n = name.toLowerCase();
        String u = url == null ? "" : url.toLowerCase();
        // 例外：atuin（shell 历史工具插件）名字含 tui 子串，放行
        if (n.contains("atuin")) return false;
        for (String k : NON_PLUGIN_NAME_KEYS) {
            if (n.contains(k)) return true;
        }
        // 官方主仓库本体：deepseek-ai/deepseek-harness（DSH 本身不是插件）
        if (u.contains("github.com/deepseek-ai/deepseek-harness")) return true;
        // 市场 UI 插件（重复度高，App 自带市场；不含 stock-market 等真功能词）
        if (n.contains("plugin-market") || n.contains("plugins-market")
                || n.contains("plugin-hub") || n.contains("plugins-hub")
                || n.contains("plugin-store") || n.contains("dsh-market")) return true;
        // awesome- 合集（任意位置；含 plugin/skill/theme/pack 词干的可能是真插件，放行）
        if (n.contains("awesome-") && !(n.contains("plugin") || n.contains("skill")
                || n.contains("theme") || n.contains("pack"))) return true;
        return false;
    }

    public static java.util.List<String[]> parseMarketTable(String md) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (md == null) return out;
        String category = "";
        for (String raw : md.split("\n")) {
            String t = raw.trim();
            // ===== 分类：<summary><b>🎓 技能包（2）</b></summary> =====
            int b1 = t.indexOf("<b>");
            if (b1 >= 0) {
                int b2 = t.indexOf("</b>", b1);
                if (b2 > b1) {
                    String c = t.substring(b1 + 3, b2).trim();
                    c = c.replaceAll("（\\s*\\d+\\s*）$", "").replaceAll("\\(\\s*\\d+\\s*\\)$", "").trim();
                    int k = 0;
                    while (k < c.length()) {
                        int cp = c.codePointAt(k);
                        if (cp > 0x2E80) k += Character.charCount(cp); else break;
                    }
                    category = c.substring(k).trim();
                }
                continue;
            }
            // ===== 分类（新版）：## 🎓 技能包（20） =====
            if (t.startsWith("## ") || t.startsWith("### ")) {
                String c = t.replaceFirst("^#+\\s*", "").trim();
                // 只认带条目计数（N）的标题，跳过"统一度量衡/汇总"等说明性标题
                if (c.matches(".*（\\s*\\d+\\s*）$") || c.matches(".*\\(\\s*\\d+\\s*\\)$")) {
                    c = c.replaceAll("（\\s*\\d+\\s*）$", "").replaceAll("\\(\\s*\\d+\\s*\\)$", "").trim();
                    int k = 0;
                    while (k < c.length()) {
                        int cp = c.codePointAt(k);
                        if (cp > 0x2E80) k += Character.charCount(cp); else break;
                    }
                    category = c.substring(k).trim();
                }
                continue;
            }
            // ===== 条目：列表式  - `[可用]` [name](url) ★12 — desc（新版 star 无★前缀：… url) 67 — desc）=====
            if (t.startsWith("- `[")) {
                int c1 = t.indexOf('`'), c2 = t.indexOf('`', c1 + 1);
                if (c1 < 0 || c2 < 0) continue;
                String compat = t.substring(c1 + 1, c2).trim();
                int lb = t.indexOf('[', c2), rb = t.indexOf(']', lb + 1);
                if (lb < 0 || rb < 0) continue;
                String name = t.substring(lb + 1, rb).trim();
                int u1 = t.indexOf('(', rb), u2 = t.indexOf(')', u1 + 1);
                if (u1 < 0 || u2 < 0) continue;
                String url = t.substring(u1 + 1, u2).trim();
                // 有的加速代理（如 GH_PROXY）会改写内容里的链接为 <代理>/https://github.com/…，剥掉前缀还原
                int ghPos = url.indexOf("https://github.com/");
                if (ghPos > 0) url = url.substring(ghPos);
                if (!url.startsWith("http")) continue;
                String rest = t.substring(u2 + 1);
                // ★star
                String star = "0";
                int st = rest.indexOf("★");
                if (st >= 0) {
                    String sx = rest.substring(st + 1).trim();
                    int d = 0;
                    while (d < sx.length() && Character.isDigit(sx.charAt(d))) d++;
                    if (d > 0) star = sx.substring(0, d);
                } else {
                    // 新版格式：url 后直接跟裸数字 star（"…) 67 — desc"）
                    String sx = rest.trim();
                    int d = 0;
                    while (d < sx.length() && Character.isDigit(sx.charAt(d))) d++;
                    if (d > 0) star = sx.substring(0, d);
                }
                String desc = "";
                int dash = rest.indexOf("—");
                if (dash >= 0) desc = rest.substring(dash + 1).trim();
                String owner = "";
                String uu = url.replace("https://github.com/", "").replace("http://github.com/", "");
                int slash = uu.indexOf('/');
                if (slash > 0) owner = uu.substring(0, slash);
                compat = compat.replace("可用", "✅可用").replace("不兼容", "❌不兼容")
                        .replace("待定", "⏳待定").replace("未测", "⏳未测");
                if (compat.length() > 8) compat = compat.substring(0, 8);
                // 过滤非插件（桌面壳/TUI/合集等），避免市场乱拉
                if (isLikelyNonPlugin(name, url, desc)) continue;
                out.add(new String[]{name, star, owner, compat, category, desc, url});
                continue;
            }
            // ===== 条目：表格式  | [name](url) | 类型 | 兼容 | 说明 |  =====
            if (t.startsWith("| [") && t.contains("](")) {
                String[] cells = t.split("\\|");
                if (cells.length < 5) continue;
                String first = cells[1].trim();
                int lb = first.indexOf('['), rb = first.indexOf("](");
                if (lb < 0 || rb < 0) continue;
                String name = first.substring(lb + 1, rb).trim();
                int u1 = first.indexOf('('), u2 = first.lastIndexOf(')');
                if (u1 < 0 || u2 < 0) continue;
                String url = first.substring(u1 + 1, u2).trim();
                // 同上：剥掉代理改写前缀
                int ghPos2 = url.indexOf("https://github.com/");
                if (ghPos2 > 0) url = url.substring(ghPos2);
                if (!url.startsWith("http")) continue;
                String compat = cells.length > 3 ? cells[3].trim() : "";
                String desc = cells.length > 4 ? cells[4].trim() : "";
                String owner = "";
                String uu = url.replace("https://github.com/", "").replace("http://github.com/", "");
                int slash = uu.indexOf('/');
                if (slash > 0) owner = uu.substring(0, slash);
                compat = compat.replace("✅ 运行级可用", "✅可用").replace("⏳ 未测", "⏳未测")
                        .replace("❌ 运行级不兼容", "❌不兼容").replace("✅", "✅可用");
                if (compat.isEmpty() || compat.equals("插件") || compat.equals("合集")) compat = "⏳未测";
                if (compat.length() > 8) compat = compat.substring(0, 8);
                // 过滤非插件（表格格式同样适用）
                if (isLikelyNonPlugin(name, url, desc)) continue;
                out.add(new String[]{name, "0", owner, compat, category, desc, url});
            }
        }
        return out;
    }

    /** 拉取单个仓库详情（最近更新/star/作者），GitHub API 单查 + 内存缓存 */
    public String[] fetchRepoInfo(String owner, String repo) {
        if (owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) return null;
        String cacheKey = owner + "/" + repo;
        String[] cached = repoCache.get(cacheKey);
        if (cached != null) return cached;
        String[] urls = {
                "https://api.github.com/repos/" + cacheKey,
                "https://ghfast.top/https://api.github.com/repos/" + cacheKey
        };
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                // 5 个源串行回退，超时必须短：原来 8s+10s 走到底最坏要等 90 秒，
                // 界面上就表现为「点了解析没反应」（issue #4）
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String all = "";
                String line;
                while ((line = r.readLine()) != null) {
                    all += line;
                    if (all.length() > 100000) break;
                }
                conn.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(all);
                String pushed = j.optString("pushed_at", "");
                if (pushed.length() > 10) pushed = pushed.substring(0, 10);
                String[] info = new String[]{
                        pushed,
                        String.valueOf(j.optInt("stargazers_count", 0)),
                        j.optJSONObject("owner") == null ? "" : j.optJSONObject("owner").optString("login", ""),
                        j.optString("description", "")
                };
                repoCache.put(cacheKey, info);
                return info;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 从 GitHub 仓库拉取 npm 包名（package.json 的 name 字段），用于安装 */
    public String fetchNpmName(String owner, String repo) {
        if (owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) return null;
        String[] urls = {
                gitHubProxy("https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json"),
                gitHubProxy("https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/package.json"),
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/package.json",
                "https://ghfast.top/https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json"
        };
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String all = "";
                String line;
                while ((line = r.readLine()) != null) {
                    all += line;
                    if (all.length() > 50000) break;
                }
                conn.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(all);
                String name = j.optString("name", "");
                if (!name.isEmpty() && !name.contains("${")) return name;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 卸载插件：先物理清理（Java+系统rm双通道）→ dsh remove → manifest 直改兜底；结果全部回显 */
    public String removePlugin(String pkg) {
        StringBuilder log = new StringBuilder();
        try {
            if (!isValidPluginSpec(pkg)) {
                log.append("卸载失败：非法插件名（").append(pkg == null ? "null" : pkg).append("）\n");
                return log.toString();
            }
            String esc = shellArg(pkg); // 防注入（dsh remove / python argv 共用）
            // 1. 先物理清理（即使后面异常，实体也已删除）
            boolean cleared = physicalRemovePluginRobust(pkg);
            log.append("[DSHA] 实体清理").append(cleared ? "完成 ✅" : "失败（仍存在）⚠️").append("\n");
            // 2. dsh remove + manifest 直改（包名走 python argv，避免拼进 heredoc 内容）
            String py = "python3 - " + esc + " <<'PY'\n" +
                    "import json,sys\n" +
                    "p='/root/.dsh/profiles/web/package.json'\n" +
                    "pn=sys.argv[1]\n" +
                    "try:\n" +
                    " d=json.load(open(p))\n" +
                    " d.get('dependencies',{}).pop(pn,None)\n" +
                    " b=d.get('dsh',{}).get('profile',{}).get('bundles')\n" +
                    " if b: d['dsh']['profile']['bundles']=[x for x in b if x!=pn]\n" +
                    " json.dump(d,open(p,'w'),indent=2,ensure_ascii=False)\n" +
                    "except Exception as e:\n" +
                    " print('[DSHA] manifest 修改失败:',e); sys.exit(1)\n" +
                    "print('[DSHA] manifest 已移除: '+pn)\n" +
                    "PY";
            String r = proot.execAndRead(
                    "( dsh plugin --profile web remove " + esc + " 2>&1 || " +
                    "node apps/cli/lib/bin.js plugin --profile web remove " + esc + " 2>&1 || " +
                    "echo '[DSHA] dsh remove 未生效，走 manifest 直改' ) ; " +
                    py + "; echo REMOVE_EXIT=$?");
            log.append(r);
        } catch (Exception e) {
            log.append("卸载执行异常: ").append(e.getMessage());
        }
        return log.toString();
    }

    /** 双通道物理清理：Java 递归删 + 系统 rm -rf 兜底；返回是否删干净 */
    private boolean physicalRemovePluginRobust(String pkg) {
        java.io.File nm = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/node_modules");
        try {
            physicalRemovePlugin(pkg); // Java 递归删（.disabled 与 scoped 容器一并处理）
            // 双保险：系统 rm -rf（Android /system/bin/rm，绕过一切 Java/Proot 层怪问题）
            String[] targets = {pkg, pkg + ".disabled"};
            for (String t : targets) {
                java.io.File f = new java.io.File(nm, t);
                if (f.exists()) {
                    Process p = new ProcessBuilder("/system/bin/rm", "-rf", f.getAbsolutePath())
                            .redirectErrorStream(true).start();
                    p.waitFor();
                }
            }
        } catch (Throwable ignored) {
        }
        return !new java.io.File(nm, pkg).exists()
                && !new java.io.File(nm, pkg + ".disabled").exists();
    }

    /** Java 侧物理清理：删 node_modules 顶层实体(.disabled 变体) + scoped 容器 + .pnpm 模糊匹配 */
    private void physicalRemovePlugin(String pkg) {
        try {
            java.io.File nm = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/node_modules");
            if (!nm.isDirectory()) return;
            String core = pkg;
            if (pkg.startsWith("@") && pkg.contains("/")) {
                // scoped：先删容器内子包，空容器顺手删
                java.io.File container = new java.io.File(nm, pkg.substring(0, pkg.indexOf('/')));
                String sub = pkg.substring(pkg.indexOf('/') + 1);
                deleteRecursively(new java.io.File(container, sub));
                deleteRecursively(new java.io.File(container, sub + ".disabled"));
                if (container.isDirectory()) {
                    String[] left = container.list();
                    if (left == null || left.length == 0) deleteRecursively(container);
                }
                core = pkg.substring(1).replace("/", "+");
            } else {
                deleteRecursively(new java.io.File(nm, pkg));
                deleteRecursively(new java.io.File(nm, pkg + ".disabled"));
            }
            // .pnpm 模糊匹配（不管版本号变体）
            java.io.File pnpm = new java.io.File(nm, ".pnpm");
            if (pnpm.isDirectory()) {
                java.io.File[] es = pnpm.listFiles(java.io.File::isDirectory);
                if (es != null) for (java.io.File e : es) {
                    String n = e.getName();
                    if (n.equals(core) || n.startsWith(core + "@")) deleteRecursively(e);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 递归删除文件/目录（Java 侧，绕过 bash rm 的环境问题）。
     *  符号链接一律只删链接本身，禁止跟随链接递归（防恶意链接指向目录外被连带删除）。 */
    private void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        if (f.isDirectory()) {
            java.io.File[] cs = f.listFiles();
            if (cs != null) for (java.io.File c : cs) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 安装插件：优先源码目录（node bin.js），无源码目录自动回退全局 dsh；依赖自愈前置 */
    public String installPlugin(String pkg) {
        return installPlugin(pkg, null);
    }

    /** 已经内置在 APK 里的插件（用 link: 指向本地目录注册，不走网络）。
     *  再从 GitHub 装一份同名的，只会和内置版本抢同一个包名，还得走 git-hosted
     *  那条最容易出错的路（clone → 跑 prepare → 硬链接进 store）。 */
    private static final String[] BUILTIN_PLUGIN_NAMES = {
            "dsh-client-ui-mobile-adapt",
            "dsh-device-shell-guide",
            "dsh-task-notifier",
    };

    private static String builtinPluginHit(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        String s = spec.toLowerCase(java.util.Locale.ROOT);
        for (String n : BUILTIN_PLUGIN_NAMES) {
            if (s.contains(n)) return n;
        }
        return null;
    }

    /** pnpm 在容器里最常见的一类失败：git-hosted 包在 store 的临时目录里
     *  clone/prepare 时 ENOENT。根子是 proot 下硬链接只能靠 --link2symlink 模拟。 */
    private static boolean isPnpmEnvFailure(String out) {
        if (out == null) return false;
        if (out.contains("Failed to prepare git-hosted package")) return true;
        boolean storePath = out.contains("pnpm/store") || out.contains("/store/v");
        return storePath && (out.contains("ENOENT") || out.contains("EEXIST"));
    }

    /**
     * 安装插件（带 GitHub 兜底）：先按 pkg 装（npm 名），若 404/找不到包 且给了 fallbackSpec，
     * 自动用 github:owner/repo 重试一次（市场条目多为仅 GitHub 发布的仓库插件）。
     */
    public String installPlugin(String pkg, String fallbackSpec) {
        if (!isValidPluginSpec(pkg)) {
            return "安装失败：非法插件名/来源：" + (pkg == null ? "null" : pkg);
        }
        // 内置插件直接劝退：装了只会打架，而且白踩一次 git-hosted 的坑
        String builtin = builtinPluginHit(pkg);
        if (builtin == null) builtin = builtinPluginHit(fallbackSpec);
        if (builtin != null) {
            return "无需安装：" + builtin + " 已经内置在 DSHA 里。\n\n"
                    + "它每次启动都会自动注册（本地 link:，不走网络），再从 GitHub 装一份"
                    + "同名插件会和内置版本抢同一个包名。\n"
                    + "如果市场里显示它未安装，去「配置」页点「重启 Web」让内置版本生效即可。";
        }
        String r = runPluginInstall(pkg);
        // ENOENT 这类是环境问题而不是包的问题：修掉硬链接配置与残留后重试一次，
        // 多数情况这一次就过了（dsh 那句「去改 allowBuilds」是误导，跟本错无关）
        if (isPnpmEnvFailure(r)) {
            String fix = runAssetScript("pnpm-env-fix.sh", "dsha-pnpm-env-fix.sh", 60_000);
            r = r + "\n\n[检测到 pnpm 环境问题（proot 下硬链接只能模拟），已修复并重试]\n"
                    + (fix == null ? "" : fix.trim() + "\n") + runPluginInstall(pkg);
        }
        if (r != null && fallbackSpec != null && !fallbackSpec.equals(pkg)
                && isPkgNotFound(r)) {
            if (!isValidPluginSpec(fallbackSpec)) {
                r += "\n[自动回退被忽略：非法来源 " + fallbackSpec + "]";
            } else {
                r = "\n[自动回退 GitHub 仓库方式安装…]\n" + runPluginInstall(fallbackSpec);
            }
        }
        if (r != null && r.contains("INSTALL_EXIT=0")) {
            return r + "\n\n[已安装到 profile，重启 WebUI 生效]";
        }
        return r == null ? "无输出" : r;
    }

    /** 解析 GitHub 仓库链接为插件信息（不安装，供市场列表展示）：
     *  返回 String[3] {npm名, owner/repo, 仓库URL}；无法解析返回 null。
     *  链接格式：https://github.com/owner/repo、owner/repo、带 .git 后缀等。 */
    public String[] parseGithubUrl(String url) {
        try {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) return null;
            String core = u;
            int g = core.indexOf("github.com/");
            if (g >= 0) core = core.substring(g + "github.com/".length());
            int q = core.indexOf('?'); if (q >= 0) core = core.substring(0, q);
            int h = core.indexOf('#'); if (h >= 0) core = core.substring(0, h);
            while (core.endsWith("/")) core = core.substring(0, core.length() - 1);
            core = core.replaceFirst("\\.git$", "");
            int slash = core.indexOf('/');
            if (slash <= 0) return null;
            String owner = core.substring(0, slash).trim();
            String repo = core.substring(slash + 1).trim();
            int r2 = repo.indexOf('/');
            if (r2 >= 0) repo = repo.substring(0, r2);
            if (owner.isEmpty() || repo.isEmpty()) return null;
            return new String[]{null, owner + "/" + repo, "https://github.com/" + owner + "/" + repo};
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从 GitHub 仓库链接安装插件（插件市场顶部入口）：
     *  解析 URL → owner/repo → 拉 package.json 拿 npm 名 → 安装（npm 名找不到回退 github: 方式）。
     *  返回执行输出。链接格式支持：https://github.com/owner/repo、owner/repo、带 .git 后缀等。 */
    public String installFromGithubUrl(String url) {
        try {
            String u = url == null ? "" : url.trim();
            String core = u;
            int g = core.indexOf("github.com/");
            if (g >= 0) core = core.substring(g + "github.com/".length());
            // 去掉 git 后缀 / 尾部斜杠 / 多余路径 / 查询参数 / 锚点（先去尾部斜杠再 .git）
            int q = core.indexOf('?'); if (q >= 0) core = core.substring(0, q);
            int h = core.indexOf('#'); if (h >= 0) core = core.substring(0, h);
            while (core.endsWith("/")) core = core.substring(0, core.length() - 1);
            core = core.replaceFirst("\\\\.git$", "");
            int slash = core.indexOf('/');
            if (slash <= 0) return "无法解析仓库链接：" + url + "\n格式应为 https://github.com/owner/repo";
            String owner = core.substring(0, slash).trim();
            String repo = core.substring(slash + 1).trim();
            // 去掉 repo 后的路径（如 /tree/main）
            int r2 = repo.indexOf('/');
            if (r2 >= 0) repo = repo.substring(0, r2);
            if (owner.isEmpty() || repo.isEmpty()) return "无法解析仓库链接：" + url;
            // 拉 npm 包名
            String npmName = fetchNpmName(owner, repo);
            if (npmName == null) {
                return "未在该仓库找到 package.json / npm 包名，可能未发布 npm。\n"
                        + "仓库：" + owner + "/" + repo + "\n"
                        + "只能源码安装：dsh plugin --profile web add github:" + owner + "/" + repo;
            }
            // 安装（npm 名 + github 兜底）
            return installPlugin(npmName, "github:" + owner + "/" + repo);
        } catch (Throwable e) {
            return "安装失败: " + e.getMessage();
        }
    }

    /** 判定安装输出是否为"包在 registry 找不到"（npm 404 类） */
    private boolean isPkgNotFound(String out) {
        return out.contains("ERR_PNPM_FETCH") || out.contains("not in the npm registry")
                || out.contains("404") || out.contains("ENOTFOUND");
    }

    /** 单次插件安装执行（源码目录优先，无则全局 dsh）；pkg 已由入口校验，这里再兜一道。 */
        private String runPluginInstall(String pkg) {
            try {
                if (!isValidPluginSpec(pkg)) return "安装失败：非法插件名/来源：" + (pkg == null ? "null" : pkg);
                String wd = detectWorkdir();
                String arg = shellArg(pkg);
                return proot.execAndRead(
                        "if [ -d /root/" + wd + " ]; then cd /root/" + wd + "; " + depsSelfHeal() +
                        "printf 'registry=https://registry.npmmirror.com\\\\n' > /root/.npmrc; " +
                        "( node apps/cli/lib/bin.js plugin --profile web add " + arg
                                + " 2>&1 || dsh plugin --profile web add " + arg + " 2>&1 ); " +
                        "else echo '[DSHA] 无源码目录，回退全局 dsh'; " +
                        "printf 'registry=https://registry.npmmirror.com\\\\n' > /root/.npmrc; " +
                        "dsh plugin --profile web add " + arg + " 2>&1; fi | tail -15; echo INSTALL_EXIT=${PIPESTATUS[0]}");
            } catch (Exception e) {
                return "安装失败: " + e.getMessage();
            }
        }


    private String copyToDownloads(java.io.File src, String name) {
        // 方案1：MediaStore（Android 10+ 免权限）
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/DSHA");
            android.net.Uri uri = appContext.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (java.io.OutputStream os = appContext.getContentResolver().openOutputStream(uri)) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
                return android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS) + "/DSHA/" + name;
            }
        } catch (Exception ignored) {
        }
        // 方案2：All files access 直写（Android 11+ 授权后）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "DSHA");
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File dst = new java.io.File(dir, name);
                    copyFile(src, dst);
                    return dst.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    // ================= 插件控制器结束 =================

}
