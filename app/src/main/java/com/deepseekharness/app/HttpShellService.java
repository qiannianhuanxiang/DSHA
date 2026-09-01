package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s "http://127.0.0.1:3090/exec?cmd=<urlencoded>"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 安全：命中危险命令（删除/格式化/卸载/重启等）时，若设置开启"需确认"，
 * 前台弹窗 / 后台高优先级通知（允许/拒绝按钮），60 秒超时默认拒绝。
 */
public final class HttpShellService {

    public static final int PORT = 3090;
    private static final String CONFIRM_CHANNEL = "dsh_confirm_channel";
    private static final int CONFIRM_NOTIF_ID = Constants.NOTIF_SHELL_CONFIRM;
    private static final long CONFIRM_TIMEOUT_S = 60;

    private static volatile HttpShellService instance;
    /** 全局「已有桥在监听」标志。HarnessService 与 DeviceBridgeService 各自 new 一个
     *  实例并都调 start()，实例字段 running 挡不住跨实例的重复启动 —— 第二个实例会
     *  因端口占用绑定失败，进而把活着的那个从 instance 里抹掉（通知按钮全废）。
     *  （吸收上游 PR#24） */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 本实例是否真正持有监听：只有持有者的 stop() 才做清理，
     *  否则那个没绑上端口的实例一被销毁就会把真桥的状态清掉。 */
    private volatile boolean owner;

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile CountDownLatch pendingLatch;
    private volatile boolean pendingAllow;
    /** 本轮确认是否已被认领：三条渠道（通知 / 弹窗 / 悬浮条）谁先点谁生效。
     *
     *  <p>没有它的时候，「检查 latch 未决 → 写 pendingAllow → countDown」这三步不是原子的：
     *  两条渠道几乎同时被点（悬浮条点了没反应又去点通知，或纯误触），两个线程都能通过
     *  {@code getCount() == 0} 的检查，于是后到的那个会把 pendingAllow 覆盖掉 ——
     *  等待线程读到的是后写入的值。表现是<b>授权语义反转</b>：点「允许」却被拒绝，
     *  更糟的是点「拒绝」而另一条渠道的「允许」后到，命令照样执行。 */
    private final java.util.concurrent.atomic.AtomicBoolean confirmResolved =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 确认进行中标志：并发确认请求直接拒绝（避免 latch 覆盖导致"点了允许却拒绝"）。
     *  用 AtomicBoolean 而非 volatile boolean —— "检查后置位"必须原子，
     *  否则两个请求线程可能同时通过检查、互相覆盖 pendingLatch。（吸收上游 PR#24） */
    private final java.util.concurrent.atomic.AtomicBoolean confirmBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 每次确认的序号：判定一次「允许/拒绝」点击属于哪个请求。
     *  没有它的话，残留通知（锁屏/通知历史/手表转发）上的旧按钮会把授权决定
     *  打到下一个请求上——等于一次点击授权了另一条命令。（吸收上游 PR#24） */
    private final java.util.concurrent.atomic.AtomicLong confirmEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    /** 当前挂起的弹窗：setCancelable(false) 后它自己关不掉，确认完必须主动 dismiss */
    private volatile androidx.appcompat.app.AlertDialog pendingDialog;
    /** /app/ask 的一次性问答状态（支持前台弹窗与通知栏快捷按钮双通道同步回答）。 */
    private final java.util.concurrent.atomic.AtomicLong askEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile androidx.appcompat.app.AlertDialog pendingAskDialog;
    private volatile CountDownLatch pendingAskLatch;
    private final java.util.concurrent.atomic.AtomicBoolean askResolved =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String askAnswer = "";
    private final java.util.concurrent.atomic.AtomicBoolean askBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private ServerSocket server;
    /** IPv6 回环监听（兼容脚本用 localhost 解析成 ::1 的场景；绑不上则忽略） */
    private ServerSocket server6;
    private volatile boolean running;
    /** 连接处理线程池（请求可能阻塞等用户确认 60s，必须并发处理，否则一个确认卡死全部请求） */
    private java.util.concurrent.ExecutorService pool;
    /** 鉴权 token（随机生成，rootfs 内 agent 通过它访问；外部网络无法到达 127.0.0.1）。
     *  每次 start 都会和 rootfs 文件对账：文件存在则沿用，缺失/内容异常则轮换重写，
     *  防止重解压 rootfs 后内存 token 与文件不一致导致 agent 无法认证。 */
    private static volatile String authToken = "";
    /** token 持久化位置（rootfs 内 agent 可读，建议 0600） */

    public HttpShellService(Context ctx) {
        this.ctx = ctx;
    }

    public static HttpShellService instance() {
        return instance;
    }

    /** 桥还没启动过时的兜底 Context。
     *
     *  <p>{@link #tokenFileIfPossible()} 原先只从 {@code instance().ctx} 取 Context，
     *  于是桥没启动过时（比如用户把「设备桥」和「悬浮条」都关着）它返回 null，
     *  {@link #ensureToken()} 只改内存、**静默不写文件**。自检因此谎报「已重新写入」，
     *  而容器侧 selftest 同时报「缺 .bridge_token」—— 两份报告自相矛盾，真机上出现过。 */
    private static volatile Context tokenCtx;

    /** 自检、恢复备份这类在桥启动前就要对齐 token 的场合，先把 Context 交给它。 */
    static void bindTokenContext(Context ctx) {
        if (ctx != null) tokenCtx = ctx.getApplicationContext();
    }

