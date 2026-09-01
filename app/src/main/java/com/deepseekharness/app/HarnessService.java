package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 前台服务：强后台保活 Web UI。
 *  - startForeground 常驻通知，降低被系统回收概率；
 *  - START_STICKY 被杀后由系统重启；
 *  - 建议引导用户加入电池优化白名单。
 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.deepseekharness.app.START";
    public static final String ACTION_STOP = "com.deepseekharness.app.STOP";
    public static final String ACTION_RESTART = "com.deepseekharness.app.RESTART";

    private static final String CHANNEL_ID = "dsh_harness_channel";
    private static final int NOTIF_ID = 1001;

    private HarnessController c;
    private HttpShellService shellHttp;
    private TaskNotifier taskNotifier;
    private final HarnessController.StateListener stateListener = this::refreshNotification;

    // ================= WebUI 监听保活 =================
    private Thread keepAliveThread;
    private volatile boolean keepAliveRunning;
    private final java.util.concurrent.atomic.AtomicBoolean restarting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastRestartAt = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long KEEPALIVE_INTERVAL_MS = 15000L;  // 探测间隔
    private static final long RESTART_COOLDOWN_MS = 120000L;   // 重启冷却：2 分钟内不重复拉起
    private static final int KEEPALIVE_MAX_FAIL = 3;           // 连续失败次数阈值

    @Override
    public void onCreate() {
        super.onCreate();
        c = HarnessController.get(this);
        createChannel();
        c.addStateListener(stateListener);
        startForeground(NOTIF_ID, buildNotification("DSHA运行中", "Web UI 正在后台保持运行"));
        // 桥接 Shizuku shell 能力（rootfs 里的助手可通过 127.0.0.1:3090 执行设备命令）
        shellHttp = new HttpShellService(this);
        shellHttp.start();
        ShizukuShell.ensureBound(this);
        // 任务完成通知已改为内置插件 dsh-task-notifier（turn/end 监听更准），
        // 旧 TaskNotifier 轮询停用（否则双重通知）
        // 局域网转发桥：开启局域网模式时，App 侧 0.0.0.0:3081 → 127.0.0.1:3080
        // （绕开官方 0.0.0.0 拦截与 Host 校验，Shizuku 式桥接思路；状态写 /root/dsh-lan.log 可终端查看）
        if (c.isLanMode()) {
            LanProxyService.start(c.getRootfsDirPath(), this, c.getPortInt());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 8+ 的硬性契约：凡是用 startForegroundService() 拉起的服务，
        // **必须在 5 秒内**调用 startForeground()，否则系统抛
        // ForegroundServiceDidNotStartInTimeException 直接杀掉进程。
        //
        // 原来 startForeground 只在 onCreate() 里调了一次。服务**首次**创建时没问题，
        // 但点「重启」时服务已经在跑，onCreate 不会再走，而 onStartCommand 里没有
        // —— 于是必然超时被强杀。用户看到的就是「点重启就闪退」，而且 crash.log
        // 是空的（系统强杀不走 UncaughtExceptionHandler，logcat 里也没有
        // FATAL EXCEPTION），这正是这个 bug 极难定位的原因。
        //
        // 所以这里无条件先把前台通知立起来（重复调用是允许且幂等的），之后再干活。
        try {
            startForeground(NOTIF_ID,
                    buildNotification("DSHA运行中", "Web UI 正在后台保持运行"));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "onStartCommand 里 startForeground 失败: " + e);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            c.stopWeb();
            stopKeepAlive();
            // 设备桥（127.0.0.1:3090）也一起停 —— 它是独立的后台服务，不跟着前台服务走。
            // 「停止」在用户眼里就是全停：留个监听端口在那儿既费电，也会让覆盖安装时系统
            // 多一个要终止的目标（装 391MB 包时那正是 Session destroyed 的诱因之一，
            // 所以装新包前点一下通知栏这个「停止」就够，不必再去设置页找入口）。
            try {
                stopService(new Intent(this, DeviceBridgeService.class));
            } catch (Throwable ignored) {
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            // 软重启：深停 → 等端口关透 → 重新拉起（不再杀 App 进程「闪退」重启）
            c.restartWeb();
            startKeepAlive();
            return START_STICKY;
        }
        // intent 为 null = 系统因 START_STICKY 把服务重建了。这时若用户此前明确停过，
        // 就不该再拉起 Web —— 否则他永远停不掉，只剩「强制停止」这一条路。
        // 前台服务壳留着（保持通知与后续可点启动），但不碰 Web 与保活。
        if (intent == null && !c.shouldAutoStartWeb("服务被系统重建")) {
            return START_STICKY;
        }
        startWeb();
        startKeepAlive();
        return START_STICKY;
    }

    private void startWeb() {
        c.startWeb();
    }

    /** 启动保活监听：TCP 探测 WebUI 端口，连续失联自动重启（带冷却防风暴） */
    /** 息屏保活用的两把锁。 */
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** ADB 后台加固每个进程只做一次（命令幂等，但起 python + adb 有几秒开销）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean hardenedOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 息屏保活：拿 {@code PARTIAL_WAKE_LOCK} 让 CPU 不随熄屏休眠。
     *
     * <p><b>WAKE_LOCK 权限在清单里声明了很久，但全树一直没有任何地方真正获取过 WakeLock。</b>
     * 后果是熄屏之后 CPU 进休眠，容器里的 node 被冻结，WebUI 自然失联；更糟的是保活线程
     * 自己也睡在 {@code Thread.sleep} 上、同样被冻住，连「发现失联再拉起」都做不到 ——
     * 于是熄屏一会儿回来，什么都停了。这是「息屏跑不住」最直接的一环。
     *
     * <p>顺带拿 WifiLock：熄屏后 WiFi 会进省电模式，3081 局域网桥与联网安装都会断。
     * {@code WIFI_MODE_FULL_HIGH_PERF} 在 Android 12+ 标了弃用但仍然生效，
     * 没有等价替代，所以照用。
     *
     * <p>代价是耗电，做成可关（{@code keepalive_wakelock}，默认开 —— 装 DSHA 本来就是
     * 要它在后台跑）。释放跟着 {@link #stopKeepAlive()} 走，与前台服务同生命周期。
     */
    private void acquireLocks() {
        try {
            if (!getSharedPreferences("deepseekharness", MODE_PRIVATE)
                    .getBoolean("keepalive_wakelock", true)) {
                return;
            }
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:web");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                android.util.Log.i("DSHA", "[保活] 已持有 PARTIAL_WAKE_LOCK");
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DSHA:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "[保活] 取锁失败（不致命，继续跑）: " + t);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }

    private void startKeepAlive() {
        stopKeepAlive();
        acquireLocks();
        // 顺手用 ADB 通道开一次后台白名单（Doze + appops）。幂等，所以每个进程只跑一次；
        // 异步跑是因为它要在容器里起 python + adb，几秒级，不能拖住前台服务的启动路径。
        // ADB 没接上时 hardenBackground 自己会跳过。
        if (hardenedOnce.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    c.hardenBackground();
                } catch (Throwable ignored) {
                }
            }, "dsha-harden").start();
        }
        keepAliveRunning = true;
        keepAliveThread = new Thread(() -> {
            int fail = 0;
            while (keepAliveRunning) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!keepAliveRunning) break;
                // 顺手守着设备桥：ADB 通道开着、但那个普通后台服务被系统回收时把它拉回来。
                // 前台服务的存活率高得多，用它当靠山最稳（否则 ADB 能力会静默消失）。
                try {
                    if (DeviceBridgeService.isAdbEnabled(HarnessService.this)
                            && !DeviceBridgeService.isRunning()) {
                        android.util.Log.w("DSHA", "[保活] 设备桥服务不在了，重新拉起");
                        DeviceBridgeService.apply(HarnessService.this);
                    }
                } catch (Throwable ignored) {
                }
                if (isWebUp()) {
                    fail = 0;
                    continue;
                }
                fail++;
                if (fail < KEEPALIVE_MAX_FAIL) continue;
                fail = 0;
                long now = System.currentTimeMillis();
                // 手动停止 / 会话自愈 / 刚停过的冷却期，统一判据在 shouldAutoStartWeb
                if (!c.shouldAutoStartWeb("保活")) continue;
                if (now - lastRestartAt.get() < RESTART_COOLDOWN_MS) continue; // 冷却期，等它自己缓过来
                lastRestartAt.set(now);
                if (restarting.compareAndSet(false, true)) {
                    try {
                        android.util.Log.w("DSHA", "[保活] WebUI 连续失联，自动重启");
                        c.startWeb();
                    } catch (Throwable ignored) {
                    } finally {
                        restarting.set(false);
                    }
                }
            }
        }, "dsha-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        releaseLocks();
        keepAliveRunning = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
    }

    /** TCP 探测 127.0.0.1:<port> 是否可达（proot 与宿主共享网络栈） */
    private boolean isWebUp() {
        int port;
        try {
            port = Integer.parseInt(c.getPort());
        } catch (Exception e) {
            return false;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshNotification() {
        if (c.getError() != null && !c.getError().isEmpty()) {
            updateNotification("DSHA启动失败", c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            updateNotification("DSHA运行中", "Web UI: http://127.0.0.1:" + c.getPort());
        }
    }

    public void onDestroy() {
        c.removeStateListener(stateListener);
        stopKeepAlive();
        if (shellHttp != null) shellHttp.stop();
        // taskNotifier 已停用（插件方案）
        LanProxyService.stop();
        c.stopWeb();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ================= 通知 =================
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DSHA后台服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 DeepSeek Harness Web UI 后台运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        // 点击常驻通知直接从屏幕底部弹出快捷对话抽屉（REORDER_TO_FRONT 唤醒常驻单例，避免销毁）
        Intent intent = new Intent(this, QuickChatSheetActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, "停止", stopPi)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }

}
