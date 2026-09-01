package com.deepseekharness.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
// GeckoView（内置浏览器内核兜底：系统 WebView 过旧时前端 JS 崩 → 白屏转圈）
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/** 启动页：状态 + 日志。点「进入」才在本 App 的 WebView 里打开，不跳系统浏览器。 */
public class LaunchFragment extends Fragment {

    private HarnessController c;
    private TextView runDot, runState, statusText, lanAddrText, logText;
    private ScrollView logScroll;
    private Button startBtn;
    /** 启动等待期的细进度条（不确定式）：让用户知道在动，而不是以为点了没反应 */
    private ProgressBar busyBar;
    /** 本次「启动/重启」按下的时刻，用于显示已等待秒数；0=不在等待中 */
    private long startingAt = 0;
    private View homePane, webPane;
    private FrameLayout webBox;
    private WebView webView;

    private boolean webReady = false;
    private boolean starting = false;
    private boolean enterWhenReady = false;
    private boolean insideWeb = false;
    private String lastLog = "";
    /** 上一次已经提示过的插件故障结论：日志每 1.5 秒刷一次，别重复往活动日志里写。 */
    private String lastPluginHint = "";
    /** 日志文件指纹（size+mtime），未变化则跳过重读（每 1.5s 轮询时省一次文件 IO） */
    private long lastLogSize = -1;
    private long lastLogMtime = -1;

