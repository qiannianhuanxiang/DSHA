package com.deepseekharness.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** 当前前台 Activity（HttpShellService 用它弹确认框）；null = 不在前台 */
    public static volatile MainActivity current = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 崩溃捕获：写日志到 files/crash.log，并继续交给系统默认 handler（保留 DropBox 崩溃报告）
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "crash.log");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                    fos.write(("\n===== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " =====\n"
                            + android.util.Log.getStackTraceString(t) + "\n").getBytes());
                }
            } catch (Exception ignored) {
            }
            // 转交系统默认 handler（否则系统 CrashReport/DropBox 收不到，只剩我们自己写的日志）
            if (prev != null) {
                prev.uncaughtException(thread, t);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        // 首次启动进入引导页
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("welcomed", false)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        if (!getIntent().getBooleanExtra("skip_extract", false)) {
            ProotBootstrap proot = new ProotBootstrap(this);
            if (!proot.isOfflineExtracted()) {
                startActivity(new Intent(this, ExtractActivity.class));
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);

        // 升级/首次启动自愈：自动备份旧环境；全新环境且 Download/DSHA 有旧备份时提示恢复
        if (HarnessController.get(this).upgradeGuard()) {
            // 仅解压完成进入主界面（skip_extract=true）才检测"全新环境可恢复"，
            // 避免首启解压前 rootfs 未就绪误弹恢复框（恢复内容会被解压流程覆盖）
            if (getIntent().getBooleanExtra("skip_extract", false)) {
                HarnessController.get(this).maybePromptRestore(this);
            }
        }

        requestPermissions();
        requestBatteryOptimization();
        maybeShowBackupReminder();
        maybeCheckUpdate();
        // ADB 默认关。只有用户在配置里勾选后才会拉设备桥。
        DeviceBridgeService.apply(this);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        View about = findViewById(R.id.btn_about);
        if (about != null) {
            about.setOnClickListener(v -> AboutDialog.show(this));
        }

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            getSupportFragmentManager().popBackStack(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            Fragment f;
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                setAppTitle("启动");
            } else if (id == R.id.nav_terminal) {
                f = new TerminalFragment();
                setAppTitle("终端");
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
                setAppTitle("市场");
            } else {
                f = new SettingsFragment();
                setAppTitle("设置");
            }
            switchFragment(f);
            return true;
        });
        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_launch);
        }
    }

    private void switchFragment(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, f)
                .commit();
    }

    /** 显示/隐藏底部导航栏（WebView 全屏时隐藏） */
    public void setBottomNavVisible(boolean visible) {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) nav.setVisibility(visible ? View.VISIBLE : View.GONE);
        View bar = findViewById(R.id.app_bar);
        if (bar != null) bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setAppTitle(String title) {
        android.widget.TextView t = findViewById(R.id.app_title);
        if (t != null) t.setText(title);
    }

    /** 自动申请所需权限：通知（前台服务需要）+ 电池优化白名单（保活） */
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        TaskNotifier.appInForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        current = null;
        TaskNotifier.appInForeground = false;
    }

    private void requestBatteryOptimization() {
        try {
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            if (prefs.getBoolean("asked_battery", false)) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.edit().putBoolean("asked_battery", true).apply();
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ================= 检查更新 =================
    /** 后台静默检查 GitHub Releases；发现新版弹窗（取消 = 本次忽略该版本） */
    private void maybeCheckUpdate() {
        final SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("check_update", true)) return;
        final String ignored = prefs.getString("ignored_version", "");
        final String current;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            String tag = UpdateChecker.checkLatestVersion();
            if (tag == null || tag.equals(ignored)) return;
            if (!UpdateChecker.isNewer(tag, current)) return;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + tag)
                    .setMessage("当前版本 v" + current + "\n是否前往下载？")
                    .setPositiveButton("更新", (d, w) -> AboutDialog.openBrowser(
                            this, "https://github.com/qiannianhuanxiang/DSHA/releases/latest"))
                    .setNegativeButton("取消", (d, w) -> prefs.edit()
                            .putString("ignored_version", tag).apply())
                    .show());
        }).start();
    }

    // ================= 备份提醒 =================
    // 提醒频率分级：默认每 6 次 → 勾选"少提醒我"依次升级为 15 / 30 / 100 次
    private static final int[] REMIND_INTERVALS = {6, 15, 30, 100};

    private void maybeShowBackupReminder() {
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        int count = prefs.getInt("launch_count", 0) + 1;
        int level = prefs.getInt("reminder_level", 0);
        int last = prefs.getInt("last_reminded", 0);
        prefs.edit().putInt("launch_count", count).apply();
        int interval = REMIND_INTERVALS[Math.min(level, REMIND_INTERVALS.length - 1)];
        if (count - last < interval) return;

        View box = LayoutInflater.from(this).inflate(R.layout.dialog_remind_backup, null);
        CheckBox lessCb = box.findViewById(R.id.remind_less);
        String[] labels = {
                "少提醒我（改为每 15 次提醒）",
                "少提醒我（改为每 30 次提醒）",
                "少提醒我（改为每 100 次提醒）"
        };
        if (level < labels.length) {
            lessCb.setText(labels[level]);
        } else {
            lessCb.setVisibility(View.GONE);
        }
        new AlertDialog.Builder(this)
                .setTitle("建议备份数据")
                .setMessage("已启动 " + count + " 次，建议把配置和对话记录导出到\n"
                        + "Download/DSHA 备份，防止意外丢失。")
                .setView(box)
                .setPositiveButton("立即备份", (d, w) -> {
                    confirmReminder(prefs, level, lessCb, count);
                    startBackup();
                })
                .setNegativeButton("取消", (d, w) ->
                        confirmReminder(prefs, level, lessCb, count))
                .show();
    }

    private void confirmReminder(SharedPreferences prefs, int level,
                                 CheckBox lessCb, int count) {
        if (lessCb != null && lessCb.isChecked()) {
            prefs.edit().putInt("reminder_level", level + 1).apply();
        }
        prefs.edit().putInt("last_reminded", count).apply();
    }

    /** 后台执行全量备份，完成后弹窗告知目录并可复制路径 */
    private void startBackup() {
        Toast.makeText(this, "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String path = BackupManager.backupToExternal(this, HarnessController.get(this));
            runOnUiThread(() -> {
                if (path == null) {
                    Toast.makeText(this, "备份失败：环境可能未安装或空间不足", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("备份完成")
                        .setMessage("已导出到：\n" + path)
                        .setPositiveButton("复制路径", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("backup", path));
                                Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("好", null)
                        .show();
            });
        }).start();
    }
}
