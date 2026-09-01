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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** 当前前台 Activity（HttpShellService 用它弹确认框）；null = 不在前台 */
    public static volatile MainActivity current = null;

    /** 自动恢复弹窗的「手动选择」出口。分区存储下 SAF 是读取「别的安装写进
     *  Download/DSHA 的备份」唯一不需要权限、也一定能成的办法（issue #22）。
     *  注册必须发生在 Activity 进入 STARTED 之前，所以放在字段初始化里。 */
    private final androidx.activity.result.ActivityResultLauncher<String[]> backupPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            HarnessController.get(this).restorePickedUri(this, uri);
                        }
                    });

    /** 供 HarnessController 的恢复弹窗调用：打开系统文件选择器挑备份包。 */
    public void pickBackupForRestore() {
        try {
            // MediaStore 给 tar.gz 记的 MIME 各家 ROM 不一，多给几个并兜 */*，
            // 否则用户会看到「没有可选文件」。
            backupPicker.launch(new String[]{"application/gzip", "application/x-gzip",
                    "application/x-tar", "application/octet-stream", "*/*"});
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开文件选择器：" + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 崩溃捕获已统一在 DshaApp 安装一次（防止 Activity 重建导致重复/覆盖 handler）

        // 首次启动进入引导页
        // 静态标志跨 Activity 存活：若上个 Activity 在恢复弹窗显示期间被销毁，
        // dismiss 回调不会触发，标志会永久卡在 true，权限弹窗从此再也不弹。
        HarnessController.restoreFlowActive = false;
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

        // 升级自动备份（幂等；rootfs 未就绪时内部自动跳过）
        HarnessController.get(this).upgradeGuard();
        // 每启动 5 次自动备份一次（固定名覆盖；与手动备份独立）
        HarnessController.get(this).maybeAutoBackupOnLaunch();
        // 检测 dsh 新版本 → 自动重跑⑥（安全守卫/补丁/内置插件适配新版本）
        HarnessController.get(this).maybeAutoReinstallGuardOnDshUpdate();
        // ADB 链路自动体检+自愈（打开即用：脚本/依赖/包装命令/连接，缺啥修啥）
        HarnessController.get(this).maybeAdbSelfHeal();
        // dsh 子包依赖完整性自愈（npmmirror 镜像元数据不一致导致 Cannot find module）
        HarnessController.get(this).maybeHealDshDeps();
        // write 工具悬空链接自愈（proot l2s 与 dsh 的 link 发布冲突；幂等秒回）
        HarnessController.get(this).maybeFixFsWrite();
        // 空 pets 目录清理（deepseek-pet 插件空目录会崩插件树）
        HarnessController.get(this).maybeCleanEmptyPets();
        // 会话损坏自愈（中途强杀导致 SQLite 写一半 → 历史加载失败）
        HarnessController.get(this).maybeHealSessionCorruption();
        // 步骤⑥版本对比：内置插件/补丁有更新时自动重跑（无需手动重装⑥）
        HarnessController.get(this).maybeRefreshStep6();
        // 内置插件注册自愈：⑥ 可能跑在 profile 生成之前（那时注册会被静默跳过），
        // 所以每次开 App 都校验一遍「设备引导插件是否真的注册进 bundles」
        HarnessController.get(this).ensureBuiltinPluginsReady();
        // 崩溃自愈提示：上次异常退出时读 crash.log 告知原因（不阻塞使用）
        showCrashRecoveryNotice();
        // 全新环境可恢复检测。走到这里 rootfs 一定已解压（skip_extract=true 来自
        // ExtractActivity，否则上面 isOfflineExtracted() 不通过就已跳走），所以不再限定
        // skip_extract —— 首启那次弹窗被用户划掉/进程被杀后，下次开 App 还有机会补上
        // （issue #22）。方法内部只在 .dsh 尚无用户数据时才弹，不会覆盖已有数据。
        HarnessController.get(this).maybePromptRestore(this);
        // 上次重解压没走完 → 数据保护目录还在，问用户要不要把数据恢复回来。
        // **必须排在升级提示之前**：数据没归位就再提示重解压，只会把同一个失败重复一遍。
        HarnessController.get(this).maybeOfferPreservedDataRecovery(this);
        // 离线包升级感知：APK 内置新离线包 → 提示重解压（数据自动保留）。
        // 放外面：正常启动（rootfs 已解压）也要检测，方法内部自带
        // isOfflineExtracted() 保护（首启未解压时静默）。
        HarnessController.get(this).maybeOfferOfflineUpgrade(this);

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
            // GitHub 链接输入框只在插件页有意义：切走就藏起来并清空，
            // 否则它会顶着别的页面的标题栏，还留着上次的内容。
            android.widget.EditText ghIn = findViewById(R.id.appbar_github_input);
            View spacer = findViewById(R.id.appbar_spacer);
            if (ghIn != null) {
                boolean onPlugins = id == R.id.nav_plugins;
                ghIn.setVisibility(onPlugins ? View.VISIBLE : View.GONE);
                if (spacer != null) spacer.setVisibility(onPlugins ? View.GONE : View.VISIBLE);
                if (!onPlugins) ghIn.setText("");
            }
            getSupportFragmentManager().popBackStack(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            Fragment f;
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                setAppTitle("启动");
            } else if (id == R.id.nav_terminal) {
                // 两套终端并存：默认 PTY 那套（跑得了 vim / htop / tmux），页内点「简易」
                // 可以退回旧的 TextView 版本 —— 新终端万一在某些机型上出问题，
                // 用户不至于连命令行都没了。选择记在 PtyTerminalFragment.KEY_PTY。
                f = PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
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
        handleIntentRouting(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentRouting(intent);
    }

    private void handleIntentRouting(Intent intent) {
        if (intent == null) return;

        // 1. 固定重置回到容器后台主页（关闭全屏网页并切到启动页）
        if (intent.getBooleanExtra("go_home", false)) {
            BottomNavigationView nav = findViewById(R.id.bottom_nav);
            if (nav != null && nav.getSelectedItemId() != R.id.nav_launch) {
                nav.setSelectedItemId(R.id.nav_launch);
            }
            Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (f instanceof LaunchFragment) {
                ((LaunchFragment) f).closeWeb();
            }
            return;
        }

        // 2. 直达全屏网页对话
        handleNotificationEnterWeb(intent);
    }

    private void handleNotificationEnterWeb(Intent intent) {
        if (intent != null && (intent.getBooleanExtra("open_web", false) || intent.getBooleanExtra("auto_enter_web", false))) {
            BottomNavigationView nav = findViewById(R.id.bottom_nav);
            if (nav != null && nav.getSelectedItemId() != R.id.nav_launch) {
                nav.setSelectedItemId(R.id.nav_launch);
            }
            Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (f instanceof LaunchFragment) {
                ((LaunchFragment) f).enterWebDirectly();
            }
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

    /** 崩溃自愈提示：上次有未处理崩溃时，读 crash.log 首条摘要告知用户（不阻塞，仅提示） */
    private void showCrashRecoveryNotice() {
        try {
            // 同一份 crash.log 只提醒一次（24h 去重，不删除日志本体，保留取证）
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            final java.io.File f = new java.io.File(getFilesDir(), "crash.log");
            if (!f.isFile() || f.length() == 0) return;
            if (System.currentTimeMillis() - prefs.getLong("crash_notice_shown", 0) < 24L * 3600 * 1000) {
                return;
            }
            prefs.edit().putLong("crash_notice_shown", System.currentTimeMillis()).apply();
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (all.trim().isEmpty()) return;
            // 只取最后一条崩溃的异常类型/消息（首个堆栈帧）
            String[] blocks = all.split("===== ");
            String last = blocks[blocks.length - 1];
            String summary = "";
            for (String line : last.split("\n")) {
                String t = line.trim();
                if (t.startsWith("java.") || t.startsWith("android.") || t.startsWith("kotlin.")) {
                    summary = t.length() > 180 ? t.substring(0, 180) : t;
                    break;
                }
            }
            final String info = summary.isEmpty() ? "发生异常" : summary;
            // 不影响提示：已读内容归档到 crash.log.prev（本轮 crash.log 保留供反复查看）
            try {
                java.io.File prev = new java.io.File(getFilesDir(), "crash.log.prev");
                //noinspection ResultOfMethodCallIgnored
                prev.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(prev);
            } catch (Throwable ignored) {
            }
            // 延迟 1.2 秒再弹：等主界面画完，不然对话框会和启动动画抢焦点。
            // 但延迟期间 Activity 可能已经被退掉（崩溃恢复场景下用户往往会立刻再退一次），
            // 那时 new AlertDialog.Builder(this).show() 会抛 BadTokenException，
            // 用户看到的是「刚从崩溃恢复又崩一次」。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                    .setTitle("上次异常退出")
                    .setMessage("DSHA 上次运行发生了未处理异常，已自动恢复。\n\n" + info
                            + "\n\n如果问题反复出现，请把内置终端里 `cat /data/data/com.dsh.client/files/crash.log.prev`（或 crash.log）的内容发给开发者。")
                    .setPositiveButton("知道了", null)
                    .show();
            }, 1200);
        } catch (Throwable ignored) {
        }
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // 用户拒绝通知权限：ADB 配对卡/任务完成提醒无法显示，给一次引导提示
            android.widget.Toast.makeText(this,
                    "未授予通知权限：ADB 配对卡片与任务完成提醒将不可用。\n可到系统设置 → 应用 → DSHA → 通知 开启。",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户回到 App 时催一次 ADB 探测：这一刻往往正要用它（内部有防抖与单飞）
        try {
            if (DeviceBridgeService.isAdbEnabled(this)) {
                DeviceBridgeService.kickNow(this, "回到 App");
            }
        } catch (Throwable ignored) {
        }
        current = this;
        TaskNotifier.appInForeground = true;
        // 从「所有文件访问」设置页返回时能立刻发现已授予 → 补跑一次数据迁移
        maybeRequestAllFilesAccess();
    }

    /** 申请「所有文件访问」（All Files Access）。
     *
     *  为什么需要：会话/设置/附件要迁到 /sdcard/Documents/dshdata 才能做到
     *  **卸载重装不丢**。而 Android 11+ 下没有这个权限就写不进公开目录，
     *  迁移脚本只会静默跳过 —— 用户以为数据安全了，其实还在私有目录里，
     *  一卸载全没。
     *
     *  这是特殊权限，不能用运行时弹窗授予，必须跳系统设置页由用户手动开。
     *  所以：说清理由 → 跳设置页 → 回来后自动补跑迁移。
     *  用户拒绝也不纠缠（只问一次），但自检里会持续提示风险。 */
    /** 供恢复流程结束后调用：那时才轮到我们问权限。 */
    void recheckAllFilesAccess() {
        maybeRequestAllFilesAccess();
    }

    private void maybeRequestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT < 30) return;      // 老系统本来就能直写公共目录
            // 恢复弹窗优先：它和我们要的是同一个权限，用户刚重装时恢复数据更紧急。
            // 直接 return 而不标记 asked_all_files —— 否则「只问一次」的额度
            // 会被这次让路白白用掉，等恢复流程结束就再也不问了。
            if (HarnessController.restoreFlowActive) return;
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            if (android.os.Environment.isExternalStorageManager()) {
                // 已授予：如果之前因为没权限跳过过迁移，这里补跑一次（幂等、失败无感）
                if (!prefs.getBoolean("public_data_migrated", false)) {
                    prefs.edit().putBoolean("public_data_migrated", true).apply();
                    final HarnessController hc = HarnessController.get(this);
                    new Thread(() -> {
                        try {
                            hc.migratePublicDataNow();
                        } catch (Throwable ignored) {
                        }
                    }, "dsha-migrate-after-grant").start();
                    // 授权后备份就能被枚举到了（#32 实测：授权前 0 个、授权后 14 个全可读）。
                    // 之前因为看不见而给出的「手动选择」提示，现在可以换成真正的恢复建议。
                    try {
                        prefs.edit().remove("restore_prompt_declined").apply();
                        hc.maybePromptRestore(this);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // 未授予且已经问过 → 不再打扰（自检里仍会报「卸载会丢数据」）
            if (prefs.getBoolean("asked_all_files", false)) return;
            // 正在结束的 Activity 上 show() 会抛 BadTokenException（本项目踩过一次）
            if (isFinishing() || isDestroyed()) return;
            prefs.edit().putBoolean("asked_all_files", true).apply();
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("让对话数据卸载重装不丢")
                    .setMessage("需要「所有文件访问」权限，把会话、设置、附件存到\n"
                            + "内部存储/Documents/dshdata\n\n"
                            + "· 卸载 App 或换机重装后数据仍在\n"
                            + "· 文件管理器里可以直接看到和备份\n"
                            + "· API Key 不会存进去（仍留在 App 私有区并加密）\n\n"
                            + "不开也能正常使用，但数据只存在 App 私有目录里，卸载即丢失。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Throwable e) {
                            // 个别 ROM 没有这个页面：退到应用详情页，用户仍能找到开关
                            try {
                                Intent i2 = new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                i2.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i2);
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .setNegativeButton("以后再说", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        current = null;
        TaskNotifier.appInForeground = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只在「真正退出」（finishing）时关闭终端持久 shell（防进程泄漏）；
        // 旋转屏幕/配置变化触发 onDestroy 时 isFinishing()=false，保留会话（否则转屏即丢终端）
        if (isFinishing()) {
            TerminalFragment.shutdownShell();
            PtyTerminalFragment.shutdown();
        }
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
            boolean apkNewer = tag != null && !tag.equals(ignored)
                    && UpdateChecker.isNewer(tag, current);
            if (!apkNewer) {
                // 应用本体已是最新 → 顺带看脚本层有没有小更新。
                // 只拉几 KB 的清单做比对，**不下载**任何脚本：更新动作留给用户手动确认，
                // 因为清单目前还没有签名，静默更新一条远程代码通道不合适。
                maybeHintRuntimeUpdate();
                return;
            }
            // 更新前自动存档：检测到新版先静默备份一次（同一版本只备份一次），
            // 防覆盖安装/下载期间出意外丢数据
            HarnessController.get(this).backupBeforeUpdate(tag);
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

    /** 脚本层有更新时温和提示一次，引导用户自己去配置页更新（不自动下载）。
     *
     *  纯手动的问题是用户根本想不起来去点；自动下载的问题是清单还没签名。
     *  折中就是这里：自动**检查**（几 KB），提示一次，动作仍由用户发起。
     *  同一批更新只提示一次 —— 用待更新文件名的指纹记住，别每次启动都烦人。 */
    private void maybeHintRuntimeUpdate() {
        try {
            RuntimeUpdater.Result probe = RuntimeUpdater.checkAndApply(
                    getApplicationContext(), HarnessController.get(this), true);
            if (probe.updated <= 0) return;
            StringBuilder key = new StringBuilder();
            for (String f : probe.changed) {
                key.append(f).append('|');
            }
            String fp = Integer.toHexString(key.toString().hashCode());
            final android.content.SharedPreferences sp = getSharedPreferences(
                    "deepseekharness", MODE_PRIVATE);
            if (fp.equals(sp.getString("runtime_hint_fp", ""))) return;   // 这批已经提过
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                        .setTitle("有脚本更新可用")
                        .setMessage(probe.updated + " 个脚本有新版本（合计通常只有几十 KB，"
                                + "不用重下整个应用）。\n\n"
                                + "到「配置」页点「检查脚本更新」即可查看具体改了哪些文件并更新。\n"
                                + "不更新也能正常使用。")
                        .setPositiveButton("知道了", (d, w) -> sp.edit()
                                .putString("runtime_hint_fp", fp).apply())
                        .setNegativeButton("不再提示这批", (d, w) -> sp.edit()
                                .putString("runtime_hint_fp", fp).apply())
                        .show();
            });
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "脚本更新检查失败（忽略）: " + e);
        }
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
                    // 别再猜原因：BackupManager 已经把真实失败原因记下来了
                    String why = BackupManager.lastError();
                    new AlertDialog.Builder(this)
                            .setTitle("备份失败")
                            .setMessage(why.isEmpty() ? "未知原因，请查看 logcat" : why)
                            .setPositiveButton("知道了", null)
                            .show();
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
