package com.deepseekharness.app;

import android.content.Context;
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

    public long getWebEpoch() { return webEpoch; }
    public void bumpWebEpoch() { webEpoch = System.currentTimeMillis(); }
    public long getHardRestartEpoch() { return hardRestartEpoch; }
    public void bumpHardRestart() { hardRestartEpoch = System.currentTimeMillis(); }
    public boolean isWebRestartLocked() { return webRestartLock.get(); }
    public boolean tryAcquireWebRestartLock() { return webRestartLock.compareAndSet(false, true); }
    public void releaseWebRestartLock() { webRestartLock.set(false); }

    /** 端口探测：ms 超时内是否可连接 Web 端口 */
    private boolean isWebPortUp(int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", parsePort()), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
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
                proot.execAndRead("pkill -9 -f node 2>/dev/null; pkill -9 -f 'dsh web' 2>/dev/null; "
                        + "pkill -9 -f 'bin.js' 2>/dev/null; sleep 1; echo done");
                waitPortClosed(5000);
            }
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
                boolean up = isWebPortUp(600);
                return up
                        ? "Web 服务正在运行但页面探测失败，可点「打开预览」或「重启」再试。"
                        : "Web 启动过（已打印 URL）但端口 3080 未就绪：\n可能原因：启动中 / 端口被占 / 依赖加载卡住。\n请稍等或「重启」；仍不行请查看 ~/dsh-web.log 完整内容。";
            }
            return tail.isEmpty() ? "WebUI 异常退出（日志为空）" : "WebUI 异常退出：\n" + tail;
        } catch (Exception e) {
            return "无法解析 WebUI 日志：" + e.getMessage();
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

    /** 使步骤缓存失效：安装结束/空闲时调用，让 UI 拿到最新状态 */
    private void invalidateStepCache() {
        stepCacheTs = -1;
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
    public boolean isBusy() { return busy; }
    /** 当前正在执行的步骤（0 = 空闲） */
    public int getCurrentStep() { return currentStep; }

    private void setState(String stage, int percent, String msg, String err, boolean b) {
        this.stage = stage;
        this.percent = percent;
        this.message = msg;
        this.error = err;
        this.busy = b;
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
    public void setPort(String v) { prefs.edit().putString("port", v).apply(); }

    public String getModel() { return prefs.getString("model", "deepseek-v4-flash"); }
    public void setModel(String v) { prefs.edit().putString("model", v).apply(); }

    public String getPermissionMode() { return prefs.getString("permission_mode", "danger-full-access"); }
    public void setPermissionMode(String v) { prefs.edit().putString("permission_mode", v).apply(); }

    public String getWorkdir() { return prefs.getString("workdir", "deepseek-harness"); }
    public void setWorkdir(String v) { prefs.edit().putString("workdir", v).apply(); }

    /** 局域网模式是否开启（App 设置项） */
    public boolean isLanMode() {
        return prefs.getBoolean("lan_mode", false);
    }

    /** rootfs 绝对路径（供桥等写日志） */
    public String getRootfsDirPath() {
        return proot.getRootfsDir().getAbsolutePath();
    }

    /** 实际工作目录自愈：prefs 指定名不存在时，扫描 rootfs /root 下含 apps/cli/lib/bin.js 的目录并回写 */
    public String detectWorkdir() {
        String wd = getWorkdir();
        if (workdirExists(wd)) return wd;
        try {
            java.io.File root = new java.io.File(proot.getRootfsDir(), "root");
            java.io.File[] dirs = root.isDirectory() ? root.listFiles(java.io.File::isDirectory) : null;
            if (dirs != null) for (java.io.File d : dirs) {
                if (new java.io.File(d, "apps/cli/lib/bin.js").isFile()
                        || new java.io.File(d, "lib/bin.js").isFile()) {
                    prefs.edit().putString("workdir", d.getName()).apply();
                    return d.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return wd;
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
        try {
            String script = readAsset("webui-degrade-patch.sh");
            if (script == null || script.isEmpty()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-degrade.sh");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            proot.execAndRead("bash /root/dsha-degrade.sh; rm -f /root/dsha-degrade.sh");
        } catch (Throwable ignored) {
        }
    }

    public void maybePrewarmWeb() {
        try {
            ensureWebUiDegrade(); // 每次启动前置自愈（幂等秒回，防插件失败卡启动）
        } catch (Throwable ignored) {
        }
        try {
            if (!proot.isInstalled() || !isHarnessInstalled()) return; // 环境/harness 未装
            if (webProcess != null && webProcess.isAlive()) return;    // 已在运行
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
        if (busy) return;
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
        if (busy) return;
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
        boolean r2 = toolsInstalled();
        boolean r3 = new File(proot.getRootfsDir(), "usr/local/bin/node").exists()
                && new File(proot.getRootfsDir(), "usr/local/bin/npm").exists();
        boolean r4 = pnpmExtrasReady();
        boolean r5 = isHarnessReady();
        boolean r6 = guardReady();
        synchronized (stepCache) {
            stepCache[STEP_ROOTFS] = r1;
            stepCache[STEP_TOOLS] = r2;
            stepCache[STEP_NODE] = r3;
            stepCache[STEP_PNPM] = r4;
            stepCache[STEP_HARNESS] = r5;
            stepCache[STEP_GUARD] = r6;
            stepCache[0] = false;
            stepCacheTs = System.currentTimeMillis();
            return stepCache.clone();
        }
    }

    /** 第 4 步完成判定：已装 + node-pty 就绪。RC6 用一次 proot 进程查完（省一次子进程，降低卡顿） */
    private boolean isHarnessReady() {
        if (useRc6()) {
            try {
                String r = proot.execAndRead(
                        "command -v dsh >/dev/null 2>&1 && " +
                        "(find /usr/local/lib/node_modules -maxdepth 8 \\( -path '*/node-pty/build/Release/pty.node' -o -path '*/node-pty/prebuilds/linux-arm64/pty.node' \\) 2>/dev/null | head -1) || echo MISSING");
                return r != null && !r.startsWith("ERROR") && !r.contains("MISSING") && !r.trim().isEmpty();
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
        ensureDangerGuard();   // PATH 包装器（rm/adb 等 15 命令）
        ensureBashGuardPatch(); // bash 工具 lib 强制加载 dsh-guard
        try {
            proot.ensureRuntimeFiles(); // polyfill / 运行环境文件
        } catch (Throwable ignored) {
        }
        ensureWatchdogFiles();  // 看门狗 + 重启命令（最新端口）
        // ===== 原生内置移动端 UI 适配（免第三方插件） =====
        // 把 dsh-client-ui-mobile-adapt 的 client 产物直接注入 web-app 前端，
        // 手机端单栏/抽屉/汉堡/全屏设置开箱即用。幂等，失败不阻塞安装。
        try {
            ensureNativeMobileAdapt();
        } catch (Throwable ignored) {
        }
        // 内置插件快照：只录实体目录（排除符号链接=用户安装插件），安装完成时最干净基线
        // 快照缺失时才生成（后续沿用；想重扫可删 /root/dsha-builtin.txt）
        runStep("生成内置插件快照", 98,
                "if [ ! -f /root/dsha-builtin.txt ]; then " +
                "find /root/.dsh/profiles/web/node_modules/ -maxdepth 1 \\( -type d -o -type f \\) ! -type l 2>/dev/null " +
                "| sed 's|.*/||' | grep -v '^\\.' | grep -v '\\.disabled$' > /root/dsha-builtin.txt; " +
                "echo '内置快照：'$(wc -l < /root/dsha-builtin.txt 2>/dev/null)' 项'; " +
                "else echo '内置插件快照已存在，沿用'; fi");
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
        runStep("安装 Node.js", 88,
                "cd /tmp && (tar -xJf node.tar.xz -C /usr/local --strip-components=1 || "
                        + "(echo '安装包损坏，自动重新下载…'; rm -f node.tar.xz; "
                        + "curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz && "
                        + "tar -xJf node.tar.xz -C /usr/local --strip-components=1))");
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

    private void installHarnessRc6() throws Exception {
        requireRootfs();
        requireTools();
        setProgress("安装 deepseek-harness 最新 RC（npm 全局）", 91);
        runStep("RC6 安装环境准备", 92,
                "npm config set allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs --location=user 2>/dev/null; " +
                "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc");
        runStep("安装 @deepseek-ai/dsh 最新 RC", 95,
                "npm install -g @deepseek-ai/dsh@rc --force --registry=https://registry.npmmirror.com 2>&1 | tail -25; " +
                "echo \">> npm 退出码: ${PIPESTATUS[0]}\"; " +
                "if [ \"${PIPESTATUS[0]}\" != 0 ]; then echo 'npm 安装失败，请重试或检查网络'; fi");
        runStep("编译 node-pty 原生模块", 98,
                "node-gyp --version >/dev/null 2>&1 || npm install -g node-gyp --registry=https://registry.npmmirror.com 2>&1 | tail -2; " +
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
                "ls \"$npty_dir/build/Release/pty.node\" >/dev/null 2>&1 && echo 'pty.node 已就绪' && command -v dsh && echo 'RC6 安装完成'");
        setProgress("RC6 安装完成", 100);
    }

    /** 直连 GitHub 源码构建（clone 多通道 fallback + npmmirror 依赖/headers 源） */
    private void installHarnessFromSource() throws Exception {
        if (useRc6()) {
            installHarnessRc6();
            return;
        }
        String wd = getWorkdir();
        String apiKey = effectiveApiKey();

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
                        + "cd /tmp && tar -xJf node.tar.xz -C /usr/local --strip-components=1 && "
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
                "(curl -kfsSL --retry 3 -m 300 " + gitHubProxy("https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main") + " -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghfast.top/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://gh-proxy.com/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghproxy.net/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/main -o dsh-src.tar.gz) && " +
                "tar -xzf dsh-src.tar.gz && mv deepseek-harness-main " + wd + " && rm -f dsh-src.tar.gz); fi");

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

        setProgress("安装依赖 pnpm install（npmmirror 源）", 95);
        try {
            // 注意：npm 11 的 `npm config set disturl` 会报 "not a valid npm option"，
            // 所以直接写 .npmrc 文件 + 环境变量（不经过 npm 配置校验）
            runStep("安装依赖 pnpm install", 95,
                    "cd /root/" + wd + " && " +
                    "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc && " +
                    "pnpm install");
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

        runStep("写入 API key", 99,
                "cd /root/" + wd + " && printf 'DEEPSEEK_API_KEY=%s\\n' '" + apiKey + "' > .env");
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
            try {
                proot.downloadRootfs(url, dest, (down, total) -> {
                    if (total <= 0) {
                        setProgress(what + "…（源未提供大小，请耐心等待）", Math.min(99, pBase + 1));
                    } else {
                        int pct = (int) (down * 100 / total);
                        setProgress(what + " " + fmtBytes(down) + "/" + fmtBytes(total) + "（" + pct + "%）（源：" + hostOf(url) + "）",
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
        for (int i = 1; pendingLat != null && i < pendingLat.length; i++) {
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
        setProgress(stage, percent);
        String fullCmd = "(" + cmd + ") >/root/dsh-step.log 2>&1"
                + " || { echo '--- 日志尾部 ---'; tail -100 /root/dsh-step.log; exit 1; }";
        proot.execChecked(fullCmd);
    }

    // ================= 脚本与命令 =================
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
        // 局域网访问：deepseek-harness 官方 CLI 默认拒绝 --host 0.0.0.0，
        // 需先打 lan-bind-patch.sh 放行（失败则回落到 127.0.0.1，服务保证能起）。
        boolean lan = appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        boolean lanReady = lan && tryEnableLanBind();
        StringBuilder sb = new StringBuilder();
        sb.append("export DSH_HOME=/root/.dsh && ")
          .append("export DEEPSEEK_API_KEY='").append(effectiveApiKey()).append("' && ")
          .append("export DSH_PERMISSION_MODE=").append(getPermissionMode()).append(" && ")
          // 危险命令确认：agent 在 rootfs 内的 rm/dd 等操作需用户确认
          // PATH 包装器 + bash 工具 lib 补丁加载守卫（双保险；不设 BASH_ENV——它会污染
          // RC6 插件初始化时子 shell 的环境，导致 dsh web 加载插件失败(index 24 崩溃)）
          .append("export DSH_CONFIRM=1 && ")
          // 预创建常见插件数据目录（防止插件扫描空目录崩溃拖垮 WebUI）
          .append("mkdir -p /root/.codex/pets /root/.dsh/plugins 2>/dev/null; ")
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
                + "if [ -d /root/" + wd + " ]; then cd /root/" + wd + "; " + depsSelfHeal()
                + "exec node apps/cli/lib/bin.js web" + opts + " > ~/dsh-web.log 2>&1; "
                + "else echo '[DSHA] 源码目录缺失，尝试全局 dsh'; "
                + "if command -v dsh >/dev/null 2>&1 && test -f \"$(command -v dsh)\"; then "
                + "exec dsh web" + opts + " > ~/dsh-web.log 2>&1; "
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
        writeWatchdogFiles(runCoreCommand(lan), parsePort());
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
                    "export DEEPSEEK_API_KEY='" + effectiveApiKey() + "'\n" +
                    "export DSH_PERMISSION_MODE=" + getPermissionMode() + "\n" +
                    "export DSH_CONFIRM=1\n" +
                    "cd /root/" + getWorkdir() + " || exit 1\n" +
                    "mkdir -p /root/.codex/pets /root/.dsh/plugins 2>/dev/null\n" +
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
                    "      sleep 2\n" +
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
            // 1) 往 rootfs 写注入脚本（heredoc 防引号问题）
            String script =
                    "set -e; " +
                    "DST=$(find /usr/local/lib/node_modules /root -maxdepth 14 " +
                    "  \\( -path '*dsh-web-app/dist*/client' -o -path '*dsh-web-app/lib/client' \\) " +
                    "  -type d 2>/dev/null | head -1); " +
                    "if [ -z \"$DST\" ]; then echo '[DSHA] 未找到 web-app client 目录，跳过移动端适配'; exit 0; fi; " +
                    "echo \"[DSHA] 注入移动端适配 -> $DST\"; " +
                    "cp -f /root/dsha-mobile-adapt/client.js \"$DST/dsh-client-ui-mobile-adapt.js\" && " +
                    "touch /root/dsha-mobile-adapt-installed && echo OK";
            java.io.File sF = new java.io.File(proot.getRootfsDir(), "root/dsha-mobile-inject.sh");
            java.io.File aDir = new java.io.File(proot.getRootfsDir(), "root/dsha-mobile-adapt");
            aDir.mkdirs();
            // 2) 把 assets 里的 client.js / index.js / cordis.patch.yml 写进 rootfs
            writeAssetTo("mobile-adapt/client.js", new java.io.File(aDir, "client.js"));
            writeAssetTo("mobile-adapt/index.js", new java.io.File(aDir, "index.js"));
            writeAssetTo("mobile-adapt/cordis.patch.yml", new java.io.File(aDir, "cordis.patch.yml"));
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

    /** profile 注册移动端适配 bundle：dependencies + dsh.profile.bundles（幂等） */
    private void registerMobileAdaptBundle() {
        try {
            final String NAME = "dsh-client-ui-mobile-adapt";
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            // dependencies 加 file: 指向我们注入的目录（零网络）
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
            if (!deps.has(NAME)) {
                deps.put(NAME, "file:/root/dsha-mobile-adapt");
            }
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
        } catch (Throwable ignored) {
        }
    }

    /** 把 assets 内文本资源写入 rootfs 指定文件（目录自动建） */
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
        if (lanBindReady) return true;
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
        return "pkill -f dsh-watchdog.sh 2>/dev/null; "
             + "pkill -f 'bin.js web' 2>/dev/null; pkill -f 'dsh web' 2>/dev/null; echo stopped";
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
        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=$HOME/dsh-bin:$PATH && ")
          .append("cd ~/").append(getWorkdir()).append(" && ")
          .append("export DEEPSEEK_API_KEY=\"").append(effectiveApiKey()).append("\" && ")
          .append("export DSH_PERMISSION_MODE=\"").append(getPermissionMode()).append("\" && ")
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
    private static final String GUARD_VERSION = "8";

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

    /** 给已构建的 bash 工具 lib 直接打补丁（强制每次执行前加载守卫，不依赖重新 build）
     *  失败时写 /root/dsh-guard-patch.log（不影响启动，日志可查） */
    public void ensureBashGuardPatch() {
        try {
            // RC6（npm 全局安装，依赖可能是嵌套或扁平布局，用 find 通配兼容两种）；
            // 源码版：packages/shell/bash-local
            String wd = getWorkdir();
            proot.execAndRead(
                    "F=$(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-bash-local/lib/index.js' 2>/dev/null | head -1); " +
                    "if [ -z \"$F\" ]; then F=/root/" + wd + "/packages/shell/bash-local/lib/index.js; fi; " +
                    "if [ ! -f \"$F\" ]; then echo \"守卫补丁: 未找到 bash 工具 lib\" > /root/dsh-guard-patch.log; " +
                    "elif grep -q 'dsh-guard' \"$F\"; then echo LIB_ALREADY; " +
                    "else sed -i 's|command: request\\.command|command: `source /root/dsh-guard.sh 2>/dev/null; ${request.command}`|' \"$F\" " +
                    "&& grep -q 'dsh-guard' \"$F\" && echo LIB_PATCHED || echo \"守卫补丁: patch 失败\" > /root/dsh-guard-patch.log; fi");
        } catch (Exception ignored) {
        }
    }

    public void startWeb() {
        synchronized (webStartLock) {
            if (webProcess != null && webProcess.isAlive()) {
                return; // 已在运行，避免重复启动
            }
            if (webStarting) return; // 已有启动在进行（防 keepAlive/手动并发起第二个实例 → EADDRINUSE）
            webStarting = true;
        }
        IO.execute(() -> {
            try {
                // 启动前预检：端口仍被占 → 深杀残留（根治 EADDRINUSE）
                if (isWebPortUp(400)) {
                    destroyAllWebProcesses();
                    proot.execAndRead(stopWebCommand());
                    if (!waitPortClosed(4000)) {
                        proot.execAndRead("pkill -9 -f node 2>/dev/null; pkill -9 -f 'bin.js' 2>/dev/null; sleep 1; echo done");
                        waitPortClosed(4000);
                    }
                }
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
                // 阻塞读取输出，保持 proot+node 进程存活（后台 nohup 会被 --kill-on-exit 杀掉）
                String out = proot.drainOutput(p);
                // 输出已重定向到 ~/dsh-web.log，stdout 为空时抓「Error 块 + 尾部」
                if (out == null || out.trim().isEmpty()) {
                    out = proot.execAndRead(
                            "awk 'NR>=1 && /Error: dsh:/{f=1} f{print; n++} f && n>45{exit}' ~/dsh-web.log | head -45; " +
                            "echo '[--- 日志头部---]'; head -3 ~/dsh-web.log 2>/dev/null; " +
                            "echo '[--- 日志尾部 ---]'; " +
                            "L=$(grep -nm1 'Error: dsh:' ~/dsh-web.log | cut -d: -f1); " +
                            "if [ -n \"$L\" ] && [ \"$L\" -gt 50 ]; then sed -n \"$((L-8)),$((L))p\" ~/dsh-web.log 2>/dev/null; fi; " +
                            "tail -c 500 ~/dsh-web.log 2>/dev/null");
                }
                String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
                setState("", 0, "", "Web UI 意外退出：\n" + tail, false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("启动出错：", e), false);
            }
        });
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
    public String backupConfig() {
        try {
            File backupRoot = new File(appContext.getFilesDir(), "backup");
            File dir = new File(backupRoot, "config-" +
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(new java.util.Date()));
            dir.mkdirs();
            int n = 0;
            File env = rootfsFile("root/" + getWorkdir() + "/.env");
            if (env.isFile()) {
                copyFile(env, new File(dir, "env-" + getWorkdir() + ".txt"));
                n++;
            }
            File dsh = rootfsFile("root/.dsh");
            if (dsh.isDirectory()) {
                copyDir(dsh, new File(dir, "dsh"));
                n++;
            }
            return n > 0 ? dir.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
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
        if (last > 0) { // 真升级（非全新安装）：后台自动备份旧环境
            IO.execute(() -> {
                try {
                    // rootfs 已就绪（有 bash）才备份；未解压/未安装时跳过
                    if (proot.isInstalled() && rootfsFile("root/.dsh").isDirectory()) {
                        String p = BackupManager.backupToExternal(appContext, HarnessController.this);
                        if (p != null) android.util.Log.i("DSHA", "升级自动备份完成: " + p);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        return true;
    }

    /** 外部下载目录 Download/DSHA 里最新的 DSHA 备份；没有返回 null */
    public File findLatestExternalBackup() {
        try {
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "DSHA");
            File[] fs = dir.listFiles((d, n) -> n.startsWith("DSHA-backup-") && n.endsWith(".tar.gz"));
            if (fs == null || fs.length == 0) return null;
            File best = null;
            for (File f : fs) {
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从外部备份 tar.gz 恢复 .dsh + .env 到 rootfs；返回结果文案 */
    public String restoreFromBackup(File backup) {
        try {
            if (!proot.isInstalled()) return "环境未就绪，请先完成环境解压/安装后再恢复";
            File tmp = rootfsFile("root/.dsha-restore.tar.gz");
            copyFile(backup, tmp);
            TarGzipExtractor.extract(tmp, new File(proot.getRootfsDir(), "root"));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return "恢复完成（配置 + 对话记录），重启 WebUI 生效";
        } catch (Exception e) {
            return "恢复失败: " + e.getMessage();
        }
    }

    /**
     * 全新环境检测：若 rootfs 尚无数据（卸载重装后的空环境）且 Download/DSHA 存在旧备份，
     * 弹窗询问是否恢复。仅在 App 升级/首次启动时由调用方触发。
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
            out.write(("DEEPSEEK_API_KEY=" + effectiveApiKey() + "\n")
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
            org.json.JSONArray bundles = new org.json.JSONObject(txt)
                    .optJSONObject("dsh").optJSONObject("profile").optJSONArray("bundles");
            if (bundles != null) for (int i = 0; i < bundles.length(); i++) {
                String v = bundles.optString(i, "").trim();
                if (!v.isEmpty()) set.add(v);
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** 扫描所有已装包（node_modules 顶层 + .pnpm），返回 package.json 声明了 dsh 字段的包名（dsh 插件的判定标准） */
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
                if (hasDshField(f)) set.add(plain);
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
                org.json.JSONArray bundles = root.optJSONObject("dsh")
                        .optJSONObject("profile").optJSONArray("bundles");
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

    /** 判断插件当前启用/禁用：存在 <name>.disabled 则禁用（顶层或 .pnpm 内精确匹配） */
    private boolean isPluginDisabled(String name) {
        for (String d : PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            if (new java.io.File(base, name + ".disabled").exists()) return true;
            java.io.File pnpm = new java.io.File(base, ".pnpm");
            if (!pnpm.isDirectory()) continue;
            java.io.File[] es = pnpm.listFiles(java.io.File::isDirectory);
            if (es == null) continue;
            for (java.io.File e : es) {
                java.io.File nm = new java.io.File(e, "node_modules");
                if (!nm.isDirectory()) continue;
                if (new java.io.File(nm, name + ".disabled").exists()) return true;
            }
        }
        return false;
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

    /** 启用/禁用插件：禁用=从 dependencies+bundles 移除声明并改名；启用=还原（避开引号嵌套：用 heredoc 临时脚本） */
    public boolean togglePlugin(String name, boolean enable) {
        try {
            final String PKG = "/root/.dsh/profiles/web/package.json";
            for (String d : PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                java.io.File on = new java.io.File(dir, name);
                java.io.File off = new java.io.File(dir, name + ".disabled");
                if (enable && off.exists()) {
                    String src = readPluginSrc(name);
                    // 原安装源记录缺失（比如禁用时 dependencies 里本来就没有该项）时兜底 "*"：
                    // 包体还在磁盘上，声明恢复即可加载，绝不能让插件"禁用后再也开不回来"
                    if (src == null || src.isEmpty() || "null".equals(src)) src = "*";
                    String safe = src.replace("'", "\\'");
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js '" + PKG + "' '" + name + "' on '" + safe + "' && " +
                            "rm -f /root/dsha-toggle.js && " +
                            "mv '" + d + "/" + name + ".disabled' '" + d + "/" + name + "' && echo OK");
                    return r != null && r.contains("OK");
                } else if (!enable && on.exists()) {
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js '" + PKG + "' '" + name + "' off && " +
                            "rm -f /root/dsha-toggle.js && " +
                            "mv '" + d + "/" + name + "' '" + d + "/" + name + ".disabled' && echo OK");
                    return r != null && r.contains("OK");
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

    /** 读取曾禁用的插件原安装源（启用时还原到 package.json） */
    private String readPluginSrc(String name) {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/.dsha-src-" + name);
            if (!f.isFile()) return "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = br.readLine(); // 只能调一次：readLine 会消费流，连续调两次第二次必是 null
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

    /** 导入插件包：解压到插件目录（rootfs 内中转） */
    public boolean importPlugins(java.io.File tarGz) {
        try {
            java.io.File tmpHost = new java.io.File(proot.getRootfsDir(), "root/plugins-import.tar.gz");
            copyFile(tarGz, tmpHost);
            final String TMP_GUEST = "/root/plugins-import.tar.gz";
            for (String d : PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) dir.mkdirs();
                String r = proot.execAndRead(
                        "cd '" + d + "' && tar -xzf '" + TMP_GUEST + "' 2>/dev/null && echo OK");
                if (r != null && r.contains("OK")) {
                    //noinspection ResultOfMethodCallIgnored
                    tmpHost.delete();
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
            String esc = pkg.replace("'", "'\\''"); // 防注入
            // 1. 先物理清理（即使后面异常，实体也已删除）
            boolean cleared = physicalRemovePluginRobust(pkg);
            log.append("[DSHA] 实体清理").append(cleared ? "完成 ✅" : "失败（仍存在）⚠️").append("\n");
            // 2. dsh remove + manifest 直改
            String py = "python3 - <<'PY'\n" +
                    "import json,sys\n" +
                    "p='/root/.dsh/profiles/web/package.json'\n" +
                    "try:\n" +
                    " d=json.load(open(p))\n" +
                    " d.get('dependencies',{}).pop('" + esc + "',None)\n" +
                    " b=d.get('dsh',{}).get('profile',{}).get('bundles')\n" +
                    " if b: d['dsh']['profile']['bundles']=[x for x in b if x!='" + esc + "']\n" +
                    " json.dump(d,open(p,'w'),indent=2,ensure_ascii=False)\n" +
                    "except Exception as e:\n" +
                    " print('[DSHA] manifest 修改失败:',e); sys.exit(1)\n" +
                    "print('[DSHA] manifest 已移除: " + esc + "')\n" +
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

    /** 递归删除文件/目录（Java 侧，绕过 bash rm 的环境问题） */
    private void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
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

    /**
     * 安装插件（带 GitHub 兜底）：先按 pkg 装（npm 名），若 404/找不到包 且给了 fallbackSpec，
     * 自动用 github:owner/repo 重试一次（市场条目多为仅 GitHub 发布的仓库插件）。
     */
    public String installPlugin(String pkg, String fallbackSpec) {
        String r = runPluginInstall(pkg);
        if (r != null && fallbackSpec != null && !fallbackSpec.equals(pkg) && isPkgNotFound(r)) {
            r = "\n[自动回退 GitHub 仓库方式安装…]\n" + runPluginInstall(fallbackSpec);
        }
        if (r != null && r.contains("INSTALL_EXIT=0")) {
            return r + "\n\n[已安装到 profile，重启 WebUI 生效]";
        }
        return r == null ? "无输出" : r;
    }

    /** 判定安装输出是否为"包在 registry 找不到"（npm 404 类） */
    private boolean isPkgNotFound(String out) {
        return out.contains("ERR_PNPM_FETCH") || out.contains("not in the npm registry")
                || out.contains("404") || out.contains("ENOTFOUND");
    }

    /** 单次插件安装执行（源码目录优先，无则全局 dsh） */
    private String runPluginInstall(String pkg) {
        try {
            String wd = detectWorkdir();
            return proot.execAndRead(
                    "if [ -d /root/" + wd + " ]; then cd /root/" + wd + "; " + depsSelfHeal() +
                    "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc; " +
                    "( node apps/cli/lib/bin.js plugin --profile web add " + pkg + " 2>&1 || dsh plugin --profile web add " + pkg + " 2>&1 ); " +
                    "else echo '[DSHA] 无源码目录，回退全局 dsh'; " +
                    "printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc; " +
                    "dsh plugin --profile web add " + pkg + " 2>&1; fi | tail -15; echo INSTALL_EXIT=${PIPESTATUS[0]}");
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