    private ValueCallback<Uri[]> filePathCallback;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tickOnce;
    private final HarnessController.StateListener stateListener = this::refreshHint;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                    filePathCallback = null;
                }
            });

    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            closeWeb();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launch, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        homePane = view.findViewById(R.id.launch_home);
        webPane = view.findViewById(R.id.launch_web);
        webBox = view.findViewById(R.id.launch_web_box);
        runDot = view.findViewById(R.id.launch_run_dot);
        runState = view.findViewById(R.id.launch_run_state);
        statusText = view.findViewById(R.id.launch_status);
        busyBar = view.findViewById(R.id.launch_busy);
        lanAddrText = view.findViewById(R.id.lan_addr);
        logText = view.findViewById(R.id.launch_log);
        logScroll = view.findViewById(R.id.launch_log_scroll);
        startBtn = view.findViewById(R.id.launch_start);
        Button restartBtn = view.findViewById(R.id.launch_open);
        Button stopBtn = view.findViewById(R.id.launch_stop);

        updateLanAddr();
        applyRunUi(false);
        refreshHint();
        c.addStateListener(stateListener);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backToHome);

        mainHandler.postDelayed(() -> new Thread(() -> {
            try {
                c.ensureWatchdogFiles();
            } catch (Throwable ignored) {
            }
            try {
                c.maybePrewarmWeb();
            } catch (Throwable ignored) {
            }
        }, "dsha-prewarm").start(), 1500);

        startBtn.setOnClickListener(v -> {
            // 「手动停止」标记不在这里清 —— HarnessController.startWeb() 开头统一清掉，
            // 这样通知栏「重启」等别的入口也一样解除，不必每个入口自己记得（漏一个就又
            // 出现「启动了但保活仍不拉起」这类怪状态）。
            if (webReady) {
                openWeb();
                return;
            }
            if (goExtractIfNeeded()) return;
            if (!c.getProot().isOfflineExtracted()) {
                Toast.makeText(requireContext(), "内置环境尚未就绪，请先等解压完成", Toast.LENGTH_LONG).show();
                return;
            }
            starting = true;
            enterWhenReady = true;
            startingAt = System.currentTimeMillis();
            applyRunUi(false);
            statusText.setText("正在启动，起来后直接进入…");
            Intent i = new Intent(requireContext(), HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
        });

        restartBtn.setOnClickListener(v -> {
            if (goExtractIfNeeded()) return;
            closeWeb();
            starting = true;
            enterWhenReady = true; // 重启完成后自动回到预览页
            startingAt = System.currentTimeMillis();
            applyRunUi(false);
            statusText.setText("正在重启…");
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
        });

        stopBtn.setOnClickListener(v -> {
            closeWeb();
            starting = false;
            enterWhenReady = false;
            webReady = false;
            startingAt = 0;
            applyRunUi(false);
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            statusText.setText("正在停止…");
        });

        if (goExtractIfNeeded()) {
            statusText.setText("正在打开内置环境解压页…");
        } else if (c.getProot().isOfflineExtracted()) {
            statusText.setText("环境已就绪。点「启动」起来后会直接进入。");
        } else {
            statusText.setText("环境未就绪。若刚装好 APK，请杀掉进程再打开一次以进入解压页。");
        }

        if (getActivity() != null && getActivity().getIntent() != null) {
            Intent actIntent = getActivity().getIntent();
            if (actIntent.getBooleanExtra("open_web", false) || actIntent.getBooleanExtra("auto_enter_web", false)) {
                enterWhenReady = true;
            }
        }

        mainHandler.post(tick);
    }

    /** 供通知点击或外部意图唤醒：直接进入 Web 对话界面 */
    public void enterWebDirectly() {
        if (!isAdded()) return;
        if (webReady && !insideWeb) {
            openWeb();
        } else if (!insideWeb) {
            enterWhenReady = true;
            if (!starting && c != null && c.getProot().isOfflineExtracted() && startBtn != null) {
                startBtn.performClick();
            }
        }
    }

    /**
     * 心跳间隔：状态还在变的时候要勤，稳定之后没必要。
     *
     * <p>原来固定 1.5 秒 —— 也就是说用户停在启动页或 WebUI 里的整段时间，每分钟 40 次
     * HTTP 探测 + 40 次日志指纹检查。Web 已经起来、人也进去看了的时候，这些只是维持
     * 状态灯与地址 chip，4 秒一次完全够。而「等启动」那段仍然 1.5 秒：那里的等待秒数要
     * 走字、就绪后还要自动跳进 WebUI，慢了会被当成卡住。
     */
    private long nextTickDelayMs() {
        if (starting || !webReady) return 1500;   // 还在等启动：保持灵敏
        return insideWeb ? 4000 : 2500;
    }

    private void tickOnce() {
        if (!isAdded()) return;        new Thread(() -> {
            final boolean up = httpOk(uiUrl());
            final String log = readWebLogTail();
            if (!isAdded()) return;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (up) {
                    starting = false;
                    startingAt = 0;
                }
                webReady = up;
                applyRunUi(up);
                refreshHint(); // 每次心跳刷新一次状态行（启动等待期的秒数在这里走字）
                updateLanAddr(); // 地址 chip 也跟着心跳：局域网后开、WiFi 换网段都能刷新
                if (up && enterWhenReady && !insideWeb) {
                    enterWhenReady = false;
                    openWeb();
                }
                if (!insideWeb && log != null && !log.equals(lastLog)) {
                    lastLog = log;
                    // 插件类故障的原始报错基本读不了（一屏栈 + 中间半行插件名），
                    // 而它恰恰是「Web 打不开」最常见的原因。认出来就把结论摆在日志上方，
                    // 别让用户对着栈猜、更别让他去清数据重装（有人这么试过，白费）。
                    String hint = PluginErrorHint.describe(log);
                    if (!hint.isEmpty()) {
                        logText.setText(hint + "\n\n———— 原始日志 ————\n" + log);
                        if (!hint.equals(lastPluginHint)) {
                            lastPluginHint = hint;
                            // 记进活动日志：用户过后回想「刚才到底怎么了」还能查到
                            try {
                                c.logActivity("Web 启动受阻，疑似插件问题："
                                        + hint.replace("\n", " "));
                            } catch (Throwable ignored) {
                            }
                        }
                    } else {
                        logText.setText(log.isEmpty() ? "还没有日志。" : log);
                    }
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
                mainHandler.postDelayed(tick, nextTickDelayMs());
            });
        }, "dsha-launch-tick").start();
    }

    private void applyRunUi(boolean up) {
        if (up) {
            runDot.setTextColor(requireContext().getColor(R.color.ok));
            runState.setText("DSH 运行中");
            startBtn.setText("进入");
        } else if (starting || c.isWebRunning()) {
            runDot.setTextColor(requireContext().getColor(R.color.warn));
            runState.setText("DSH 启动中");
            startBtn.setText("启动");
        } else {
            runDot.setTextColor(requireContext().getColor(R.color.text_muted));
            runState.setText("DSH 未运行");
            startBtn.setText("启动");
        }
        // 等待期才显示细进度条（跑起来/未运行都收起，界面不留噪声）
        if (busyBar != null) {
            busyBar.setVisibility(!up && starting ? View.VISIBLE : View.GONE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openWeb() {
        if (insideWeb) return;
        insideWeb = true;
        homePane.setVisibility(View.GONE);
        webPane.setVisibility(View.VISIBLE);
        backToHome.setEnabled(true);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(false);
        }
        boolean useGecko = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("gecko_core", false);
        // 自动检测：系统 WebView 过旧（Chrome < 118，前端需要 AbortSignal.any/timeout）
        // → 强制用 GeckoView（内置内核，版本新）。用户也可手动开 gecko_core。
        if (!useGecko) {
            try {
                String ua = WebSettings.getDefaultUserAgent(requireContext());
                java.util.regex.Matcher cm = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
                if (cm.find() && Integer.parseInt(cm.group(1)) < 118) {
                    android.util.Log.w("DSHA", "系统 WebView 过旧 (Chrome/" + cm.group(1)
                            + " < 118)，自动切换 GeckoView");
                    useGecko = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (useGecko) {
            // GeckoView 兜底：系统 WebView 过旧（Chrome<118）时前端 JS 崩
            try {
                // 已有 GeckoView（child>0）→ 复用并刷新；否则新建
                GeckoView gv = null;
                for (int i = 0; i < webBox.getChildCount(); i++) {
                    if (webBox.getChildAt(i) instanceof GeckoView) {
                        gv = (GeckoView) webBox.getChildAt(i);
                        break;
                    }
                }
                if (gv == null) {
                    GeckoRuntime runtime = GeckoRuntime.getDefault(requireContext());
                    gv = new GeckoView(requireContext());
                    webBox.addView(gv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                GeckoSession gs = gv.getSession() != null
                        ? gv.getSession() : new GeckoSession();
                if (gv.getSession() == null) {
                    gs.open(GeckoRuntime.getDefault(requireContext()));
                    gv.setSession(gs);
                }
                attachGeckoDownload(gs);
                gs.loadUri(uiUrl());
                return; // GeckoView 加载，不走 WebView
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "GeckoView 启动失败，回退 WebView: " + e);
            }
        }
        if (webView == null) {
            webView = new WebView(requireContext());
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            // 现代前端特性：混合内容（http 页面加载资源）+ 数据库 + 多窗口
            // 我们只加载 http://127.0.0.1:<port>，不需要混合内容全放行。
            // ALWAYS_ALLOW 会让页面内任何 https 框架都能拉 http 资源（可被中间人注入）。
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            // API 29 及以下 allowFileAccess 默认为 true：显式关掉。
            // WebUI 全部走 http，用不到 file://，留着只是多一条攻击面。
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setDatabaseEnabled(true);
            ws.setSupportMultipleWindows(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);
            boolean desktop = requireContext()
                    .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getBoolean("desktop_mode", false);
            if (desktop) {
                ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            }
            webView.setWebViewClient(new WebViewClient());
            // 系统 WebView 的下载：DownloadListener 只给 URL，得自己再发一次请求。
            // 产物与 GeckoView 那条路落同一个目录（Download/DSHA/下载/）。
            webView.setDownloadListener((url, ua, cd, mime, len) -> {
                final android.content.Context appCtx = requireContext().getApplicationContext();
                android.widget.Toast.makeText(appCtx, "开始下载…",
                        android.widget.Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    String name = DownloadSink.guessName(url, cd, "download");
                    String path = DownloadSink.download(appCtx, url, name, mime);
                    final String msg = path == null
                            ? "下载失败：" + name : "已保存到 " + path;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(appCtx, msg,
                                    android.widget.Toast.LENGTH_LONG).show());
                }).start();
            });
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                                 FileChooserParams params) {
                    filePathCallback = cb;
                    String[] accept = params.getAcceptTypes();
                    String mime = (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty())
                            ? accept[0] : "*/*";
                    pickFile.launch(mime);
                    return true;
                }
            });
            webBox.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        webView.loadUrl(uiUrl());
    }

    public void closeWeb() {
        if (!insideWeb) return;
        insideWeb = false;
        webPane.setVisibility(View.GONE);
        homePane.setVisibility(View.VISIBLE);
        backToHome.setEnabled(false);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    private void refreshHint() {
        if (!isAdded() || statusText == null) return;
        String line = statusLine();
        if (!line.isEmpty()) statusText.setText(line);
    }

    /** 组装状态行：错误/进度消息优先，启动等待期在最前面附一行「已等待 N 秒」，
     *  让用户知道进程在动（之前只有一句静态的「正在启动…」，等 40 秒会以为卡死）。 */
    private String statusLine() {
        String base = "";
        if (c.getError() != null && !c.getError().isEmpty()) {
            base = c.getError();
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            base = c.getMessage();
        } else if (c.isBusy()) {
            base = c.getStage();
        }
        if (starting && !webReady && startingAt > 0) {
            long sec = (System.currentTimeMillis() - startingAt) / 1000;
            String wait = "正在启动… 已等待 " + sec + " 秒"
                    + (sec >= 45 ? "（偏慢了，可看下方日志尾部）" : "（通常 20~60 秒）");
            base = base.isEmpty() ? wait : wait + "\n" + base;
        }
        // 没填 key 也允许安装和启动，但要给个明确去处，否则用户会卡在「为什么不能对话」
        if (webReady && c.getApiKey().isEmpty()) {
            String tip = "未配置 API key —— 在 WebUI 的设置里选服务商并填入密钥即可开始对话"
                    + "（可用第三方接口地址）";
            base = base.isEmpty() ? tip : base + "\n" + tip;
        }
        return base;
    }

    /**
     * 给 GeckoSession 接上下载。
     *
     * <p>GeckoView 对「不能内联显示的响应」默认直接丢弃 —— 从来没设过 ContentDelegate
     * 的后果就是：WebUI 里点导出/下载什么反应都没有，既不报错也不落文件。产物统一放
     * {@code Download/DSHA/下载/}。
     *
     * <p>响应体必须在后台线程读（主线程读会卡住 UI，大文件直接 ANR）。
     */
    private void attachGeckoDownload(GeckoSession gs) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        gs.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onExternalResponse(@NonNull GeckoSession session,
                                           @NonNull org.mozilla.geckoview.WebResponse response) {
                new Thread(() -> {
                    String cd = headerOf(response.headers, "Content-Disposition");
                    String mime = headerOf(response.headers, "Content-Type");
                    String name = DownloadSink.guessName(response.uri, cd, "download");
                    String path = null;
                    try {
                        if (response.body != null) {
                            path = DownloadSink.save(appCtx, response.body, name, mime);
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("DSHA", "GeckoView 下载失败: " + t);
                    }
                    final String msg = path == null ? "下载失败：" + name : "已保存到 " + path;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(appCtx, msg,
                                    android.widget.Toast.LENGTH_LONG).show());
                }).start();
            }
        });
    }

    /** HTTP 头名大小写不敏感取值（GeckoView 给的 map 不保证大小写）。 */
    private static String headerOf(java.util.Map<String, String> headers, String key) {
        if (headers == null || key == null) return null;
        String v = headers.get(key);
        if (v != null) return v;
        for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private String uiUrl() {
        String base = "http://127.0.0.1:" + c.getPort() + "/";
        // dsh 的 Web 服务加了 token 鉴权（本机任何 App 都能访问 127.0.0.1，
        // 上游只绑回环、没有鉴权层）。首帧带上 token，服务端回设 Cookie，
        // 之后的静态资源、XHR 与 WebSocket 都自动带，页面里不必到处拼。
        String t = HttpShellService.currentToken();
        return t.isEmpty() ? base : base + "?dsha_t=" + android.net.Uri.encode(t);
    }

    /** 启动页那行可点的地址 chip。
     *
     *  <p>用户反馈「启动页给的 URL 用不了，AI 找出来 :3080/?dsha_t=... 才是对的」。
     *  原因是这里<b>只显示局域网地址</b>（3081），而那条链当时是坏的 ——
     *  {@code stripTokenFromRequestLine} 把请求行的 HTTP 版本吃掉，后端直接 400。
     *  用手机自带浏览器打开所需要的本机地址（带 dsh 自己的 {@code dsha_t}）
     *  从来没有在界面上出现过，用户只能让 agent 去日志里挖。
     *
     *  <p>现在一行 chip 收两个入口，点开再选本机 / 同 WiFi。另外它以前只在
     *  onViewCreated 算一次 —— 局域网后来才开、或者 WiFi 换了网段都不会刷新，
     *  现在跟着心跳走。 */
    private void updateLanAddr() {
        if (!webReady) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        lanAddrText.setText("在浏览器中打开 ▸ 点这里取地址（本机 / 同 WiFi）");
        lanAddrText.setVisibility(View.VISIBLE);
        lanAddrText.setOnClickListener(v -> showBrowserAddrDialog());
    }

    /** 列出可用的浏览器访问地址。地址里的 token 就是凭据，所以复制后要提醒一句。 */
    private void showBrowserAddrDialog() {
        final String local = uiUrl();
        boolean lan = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        String ip = HarnessController.getLanAddress();
        final String lanAddr = (lan && ip != null && !ip.isEmpty())
                ? "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                        + LanProxyService.getLanToken(requireContext())
                : null;

        final java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();

        items.add("用本机浏览器打开\n" + local);
        acts.add(() -> AboutDialog.openBrowser(requireContext(), local));
        items.add("复制本机地址");
        acts.add(() -> copyAddr("本机地址", local));
        if (lanAddr != null) {
            items.add("复制局域网地址（同 WiFi 的其它设备用）\n" + lanAddr);
            acts.add(() -> copyAddr("局域网地址", lanAddr));
        } else if (lan) {
            items.add("局域网已开启，但还没拿到 WiFi 地址（连上 WiFi 再看）");
            acts.add(() -> { });
        } else {
            items.add("局域网访问未开启 —— 去「配置」页打开后再来取地址");
            acts.add(() -> { });
        }

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("在浏览器中打开")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < acts.size()) acts.get(which).run();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyAddr(String label, String addr) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(label, addr));
            // token 就写在地址里，等于密码 —— 说清楚，别让人随手发到群里
            Toast.makeText(requireContext(), label + "已复制（里面的 token 相当于密码，别外发）",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(requireContext(), "复制失败：" + addr, Toast.LENGTH_LONG).show();
        }
    }

    private boolean goExtractIfNeeded() {
        try {
            if (!c.getProot().isOfflineExtracted()) {
                startActivity(new Intent(requireContext(), ExtractActivity.class));
                if (getActivity() != null) getActivity().finish();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean httpOk(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private String readWebLogTail() {
        try {
            File f = new File(c.getProot().getRootfsDir(), "root/dsh-web.log");
            if (!f.isFile() || f.length() == 0) return "";
            // 指纹未变：跳过重读（省 IO；日志不写时每 1.5s 轮询零成本）
            if (f.lastModified() == lastLogMtime && f.length() == lastLogSize) return lastLog;
            lastLogMtime = f.lastModified();
            lastLogSize = f.length();
            long len = f.length();
            long start = Math.max(0, len - 24000);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                // readFully 可能因日志被截断抛 EOF：改用尽力读
                int off = 0;
                while (off < buf.length) {
                    int n = raf.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                String s = new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
                if (start > 0) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0 && nl + 1 < s.length()) s = s.substring(nl + 1);
                }
                return s;
            }
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(tick);
        if (c != null) c.removeStateListener(stateListener);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
        if (webView != null) {
            webBox.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }
}
