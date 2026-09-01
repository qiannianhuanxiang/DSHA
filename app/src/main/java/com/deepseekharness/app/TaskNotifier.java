package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务完成通知器：监控 rootfs 内「会话文件」（session.jsonl.zstd）的写入活动。
 * 判定规则（降低误报）：
 *  - 只统计会话文件变化（排除 settings.yaml 等非对话写入）
 *  - 连续 3 次轮询（约 12 秒）都有写入才判定"agent 干活中"
 *  - 干活中连续静默 90 秒 = 任务完成 → 发通知
 * App 在前台（用户正在看预览）时不打扰。
 */
public class TaskNotifier {

    public static final String CHANNEL_ID = Constants.CHANNEL_TASK_RESULT;
    private static final int NOTIF_ID = 2002;
    private static final long POLL_MS = 4000;
    private static final long IDLE_MS = 90000;      // 静默 90 秒判定完成（容忍 agent 长思考）
    private static final int ARM_STREAK = 3;        // 连续 3 次活跃（约 12 秒）才武装

    /** App 是否在前台（MainActivity 维护）；前台时不发通知 */
    public static volatile boolean appInForeground = false;

    private final Context ctx;
    private final HarnessController c;
    private ScheduledExecutorService executor;
    private long lastActive = 0;
    private int activeStreak = 0;
    private boolean armed = false;

    public TaskNotifier(Context ctx, HarnessController c) {
        this.ctx = ctx;
        this.c = c;
    }

    public void start() {
        if (executor != null) return;
        createChannel();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::tick, 5, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        armed = false;
        activeStreak = 0;
        lastActive = 0;
    }

    private void tick() {
        try {
            if (!c.isWebRunning() || appInForeground) return;
            // 只检测会话文件（agent 回复/思考记录），排除配置等非对话写入
            String out = c.getProot().execAndRead(
                    "find /root/.dsh -type f -name 'session*' -newermt '-"
                            + (POLL_MS / 1000 + 1) + " seconds' 2>/dev/null | head -3");
            boolean active = out != null && !out.trim().isEmpty();
            long now = System.currentTimeMillis();
            if (active) {
                lastActive = now;
                if (activeStreak < ARM_STREAK) activeStreak++;
                if (activeStreak >= ARM_STREAK) armed = true;
            } else {
                activeStreak = 0;
                if (armed && now - lastActive >= IDLE_MS) {
                    armed = false;
                    notifyDone();
                }
            }
        } catch (Exception ignored) {
            // 进程重启/网络抖动期间静默跳过
        }
    }

    private void notifyDone() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIF_TASK_RUNNING);
        }

        Intent intent = new Intent(ctx, QuickChatSheetActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 点击「💬 继续对话」直接从屏幕底部唤起抽屉弹层
        Intent actionIntent = new Intent(ctx, QuickChatSheetActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent actionPi = PendingIntent.getActivity(ctx, 26, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_launch, "💬 继续对话", actionPi)
                .build();

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle("DSHA · 任务完成")
                .setContentText("智能体已结束任务，点击查看结果")
                .setContentIntent(pi)
                .addAction(replyAction)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "任务结果与交互",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("智能体任务完成、异常结束或终止时的结果通知");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