    private static java.io.File tokenFileIfPossible() {
        Context c = null;
        try {
            c = instance().ctx;
        } catch (Throwable ignored) {
        }
        if (c == null) c = tokenCtx;
        if (c == null) return null;
        try {
            HarnessController hc = HarnessController.get(c);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                return new java.io.File(hc.getProot().getRootfsDir(), "root/.dsh/.bridge_token");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读取 rootfs 内 token 文件（只读，不修改内容）。 */
    private static String readTokenFromFile(java.io.File tf) {
        if (tf == null || !tf.isFile()) return null;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(tf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty() || s.length() > 128) return null;
            // 只允许可安全放入 URL/Header 的一半字符，拒绝换行等脏内容
            if (!s.matches("[A-Za-z0-9_-]+")) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 生成/对账 token（rootfs 文件优先；缺失或无效则轮换并写入）。
     *  注：token 属于 rootfs 内 agent 访问 3090 桥的共享凭据，不做 0600 之外的额外加密。 */
    private static String ensureToken() {
        synchronized (HttpShellService.class) {
            java.io.File tf = tokenFileIfPossible();
            String fromFile = readTokenFromFile(tf);
            if (fromFile != null && !fromFile.isEmpty()) {
                authToken = fromFile;
                return authToken;
            }
            // 无文件或内容无效 → 轮换（不能用旧内存值，否则 agent 读到的文件永远不会出现）
            String t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            authToken = t;
            if (tf != null) {
                try {
                    if (tf.getParentFile() != null) tf.getParentFile().mkdirs();
                    java.nio.file.Files.write(tf.toPath(), t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                        java.nio.file.Files.setPosixFilePermissions(tf.toPath(),
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    } catch (Throwable e) {
            android.util.Log.w("DSHA", "token 文件写入失败，3090 桥将无法鉴权: " + e);
        }
                } catch (Throwable e) {
            android.util.Log.w("DSHA", "token 文件读取/清理失败: " + e);
        }
            }
            return authToken;
        }
    }

    /** 最近一次绑定结果：空 = 正常；非空 = 失败原因（自检与诊断读它）。
     *  端口被别的应用占掉时，症状和当年那个「只绑 ::1」的 bug 一模一样
     *  （agent 调什么都超时、确认弹窗不出现），所以必须留下明确的失败原因。 */
    private static volatile String bindError = "";

    public static String bindError() {
        return bindError;
    }

    private void noteBindOk() {
        bindError = "";
        writeBridgeStatus("ok port=" + PORT);
    }

    private void noteBindError(String why) {
        bindError = why;
        android.util.Log.e("DSHA", "3090 桥绑定失败：" + why);
        writeBridgeStatus("fail " + why);
    }

    /** 桥状态落到 rootfs 的 /root/.dsh/.bridge_status，容器里 cat 一下就知道桥为什么不通 */
    private void writeBridgeStatus(String s) {
        try {
            java.io.File tf = tokenFileIfPossible();
            if (tf == null || tf.getParentFile() == null) return;
            java.io.File f = new java.io.File(tf.getParentFile(), ".bridge_status");
            if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) return;
            java.nio.file.Files.write(f.toPath(),
                    (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    public void start() {
        if (running) return;
        // 跨实例互斥：已经有桥在监听就直接返回，别去抢端口把活着的那个搞坏
        if (!STARTED.compareAndSet(false, true)) {
            android.util.Log.i("DSHA", "3090 桥已在运行，跳过重复启动");
            return;
        }
        owner = true;
        running = true;
        instance = this;
        ensureToken();
        // 固定小线程池：请求可能挂起等用户确认（60s），串行处理会互相阻塞
        pool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http-shell");
            t.setDaemon(true);
            return t;
        });
        Thread t = new Thread(() -> {
            try {
                // 安全：仅绑定回环（loopback），外部网络无法访问！
                // 关键：必须显式绑 IPv4 127.0.0.1 —— InetAddress.getLoopbackAddress()
                // 在 Android（IPv6 优先）上返回 ::1，桥只监听 [::1]:3090，而 rootfs 内
                // 所有客户端（adb-shell.py / dsh-confirm.sh / 内置插件）都连 127.0.0.1
                // → Connection refused → 确认弹窗永不出现，命令被判 USER_REJECTED。
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), PORT));
                noteBindOk();
                acceptLoop(server);
            } catch (java.net.BindException e) {
                noteBindError("端口 " + PORT + " 已被其它应用占用（" + e.getMessage()
                        + "）—— 关掉占用它的应用，或重启手机后重开 DSHA");
            } catch (IOException e) {
                noteBindError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "http-shell-accept");
        t.setDaemon(true);
        t.start();
        // 附加监听 [::1]:3090：脚本/插件若用 localhost（可能解析成 IPv6）也能命中。
        // 绑不上（无 IPv6 栈/被占）时静默跳过，IPv4 主监听已足够。
        Thread t6 = new Thread(() -> {
            try {
                server6 = new ServerSocket();
                server6.setReuseAddress(true);
                server6.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("::1"), PORT));
                acceptLoop(server6);
            } catch (Throwable e) {
                // IPv6 绑不上不算故障（有些设备没有 IPv6 栈），IPv4 那条是主通道
                android.util.Log.i("DSHA", "3090 的 [::1] 附加监听未启用: " + e);
            }
        }, "http-shell-accept6");
        t6.setDaemon(true);
        t6.start();
    }

    /** 接受连接并分发到线程池（IPv4/IPv6 两个监听共用） */
    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket client = ss.accept();
                // 读超时 15 秒（原来 120 秒）。这个超时只管「读请求头」这一段 ——
                // 命令执行与等用户点确认期间并不 read，不受影响。
                // 而池子只有 4 个线程：同一台手机上任何 App 都能连 loopback，
                // 4 个「连上不说话」的连接就能让桥停摆两分钟，agent 的确认弹窗和
                // 命令全部超时。请求头 15 秒到不齐的客户端本来也不正常。
                client.setSoTimeout(15_000);
                java.util.concurrent.ExecutorService p = pool;
                if (p == null) {
                    try { client.close(); } catch (IOException ignored) { }
                    return;
                }
                p.execute(() -> handle(client));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    public void stop() {
        if (!owner) return; // 非持有者：什么都别动，否则会把真桥的状态清掉
        owner = false;
        running = false;
        writeBridgeStatus("stopped");
        instance = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (server6 != null) server6.close();
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        // 释放挂起的确认（默认拒绝）
        CountDownLatch l = pendingLatch;
        if (l != null) l.countDown();
        dismissConfirmDialog();
        cancelConfirmNotification();
        STARTED.set(false); // 放开，允许后续重新启动（DeviceBridgeService 会自愈拉起）
    }

    /** 校验查询串/头中的 token（常量时间比较 + URL 解码容错） */
    /** 当前桥 token；桥还没起来时返回空串（调用方按「不带 token」处理）。
     *  WebView 首帧 URL 与局域网代理都要用它 —— dsh 的 Web 服务已加 token 鉴权
     *  （webserver-auth-patch.sh），不带 token 会 403。 */
    public static String currentToken() {
        try {
            String t = authToken.isEmpty() ? ensureToken() : authToken;
            return t == null ? "" : t;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 自检用：当前内存里的桥 token 快照（空串 = 桥还没起来过）。
     *  故意不触发生成 —— 自检本身不该有副作用，写文件那是
     *  {@link #resetTokenAfterRestore()} 的活儿。 */
    static String tokenSnapshot() {
        return authToken == null ? "" : authToken;
    }

    /** 恢复备份后重新对齐 3090 桥的 token。
     *
     *  <p>老备份包里带着**备份那台机器**的 {@code .dsh/.bridge_token}（新版备份已经把它
     *  排除了）。恢复出来之后 rootfs 里是旧 token，而 App 进程内的 {@link #authToken}
     *  还是当前那个 —— 它是静态字段，{@link #ensureToken()} 只在缓存为空时才读文件。
     *  于是 App 用自己的 token 拼 WebView 首帧 URL，dsh 后端却按恢复出来的旧 token 校验，
     *  用户看到的就是「DSHA：需要 token，请在 DSHA 应用内打开，或在 URL 后加 ?dsha_t=…」。
     *
     *  <p>处理：删掉恢复出来的 token 文件、清空内存缓存，再让 ensureToken 重新生成并写回，
     *  两侧重新对齐。dsh 后端自己也缓存了 token（webserver-auth-patch 里的
     *  {@code __dshaTokenCache}），所以要重启 Web 才彻底生效 —— 恢复流程本来就提示重启。 */
    public static void resetTokenAfterRestore() {
        try {
            java.io.File tf = tokenFileIfPossible();
            if (tf != null && tf.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                tf.delete();
            }
            authToken = "";
            ensureToken();
            android.util.Log.i("DSHA", "恢复后已重置 3090 桥 token（老备份里带的是别的机器的）");
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "恢复后重置桥 token 失败: " + e);
        }
    }

    private static boolean tokenMatch(String presented) {
        String token = authToken.isEmpty() ? ensureToken() : authToken;
        return token != null && !token.isEmpty() && LanAuth.constantTimeEquals(token, presented);
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String cmd = "";
            if (path.startsWith("/exec") || path.startsWith("/confirm")) {
                // 走统一的查询串解析（Query.param）：值要截断到 &，参数名要精确匹配。
                // 旧实现是 path.indexOf("cmd=") —— 值截断修过了，但参数名边界一直没有，
                // 于是 ?xcmd=junk&cmd=真命令 会取到 junk。/confirm 的 cmd 是<b>给用户看的
                // 命令原文</b>，取错就等于让用户批准了一条与实际不符的命令。
                cmd = getParam(queryOf(path), "cmd", "");
            }
            // 鉴权：token 必须匹配（通过 ?token= 或 X-Token header）
            boolean authed = false;
            String t = "";
            // 解析与 LanProxyService 共用 LanAuth 那一份。原来这里是
            // query.indexOf("token=")，没有参数名边界：?xtoken=junk&token=真值
            // 会先命中 xtoken= 取到 junk 而误拒。两处各写一套判断正是本项目
            // 反复栽的模式，合并后由 tools/pure-logic-test.sh 一起覆盖。
            String qt = LanAuth.queryTokenFromTarget(path);
            if (qt != null && !qt.isEmpty()) {
                try { qt = URLDecoder.decode(qt, "UTF-8"); } catch (Exception ignored) { }
                t = qt;
                authed = tokenMatch(qt.trim());
            }
            if (!authed) {
                // 也支持 header 传 token（agent 引导用 curl -H）
                try {
                    String hdr;
                    int lines = 0;
                    while ((hdr = reader.readLine()) != null && !hdr.isEmpty()) {
                        // 桥绑在 loopback，但同一台手机上任何 App 都能连 loopback。
                        // 池子只有 4 个线程 —— 不设上限的话，一个只管发头不发空行的
                        // 连接就能占住一个线程直到读超时。行数封顶 + 下面的读超时兜底。
                        if (++lines > 64) break;
                        if (hdr.toLowerCase().startsWith("x-token:")) {
                            String hv = hdr.substring(8).trim();
                            if (!hv.isEmpty() && tokenMatch(hv)) authed = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            String result;
            if (!authed) {
                result = "[UNAUTHORIZED]";
            } else if (path.startsWith("/app/task/running")) {
                result = appTaskRunning(path);
            } else if (path.startsWith("/app/task/cancel")) {
                result = appTaskCancel();
            } else if (path.startsWith("/app/notify")) {
                // agent 通过 App 发通知栏提醒（App 层交互）
                result = appNotify(path);
            } else if (path.startsWith("/app/toast")) {
                // agent 弹 App 内 Toast
                result = appToast(path);
            } else if (path.startsWith("/app/readfile")) {
                // agent 读外部文件（rootfs 挂载 /sdcard 的补充；支持路径参数）
                result = appReadFile(path);
            } else if (path.startsWith("/health")) {
                result = "OK"; // 存活探测（仍需 token）：客户端可据此区分「桥没起」与「命令失败」
            } else if (path.startsWith("/app/ui/")) {
                result = appUi(path);
            } else if (path.startsWith("/app/device")) {
                result = appDevice();
            } else if (path.startsWith("/app/apps")) {
                result = appList(path);
            } else if (path.startsWith("/app/launch")) {
                result = appLaunch(path);
            } else if (path.startsWith("/app/clip")) {
                result = appClip(path);
            } else if (path.startsWith("/app/share")) {
                result = appShare(path);
            } else if (path.startsWith("/app/open")) {
                result = appOpen(path);
            } else if (path.startsWith("/app/vibrate")) {
                result = appVibrate(path);
            } else if (path.startsWith("/app/ask")) {
                result = appAsk(path);
            } else if (path.startsWith("/app/version")) {
                result = appVersion();
            } else if (path.startsWith("/app/help")) {
                result = appHelp();
            } else if (path.startsWith("/app/plugins")) {
                result = appPlugins(path);
            } else if (path.startsWith("/app/overlay")) {
                result = appOverlay(path);
            } else if (path.startsWith("/app/location")) {
                // 位置 / 传感器 / 手电：手机相对服务器真正独有的那几样能力。
                // 顺序要紧 —— /app/sensors 必须在 /app/sensor 之前判，
                // 否则 startsWith 会让「列表」被「读单个」抢走。
                result = DeviceSense.location(ctx, "1".equals(getParam(queryOf(path), "fresh", "")));
            } else if (path.startsWith("/app/sensors")) {
                result = DeviceSense.sensorList(ctx);
            } else if (path.startsWith("/app/sensor")) {
                result = DeviceSense.sensorRead(ctx, getParam(queryOf(path), "name", "light"));
            } else if (path.startsWith("/app/torch")) {
                String on = getParam(queryOf(path), "on", "1");
                result = DeviceSense.torch(ctx, !"0".equals(on) && !"off".equalsIgnoreCase(on));
            } else if (path.startsWith("/app/export")) {
                result = appExport(path);
            } else if (cmd.isEmpty()) {
                result = "[NO_CMD]";
            } else if (path.startsWith("/confirm")) {
                // rootfs 内包装器请求的确认：只弹窗，不执行
                // force=1（adb-shell 报备）→ 所有命令都确认；否则仅危险命令
                boolean force = path.contains("force=1");
                boolean needConfirm = force || (confirmEnabled() && DangerShellGuard.isDangerous(cmd));
                result = needConfirm ? (requestUserConfirm(cmd) ? "YES" : "NO") : "YES";
            } else if (DangerShellGuard.isDangerous(cmd) && confirmEnabled()) {
                result = awaitConfirm(cmd);
            } else {
                result = ShizukuShell.exec(cmd);
            }
            // 关键：result 必须包引号 —— 旧实现输出 {"result":YES} 是非法 JSON，
            // 客户端（adb-shell.py 判 '"YES"' in body / agent 用 json 解析）全部失效：
            // 用户点「允许」也会被当成拒绝。
            String body = "{\"result\":\"" + jsonEscape(result) + "\"}";
            byte[] bodyBytes = body.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            c.getOutputStream().write(head.getBytes("UTF-8"));
            c.getOutputStream().write(bodyBytes);
            c.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    // ================= App 层交互端点（agent 通过 3090 桥调用） =================

    /** /app/notify?title=&text= ：发通知栏提醒 */
    private String appNotify(String path) {
        try {
            String q = queryOf(path);
            String title = getParam(q, "title", "DSHA 通知");
            String text = getParam(q, "text", "");
            if (text.isEmpty()) return "NO_TEXT";

            // 任务结束，清除运行中状态通知
            cancelRunningNotification();

            // 1. 先走通知：写入「任务结果与交互」独立通道，挂载 RemoteInput 继续对话输入组件
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel ch = new NotificationChannel(
                            Constants.CHANNEL_TASK_RESULT, "任务结果与交互",
                            NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("智能体任务完成、异常结束或终止时的结果通知");
                    nm.createNotificationChannel(ch);
                }

                Intent openAppIntent = new Intent(ctx, QuickChatSheetActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent contentPi = PendingIntent.getActivity(ctx, 20, openAppIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                // 点击「💬 继续对话」直接从屏幕底部唤起抽屉弹层
                Intent actionIntent = new Intent(ctx, QuickChatSheetActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent actionPi = PendingIntent.getActivity(ctx, 25, actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                        R.drawable.ic_launch, "💬 继续对话", actionPi)
                        .build();

                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Constants.CHANNEL_TASK_RESULT)
                        .setSmallIcon(R.drawable.ic_launch)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(contentPi)
                        .addAction(replyAction)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);
                nm.notify(Constants.NOTIF_TASK, b.build());
            }

            // 2. 再走 Toast：若 App 处于前台，弹出友好 Toast 提示用户（不阻断通知栏卡片）
            if (TaskNotifier.appInForeground) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Toast.makeText(ctx, "✓ " + title + "：" + (text.length() > 30 ? text.substring(0, 30) + "…" : text), Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            }

            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** /app/toast?text= ：弹 App 内 Toast */
    /** 屏幕操作（走无障碍服务）：读屏 / 点按 / 输入 / 按键 / 滑动。
     *
     *  这条通道不需要 ADB 也不需要 Shizuku —— 绝大多数用户两者都没有，
     *  而无障碍是一次授权长期可用，这才是 agent 能真正「操作手机」的现实路径。 */
    // ==================== 屏幕操作的授权闸门 ====================
    //
    // 为什么必须有这道闸：/app/ui/* 能读屏、点按、输入，破坏力其实**超过** shell 命令 ——
    // 它直接操作用户**已经登录**的应用，绕过所有应用层权限。agent 一旦被 prompt
    // injection 诱导（读到网页或文件里夹带的指令），就能在支付软件里点按、把私信
    // 截屏留到磁盘。而 /exec 一直有危险命令守卫，UI 操作在我加完那六个端点之后
    // 一道闸都没有 —— 这是自查时发现的最大缺口。
    //
    // 可用性上的平衡：GUI 自动化要连续操作，每一步都弹窗根本没法用。所以做成
    // 「一次授权 + 时间窗」：首次弹确认，允许后十分钟内不再问；但前台是支付/银行/
    // 密码管理类应用时无视时间窗，每次都要确认。
    private static volatile long uiGrantUntil = 0L;
    private static final long UI_GRANT_MS = 10 * 60 * 1000L;

    /** 涉钱、涉密的应用：宁可多问一次。取不到包名也按敏感处理。 */
    private static boolean isSensitiveApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        String[] keys = {
                "alipay", "tencent.mm", "unionpay", "jdpay", "wallet", "paypal",
                "bank", "icbc", "ccb", "abchina", "bankofchina", "cmbchina",
                "bankcomm", "psbc", "cebbank", "cmbc", "spdb", "citic", "hxb",
                "keepass", "bitwarden", "lastpass", "1password", "authenticator",
                "com.android.settings",   // 系统设置：能改权限、开无障碍、卸载应用
        };
        for (String k : keys) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    private static java.io.File authLeaseFileIfPossible() {
        Context c = null;
        try {
            c = instance().ctx;
        } catch (Throwable ignored) {
        }
        if (c == null) c = tokenCtx;
        if (c == null) return null;
        try {
            HarnessController hc = HarnessController.get(c);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                return new java.io.File(hc.getProot().getRootfsDir(), "root/.dsh/.auth_lease");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** @param action 给用户看的具体动作描述 —— 弹窗必须说清 AI 要干什么，
     *               而不是笼统一句「操作屏幕」，否则用户等于盲签。 */
    private boolean uiAuthorized(String action) {
        String pkg = DshaAccessibilityService.currentPackage();
        boolean sensitive = isSensitiveApp(pkg);
        long now = System.currentTimeMillis();
        if (!sensitive) {
            if (now < uiGrantUntil) {
                return true;
            }
            java.io.File lf = authLeaseFileIfPossible();
            if (lf != null && lf.isFile()) {
                try {
                    String s = new String(java.nio.file.Files.readAllBytes(lf.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    double expSec = Double.parseDouble(s);
                    if (now / 1000.0 < expSec) {
                        uiGrantUntil = (long) (expSec * 1000L);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        String where = pkg.isEmpty() ? "当前界面" : pkg;
        String why = sensitive
                ? "在【" + where + "】里：" + action
                + "  # 这类应用涉及支付或隐私，每次都需要你确认"
                : action + "  # 允许后 10 分钟内的屏幕与设备操作不再询问";
        boolean ok = requestUserConfirm(why);
        if (ok && !sensitive) {
            long grantExp = now + UI_GRANT_MS;
            uiGrantUntil = grantExp;
            java.io.File lf = authLeaseFileIfPossible();
            if (lf != null) {
                try {
                    java.nio.file.Files.write(lf.toPath(),
                            String.valueOf(grantExp / 1000.0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
            }
        }
        return ok;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private String appUi(String path) {
        String q = queryOf(path);
        try {
            if (path.startsWith("/app/ui/dump")) {
                if (!uiAuthorized("读取当前屏幕上的文字与控件")) return "[ERR] 你拒绝了这次屏幕读取";
                return DshaAccessibilityService.uiDump();
            }
            if (path.startsWith("/app/ui/tap")) {
                String text = getParam(q, "text", "");
                // 有文字就按文字点：控件位置会随滚动和动画变，文字不会
                if (!text.isEmpty()) {
                    if (!uiAuthorized("点击「" + shortText(text) + "」")) return "[ERR] 你拒绝了这次点击";
                    return DshaAccessibilityService.uiTapText(text);
                }
                int x = intParam(q, "x", -1);
                int y = intParam(q, "y", -1);
                if (x < 0 || y < 0) return "[ERR] 需要 ?text=要点的文字 或 ?x=&y=坐标";
                if (!uiAuthorized("点击坐标 (" + x + "," + y + ")")) return "[ERR] 你拒绝了这次点击";
                return DshaAccessibilityService.uiTap(x, y);
            }
            if (path.startsWith("/app/ui/input")) {
                String text = getParam(q, "text", "");
                if (text.isEmpty()) return "[ERR] 需要 ?text=";
                if (!uiAuthorized("在输入框里填入「" + shortText(text) + "」")) {
                    return "[ERR] 你拒绝了这次输入";
                }
                return DshaAccessibilityService.uiInput(text);
            }
            if (path.startsWith("/app/ui/key")) {
                String k = getParam(q, "name", "");
                if (!uiAuthorized("按下系统按键 " + shortText(k))) return "[ERR] 你拒绝了这次按键";
                return DshaAccessibilityService.uiKey(k);
            }
            if (path.startsWith("/app/ui/screenshot") || path.startsWith("/app/ui/shot")) {
                // 截屏会把当前画面留到磁盘，等于一份可被后续读取的隐私快照
                if (!uiAuthorized("截取当前屏幕并保存为图片")) return "[ERR] 你拒绝了这次截屏";
                return DshaAccessibilityService.uiScreenshot();
            }
            if (path.startsWith("/app/ui/swipe")) {
                int x1 = intParam(q, "x1", -1);
                int y1 = intParam(q, "y1", -1);
                int x2 = intParam(q, "x2", -1);
                int y2 = intParam(q, "y2", -1);
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    return "[ERR] 需要 ?x1=&y1=&x2=&y2=（可选 &ms=时长）";
                }
                if (!uiAuthorized("滑动屏幕 (" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ")")) {
                    return "[ERR] 你拒绝了这次滑动";
                }
                return DshaAccessibilityService.uiSwipe(x1, y1, x2, y2, intParam(q, "ms", 300));
            }
            return "[ERR] 未知端点（可用：dump/tap/input/key/swipe）";
        } catch (Throwable t) {
            return "[ERR] " + t;
        }
    }

    private int intParam(String q, String k, int def) {
        try {
            return Integer.parseInt(getParam(q, k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * {@code /app/overlay?session=&kind=delta|tool|text|done|clear&text=} ——
     * 把 agent 正在生成的内容送到屏幕顶部的流式悬浮条（{@link OverlayController}）。
     *
     * <p>返回值刻意分三种，让插件侧能自己降级：{@code DISABLED}（用户没开这个功能）、
     * {@code NO_PERMISSION}（没给悬浮窗权限）、{@code OK}。插件拿到前两种就该停止推送 ——
     * 流式增量是高频调用，白发一路 HTTP 纯属烧电。
     */
    /**
     * {@code /app/plugins}：让 dsh 进程内的插件把<b>真实加载状态</b>报给 App，
     * 也可以只读回上一次上报。
     *
     * <p><b>为什么要走桥，而不是 App 自己读文件</b>：App 只能读 profile 的 package.json
     * 猜「注册了没有」，而<b>注册了不等于加载成功</b> —— 入口文件缺失、inject 的服务不存在、
     * patch 里的 name 与目标行对不上，都会让插件静静地不生效，而 package.json 看起来一切正常。
     * 只有跑在 dsh 进程里的插件能通过 cordis 上下文看到真实状态。这正是「插件装了没反应」
     * 一直缺的那份证据 —— 缺了它，App 只能猜，用户只能重装。
     *
     * <p>约定：
     * <ul>
     *   <li>{@code ?loaded=a,b&failed=c}（逗号分隔）→ 上报，存起来给插件页与自检用；</li>
     *   <li>不带参数 → 只读，返回 {@code LOADED:… / FAILED:… / AT:<毫秒时间戳>}。</li>
     * </ul>
     */
    /**
     * {@code /app/help}：3090 桥的完整端点清单，纯文本、给 agent 读。
     *
     * <p><b>为什么要有这个端点</b>：这份清单原来整份写在 device-shell-guide 的注入提示词里
     * （12KB，约几千 token），而它是<b>每一轮对话都要付的成本</b> —— 哪怕这轮根本不碰设备。
     * 挪到运行时按需查之后，提示词只留骨架，agent 要用设备能力时 curl 一次就拿到全部细节。
     *
     * <p>还有个额外好处：这份清单跟端点实现<b>在同一个文件里</b>，加端点时顺手就更新了；
     * 写在插件的提示词里则要改 assets、bump 版本、重签清单，于是必然脱节
     * （AGENTS.md 里「文档说 14 个端点、实际 26 个」就是这么来的）。
     */
    /**
     * 桥协议版本 —— 插件侧靠它判断「这台 App 支持哪些端点」。
     *
     * <p><b>什么时候该涨</b>（写清楚，否则这个号形同虚设）：
     * <ul>
     *   <li><b>加新端点：不涨。</b>老插件不知道新端点，行为不变；新插件想用新端点，
     *       自己 try 一下拿 404 就知道了；</li>
     *   <li><b>改已有端点的参数含义、返回格式，或删端点：涨。</b>这类改动会让按老约定
     *       写的插件静默拿到错东西 —— 那正是版本号要挡的事。</li>
     * </ul>
     *
     * <p>所以插件的正确写法是 {@code if (protocol >= N)} 而不是 {@code == N}。
     */
    private static final int BRIDGE_PROTOCOL = 1;

    /**
     * {@code /app/version}：桥协议与 App 版本，给插件做特性检测。
     *
     * <p>没有这个端点时，插件只能靠「试着调一下看会不会 404」来猜 App 的能力，
     * 而 dsh 与 DSHA 是各自升级的 —— 用户完全可能拿新插件配旧 App。
     */
    private String appVersion() {
        return "BRIDGE_PROTOCOL=" + BRIDGE_PROTOCOL + "\n"
                + "APP_VERSION=" + BuildConfig.VERSION_NAME + "\n"
                + "APP_CODE=" + BuildConfig.VERSION_CODE + "\n"
                + "HINT=端点清单见 /app/help；判版本请用 >= 而不是 ==\n";
    }

    private String appHelp() {
        return "DSHA 3090 桥端点清单（BRIDGE_PROTOCOL=" + BRIDGE_PROTOCOL + "）\n"
            + "token 取自 /root/.dsh/.bridge_token，下面记为 $T。\n"
            + "带中文/空格的参数一律用 -G --data-urlencode，别手写 URL 编码。\n"
            + "\n"
            + "== 屏幕操作（无障碍服务，不需要 ADB/Shizuku）==\n"
            + "读屏  curl -s \"127.0.0.1:3090/app/ui/dump?token=$T\"\n"
            + "      → 每行「[序号] \"文字\" 可点击 中心=(x,y) 区域=l,t,r,b」\n"
            + "点按  curl -s -G 127.0.0.1:3090/app/ui/tap --data-urlencode \"text=设置\" --data-urlencode \"token=$T\"\n"
            + "      → 优先按文字点：控件位置随滚动/动画变，文字不变。没有文字才用 ?x=&y=\n"
            + "输入  curl -s -G 127.0.0.1:3090/app/ui/input --data-urlencode \"text=内容\" --data-urlencode \"token=$T\"\n"
            + "      → 填到当前焦点框；没有焦点先 tap 一下输入框\n"
            + "按键  /app/ui/key?name=back  （back/home/recents/notifications/quicksettings/lock）\n"
            + "滑动  /app/ui/swipe?x1=500&y1=1500&x2=500&y2=500&ms=300\n"
            + "截屏  /app/ui/screenshot   → 存 PNG 到 Download/DSHA 并返回路径（不回 base64）\n"
            + "节奏：每次点按/输入后先 dump 再决定下一步，别凭记忆连点。\n"
            + "\n"
            + "== 设备与应用 ==\n"
            + "/app/device                     机型/系统/电量/网络/屏幕/存储/内存\n"
            + "/app/apps?q=微信&limit=50       已装应用（默认只列第三方）\n"
            + "/app/launch?pkg=com.tencent.mm  启动应用\n"
            + "/app/clip                       读剪贴板（需 App 在前台，系统限制）\n"
            + "/app/clip + text=…              写剪贴板\n"
            + "/app/readfile?path=/sdcard/…    读外部文件\n"
            + "/sdcard 已挂载，Download / DCIM 等公共目录可直接读写\n"
            + "\n"
            + "== 与用户交互 ==\n"
            + "/app/ask?options=继续|取消 + q=…  弹窗阻塞等回答（最多三个选项）\n"
            + "/app/notify?title=… + text=…      通知栏\n"
            + "/app/toast + text=…               App 内提示\n"
            + "/app/vibrate?ms=300               震动（长任务跑完叫醒用户）\n"
            + "/app/share（text= 或 path=）      分享到其它应用\n"
            + "/app/open?url=https://…           打开链接\n"
            + "/app/export?path=/root/report.md  把产物交给用户 → 落 Download/DSHA\n"
            + "建议：需要用户拍板用 /app/ask 而不是干等；长任务结束用 notify 或 vibrate 叫人；\n"
            + "产出报告用 /app/export，别只留在容器里。\n"
            + "\n"
            + "== 传感器与位置（默认关闭，需用户在配置页勾选）==\n"
            + "/app/location（加 fresh=1 强制重新定位，可能等数秒）\n"
            + "/app/sensors 列表 · /app/sensor?name=light 读值\n"
            + "（light 环境光 lux / accel / gyro / magnet / pressure / proximity /\n"
            + " gravity / rotation 姿态四元数 / steps 开机后步数）\n"
            + "/app/torch?on=1 手电\n"
            + "这三类返回 DISABLED（用户没开该能力）或 NO_PERMISSION（没授系统权限）时，\n"
            + "照原话告诉用户去哪开，不要重试 —— 重试不会让开关自己变。\n"
            + "\n"
            + "== 元信息 ==\n"
            + "/app/version                          桥协议版本 + App 版本（特性检测用）\n"
            + "/app/help                             本清单\n"
            + "\n"
            + "== 插件状态 ==\n"
            + "/app/plugins                          读回上次上报的加载状态\n"
            + "/app/plugins?loaded=a,b&failed=c      上报（插件侧用）\n"
            + "\n"
            + "== 设备 shell（ADB 无线调试，用户可能没开）==\n"
            + "/root/dsh-bin/adb-shell \"命令\"        shell 级（uid=2000）\n"
            + "包装命令不存在时：python3 /root/.dsh/adb-shell.py \"命令\"\n"
            + "报连不上/未配对：先看上面的 App 层接口能不能办成；确实必须 shell 才请用户到\n"
            + "「配置」页开「ADB 设备通道」并配对，别反复试同一条命令。\n"
            + "不要用 /root/dsh-bin/adb 或裸 adb —— 那是守卫包装脚本，会失败。\n"
            + "\n"
            + "== root（--su）==\n"
            + "默认权限是 shell 级（uid=2000，非 root）。不要主动用 --su；\n"
            + "只有用户明确要求 root 操作时才尝试，且要先请他到「配置」页勾选「允许 root shell」。\n";
    }

    private String appPlugins(String path) {
        try {
            String q = queryOf(path);
            String loaded = getParam(q, "loaded", null);
            String failed = getParam(q, "failed", null);
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
            if (loaded == null && failed == null) {
                return "LOADED:" + sp.getString("plugin_loaded", "")
                        + "\nFAILED:" + sp.getString("plugin_failed", "")
                        + "\nAT:" + sp.getLong("plugin_report_ts", 0L);
            }
            sp.edit()
                    .putString("plugin_loaded", loaded == null ? "" : loaded.trim())
                    .putString("plugin_failed", failed == null ? "" : failed.trim())
                    .putLong("plugin_report_ts", System.currentTimeMillis())
                    .apply();
            // 有加载失败的就写进活动日志 —— 那是用户唯一能看到「插件为什么没反应」的地方
            if (failed != null && !failed.trim().isEmpty()) {
                try {
                    HarnessController.get(ctx).logActivity("插件加载失败：" + failed.trim());
                } catch (Throwable ignored) {
                }
            }
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    private String appOverlay(String path) {
        try {
            String q = queryOf(path);
            String kind = getParam(q, "kind", "delta");
            String text = getParam(q, "text", "");
            String session = getParam(q, "session", "");

            // 通知栏同步实时运行状态与「🛑 停止任务」紧急制动按钮
            if ("tool".equals(kind) || "text".equals(kind)) {
                if (!text.trim().isEmpty()) {
                    showRunningNotification(text);
                }
            } else if ("done".equals(kind) || "clear".equals(kind)) {
                cancelRunningNotification();
            }

            if (!OverlayController.enabled(ctx)) return "DISABLED";
            if (!OverlayController.permitted(ctx)) return "NO_PERMISSION";
            // 让插件知道用户想不想看这两类内容，省得白发一路 HTTP
            if ("reasoning".equals(kind) && !OverlayController.showReasoning(ctx)) {
                return "SKIP_REASONING";
            }
            OverlayController.push(ctx, session, kind, text);
            return OverlayController.showCommand(ctx) ? "OK" : "OK_NO_CMD";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String appTaskRunning(String path) {
        try {
            String q = queryOf(path);
            String title = getParam(q, "title", "DSHA · 正在执行");
            String text = getParam(q, "text", "智能体正在执行自动化任务...");
            showRunningNotification(title, text);
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String appTaskCancel() {
        try {
            cancelRunningNotification();
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private void showRunningNotification(String text) {
        showRunningNotification("DSHA · 正在执行", text);
    }

    private void showRunningNotification(String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        Constants.CHANNEL_AGENT_RUNNING, "Agent 运行状态",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("智能体运行中实时操作步骤通知");
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }

            Intent openAppIntent = new Intent(ctx, QuickChatSheetActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentPi = PendingIntent.getActivity(ctx, 110, openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent stopIntent = new Intent(ctx, ConfirmReceiver.class)
                    .setAction(ConfirmReceiver.ACTION_STOP_TASK);
            PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 111, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Action stopAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_launch, "🛑 停止任务", stopPi)
                    .build();

            String shortMsg = (text == null || text.trim().isEmpty()) ? "智能体正在执行自动化任务..." : shortText(text);

            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, Constants.CHANNEL_AGENT_RUNNING)
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle(title != null && !title.isEmpty() ? title : "DSHA · 正在执行")
                    .setContentText(shortMsg)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text != null && !text.isEmpty() ? text : shortMsg))
                    .setContentIntent(contentPi)
                    .addAction(stopAction)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Constants.NOTIF_TASK_RUNNING, nb.build());
        } catch (Throwable ignored) {}
    }

    private void cancelRunningNotification() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.NOTIF_TASK_RUNNING);
        } catch (Throwable ignored) {}
    }

    private String appToast(String path) {
        try {
            final String text = getParam(queryOf(path), "text", "");
            if (text.isEmpty()) return "NO_TEXT";
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** /app/readfile?path= ：读外部文件（文本，限制 256KB）。路径如 /sdcard/Download/x.txt
     *  安全：禁止读凭据文件（.env / .bridge_token / settings.yaml —— 含 API key/对话密钥）。 */
    private String appReadFile(String path) {
        try {
            String p = getParam(queryOf(path), "path", "");
            if (p.isEmpty()) return "NO_PATH";
            String lower = p.toLowerCase();
            if (lower.endsWith("/.env") || lower.contains("/.env/")
                    || lower.contains(".bridge_token") || lower.contains("settings.yaml")) {
                return "FORBIDDEN: 凭据文件不可读（.env/.bridge_token/settings.yaml）";
            }
            java.io.File f = new java.io.File(p);
            // 只允许读取外部存储（/sdcard 或 /storage/emulated/0）：
            // 否则 agent 可绕过过滤直接读 App 私有目录（SharedPreferences 里含 API key）
            String canon;
            try {
                canon = f.getCanonicalPath();
            } catch (Exception e) {
                return "FORBIDDEN: 路径无法解析（" + p + "）";
            }
            // 前缀匹配必须带路径分隔符，否则 /sdcardEVIL/x、/storage/emulated/0abc/x
            // 这类路径会被当成外部存储放行。原实现算了 external 又不用它，
            // 实际生效的是下面那个不带斜杠的宽松判断 —— 等于白名单形同虚设。
            // （TarGzipExtractor.linkSafeWithin 里的同类校验就做对了：前缀 + 分隔符）
            boolean external = canon.equals("/sdcard") || canon.startsWith("/sdcard/")
                    || canon.equals("/storage/emulated/0") || canon.startsWith("/storage/emulated/0/");
            if (!external) {
                return "FORBIDDEN: 仅允许读取 /sdcard 外部存储（" + p + "）";
            }
            if (!f.isFile()) return "NOT_FOUND: " + p;
            if (f.length() > 256 * 1024) return "TOO_LARGE: " + f.length();
            byte[] bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ================= App 层能力（不需要 ADB / Shizuku，agent 直接调） =================

    /** /app/device ：设备状态一览（机型/系统/电量/网络/屏幕/存储/内存） */
    private String appDevice() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("model=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            sb.append("android=").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            try {
                android.os.BatteryManager bm =
                        (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                android.content.Intent st = ctx.registerReceiver(null,
                        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int status = st == null ? -1 : st.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                int level = bm == null ? -1
                        : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                sb.append("battery=").append(level).append("% charging=").append(charging).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                String net = "none";
                if (cm != null) {
                    android.net.Network n = cm.getActiveNetwork();
                    android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                    if (nc != null) {
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cellular";
                        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                        else net = "other";
                    }
                }
                sb.append("network=").append(net).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                sb.append("screen=").append(pm != null && pm.isInteractive() ? "on" : "off").append('\n');
            } catch (Throwable ignored) {
            }
            sb.append("app_foreground=").append(MainActivity.current != null).append('\n');
            try {
                android.os.StatFs fs = new android.os.StatFs(
                        android.os.Environment.getExternalStorageDirectory().getPath());
                long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
                sb.append("storage_free=").append(HarnessController.fmtBytes(free))
                        .append(" total=").append(HarnessController.fmtBytes(total)).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(mi);
                    sb.append("memory_free=").append(HarnessController.fmtBytes(mi.availMem))
                            .append(" total=").append(HarnessController.fmtBytes(mi.totalMem)).append('\n');
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
        return sb.toString().trim();
    }

    /** /app/apps?q=关键字&limit=50 ：已装应用列表（每行「包名<TAB>应用名」） */
    private String appList(String path) {
        try {
            String q = getParam(queryOf(path), "q", "").toLowerCase();
            int limit = 50;
            try {
                limit = Math.max(1, Math.min(300, Integer.parseInt(getParam(queryOf(path), "limit", "50"))));
            } catch (Exception ignored) {
            }
            boolean userOnly = !"0".equals(getParam(queryOf(path), "user", "1")); // 默认只列第三方应用
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            java.util.List<android.content.pm.PackageInfo> all = pm.getInstalledPackages(0);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (android.content.pm.PackageInfo pi : all) {
                if (pi.applicationInfo == null) continue;
                boolean sys = (pi.applicationInfo.flags
                        & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (userOnly && sys) continue;
                String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                if (!q.isEmpty() && !pi.packageName.toLowerCase().contains(q)
                        && !label.toLowerCase().contains(q)) {
                    continue;
                }
                sb.append(pi.packageName).append('\t').append(label).append('\n');
                if (++n >= limit) break;
            }
            if (n == 0) return "（没有匹配的应用）";
            return sb.append("共 ").append(n).append(" 个").toString();
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/launch?pkg=包名 ：启动应用（App 层，不需要 ADB） */
    private String appLaunch(String path) {
        try {
            String pkg = getParam(queryOf(path), "pkg", "");
            if (pkg.isEmpty()) return "NO_PKG";
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return "NOT_FOUND: " + pkg + "（该应用没有启动入口或未安装）";
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "OK: 已启动 " + pkg;
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/clip 读剪贴板；/app/clip?text=xxx 写剪贴板 */
    private String appClip(String path) {
        final String text = getParam(queryOf(path), "text", "");
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "NO_SERVICE";
            if (!text.isEmpty()) {
                mainHandler.post(() -> {
                    try {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA", text));
                    } catch (Throwable ignored) {
                    }
                });
                return "OK: 已写入剪贴板（" + text.length() + " 字）";
            }
            // 读：Android 10+ 只有前台应用能读剪贴板，后台一律拿不到
            if (MainActivity.current == null) {
                return "[APP_BACKGROUND] 系统限制：只有 App 在前台时才能读剪贴板，"
                        + "可先用 /app/notify 提醒用户打开 DSHA";
            }
            android.content.ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "（剪贴板为空）";
            CharSequence cs = cd.getItemAt(0).coerceToText(ctx);
            String s = cs == null ? "" : cs.toString();
            if (s.length() > 8192) s = s.substring(0, 8192) + "…（已截断）";
            return s;
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/share?text=... 或 /app/share?path=/sdcard/x.txt ：调起系统分享面板 */
    private String appShare(String path) {
        try {
            String q = queryOf(path);
            String text = getParam(q, "text", "");
            String file = getParam(q, "path", "");
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            if (!file.isEmpty()) {
                java.io.File f = new java.io.File(file);
                if (!f.isFile()) return "NOT_FOUND: " + file;
                // 只允许分享外部存储里的文件（App 私有目录需要 FileProvider 授权）
                String canon = f.getCanonicalPath();
                if (!canon.startsWith("/sdcard") && !canon.startsWith("/storage/emulated/0")) {
                    return "FORBIDDEN: 只能分享 /sdcard 下的文件";
                }
                send.setType("*/*");
                send.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(f));
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!text.isEmpty()) send.putExtra(android.content.Intent.EXTRA_TEXT, text);
            } else {
                if (text.isEmpty()) return "NO_CONTENT";
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, text);
            }
            android.content.Intent chooser = android.content.Intent.createChooser(send, "分享");
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return "OK: 已弹出分享面板";
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/open?url=... ：用系统默认应用打开链接（http/https/geo/tel…） */
    private String appOpen(String path) {
        try {
            String url = getParam(queryOf(path), "url", "");
            if (url.isEmpty()) return "NO_URL";
            String low = url.toLowerCase();
            // 只放行常见安全 scheme：file:// 会把 App 私有文件暴露给任意应用
            if (!low.startsWith("http://") && !low.startsWith("https://")
                    && !low.startsWith("geo:") && !low.startsWith("tel:")
                    && !low.startsWith("mailto:") && !low.startsWith("market://")) {
                return "FORBIDDEN: 只支持 http/https/geo/tel/mailto/market 链接";
            }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "OK: 已打开 " + url;
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/vibrate?ms=300 ：震动提醒（长任务跑完叫醒用户） */
    private String appVibrate(String path) {
        try {
            long ms = 300;
            try {
                ms = Math.max(30, Math.min(2000, Long.parseLong(getParam(queryOf(path), "ms", "300"))));
            } catch (Exception ignored) {
            }
            android.os.Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm =
                        (android.os.VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null) return "NO_VIBRATOR";
            v.vibrate(android.os.VibrationEffect.createOneShot(ms,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            return "OK: 震动 " + ms + "ms";
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** /app/ask?q=问题&options=选项A|选项B|选项C ：弹窗 + 通知栏双通道问用户，阻塞等回答（最多 3 个选项，120 秒超时） */
    private String appAsk(String path) {
        String q = getParam(queryOf(path), "q", "");
        String optRaw = getParam(queryOf(path), "options", "");
        if (q.isEmpty()) return "NO_QUESTION";
        String[] parts = optRaw.isEmpty() ? new String[] { "好" } : optRaw.split("\\|");
        final String[] opts = parts.length <= 3 ? parts : new String[] { parts[0], parts[1], parts[2] };
        // 检查与置位必须原子（见 askBusy 声明处）。CAS 成功之后立刻进 try，
        // 保证任何返回路径都会在 finally 里放开它。
        if (!askBusy.compareAndSet(false, true)) {
            return "[BUSY] 已有一个提问在等用户回答";
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final long myEpoch = askEpoch.incrementAndGet();
            askAnswer = "";
            askResolved.set(false);
            pendingAskLatch = latch;

            // 1. 发送高优先级常驻通知（带选项快捷操作按钮，前台、后台与锁屏均可一键回答）
            showAskNotification(q, opts, myEpoch);

            // 2. 若前台 Activity 活跃，同时展示弹窗
            final MainActivity act = MainActivity.current;
            if (act != null) {
                act.runOnUiThread(() -> {
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        androidx.appcompat.app.AlertDialog.Builder b =
                                new androidx.appcompat.app.AlertDialog.Builder(act)
                                        .setTitle("💬 助手提问").setMessage(q);
                        b.setPositiveButton(opts[0], (d, w) -> resolveAsk(opts[0], myEpoch));
                        if (opts.length > 1) {
                            b.setNegativeButton(opts[1], (d, w) -> resolveAsk(opts[1], myEpoch));
                        }
                        if (opts.length > 2) {
                            b.setNeutralButton(opts[2], (d, w) -> resolveAsk(opts[2], myEpoch));
                        }
                        b.setCancelable(false);
                        pendingAskDialog = b.show();
                    } catch (Throwable e) {
                        android.util.Log.w("DSHA", "提问弹窗弹出失败，仍可从通知回答：" + e);
                    }
                });
            }

            try {
                boolean answered = latch.await(120, TimeUnit.SECONDS);
                if (!answered) return "[TIMEOUT] 用户 120 秒内没有回答";
                return askAnswer.isEmpty() ? "[DISMISSED] 用户关掉了提问框" : askAnswer;
            } catch (InterruptedException e) {
                return "[INTERRUPTED]";
            }
        } finally {
            pendingAskLatch = null;
            dismissAskDialog();
            cancelAskNotification();
            askBusy.set(false);
        }
    }

    /** /app/export?path=/root/x.md&name=x.md ：把文件导出到 Download/DSHA（走 MediaStore，用户可直接在文件管理器看到） */
    private String appExport(String path) {
        try {
            String q = queryOf(path);
            String src = getParam(q, "path", "");
            if (src.isEmpty()) return "NO_PATH";
            String name = getParam(q, "name", "");
            java.io.File f = new java.io.File(src);
            if (!f.isFile()) {
                // 允许传 rootfs 内的 guest 路径（/root/... → 映射到 App 私有目录）
                try {
                    HarnessController hc = HarnessController.get(ctx);
                    java.io.File guess = new java.io.File(hc.getProot().getRootfsDir(),
                            src.startsWith("/") ? src.substring(1) : src);
                    if (guess.isFile()) f = guess;
                } catch (Throwable ignored) {
                }
            }
            if (!f.isFile()) return "NOT_FOUND: " + src;
            if (f.length() > 64L * 1024 * 1024) return "TOO_LARGE: " + f.length();
            if (name.isEmpty()) name = f.getName();
            if (name.contains("/") || name.contains("..")) return "BAD_NAME";
            String out = BackupManager.exportToDownloads(ctx, f, name);
            return out == null ? "ERROR: 导出失败（存储权限或空间不足）" : "OK: " + out;
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

/** 从（仅含 query 的）查询串提取参数。调用方务必先截取 '?' 之后的内容。 */
    private static String getParam(String q, String key, String def) {
        return Query.param(q, key, def);
    }

    // 便捷包装：路径中取 query 部分
    private static String queryOf(String path) {
        return Query.of(path);
    }

    private boolean confirmEnabled() {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true);
    }

    /** 危险命令：挂起等待用户确认（前台弹窗 / 后台通知），超时默认拒绝 */
    private String awaitConfirm(String cmd) {
        return requestUserConfirm(cmd) ? ShizukuShell.exec(cmd) : "[USER_REJECTED]";
    }

    /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用。
     *  通知与弹窗同时发：只走弹窗的话，Activity 一被 pause 用户就再也看不见，
     *  只能干等 60s 超时——这正是「弹窗有时不出现」的由来。（吸收上游 PR#24） */
    private boolean requestUserConfirm(String cmd) {
        if (!confirmBusy.compareAndSet(false, true)) {
            return false; // 已有确认在进行：拒绝新的（避免 pendingLatch 互相覆盖）
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            // epoch 先递增：上一轮残留的弹窗/通知按钮带的是旧 epoch，会被丢弃
            final long myEpoch = confirmEpoch.incrementAndGet();
            pendingAllow = false;   // 先写标志，再发布 latch
            confirmResolved.set(false);  // 必须早于发布 latch：latch 一露面就可能有点击进来
            pendingLatch = latch;

            // 通知是权威渠道（前后台都在），前台再叠一个弹窗当快捷方式
            showConfirmNotification(cmd, myEpoch);
            // 第三条渠道：悬浮条上就地批准。agent 干活时用户往往并不在 App 里 ——
            // 拉下通知栏找那条通知、或者切回 App，都比点一下已经浮在最上层的按钮慢。
            // 三条渠道共用同一个 epoch + latch，谁先点谁生效。
            OverlayController.askConfirm(ctx, cmd,
                    () -> resolveConfirm(true, myEpoch),
                    () -> resolveConfirm(false, myEpoch));
            final MainActivity act = MainActivity.current;
            if (act != null) {
                final String prompt = "模型试图在设备上执行：\n" + cmd + "\n\n是否允许？";
                act.runOnUiThread(() -> {
                    // 正在 finishing 的 Activity 上 show() 会抛 BadTokenException，
                    // 而这里是主线程，异常不在 handle() 的 catch 范围内 → 会崩 App
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        pendingDialog = new androidx.appcompat.app.AlertDialog.Builder(act)
                                .setTitle("DSHA 安全确认")
                                .setMessage(prompt)
                                // 必须明确选一个：误触关闭不再被当作拒绝。也不要在
                                // OnDismiss/OnCancel 里 countDown —— Activity 被 pause
                                // 导致的 dismiss 会误判成「用户拒绝」，而用户还能从通知里点。
                                .setCancelable(false)
                                .setPositiveButton("允许", (d, w) -> resolveConfirm(true, myEpoch))
                                .setNegativeButton("拒绝", (d, w) -> resolveConfirm(false, myEpoch))
                                .show();
                    } catch (Throwable t) {
                        android.util.Log.w("DSHA", "确认弹窗弹出失败，仍可从通知确认：" + t);
                    }
                });
            } else if (!notificationsEnabled()) {
                // 后台 + 通知被拒 = 用户看不到任何提示，只能干等 60s 超时被拒。
                // 至少留下日志，别让这变成无从排查的「命令莫名被拒」。
                android.util.Log.w("DSHA", "无前台界面且通知权限被拒，确认必然超时拒绝：" + cmd);
            }

            try {
                boolean finished = latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
                return finished && pendingAllow;
            } catch (InterruptedException e) {
                return false;
            }
        } finally {
            // 顺序要紧：清理全部做完，最后才放开 confirmBusy。反过来的话，
            // 下一个请求会抢在清理前发出新通知，而 cancelConfirmNotification()
            // 用的是固定通知 ID，会把它刚发的那条取消掉。
            pendingLatch = null;
            dismissConfirmDialog();
            cancelConfirmNotification();
            OverlayController.dismissConfirm(ctx);
            confirmBusy.set(false);
        }
    }

    private boolean notificationsEnabled() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // framework API 24+，比运行时权限检查更准（用户在设置里关掉通知也算）
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable e) {
            return true; // 判断不了就别妄下结论
        }
    }

    /** 通知按钮（ConfirmReceiver）、前台弹窗按钮与悬浮条按钮共用的回调。
     *  epoch 校验 + 原子认领：丢弃迟到的（属于上一个请求的）点击，以及同一轮里后到的那次。 */
    public void resolveConfirm(boolean allow, long epoch) {
        if (epoch != confirmEpoch.get()) {
            android.util.Log.i("DSHA", "忽略过期的确认点击（epoch " + epoch + "）");
            return;
        }
        CountDownLatch l = pendingLatch;
        if (l == null || l.getCount() == 0) return; // 已决或无挂起（快速路径）
        // 真正的认领在这里，且必须原子 —— 上面那个 getCount 检查挡不住两条渠道同时点。
        if (!confirmResolved.compareAndSet(false, true)) return;
        pendingAllow = allow;
        l.countDown();
        dismissConfirmDialog();
        cancelConfirmNotification();
    }

    /** 关掉挂起的弹窗：setCancelable(false) 让它自己关不掉，确认完成后必须主动 dismiss，
     *  否则它会滞留在屏幕上，用户后来点它就把授权打到下一个请求上了。
     *  先把引用摘到局部变量再置 null，这样即使下一个请求已设好新弹窗也不会误关它。 */
    private void dismissConfirmDialog() {
        final androidx.appcompat.app.AlertDialog d = pendingDialog;
        if (d == null) return;
        pendingDialog = null;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (d.isShowing()) d.dismiss();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void showConfirmNotification(String cmd, long epoch) {
        createConfirmChannel();
        String shortCmd = cmd.length() > 100 ? cmd.substring(0, 100) + "…" : cmd;
        Intent openAppIntent = new Intent(ctx, MainActivity.class)
                .putExtra("open_web", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 30, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // epoch 随 Intent 带回：残留通知上的旧按钮会因 epoch 过期被丢弃
        Intent allowI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_ALLOW)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        Intent denyI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_DENY)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        PendingIntent allowPi = PendingIntent.getBroadcast(ctx, 31, allowI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent denyPi = PendingIntent.getBroadcast(ctx, 32, denyI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("⚠️ DSHA 安全确认")
                .setContentText("模型试图执行：" + shortCmd)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("模型试图在设备上执行：\n" + cmd + "\n\n是否允许？"))
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .addAction(0, "允许", allowPi)
                .addAction(0, "拒绝", denyPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CONFIRM_NOTIF_ID, n);
    }

    private void cancelConfirmNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CONFIRM_NOTIF_ID);
    }

    private void showAskNotification(String q, String[] opts, long epoch) {
        createConfirmChannel();
        String shortQ = q.length() > 100 ? q.substring(0, 100) + "…" : q;
        Intent openAppIntent = new Intent(ctx, MainActivity.class)
                .putExtra("open_web", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 39, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("💬 助手提问")
                .setContentText(shortQ)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(q))
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        for (int i = 0; i < opts.length; i++) {
            String opt = opts[i];
            Intent intent = new Intent(ctx, ConfirmReceiver.class)
                    .setAction(ConfirmReceiver.ACTION_ASK_ANSWER)
                    .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch)
                    .putExtra(ConfirmReceiver.EXTRA_ANSWER, opt);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 40 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(0, opt, pi);
        }

        // 挂载 RemoteInput 直接就地文本输入快捷回复
        androidx.core.app.RemoteInput remoteInput = new androidx.core.app.RemoteInput.Builder(ConfirmReceiver.EXTRA_REPLY_TEXT)
                .setLabel("输入回复内容...")
                .build();
        Intent replyIntent = new Intent(ctx, ConfirmReceiver.class)
                .setAction(ConfirmReceiver.ACTION_ASK_REPLY)
                .putExtra(ConfirmReceiver.EXTRA_EPOCH, epoch);
        PendingIntent replyPi = PendingIntent.getBroadcast(ctx, 49, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_launch, "💬 快捷输入", replyPi)
                .addRemoteInput(remoteInput)
                .build();
        nb.addAction(replyAction);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Constants.NOTIF_ASK_QUESTION, nb.build());
    }

    private void cancelAskNotification() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.NOTIF_ASK_QUESTION);
        } catch (Throwable ignored) {}
    }

    private void dismissAskDialog() {
        final androidx.appcompat.app.AlertDialog d = pendingAskDialog;
        if (d == null) return;
        pendingAskDialog = null;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (d.isShowing()) d.dismiss();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    public void resolveAsk(String answer, long epoch) {
        if (epoch != askEpoch.get()) {
            android.util.Log.i("DSHA", "忽略过期的提问点击（epoch " + epoch + "）");
            return;
        }
        CountDownLatch l = pendingAskLatch;
        if (l == null || l.getCount() == 0) return;
        if (!askResolved.compareAndSet(false, true)) return;
        askAnswer = answer;
        l.countDown();
        dismissAskDialog();
        cancelAskNotification();
    }

    private void createConfirmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CONFIRM_CHANNEL, "安全确认",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("模型执行危险操作时的确认提醒");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
