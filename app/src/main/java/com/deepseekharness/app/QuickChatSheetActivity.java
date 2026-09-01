package com.deepseekharness.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * 快捷对话底部抽屉弹层（纯代码动态构建，零外部 XML 依赖）：
 * 1. 左右 100% 铺满物理屏幕（Theme.DeepseekHarness.SheetTransparent + Decor 零边距）；
 * 2. 顶栏 4 按钮像素级规格统一（36x36dp 触摸区、1.85dp 中等线宽、圆角对齐、绝对对称居中）；
 *    - ① [ ✕ ] 关闭弹层
 *    - ② [ >_ ] 容器终端控制台
 *    - ③ [ 💬➕ ] 开启新对话（毫秒级 DOM 触发 / 路由重置）
 *    - ④ [ ⬒ ] 顺时针旋转 90° 的全屏展开聊天按钮
 * 3. 多档 15% 阶梯智能吸附停靠（35%/50%/65%/80%/95%），低于 25% 安全退出；
 * 4. 底部严格锁定在屏幕最底端，拖拽仅顶部上下伸缩；
 * 5. 全局静态 WebView 单例保活，再次弹出零转圈、零重新加载；
 * 6. 1:1 精准字体还原（移除 OverviewMode，设置 textZoom 100）；
 * 7. 注入透明全局 CSS 变量与 DOM 背景，100% 透出毛玻璃半透明卡片与桌面壁纸；
 * 8. 键盘弹出时：单次状态跃迁平滑拉升至默认高度，卡片顶部与底板绝对锁死在原位，底部全量铺满浅色底板，内部 WebView 视口等额收缩，输入框精准停靠在键盘正上方；
 * 9. 低位退出在动画完全结束后（onAnimationEnd）重置高度，彻底消除退出时的拉长闪屏。
 */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class QuickChatSheetActivity extends Activity {

    public static final int ICON_CLOSE = 1;
    public static final int ICON_SETTINGS = 2;
    public static final int ICON_NEW_CHAT = 3;
    public static final int ICON_FULLSCREEN = 4;

    // 全局静态保活单例，彻底解决再次进入重新转圈加载问题
    @SuppressLint("StaticFieldLeak")
    private static WebView sCachedWebView = null;
    private static boolean sWebLoaded = false;

    private FrameLayout rootOverlay;
    private LinearLayout sheetCard;
    private FrameLayout webContainer;
    private View keyboardSpacer;
    private ProgressBar progressBar;
    private TextView errorHint;
    private HarnessController controller;

    private int screenHeight = 0;
    private int defaultHeight = 0;
    private int maxHeight = 0;
    private int minHeight = 0;
    private int currentHeight = 0;
    private boolean isDismissing = false;
    private boolean isDarkMode = false;

    private float initialTouchY = 0f;
    private int initialHeightOnTouch = 0;

    // 键盘监听状态跃迁锁与动画控制器（彻底杜绝动画死锁）
    private boolean isKeyboardElevated = false;
    private ValueAnimator heightAnimator = null;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 窗口基础配置：全屏铺满、底部对齐（彻底锁死底部）、半透明遮罩、点击外部退出
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            // 采用 ADJUST_NOTHING：避免 Window 整体与卡片顶边被系统向上顶飞
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            if (window.getDecorView() != null) {
                window.getDecorView().setPadding(0, 0, 0, 0);
            }
        }

        controller = HarnessController.get(this);
        isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        calculateDimensions();
        setContentView(buildUi());
        setupGesture();
        setupKeyboardObserver();
        attachChatWeb();
        animateIn();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        animateIn();
    }

    private void calculateDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        // 初始高度设为 78%，全屏态 95%，最低安全退出阈值 25%
        defaultHeight = (int) (screenHeight * 0.78f);
        maxHeight = (int) (screenHeight * 0.95f);
        minHeight = (int) (screenHeight * 0.25f);
        currentHeight = defaultHeight;
    }

    private View buildUi() {
        // 毛玻璃半透明底色（浅色：#EBF5F8FC 半透轻白蓝；深色：#EB161B24 半透深灰）
        int cardBgColor = isDarkMode ? Color.parseColor("#EB161B24") : Color.parseColor("#EBF5F8FC");
        int textColor = isDarkMode ? Color.parseColor("#E8ECF4") : Color.parseColor("#1A2230");
        int lineColor = isDarkMode ? Color.parseColor("#302A3344") : Color.parseColor("#30E2E6EE");
        int handleColor = isDarkMode ? Color.parseColor("#704A5568") : Color.parseColor("#90CBD5E1");
        int borderColor = isDarkMode ? Color.parseColor("#352A3344") : Color.parseColor("#35CBD5E1");

        // 1. 根全屏透明遮罩容器（左右 100% 撑满）
        rootOverlay = new FrameLayout(this);
        rootOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootOverlay.setBackgroundColor(Color.TRANSPARENT);
        rootOverlay.setPadding(0, 0, 0, 0);

        // 点击外部空白区域退出
        rootOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                int[] loc = new int[2];
                sheetCard.getLocationOnScreen(loc);
                float y = event.getRawY();
                if (y < loc[1]) {
                    dismissSheet();
                    return true;
                }
            }
            return false;
        });

        // 2. 底部卡片主体（Gravity.BOTTOM 彻底锁定底部，左右 100% 铺满）
        sheetCard = new LinearLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, defaultHeight);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.setMargins(0, 0, 0, 0);
        sheetCard.setLayoutParams(cardLp);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        sheetCard.setElevation(dpToPx(16));
        sheetCard.setClipChildren(true);

        // 24dp 顶部圆角毛玻璃半透背景 + 细微描边（一直覆盖到底部，键盘下方完全拥有同色垫板）
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        float r = dpToPx(24);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        cardBg.setColor(cardBgColor);
        cardBg.setStroke(dpToPx(1), borderColor);
        sheetCard.setBackground(cardBg);

        // 3. 紧凑拖拽横条区域（Drag Handle）：压缩留白
        FrameLayout dragArea = new FrameLayout(this);
        dragArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(14)));
        dragArea.setPadding(0, dpToPx(5), 0, dpToPx(2));

        View handle = new View(this);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);

        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setCornerRadius(dpToPx(2));
        handleBg.setColor(handleColor);
        handle.setBackground(handleBg);
        dragArea.addView(handle);
        sheetCard.addView(dragArea);

        // 4. 顶部操作栏（RelativeLayout 保证标题绝对对称居中）
        RelativeLayout headerBar = new RelativeLayout(this);
        headerBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(42)));
        headerBar.setPadding(dpToPx(10), 0, dpToPx(10), dpToPx(2));

        // 左侧按钮组：[① ✕ 关闭] + [② >_ 容器设置]
        LinearLayout leftGroup = new LinearLayout(this);
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftGroup.setLayoutParams(leftLp);
        leftGroup.setOrientation(LinearLayout.HORIZONTAL);
        leftGroup.setGravity(Gravity.CENTER_VERTICAL);

        // [① ✕ 关闭按钮]
        View btnClose = createHeaderIconButton(ICON_CLOSE, textColor, "关闭弹层");
        btnClose.setOnClickListener(v -> dismissSheet());
        leftGroup.addView(btnClose);

        // [② >_ 容器设置按钮]
        View btnSettings = createHeaderIconButton(ICON_SETTINGS, textColor, "进入容器主页面");
        LinearLayout.LayoutParams settingsLp = (LinearLayout.LayoutParams) btnSettings.getLayoutParams();
        settingsLp.setMarginStart(dpToPx(4));
        btnSettings.setLayoutParams(settingsLp);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("go_home", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            dismissSheet();
        });
        leftGroup.addView(btnSettings);
        headerBar.addView(leftGroup);

        // 中间标题（物理绝对对称居中）
        TextView title = new TextView(this);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        title.setLayoutParams(titleLp);
        title.setText("DSHA 对话");
        title.setTextColor(textColor);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerBar.addView(title);

        // 右侧按钮组：[③ 💬➕ 新建对话] + [④ ⬒ 全屏进入App]
        LinearLayout rightGroup = new LinearLayout(this);
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        rightGroup.setLayoutParams(rightLp);
        rightGroup.setOrientation(LinearLayout.HORIZONTAL);
        rightGroup.setGravity(Gravity.CENTER_VERTICAL);

        // [③ 💬➕ 新建对话按钮]
        View btnNewChat = createHeaderIconButton(ICON_NEW_CHAT, textColor, "开启新对话");
        btnNewChat.setOnClickListener(v -> {
            if (sCachedWebView != null) {
                String js = "(function() {" +
                        "  var btn = document.querySelector('[class*=\"newSession\"], [aria-label*=\"新会话\"], [aria-label*=\"新建\"], button[title*=\"新会话\"], button[title*=\"New session\"], button[title*=\"New Chat\"]');" +
                        "  if (btn) {" +
                        "    btn.click();" +
                        "  } else {" +
                        "    window.location.hash = '';" +
                        "    window.location.reload();" +
                        "  }" +
                        "})();";
                sCachedWebView.evaluateJavascript(js, null);
            }
        });
        rightGroup.addView(btnNewChat);

        // [④ ⬒ 全屏聊天按钮]
        View btnFullscreen = createHeaderIconButton(ICON_FULLSCREEN, textColor, "全屏打开聊天页面");
        LinearLayout.LayoutParams fullscreenLp = (LinearLayout.LayoutParams) btnFullscreen.getLayoutParams();
        fullscreenLp.setMarginStart(dpToPx(4));
        btnFullscreen.setLayoutParams(fullscreenLp);
        btnFullscreen.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("open_web", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            dismissSheet();
        });
        rightGroup.addView(btnFullscreen);
        headerBar.addView(rightGroup);

        sheetCard.addView(headerBar);

        // 5. 分割线
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        divider.setBackgroundColor(lineColor);
        sheetCard.addView(divider);

        // 6. WebView 主体容器（自适应伸缩，防漏字）
        webContainer = new FrameLayout(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webContainer.setLayoutParams(webLp);
        webContainer.setClipChildren(true);
        webContainer.setClipToPadding(true);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(sWebLoaded ? View.GONE : View.VISIBLE);
        webContainer.addView(progressBar);

        errorHint = new TextView(this);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;
        errorHint.setLayoutParams(errLp);
        errorHint.setText("正在连接 DSHA 服务…");
        errorHint.setTextColor(isDarkMode ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
        errorHint.setTextSize(14);
        errorHint.setVisibility(View.GONE);
        webContainer.addView(errorHint);

        sheetCard.addView(webContainer);

        // 7. 键盘底部占位底板（垫在键盘下方，具有与卡片一致的同色毛玻璃底色，绝不漏桌面壁纸）
        keyboardSpacer = new View(this);
        keyboardSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        keyboardSpacer.setBackgroundColor(Color.TRANSPARENT);
        sheetCard.addView(keyboardSpacer);

        rootOverlay.addView(sheetCard);

        return rootOverlay;
    }

    private View createHeaderIconButton(int iconType, int iconColor, String contentDescription) {
        HeaderIconButton btn = new HeaderIconButton(this, iconType, iconColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
        btn.setLayoutParams(lp);
        btn.setContentDescription(contentDescription);
        btn.setClickable(true);
        btn.setFocusable(true);

        // 圆形水波纹反馈
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#253D6FD4")), null, mask);
        btn.setBackground(ripple);

        return btn;
    }

    /** 4 按钮高精度矢量绘制 View（统一 36x36dp 容器、1.85dp 规范线宽、圆倒角与对称视觉） */
    private static class HeaderIconButton extends View {
        private final int iconType;
        private final Paint paint;
        private final RectF rectF = new RectF();

        public HeaderIconButton(Context context, int iconType, int color) {
            super(context);
            this.iconType = iconType;
            float density = context.getResources().getDisplayMetrics().density;
            float strokeWidthPx = 1.85f * density;

            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(strokeWidthPx);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float dp = getResources().getDisplayMetrics().density;

            switch (iconType) {
                case ICON_CLOSE: { // ① [ ✕ ] 关闭 (光学收敛，端点半长 6.6dp，避免视觉膨胀)
                    float s = 6.6f * dp;
                    canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
                    canvas.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
                    break;
                }
                case ICON_SETTINGS: { // ② [ >_ 容器控制台 ] (圆角窗口外框 + 内部命令行提示符 > _)
                    float halfW = 8.5f * dp;
                    float halfH = 7.0f * dp;
                    float r = 2.8f * dp;
                    rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                    canvas.drawRoundRect(rectF, r, r, paint);

                    // 命令行提示符 >
                    Path path = new Path();
                    path.moveTo(cx - 4.5f * dp, cy - 2.8f * dp);
                    path.lineTo(cx - 1.2f * dp, cy);
                    path.lineTo(cx - 4.5f * dp, cy + 2.8f * dp);
                    canvas.drawPath(path, paint);

                    // 光标下划线 _
                    canvas.drawLine(cx + 0.8f * dp, cy + 2.8f * dp, cx + 4.8f * dp, cy + 2.8f * dp, paint);
                    break;
                }
                case ICON_NEW_CHAT: { // ③ [ 💬➕ ] 新建会话 (圆角对话气泡 + 内部十字加号)
                    float halfW = 8.2f * dp;
                    float topH = 7.2f * dp;
                    float botH = 3.5f * dp;
                    float r = 2.5f * dp;
                    rectF.set(cx - halfW, cy - topH, cx + halfW, cy + botH);
                    canvas.drawRoundRect(rectF, r, r, paint);
                    // 气泡小尾巴
                    Path path = new Path();
                    path.moveTo(cx - 2.8f * dp, cy + botH);
                    path.lineTo(cx - 6.2f * dp, cy + botH + 4.2f * dp);
                    path.lineTo(cx - 6.2f * dp, cy + botH);
                    canvas.drawPath(path, paint);
                    // 内部加号
                    float plusR = 2.8f * dp;
                    float plusCy = cy - 1.8f * dp;
                    canvas.drawLine(cx, plusCy - plusR, cx, plusCy + plusR, paint);
                    canvas.drawLine(cx - plusR, plusCy, cx + plusR, plusCy, paint);
                    break;
                }
                case ICON_FULLSCREEN: { // ④ [ ⬒ ] 全屏展开 (顺时针旋转90°后的顶栏+主视口分栏)
                    float halfW = 8.0f * dp;
                    float halfH = 8.0f * dp;
                    float r = 2.8f * dp;
                    rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                    canvas.drawRoundRect(rectF, r, r, paint);
                    // 顶栏水平分割线
                    float dividerY = cy - 2.8f * dp;
                    canvas.drawLine(cx - halfW, dividerY, cx + halfW, dividerY, paint);
                    break;
                }
            }
        }
    }

    /** 键盘精准监听：单次状态跃迁拉升高度，底部占位块承托输入法，使输入框自然上浮 */
    private void setupKeyboardObserver() {
        if (getWindow() == null || getWindow().getDecorView() == null) return;
        View decorView = getWindow().getDecorView();

        keyboardLayoutListener = () -> {
            int keyboardHeight = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && decorView.getRootWindowInsets() != null) {
                keyboardHeight = decorView.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
            }
            if (keyboardHeight <= 0) {
                Rect r = new Rect();
                decorView.getWindowVisibleDisplayFrame(r);
                int totalHeight = decorView.getRootView().getHeight();
                if (totalHeight <= 0) totalHeight = screenHeight;
                int diff = totalHeight - r.bottom;
                if (diff > dpToPx(100)) {
                    keyboardHeight = diff;
                }
            }

            boolean isKeyboardVisible = keyboardHeight > dpToPx(100);

            if (keyboardSpacer != null) {
                ViewGroup.LayoutParams lp = keyboardSpacer.getLayoutParams();
                if (lp != null && lp.height != keyboardHeight) {
                    lp.height = keyboardHeight;
                    keyboardSpacer.setLayoutParams(lp);
                }
            }

            // 状态跃迁锁：仅在键盘从“隐藏”变为“弹出”的单次边缘跳变时触发拉升，防止每帧循环打断动画
            if (isKeyboardVisible) {
                if (!isKeyboardElevated) {
                    isKeyboardElevated = true;
                    if (currentHeight <= (int) (screenHeight * 0.52f)) {
                        animateHeightTo(defaultHeight);
                    }
                }
            } else {
                isKeyboardElevated = false;
            }
        };
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
    }

    private void setupGesture() {
        View.OnTouchListener gestureListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchY = event.getRawY();
                    initialHeightOnTouch = currentHeight;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - initialTouchY; // 下滑 > 0，上滑 < 0
                    int targetH = (int) (initialHeightOnTouch - dy);
                    if (targetH > maxHeight) targetH = maxHeight;
                    if (targetH > 0) {
                        currentHeight = targetH;
                        updateCardHeight(targetH);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 1. 低于安全下限阈值（< 25%），顺滑向下退出
                    if (currentHeight < minHeight) {
                        dismissSheet();
                    } else {
                        // 2. 15% 步长阶梯智能吸附停留（35%, 50%, 65%, 80%, 95%）
                        snapToNearest15PercentStep();
                    }
                    return true;
            }
            return false;
        };

        if (sheetCard.getChildCount() > 0) {
            sheetCard.getChildAt(0).setOnTouchListener(gestureListener); // dragArea
        }
        if (sheetCard.getChildCount() > 1) {
            sheetCard.getChildAt(1).setOnTouchListener(gestureListener); // headerBar
        }
    }

    private void updateCardHeight(int height) {
        if (sheetCard != null) {
            ViewGroup.LayoutParams lp = sheetCard.getLayoutParams();
            if (lp != null && lp.height != height) {
                lp.height = height;
                sheetCard.setLayoutParams(lp);
            }
        }
    }

    /** 15% 阶梯智能多档吸附算法（35%, 50%, 65%, 80%, 95%） */
    private void snapToNearest15PercentStep() {
        float[] steps = {0.35f, 0.50f, 0.65f, 0.80f, 0.95f};
        float currentRatio = (float) currentHeight / (float) screenHeight;

        float closestRatio = steps[0];
        float minDiff = Math.abs(currentRatio - steps[0]);

        for (int i = 1; i < steps.length; i++) {
            float diff = Math.abs(currentRatio - steps[i]);
            if (diff < minDiff) {
                minDiff = diff;
                closestRatio = steps[i];
            }
        }

        int targetH = (int) (screenHeight * closestRatio);
        animateHeightTo(targetH);
    }

    private void animateHeightTo(int targetH) {
        int startH = currentHeight;
        if (startH == targetH) return;

        if (heightAnimator != null && heightAnimator.isRunning()) {
            heightAnimator.cancel();
        }

        heightAnimator = ValueAnimator.ofInt(startH, targetH);
        heightAnimator.setDuration(180);
        heightAnimator.setInterpolator(new DecelerateInterpolator());
        heightAnimator.addUpdateListener(animation -> {
            currentHeight = (int) animation.getAnimatedValue();
            updateCardHeight(currentHeight);
        });
        heightAnimator.start();
    }

    /** 挂载常驻单例 WebView，实现 100% 零转圈秒开、1:1 原生字体与透明毛玻璃透光 */
    private void attachChatWeb() {
        if (sCachedWebView == null) {
            sCachedWebView = new WebView(getApplicationContext());
            WebSettings ws = sCachedWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setSupportMultipleWindows(false);
            ws.setUseWideViewPort(true);
            // 移除 setLoadWithOverviewMode(true)，设置 100% 原始字体比例
            ws.setLoadWithOverviewMode(false);
            ws.setTextZoom(100);
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);

            // 禁用系统自动算法反色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ws.setForceDark(WebSettings.FORCE_DARK_OFF);
                } catch (Throwable ignored) {}
            }
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    ws.setAlgorithmicDarkeningAllowed(false);
                } catch (Throwable ignored) {}
            }

            sCachedWebView.setBackgroundColor(Color.TRANSPARENT);

            boolean desktop = getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getBoolean("desktop_mode", false);
            if (desktop) {
                ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            }

            sCachedWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    sWebLoaded = true;
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    // 彻底覆写 DSH 前端 CSS 变量与 DOM 背景，消除纯黑实心色，透出半透明毛玻璃卡片
                    injectTransparentBackground(view);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame() && errorHint != null) {
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        errorHint.setVisibility(View.VISIBLE);
                        errorHint.setText("DSHA 服务未就绪，请先在控制台启动");
                    }
                }
            });

            sCachedWebView.setWebChromeClient(new WebChromeClient());

            String base = "http://127.0.0.1:" + (controller != null ? controller.getPort() : "3080") + "/";
            String token = HttpShellService.currentToken();
            String url = token.isEmpty() ? base : base + "?dsha_t=" + Uri.encode(token);
            sCachedWebView.loadUrl(url);
        } else {
            if (sCachedWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sCachedWebView.getParent()).removeView(sCachedWebView);
            }
            if (progressBar != null) {
                progressBar.setVisibility(sWebLoaded ? View.GONE : View.VISIBLE);
            }
            injectTransparentBackground(sCachedWebView);
        }

        webContainer.addView(sCachedWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 彻底覆写前端背景 CSS 变量，确保背景 100% 透明透光 */
    private void injectTransparentBackground(WebView view) {
        if (view == null) return;
        try {
            String js = "(function() {" +
                    "  var css = `\n" +
                    "    html, body, #root, [data-ds-dark-theme], main, .dsh-layout-root, div[class*='_root_'], div[class*='_wrap_'], div[class*='_container_'] {\n" +
                    "      background: transparent !important;\n" +
                    "      background-color: transparent !important;\n" +
                    "    }\n" +
                    "    :root, .dark, [data-ds-dark-theme] {\n" +
                    "      --dsw-alias-bg-base: transparent !important;\n" +
                    "      --dsw-alias-bg-layer-1: transparent !important;\n" +
                    "      --dsw-alias-bg-layer-2: rgba(255, 255, 255, 0.05) !important;\n" +
                    "      --dsw-specific-sidebar-fill: transparent !important;\n" +
                    "    }\n" +
                    "  `;\n" +
                    "  var style = document.getElementById('dsh-transparent-style');\n" +
                    "  if (!style) {\n" +
                    "    style = document.createElement('style');\n" +
                    "    style.id = 'dsh-transparent-style';\n" +
                    "    document.head.appendChild(style);\n" +
                    "  }\n" +
                    "  style.innerHTML = css;\n" +
                    "  if (document.documentElement) document.documentElement.style.backgroundColor = 'transparent';\n" +
                    "  if (document.body) document.body.style.backgroundColor = 'transparent';\n" +
                    "})();";
            view.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    /** 从底部顺滑滑入展开（屏幕外静默就绪，绝不闪屏变形） */
    private void animateIn() {
        isDismissing = false;
        if (sheetCard != null) {
            // 如果上次处于低位 (<=50%)，在屏幕外先设为不可见并修改高度
            if (currentHeight <= (int) (screenHeight * 0.52f)) {
                currentHeight = defaultHeight;
                updateCardHeight(defaultHeight);
            }
            sheetCard.setVisibility(View.INVISIBLE);
            sheetCard.setTranslationY(screenHeight > 0 ? screenHeight : 2500);

            sheetCard.post(() -> {
                sheetCard.setTranslationY(sheetCard.getHeight() > 0 ? sheetCard.getHeight() : defaultHeight);
                sheetCard.setVisibility(View.VISIBLE);
                sheetCard.animate()
                        .translationY(0)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null)
                        .start();
            });
        }
    }

    /** 顺滑向下平移退出弹层并转入后台保活（moveTaskToBack，退出时绝不碰高度） */
    private void dismissSheet() {
        if (isDismissing) return;
        isDismissing = true;

        // 退出前顺带隐藏键盘
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}

        if (sheetCard != null) {
            sheetCard.animate()
                    .translationY(sheetCard.getHeight() + dpToPx(30))
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            moveTaskToBack(true);
                            overridePendingTransition(0, 0);
                            isDismissing = false;
                        }
                    })
                    .start();
        } else {
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
            isDismissing = false;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (sCachedWebView != null && sCachedWebView.canGoBack()) {
            sCachedWebView.goBack();
        } else {
            dismissSheet();
        }
    }

    @Override
    protected void onDestroy() {
        if (keyboardLayoutListener != null && getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        }
        super.onDestroy();
        if (sCachedWebView != null && sCachedWebView.getParent() == webContainer) {
            webContainer.removeView(sCachedWebView);
        }
    }
}
