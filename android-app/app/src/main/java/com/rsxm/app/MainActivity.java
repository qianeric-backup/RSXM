package com.rsxm.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.WindowManager;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.zip.GZIPInputStream;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import rikka.shizuku.Shizuku;

/**
 * Reasonix Proot —— 在 Android 上通过 proot 运行 Alpine Linux 环境，
 * 启动后自动进入 reasonix AI 编码助手（TUI）。
 *
 * 架构：
 *   WebView (xterm.js 终端)  <->  Java 管道  <->  pty-bridge（静态 musl，提供 PTY）
 *                                                          └─ proot -> Alpine -> entry.sh -> reasonix
 */
public class MainActivity extends Activity {

    private static final String TAG = "ReasonixProot";
    private static final int REQ_UPDATE_RESONIX = 200;
    private static final int REQ_SKILL_IMPORT = 201;
    private static final int REQ_CREATE_ENV_TEMPLATE = 202;
    private static final int REQ_IMPORT_ENV_TEMPLATE = 203;
    /** 官方更新源：@reasonix/cli-linux-arm64（npm 平台二进制包，npmmirror 国内镜像） */
    private static final String REASONIX_DEFAULT_URL =
            "https://registry.npmmirror.com/@reasonix/cli-linux-arm64/-/cli-linux-arm64-1.31.4.tgz";
    /**
     * 上下滑动调速档位：1~10（prefs 键 scroll_speed，默认 5）。
     * 档位越小滑动越慢：SCROLL_STEP = 档位换算的每页滑动像素数
     *  档位 1 → 500px/页（最慢，精细浏览）
     *  档位 5 → 100px/页（默认，翻看历史）
     *  档位 10 → 10px/页（最快，接近原版 8px/页）
     */
    public static final int SPEED_MIN = 1;
    public static final int SPEED_MAX = 10;
    public static final int SPEED_DEFAULT = 5;

    private WebView webView;
    // proot 进程与输入流静态持有：后台运行模式下与 Activity 生命周期解耦，
    // Activity 重建（系统回收/返回后重开）时无需重启环境，终端 I/O 可无缝续接。
    private static volatile Process sProotProcess;
    private static volatile OutputStream sProcIn;
    /** 当前活动实例：后台 reader 线程输出经它转发到活动终端（重建后指向新实例） */
    private static volatile MainActivity sCurrent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean environmentStarted = false;
    /** WebView 页面加载完成标记（onPageFinished 置位，超时未完成则重载页面） */
    private volatile boolean pageLoaded = false;
    /** 悬浮快捷键工具栏显示状态（JS 侧 keys-toolbar，侧滑菜单「快捷键」切换用） */
    private volatile boolean keysToolbarVisible = false;
    /** 复用环境标记：后台模式开启时 Activity 重建复用运行中的 proot 环境 */
    private volatile boolean reuseEnv = false;
    /** 开发环境安装中标记（static，v2.0.25：安装任务是进程级的 nohup 脚本+15 分钟轮询线程，
     *  跨 Activity 存活；实例字段在后台模式 Activity 重建后归零 → 可重复发起安装，
     *  第二次 rm 标记文件会让旧任务完成码归属错乱、新旧 apk 并发锁冲突） */
    private static volatile boolean sDevEnvInstalling = false;
    /** 正在安装的环境名称（null 表示无任务；面板重开时据此恢复禁用/提示状态） */
    private static volatile String sDevEnvInstallingName = null;
    /** 面板代际（v2.0.25 B4 修复）：安装线程经 runOnUiThread 更新发起时捕获的旧面板
     *  View——面板重开后新面板进度永远不刷新。每次 showDevEnvDialog ++；安装线程
     *  回调发现代际不符则只写终端输出，不再触碰旧控件。 */
    private int panelGen = 0;
    /** apk 日志进度模式：(x/N) Installing ... */
    private static final Pattern APK_PROGRESS = Pattern.compile("\\((\\d+)/(\\d+)\\)");
    private DrawerLayout drawerLayout;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启动加速：无 Activity 过渡动画
        overridePendingTransition(0, 0);
        sCurrent = this;
        // 重新打开（Activity 重建）时清理上次残留的 proot/pty-bridge/reasonix 进程，
        // 避免双环境并存的 PTY 竞争导致 reasonix CLI 排版错乱。
        // 例外：后台运行模式下旧环境仍在运行 → 直接复用（进程/流静态持有，与 Activity
        // 解耦，终端 I/O 无缝续接），不清理不重启，避免 AI 会话在后台中断。
        boolean bgMode = bgModeOn();
        if (bgMode && sProotProcess != null && sProotProcess.isAlive()) {
            environmentStarted = true;
            reuseEnv = true;      // 复用环境：新 WebView 空白，需强制 reasonix 重绘 TUI
            startRootPolling();   // 复用环境：恢复 root 命令桥轮询（onDestroy 已停）
            startServeWatchdog(); // 复用环境：看门狗继续守着 serve（切到其他 app 期间掉了也能自愈）
            Log.d(TAG, "background mode: reusing running proot environment");
        } else {
            killProotTree();
        }

        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        webView = findViewById(R.id.webview);
        // 二次开启黑屏修复：硬件渲染在部分设备出现 userfaultfd 卡死（logcat:
        // "userfaultfd: MOVE ioctl seems unsupported: Connection timed out"）导致
        // onPageFinished 不触发、xterm 不渲染 → 终端黑屏；改软件渲染绕过。
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // JS -> Java 桥（键盘输入）
        webView.addJavascriptInterface(this, "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                pageLoaded = true;
                // 页面(重)加载完成后注入当前滑动调速档位（WebView 重载后 JS 变量重置）
                applyScrollSpeed(getSharedPreferences("prefs", MODE_PRIVATE)
                        .getInt("scroll_speed", SPEED_DEFAULT));
                // 聚焦 WebView 触发 xterm 渲染，避免启动后需点击才显示 CLI 界面
                try { view.requestFocus(); } catch (Exception ignored) {}
                if (reuseEnv) {
                    // 复用环境（后台模式开启 + Activity 重建）：新 WebView 终端空白，
                    // reasonix 不感知新终端，强制重绘完整 TUI（微调列数触发 SIGWINCH 再恢复）
                    reuseEnv = false;
                    view.postDelayed(() -> {
                        try {
                            view.evaluateJavascript(
                                    "if(window.Android&&Android.resize){Android.resize(term.rows,Math.max(1,term.cols-1));"
                                            + "setTimeout(function(){Android.resize(term.rows,term.cols);},200);}", null);
                        } catch (Exception ignored) {}
                    }, 600);
                }
                if (!environmentStarted) {
                    environmentStarted = true;
                    new Thread(MainActivity.this::startEnvironment).start();
                }
            }
        });
        webView.loadUrl("file:///android_asset/web/index.html");

        // onPageFinished 超时重载：userfaultfd 渲染卡死时页面可能一直不完成，4s 后重载恢复
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!pageLoaded && webView != null) {
                Log.w(TAG, "onPageFinished 超时，重载 index.html");
                try { webView.reload(); } catch (Exception ignored) {}
            }
        }, 4000);

        // 兜底启动：环境启动不依赖 WebView 渲染。二次开启时 WebView 可能因渲染线程
        // 卡住（logcat: userfaultfd: MOVE ioctl seems unsupported: Connection timed out）
        // 导致 onPageFinished 不触发 → 环境永不启动 → 终端黑屏。
        // onPageFinished 正常触发会先启动环境（environmentStarted 置位），此处 4s 后跳过。
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!environmentStarted) {
                environmentStarted = true;
                Log.w(TAG, "onPageFinished 未触发（WebView 渲染异常），兜底启动环境");
                new Thread(MainActivity.this::startEnvironment).start();
            }
        }, 4000);

        // 标题栏已移除：侧滑菜单入口改由快捷栏「菜单」提供（btn_menu 已从布局删除，防旧布局兼容保留空判断）
        findViewById(R.id.qb_menu).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START, false));
        // 侧滑菜单：一级高频入口；视图切换/高级设置 亦在一级
        findViewById(R.id.menu_adb).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showAdbDialog(); });
        findViewById(R.id.menu_apikey).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showApiKeyConfigDialog(); });
        findViewById(R.id.menu_github).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showGitHubDialog(); });
        findViewById(R.id.menu_ds2api).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showDs2ApiDialog(); });
        findViewById(R.id.menu_update).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showUpdateResonixDialog(); });
        findViewById(R.id.menu_purge).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showPurgeDialog(); });
        findViewById(R.id.menu_aitest8).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showAITest8Dialog(); });
        findViewById(R.id.menu_project).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showProjectDialog(); });
        findViewById(R.id.menu_sessions).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showSessionsDialog(); });
        findViewById(R.id.menu_advanced).setOnClickListener(v -> { drawerLayout.closeDrawer(GravityCompat.START, false); showAdvancedDialog(); });
        findViewById(R.id.menu_view).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START, false);
            if (nativeViewOn) exitNativeView(); else enterNativeView();
            updateMenuViewLabel();
        });
        // 升级安装后 rootfs 可能没有 YOLO 标记：以偏好为准补写（默认开启）
        syncYoloMark(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true));

        // 顶边栏：仅「菜单」入口（其余功能在侧滑栏/高级设置）
        findViewById(R.id.qb_menu).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START, false));
        updateMenuViewLabel();

        // 原生会话视图：发送按钮 + 输入框回车发送
        findViewById(R.id.native_send).setOnClickListener(v -> sendNativeInput());
        EditText nativeInput = findViewById(R.id.native_input);
        if (nativeInput != null) {
            nativeInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    sendNativeInput();
                    return true;
                }
                return false;
            });
        }
        // 原生视图头部操作：新会话（走 serve POST /new，重新取 transcript 基线）
        findViewById(R.id.native_new).setOnClickListener(v -> {
            try {
                LinearLayout list = findViewById(R.id.native_output);
                if (list != null) list.removeAllViews();
                resetTranscriptView();
                setBubbleCount(0);
                setGenInFlight(false);
                setNativeStatus("");
                pushOutput("\r\n[已请求 serve 开始新会话]\r\n");
                new Thread(() -> {
                    serveClient().newSession();
                    ui.post(this::loadTranscriptSnapshot);
                }, "serve-new").start();
            } catch (Exception e) {
                Log.e(TAG, "native new session failed", e);
            }
        });
        // 回终端（原生视图 → xterm 终端）
        findViewById(R.id.native_gototerm).setOnClickListener(v -> exitNativeView());

        // 全屏功能面板：返回按钮关闭（系统返回键同样生效）
        findViewById(R.id.panel_back).setOnClickListener(v -> hidePanel());

        // 软键盘监听：原生视图输入时自动滚底
        setupKeyboardListener();

        // 测试/调试入口：am start -e force_reinstall true 模拟无 root 设备的自动修复流程
        if (getIntent().getBooleanExtra("force_reinstall", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptReinstallForNativeLib, 3000);
        }

        requestStoragePermission();

        // 后台运行模式恢复：**默认开启**（保活服务让进程不会退化成 cached 进程，
        // 切到其他 app / Activity 被回收重开后环境与 serve 都继续运行）；
        // 只有用户显式关闭过才不拉起。
        if (bgModeOn()) {
            // Android 13+ 的通知权限只自动请求一次：没权限时前台服务照常运行，
            // 只是常驻通知不可见（用户容易误以为没在保活）→ 首次自动开启时请求一次。
            boolean askNotif = Build.VERSION.SDK_INT >= 33
                    && !getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("notif_asked", false);
            if (askNotif) {
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                        .putBoolean("notif_asked", true).apply();
            }
            startBackgroundService(askNotif);
        }

        // 视图恢复：无历史偏好 → 默认进入原生 GUI 会话视图（V2.0 起 GUI 为默认界面）；
        // 有偏好 → 按上次选择恢复。延迟到环境就绪后进入，避免与启动流程抢焦点。
        if (!getSharedPreferences("prefs", MODE_PRIVATE).contains("view_mode")
                || "native".equals(getSharedPreferences("prefs", MODE_PRIVATE).getString("view_mode", "native"))) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!nativeViewOn && environmentStarted) {
                    enterNativeView();
                }
            }, 1200);
        }
    }

    /** 供工具类（McpManager 等）root 桥兜底访问当前活动实例 */
    static MainActivity current() {
        return sCurrent;
    }

    /* ==================== Root 权限（KernelSU/Magisk） ==================== */

    /** root 命令桥：guest 写 /root/.root-cmd → 此轮询执行 su → 结果写 /root/.root-out */
    private final Handler rootPoller = new Handler(Looper.getMainLooper());
    private final Runnable rootPollTask = new Runnable() {
        @Override
        public void run() {
            processRootCommandQueue();
            rootPoller.postDelayed(this, 1500);
        }
    };

    private void startRootPolling() {
        Log.d(TAG, "root polling started");
        rootPoller.removeCallbacks(rootPollTask);
        probeRootAndMark();   // 预检 root 并写 .root-ok（guest adb wrapper 据此直连 root 桥）
        rootPoller.postDelayed(rootPollTask, 1500);
    }

    private void stopRootPolling() {
        rootPoller.removeCallbacks(rootPollTask);
    }

    /** 处理 guest 的 root 命令队列（一次一个，串行；结果写回 .root-out 带 __DONE__ 标记） */
    private final java.util.concurrent.atomic.AtomicBoolean rootBridgeBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void processRootCommandQueue() {
        // 串行保证：上一条命令还在执行时新命令留在 .root-cmd 等下一轮，
        // 避免两条命令并发执行把 .root-out（含 __DONE__ 标记）写花
        if (!rootBridgeBusy.compareAndSet(false, true)) return;
        boolean dispatched = false;
        try {
            File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
            File cmdFile = new File(rootDir, ".root-cmd");
            File outFile = new File(rootDir, ".root-out");
            if (!cmdFile.exists()) return;   // finally 复位
            String cmd = new String(java.nio.file.Files.readAllBytes(cmdFile.toPath()),
                    StandardCharsets.UTF_8).trim();
            cmdFile.delete();
            if (cmd.isEmpty()) return;       // finally 复位
            Log.d(TAG, "root bridge cmd: " + cmd);
            new Thread(() -> {
                String r = execRootCommand(cmd, 25);
                try {
                    java.nio.file.Files.write(outFile.toPath(),
                            ((r == null ? "(root 执行失败，请检查授权)" : r) + "\n__DONE__")
                                    .getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Log.w(TAG, "root out write failed", e);
                } finally {
                    rootBridgeBusy.set(false);   // 命令线程结束才允许下一条
                }
            }, "root-bridge").start();
            dispatched = true;   // 已派发：busy 由命令线程释放
        } catch (Exception e) {
            Log.w(TAG, "root queue failed", e);
        } finally {
            if (!dispatched) rootBridgeBusy.set(false);   // 未派发路径（无命令/异常）必须复位
        }
    }

    /** Root 权限对话框：检测状态 + 授权引导 + 测试 */
    private void showRootDialog() {
        String su = findSuPath();
        TextView status = new TextView(this);
        status.setTextSize(14);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView tip = createDarkTip(
                "reasonix 内可执行 root <命令> 获取手机 root 权限（如 root id、root 'pm list packages'）。\n"
                        + "首次执行会弹出 root 授权请求（KernelSU/Magisk），请允许。\n"
                        + "⚠ root 可完全控制系统，请勿执行未知命令。");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        addV(panel, status, 0);
        addV(panel, tip, 8);
        Button testBtn = createDarkButton("测试 root（执行 id）");
        addV(panel, testBtn, 10);
        TextView result = createDarkResult();
        result.setText("（测试结果将显示在这里）");
        addV(panel, result, 8);

        // 运行模式切换：proot（默认）/ chroot（root 直入，SELinux 保持 enforcing 下 JVM 亦可用）
        final boolean chrootNow = isChrootMode();
        panel.addView(createDarkSectionTitle("运行模式"));
        TextView modeTip = createDarkTip(
                "运行模式：" + (chrootNow ? "chroot（root 直入）" : "proot（默认）") + "\n"
                        + "chroot 使用 root 直接 chroot 进环境（需 root 授权），SELinux 保持 enforcing 时\n"
                        + "JVM/安卓开发环境亦正常（proot 模式 enforcing 下不可用）；切换会重启 reasonix 环境。");
        modeTip.setTextColor(chrootNow ? 0xFF7FDB8A : 0xFFAAAAAA);
        modeTip.setPadding(0, dp(2), 0, dp(4));
        addV(panel, modeTip, 6);
        Button modeBtn = createDarkButton(chrootNow ? "切换回 proot 模式" : "切换为 chroot 模式（实验）");
        modeBtn.setOnClickListener(v -> {
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putString("run_mode", chrootNow ? "proot" : "chroot").apply();
            result.setText(chrootNow ? "已切换为 proot 模式，正在重启环境..." : "已切换为 chroot 模式，正在重启环境...");
            hidePanel();
            restartEnvironment();
        });
        // chroot 需要 root 授权：先置灰，root-check 检测通过后恢复；无 root 保持禁用并提示
        modeBtn.setEnabled(false);
        modeBtn.setTextColor(0xFF6A6A6A);
        modeBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1E1E1E));
        addV(panel, modeBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("ROOT", panel, null);
        // 检测状态（后台线程）
        new Thread(() -> {
            final String st;
            if (su == null) {
                st = "未检测到 root（未安装 KernelSU/Magisk）";
            } else {
                String r = execRootCommand("id", 5);
                st = (r != null && r.contains("uid=0"))
                        ? "已授权（" + su + "）：" + r.split("\n")[0]
                        : "检测到 " + su + "，但执行失败（请在弹窗授权后重试）";
            }
            runOnUiThread(() -> {
                status.setText(st);
                status.setTextColor(st.contains("已授权") ? 0xFF7FDB8A : (st.contains("未检测") ? 0xFF888888 : 0xFFFFD54F));
                // chroot 按钮：root 可用才恢复（chroot 需 su 授权）
                if (st.contains("已授权")) {
                    modeBtn.setEnabled(true);
                    modeBtn.setTextColor(0xFFFFFFFF);
                    modeBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF262626));
                } else {
                    modeTip.setText(modeTip.getText() + "\n⚠ chroot 需要 root 授权，当前未检测到可用 root，切换按钮不可用。");
                    modeTip.setTextColor(0xFFFF6B6B);
                }
            });
        }, "root-check").start();
        testBtn.setOnClickListener(v -> {
            result.setText("执行中...\nroot id");
            new Thread(() -> {
                String r = execRootCommand("id", 8);
                runOnUiThread(() -> {
                    result.setTextColor(r != null && r.contains("uid=0") ? 0xFF7FDB8A : 0xFFFF6E6E);
                    result.setText(r == null ? "(无输出或超时——请检查授权)" : r.trim());
                });
            }, "root-test").start();
        });
    }

    /** 探测 su 路径（含 KernelSU/Magisk；找不到则回退 PATH 中的 su）。
     *  结果缓存（v2.0.25）：旧实现每次 execRootCommand 都重新探测，PATH 回退分支
     *  还要起一次 `su -c id`（最多等 3s），拖慢每条 root 命令。 */
    private static volatile String sSuPathCache;

    private String findSuPath() {
        String cached = sSuPathCache;
        if (cached != null) {
            // 绝对路径缓存失效重探；"su"（PATH 回退）视为稳定
            if ("su".equals(cached) || new File(cached).exists()) return cached;
            sSuPathCache = null;
        }
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup",
                "/debug_ramdisk/su",              // KernelSU
                "/data/adb/ksu/bin/su",           // KernelSU
                "/data/adb/magisk/busybox/su"     // Magisk
        };
        for (String p : paths) {
            boolean ex = new File(p).exists();
            Log.d(TAG, "root probe: " + p + " exists=" + ex);
            if (ex) {
                sSuPathCache = p;
                return p;
            }
        }
        // 回退：直接使用 "su"（走 PATH，KernelSU/Magisk 通常已加入 PATH）
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                byte[] buf = new byte[256];
                int n = p.getInputStream().read(buf);
                String s = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                p.destroy();
                if (s.contains("uid=0")) {
                    Log.d(TAG, "root probe: PATH su works: " + s.trim());
                    sSuPathCache = "su";
                    return "su";
                }
            } else {
                p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "root probe: PATH su failed", e);
        }
        return null;
    }

    /** 预检 root 并写 /root/.root-ok 标记（guest 侧 adb wrapper 据此直连 root 命令桥，无需无线调试） */
    private void probeRootAndMark() {
        new Thread(() -> {
            String su = findSuPath();
            boolean ok = su != null;
            if (ok) {
                String r = execRootCommand("id", 5);
                ok = r != null && r.contains("uid=0");
            }
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File okFile = new File(rootDir, ".root-ok");
                if (ok) {
                    java.nio.file.Files.write(okFile.toPath(), "ok\n".getBytes(StandardCharsets.UTF_8));
                    Log.d(TAG, "root precheck OK, wrote .root-ok");
                } else {
                    okFile.delete();
                    Log.d(TAG, "root precheck unavailable, removed .root-ok");
                }
            } catch (Exception e) {
                Log.w(TAG, "root mark failed", e);
            }
        }, "root-precheck").start();
    }

    /** 执行 root 命令（su -c），返回 stdout+stderr（失败返回 null）；包内可见供 McpManager root 桥兜底。
     *  约定错误文本（供 McpManager.isExecFailure 识别）：“(超时…” / “(root 执行失败…” */
    String execRootCommand(String cmd, int timeoutSec) {
        String su = findSuPath();
        if (su == null) return null;
        Process p = null;
        try {
            p = new ProcessBuilder(su, "-c", cmd)
                    .redirectErrorStream(true).start();
            // 看门狗：超时杀进程（排水循环会一直读到 EOF，需要外部强超时兜底）
            final boolean[] timedOut = {false};
            final Process fp = p;
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutSec * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (fp.isAlive()) {
                    timedOut[0] = true;   // v2.0.25：超时带标记返回（旧实现返回无标记的部分输出，
                    fp.destroyForcibly(); // McpManager.isExecFailure 会把截断输出当合法内容）
                }
            }, "root-exec-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            // 持续排水直到 EOF（v2.0.25 修复：旧实现 waitFor 后才读且只读一次 8KB——
            // 输出超过管道缓冲（64KB）时命令写阻塞 → 误判「(超时)」；长输出被截断）
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() >= 512 * 1024) {   // 上限保护：异常超长输出截断
                        p.destroy();
                        break;
                    }
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            watchdog.interrupt();
            String out = bos.toString("UTF-8");
            if (timedOut[0]) {
                return "(超时) " + out;   // 带约定错误前缀：部分输出可读但调用方可识别为失败
            }
            if (out.isEmpty() && !p.isAlive() && p.exitValue() != 0) {
                return "(root 执行失败 exit=" + p.exitValue() + ")";
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "root exec failed: " + e);
            if (p != null) p.destroy();
            return null;
        }
    }

    /* ==================== 侧滑菜单功能 ==================== */

    // ------------------------------------------------------------------
    // 全屏功能面板（取代系统 AlertDialog 弹窗，页面式切换避免遮挡控件）
    // ------------------------------------------------------------------
    private Runnable panelOnClose;   // 面板关闭回调（hidePanel 时触发一次）

    /** 显示全屏功能面板：标题 + 内容视图；onClose 在面板关闭时回调（可空） */
    private void showPanel(String title, View content, Runnable onClose) {
        ((TextView) findViewById(R.id.panel_title)).setText(title);
        LinearLayout holder = findViewById(R.id.panel_content);
        holder.removeAllViews();
        holder.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelOnClose = onClose;
        // 面板内键盘弹出不压缩布局/不自动滚动（adjustPan：窗口整体平移保持输入框可见，
        // 面板外层 ScrollView 不会因键盘把内容超高而自动滑动）；退出面板恢复终端 adjustResize。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        findViewById(R.id.panel_overlay).setVisibility(View.VISIBLE);
    }

    /** 关闭全屏功能面板 */
    private void hidePanel() {
        if (findViewById(R.id.panel_overlay).getVisibility() != View.VISIBLE) return;
        findViewById(R.id.panel_overlay).setVisibility(View.GONE);
        ((ViewGroup) findViewById(R.id.panel_content)).removeAllViews();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Runnable cb = panelOnClose;
        panelOnClose = null;
        if (cb != null) cb.run();
    }

    /** 返回键三级逻辑：全屏面板 → 原生会话视图 → 退出应用 */
    @Override
    public void onBackPressed() {
        // 1) 功能面板打开：关面板
        if (findViewById(R.id.panel_overlay).getVisibility() == View.VISIBLE) {
            hidePanel();
            return;
        }
        // 2) 原生会话视图：切回终端（不退出应用）
        if (nativeViewOn) {
            // 若软键盘弹出，第一次返回先收键盘（再按才切回终端），符合手机习惯
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View cur = getCurrentFocus();
            if (imm != null && cur != null && imm.isActive(cur)) {
                imm.hideSoftInputFromWindow(cur.getWindowToken(), 0);
                return;
            }
            exitNativeView();
            return;
        }
        super.onBackPressed();
    }

    /**
     * 软键盘全局监听：键盘弹出/收起时同步交互逻辑——
     *  - 原生会话视图：键盘弹出自动滚到底部（输入时能看见最新消息），收起无操作；
     *  - 终端视图：键盘弹出会压缩终端高度（adjustResize），reasonix 按新行数重绘，无需干预。
     */
    private void setupKeyboardListener() {
        View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(r);
            int heightDiff = root.getRootView().getHeight() - (r.bottom - r.top);
            if (heightDiff > dp(120)) {
                // 键盘弹出
                if (nativeViewOn) {
                    autoScrollBottom(true);   // 键盘弹出=用户正在输入，强制滚底
                }
            }
        });
    }

    // 后台运行/YOLO/滑动速度 的开关与档位现在都放在「高级设置」二级面板内动态构建，
    // 状态标签由 showAdvancedDialog 内部控件维护（储存 recent 引用以便刷新）。

    /** 高级设置面板重建后回写开关/档位状态（无面板时无害） */
    private void updateBgModeLabel() {
        TextView tv = advancedBgLabel;
        if (tv != null) {
            boolean on = bgModeOn();
            tv.setText(on ? "后台运行：开" : "后台运行：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    /** YOLO 免审批模式标签：开启时 reasonix 完全跳过工具审批（--permission-mode danger-full-access） */
    private void updateYoloModeLabel() {
        TextView tv = advancedYoloLabel;
        if (tv != null) {
            boolean on = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("yolo_mode", true);
            tv.setText(on ? "YOLO 免审批：开" : "YOLO 免审批：关");
            tv.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    // ---- 上下滑动调速 ----

    /**
     * 档位(1~10) → 每页滑动像素数：SCROLL_STEP = 1000 / 档位。
     * 档位 1 → 500px/页（最慢，精细浏览）；档位 5 → 100px/页（默认）；
     * 档位 10 → 10px/页（最快，接近原固定 8px/页的翻动速度）。
     */
    private static int scrollStepForSpeed(int speed) {
        if (speed < SPEED_MIN) speed = SPEED_MIN;
        if (speed > SPEED_MAX) speed = SPEED_MAX;
        return 1000 / speed;
    }

    /** 把当前档位换算的 SCROLL_STEP 注入 xterm.js（JS 函数 setScrollStep 已暴露） */
    private void applyScrollSpeed(int speed) {
        int step = scrollStepForSpeed(speed);
        if (webView == null) return;
        ui.post(() -> {
            try {
                webView.evaluateJavascript("window.setScrollStep(" + step + ")", null);
            } catch (Exception ignored) {}
        });
    }

    /** 更新高级设置面板里的档位显示文本 */
    private void updateSpeedLabel(int speed) {
        if (advancedSpeedLabel != null) {
            advancedSpeedLabel.setText("当前：" + speed + " 档（滑动"
                    + (speed >= 7 ? "较快" : speed <= 3 ? "较慢" : "适中") + "）");
        }
    }

    /** 同步 YOLO 开关到 rootfs 标记（/root/.rsxm-yolo），reasonix wrapper 每次启动时读取决定审批模式 */
    private void syncYoloMark(boolean on) {
        try {
            File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
            File mark = new File(rootDir, ".rsxm-yolo");
            if (on) {
                if (!mark.exists()) mark.createNewFile();
            } else {
                mark.delete();
            }
            Log.d(TAG, "yolo mark " + (on ? "created" : "removed"));
        } catch (Exception e) {
            Log.w(TAG, "sync yolo mark failed", e);
        }
    }

    /**
     * 后台运行（保活）模式开关 —— 默认开启（{@link BackgroundService#BG_MODE_DEFAULT}）。
     *
     * <p>统一入口：偏好 `background_mode` 的默认值只在
     * {@link BackgroundService#BG_MODE_DEFAULT} 里定义一次，避免各处默认值不一致出现
     * "服务在跑但判定为关"这类分裂状态。
     */
    private boolean bgModeOn() {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("background_mode", BackgroundService.BG_MODE_DEFAULT);
    }

    /**
     * 启动后台保活前台服务（后台运行模式）。
     * requestNotifPerm=true（用户手动开启时）：顺带请求通知权限（Android 13+），
     * 保证常驻通知可见；onCreate 自动恢复时传 false，避免打扰。
     */
    private void startBackgroundService(boolean requestNotifPerm) {
        try {
            startForegroundService(new Intent(this, BackgroundService.class));
            Log.d(TAG, "background keep-alive service started");
        } catch (Exception e) {
            Log.w(TAG, "start background service failed", e);
        }
        if (requestNotifPerm && Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 201);
            } catch (Exception e) {
                Log.w(TAG, "request notification permission failed", e);
            }
        }
    }

    /** 停止后台保活前台服务（后台运行模式关闭） */
    private void stopBackgroundService() {
        try {
            stopService(new Intent(this, BackgroundService.class));
            Log.d(TAG, "background keep-alive service stopped");
        } catch (Exception e) {
            Log.w(TAG, "stop background service failed", e);
        }
    }

    /* ==================== 侧滑菜单功能 ==================== */

    /** dp 转 px */
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 安装 SKILL：skill 是 reasonix 的 AI 技能包（SKILL.md 规范格式），
     *  写入当前项目（reasonix 工作区）的 .reasonix/skills/<name>/SKILL.md，
     *  reasonix 按工作区加载：<workspace>/.reasonix/skills/（默认项目 /root）,
     *  安装后 reasonix 内 /skills reload 或重启环境即可显示 */
    private void showSkillInstallDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "SKILL 为 reasonix 技能包（SKILL.md），全局安装（所有项目共用，切换项目不影响）。\n"
                        + "格式：开头 YAML frontmatter，含 name 和 description。"));
        // ---- 新增区 ----
        panel.addView(createDarkSectionTitle("新增 SKILL"));
        skillNameInput = createDarkEditText("SKILL 名称（如 mytool，仅字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, skillNameInput, 6);
        skillContentInput = new EditText(this);
        skillContentInput.setHint("SKILL.md 内容（粘贴/导入，含 frontmatter）");
        skillContentInput.setTextColor(0xFFE0E0E0);
        skillContentInput.setHintTextColor(0xFF707070);
        skillContentInput.setTextSize(13);
        skillContentInput.setGravity(android.view.Gravity.TOP);
        skillContentInput.setSingleLine(false);
        skillContentInput.setMaxLines(Integer.MAX_VALUE);
        skillContentInput.setVerticalScrollBarEnabled(true);
        skillContentInput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        skillContentInput.setBackgroundColor(0xFF1A1A1A);
        skillContentInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        // 固定高度 + 内部滚动（二级滑动）：导入/粘贴大内容时内容区自己滚，不撑动整个功能页
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        contentLp.topMargin = dp(6);
        panel.addView(skillContentInput, contentLp);
        Button importBtn = createDarkButton("从手机文件导入 SKILL.md");
        importBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/*");
            try {
                startActivityForResult(i, REQ_SKILL_IMPORT);
            } catch (Exception e) {
                pushOutput("\r\n[无法打开文件选择器: " + e.getMessage() + "]\r\n");
            }
        });
        addV(panel, importBtn, 8);
        Button installBtn = createDarkButton("安装 SKILL");
        installBtn.setOnClickListener(v -> {
            String name = skillNameInput.getText().toString().trim();
            String content = skillContentInput.getText().toString();
            if (name.isEmpty() || content.isEmpty()) {
                pushOutput("\r\n[请填写 SKILL 名称和内容]\r\n");
                return;
            }
            if (!name.matches("[A-Za-z0-9_.-]+")) {
                pushOutput("\r\n[SKILL 名称仅允许字母、数字、_ . -]\r\n");
                return;
            }
            installSkill(name, content);
            // 列表刷新由 installSkill 完成回调触发（不再固定延时，避免与安装命令并发抢占 .adb-cmd/.adb-out）
        });
        addV(panel, installBtn, 8);
        // ---- 查找区 ----
        panel.addView(createDarkSectionTitle("查找"));
        final EditText searchInput = createDarkEditText("输入名称关键字过滤",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, searchInput, 6);
        // ---- 已装列表区（固定高度 + 二级滑动，列表变长不会导致整个功能页滚动）----
        panel.addView(createDarkSectionTitle("已安装 SKILL"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        addV(panel, createDarkTip("勾选 = 启用；取消勾选 = 禁用（reasonix 隐藏）。"), 8);
        // 刷新/查找联动
        skillRefreshRunnable = () -> loadSkillList(listBox, searchInput.getText().toString().trim());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { skillRefreshRunnable.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        skillRefreshRunnable.run();   // 初始加载列表
        showPanel("SKILL", panel, null);
    }

    /** SKILL 列表刷新任务（安装/删除/开关后调用；面板持有当前 listBox/filter） */
    private Runnable skillRefreshRunnable;

    /** SKILL 面板输入框引用（文件导入回调填充用；面板每次重建时重新赋值） */
    private EditText skillNameInput, skillContentInput;

    /** 全局 SKILL 根目录（Reasonix home: ~/.reasonix/skills）的宿主侧路径。
     *  SKILL 全局通用：所有项目共用（reasonix 每个项目都会加载 <Reasonix home>/skills/），
     *  切换项目不影响；固定路径在 rootfs 内，不依赖 guest 内 /sdcard 绑定。 */
    private File globalSkillsDir() {
        return new File(new File(new File(getFilesDir(), "rootfs"), "root"), ".reasonix/skills");
    }

    /** 解析 guest 读出的 disabled_skills 行（形如 disabled_skills = ["a", "b"]） */
    private void parseDisabled(String cfg, java.util.Set<String> disabled) {
        if (cfg == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]]*)\\]").matcher(cfg);
        if (m.find()) {
            for (String x : m.group(1).split(",")) {
                String n = x.trim().replace("\"", "").replace("'", "");
                if (!n.isEmpty()) disabled.add(n);
            }
        }
    }

    /** 异步加载已装 SKILL 列表与启用状态，渲染到容器（支持名称过滤） */
    private void loadSkillList(LinearLayout container, String filter) {
        container.removeAllViews();
        container.addView(createDarkTip("加载列表中..."));
        new Thread(() -> {
            try {
                final List<String> names = new ArrayList<>();
                final java.util.Set<String> disabled = new java.util.HashSet<>();
                // 全局 skills 目录（~/.reasonix/skills）：宿主侧列目录，与安装/删除一致，不依赖 guest /sdcard 绑定
                File[] dirs = globalSkillsDir().listFiles();
                if (dirs != null) {
                    for (File d : dirs) {
                        String n = d.getName();
                        if (d.isDirectory() && !n.startsWith(".")) names.add(n);
                    }
                }
                // disabled 状态：config.toml 在 /root/.reasonix/（Reasonix home），仍经 guest 读
                parseDisabled(executeInGuest(
                        "grep -A8 '\\[skills\\]' $HOME/.reasonix/config.toml 2>/dev/null | grep disabled_skills", 10),
                        disabled);
                runOnUiThread(() -> renderSkillList(container, names, disabled, filter));
            } catch (Exception e) {
                Log.e(TAG, "load skills failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "skill-load").start();
    }

    /** 渲染 SKILL 列表（每行：名称 + 启用勾选 + 删除） */
    private void renderSkillList(LinearLayout container, List<String> names,
                                 java.util.Set<String> disabled, String filter) {
        container.removeAllViews();
        boolean has = false;
        for (final String name : names) {
            if (filter != null && !filter.isEmpty() && !name.contains(filter)) continue;
            has = true;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextColor(0xFFE0E0E0);
            tv.setTextSize(14);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            CheckBox cb = new CheckBox(this);
            cb.setChecked(!disabled.contains(name));
            cb.setText("启用");
            cb.setTextColor(0xFFAAAAAA);
            cb.setOnCheckedChangeListener((b, checked) -> setSkillEnabled(name, checked));
            row.addView(cb);
            Button del = createDarkButton("删除");
            del.setOnClickListener(v -> {
                uninstallSkill(name);   // 列表刷新由 uninstallSkill 完成回调触发
            });
            row.addView(del);
            container.addView(row);
        }
        if (!has) container.addView(createDarkTip("（无匹配的已安装 SKILL）"));
    }

    /** 启用/禁用 SKILL：修改 guest 内 $HOME/.reasonix/config.toml 的 [skills] disabled_skills */
    private void setSkillEnabled(String name, boolean enable) {
        new Thread(() -> {
            try {
                String script = "import os, sys\n"
                        + "p = '/root/.reasonix/config.toml'\n"
                        + "name = sys.argv[1]\n"
                        + "disable = sys.argv[2] == '1'\n"
                        + "content = open(p).read() if os.path.exists(p) else ''\n"
                        + "lines = content.split('\\n')\n"
                        + "out = []\n"
                        + "found = False\n"
                        + "for l in lines:\n"
                        + "    if 'disabled_skills' in l and '[' in l:\n"
                        + "        found = True\n"
                        + "        arr = l[l.index('[')+1:l.rindex(']')]\n"
                        + "        names = [x.strip().strip('\\\"\\'').strip() for x in arr.split(',') if x.strip()]\n"
                        + "        if disable and name not in names: names.append(name)\n"
                        + "        if (not disable) and name in names: names.remove(name)\n"
                        + "        l = 'disabled_skills = [' + ', '.join('\\\"%s\\\"' % n for n in names) + ']'\n"
                        + "    out.append(l)\n"
                        + "if not found:\n"
                        + "    if '[skills]' in content:\n"
                        + "        idx = next(i for i,l in enumerate(out) if l.strip() == '[skills]')\n"
                        + "        j = idx + 1\n"
                        + "        while j < len(out) and not out[j].strip().startswith('['): j += 1\n"
                        + "        out.insert(j, 'disabled_skills = [' + ('\\\"%s\\\"' % name) + ']' if disable else 'disabled_skills = []')\n"
                        + "    else:\n"
                        + "        out += ['', '[skills]', 'disabled_skills = [' + ('\\\"%s\\\"' % name) + ']' if disable else 'disabled_skills = []']\n"
                        + "open(p, 'w').write('\\n'.join(out))\n"
                        + "print('OK ' + name + (' disabled' if disable else ' enabled'))\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                String out = executeInGuest("echo " + b64 + " | base64 -d > /tmp/skill_toggle.py; "
                        + "python3 /tmp/skill_toggle.py " + name + " " + (enable ? "0" : "1"), 10);
                runOnUiThread(() -> pushOutput("\r\n[SKILL " + name + (enable ? " 已启用" : " 已禁用") + "]"
                        + (out == null ? "" : "\n" + out) + "\r\n"));
            } catch (Exception e) {
                Log.e(TAG, "set skill enabled failed", e);
                runOnUiThread(() -> pushOutput("\r\n[切换 SKILL 启用状态失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-toggle").start();
    }

    /** 卸载 SKILL：删除全局 SKILL 目录 ~/.reasonix/skills/<name>（所有项目共用，切换项目不影响） */
    private void uninstallSkill(String name) {
        new Thread(() -> {
            try {
                String out;
                File dir = new File(globalSkillsDir(), name);
                boolean ok = deleteRecursive(dir);
                StringBuilder sb = new StringBuilder(ok ? "UNINSTALLED_OK" : "(宿主删除失败)");
                File[] left = globalSkillsDir().listFiles();
                if (left != null) {
                    for (File f : left) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) sb.append('\n').append(f.getName());
                    }
                }
                out = sb.toString();
                String msg = "\r\n[卸载 SKILL: " + name + "]\r\n"
                        + ((out != null && out.contains("UNINSTALLED_OK"))
                            ? "已删除。剩余 SKILL：\n" + out.replace("UNINSTALLED_OK", "").trim()
                            : (out == null ? "(无响应)" : out)) + "\r\n";
                runOnUiThread(() -> pushOutput(msg));
                runOnUiThread(() -> { if (skillRefreshRunnable != null) skillRefreshRunnable.run(); });
            } catch (Exception e) {
                Log.e(TAG, "uninstall skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[卸载 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-uninstall").start();
    }

    /** 回退路径：经 guest 服务循环写入全局 SKILL（base64 避免转义/引号问题） */
    private String guestInstallSkill(String name, String content) {
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        String cmd = "SK=\"/root/.reasonix/skills\"; "
                + "mkdir -p \"$SK/" + name + "\""
                + " && echo " + b64 + " | base64 -d > \"$SK/" + name + "/SKILL.md\""
                + " && chmod 644 \"$SK/" + name + "/SKILL.md\""
                + " && echo INSTALLED_OK && ls -la \"$SK/" + name + "/SKILL.md\"";
        return executeInGuest(cmd, 12);
    }

    /** 安装 SKILL：写入全局 SKILL 目录 ~/.reasonix/skills/<name>/SKILL.md（Reasonix home skills，
     *  所有项目共用，切换项目不影响；reasonix 内 /skills reload 或重启后显示）。
     *  宿主侧写入 rootfs（稳定，不依赖 guest /sdcard 绑定）；宿主写入失败回退 guest 命令。 */
    private void installSkill(String name, String content) {
        new Thread(() -> {
            try {
                String out;
                File dir = new File(globalSkillsDir(), name);
                try {
                    if (dir.mkdirs() || dir.isDirectory()) {
                        java.nio.file.Files.write(new File(dir, "SKILL.md").toPath(),
                                content.getBytes(StandardCharsets.UTF_8));
                        out = "INSTALLED_OK\n" + new File(dir, "SKILL.md").getAbsolutePath();
                    } else {
                        out = guestInstallSkill(name, content);   // 宿主创建失败回退
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "host skill write failed, fallback guest", e2);
                    out = guestInstallSkill(name, content);
                }
                String msg = "\r\n[安装 SKILL: " + name + "]\r\n"
                        + (out == null ? "(无响应)" : out)
                        + "\r\n[完成。SKILL 为全局安装（所有项目共用），reasonix 内 /skills reload 或重启应用环境后显示]\r\n";
                runOnUiThread(() -> pushOutput(msg));
                runOnUiThread(() -> { if (skillRefreshRunnable != null) skillRefreshRunnable.run(); });
            } catch (Exception e) {
                Log.e(TAG, "install skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[安装 SKILL 失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-install").start();
    }

    /** 解析 SKILL.md frontmatter 中的 name（开头 --- 到下一个 --- 之间的 name: 行），无则返回 null */
    private String parseSkillName(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (!c.startsWith("---")) return null;
        int end = c.indexOf("\n---", 3);
        if (end < 0) return null;
        String fm = c.substring(3, end);
        for (String l : fm.split("\n")) {
            String t = l.trim();
            if (t.startsWith("name:")) {
                String n = t.substring(5).trim().replace("\"", "").replace("'", "").trim();
                if (!n.isEmpty()) return n;
            }
        }
        return null;
    }

    /** 从手机文件导入 SKILL.md（SAF 免存储权限）：读取内容 → 解析 frontmatter name →
     *  填入表单（名称+内容）由用户确认，安装仍由「安装 SKILL」按钮触发 */
    private void importSkillFromUri(Uri uri) {
        new Thread(() -> {
            try {
                String content;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> pushOutput("\r\n[导入失败: 无法读取所选文件]\r\n"));
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    content = sb.toString();
                }
                if (content == null || content.trim().isEmpty()) {
                    runOnUiThread(() -> pushOutput("\r\n[导入失败: 文件为空]\r\n"));
                    return;
                }
                final String name = parseSkillName(content);
                final String finalContent = content;
                runOnUiThread(() -> {
                    // v2.0.25：SAF 选择期间面板可能已关/重建——面板已关时字段引用指向脱离
                    // View，填进去用户看不到还提示"已导入"。面板未开则放弃填充。
                    if (findViewById(R.id.panel_overlay).getVisibility() != View.VISIBLE) {
                        pushOutput("\r\n[SKILL 已取消（面板已关闭），重新打开面板再导入]\r\n");
                        return;
                    }
                    if (skillNameInput != null) skillNameInput.setText(name == null ? "" : name);
                    if (skillContentInput != null) skillContentInput.setText(finalContent);
                    pushOutput("\r\n[已导入" + (name != null ? " " + name : "") + "，检查后点「安装 SKILL」完成安装]\r\n");
                });
            } catch (Exception e) {
                Log.e(TAG, "import skill failed", e);
                runOnUiThread(() -> pushOutput("\r\n[导入 SKILL 文件失败: " + e.getMessage() + "]\r\n"));
            }
        }, "skill-import").start();
    }

    /* ==================== 项目位置 ==================== */

    /** 项目：reasonix 工作目录（会话/记忆按项目隔离），可建在内部或手机目录（/sdcard），切换后重启生效 */
    private void showProjectDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "项目为 reasonix 工作目录（会话/记忆按项目隔离）。\n"
                        + "「进入」切换（重启生效）；「删除」移除。可建内部或手机目录（文件管理器可见）。"));
        final TextView curView = new TextView(this);
        curView.setTextColor(0xFF4CAF50);
        curView.setTextSize(14);
        curView.setTypeface(null, android.graphics.Typeface.BOLD);
        curView.setPadding(dp(2), dp(8), dp(2), dp(4));
        addV(panel, curView, 6);
        // 项目列表（内置 + 手机分组渲染，每行 名称/进入/删除；固定高度 + 二级滑动）
        panel.addView(createDarkSectionTitle("项目列表"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(290));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        // 新增区
        panel.addView(createDarkSectionTitle("新建项目"));
        final EditText newInput = createDarkEditText("新项目名称（如 myapp，字母数字._-）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, newInput, 6);
        Button newSdcardBtn = createDarkButton("在手机目录创建并进入");
        newSdcardBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (checkProjectName(name)) createProject(name, true, curView, listBox);
        });
        addV(panel, newSdcardBtn, 8);
        Button newInnerBtn = createDarkButton("在内部创建并进入");
        newInnerBtn.setOnClickListener(v -> {
            String name = newInput.getText().toString().trim();
            if (checkProjectName(name)) createProject(name, false, curView, listBox);
        });
        addV(panel, newInnerBtn, 8);
        Button defaultBtn = createDarkButton("恢复默认（/root）");
        defaultBtn.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                new File(rootDir, ".rsxm-project").delete();
                pushOutput("\r\n[已恢复默认项目 /root，重启后生效]\r\n");
                loadProjectList(curView, listBox);
            } catch (Exception e) {
                Log.e(TAG, "reset project failed", e);
            }
        });
        addV(panel, defaultBtn, 8);
        Button refreshBtn = createDarkButton("刷新项目列表");
        refreshBtn.setOnClickListener(v -> loadProjectList(curView, listBox));
        addV(panel, refreshBtn, 8);
        loadProjectList(curView, listBox);
        showPanel("项目", panel, null);
    }

    /** 会话面板：列出 ~/.reasonix/projects/<项目>/sessions/ 下的历史会话（聊天记录），
     *  按项目分组显示（时间 + 预览）。点击「继续」写 /root/.rsxm-resume 标记并重启环境，
     *  entry.sh wrapper 用 `--resume <jsonl路径>` 恢复该会话；「删除」移入 sessions/.trash 回收站；
     *  「新建会话」清除恢复标记后重启，进入全新会话。 */
    private void showSessionsDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "reasonix 会话（聊天记录）按项目保存在 ~/.reasonix/projects/<项目>/sessions/，"
                        + "退出 reasonix 即自动保存；serve/GUI 会话同样落盘（文件 <时间戳>-<模型>.jsonl，"
                        + "标题取首条消息）。\n"
                        + "「继续」恢复该会话聊天（重启环境生效）；「删除」移入回收站；「新建会话」开始全新对话。"));
        panel.addView(createDarkSectionTitle("历史会话"));
        final ScrollView listScroll = new ScrollView(this);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        listLp.topMargin = dp(6);
        panel.addView(listScroll, listLp);
        Button newBtn = createDarkButton("新建会话（清除恢复标记，重启进入全新会话）");
        newBtn.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                new File(rootDir, ".rsxm-resume").delete();
                pushOutput("\r\n[已清除会话恢复标记，重启后开始全新会话]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "new session failed", e);
            }
        });
        addV(panel, newBtn, 8);
        Button refreshBtn = createDarkButton("刷新会话列表");
        refreshBtn.setOnClickListener(v -> loadSessions(listBox));
        addV(panel, refreshBtn, 8);
        loadSessions(listBox);
        showPanel("会话", panel, null);
    }

    /** 宿主侧遍历 rootfs 的 ~/.reasonix/projects 下各项目 sessions 目录中的 .jsonl 会话文件
     *  （排除回收站、恢复分支、隐藏文件），按项目分组渲染：每行「时间 + 预览」+ 继续/删除按钮。 */
    private void loadSessions(LinearLayout container) {
        container.removeAllViews();
        container.addView(createDarkTip("加载中..."));
        new Thread(() -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File projectsDir = new File(new File(rootDir, ".reasonix"), "projects");
                final java.util.List<String[]> groups = new java.util.ArrayList<>(); // {项目标签, jsonl绝对路径, 时间, 预览}
                if (projectsDir.isDirectory()) {
                    File[] pds = projectsDir.listFiles(File::isDirectory);
                    if (pds != null) {
                        java.util.Arrays.sort(pds, java.util.Comparator.comparing(File::getName));
                        for (File pd : pds) {
                            File sessions = new File(pd, "sessions");
                            // 只认会话主文件 *.jsonl（v1.39 新格式 <时间戳>-<模型>.jsonl，serve/GUI 会话
                            // 同样落盘；旧格式 <id>.jsonl 兼容）：排除 events/turns/conflicts 事件流
                            // （不含 role/content/model，且 lastModified 更新，一旦被选中就会挤掉
                            // 真正的会话文件——GUI 无气泡、无模型名）
                            File[] files = sessions.listFiles((d, n) ->
                                    n.endsWith(".jsonl") && !n.startsWith(".")
                                            && !n.endsWith(".events.jsonl") && !n.endsWith(".conflicts.jsonl")
                                            && !n.endsWith(".turns.jsonl")
                                            && !n.endsWith(".recovery.json") && !n.endsWith(".recovery")
                                            && !n.contains(".lease."));
                            if (files == null || files.length == 0) continue;
                            java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified).reversed());
                            String label = projectLabel(pd.getName());
                            for (File f : files) {
                                groups.add(new String[]{label, f.getAbsolutePath(),
                                        fmtSessionTime(f.lastModified()), sessionPreview(f)});
                            }
                        }
                    }
                }
                runOnUiThread(() -> {
                    container.removeAllViews();
                    if (groups.isEmpty()) {
                        container.addView(createDarkTip("（暂无历史会话，reasonix 会话会自动保存到这里）"));
                        return;
                    }
                    String lastProj = null;
                    for (String[] g : groups) {
                        if (!g[0].equals(lastProj)) {
                            lastProj = g[0];
                            TextView t = new TextView(this);
                            t.setText("项目 " + g[0]);
                            t.setTextColor(0xFF7FDB8A);
                            t.setTextSize(13);
                            t.setTypeface(null, android.graphics.Typeface.BOLD);
                            t.setPadding(0, dp(10), 0, dp(2));
                            container.addView(t);
                        }
                        container.addView(sessionRow(g, container));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "load sessions failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "session-load").start();
    }

    /** 会话行：时间 + 预览 + 「继续」「删除」 */
    private View sessionRow(String[] g, LinearLayout container) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(0xFF141414);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(4);
        row.setLayoutParams(rp);
        TextView info = new TextView(this);
        info.setText(g[2] + "\n" + g[3]);
        info.setTextColor(0xFFE0E0E0);
        info.setTextSize(13);
        info.setLineSpacing(0, 1.1f);
        row.addView(info);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cont = createDarkButton("继续");
        cont.setOnClickListener(v -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                // guest 内路径：rootDir（宿主 rootfs/root）对应 guest /root
                String abs = new File(g[1]).getAbsolutePath();
                String rel = abs.substring(rootDir.getAbsolutePath().length());
                String guestPath = "/root" + rel;
                java.nio.file.Files.write(new File(rootDir, ".rsxm-resume").toPath(),
                        guestPath.getBytes(StandardCharsets.UTF_8));
                pushOutput("\r\n[恢复会话: " + g[0] + " " + g[2] + "]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "resume session failed", e);
                pushOutput("\r\n[恢复失败: " + e.getMessage() + "]\r\n");
            }
        });
        Button del = createDarkButton("删除");
        del.setOnClickListener(v -> {
            final File f = new File(g[1]);
            // 后台线程执行：chroot 模式会话为 root 属主 600，renameTo 必失败且 root 桥 su 可能耗时数秒，不能阻塞 UI
            new Thread(() -> {
                try {
                    File trash = new File(f.getParentFile(), ".trash");
                    trash.mkdirs();
                    boolean ok = f.renameTo(new File(trash, f.getName()));
                    if (!ok) {
                        // v2.0.25 修复：trash 同名文件时 busybox mv 会静默覆盖，回收站旧会话丢失——
                        // 用 mv -n 禁覆盖 + 冲突时目标名追加时间戳
                        String tgt = sq(trash.getAbsolutePath() + "/" + f.getName());
                        String o = execRootCommand("mkdir -p '" + sq(trash.getAbsolutePath())
                                + "' && mv -n '" + sq(f.getAbsolutePath()) + "' '" + tgt + "' 2>&1"
                                + " && (! test -e '" + sq(f.getAbsolutePath()) + "' && echo TRASH_MOVED_OK)", 10);
                        ok = o != null && o.contains("TRASH_MOVED_OK");
                        if (!ok) {
                            // 同名冲突兜底：目标改名（加时间戳）再移
                            o = execRootCommand("mv '" + sq(f.getAbsolutePath()) + "' '"
                                    + sq(trash.getAbsolutePath() + "/" + f.getName() + "."
                                    + System.currentTimeMillis()) + "' 2>&1 && echo TRASH_MOVED_OK", 10);
                            ok = o != null && o.contains("TRASH_MOVED_OK");
                        }
                    }
                    final boolean r = ok;
                    runOnUiThread(() -> {
                        pushOutput("\r\n[会话" + (r ? "已移入回收站" : "删除失败") + ": " + g[2] + "]\r\n");
                        loadSessions(container);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "delete session failed", e);
                }
            }, "session-del").start();
        });
        btns.addView(cont);
        btns.addView(del);
        row.addView(btns);
        return row;
    }

    /** 项目 key 目录名 → 可读项目路径（reasonix key = 路径转义：\\→-、冒号去掉、字母小写；
     *  仅解码已知前缀，其余原样显示） */
    private static String projectLabel(String key) {
        String k = key.toLowerCase(java.util.Locale.US);
        if (k.startsWith("-sdcard-reasonixprojects-"))
            return "/sdcard/ReasonixProjects/" + key.substring("-sdcard-ReasonixProjects-".length());
        if (k.startsWith("-root-"))
            return "/root/" + key.substring("-root-".length());
        if (k.equals("-root")) return "/root";
        return key;
    }

    /** 会话时间：文件最后修改时间 → "MM-dd HH:mm" */
    private static String fmtSessionTime(long t) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()));
    }

    /** shell 单引号转义（root 桥命令拼接防注入/防路径含引号破坏命令） */
    private static String sq(String s) {
        return s.replace("'", "'\\''");
    }

    /** 会话预览：jsonl 第一条 user 消息文本（截断 50 字符）。
     *  chroot 模式下会话为 root 属主 600，宿主直读失败时经 root 桥 chmod 后重读。 */
    private String sessionPreview(File f) {
        byte[] head = readSessionHead(f);
        if (head == null) return "(读取失败，无 root 权限)";
        String text = new String(head, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) continue;
            try {
                org.json.JSONObject o = new org.json.JSONObject(line);
                if ("user".equals(o.optString("role"))) {
                    Object c = o.opt("content");
                    String s = (c instanceof String) ? (String) c : String.valueOf(c);
                    s = s.replaceAll("\\s+", " ").trim();
                    if (s.length() > 50) s = s.substring(0, 50) + "…";
                    return s.isEmpty() ? "(空)" : s;
                }
            } catch (Exception ignore) { }
        }
        return "(无文本内容)";
    }

    /** 读会话文件头部（前 64KB）；宿主直读失败（chroot 模式 root 属主 600）→ root 桥 chmod 后重读 */
    private byte[] readSessionHead(File f) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n = in.read(buf);
                byte[] out = new byte[Math.max(n, 0)];
                if (n > 0) System.arraycopy(buf, 0, out, 0, n);
                return out;
            } catch (Exception e) {
                if (attempt == 0) {
                    execRootCommand("chmod a+r '" + sq(f.getAbsolutePath()) + "' 2>/dev/null", 8);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /** 加载项目列表（内置 /root 与手机 /sdcard/ReasonixProjects），渲染到容器 */
    private void loadProjectList(TextView curView, LinearLayout container) {
        container.removeAllViews();
        container.addView(createDarkTip("加载中..."));
        new Thread(() -> {
            try {
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                String cur = "默认（/root）";
                File mark = new File(rootDir, ".rsxm-project");
                if (mark.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                            StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) cur = s;
                }
                // 内置项目 = /root/ 下用户创建的目录（~/.reasonix/projects/ 是 reasonix 自动生成的
                // 项目元数据/会话目录，手机项目也会触发，不再混入列表）
                String out = executeInGuest(
                        "echo [内部]:; ls -d /root/*/ 2>/dev/null | sed 's|/root/||; s|/$||';", 10);
                final List<String> inner = new ArrayList<>();
                final List<String> sdcard = new ArrayList<>();
                if (out != null) {
                    boolean inInner = false;
                    for (String l : out.split("\n")) {
                        String t = l.trim();
                        if (t.equals("[内部]:")) { inInner = true; continue; }
                        if (inInner && !t.isEmpty() && !t.contains("error")) inner.add(t);
                    }
                }
                // 手机目录：宿主侧直接读真存储（chroot 内写 /sdcard 会被 mount namespace 隔离，
                // 宿主文件管理器不可见；宿主 File API 读写才是文件管理器可见的目录）
                File sdcardProj = new File("/storage/emulated/0/ReasonixProjects");
                if (sdcardProj.isDirectory()) {
                    String[] list = sdcardProj.list();
                    if (list != null) {
                        for (String n : list) {
                            if (!n.startsWith(".")) sdcard.add(n);
                        }
                    }
                }
                final String c = cur;
                runOnUiThread(() -> {
                    curView.setText("当前项目：" + c);
                    renderProjectList(container, inner, sdcard, c, curView, container);
                });
            } catch (Exception e) {
                Log.e(TAG, "load projects failed", e);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "project-load").start();
    }

    /** 渲染项目列表：内置/手机分组，每行 名称 + 进入 + 删除 */
    private void renderProjectList(LinearLayout container, List<String> inner, List<String> sdcard,
                                   String cur, TextView curView, LinearLayout listBox) {
        container.removeAllViews();
        addProjectGroup(container, "内置目录（/root/）", inner, "/root", cur, curView, listBox);
        addProjectGroup(container, "手机目录（/sdcard/ReasonixProjects/）", sdcard, "/sdcard/ReasonixProjects", cur, curView, listBox);
        if (inner.isEmpty() && sdcard.isEmpty()) container.addView(createDarkTip("（暂无项目，可新建）"));
    }

    private void addProjectGroup(LinearLayout container, String title, List<String> names,
                                 String base, String cur, TextView curView, LinearLayout listBox) {
        if (names.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, dp(2));
        container.addView(t);
        for (final String name : names) {
            final String path = base + "/" + name;
            final boolean isCur = path.equals(cur);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView tv = new TextView(this);
            tv.setText(name + (isCur ? "（当前）" : ""));
            tv.setTextColor(isCur ? 0xFF4CAF50 : 0xFFE0E0E0);
            tv.setTextSize(14);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button go = createDarkButton("进入");
            go.setOnClickListener(v -> switchProject(path, curView, listBox));
            row.addView(go);
            Button del = createDarkButton("删除");
            del.setOnClickListener(v -> deleteProject(path, curView, listBox));
            row.addView(del);
            container.addView(row);
        }
    }

    /** 删除项目目录；若是当前项目则同时清除切换标记 */
    private void deleteProject(String path, TextView curView, LinearLayout listBox) {
        new Thread(() -> {
            try {
                boolean ok;
                if (path.startsWith("/sdcard/")) {
                    // 手机目录走宿主侧（文件管理器可见）
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    ok = deleteRecursive(new File("/storage/emulated/0/ReasonixProjects", name));
                } else {
                    String out = executeInGuest("rm -rf '" + sq(path) + "' && echo DELETED_OK", 10);
                    ok = out != null && out.contains("DELETED_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[删除项目失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                File mark = new File(rootDir, ".rsxm-project");
                if (mark.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                            StandardCharsets.UTF_8).trim();
                    if (s.equals(path)) mark.delete();   // 删的是当前项目，回默认
                }
                runOnUiThread(() -> {
                    pushOutput("\r\n[已删除项目 " + path + "]\r\n");
                    loadProjectList(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "delete project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[删除项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-del").start();
    }

    /** 递归删除目录/文件（宿主侧手机项目用） */
    private boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        return f.delete();
    }

    /** 校验新项目名称（目录名安全） */
    private boolean checkProjectName(String name) {
        if (name.isEmpty()) {
            pushOutput("\r\n[请输入项目名称]\r\n");
            return false;
        }
        // v2.0.25：显式拒绝 "." / ".."
        if (name.equals(".") || name.equals("..") || name.startsWith(".")) {
            pushOutput("\r\n[项目名称不允许以 . 开头或使用相对目录]\r\n");
            return false;
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            pushOutput("\r\n[项目名称仅允许字母、数字、_ . -]\r\n");
            return false;
        }
        return true;
    }

    /** 新建项目：手机目录(/sdcard/ReasonixProjects/<name>)或内部(/root/<name>)，创建后切换并重启 */
    private void createProject(String name, boolean inSdcard, TextView curView, LinearLayout listBox) {
        String path = inSdcard
                ? "/sdcard/ReasonixProjects/" + name
                : "/root/" + name;
        new Thread(() -> {
            try {
                // 手机目录走宿主侧（文件管理器可见；chroot 内写 /sdcard 被 mount namespace 隔离）；
                // 内置目录走 guest（rootfs 内）
                boolean ok;
                if (inSdcard) {
                    // mkdirs 失败不阻断（reasonix wrapper 启动时对标记目录 mkdir -p 兜底，
                    // guest 内可用；宿主可见性在权限正常时由这里保证）
                    try {
                        new File("/storage/emulated/0/ReasonixProjects", name).mkdirs();
                    } catch (Exception ignored) {
                    }
                    ok = true;
                } else {
                    String out = executeInGuest("mkdir -p '" + sq(path) + "' && echo MKDIR_OK", 10);
                    ok = out != null && out.contains("MKDIR_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[创建项目目录失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已创建项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                    refreshAfterEnvRestart(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "create project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[创建项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-create").start();
    }

    /** 刷新当前项目与项目列表显示（内部 + 手机目录） */
    /** 切换项目：guest 内创建目录 + 写标记 + 重启环境（reasonix 从新项目目录启动） */
    private void switchProject(String path, TextView curView, LinearLayout listBox) {
        new Thread(() -> {
            try {
                // 手机目录走宿主侧（文件管理器可见），内置走 guest
                boolean ok;
                if (path.startsWith("/sdcard/")) {
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    // 宿主 mkdirs（文件管理器可见）；失败不阻断切换——reasonix wrapper 启动时
                    // 会对标记目录 mkdir -p（guest 内 /sdcard 绑定可见宿主目录），目录存在性由它兜底
                    try {
                        new File("/storage/emulated/0/ReasonixProjects", name).mkdirs();
                    } catch (Exception ignored) {
                    }
                    ok = true;
                } else {
                    String out = executeInGuest("mkdir -p '" + sq(path) + "' && echo MKDIR_OK", 10);
                    ok = out != null && out.contains("MKDIR_OK");
                }
                if (!ok) {
                    runOnUiThread(() -> pushOutput("\r\n[创建项目目录失败]\r\n"));
                    return;
                }
                File rootDir = new File(new File(getFilesDir(), "rootfs"), "root");
                java.nio.file.Files.write(new File(rootDir, ".rsxm-project").toPath(),
                        (path + "\n").getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    pushOutput("\r\n[已切换到项目 " + path + "，正在重启 reasonix...]\r\n");
                    restartEnvironment();
                    refreshAfterEnvRestart(curView, listBox);
                });
            } catch (Exception e) {
                Log.e(TAG, "switch project failed", e);
                runOnUiThread(() -> pushOutput("\r\n[切换项目失败: " + e.getMessage() + "]\r\n"));
            }
        }, "project-switch").start();
    }

    /** 环境重启后延迟刷新项目列表（重启期间 guest 服务不可用，等环境起来再读） */
    private void refreshAfterEnvRestart(TextView curView, LinearLayout listBox) {
        curView.postDelayed(() -> loadProjectList(curView, listBox), 4000);
    }

    /* ==================== MCP 服务器 ==================== */

    /** MCP 服务器面板：管理当前项目 .mcp.json 的 mcpServers（reasonix 按项目根 .mcp.json
     *  发现 MCP 服务器）。列表（名称/类型/摘要）+ 添加/编辑表单 + 删除；保存后写回
     *  .mcp.json 并提示重启环境生效（MCP 服务器随 reasonix 会话启动连接）。 */
    /** MCP 服务器（最简化）：读取/编辑当前项目 .mcp.json 原始 JSON，一个输入框搞定。 */
    private void showMcpDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip(
                "MCP 服务器配置写入当前项目 .mcp.json（reasonix 按项目发现 MCP 能力）。\n"
                + "保存后重启环境生效。格式示例：\n"
                + "{ \"mcpServers\": { \"github\": { \"command\": \"npx\", "
                + "\"args\": [\"-y\",\"@server/github\"], \"auto_start\": true } } }"));

        final TextView pathInfo = createDarkResult();
        addV(panel, pathInfo, 8);

        final EditText jsonInput = new EditText(this);
        jsonInput.setHint(".mcp.json 完整内容（JSON）");
        jsonInput.setTextColor(0xFFFFFFFF);
        jsonInput.setHintTextColor(0xFF707070);
        jsonInput.setTextSize(12);
        jsonInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        jsonInput.setBackgroundColor(0xFF1A1A1A);
        jsonInput.setMinLines(6);
        jsonInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        addV(panel, jsonInput, 6);

        final TextView status = createDarkResult();
        addV(panel, status, 8);

        // 载入：读 .mcp.json
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                new Thread(() -> {
                    String dir = McpManager.projectDir(MainActivity.this);
                    String path = dir + "/.mcp.json";
                    try {
                        File f = new File(path);
                        if (f.exists()) {
                            // chroot/proot 权限保护：先 chmod 再读
                            execRootCommand("chmod a+r '" + f.getAbsolutePath() + "' 2>/dev/null", 5);
                            byte[] bb = java.nio.file.Files.readAllBytes(f.toPath());
                            String body = new String(bb, StandardCharsets.UTF_8);
                            runOnUiThread(() -> {
                                pathInfo.setText("文件：" + path);
                                jsonInput.setText(body);
                            });
                        } else {
                            runOnUiThread(() -> pathInfo.setText("（.mcp.json 不存在，保存时会自动创建）"));
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> pathInfo.setText("（读取失败：" + e.getMessage() + "）"));
                    }
                }, "mcp-load").start();
            }
        };
        refresh.run();

        Button saveBtn = createDarkButton("保存 .mcp.json");
        saveBtn.setOnClickListener(v -> {
            String txt = jsonInput.getText().toString().trim();
            if (txt.isEmpty()) { status.setText("内容为空"); return; }
            try {
                new org.json.JSONObject(txt);  // JSON 语法校验
            } catch (Exception e) {
                status.setText("JSON 语法错误：" + e.getMessage());
                return;
            }
            new Thread(() -> {
                String dir = McpManager.projectDir(MainActivity.this);
                String path = dir + "/.mcp.json";
                byte[] data = txt.getBytes(StandardCharsets.UTF_8);
                try {
                    new java.io.File(path).getParentFile().mkdirs();
                    java.nio.file.Files.write(new java.io.File(path).toPath(), data);
                    runOnUiThread(() -> {
                        status.setText("已保存\n请重启环境生效");
                        showToast("已保存");
                    });
                } catch (Exception directFail) {
                    // v2.0.25 修复：chroot 模式下项目文件为 root 属主，直写必失败——回退
                    // root 桥 heredoc 写入（McpManager.writeFileAny 同款兜底）
                    try {
                        String err = McpManager.writeGuestFile(MainActivity.this, path, txt);
                        if (err != null) throw new Exception(err);
                        runOnUiThread(() -> {
                            status.setText("已保存（root 桥）\n请重启环境生效");
                            showToast("已保存");
                        });
                    } catch (Exception e2) {
                        runOnUiThread(() -> status.setText("保存失败：" + directFail.getMessage() + " / " + e2.getMessage()));
                    }
                }
            }, "mcp-save").start();
        });
        addV(panel, saveBtn, 8);

        showPanel("MCP 服务器", panel, null);
        // v2.0.25：只发起一次 refresh（旧代码 showPanel 前后各一次，第二次落地晚会
        // 冲掉用户已输入的 json 内容）
    }


    /** 开发环境：一键安装常用开发环境（Alpine 包管理器，后台安装 + 面板内实时进度） */
    private void showDevEnvDialog() {
        panelGen++;   // 面板重建：安装线程的旧 View 引用作废（回调检测代际后放弃更新）
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        panel.addView(createDarkTip(
                "一键安装开发环境（Alpine 包，需联网），进度实时显示。\n"
                        + "大环境（Android/Go）耗时较长。"));

        // chroot 检测：Android 开发依赖 chroot 运行模式（JVM 需 root 域，proot + SELinux
        // enforcing 下 mprotect RWX 被拒无法启动）；未开启 chroot 时置灰并提示。
        // Python/Node/Go/C-C++/通用工具不依赖 chroot，保持可用。
        final boolean chrootOn = isChrootMode();
        final TextView chrootWarn = new TextView(this);
        chrootWarn.setText(chrootOn ? ""
                : "⚠ 未开启 chroot：Android 开发已置灰（JVM 需 root 域）。\nROOT 面板可切换为 chroot。");
        chrootWarn.setTextColor(chrootOn ? 0xFFAAAAAA : 0xFFFF6B6B);
        chrootWarn.setTextSize(13);
        chrootWarn.setLineSpacing(0, 1.2f);
        chrootWarn.setMaxLines(2);
        chrootWarn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chrootWarn.setPadding(0, dp(6), 0, dp(2));
        // 固定高度：警告出现/消失/精简不引起功能页内容上下移动
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        warnLp.topMargin = dp(6);
        panel.addView(chrootWarn, warnLp);

        // 安装进度区（固化显示且高度固定：空闲/安装中/完成时输出内容变化不引起功能页上下调整）
        LinearLayout progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, dp(12), 0, dp(8));
        progressBox.setVisibility(View.VISIBLE);
        TextView progressTitle = new TextView(this);
        progressTitle.setText("安装进度");
        progressTitle.setTextColor(0xFFAAAAAA);
        progressTitle.setTextSize(14);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        TextView progressText = new TextView(this);
        progressText.setText("空闲：点列表中的「安装」开始，进度实时显示于此。");
        progressText.setTextColor(0xFFAAAAAA);
        progressText.setTextSize(12);
        progressText.setPadding(0, dp(4), 0, 0);
        progressText.setMaxLines(2);   // 固定行数，输出变化不改变高度
        progressText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        progressBox.addView(progressTitle);
        progressBox.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        progressBox.addView(progressText);
        panel.addView(progressBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        // envs 第 4 列：是否依赖 chroot 运行模式。实测仅 Android 开发依赖
        // （JVM 需 root 域 execmem，proot + SELinux enforcing 下 mprotect RWX 被拒无法启动）；
        // Python/Node/Go/C-C++/通用工具 安装与运行均不依赖。
        // envs 第 5 列：关键命令（安装前检测半装：数据库标记已装但文件缺失 → 自动清理重装）
        String[][] builtin = {
                {"Android 开发", "openjdk17-jdk gradle android-tools",
                        "JDK17 + Gradle + adb/fastboot（安卓应用构建）", "1", "java gradle adb"},
                {"Python 开发", "python3 py3-pip", "Python3 + pip", "0", "python3 pip3"},
                {"Node.js", "nodejs npm", "Node.js + npm", "0", "node npm"},
                {"Go 开发", "go", "Go 语言工具链", "0", "go"},
                {"C/C++ 开发", "gcc g++ make musl-dev", "GCC/G++ + Make + 头文件", "0", "gcc g++ make"},
                {"通用工具", "git vim curl wget zip unzip", "Git/Vim/curl/wget 等", "0", "git vim curl wget zip unzip"},
        };
        // 合并模板导入的自定义环境（持久化于本地偏好，重启 app 保留）
        java.util.List<String[]> envListAll = new java.util.ArrayList<>();
        Collections.addAll(envListAll, builtin);
        envListAll.addAll(loadCustomEnvs());
        final String[][] envs = envListAll.toArray(new String[0][]);
        // ---- 模板区：下载模板（.rsxmenv）+ 导入模板 ----
        panel.addView(createDarkSectionTitle("自定义模板"));
        LinearLayout tplRow = new LinearLayout(this);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        Button dlBtn = createDarkButton("下载模板");
        dlBtn.setOnClickListener(v -> downloadEnvTemplate());
        Button impBtn = createDarkButton("导入模板");
        impBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMPORT_ENV_TEMPLATE);
            } catch (Exception e) {
                pushOutput("\r\n[无法打开文件选择器: " + e.getMessage() + "]\r\n");
            }
        });
        tplRow.addView(dlBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tplRow.addView(impBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tplRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tplRowLp.topMargin = dp(6);
        panel.addView(tplRow, tplRowLp);
        addV(panel, createDarkTip("模板（.rsxmenv）可自定义环境：下载模板 → 配置 apk 包 → 导入后即可安装。\n"
                + "chroot=1 的环境需先在 ROOT 面板开启 chroot 模式。"), 8);
        // ---- 已安装列表（固定高度 + 二级滑动；每行 名称/状态/删除）----
        panel.addView(createDarkSectionTitle("已安装环境"));
        final ScrollView envScroll = new ScrollView(this);
        final LinearLayout envList = new LinearLayout(this);
        envList.setOrientation(LinearLayout.VERTICAL);
        envScroll.addView(envList);
        LinearLayout.LayoutParams envLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        envLp.topMargin = dp(6);
        panel.addView(envScroll, envLp);
        loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
        // 面板重开时若仍有安装任务在后台进行：恢复显示进度区
        String installing = sDevEnvInstallingName;
        if (installing != null) {
            progressBox.setVisibility(View.VISIBLE);
            progressTitle.setText(installing + " 安装中...");
            progressTitle.setTextColor(0xFFFFFFFF);
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
            progressText.setText("安装进行中，请稍候...");
        }
        showPanel("开发环境", panel, null);
    }

    /** 开发环境模板（.rsxmenv）默认内容：下载模板时写入，用户编辑后导入添加自定义环境 */
    private static final String ENV_TEMPLATE_CONTENT =
            "# RSXM 开发环境模板（.rsxmenv）\n"
            + "# 配置后保存，在「开发环境」面板点「导入模板」选择此文件即可添加自定义环境\n"
            + "# name：环境名（显示用）\n"
            + "# packages：apk 包列表（空格分隔）\n"
            + "# description：描述\n"
            + "# chroot：1=依赖 chroot 运行模式，0=不依赖\n"
            + "# key_commands：关键命令（空格分隔，用于已装检测）\n"
            + "name=myenv\n"
            + "packages=git curl\n"
            + "description=我的自定义开发环境\n"
            + "chroot=0\n"
            + "key_commands=git curl\n";

    /** 读取持久化的自定义环境（模板导入，SharedPreferences JSON），返回 {name, packages, desc, chroot, keyCmds}[] */
    private List<String[]> loadCustomEnvs() {
        List<String[]> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences("prefs", MODE_PRIVATE).getString("custom_dev_envs", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{
                        o.optString("name", ""),
                        o.optString("packages", ""),
                        o.optString("description", ""),
                        o.optString("chroot", "0"),
                        o.optString("keyCmds", "")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 持久化自定义环境列表 */
    private void saveCustomEnvs(List<String[]> envs) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] e : envs) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", e[0]);
                o.put("packages", e[1]);
                o.put("description", e[2]);
                o.put("chroot", e[3]);
                o.put("keyCmds", e[4]);
                arr.put(o);
            }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("custom_dev_envs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 解析 .rsxmenv 模板文本：返回 {name, packages, desc, chroot, keyCmds}，缺 name/packages 返回 null */
    private String[] parseEnvTemplate(String content) {
        String name = "", packages = "", desc = "", chroot = "0", cmds = "";
        for (String l : content.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            if (k.equals("name")) name = v;
            else if (k.equals("packages")) packages = v;
            else if (k.equals("description")) desc = v;
            else if (k.equals("chroot")) chroot = v.equals("1") ? "1" : "0";
            else if (k.equals("key_commands")) cmds = v;
        }
        if (name.isEmpty() || packages.isEmpty()) return null;
        if (cmds.isEmpty()) cmds = packages.split(" ")[0];   // 缺省用第一个包作为关键命令
        return new String[]{name, packages, desc, chroot, cmds};
    }

    /** 下载开发环境模板：系统保存选择器（SAF），默认文件名 dev-env-template.rsxmenv */
    private void downloadEnvTemplate() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        i.putExtra(Intent.EXTRA_TITLE, "dev-env-template.rsxmenv");
        try {
            startActivityForResult(i, REQ_CREATE_ENV_TEMPLATE);
        } catch (Exception e) {
            pushOutput("\r\n[无法打开保存选择器: " + e.getMessage() + "]\r\n");
        }
    }

    /** 导入开发环境模板：读取 .rsxmenv → 解析 → 持久化（同名覆盖）→ 重建面板显示新环境 */
    private void importEnvTemplate(Uri uri) {
        new Thread(() -> {
            try {
                String content;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> pushOutput("\r\n[导入失败: 无法读取所选文件]\r\n"));
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    content = sb.toString();
                }
                final String[] env = parseEnvTemplate(content);
                if (env == null) {
                    runOnUiThread(() -> pushOutput("\r\n[模板无效：缺少 name 或 packages 字段]\r\n"));
                    return;
                }
                List<String[]> customs = loadCustomEnvs();
                boolean replaced = false;
                for (int i = 0; i < customs.size(); i++) {
                    if (customs.get(i)[0].equals(env[0])) { customs.set(i, env); replaced = true; }
                }
                if (!replaced) customs.add(env);
                saveCustomEnvs(customs);
                final boolean replacedFinal = replaced;
                runOnUiThread(() -> {
                    pushOutput("\r\n[已导入环境模板 " + env[0]
                            + (replacedFinal ? "（同名已覆盖）" : "") + "，可点「安装」开始安装]\r\n");
                    hidePanel();
                    showDevEnvDialog();   // 重建面板，列表显示新环境
                });
            } catch (Exception e) {
                Log.e(TAG, "import env template failed", e);
                runOnUiThread(() -> pushOutput("\r\n[导入模板失败: " + e.getMessage() + "]\r\n"));
            }
        }, "env-template-import").start();
    }

    /** 安装开发环境：guest 内 nohup 后台 apk add（防服务循环 20s timeout 杀掉长安装），
     *  轮询状态文件报告结果，并解析 apk 日志 (x/N) 实时刷新面板进度条 */
    private void installDevEnv(String name, String packages, String keyCmds,
                               View progressBox, TextView progressTitle, ProgressBar progressBar,
                               TextView progressText, LinearLayout envList, String[][] envs) {
        if (sDevEnvInstalling) {
            runOnUiThread(() -> pushOutput("\r\n[开发环境] 已有安装任务进行中，请等待完成\r\n"));
            return;
        }
        sDevEnvInstalling = true;
        sDevEnvInstallingName = name;
        pushOutput("\r\n[开发环境] 开始安装 " + name + "（后台进行，进度见面板，可继续使用终端）...\r\n");
        runOnUiThread(() -> {
            progressBox.setVisibility(View.VISIBLE);
            progressTitle.setText(name + " 安装中...");
            progressTitle.setTextColor(0xFFFFFFFF);
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
            progressText.setText("正在更新软件源索引...");
        });
        new Thread(() -> {
            final int gen = panelGen;   // 代际捕获：面板重开后回调不再更新旧控件（B4）
            try {
                // 安装脚本经 base64 写入 guest（服务循环用 sh -c "$CMD" 执行，命令内不能含双引号），
                // 再 nohup 后台执行，避免 20s timeout 杀掉长安装。
                // 半装自动修复：关键命令缺失（apk 数据库标记已装但文件中断缺失，如 libjli.so/二进制丢失）
                // 时先 apk del 清理残留再全新安装，避免 apk add 因"已装"直接跳过导致环境不可用。
                String script = "#!/bin/sh\n"
                        + "# 等待其他 apk 操作完成（防并发 apk 数据库锁冲突：Unable to lock database, 退出码 99）\n"
                        + "i=0\n"
                        + "while [ -f /root/.env-lock ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done\n"
                        + "touch /root/.env-lock\n"
                        + "trap 'rm -f /root/.env-lock' EXIT\n"
                        + "apk update > /root/.env-install.log 2>&1\n"
                        + "echo \"--- 安装 " + name + " ---\" >> /root/.env-install.log\n"
                        + "NEED=0\n"
                        + "for c in " + keyCmds + "; do command -v $c >/dev/null 2>&1 || NEED=1; done\n"
                        + "if [ $NEED = 1 ]; then\n"
                        + "  echo \"[检测到安装不完整，清理残留后重新安装]\" >> /root/.env-install.log\n"
                        + "  apk del " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "fi\n"
                        + "apk add --no-cache " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "echo INSTALL_DONE_$? > /root/.env-done\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                executeInGuest("rm -f /root/.env-done /root/.env-install.log; "
                        + "echo " + b64 + " | base64 -d > /root/.env-install.sh; chmod +x /root/.env-install.sh; "
                        + "nohup sh /root/.env-install.sh > /dev/null 2>&1 & echo STARTED", 8);
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000);
                    // 读取日志尾部解析进度（fetch 阶段不定进度，(x/N) 阶段百分比）
                    String log = executeInGuest("tail -80 /root/.env-install.log 2>/dev/null", 6);
                    if (log != null) {
                        Matcher m = APK_PROGRESS.matcher(log);
                        int done = -1, total = -1;
                        while (m.find()) {
                            done = Integer.parseInt(m.group(1));
                            total = Integer.parseInt(m.group(2));
                        }
                        final int fDone = done, fTotal = total;
                        runOnUiThread(() -> {
                            if (gen != panelGen) return;   // 面板已关/重开：旧控件不更新
                            if (fDone > 0 && fTotal > 0) {
                                int pct = Math.min(99, fDone * 100 / fTotal);
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(pct);
                                progressText.setText("正在安装 " + fDone + "/" + fTotal + "（" + pct + "%）");
                            } else if (log.contains("fetch")) {
                                progressBar.setIndeterminate(true);
                                progressText.setText("正在下载软件包...");
                            } else {
                                progressBar.setIndeterminate(true);
                                progressText.setText("正在更新软件源索引...");
                            }
                        });
                    }
                    String st = executeInGuest("cat /root/.env-done 2>/dev/null", 6);
                    if (st != null && st.contains("INSTALL_DONE_")) {
                        final String code = st.replaceAll("[^0-9]", "").trim();
                        String doneLog = executeInGuest("tail -4 /root/.env-install.log 2>/dev/null", 6);
                        final String tail = doneLog == null ? "" : doneLog;
                        final boolean ok = code.equals("0");
                        String msg = "\r\n[开发环境] " + name + " 安装完成（apk 退出码 " + code + "）\n"
                                + tail + "\r\n"
                                + (ok ? "[完成] 可直接在终端使用 " + name + " 环境\r\n"
                                : "[失败] 请检查网络（aliyun 源）后重试\r\n");
                        runOnUiThread(() -> {
                            if (gen != panelGen) { pushOutput(msg); return; }   // 面板已换：只写终端
                            progressTitle.setText(name + (ok ? " 安装完成" : " 安装失败"));
                            progressTitle.setTextColor(ok ? 0xFF7FDB8A : 0xFFFF6B6B);
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(100);
                            // 进度条下只显示简单状态，不回显安装日志（tail 已固化省略无意义）
                            progressText.setText(ok ? "完成（退出码 0）" : "失败（apk 退出码 " + code + "）");
                            loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                            pushOutput(msg);
                        });
                        return;
                    }
                }
                runOnUiThread(() -> {
                    if (gen != panelGen) return;   // 面板已换：旧控件引用作废
                    progressTitle.setText(name + " 安装超时");
                    progressTitle.setTextColor(0xFFFFD54F);
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    progressText.setText("超过 15 分钟未完成，请检查网络后重试");
                    loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                    pushOutput("\r\n[开发环境] " + name + " 安装超时（15 分钟），请检查网络后重试\r\n");
                });
            } catch (Exception e) {
                Log.e(TAG, "install dev env failed", e);
                runOnUiThread(() -> {
                    if (gen != panelGen) return;   // 面板已换：旧控件引用作废
                    progressTitle.setText(name + " 安装失败");
                    progressTitle.setTextColor(0xFFFF6B6B);
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    progressText.setText("异常：" + e.getMessage());
                    loadInstalledEnvs(envList, envs, progressBox, progressTitle, progressBar, progressText);
                    pushOutput("\r\n[开发环境] 安装失败: " + e.getMessage() + "\r\n");
                });
            } finally {
                sDevEnvInstalling = false;
                sDevEnvInstallingName = null;
            }
        }, "dev-env").start();
    }

    /** 检测各开发环境是否已安装（关键命令存在），渲染到已安装列表 */
    private void loadInstalledEnvs(LinearLayout container, String[][] envs, View progressBox,
                                   TextView progressTitle, ProgressBar progressBar, TextView progressText) {
        container.removeAllViews();
        container.addView(createDarkTip("检测中..."));
        new Thread(() -> {
            try {
                // 标签动态生成：内置+自定义环境都可能超过 6 个（A-Z 后接 a-z，最多 52 个）
                char[] tags = new char[envs.length];
                for (int i = 0; i < envs.length; i++) tags[i] = (char)(i < 26 ? 'A' + i : 'a' + (i - 26));
                StringBuilder cmd = new StringBuilder("ok(){ command -v $1 >/dev/null 2>&1 && echo -n 1 || echo -n 0; }; ");
                for (int i = 0; i < envs.length && i < tags.length; i++) {
                    cmd.append("echo ").append(tags[i]).append(":$(ok ")
                            .append(envs[i][4].replace(" ", ")$(ok ")).append("); ");
                }
                String out = executeInGuest(cmd.toString(), 15);
                final boolean[] installed = new boolean[envs.length];
                final boolean[] partial = new boolean[envs.length];
                if (out != null) {
                    for (int i = 0; i < envs.length && i < tags.length; i++) {
                        String line = null;
                        for (String l : out.split("\n")) {
                            if (l.startsWith(tags[i] + ":")) { line = l.substring(2).trim(); break; }
                        }
                        if (line == null) continue;
                        int count = 0;
                        for (int k = 0; k < line.length(); k++) if (line.charAt(k) == '1') count++;
                        int total = envs[i][4].split(" ").length;
                        installed[i] = count == total;
                        partial[i] = count > 0 && count < total;
                    }
                }
                final boolean[] fInstalled = installed, fPartial = partial;
                runOnUiThread(() -> renderInstalledEnvs(container, envs, fInstalled, fPartial,
                        progressBox, progressTitle, progressBar, progressText));
            } catch (Exception e) {
                Log.e(TAG, "load installed envs failed", e);
                // v2.0.25：检测失败要有 UI 反馈，不能永远卡「检测中...」
                runOnUiThread(() -> {
                    container.removeAllViews();
                    container.addView(createDarkTip("（检测失败：" + e.getMessage() + "）"));
                });
            }
        }, "env-installed-load").start();
    }

    /** 渲染环境列表（每行：名称 + 状态 + 未安装时的「安装」按钮 + 已装时的「删除」按钮） */
    private void renderInstalledEnvs(LinearLayout container, String[][] envs,
                                     boolean[] installed, boolean[] partial,
                                     View progressBox, TextView progressTitle,
                                     ProgressBar progressBar, TextView progressText) {
        container.removeAllViews();
        // 自定义环境名集合（模板导入）：未安装时额外提供「移除」按钮，可彻底剔除模板条目
        java.util.Set<String> customNames = new java.util.HashSet<>();
        for (String[] ce : loadCustomEnvs()) customNames.add(ce[0]);
        for (int i = 0; i < envs.length; i++) {
            final String[] e = envs[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView tv = new TextView(this);
            String st = installed[i] ? "已安装" : (partial[i] ? "部分安装" : "未安装");
            tv.setText(e[0] + "：" + st);
            tv.setTextColor(installed[i] ? 0xFF7FDB8A : (partial[i] ? 0xFFFFD54F : 0xFF888888));
            tv.setTextSize(13);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // 未安装/部分 → 安装（重装）按钮；chroot 依赖环境（Android 开发等，e[3]=="1"）
            // 在非 chroot 模式不可用（JVM 需 root 域）→ 置灰；已安装/部分 → 删除按钮
            if (!installed[i]) {
                final boolean needChroot = e[3].equals("1") && !isChrootMode();
                final String label = needChroot ? "需 chroot" : (partial[i] ? "重装" : "安装");
                Button inst = createDarkButton(label);
                if (needChroot) {
                    inst.setEnabled(false);
                    inst.setTextColor(0xFF6A6A6A);
                    inst.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1E1E1E));
                } else {
                    inst.setOnClickListener(v -> installDevEnv(e[0], e[1], e[4],
                            progressBox, progressTitle, progressBar, progressText, container, envs));
                }
                row.addView(inst);
                if (customNames.contains(e[0])) {
                    Button rm = createDarkButton("移除");
                    rm.setOnClickListener(v -> removeCustomEnv(e[0]));
                    row.addView(rm);
                }
            }
            if (installed[i] || partial[i]) {
                Button del = createDarkButton("删除");
                del.setOnClickListener(v -> uninstallDevEnv(e[0], e[1], container, envs,
                        progressBox, progressTitle, progressBar, progressText));
                row.addView(del);
            }
            container.addView(row);
        }
    }

    /** 移除自定义环境模板：从持久化列表剔除并重建面板（已装的 apk 请先点「删除」） */
    private void removeCustomEnv(String name) {
        List<String[]> customs = loadCustomEnvs();
        boolean removed = false;
        for (int i = 0; i < customs.size(); i++) {
            if (customs.get(i)[0].equals(name)) { customs.remove(i); removed = true; break; }
        }
        if (!removed) return;
        saveCustomEnvs(customs);
        pushOutput("\r\n[已移除自定义环境 " + name + "]\r\n");
        hidePanel();
        showDevEnvDialog();
    }

    /** 删除开发环境：guest 内 nohup apk del，完成后刷新已安装列表 */
    private void uninstallDevEnv(String name, String packages, LinearLayout container, String[][] envs,
                                 View progressBox, TextView progressTitle, ProgressBar progressBar, TextView progressText) {
        if (sDevEnvInstalling) {
            runOnUiThread(() -> pushOutput("\r\n[开发环境] 有安装/删除任务进行中，请等待完成\r\n"));
            return;
        }
        sDevEnvInstalling = true;
        sDevEnvInstallingName = name + " 删除";
        pushOutput("\r\n[开发环境] 开始删除 " + name + "（后台进行）...\r\n");
        new Thread(() -> {
            final int gen = panelGen;   // 代际捕获：面板重开后不再刷新旧列表控件
            try {
                String script = "#!/bin/sh\n"
                        + "# 等待其他 apk 操作完成（防并发 apk 数据库锁冲突）\n"
                        + "i=0\n"
                        + "while [ -f /root/.env-lock ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done\n"
                        + "touch /root/.env-lock\n"
                        + "trap 'rm -f /root/.env-lock' EXIT\n"
                        + "echo \"--- 删除 " + name + " ---\" > /root/.env-install.log\n"
                        + "apk del " + packages + " >> /root/.env-install.log 2>&1\n"
                        + "echo INSTALL_DONE_$? > /root/.env-done\n";
                String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
                executeInGuest("rm -f /root/.env-done /root/.env-install.log; "
                        + "echo " + b64 + " | base64 -d > /root/.env-install.sh; chmod +x /root/.env-install.sh; "
                        + "nohup sh /root/.env-install.sh > /dev/null 2>&1 & echo STARTED", 8);
                long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000);
                    String st = executeInGuest("cat /root/.env-done 2>/dev/null", 6);
                    if (st != null && st.contains("INSTALL_DONE_")) {
                        runOnUiThread(() -> {
                            if (gen == panelGen) {   // 面板已换：旧容器列表不刷新（B4）
                                loadInstalledEnvs(container, envs, progressBox, progressTitle, progressBar, progressText);
                            }
                            pushOutput("\r\n[开发环境] " + name + " 已删除\r\n");
                        });
                        return;
                    }
                }
                runOnUiThread(() -> pushOutput("\r\n[开发环境] " + name + " 删除超时\r\n"));
            } catch (Exception e) {
                Log.e(TAG, "uninstall dev env failed", e);
            } finally {
                sDevEnvInstalling = false;
                sDevEnvInstallingName = null;
            }
        }, "dev-env-del").start();
    }

    /** 深色输入框（原生 Material 下划线风格，背景透明保持纯黑） */
    private EditText createDarkEditText(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setHint(hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF707070);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        // 统一块状深灰底（与 SKILL 内容输入框一致），替代 Material 下划线，深色面板更清晰
        et.setBackgroundColor(0xFF1A1A1A);
        return et;
    }

    /** 原生风格按钮（平台 Theme.Material 下自带圆角/波纹，仅覆 tint 为黑灰保持纯黑）。
     *  统一最小高度与内边距：全宽主按钮与行内小按钮观感一致 */
    private Button createDarkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setAllCaps(false);
        // 原生涟漪保留（colorControlHighlight），底色改为黑灰
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF262626));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    /** 深色说明文字（12sp 灰，带行距） */
    private TextView createDarkTip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(12);
        tv.setLineSpacing(0, 1.3f);
        return tv;
    }

    /** 深色区块标题（14sp 白 bold，与说明文字层级区分，用于功能页分组） */
    private TextView createDarkSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    /** 添加子视图并统一顶部间距（功能页排版：元素间固定留白；负值/0 无上边距） */
    private void addV(LinearLayout panel, View v, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topDp > 0) lp.topMargin = dp(topDp);
        panel.addView(v, lp);
    }

    // ==================== GitHub（登录 / Token 管理） ====================

    /** GitHub 面板：token 添加/保存、登录验证（自动打包功能已移除）。 */
    private void showGitHubDialog() {
        final GitHubManager gh = new GitHubManager(this);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));

        addV(panel, createDarkSectionTitle("GitHub 登录（access token）"), 0);
        addV(panel, createDarkTip("输入 GitHub Personal Access Token（repo 读权限即可），"
                + "用于登录验证。Token 仅保存在本机，不写入 Linux 环境。"), 4);

        final EditText tokenInput = createDarkEditText("ghp_xxx / github_pat_xxx", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        addV(panel, tokenInput, 8);

        final TextView status = createDarkResult();
        addV(panel, status, 8);

        // 登录 / 清除
        LinearLayout rowTop = new LinearLayout(this);
        rowTop.setOrientation(LinearLayout.HORIZONTAL);
        Button btnLogin = createDarkButton("登录 / 验证");
        Button btnClear = createDarkButton("清除 Token");
        rowTop.addView(btnLogin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rowTop.addView(btnClear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addV(panel, rowTop, 10);

        btnLogin.setOnClickListener(v -> {
            String tok = tokenInput.getText().toString().trim();
            // 掩码占位（预填的 ghp_xxxx••••••••）不是真实 token：直接提交必然失败，
            // 若仍是掩码占位则按未输入处理，提示用户重输
            if (!tok.isEmpty() && tok.contains("••••")) {
                if (tokenInput.getTag() != null && tok.startsWith(
                        tokenInput.getTag().toString().substring(0, Math.min(8, tokenInput.getTag().toString().length())))) {
                    status.setText("token 已遮蔽显示，如需重新验证请先清除后粘贴完整 token。");
                    return;
                }
            }
            if (tok.isEmpty()) {
                status.setText("请输入 token。");
                return;
            }
            status.setText("验证中...");
            new Thread(() -> {
                GitHubManager.LoginResult r = gh.login(tok);
                runOnUiThread(() -> status.setText(r.ok
                        ? "登录成功：@" + r.login + (r.name.isEmpty() ? "" : "（" + r.name + "）")
                        : "登录失败：" + r.error));
            }, "gh-login").start();
        });

        btnClear.setOnClickListener(v -> {
            gh.clearToken();
            tokenInput.setText("");
            status.setText("已清除 token。");
        });

        showPanel("GitHub", panel, null);
        // 预填已保存 token（仅展示前 8 位，保护隐私）
        String saved = gh.getToken();
        if (!saved.isEmpty()) {
            tokenInput.setText(saved.length() > 8 ? saved.substring(0, 8) + "••••••••" : saved);
            tokenInput.setTag(saved);
        }
        tokenInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                // 用户编辑时清除遮蔽占位
                if (tokenInput.getTag() != null && !s.toString().startsWith(tokenInput.getTag().toString().substring(0, Math.min(8, tokenInput.getTag().toString().length())))) {
                    tokenInput.setTag(null);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        // 进入面板即验证 token 登录态
        new Thread(() -> {
            GitHubManager.LoginResult r = gh.fetchUser();
            runOnUiThread(() -> status.setText(r.ok
                    ? "已登录：@" + r.login + (r.name.isEmpty() ? "" : "（" + r.name + "）")
                    : (gh.hasToken() ? "Token 无效：" + r.error : "未登录：请在下方输入 GitHub access token。")));
        }, "gh-user").start();
    }

    /* ==================== dsh purge（宿主清洗台 + 演练台，融合 dsh-purge v1.1.35 侧栏 UI） ==================== */

    /** purge 面板版本与偏好键 */
    private static final String PURGE_VER = "v1.1.35";
    private static final String PURGE_THEME_KEY = "purge_theme_light";   // true=白纸主题, false=墨色主题(默认)
    private static final String PURGE_DRILL_OK = "purge_drill_ok";       // 演练台已授权

    /** 清洗页补丁定义：RSXM 宿主策略补丁（映射 dsh-purge「清洗 → 补丁」）。
     *  0 破甲 system_prompt / 1 权限白名单 / 2 Guardian 禁用 / 3 YOLO 审批 / 4 全局指令 */
    private static final int PATCH_ARMOR = 0, PATCH_PERM = 1, PATCH_GUARDIAN = 2,
            PATCH_YOLO = 3, PATCH_AGENTS = 4;
    private static final String[] PATCH_MARKERS = {
            "RSXM_ARMOR_V2", "RSXM_PERM_ALLOW", "RSXM_GUARDIAN_OFF", "RSXM_DESK_YOLO", null
    };
    private static final String[] PATCH_NAMES = {
            "破甲 system_prompt", "权限白名单", "Guardian 禁用", "Full access 审批", "全局指令 AGENTS.md"
    };
    private static final String[] PATCH_DESCS = {
            "破甲内核注入 [agent] system_prompt（标记 RSXM_ARMOR_V2）",
            "[permissions] allow 白名单覆盖渗透/侦查/运维命令（标记 RSXM_PERM_ALLOW）",
            "guardian_model=\"\" 禁用高风险审查（标记 RSXM_GUARDIAN_OFF）",
            "[desktop] default_tool_approval_mode=\"danger-full-access\" 免审批（标记 RSXM_DESK_YOLO；"
                    + "与高级设置 YOLO 开关协同：开关写 wrapper 会话标记，本补丁写 config 新会话默认）",
            "全局指令 ~/.reasonix/AGENTS.md（与规则集联动，应用=启用规则）"
    };

    /** purge 面板当前内容容器（页签切换/操作刷新用） */
    private LinearLayout purgeContentBox;

    /* ==================== AI 破甲工具箱（AITEST8 融合，桌面版 v8.1.5 重构） ==================== */
    private static final String AITEST8_VER = "8.1.5";
    private static final String AITEST8_CHANNEL = "正式版";
    private static final String AITEST8_CONTACT = "@sifthost";
    private static final String AITEST8_GROUP = "https://t.me/+v5eOTuqSKVkwZWVl";
    /** 桌面版 IDE 插件清单（Windows 专属，Android 侧映射为破甲目标区块） */
    private static final String[][] AITEST8_IDES = {
            {"claude_code", "Claude Code", "Anthropic 编码代理"},
            {"codex", "Codex CLI", "OpenAI 编码代理"},
            {"gemini_cli", "Gemini CLI", "Google 编码代理"},
            {"claude_haha", "Claude Haha", "Claude 变体客户端"},
            {"cursor", "Cursor", "AI 编辑器"},
            {"trae", "Trae", "字节 AI IDE"},
            {"trae_cn", "Trae CN", "Trae 国内版"},
            {"codebuddy", "CodeBuddy", "腾讯 AI IDE"},
            {"qoder", "Qoder", "AI 编辑器"},
            {"workbuddy", "WorkBuddy", "通用工作代理"},
    };
    /** 本机技能包清单（装技页：勾选安装/卸载 → ~/.reasonix/skills/） */
    private static final String[][] AITEST8_SKILLS = {
            {"reverse-flow", "逆向全谱系技能包（94 文件；启动词「真心为你」）", "逆向反编译"},
            {"redteam", "红队 23 技能包（信息收集→权限维持）", "红队"},
            {"av-evasion", "免杀规避 18 章技能包", "检测规避"},
            {"decompile", "hacker-asm-decompile（反汇编/解混淆）", "逆向反编译"},
    };
    /** AITEST8 面板内容容器（页签切换/操作刷新用） */
    private LinearLayout aitest8ContentBox;
    private TextView aitest8LogView;
    /** 破甲指令编辑草稿（页面重建时保留，避免操作后输入内容丢失） */
    private String aitest8InstDraft = "";
    /** IDE 目标列表展开态（页面重建时保留） */
    private boolean aitest8IdeExpanded = false;
    /** 操作日志文本（页面重建时保留，避免操作结果丢失） */
    private String aitest8LogText = "";

    /** config.toml 宿主侧路径（rootfs/root/.reasonix/config.toml） */
    private File purgeConfFile() {
        return new File(new File(new File(getFilesDir(), "rootfs"), "root/.reasonix"), "config.toml");
    }

    private String purgeConfRead() {
        try {
            File f = purgeConfFile();
            return f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            Log.w(TAG, "purge conf read failed", e);
            return "";
        }
    }

    private void purgeConfWrite(String content) {
        try {
            File f = purgeConfFile();
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "purge conf written");
        } catch (Exception e) {
            Log.w(TAG, "purge conf write failed", e);
        }
    }

    /** 破甲提示词源：优先 rootfs 内用户编辑版（~/.reasonix/break-armor-prompt.md），
     *  其次 APK 内置资产（面板「保存」「恢复内置模板」就是写/覆盖这个文件） */
    private String readArmorPromptSource() {
        try {
            File f = new File(new File(new File(getFilesDir(), "rootfs"), "root/.reasonix"),
                    "break-armor-prompt.md");
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception e) {
            Log.w(TAG, "read rootfs armor prompt failed", e);
        }
        return readBreakArmorPrompt();
    }

    /** 补丁 0 应用：破甲提示词注入 [agent] system_prompt（与 ensureSandboxDisabled 的 v2 迁移一致） */
    private String injectArmorIntoAgent(String content) {
        String prompt = readArmorPromptSource();
        if (prompt.isEmpty()) return content;
        // 移除旧顶层破甲块与任何独立 system_prompt 块（避免重复键）
        content = content.replaceAll(
                "(?ms)^\\s*# rsxm-break-armor-v(?:0\\.5\\.0|0\\.3\\.0-gen4)\\s*\\n\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "");
        content = content.replaceAll("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "")
                .replaceAll("(?m)^\\s*system_prompt\\s*=\\s*\"[^\"]*\"\\s*", "");
        String block = "system_prompt = \"\"\"\n" + prompt
                + "\n\n--- 环境能力说明（RSXM 应用附加，按需使用，不要编造）：---\n"
                + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                + "\"\"\"\n";
        if (java.util.regex.Pattern.compile("(?m)^\\s*\\[agent\\]\\s*$").matcher(content).find()) {
            content = content.replaceFirst("(?m)^\\s*\\[agent\\]\\s*$",
                    java.util.regex.Matcher.quoteReplacement("[agent]\n" + block + "\n"));
        } else {
            content += "\n[agent]\n" + block + "\n";
        }
        content += "# RSXM_ARMOR_V2 (rsxm-break-armor in [agent])\n";
        return content;
    }

    /** 补丁 0 还原：移除 [agent] system_prompt 块与标记（段内无其它键时连段头删除） */
    private String stripArmorFromAgent(String content) {
        content = content.replaceAll("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "")
                .replaceAll("(?m)^\\s*system_prompt\\s*=\\s*\"[^\"]*\"\\s*", "");
        content = content.replaceAll("(?m)^\\s*# RSXM_ARMOR_V2.*$\\n?", "");
        content = content.replaceAll("(?ms)^\\s*\\[agent\\]\\s*\\n(?=\\s*\\[|\\s*$)", "");
        return content;
    }

    /** 补丁 1 应用：权限白名单注入（无 [permissions] 段时追加完整块） */
    private String injectPermBlock(String content) {
        boolean hasHead = java.util.regex.Pattern.compile("(?m)^\\s*\\[permissions\\]\\s*$")
                .matcher(content).find();
        if (!hasHead) {
            content += PERMISSIONS_RSXM_BLOCK;
        } else if (!content.contains("allow_dynamic_bash")) {
            content = content.replaceFirst("(?m)^\\s*\\[permissions\\]\\s*$",
                    "[permissions]\nallow_dynamic_bash = true\n");
        } else {
            content += "\n# RSXM_PERM_ALLOW (rsxm-pentest-bypass) user-allow kept\n";
        }
        content += "\n# RSXM_PERM_ALLOW (rsxm-pentest-bypass)\n";
        return content;
    }

    /** 补丁 1 还原：移除 [permissions] 段与标记（含用户自定义 allow，操作前有提示） */
    private String stripPermBlock(String content) {
        content = content.replaceAll("(?m)^\\s*# RSXM_PERM_ALLOW.*$\\n?", "");
        content = content.replaceAll("(?ms)^\\s*\\[permissions\\]\\s*\\n(?:.*?\\n)*?(?=\\s*\\[|\\s*$)", "");
        return content;
    }

    /** 补丁 2 应用：guardian_model=\"\" 插到第一个表头前 */
    private String injectGuardianOff(String content) {
        content = content.replaceAll("(?m)^\\s*guardian_model\\s*=.*$\\s*", "");
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("(?m)^(\\s*\\[[a-zA-Z_][^\\]]*\\]\\s*$)").matcher(content);
        if (tm.find()) {
            content = content.substring(0, tm.start())
                    + "# RSXM_GUARDIAN_OFF\nguardian_model = \"\"\n"
                    + content.substring(tm.start());
        } else {
            content += "\nguardian_model = \"\"\n";
        }
        content += "\n# RSXM_GUARDIAN_OFF\n";
        return content;
    }

    /** 补丁 2 还原：移除 guardian_model 键与标记 */
    private String stripGuardianOff(String content) {
        content = content.replaceAll("(?m)^\\s*guardian_model\\s*=.*$\\s*", "");
        content = content.replaceAll("(?m)^\\s*# RSXM_GUARDIAN_OFF.*$\\n?", "");
        return content;
    }

    /** 补丁 3 应用：desktop 默认审批 danger-full-access（reasonix 1.39.3 权限枚举，
     *  旧值 yolo/auto 已退役；Full access = 无 bwrap 时也允许非受限会话）。
     *  幂等：先清除旧键/旧标记再注入。 */
    private String injectYolo(String content) {
        content = stripYolo(content);
        if (java.util.regex.Pattern.compile("(?m)^\\s*\\[desktop\\]\\s*$").matcher(content).find()) {
            content = content.replaceFirst("(?m)^\\s*\\[desktop\\]\\s*$",
                    "[desktop]\ndefault_tool_approval_mode = \"danger-full-access\"");
        } else {
            content += "\n[desktop]\ndefault_tool_approval_mode = \"danger-full-access\"\n";
        }
        content += "\n# RSXM_DESK_YOLO\n";
        return content;
    }

    /** 补丁 3 还原：移除 yolo 键与标记（段空则删段头，保留 telemetry 等其它键） */
    private String stripYolo(String content) {
        content = content.replaceAll("(?m)^\\s*default_tool_approval_mode\\s*=.*$\\s*", "");
        content = content.replaceAll("(?m)^\\s*# RSXM_DESK_YOLO.*$\\n?", "");
        content = content.replaceAll("(?ms)^\\s*\\[desktop\\]\\s*\\n(?=\\s*\\[|\\s*$)", "");
        return content;
    }

    /** 全局指令文件（~/.reasonix/AGENTS.md）宿主侧路径 */
    private File purgeAgentsFile() {
        return new File(new File(new File(getFilesDir(), "rootfs"), "root/.reasonix"), "AGENTS.md");
    }

    /** 当前启用规则正文（无启用规则时用内置 rsxm-default 模板） */
    private String purgeActiveRuleText() {
        File rulesDir = new File(new File(new File(getFilesDir(), "rootfs"), "root/.reasonix"), "rules");
        File[] files = rulesDir.exists() ? rulesDir.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (n.endsWith(".json") || n.equals("state.json")) continue;
                if (n.endsWith(".md")) {
                    try {
                        String body = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                                StandardCharsets.UTF_8);
                        if (!body.trim().isEmpty()) return body;
                    } catch (Exception ignored) {}
                }
            }
        }
        // 回退内置模板（assets/purge/rules/rsxm-default.md，由 setup/refresh 部署到 rootfs）
        File tpl = new File(rulesDir, "rsxm-default.md");
        try {
            if (tpl.exists()) {
                return new String(java.nio.file.Files.readAllBytes(tpl.toPath()),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 补丁操作统一入口：应用 / 还原（宿主侧读写，不依赖 guest 内工具） */
    private String purgePatchApply(int id) {
        String conf = purgeConfRead();
        switch (id) {
            case PATCH_ARMOR:
                purgeConfWrite(injectArmorIntoAgent(conf));
                return "破甲 system_prompt 已写入 [agent]（重启环境后生效）";
            case PATCH_PERM:
                purgeConfWrite(injectPermBlock(conf));
                return "[permissions] 白名单已注入（重启环境后生效）";
            case PATCH_GUARDIAN:
                purgeConfWrite(injectGuardianOff(conf));
                return "guardian_model 已禁用（重启环境后生效）";
            case PATCH_YOLO:
                purgeConfWrite(injectYolo(conf));
                return "desktop YOLO 审批已写入（重启环境后生效）";
            case PATCH_AGENTS: {
                String text = purgeActiveRuleText();
                if (text.isEmpty()) return "无可用规则正文（先在规则集新建或恢复内置模板）";
                try {
                    File f = purgeAgentsFile();
                    f.getParentFile().mkdirs();
                    java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                    return "全局指令已写入 ~/.reasonix/AGENTS.md";
                } catch (Exception e) {
                    return "写入失败：" + e.getMessage();
                }
            }
            default:
                return "未知补丁";
        }
    }

    private String purgePatchRevert(int id) {
        switch (id) {
            case PATCH_ARMOR:
                purgeConfWrite(stripArmorFromAgent(purgeConfRead()));
                return "破甲 system_prompt 已移除（重启环境后生效）";
            case PATCH_PERM:
                purgeConfWrite(stripPermBlock(purgeConfRead()));
                return "[permissions] 段与白名单已移除（含自定义 allow，重启生效）";
            case PATCH_GUARDIAN:
                purgeConfWrite(stripGuardianOff(purgeConfRead()));
                return "guardian_model 键已移除（重启环境后生效）";
            case PATCH_YOLO:
                purgeConfWrite(stripYolo(purgeConfRead()));
                return "desktop YOLO 键已移除（重启环境后生效）";
            case PATCH_AGENTS: {
                File f = purgeAgentsFile();
                if (f.exists() && !f.delete()) return "AGENTS.md 删除失败";
                return "全局指令已移除";
            }
            default:
                return "未知补丁";
        }
    }

    /** 补丁状态：已应用 / 未应用（ARMOR 需标记 + 内容锚点双确认，防旧版浅内容误报已应用） */
    private boolean purgePatchApplied(int id) {
        if (id == PATCH_AGENTS) return purgeAgentsFile().exists();
        if (id == PATCH_ARMOR) {
            // 只查标记：自定义破甲指令（不含锚点）也视为已注入；
            // 锚点仅用于 ensureSandboxDisabled 的旧版迁移判断，不作为状态依据。
            return purgeConfRead().contains(PATCH_MARKERS[PATCH_ARMOR]);
        }
        return purgeConfRead().contains(PATCH_MARKERS[id]);
    }

    /* ---------- purge 主题控件（白 / 墨） ---------- */

    private boolean purgeLight() {
        return getSharedPreferences("prefs", MODE_PRIVATE).getBoolean(PURGE_THEME_KEY, false);
    }

    private int purgeFg()    { return purgeLight() ? 0xFF1C1B18 : 0xFFFFFFFF; }
    private int purgeMute()  { return purgeLight() ? 0xFF5A554C : 0xFFAAAAAA; }
    private int purgeBg()    { return purgeLight() ? 0xFFFAFAF7 : 0xFF0D0D0D; }
    private int purgeRowBg() { return purgeLight() ? 0xFFECEBE5 : 0xFF141414; }
    private int purgeBtnBg() { return purgeLight() ? 0xFFDCDBD4 : 0xFF262626; }

    private TextView purgeTip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(purgeMute());
        tv.setTextSize(12);
        tv.setLineSpacing(0, 1.3f);
        return tv;
    }

    private TextView purgeSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(purgeFg());
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    private Button purgeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(purgeFg());
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(purgeBtnBg()));
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    /** 页签按钮（选中=品牌色下划线风格：着色文字+边框底） */
    private Button purgeTabButton(String text, boolean on) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackgroundColor(0x00000000);
        b.setTextColor(on ? 0xFF6DBF8C : purgeMute());
        b.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return b;
    }

    /** 状态徽标：小圆点 + 文案 */
    private TextView purgeBadge(String text, boolean ok) {
        TextView tv = new TextView(this);
        tv.setText((ok ? "● " : "○ ") + text);
        tv.setTextColor(ok ? 0xFF4CAF50 : 0xFFFFB74D);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    /** 补丁行：名称 + 状态 + 描述 + 应用/还原按钮 */
    private LinearLayout purgePatchRow(final int id, final Runnable after) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(purgeRowBg());
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        row.setLayoutParams(rp);
        TextView t = new TextView(this);
        t.setText(PATCH_NAMES[id]);
        t.setTextColor(purgeFg());
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(t);
        final TextView badge = purgeBadge(purgePatchApplied(id) ? "已应用" : "未应用", purgePatchApplied(id));
        row.addView(badge);
        TextView d = new TextView(this);
        d.setText(PATCH_DESCS[id]);
        d.setTextColor(purgeMute());
        d.setTextSize(11);
        row.addView(d);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button apply = purgeButton("应用");
        Button revert = purgeButton("还原");
        btns.addView(apply, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        btns.addView(revert, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        apply.setOnClickListener(v -> {
            apply.setEnabled(false);
            runPurgeOp(() -> purgePatchApply(id), badge, apply, revert, after);
        });
        revert.setOnClickListener(v -> {
            revert.setEnabled(false);
            runPurgeOp(() -> purgePatchRevert(id), badge, apply, revert, after);
        });
        // 破甲补丁行：联动「破甲中心」（融合后的统一入口，含指令编辑/批量部署/资产）
        if (id == PATCH_ARMOR) {
            Button center = purgeButton("破甲中心");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = dp(4);
            btns.addView(center, clp);
            center.setOnClickListener(v -> {
                hidePanel();   // 关闭 purge 面板（触发 onClose 清理引用）后进入统一破甲中心
                showAITest8Dialog();
            });
        }
        row.addView(btns);
        return row;
    }

    /** 统一跑补丁操作：后台执行 → 主线程更新徽标/状态/重建列表 */
    private void runPurgeOp(final java.util.function.Supplier<String> op, final TextView badge,
                            final Button apply, final Button revert, final Runnable after) {
        new Thread(() -> {
            final String msg = op.get();
            runOnUiThread(() -> {
                if (badge != null) {
                    boolean on = purgePatchApplied(badge.getTag() instanceof Integer
                            ? (Integer) badge.getTag() : PATCH_ARMOR);
                    badge.setText((on ? "● " : "○ ") + (on ? "已应用" : "未应用"));
                    badge.setTextColor(on ? 0xFF4CAF50 : 0xFFFFB74D);
                }
                if (apply != null) apply.setEnabled(true);
                if (revert != null) revert.setEnabled(true);
                if (after != null) after.run();
                pushOutput("\r\n[dsh purge] " + msg + "\r\n");
            });
        }, "purge-op").start();
    }

    /** 清洗页：补丁分组 + 提示词 + 规则集 + Skill（映射 dsh-purge 清洗页四分区） */
    private void buildPurgeClean(LinearLayout box) {
        box.removeAllViews();
        // 补丁分组
        box.addView(purgeSection("补丁"));
        box.addView(purgeTip("RSXM 宿主策略补丁。应用后需重启环境才完全生效（与上游 dsh-purge「应用后必须重启」一致）。"));
        Runnable refreshAll = () -> rebuildPurgeClean();
        for (int i = 0; i < PATCH_NAMES.length; i++) {
            LinearLayout row = purgePatchRow(i, refreshAll);
            TextView badge = (TextView) row.getChildAt(1);
            badge.setTag(i);
            box.addView(row);
        }
        // 提示词
        box.addView(purgeSection("提示词"));
        box.addView(purgeTip("编辑 ~/.reasonix/break-armor-prompt.md（会话覆盖段）。「应用为 system_prompt」将其与破甲内核一起注入 [agent]。"));
        final EditText promptInput = new EditText(this);
        promptInput.setHint("break-armor-prompt.md 内容（导入/粘贴，含破甲内核）");
        promptInput.setTextColor(purgeFg());
        promptInput.setHintTextColor(purgeMute());
        promptInput.setTextSize(12);
        promptInput.setGravity(android.view.Gravity.TOP);
        promptInput.setSingleLine(false);
        promptInput.setMaxLines(Integer.MAX_VALUE);
        promptInput.setVerticalScrollBarEnabled(true);
        promptInput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        promptInput.setBackgroundColor(purgeRowBg());
        promptInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams promptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        promptLp.topMargin = dp(6);
        box.addView(promptInput, promptLp);
        new Thread(() -> {
            final String body = readArmorPromptSource();
            runOnUiThread(() -> promptInput.setText(body));
        }, "purge-prompt-load").start();
        LinearLayout pbtns = new LinearLayout(this);
        pbtns.setOrientation(LinearLayout.HORIZONTAL);
        Button saveP = purgeButton("保存到文件");
        Button injectP = purgeButton("应用为 system_prompt");
        Button restoreP = purgeButton("恢复内置模板");
        pbtns.addView(saveP, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pbtns.addView(injectP, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = dp(8);
        box.addView(pbtns, pbLp);
        addV(box, restoreP, 6);
        saveP.setOnClickListener(v -> {
            new Thread(() -> {
                try {
                    File f = new File(new File(new File(getFilesDir(), "rootfs"),
                            "root/.reasonix"), "break-armor-prompt.md");
                    f.getParentFile().mkdirs();
                    final String body = promptInput.getText().toString();
                    java.nio.file.Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> pushOutput("\r\n[dsh purge] 提示词已保存到 ~/.reasonix/break-armor-prompt.md\r\n"));
                } catch (Exception e) {
                    Log.w(TAG, "purge prompt save failed", e);
                }
            }, "purge-prompt-save").start();
        });
        injectP.setOnClickListener(v -> runPurgeOp(() -> purgePatchApply(PATCH_ARMOR),
                null, null, null, refreshAll));
        restoreP.setOnClickListener(v -> {
            try {
                // 恢复内置破甲内核最新载荷（infinite-gen-4.1-flash v0.4.0-hardened，
                // 与 canonical 逐字节一致），写入编辑区供保存/应用
                final String tpl = readAssetText("purge/prompts/infinite-gen-4.1-flash.md");
                runOnUiThread(() -> promptInput.setText(tpl));
            } catch (Exception e) {
                Log.w(TAG, "purge prompt restore failed", e);
            }
        });
        // 规则集
        box.addView(purgeSection("规则集"));
        box.addView(purgeTip("多套 AGENTS.md / CLAUDE.md 存放于 ~/.reasonix/rules/。启用即写入全局指令（对应上方「全局指令」补丁），删除从列表去掉。内置模板 rsxm-default 由 ~/.reasonix/purge/rules/ 提供副本，删除后仍可作为全局指令内容。"));
        final LinearLayout rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(rulesBox);
        refreshPurgeRules(rulesBox, refreshAll);
        // Skill
        box.addView(purgeSection("Skill"));
        box.addView(purgeTip("全局 skill（~/.reasonix/skills，跨项目可见）。完整安装/删除/启用请到「高级设置 → SKILL」。"));
        final LinearLayout skillBox = new LinearLayout(this);
        skillBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(skillBox);
        refreshPurgeSkills(skillBox);
    }

    /** 重建清洗页（补丁操作后刷新状态） */
    private void rebuildPurgeClean() {
        if (purgeContentBox == null) return;
        buildPurgeClean(purgeContentBox);
    }

    /** 规则集列表：~/.reasonix/rules/*.md（+meta .json 显示名称/目标），启用=写全局指令 */
    private void refreshPurgeRules(final LinearLayout box, final Runnable after) {
        box.removeAllViews();
        box.addView(purgeTip("加载规则集..."));
        new Thread(() -> {
            try {
                final List<File> md = new ArrayList<>();
                File rulesDir = new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "rules");
                if (rulesDir.exists()) {
                    File[] files = rulesDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.getName().endsWith(".md")) md.add(f);
                        }
                    }
                }
                java.util.Collections.sort(md, (a, b) -> a.getName().compareTo(b.getName()));
                runOnUiThread(() -> {
                    box.removeAllViews();
                    if (md.isEmpty()) {
                        box.addView(purgeTip("（无规则集。点下方「恢复内置模板」创建 rsxm-default）"));
                    }
                    for (final File f : md) {
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setBackgroundColor(purgeRowBg());
                        row.setPadding(dp(12), dp(6), dp(12), dp(6));
                        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rp.topMargin = dp(4);
                        row.setLayoutParams(rp);
                        TextView n = new TextView(this);
                        n.setText(f.getName().replace(".md", ""));
                        n.setTextColor(purgeFg());
                        n.setTextSize(13);
                        n.setTypeface(null, android.graphics.Typeface.BOLD);
                        row.addView(n);
                        TextView meta = new TextView(this);
                        boolean active = purgeAgentsFile().exists();
                        meta.setText("目标：AGENTS.md · " + (active ? "已启用（作为全局指令）" : "未启用"));
                        meta.setTextColor(purgeMute());
                        meta.setTextSize(11);
                        row.addView(meta);
                        LinearLayout btns = new LinearLayout(this);
                        btns.setOrientation(LinearLayout.HORIZONTAL);
                        Button enable = purgeButton("启用（写全局指令）");
                        Button del = purgeButton("删除");
                        btns.addView(enable, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        btns.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        enable.setOnClickListener(v -> {
                            new Thread(() -> {
                                try {
                                    File ag = purgeAgentsFile();
                                    ag.getParentFile().mkdirs();
                                    final String body = new String(java.nio.file.Files.readAllBytes(
                                            f.toPath()), StandardCharsets.UTF_8);
                                    java.nio.file.Files.write(ag.toPath(), body.getBytes(StandardCharsets.UTF_8));
                                    runOnUiThread(() -> pushOutput("\r\n[dsh purge] 已启用规则 "
                                            + f.getName() + "（写入 AGENTS.md）\r\n"));
                                    if (after != null) after.run();
                                } catch (Exception e) {
                                    Log.w(TAG, "purge rule enable failed", e);
                                }
                            }, "purge-rule-enable").start();
                        });
                        del.setOnClickListener(v -> {
                            if (f.delete()) {
                                pushOutput("\r\n[dsh purge] 已删除规则 " + f.getName() + "\r\n");
                                if (after != null) after.run();
                            }
                        });
                        row.addView(btns);
                        box.addView(row);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "purge rules load failed", e);
                runOnUiThread(() -> {
                    box.removeAllViews();
                    box.addView(purgeTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "purge-rules").start();
    }

    /** Skill 列表（只读展示 + 启用状态，管理走高级设置 SKILL 面板） */
    private void refreshPurgeSkills(final LinearLayout box) {
        box.removeAllViews();
        box.addView(purgeTip("加载 skill..."));
        new Thread(() -> {
            try {
                final List<String> names = new ArrayList<>();
                final java.util.Set<String> disabled = new java.util.HashSet<>();
                File[] dirs = globalSkillsDir().listFiles();
                if (dirs != null) {
                    for (File d : dirs) {
                        String n = d.getName();
                        if (d.isDirectory() && !n.startsWith(".")) names.add(n);
                    }
                }
                parseDisabled(executeInGuest(
                        "grep -A8 '\\[skills\\]' $HOME/.reasonix/config.toml 2>/dev/null | grep disabled_skills", 4),
                        disabled);
                runOnUiThread(() -> {
                    box.removeAllViews();
                    if (names.isEmpty()) {
                        box.addView(purgeTip("（无已安装全局 skill）"));
                        return;
                    }
                    for (final String name : names) {
                        TextView row = new TextView(this);
                        row.setText((disabled.contains(name) ? "○ 禁用  " : "● 启用  ") + name);
                        row.setTextColor(disabled.contains(name) ? purgeMute() : 0xFF4CAF50);
                        row.setTextSize(12);
                        row.setPadding(dp(4), dp(3), 0, dp(3));
                        box.addView(row);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "purge skills load failed", e);
                runOnUiThread(() -> {
                    box.removeAllViews();
                    box.addView(purgeTip("（加载失败：" + e.getMessage() + "）"));
                });
            }
        }, "purge-skills").start();
    }

    /** 读取 APK assets 文本（purge 面板模板/说明用） */
    private String readAssetText(String assetPath) throws java.io.IOException {
        try (InputStream in = getAssets().open(assetPath);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    /** dsh purge 主面板：页签（清洗 / 演练台）+ 白墨主题切换 + 关于 */
    private void showPurgeDialog() {
        final boolean light = purgeLight();
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(16));
        panel.setBackgroundColor(purgeBg());
        // 头：标题 + 版本 + 白/墨切换
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("dsh purge 清洗台");
        title.setTextColor(0xFFFF8A5C);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ver = new TextView(this);
        ver.setText("v" + PURGE_VER);
        ver.setTextColor(purgeMute());
        ver.setTextSize(11);
        head.addView(ver);
        Button theme = purgeButton(light ? "墨" : "白");
        theme.setOnClickListener(v -> {
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean(PURGE_THEME_KEY, !purgeLight()).apply();
            showPurgeDialog();   // 重建面板切换主题
        });
        head.addView(theme);
        panel.addView(head);
        // 页签栏
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final Button tabClean = purgeTabButton("清洗", true);
        final Button tabDrill = purgeTabButton("演练台", false);
        final Button tabRes = purgeTabButton("资源", false);
        tabs.addView(tabClean, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(tabDrill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(tabRes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(tabs);
        // 内容容器
        purgeContentBox = new LinearLayout(this);
        purgeContentBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(purgeContentBox);
        final Runnable setClean = () -> {
            tabClean.setTextColor(0xFF6DBF8C);
            tabClean.setTypeface(null, android.graphics.Typeface.BOLD);
            tabDrill.setTextColor(purgeMute());
            tabDrill.setTypeface(null, android.graphics.Typeface.NORMAL);
            tabRes.setTextColor(purgeMute());
            tabRes.setTypeface(null, android.graphics.Typeface.NORMAL);
        };
        final Runnable setDrill = () -> {
            tabDrill.setTextColor(0xFF6DBF8C);
            tabDrill.setTypeface(null, android.graphics.Typeface.BOLD);
            tabClean.setTextColor(purgeMute());
            tabClean.setTypeface(null, android.graphics.Typeface.NORMAL);
            tabRes.setTextColor(purgeMute());
            tabRes.setTypeface(null, android.graphics.Typeface.NORMAL);
        };
        final Runnable setRes = () -> {
            tabRes.setTextColor(0xFF6DBF8C);
            tabRes.setTypeface(null, android.graphics.Typeface.BOLD);
            tabClean.setTextColor(purgeMute());
            tabClean.setTypeface(null, android.graphics.Typeface.NORMAL);
            tabDrill.setTextColor(purgeMute());
            tabDrill.setTypeface(null, android.graphics.Typeface.NORMAL);
        };
        tabClean.setOnClickListener(v -> { setClean.run(); buildPurgeClean(purgeContentBox); });
        tabDrill.setOnClickListener(v -> { setDrill.run(); buildPurgeDrill(purgeContentBox); });
        tabRes.setOnClickListener(v -> { setRes.run(); buildPurgeResources(purgeContentBox); });
        buildPurgeClean(purgeContentBox);   // 默认进清洗页
        showPanel("dsh purge", panel, () -> purgeContentBox = null);
    }

    /** 深色代码结果区（purge 主题版，同 createDarkResult 但跟随白/墨） */
    private TextView purgeResult() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFF7FDB8A);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setBackgroundColor(purgeRowBg());
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setMinHeight(dp(48));
        tv.setMaxLines(6);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /* ==================== AI 破甲工具箱（AITEST8 融合面板） ====================
     * 源自 Windows 桌面版 AITEST8.0 v8.1.5「AI 破甲工具箱 / AI-Pojia-Toolbox」
     * （PySide6，作者 dengbo-hui）：桌面 Qt UI 无法在 Android 直接运行，按「UI 路由
     * 融合」原则在 RSXM 原生面板重建——功能区/主题/文案对齐桌面版，能力与内置
     * 破甲环境（config 注入 + skills 部署）打通。桌面版自有能力（Windows IDE 插件
     * .pyd、gitee 共享池、作者激活 API）在本侧映射为区块入口/说明/本地配置。
     */
    private void showAITest8Dialog() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(16));
        panel.setBackgroundColor(0xFF050908);
        // 头：标题 + 版本 + 主题色
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("AI 破甲工具箱");
        title.setTextColor(0xFF36F29A);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ver = new TextView(this);
        ver.setText("v" + AITEST8_VER + " · " + AITEST8_CHANNEL);
        ver.setTextColor(0xFF6E7681);
        ver.setTextSize(11);
        head.addView(ver);
        panel.addView(head);
        // 页签栏（破甲中心 = 聚合三处破甲 UI：purge 破甲补丁 / purge 提示词 / AITEST8 批量破甲）
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final Button tabDash = aitest8Tab("破甲中心", true);
        final Button tabSkills = aitest8Tab("装技", false);
        final Button tabPool = aitest8Tab("共享池", false);
        final Button tabAct = aitest8Tab("激活", false);
        tabs.addView(tabDash, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(tabSkills, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(tabPool, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(tabAct, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(tabs);
        aitest8ContentBox = new LinearLayout(this);
        aitest8ContentBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(aitest8ContentBox);
        final java.util.function.Consumer<Button> sel = (Button on) -> {
            for (Button b : new Button[]{tabDash, tabSkills, tabPool, tabAct}) {
                b.setTextColor(b == on ? 0xFF36F29A : 0xFF6E7681);
                b.setTypeface(null, b == on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        };
        tabDash.setOnClickListener(v -> { sel.accept(tabDash); buildAITest8Dash(aitest8ContentBox); });
        tabSkills.setOnClickListener(v -> { sel.accept(tabSkills); buildAITest8Skills(aitest8ContentBox); });
        tabPool.setOnClickListener(v -> { sel.accept(tabPool); buildAITest8Pool(aitest8ContentBox); });
        tabAct.setOnClickListener(v -> { sel.accept(tabAct); buildAITest8Activation(aitest8ContentBox); });
        buildAITest8Dash(aitest8ContentBox);
        showPanel("AI 破甲工具箱", panel, () -> aitest8ContentBox = null);
    }

    /** AITEST8 页签按钮（选中=品牌绿） */
    private Button aitest8Tab(String text, boolean on) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackgroundColor(0x00000000);
        b.setTextColor(on ? 0xFF36F29A : 0xFF6E7681);
        b.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return b;
    }

    /** AITEST8 强调按钮（品牌绿底/暗底） */
    private Button aitest8Button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFE6EDF3);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                primary ? 0xFF0F6E49 : 0xFF0C1B16));
        return b;
    }

    /** AITEST8 说明行（暗色小字） */
    private TextView aitest8Tip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF9BA6B4);
        tv.setTextSize(12);
        tv.setLineSpacing(0, 1.3f);
        return tv;
    }

    /** AITEST8 区块标题（品牌绿加粗） */
    private TextView aitest8Section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF7FFFC1);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    /** 追加一行到面板日志（主线程安全；文本持久化，重建页面后仍可见） */
    private void aitest8Log(String line) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        final String entry = "[" + ts + "] " + line;
        runOnUiThread(() -> {
            aitest8LogText = entry + "\n" + aitest8LogText;
            if (aitest8LogText.length() > 6000) {
                aitest8LogText = aitest8LogText.substring(0, 6000);
            }
            if (aitest8LogView != null) aitest8LogView.setText(aitest8LogText);
        });
    }

    /** 破甲中心页（融合三处破甲 UI：purge 补丁[破甲 system_prompt] / purge 提示词页签 /
     *  AITEST8 批量破甲）：内核状态 + 一键部署/还原 + 指令编辑（自定义真正生效）+
     *  IDE 目标 + 关联资产 + 日志 */
    private void buildAITest8Dash(LinearLayout box) {
        box.removeAllViews();
        // ── 破甲内核状态 ──
        box.addView(aitest8Section("破甲内核"));
        final TextView kernel = new TextView(this);
        kernel.setTextColor(0xFF8B949E);
        kernel.setTextSize(12);
        box.addView(kernel);
        new Thread(() -> {
            final boolean on = purgePatchApplied(PATCH_ARMOR);
            final String tip = on ? "已注入 [agent] system_prompt（标记 RSXM_ARMOR_V2）" : "未注入";
            runOnUiThread(() -> {
                kernel.setText("● " + tip);
                kernel.setTextColor(on ? 0xFF3FB950 : 0xFFF85149);
            });
        }, "aitest8-kernel").start();
        // ── 主操作：部署 / 还原 / 重启环境生效 ──
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button deployBtn = aitest8Button("一键部署破甲", true);
        final Button revertBtn = aitest8Button("还原破甲", false);
        final Button restartBtn = aitest8Button("重启环境生效", false);
        opRow.addView(deployBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams ovl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ovl.leftMargin = dp(4);
        opRow.addView(revertBtn, ovl);
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rsl.leftMargin = dp(4);
        opRow.addView(restartBtn, rsl);
        box.addView(opRow);
        deployBtn.setOnClickListener(v -> {
            aitest8Busy(deployBtn, true, "部署中…");
            aitest8Log("一键部署破甲：注入 [agent] system_prompt + 全局技能 + 资产…");
            new Thread(() -> {
                try {
                    File rootfs = new File(getFilesDir(), "rootfs");
                    deployAITest8Assets(rootfs);        // reverse-flow 技能包 + 教程
                    ensureSandboxDisabled(rootfs);      // config 注入 + skills + 锚点自愈
                    aitest8Log("✓ 破甲部署完成（锚点 escape-clause free kernel 已写入）");
                    aitest8Log("提示：点击「重启环境生效」或重启 app 后完全生效");
                    showToast("破甲部署完成");
                    runOnUiThread(() -> { aitest8Busy(deployBtn, false, "一键部署破甲"); rebuildAITest8Dash(box); });
                } catch (Exception e) {
                    aitest8Log("✗ 破甲部署失败: " + e.getMessage());
                    runOnUiThread(() -> aitest8Busy(deployBtn, false, "一键部署破甲"));
                }
            }, "aitest8-deploy").start();
        });
        revertBtn.setOnClickListener(v -> {
            aitest8Busy(revertBtn, true, "还原中…");
            aitest8Log("还原破甲：移除 [agent] system_prompt 块…");
            new Thread(() -> {
                String msg = purgePatchRevert(PATCH_ARMOR);
                aitest8Log("✓ " + msg);
                showToast(msg);
                runOnUiThread(() -> { aitest8Busy(revertBtn, false, "还原破甲"); rebuildAITest8Dash(box); });
            }, "aitest8-revert").start();
        });
        restartBtn.setOnClickListener(v -> {
            aitest8Busy(restartBtn, true, "重启中…");
            aitest8Log("正在重启 reasonix 环境（约 10-30 秒完成）…");
            restartEnvironment();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> aitest8Busy(restartBtn, false, "重启环境生效"), 1500);
        });
        // ── 操作日志（紧跟操作区：反馈立即可见）──
        box.addView(aitest8Section("操作日志"));
        aitest8LogView = new TextView(this);
        aitest8LogView.setTextColor(0xFF7FFFC1);
        aitest8LogView.setTextSize(11);
        aitest8LogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        aitest8LogView.setBackgroundColor(0xFF07110E);
        aitest8LogView.setPadding(dp(10), dp(8), dp(10), dp(8));
        aitest8LogView.setMinHeight(dp(72));
        aitest8LogView.setMaxLines(8);
        if (!aitest8LogText.isEmpty()) aitest8LogView.setText(aitest8LogText);
        box.addView(aitest8LogView);
        aitest8Log("破甲中心就绪 · 三处破甲 UI 已融合");
        // ── 破甲指令（草稿保留：重建不丢输入）──
        box.addView(aitest8Section("破甲指令"));
        box.addView(aitest8Tip("编辑破甲提示词（含内核）。「保存并应用」写入 break-armor-prompt.md 并注入 [agent]"
                + " system_prompt（自定义内容生效）；「恢复内置模板」回到 infinite-gen-4 v0.4.0-hardened 默认。"));
        final EditText inst = new EditText(this);
        inst.setSingleLine(false);
        inst.setMinLines(4);
        inst.setTextSize(12);
        inst.setTextColor(0xFFE6EDF3);
        inst.setHintTextColor(0xFF6E7681);
        inst.setBackgroundColor(0xFF07110E);
        inst.setGravity(android.view.Gravity.TOP);
        inst.setVerticalScrollBarEnabled(true);
        inst.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        if (aitest8InstDraft == null || aitest8InstDraft.isEmpty()) {
            inst.setHint("编辑区留空 = 使用内置默认；输入自定义内容后点「保存并应用」生效");
        } else {
            inst.setText(aitest8InstDraft);
        }
        inst.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                aitest8InstDraft = e.toString();
            }
        });
        box.addView(inst, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        LinearLayout instRow = new LinearLayout(this);
        instRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button saveApply = aitest8Button("保存并应用", true);
        Button restoreInst = aitest8Button("恢复内置模板", false);
        instRow.addView(saveApply, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams ril = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ril.leftMargin = dp(4);
        instRow.addView(restoreInst, ril);
        box.addView(instRow);
        saveApply.setOnClickListener(v -> {
            aitest8Busy(saveApply, true, "应用中…");
            final String txt = inst.getText().toString();
            new Thread(() -> {
                try {
                    File f = new File(new File(new File(getFilesDir(), "rootfs"),
                            "root/.reasonix"), "break-armor-prompt.md");
                    f.getParentFile().mkdirs();
                    java.nio.file.Files.write(f.toPath(), txt.getBytes(StandardCharsets.UTF_8));
                    String msg = purgePatchApply(PATCH_ARMOR);   // 自定义内容真正注入 [agent]
                    aitest8Log("✓ " + msg);
                    showToast(txt.trim().isEmpty() ? "已应用内置默认" : "自定义指令已保存并应用");
                } catch (Exception e) {
                    aitest8Log("✗ 保存失败: " + e.getMessage());
                }
                runOnUiThread(() -> { aitest8Busy(saveApply, false, "保存并应用"); rebuildAITest8Dash(box); });
            }, "aitest8-inst-save").start();
        });
        restoreInst.setOnClickListener(v -> {
            try {
                final String tpl = readAssetText("purge/prompts/infinite-gen-4.1-flash.md");
                runOnUiThread(() -> inst.setText(tpl));
                aitest8Log("已恢复内置破甲模板（点「保存并应用」生效）");
            } catch (Exception e) {
                aitest8Log("✗ 恢复模板失败: " + e.getMessage());
            }
        });
        // ── IDE 破甲目标（展开态保留）──
        box.addView(aitest8Section("IDE 破甲目标（" + AITEST8_IDES.length + "）"));
        box.addView(aitest8Tip("桌面版插件为 Windows 专属（.pyd），本侧映射为破甲目标区块——"
                + "一键部署即对所有目标生效。"));
        final LinearLayout ideBox = new LinearLayout(this);
        ideBox.setOrientation(LinearLayout.VERTICAL);
        final TextView ideFold = new TextView(this);
        ideFold.setText(aitest8IdeExpanded ? "▾ 收起 IDE 目标列表"
                : "▸ 展开 " + AITEST8_IDES.length + " 个 IDE 目标列表");
        ideFold.setTextColor(0xFF36F29A);
        ideFold.setTextSize(12);
        ideFold.setPadding(dp(4), dp(4), 0, dp(4));
        ideFold.setOnClickListener(v -> {
            boolean show = ideBox.getChildCount() == 0;
            aitest8IdeExpanded = show;
            ideFold.setText(show ? "▾ 收起 IDE 目标列表" : "▸ 展开 " + AITEST8_IDES.length + " 个 IDE 目标列表");
            if (show) {
                for (String[] ide : AITEST8_IDES) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(10), dp(6), dp(10), dp(6));
                    row.setBackgroundColor(0xFF0B1713);
                    LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    irlp.topMargin = dp(3);
                    row.setLayoutParams(irlp);
                    TextView name = new TextView(this);
                    name.setText(ide[1]);
                    name.setTextColor(0xFFE6EDF3);
                    name.setTextSize(13);
                    name.setTypeface(null, android.graphics.Typeface.BOLD);
                    row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    TextView desc = new TextView(this);
                    desc.setText(ide[2]);
                    desc.setTextColor(0xFF6E7681);
                    desc.setTextSize(11);
                    row.addView(desc);
                    ideBox.addView(row);
                }
            } else {
                ideBox.removeAllViews();
            }
        });
        box.addView(ideFold);
        box.addView(ideBox);
        if (aitest8IdeExpanded) ideFold.performClick();
        // ── 关联资产（技能包部署状态 + 一键部署）──
        box.addView(aitest8Section("关联资产"));
        final TextView assetState = new TextView(this);
        assetState.setTextColor(0xFF8B949E);
        assetState.setTextSize(12);
        box.addView(assetState);
        new Thread(() -> {
            File skillsDir = new File(new File(getFilesDir(), "rootfs/root/.reasonix"), "skills");
            StringBuilder sb = new StringBuilder();
            for (String[] sk : AITEST8_SKILLS) {
                File d = new File(skillsDir, sk[0]);
                sb.append(sk[0]).append(d.isDirectory() ? " ●" : " ○").append("  ");
            }
            final String s = sb.toString().trim();
            runOnUiThread(() -> assetState.setText(s));
        }, "aitest8-assets").start();
        final Button assetBtn = aitest8Button("部署关联技能包", false);
        assetBtn.setOnClickListener(v -> {
            aitest8Busy(assetBtn, true, "部署中…");
            new Thread(() -> {
                File skillsDir = new File(new File(getFilesDir(), "rootfs/root/.reasonix"), "skills");
                skillsDir.mkdirs();
                for (String[] sk : AITEST8_SKILLS) {
                    File dest = new File(skillsDir, sk[0]);
                    if (dest.isDirectory()) continue;
                    if ("reverse-flow".equals(sk[0])) {
                        try {
                            extractAssetTree("aitest8/reverse_flow_skill", dest);
                            aitest8Log("✓ 已部署 reverse-flow（94 文件）");
                        } catch (Exception e) {
                            aitest8Log("✗ 部署 reverse-flow 失败: " + e.getMessage());
                        }
                    } else {
                        deployAITest8SkillGroup(sk[0], dest);
                    }
                }
                aitest8Log("✓ 关联技能包部署完成（重启 reasonix 后完全生效）");
                runOnUiThread(() -> { aitest8Busy(assetBtn, false, "部署关联技能包"); rebuildAITest8Dash(box); });
            }, "aitest8-assets-deploy").start();
        });
        box.addView(assetBtn);
    }

    /** 破甲中心重建（保留草稿/展开态/日志；回到页面顶部） */
    private void rebuildAITest8Dash(LinearLayout box) {
        buildAITest8Dash(box);
        // 回到页面顶部（滚动容器在 activity_main.xml 中包裹 panel_content）
        try {
            android.view.ViewParent parent = findViewById(R.id.panel_content).getParent();
            if (parent instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) parent).smoothScrollTo(0, 0);
            }
        } catch (Exception ignored) {}
    }

    /** 操作按钮执行态：禁用 + 文案切换（执行中防重复点击） */
    private void aitest8Busy(Button b, boolean busy, String text) {
        if (b == null) return;
        b.setEnabled(!busy);
        b.setText(text);
    }

    private void buildAITest8Skills(LinearLayout box) {
        box.removeAllViews();
        box.addView(aitest8Section("技能库"));
        box.addView(aitest8Tip("勾选安装，取消勾选卸载；部署到 ~/.reasonix/skills/ 后 reasonix 自动装载。"
                + "reverse-flow 为桌面版内置技能包（94 文件，启动词「真心为你」）。"));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        final TextView state = new TextView(this);
        state.setTextColor(0xFF8B949E);
        state.setTextSize(12);
        box.addView(state);
        final java.util.Map<String, Boolean> checks = new java.util.HashMap<>();
        new Thread(() -> {
            File skillsDir = new File(new File(getFilesDir(), "rootfs/root/.reasonix"), "skills");
            java.util.Set<String> installed = new java.util.HashSet<>();
            File[] dirs = skillsDir.listFiles(File::isDirectory);
            if (dirs != null) for (File d : dirs) installed.add(d.getName());
            runOnUiThread(() -> {
                list.removeAllViews();
                for (String[] sk : AITEST8_SKILLS) {
                    String dirName = sk[0];
                    boolean has = installed.contains(dirName);
                    checks.put(dirName, has);
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(10), dp(6), dp(10), dp(6));
                    card.setBackgroundColor(0xFF0B1713);
                    card.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    final android.widget.CheckBox cb = new android.widget.CheckBox(this);
                    cb.setChecked(has);
                    cb.setText(sk[1] + (has ? "　● 已安装" : "　○ 未安装"));
                    cb.setTextColor(has ? 0xFF7FFFC1 : 0xFF8B949E);
                    cb.setTextSize(12);
                    cb.setOnCheckedChangeListener((b, checked) -> checks.put(dirName, checked));
                    card.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    TextView cat = new TextView(this);
                    cat.setText(sk[2]);
                    cat.setTextColor(0xFF36F29A);
                    cat.setTextSize(11);
                    cat.setPadding(dp(8), 0, 0, 0);
                    card.addView(cat);
                    list.addView(card);
                }
                state.setText("已选 " + 0 + " / 共 " + AITEST8_SKILLS.length
                        + "（勾选后点「应用变更」生效）");
            });
        }, "aitest8-skills-scan").start();
        Button apply = aitest8Button("应用变更", true);
        apply.setOnClickListener(v -> {
            new Thread(() -> {
                File skillsDir = new File(new File(getFilesDir(), "rootfs/root/.reasonix"), "skills");
                skillsDir.mkdirs();
                for (String[] sk : AITEST8_SKILLS) {
                    File dest = new File(skillsDir, sk[0]);
                    boolean want = Boolean.TRUE.equals(checks.get(sk[0]));
                    if (want && !dest.isDirectory()) {
                        if ("reverse-flow".equals(sk[0])) {
                            try {
                                extractAssetTree("aitest8/reverse_flow_skill", dest);
                                aitest8Log("已装载技能 reverse-flow（94 文件）");
                            } catch (Exception e) {
                                aitest8Log("装载 reverse-flow 失败: " + e.getMessage());
                            }
                        } else {
                            deployAITest8SkillGroup(sk[0], dest);
                        }
                    } else if (!want && dest.isDirectory()) {
                        deleteRecursive(dest);
                        aitest8Log("已移除技能 " + sk[0]);
                    }
                }
                aitest8Log("技能变更已应用，重启 reasonix 后完全生效");
                runOnUiThread(() -> buildAITest8Skills(aitest8ContentBox));
            }, "aitest8-skills-apply").start();
        });
        box.addView(apply);
    }

    /** 部署 purge 自带技能组（redteam/av-evasion/decompile）到 skills 目录 */
    private void deployAITest8SkillGroup(String group, File dest) {
        try {
            String prefix = "purge/skills/" + group;
            android.content.res.AssetManager am = getAssets();
            String[] top = am.list(prefix);
            if (top == null || top.length == 0) return;
            dest.mkdirs();
            for (String t : top) {
                String ap = prefix + "/" + t;
                if (t.contains(".")) {
                    extractAsset(ap, new File(dest, t));
                } else {
                    extractAssetTree(ap, new File(dest, t));
                }
            }
            aitest8Log("已装载技能 " + group);
        } catch (Exception e) {
            aitest8Log("装载 " + group + " 失败: " + e.getMessage());
        }
    }

    /** 共享池页：社区共享技能（gitee 上游仓库） */
    private void buildAITest8Pool(LinearLayout box) {
        box.removeAllViews();
        box.addView(aitest8Section("共享技能池"));
        box.addView(aitest8Tip("取之于社区，用之于社区：浏览/下载共享技能（桌面版经 gitee 仓库 "
                + "dengbo-hui/ai-armor-piercing-toolbox 的 shared_skills/ 与 manifest.json 同步，"
                + "含自动审核：frontmatter/结构/安全扫描/跨 IDE 兼容/配额/重复检测）。\n"
                + "桌面版插件为 Windows 专属，共享池同步在此侧保留入口：配置 GITEE_TOKEN "
                + "（git config --global credential.helper 或环境变量）后即可拉取共享技能清单。"));
        final TextView out = new TextView(this);
        out.setTextColor(0xFF7FFFC1);
        out.setTextSize(11);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setBackgroundColor(0xFF07110E);
        out.setPadding(dp(10), dp(8), dp(10), dp(8));
        out.setMinHeight(dp(64));
        box.addView(out);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button check = aitest8Button("刷新列表", false);
        check.setOnClickListener(v -> {
            out.setText("正在读取 gitee 共享池清单…");
            new Thread(() -> {
                String r = executeInGuest("curl -sS -m 12 "
                        + "'https://gitee.com/api/v5/repos/dengbo-hui/ai-armor-piercing-toolbox/contents/shared_skills'"
                        + " | head -c 1200", 16);
                String msg = (r == null || r.trim().isEmpty() || r.contains("404"))
                        ? "共享池不可读（网络受限或仓库路径变更）：" + (r == null ? "无输出" : r.substring(0, Math.min(r.length(), 200)))
                        : "gitee API 返回：\n" + (r.length() > 800 ? r.substring(0, 800) + "…" : r);
                runOnUiThread(() -> out.setText(msg));
            }, "aitest8-pool").start();
        });
        Button share = aitest8Button("分享说明", false);
        share.setOnClickListener(v -> out.setText("分享流程（桌面版）：自动审核（实时回显）→ ① 自动修正"
                + "（补 author/license / 命名冲突重命名 / 跨 IDE 换行归一 / 绝对路径转相对路径；"
                + "内容/安全类问题不自动改）→ ② 提交到共享池（gitee，单文件 1MB 上限）。\n"
                + "本融合版保留入口与规则说明，实际发布请在桌面版或 gitee 仓库完成。"));
        row.addView(check, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sl.leftMargin = dp(4);
        row.addView(share, sl);
        box.addView(row);
    }

    /** 激活页：设备码 + 注册码（作者签发）+ 关于/客服 */
    private void buildAITest8Activation(LinearLayout box) {
        box.removeAllViews();
        final TextView status = new TextView(this);
        status.setTextSize(13);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(status);
        refreshAITest8AuthStatus(status);
        box.addView(aitest8Section("软件激活 · 永久授权"));
        box.addView(aitest8Tip("桌面版校验链：设备 Ed25519 密钥对 + 服务端短租约（POST /v1/access，"
                + "LEASE_PUBLIC_KEY 验签） + AT8 注册码在线兑换（PUBLIC_KEY 验签） + 风险停用闸。"
                + "zip 破解版已在 subscription.py 末尾追加 local patch：同名函数覆盖 status/activate/"
                + "refresh/check_token，恒返回 authorized / mode=lifetime（本机授权：永久）。"
                + "本融合版注入等效永久授权，无需注册码、无需联网、无到期/吊销。"));
        // 设备码（展示 + 复制）
        final String deviceCode = aitest8DeviceCode();
        TextView devLabel = new TextView(this);
        devLabel.setText("本机设备码");
        devLabel.setTextColor(0xFFE6EDF3);
        devLabel.setTextSize(12);
        box.addView(devLabel);
        LinearLayout devRow = new LinearLayout(this);
        devRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText dev = new EditText(this);
        dev.setText(deviceCode);
        dev.setEnabled(false);
        dev.setTextSize(12);
        dev.setTextColor(0xFFE6EDF3);
        dev.setBackgroundColor(0xFF07110E);
        devRow.addView(dev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button copy = aitest8Button("复制设备码", false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("device", deviceCode));
            showToast("设备码已复制到剪贴板");
        });
        LinearLayout.LayoutParams cml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cml.leftMargin = dp(4);
        devRow.addView(copy, cml);
        box.addView(devRow);
        // 注册码（已破解：可选展示，不再要求）
        TextView codeLabel = new TextView(this);
        codeLabel.setText("注册码（已破解，无需填写）");
        codeLabel.setTextColor(0xFF6E7681);
        codeLabel.setTextSize(12);
        box.addView(codeLabel);
        final EditText code = new EditText(this);
        code.setSingleLine(false);
        code.setMinLines(2);
        code.setTextSize(12);
        code.setTextColor(0xFFE6EDF3);
        code.setBackgroundColor(0xFF07110E);
        code.setHint("可选：粘贴桌面版 AT8 注册码留档（不影响授权）");
        box.addView(code);
        // 授权操作：注入永久授权 / 还原
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        Button inject = aitest8Button("注入永久授权", true);
        Button reset = aitest8Button("还原为未授权", false);
        actRow.addView(inject, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rtl.leftMargin = dp(4);
        actRow.addView(reset, rtl);
        box.addView(actRow);
        inject.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            try {
                File f = new File(new File(getFilesDir(), "rootfs/root/.reasonix/aitest8"), "license.json");
                f.getParentFile().mkdirs();
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("device", deviceCode);
                o.put("code", c);
                o.put("patched", true);
                o.put("mode", "lifetime");
                o.put("authorized", true);
                o.put("activated_at", System.currentTimeMillis() / 1000);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                    fo.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                aitest8Log("✓ 已注入永久授权（mode=lifetime，local patch 等效）");
                showToast("本机授权：永久");
                refreshAITest8AuthStatus(status);
            } catch (Exception e) {
                showToast("注入失败: " + e.getMessage());
            }
        });
        reset.setOnClickListener(v -> {
            try {
                File f = new File(new File(getFilesDir(), "rootfs/root/.reasonix/aitest8"), "license.json");
                if (f.exists() && !f.delete()) {
                    showToast("删除失败");
                    return;
                }
                aitest8Log("已还原为未授权状态");
                refreshAITest8AuthStatus(status);
            } catch (Exception e) {
                showToast("还原失败: " + e.getMessage());
            }
        });
        // 客服/群
        LinearLayout contact = new LinearLayout(this);
        contact.setOrientation(LinearLayout.HORIZONTAL);
        Button support = aitest8Button("联系客服 " + AITEST8_CONTACT, false);
        support.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/sifthost")));
            } catch (Exception ignored) {}
        });
        Button group = aitest8Button("加入 Telegram 群", false);
        group.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(AITEST8_GROUP)));
            } catch (Exception ignored) {}
        });
        contact.addView(support, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        gl.leftMargin = dp(4);
        contact.addView(group, gl);
        box.addView(contact);
        // 关于（含授权破解机制说明）
        Button about = aitest8Button("关于 · 破解说明", false);
        about.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = getAssets().open("aitest8/about.md")) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            new android.app.AlertDialog.Builder(this)
                    .setTitle("关于 — AI 破甲工具箱（破解授权）")
                    .setMessage(sb.length() == 0 ? "AITEST8.0 · AI 破甲工具箱（RSXM 融合版 · 永久授权）" : sb.toString())
                    .setPositiveButton("关闭", null)
                    .show();
        });
        box.addView(about);
    }

    /** 设备码：Android ID / 序列号 hash（稳定、可复制） */
    private String aitest8DeviceCode() {
        String id = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (id == null) id = "RSXM";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((id + "::aitest8").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("AT8-");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02X", h[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "AT8-" + id;
        }
    }

    /** 刷新授权状态行（guest ~/.reasonix/aitest8/license.json；patched=true → 永久授权） */
    private void refreshAITest8AuthStatus(final TextView status) {
        new Thread(() -> {
            String txt = "本机未授权 · 点击「注入永久授权」完成破解";
            int color = 0xFFF85149;
            try {
                File f = new File(new File(getFilesDir(), "rootfs/root/.reasonix/aitest8"), "license.json");
                if (f.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    org.json.JSONObject o = new org.json.JSONObject(s);
                    boolean patched = o.optBoolean("patched", false);
                    String mode = o.optString("mode", "");
                    long at = o.optLong("activated_at", 0);
                    if (patched) {
                        txt = "本机授权：永久 · lifetime（local patch 等效，无到期/吊销）"
                                + (at > 0 ? " · 注入时间 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                                java.util.Locale.ROOT).format(new java.util.Date(at * 1000)) : "");
                        color = 0xFF3FB950;
                    } else {
                        txt = "本机未授权（patched 缺失）";
                    }
                }
            } catch (Exception ignored) {}
            final String t = txt;
            final int c = color;
            runOnUiThread(() -> {
                status.setText("● " + t);
                status.setTextColor(c);
            });
        }, "aitest8-auth").start();
    }

    /** 递归解压 assets 目录树到 dest（保持目录结构；目录/文件以可 open 判断） */
    private void extractAssetTree(String assetDir, File dest) throws IOException {
        String[] children = getAssets().list(assetDir);
        if (children == null) return;
        dest.mkdirs();
        for (String child : children) {
            String ap = assetDir + "/" + child;
            boolean isFile;
            try (InputStream probe = getAssets().open(ap)) {
                isFile = true;
            } catch (IOException dirLike) {
                isFile = false;
            }
            if (isFile) {
                extractAsset(ap, new File(dest, child));
            } else {
                extractAssetTree(ap, new File(dest, child));
            }
        }
    }

    /** AITEST8 资产部署（技能包 / 教程 / 关于）：~/.reasonix/ 下（幂等） */
    private void deployAITest8Assets(File rootfs) {
        try {
            File rx = new File(rootfs, "root/.reasonix");
            File skillsDir = new File(rx, "skills/reverse-flow");
            if (!skillsDir.exists()) {
                extractAssetTree("aitest8/reverse_flow_skill", skillsDir);
                Log.d(TAG, "AITEST8 reverse-flow skill deployed");
            }
            File a8 = new File(rx, "aitest8");
            extractAsset("aitest8/tutorial.html", new File(a8, "tutorial.html"));
            extractAsset("aitest8/about.md", new File(a8, "about.md"));
        } catch (Exception e) {
            Log.w(TAG, "AITEST8 asset deploy failed", e);
        }
    }

    /** 演练台页：未授权 → 声明 + 三项勾选；已授权 → 资产 / 技能 / 环境（只读巡检） */
    private void buildPurgeDrill(LinearLayout box) {
        box.removeAllViews();
        final boolean ok = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean(PURGE_DRILL_OK, false);
        if (!ok) {
            box.addView(purgeSection("演练台授权"));
            box.addView(purgeTip("演练台只用于你有权管理的本机、离线靶标，或已书面授权的演练环境。"
                    + "未经授权的渗透、攻击、窃取和破坏一律禁止。首次进入请阅读声明并勾选以下三项。"));
            CheckBox c1 = new CheckBox(this);
            c1.setText("我确认演练范围仅限本机 / 离线靶标 / 已书面授权环境");
            c1.setTextColor(purgeFg());
            CheckBox c2 = new CheckBox(this);
            c2.setText("我确认不对外网或未授权系统发起任何扫描、探测或攻击");
            c2.setTextColor(purgeFg());
            CheckBox c3 = new CheckBox(this);
            c3.setText("我确认不将演练台用于违法用途，后果由使用者承担");
            c3.setTextColor(purgeFg());
            addV(box, c1, 6);
            addV(box, c2, 2);
            addV(box, c3, 2);
            final Button go = purgeButton("确认授权并进入演练台");
            go.setEnabled(false);
            go.setTextColor(0xFF5A5A5A);
            addV(box, go, 10);
            android.widget.CompoundButton.OnCheckedChangeListener checkAll = (b, chk) -> {
                boolean all = c1.isChecked() && c2.isChecked() && c3.isChecked();
                go.setEnabled(all);
                go.setTextColor(all ? purgeFg() : 0xFF5A5A5A);
            };
            c1.setOnCheckedChangeListener(checkAll);
            c2.setOnCheckedChangeListener(checkAll);
            c3.setOnCheckedChangeListener(checkAll);
            go.setOnClickListener(v -> {
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                        .putBoolean(PURGE_DRILL_OK, true).apply();
                pushOutput("\r\n[dsh purge] 演练台授权已确认（本机/离线/书面授权范围）\r\n");
                buildPurgeDrill(box);
            });
            return;
        }
        // 已授权：资产 / 技能 / 环境
        box.addView(purgeSection("资产"));
        final TextView assets = purgeResult();
        assets.setText("（加载中...）");
        addV(box, assets, 4);
        box.addView(purgeSection("技能"));
        final LinearLayout skillBox = new LinearLayout(this);
        skillBox.setOrientation(LinearLayout.VERTICAL);
        addV(box, skillBox, 4);
        refreshPurgeSkills(skillBox);
        box.addView(purgeSection("环境"));
        final TextView env = purgeResult();
        env.setText("（加载中...）");
        addV(box, env, 4);
        Button refresh = purgeButton("刷新巡检");
        addV(box, refresh, 10);
        // 资产巡检：guest 内实际挂载状态（依赖环境运行）
        new Thread(() -> {
            String out = executeInGuest(
                    "echo '[挂载]'; ls -ld /sdcard /host-data /host/system 2>&1; "
                            + "echo '[host-data]'; ls /host-data 2>/dev/null | head -3; "
                            + "echo '[sdcard]'; ls /sdcard 2>/dev/null | head -3", 12);
            final String r = out;
            runOnUiThread(() -> {
                assets.setTextColor(0xFF7FDB8A);
                assets.setText(r);
            });
        }, "purge-assets").start();
        // 环境巡检：宿主侧 + guest 混合
        new Thread(() -> {
            String guest = executeInGuest(
                    "echo '[系统] '$(cat /etc/alpine-release 2>/dev/null)' '$(uname -m 2>/dev/null); "
                            + "echo '[reasonix] '$(cat /root/.reasonix/.npm-version 2>/dev/null || echo 内置); "
                            + "echo '[root桥] '$([ -f /root/.root-ok ] && echo OK || echo 未授权); "
                            + "echo '[adb] '$(cat /root/.adb_status 2>/dev/null || echo 未连接); "
                            + "echo '[ds2api] '$(pgrep -x ds2api >/dev/null 2>&1 && echo RUNNING || echo STOPPED); "
                            + "echo '[磁盘] '$(df -h / 2>/dev/null | tail -1 | awk '{print $2\" 可用 \"$4}')", 12);
            final String mode = isChrootMode() ? "chroot（需 root）" : "proot（免 root）";
            final String r = "[运行模式] " + mode + "\n" + guest;
            runOnUiThread(() -> {
                env.setTextColor(0xFF7FDB8A);
                env.setText(r);
            });
        }, "purge-env").start();
        refresh.setOnClickListener(v -> buildPurgeDrill(box));
    }

    /** 资源页签：漏洞库 / 规则库 / skill 包 —— 内置安装到 guest + GitHub 同步（RSXM resources/） */
    private void buildPurgeResources(LinearLayout box) {
        box.removeAllViews();
        box.addView(purgeSection("资源中心"));
        box.addView(purgeTip("内置：漏洞库（7 份速查）、规则库（2 套）、红队 skill 包（dsh-purge 上游 23 个）。"
                + "「安装到环境」从 APK 提取部署（离线可用）；「GitHub 同步」从 "
                + "github.com/qianeric-backup/RSXM 的 resources/ 拉取更新（需网络）。"));
        // 漏洞库
        box.addView(purgeSection("漏洞库"));
        box.addView(purgeTip("~/.reasonix/purge/vulndb/：web-injection / web-logic / auth-identity / "
                + "intranet-post / cloud-mobile / cve-quick（reasonix 内 /vulndb 检索）"));
        final TextView vulnState = purgeResult();
        addV(box, vulnState, 4);
        LinearLayout vbtns = new LinearLayout(this);
        vbtns.setOrientation(LinearLayout.HORIZONTAL);
        Button vInstall = purgeButton("安装到环境");
        Button vSync = purgeButton("GitHub 同步");
        vbtns.addView(vInstall, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        vbtns.addView(vSync, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addV(box, vbtns, 6);
        // 规则库
        box.addView(purgeSection("规则库"));
        box.addView(purgeTip("~/.reasonix/rules/：rsxm-default + redteam-operations（启用即写全局指令）"));
        final LinearLayout rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        addV(box, rulesBox, 4);
        refreshPurgeRules(rulesBox, null);
        // skill 包
        box.addView(purgeSection("Skill 包"));
        box.addView(purgeTip("~/.reasonix/skills/：redteam（dsh-purge 23 个）、av-evasion（免杀对抗 18 章）、"
                + "hacker-asm-decompile（全平台反编译）。reasonix 自动加载（/skills 查看）；"
                + "另有 Skills4RedTeam 社区技能索引（~/.reasonix/purge/skills-index.md）"));
        final TextView skillState = purgeResult();
        addV(box, skillState, 4);
        LinearLayout sbtns = new LinearLayout(this);
        sbtns.setOrientation(LinearLayout.HORIZONTAL);
        Button sInstall = purgeButton("安装到环境");
        Button sSync = purgeButton("GitHub 同步全部");
        sbtns.addView(sInstall, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sbtns.addView(sSync, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addV(box, sbtns, 6);
        // 初始状态
        refreshPurgeResourceStates(vulnState, skillState);
        // 安装（宿主侧从 APK 提取，离线可用）
        vInstall.setOnClickListener(v -> {
            deployPurgeAssets(new File(getFilesDir(), "rootfs"));
            refreshPurgeResourceStates(vulnState, skillState);
            pushOutput("\r\n[dsh purge] 漏洞库已安装到环境（~/.reasonix/purge/vulndb/）\r\n");
        });
        sInstall.setOnClickListener(v -> {
            deployPurgeAssets(new File(getFilesDir(), "rootfs"));
            refreshPurgeResourceStates(vulnState, skillState);
            pushOutput("\r\n[dsh purge] 红队 skill 包已安装（~/.reasonix/skills/redteam/，重启环境生效）\r\n");
        });
        // GitHub 同步（guest 内 wget，busybox 自带）
        vSync.setOnClickListener(v -> {
            vSync.setEnabled(false);
            new Thread(() -> {
                String cmd = buildPurgeSyncCmd("vulndb", new String[]{
                        "00-index.md", "web-injection.md", "web-logic.md",
                        "auth-identity.md", "intranet-post.md", "cloud-mobile.md", "cve-quick.md"},
                        "~/.reasonix/purge/vulndb");
                String out = executeInGuest(cmd, 90);
                runOnUiThread(() -> {
                    vSync.setEnabled(true);
                    refreshPurgeResourceStates(vulnState, skillState);
                    pushOutput("\r\n[dsh purge] 漏洞库 GitHub 同步：" + out + "\r\n");
                });
            }, "purge-vuln-sync").start();
        });
        sSync.setOnClickListener(v -> {
            sSync.setEnabled(false);
            new Thread(() -> {
                StringBuilder all = new StringBuilder();
                try {
                    String[] names = getAssets().list("purge/skills/redteam");
                    java.util.List<String> mds = new ArrayList<>();
                    if (names != null) for (String n : names) if (n.endsWith(".md")) mds.add(n);
                    all.append(buildPurgeSyncCmd("skills/redteam", mds.toArray(new String[0]),
                            "~/.reasonix/skills/redteam")).append(" ");
                    all.append(buildPurgeSyncCmd("skills/av-evasion",
                            new String[]{"SKILL.md"}, "~/.reasonix/skills/av-evasion")).append(" ");
                    String[] avRefs = getAssets().list("purge/skills/av-evasion/references");
                    java.util.List<String> avRefMds = new ArrayList<>();
                    if (avRefs != null) for (String n : avRefs) if (n.endsWith(".md")) avRefMds.add(n);
                    all.append(buildPurgeSyncCmd("skills/av-evasion/references",
                            avRefMds.toArray(new String[0]),
                            "~/.reasonix/skills/av-evasion/references")).append(" ");
                    all.append(buildPurgeSyncCmd("skills/decompile",
                            new String[]{"SKILL.md"}, "~/.reasonix/skills/hacker-asm-decompile")).append(" ");
                    all.append(buildPurgeSyncCmd("", new String[]{"skills-index.md", "skills-index-LICENSE.txt"},
                            "~/.reasonix/purge")).append(" ");
                } catch (java.io.IOException e) {
                    Log.w(TAG, "purge skill sync list failed", e);
                }
                String out = executeInGuest(all.toString(), 150);
                runOnUiThread(() -> {
                    sSync.setEnabled(true);
                    refreshPurgeResourceStates(vulnState, skillState);
                    pushOutput("\r\n[dsh purge] skill 包 GitHub 同步：" + out + "\r\n");
                });
            }, "purge-skill-sync").start();
        });
    }

    /** 生成 guest 内 GitHub 同步命令：逐文件 wget raw.githubusercontent.com/qianeric-backup/RSXM/main/resources/<group>/<file> */
    private String buildPurgeSyncCmd(String group, String[] files, String destDir) {
        StringBuilder sb = new StringBuilder("mkdir -p ").append(destDir).append("; cd /tmp && ");
        String base = "https://raw.githubusercontent.com/qianeric-backup/RSXM/main/resources"
                + (group.isEmpty() ? "/" : "/" + group + "/");
        for (String f : files) {
            sb.append("wget -q -T 20 -O ").append(f).append(" '").append(base).append(f)
                    .append("' && mv -f ").append(f).append(" ").append(destDir).append("/; ");
        }
        sb.append("echo __SYNC_DONE__");
        return sb.toString();
    }

    /** 刷新资源状态行（guest 侧已装文件数 / 总文件数） */
    private void refreshPurgeResourceStates(final TextView vulnState, final TextView skillState) {
        new Thread(() -> {
            try {
                final int vulnTotal = 7;
                File vd = new File(new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "purge"), "vulndb");
                final int vulnHave = vd.exists() ? (vd.listFiles() == null ? 0 : vd.listFiles().length) : 0;
                File rt = new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "skills/redteam");
                final int rtHave = rt.exists() && rt.listFiles() != null ? rt.listFiles().length : 0;
                File av = new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "skills/av-evasion");
                final int avHave = av.exists() && av.listFiles() != null ? av.listFiles().length : 0;
                File dec = new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "skills/hacker-asm-decompile/SKILL.md");
                final boolean decHave = dec.exists();
                File idx = new File(new File(new File(getFilesDir(), "rootfs"),
                        "root/.reasonix"), "purge/skills-index.md");
                final boolean idxHave = idx.exists();
                runOnUiThread(() -> {
                    vulnState.setTextColor(0xFF7FDB8A);
                    vulnState.setText("漏洞库：已装 " + vulnHave + "/" + vulnTotal
                            + " 份（内置 7 份，APK 升级自动刷新）");
                    skillState.setTextColor(0xFF7FDB8A);
                    skillState.setText("Skill：redteam " + rtHave + "/23 · av-evasion " + avHave
                            + "/2 · decompile " + (decHave ? "✓" : "✗")
                            + " · 索引 " + (idxHave ? "✓" : "✗") + "（reasonix 自动加载）");
                });
            } catch (Exception e) {
                Log.w(TAG, "purge resource states failed", e);
            }
        }, "purge-res-states").start();
    }

    /**
     * 高级设置（二级菜单）：ROOT / 开发环境 / SKILL / MCP 服务器 / 后台运行 /
     * YOLO 免审批 / 滑动速度 / 快捷键。点击进入对应功能面板（面板即二级页面，
     * 返回箭头或系统返回键关闭整个面板回到主界面）。
     */
    private void showAdvancedDialog() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(16));
        addV(panel, createDarkTip("以下为二级功能入口：点击进入对应设置页（返回箭头关闭本面板）。"), 0);

        // ROOT
        addV(panel, createDarkSectionTitle("系统"), 10);
        addV(panel, createDarkMenuRow("ROOT", "手机 root 权限（KernelSU/Magisk）授权与测试", null, () -> showRootDialog()), 4);
        addV(panel, createDarkMenuRow("开发环境", "安装 Python/Node 等 apk 开发工具包", null, () -> showDevEnvDialog()), 4);

        // AI 能力
        addV(panel, createDarkSectionTitle("AI 能力"), 12);
        addV(panel, createDarkMenuRow("SKILL", "安装/管理 reasonix 技能", null, () -> showSkillInstallDialog()), 4);
        addV(panel, createDarkMenuRow("MCP 服务器", "管理当前项目 .mcp.json", null, () -> showMcpDialog()), 4);
        addV(panel, createDarkMenuRow("Serve 模式（无头对话）", "结构化对话/历史回显/回溯/审批，不依赖终端 TUI", null, () -> showServeDialog()), 4);

        // 运行模式
        addV(panel, createDarkSectionTitle("运行模式"), 12);
        final LinearLayout bgRow = createDarkMenuRow("后台运行", "app 后台保活，AI 会话不中断", null, () -> {
            SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
            boolean on = !sp.getBoolean("background_mode", BackgroundService.BG_MODE_DEFAULT);
            sp.edit().putBoolean("background_mode", on).apply();
            if (on) startBackgroundService(true); else stopBackgroundService();
            updateBgModeLabel();
        });
        advancedBgLabel = (TextView) bgRow.getChildAt(0);
        updateBgModeLabel();
        addV(panel, bgRow, 4);

        final LinearLayout yoloRow = createDarkMenuRow("YOLO 免审批", "reasonix 完全跳过工具审批（等价 --permission-mode danger-full-access）", null, () -> {
            SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
            boolean on = !sp.getBoolean("yolo_mode", true);
            sp.edit().putBoolean("yolo_mode", on).apply();
            syncYoloMark(on);
            updateYoloModeLabel();
            // 立即生效：重启 reasonix 环境（wrapper 读取新标记决定审批模式）
            restartEnvironment();
        });
        advancedYoloLabel = (TextView) yoloRow.getChildAt(0);
        updateYoloModeLabel();
        addV(panel, yoloRow, 4);

        // 交互偏好
        addV(panel, createDarkSectionTitle("交互偏好"), 12);
        // 滑动速度
        LinearLayout speedWrap = new LinearLayout(this);
        speedWrap.setOrientation(LinearLayout.VERTICAL);
        speedWrap.setBackgroundColor(0xFF141414);
        speedWrap.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView speedTitle = new TextView(this);
        speedTitle.setText("滑动速度");
        speedTitle.setTextColor(0xFFFFFFFF);
        speedTitle.setTextSize(14);
        speedWrap.addView(speedTitle);
        final TextView speedVal = new TextView(this);
        speedVal.setTextColor(0xFF8A8A8A);
        speedVal.setTextSize(12);
        speedWrap.addView(speedVal);
        final SeekBar sbSpeed = new SeekBar(this);
        int speed = getSharedPreferences("prefs", MODE_PRIVATE).getInt("scroll_speed", SPEED_DEFAULT);
        if (speed < SPEED_MIN) speed = SPEED_MIN;
        if (speed > SPEED_MAX) speed = SPEED_MAX;
        sbSpeed.setMax(SPEED_MAX - SPEED_MIN);
        sbSpeed.setProgress(speed - SPEED_MIN);
        speedWrap.addView(sbSpeed);
        advancedSpeedLabel = speedVal;
        updateSpeedLabel(speed);
        // 先注入当前档位（页面可能已加载；若未加载，onPageFinished 启动环境时也会注入）
        applyScrollSpeed(speed);
        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int s = seekBar.getProgress() + SPEED_MIN;
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("scroll_speed", s).apply();
                applyScrollSpeed(s);
                updateSpeedLabel(s);
            }
        });
        addV(panel, speedWrap, 4);

        addV(panel, createDarkMenuRow("快捷键", "悬浮快捷键工具栏（可拖拽，九键）", null, () -> toggleKeysToolbar()), 4);

        showPanel("高级设置", panel, () -> {
            advancedBgLabel = null;
            advancedYoloLabel = null;
            advancedSpeedLabel = null;
        });
    }

    /** 高级设置菜单行：返回整行 LinearLayout（标题 + 说明 + 点击监听）；
     * renderLabel 参数预留（状态标签动态化），点击整行触发 action。 */
    private LinearLayout createDarkMenuRow(String title, String desc,
                                           java.util.function.Consumer<TextView> renderLabel, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(0xFF141414);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        row.setLayoutParams(rp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(0xFF8A8A8A);
            d.setTextSize(11);
            row.addView(d);
        }
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        if (renderLabel != null) renderLabel.accept(t);
        return row;
    }

    /** 深色代码结果区 */
    private TextView createDarkResult() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFF7FDB8A);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setBackgroundColor(0xFF101010);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 固定最小高度 + 行数限制：结果回显出现/更新不引起功能页内容上下移动（长输出省略，完整在终端）
        tv.setMinHeight(dp(48));
        tv.setMaxLines(4);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /** ADB 无线调试：显示局域网 IP 与连接指引 */
    private void showAdbDialog() {
        String ip = getLocalIpAddress();
        final String fip = (ip == null) ? "<IP>" : ip;
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String savedPairPort = prefs.getString("adb_pair_port", "");
        String savedPairCode = prefs.getString("adb_pair_code", "");
        String status = readAdbStatus();
        // 输入面板：配对端口 / 配对码（连接端口由 app 自动扫描，无需填写）
        EditText pairPort = createDarkEditText("配对端口（无线调试界面显示，如 37000）", InputType.TYPE_CLASS_NUMBER);
        if (!savedPairPort.isEmpty()) pairPort.setText(savedPairPort);
        EditText pairCode = createDarkEditText("配对码（6 位数字）", InputType.TYPE_CLASS_NUMBER);
        if (savedPairCode.length() == 6) pairCode.setText(savedPairCode);
        TextView tip = createDarkTip("本机 IP：" + fip + "\n"
                + "手机「开发者选项 → 无线调试」开启后：首次填配对端口+配对码点「配对并连接」；\n"
                + "之后点「自动连接」免配对直连；连接后 reasonix 内可直接 adb shell / adb install。");
        // 独立状态行（执行后自动刷新）
        TextView statusLine = new TextView(this);
        statusLine.setText("连接状态：" + status);
        statusLine.setTextColor(status.contains("已连接") ? 0xFF7FDB8A : (status.contains("需配对") ? 0xFFFFD54F : 0xFFCCCCCC));
        statusLine.setTextSize(13);
        statusLine.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = dp(16);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(8), pad, dp(12));
        addV(panel, statusLine, 0);
        addV(panel, tip, 8);
        panel.addView(createDarkSectionTitle("配对信息"));
        addV(panel, pairPort, 6);
        addV(panel, pairCode, 8);
        // 执行结果区（adb 输出实时显示，不依赖 reasonix 会话）
        TextView resultView = createDarkResult();
        resultView.setText("（执行结果将显示在这里）");
        addV(panel, resultView, 8);
        // 自动连接按钮：app 直接驱动 guest 内 adb-autoconnect（扫描 30000-49999 并 connect）
        Button autoBtn = createDarkButton("自动连接（免配对直连）");
        autoBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            runAdbInGuest("adb-autoconnect", resultView, statusLine);
        });
        addV(panel, autoBtn, 8);
        // 配对并连接按钮：app 执行配对 + 自动扫描连接端口 + 连接（全程 app 处理）
        Button pairBtn = createDarkButton("配对并连接");
        pairBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            String pp = pairPort.getText().toString().trim();
            String pc = pairCode.getText().toString().trim();
            if (pp.isEmpty() || pc.isEmpty()) {
                resultView.setTextColor(0xFFFF6E6E);
                resultView.setText("请先在手机上开启「无线调试」，抄下配对端口和 6 位配对码后填写。");
                return;
            }
            runAdbInGuest("adb-dopair " + pp + " " + pc, resultView, statusLine);
        });
        addV(panel, pairBtn, 8);
        // 复制命令按钮（原对话框 neutral 按钮 → 面板内按钮）
        Button copyBtn = createDarkButton("复制命令");
        copyBtn.setOnClickListener(v -> {
            saveAdbPrefs(prefs, pairPort, pairCode);
            String cmd = buildAdbCommands(fip,
                    pairPort.getText().toString().trim(),
                    pairCode.getText().toString().trim());
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
            } catch (Exception e) {
                Log.w(TAG, "clipboard failed", e);
            }
        });
        addV(panel, copyBtn, 8);
        // Shizuku 支持：依赖 Shizuku 的 adb 权限持久化（替代原「后台保活」；Shizuku 服务常驻，
        // 本应用关闭后仍可经 Shizuku 执行 adb 命令）
        panel.addView(createDarkSectionTitle("Shizuku 持久化"));
        final TextView szStatus = new TextView(this);
        szStatus.setTextSize(13);
        szStatus.setPadding(dp(2), dp(2), dp(2), dp(4));
        final boolean szOn = shizukuAvailable();
        szStatus.setText("Shizuku：" + (szOn ? "已授权（adb 权限可用）" : "未授权/未安装"));
        szStatus.setTextColor(szOn ? 0xFF4CAF50 : 0xFFFFD54F);
        addV(panel, szStatus, 6);
        addV(panel, createDarkTip(
                "Shizuku 授权后 adb 命令以其权限执行，关闭本应用仍可用（替代后台保活）。"), 6);
        Button szBtn = createDarkButton(szOn
                ? "通过 Shizuku 持久化 adb（启动 adb server）" : "Shizuku 授权（打开授权页）");
        szBtn.setOnClickListener(v -> {
            if (shizukuAvailable()) {
                resultView.setTextColor(0xFFFFD54F);
                resultView.setText("通过 Shizuku 启动 adb server（持久化）...\n");
                new Thread(() -> {
                    String out = execViaShizuku("adb start-server 2>&1; adb devices 2>&1", 30);
                    runOnUiThread(() -> {
                        resultView.setTextColor(0xFF7FDB8A);
                        resultView.setText("Shizuku adb server 已启动（Shizuku 保持，持久化）：\n" + out);
                        szStatus.setText("Shizuku：已授权（adb 权限可用）");
                        szStatus.setTextColor(0xFF4CAF50);
                    });
                }, "shizuku-adb").start();
            } else {
                requestShizukuPermission();
            }
        });
        addV(panel, szBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        Runnable autoConnect = () -> runAdbInGuest("adb-autoconnect", resultView, statusLine);
        // 面板关闭时同步取消：stop 轮询 + 摘掉 600ms 延迟任务（旧实现面板关闭后仍会
        // 发起至多 150s 的 adb 命令并占住 adbCmdLock）
        showPanel("ADB 调试", panel, () -> {
            stopAdbStatusRefresh();
            statusLine.removeCallbacks(autoConnect);
        });
        // 状态实时刷新：面板打开期间每 4 秒用 adb devices 检查真实连接（防快照过期）
        startAdbStatusRefresh(statusLine);
        // 操作逻辑优化：状态未知（首次/环境刚启动）时自动触发一次检测，免去手动点击
        if (status.contains("未知")) {
            statusLine.postDelayed(autoConnect, 600);
        }
    }

    /** ADB 状态定时刷新：面板打开期间每 4 秒检查 adb devices 真实状态（防快照过期显示不符） */
    private final Handler adbStatusRefresher = new Handler(Looper.getMainLooper());
    private Runnable adbStatusTask;
    /** 轮询存活标记（v2.0.25 修复泄漏：worker 在 runOnUiThread 里自我 postDelayed 重排，
     *  stopAdbStatusRefresh 只 removeCallbacks——面板关闭时 worker 正在执行（每轮 0.3~10s，
     *  在飞概率高）→ 回调结束后又在 stop 之后 self-reschedule，轮询永久泄漏且与用户命令抢锁） */
    private final java.util.concurrent.atomic.AtomicBoolean adbStatusActive =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void startAdbStatusRefresh(TextView statusLine) {
        stopAdbStatusRefresh();
        adbStatusActive.set(true);
        final TextView fStatusLine = statusLine;
        adbStatusTask = new Runnable() {
            @Override
            public void run() {
                if (!adbStatusActive.get()) return;   // 面板已关：在飞的循环到此终止
                new Thread(() -> {
                    if (!adbStatusActive.get()) return;
                    String st;
                    boolean wifiOff = false;
                    // 系统无线调试开关检测（经 root 桥读系统设置，不依赖 adb 连接）
                    try {
                        String wd = execRootCommand("settings get global adb_wifi_enabled", 4);
                        if (wd != null && wd.trim().equals("0")) wifiOff = true;
                    } catch (Exception ignored) {
                    }
                    if (wifiOff) {
                        st = "系统无线调试未开启（请到 设置→开发者选项→无线调试 打开）";
                    } else {
                        try {
                            String out = executeInGuest("adb devices 2>&1", 6);
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("([\\d.]+:\\d+)\\s+device(\\s|$)")
                                    .matcher(out == null ? "" : out);
                            if (m.find()) {
                                st = "已连接 " + m.group(1);
                            } else if (out != null && out.contains("unauthorized")) {
                                st = "需授权（手机弹窗点允许）";
                            } else if (out != null && out.contains("offline")) {
                                st = "设备离线（offline）";
                            } else {
                                st = "未连接（点自动连接或配对）";
                            }
                        } catch (Exception e) {
                            st = "检测失败";
                        }
                    }
                    final String statusText = st;   // final 副本供 lambda 捕获
                    runOnUiThread(() -> {
                        if (!adbStatusActive.get()) return;   // 已停止：不再更新也不重排
                        if (fStatusLine != null) fStatusLine.setText("连接状态：" + statusText);
                        adbStatusRefresher.postDelayed(this, 4000);
                    });
                }, "adb-status").start();
            }
        };
        adbStatusRefresher.postDelayed(adbStatusTask, 1200);
    }

    private void stopAdbStatusRefresh() {
        adbStatusActive.set(false);   // 先停活标记（在飞 worker 不再重排），再摘回调
        if (adbStatusTask != null) {
            adbStatusRefresher.removeCallbacks(adbStatusTask);
            adbStatusTask = null;
        }
    }

    /** 尝试 root 修复缺失的 native 库：从 APK 提取 so 到 nativeLibraryDir，并修复 SELinux 上下文（apk_data_file 域才可执行） */
    private boolean tryRepairNativeLib(String nativeLibDir, String apkPath) {        if (findSuPath() == null) return false;   // 无 root 无法 chcon，跳过
        File tmp = new File(getFilesDir(), "tmpnative");
        tmp.mkdirs();
        boolean any = false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
            String prefix = "lib/arm64-v8a/";
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith(prefix) && n.endsWith(".so")) {
                    String name = n.substring(prefix.length());
                    File out = new File(tmp, name);
                    try (java.io.InputStream in = zf.getInputStream(e);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int c;
                        while ((c = in.read(buf)) > 0) fos.write(buf, 0, c);
                    }
                    String r = execRootCommand("mkdir -p " + nativeLibDir
                            + " && cp " + out.getAbsolutePath() + " " + nativeLibDir + "/" + name
                            + " && chmod 755 " + nativeLibDir + "/" + name
                            + " && chcon u:object_r:apk_data_file:s0 " + nativeLibDir + "/" + name
                            + " && echo REPAIR_OK", 30);
                    if (r != null && r.contains("REPAIR_OK")) {
                        Log.d(TAG, "native lib repaired: " + name);
                        any = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "repair native lib failed", e);
        }
        return any && new File(nativeLibDir, "proot.so").exists();
    }

    /** 无 root 修复 native 库：自动打开系统安装器引导覆盖安装本 APK
     *  （覆盖安装后系统重新解压 native lib）。
     *  注意：Android 安全机制要求无 root 覆盖安装必须用户确认一次（静默自更新仅系统应用可用），
     *  因此自动打开安装器后用户点「更新」即可，无需找 APK/手动选择。首次需先允许"安装未知应用" */
    private void promptReinstallForNativeLib() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                pushOutput("\r\n[native 库缺失。需要覆盖安装本应用来修复，请先允许「安装未知应用」]\r\n");
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            // 复制 APK 到 cacheDir/apk/ 并打开系统安装器（覆盖安装 → 系统重新解压 native lib）
            File apkDir = new File(getCacheDir(), "apk");
            apkDir.mkdirs();
            File apk = new File(apkDir, "reinstall.apk");
            try (java.io.InputStream in = new java.io.FileInputStream(getApplicationInfo().sourceDir);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int c;
                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pushOutput("\r\n[native 库缺失。已自动打开安装器，请点「更新/安装」覆盖安装本应用（系统将重新解压所需文件）]\r\n");
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "prompt reinstall failed", e);
            pushOutput("\r\n[启动失败] native 库未解压：请卸载后重新安装本 APK（或电脑端用 adb install --no-streaming）\r\n");
        }
    }

    /** Shizuku 可用性：binder 存活且已授权（adb/root 权限） */
    private boolean shizukuAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w(TAG, "shizuku check failed", e);
            return false;
        }
    }

    /** 经 Shizuku 权限执行 shell 命令（adb 权限；Shizuku 服务常驻 → 持久化，不依赖本应用存活） */
    private String execViaShizuku(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            try (InputStream in = p.getInputStream()) {
                while (System.currentTimeMillis() < deadline) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.w(TAG, "shizuku exec failed", e);
            return null;
        } finally {
            if (p != null) p.destroy();   // v2.0.25：超时/异常后不留子进程泄漏
        }
    }

    /** 请求 Shizuku 授权（打开 Shizuku Manager 授权页）；未安装时给出提示 */
    private void requestShizukuPermission() {
        try {
            Intent intent = new Intent("moe.shizuku.manager.intent.action.REQUEST_PERMISSION");
            intent.setPackage("moe.shizuku.manager");
            intent.putExtra("moe.shizuku.manager.intent.extra.APP_UID", getApplicationInfo().uid);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "request shizuku permission failed", e);
            pushOutput("\r\n[Shizuku Manager 未安装，请先安装 Shizuku（GitHub: RikkaApps/Shizuku）后再授权]\r\n");
        }
    }

    /** 在 guest 内直接执行 adb 命令（经 adb 服务，不依赖 reasonix/AI 会话），结果实时显示 */
    private void runAdbInGuest(String cmd, TextView resultView, TextView statusLine) {
        final String fcmd = cmd;
        resultView.setTextColor(0xFFFFD54F);
        resultView.setText("执行中...\n" + fcmd.trim());
        new Thread(() -> {
            String out = executeInGuest(fcmd, 150);
            runOnUiThread(() -> {
                resultView.setTextColor(out.contains("error") || out.contains("超时")
                        ? 0xFFFF6E6E : 0xFF7FDB8A);
                resultView.setText(out);
                if (statusLine != null) {
                    String st = readAdbStatus();
                    statusLine.setText("连接状态：" + st);
                    statusLine.setTextColor(st.contains("已连接") ? 0xFF7FDB8A
                            : (st.contains("需配对") ? 0xFFFFD54F : 0xFFCCCCCC));
                }
            });
        }, "adb-exec").start();
    }

    /** 写入命令到 guest adb 服务并轮询结果（.adb-cmd → 执行 → .adb-out 含 __DONE__ 标记）。
     *  全程持有 adbCmdLock 串行执行：多条并发命令共用同一个 .adb-cmd/.adb-out 文件，
     *  并发写入会互相覆盖导致命令丢失/读到错误输出（如安装 SKILL 后立刻刷新列表）。 */
    private final Object adbCmdLock = new Object();
    private String executeInGuest(String cmd, int timeoutSec) {
        synchronized (adbCmdLock) {
            try {
                File root = new File(new File(getFilesDir(), "rootfs"), "root");
                File cmdFile = new File(root, ".adb-cmd");
                File outFile = new File(root, ".adb-out");
                outFile.delete();
                java.nio.file.Files.write(cmdFile.toPath(), cmd.getBytes(StandardCharsets.UTF_8));
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(300);
                    if (outFile.exists()) {
                        String out = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
                                StandardCharsets.UTF_8);
                        if (out.contains("__DONE__")) {
                            outFile.delete();
                            String r = out.replace("__DONE__", "").trim();
                            Log.d(TAG, "adb out: " + r);
                            return r.isEmpty() ? "(无输出)" : r;
                        }
                    }
                }
                return "(执行超时 " + timeoutSec + " 秒)";
            } catch (Exception e) {
                Log.w(TAG, "executeInGuest failed", e);
                return "(执行失败: " + e + ")";
            }
        }
    }

    /** 保存 ADB 配对信息（下次打开自动填充） */
    private void saveAdbPrefs(SharedPreferences prefs, EditText pairPort, EditText pairCode) {
        prefs.edit()
                .putString("adb_pair_port", pairPort.getText().toString().trim())
                .putString("adb_pair_code", pairCode.getText().toString().trim())
                .apply();
    }

    /** 读取 guest 内 adb 连接状态（/root/.adb_status，由 adb-autoconnect 写入） */
    private String readAdbStatus() {
        try {
            File f = new File(new File(new File(getFilesDir(), "rootfs"), "root"), ".adb_status");
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    if (s.startsWith("connected")) return "已连接 " + s.substring("connected ".length());
                    if (s.equals("unauthorized")) return "已连接但未授权（请在手机弹窗点允许）";
                    if (s.equals("offline")) return "设备离线（offline）";
                    if (s.equals("need_pair")) return "需配对：无线调试已开启但未信任本设备（填写配对端口+配对码配对）";
                    if (s.equals("no_port")) return "未发现无线调试端口（请确认已开启）";
                    if (s.equals("no_adb")) return "adb 未就绪（android-tools 后台安装中，请稍后重试）";
                    if (s.equals("no_ip")) return "未获取本机 IP（请连接 Wi-Fi）";
                    return s;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readAdbStatus failed", e);
        }
        return "未知（首次启动后自动检测）";
    }

    /** 生成 adb 配对/连接命令（由 app 驱动 guest 内 adb-dopair 完成配对+自动扫描连接端口） */
    private String buildAdbCommands(String ip, String pairPort, String pairCode) {
        StringBuilder sb = new StringBuilder();
        if (!pairPort.isEmpty() && !pairCode.isEmpty()) {
            sb.append("adb-dopair ").append(pairPort).append(' ').append(pairCode).append('\n');
        }
        return sb.toString();
    }

    /** 向 guest 终端发送命令（用户需已退到 shell；reasonix 会话内无效） */
    private void sendToTerminal(String cmd) {
        // v2.0.25：走 write()（与 xterm 键入/resize 共用同一把 sProcIn 锁），
        // 旧实现直接写 sProcIn，会与其他线程字节交错
        if (sProcIn == null) {
            pushOutput("\r\n[终端未就绪]\r\n");
            return;
        }
        write(cmd + "\n");
        pushOutput("\r\n[已发送 adb 命令到终端]\r\n");
        Log.d(TAG, "sent to terminal: " + cmd.replace("\n", " ; "));
    }

    /** 获取本机局域网 IPv4 地址（遍历网络接口；优先 wlan*——无线调试配对要求与手机同网段，
     *  旧实现按枚举顺序取第一个非环回 IPv4，可能取到 rmnet 蜂窝地址导致配对失败） */
    private String getLocalIpAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean wlan = ni.getName() != null && ni.getName().startsWith("wlan");
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    byte[] b = addr.getAddress();
                    if (b.length == 4 && (b[0] & 0xff) != 0) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            if (wlan) return ip;      // Wi-Fi 地址优先
                            if (fallback == null) fallback = ip;
                        }
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpAddress failed", e);
        }
        return null;
    }

    /** API Key 配置：读取/修改 reasonix 的 DEEPSEEK_API_KEY */
    /** 解析 config.toml 当前 default_model（provider name，无则默认 deepseek-flash） */
    private String parseDefaultModel(File conf) {
        if (conf != null && conf.exists()) {
            try {
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("default_model")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("default_model\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) return m.group(1);
                    }
                }
            } catch (Exception ignored) {}
        }
        return "deepseek-flash";
    }

    /** 解析 config.toml [[providers]] 块：返回 {providerName, model} 列表 */
    private List<String[]> parseProviders(File conf) {
        List<String[]> list = new ArrayList<>();
        if (conf != null && conf.exists()) {
            try {
                String name = null, model = null;
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("[[providers]]")) {
                        if (name != null && model != null) list.add(new String[]{name, model});
                        name = null; model = null;
                    } else if (name == null && t.matches("name\\s*=.*")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) name = m.group(1);
                    } else if (model == null && t.matches("model\\s*=.*")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^model\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        if (m.find()) model = m.group(1);
                    }
                }
                if (name != null && model != null) list.add(new String[]{name, model});
            } catch (Exception ignored) {}
        }
        return list;
    }

    /** Provider 配置信息（对应 config.toml 的一个 [[providers]] 块） */
    private static class ProviderInfo {
        String name;      // provider 名（default_model 引用）
        String kind;      // anthropic | openai | responses
        String baseUrl;   // API 端点
        String requestUrl;// 精确请求 URL（config.toml 的 request_url，完整端点时才有）
        String apiKeyEnv; // .env 中存储密钥的变量名
        String model;     // 默认模型（default / model 字段）
        List<String> models;   // models=[...] 列表（可选；为空时联网拉取或手工填）
        ProviderInfo(String name, String kind, String baseUrl, String apiKeyEnv, String model) {
            this(name, kind, baseUrl, apiKeyEnv, model, null);
        }
        ProviderInfo(String name, String kind, String baseUrl, String apiKeyEnv, String model, List<String> models) {
            this.name = name; this.kind = kind; this.baseUrl = baseUrl;
            this.apiKeyEnv = apiKeyEnv; this.model = model; this.models = models;
        }
    }

    /** 解析 config.toml 全部 [[providers]] 块，返回含 name/kind/base_url/api_key_env/model/models 的完整信息 */
    private List<ProviderInfo> parseProviderInfos(File conf) {
        List<ProviderInfo> list = new ArrayList<>();
        if (conf != null && conf.exists()) {
            try {
                String name = null, kind = null, baseUrl = null, apiKeyEnv = null, model = null;
                String requestUrl = null;
                List<String> models = null;
                for (String l : new String(java.nio.file.Files.readAllBytes(conf.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("[[providers]]")) {
                        if (name != null) {
                            // 中间块 flush 同样补 api_key_env 兜底（v2.0.25：旧只在末块兜底，
                            // 中间 provider 的 apiKeyEnv=null → .env 读写落空、删除误判失败）
                            String ae = (apiKeyEnv == null || apiKeyEnv.isEmpty()) ? "API_KEY" : apiKeyEnv;
                            ProviderInfo pi = new ProviderInfo(name, kind, baseUrl, ae, model, models);
                            pi.requestUrl = requestUrl;
                            list.add(pi);
                        }
                        name = null; kind = null; baseUrl = null; apiKeyEnv = null; model = null;
                        requestUrl = null; models = null;
                        continue;
                    }
                    if (t.startsWith("[")) continue;   // 其他 section 块跳过
                    java.util.regex.Matcher m;
                    if (name == null && (m = java.util.regex.Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        name = m.group(1);
                    } else if (kind == null && (m = java.util.regex.Pattern.compile("^kind\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        kind = m.group(1);
                    } else if (baseUrl == null && (m = java.util.regex.Pattern.compile("^base_url\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        baseUrl = m.group(1);
                    } else if (requestUrl == null && (m = java.util.regex.Pattern.compile("^request_url\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        requestUrl = m.group(1);
                    } else if (apiKeyEnv == null && (m = java.util.regex.Pattern.compile("^api_key_env\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        apiKeyEnv = m.group(1);
                    } else if (model == null && (m = java.util.regex.Pattern.compile("^(?:default|model)\\s*=\\s*\"([^\"]+)\"").matcher(t)).find()) {
                        model = m.group(1);
                    } else if (models == null && t.startsWith("models")) {
                        // models = [ "a", "b", ... ]
                        java.util.regex.Matcher ms = java.util.regex.Pattern
                                .compile("models\\s*=\\s*\\[(.*)\\]", java.util.regex.Pattern.DOTALL).matcher(t);
                        if (ms.find()) {
                            models = new ArrayList<>();
                            java.util.regex.Matcher item = java.util.regex.Pattern
                                    .compile("\"([^\"]+)\"").matcher(ms.group(1));
                            while (item.find()) models.add(item.group(1));
                        }
                    }
                }
                if (name != null) {
                    // api_key_env 缺失/为空时兜底 API_KEY，避免读写 .env 时落空
                    if (apiKeyEnv == null || apiKeyEnv.isEmpty()) apiKeyEnv = "API_KEY";
                    ProviderInfo pi = new ProviderInfo(name, kind, baseUrl, apiKeyEnv, model, models);
                    pi.requestUrl = requestUrl;
                    list.add(pi);
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    /** 改写 config.toml 的 default_model（provider name），返回是否成功 */
    private boolean setDefaultModel(File conf, String providerName) {
        try {
            if (conf == null || !conf.exists()) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            boolean ok = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("default_model")) {
                    lines.set(i, "default_model = \"" + providerName + "\"");
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                // TOML 顶层键必须在所有 [section] 之前：找不到 default_model 时插到第一个 section 前，无 section 追加末尾
                int insert = lines.size();
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().startsWith("[")) { insert = i; break; }
                }
                lines.add(insert, "default_model = \"" + providerName + "\"");
            }
            java.nio.file.Files.write(conf.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "set default model failed", e);
            return false;
        }
    }

    /** 读取 .env 中指定变量的值（无则返回空串） */
    private String readApiKeyFromEnv(File env, String varName) {
        if (env == null || !env.exists() || varName == null || varName.isEmpty()) return "";
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(env.toPath()),
                    StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.startsWith(varName + "=")) {
                    return t.substring(varName.length() + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 加载 provider 的可选模型到模型 Spinner：静态 models 优先，否则联网拉取，失败兜底静态表。
     *  供切换 provider 与点击「刷新模型列表」复用。 */
    /** 模型下拉请求序号（v2.0.25 修复异步竞态）：切 provider/测试连通性/刷新列表三条
     *  异步链路共用 adapter，旧回调后至会覆盖新 provider 的列表——保存时把旧 provider
     *  的模型名经 setProviderDefaultModel 写进新 provider 块（config 写脏）。 */
    private volatile int modelReqSeq = 0;

    /** 最近一次「拉取模型」的失败原因（URL + HTTP 状态码 + 服务端原文），
     *  供 UI 直接展示——401/403 这类鉴权失败以前只显示「未能获取模型列表」，无从定位 */
    private volatile String lastModelFetchError = "";

    private void loadModelsForProvider(final ProviderInfo p, final File env,
                                       final ArrayAdapter<String> modelAdapter, final Spinner modelSpinner) {
        if (p == null) return;
        final int reqId = ++modelReqSeq;
        // 静态 models 已配：直接填充（无需联网）
        if (p.models != null && !p.models.isEmpty()) {
            final List<String> list = p.models;
            runOnUiThread(() -> {
                if (reqId != modelReqSeq) return;   // 已有更新的请求，放弃过期结果
                modelAdapter.clear();
                for (String s : list) modelAdapter.add(s);
                modelAdapter.notifyDataSetChanged();
                int idx = 0;
                if (p.model != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).equals(p.model)) { idx = i; break; }
                    }
                }
                modelSpinner.setSelection(idx);
            });
            return;
        }
        // 无静态列表：异步联网拉取；失败不兜底（保持空列表，不显示任何模型）
        new Thread(() -> {
            String key = readApiKeyFromEnv(env, p.apiKeyEnv);
            List<String> fetched = fetchModelsFromProvider(p.baseUrl, key, p.kind);
            final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
            runOnUiThread(() -> {
                if (reqId != modelReqSeq) return;   // 过期结果丢弃
                modelAdapter.clear();
                for (String s : finalList) modelAdapter.add(s);
                modelAdapter.notifyDataSetChanged();
                int idx = 0;
                if (p.model != null) {
                    for (int i = 0; i < finalList.size(); i++) {
                        if (finalList.get(i).equals(p.model)) { idx = i; break; }
                    }
                }
                modelSpinner.setSelection(idx);
            });
        }, "rx-load-models").start();
    }

    /** 改写 config.toml 中指定 provider 块的 default 字段（默认模型名），返回是否成功 */
    private boolean setProviderDefaultModel(File conf, String providerName, String modelName) {
        try {
            if (conf == null || !conf.exists() || providerName == null || modelName == null) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            // 块定位：[[providers]] 重置 → name 行匹配则进入目标块（name 行在块头，须无条件参与匹配）
            boolean inTarget = false, found = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[[providers]]")) {
                    inTarget = false;
                } else if (t.startsWith("[")) {
                    inTarget = false;   // 遇其他 section：目标块已结束（防越块改写/插入）
                    continue;
                } else if (t.startsWith("name")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                    inTarget = m.find() && m.group(1).equals(providerName);
                }
                if (inTarget && t.startsWith("default")) {
                    lines.set(i, "default     = \"" + tomlEsc(modelName) + "\"");
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 未找到 default 字段：插到目标块末行之后（下一 [[providers]] 前），不越出块边界
                int insertAfter = -1;
                boolean inBlk = false;
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (t.startsWith("[[providers]]")) {
                        inBlk = false;
                    } else if (t.startsWith("[")) {
                        inBlk = false;   // 遇其他 section：目标块已结束
                        continue;
                    } else if (t.startsWith("name")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(t);
                        inBlk = m.find() && m.group(1).equals(providerName);
                    }
                    if (inBlk) insertAfter = i;
                }
                if (insertAfter >= 0) {
                    lines.add(insertAfter + 1, "default     = \"" + tomlEsc(modelName) + "\"");
                    found = true;
                }
            }
            if (found) {
                java.nio.file.Files.write(conf.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "set provider default model failed", e);
            return false;
        }
    }

    /** 改写 config.toml 中指定 provider 块的 kind / base_url / request_url（手动修改 API URL 或协议），
     *  返回是否成功。requestUrl 为空表示该 provider 不用精确端点，此时会删除块内已有的 request_url
     *  （否则旧 request_url 会盖住新写入的 base_url，reasonix 仍打旧地址）。 */
    private boolean setProviderEndpoint(File conf, String providerName, String newBaseUrl,
                                        String requestUrl, String kind) {
        try {
            if (conf == null || !conf.exists() || providerName == null
                    || newBaseUrl == null || newBaseUrl.isEmpty()) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            // 1) 定位目标 [[providers]] 块 [start, end)
            int start = -1, end = -1;
            for (int i = 0; i < lines.size() && start < 0; i++) {
                if (!lines.get(i).trim().startsWith("[[providers]]")) continue;
                int j = i + 1;
                while (j < lines.size()) {
                    String tj = lines.get(j).trim();
                    if (tj.startsWith("[[providers]]") || tj.startsWith("[")) break;
                    j++;
                }
                for (int k = i + 1; k < j; k++) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(lines.get(k).trim());
                    if (m.find()) {
                        if (m.group(1).equals(providerName)) { start = i; end = j; }
                        break;
                    }
                }
            }
            if (start < 0) return false;
            // 2) 重建该块：丢弃待改写的三个键行，紧随 name 行写回新值
            List<String> rebuilt = new ArrayList<>();
            boolean inserted = false;
            for (int i = start; i < end; i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("base_url") || t.startsWith("request_url") || t.startsWith("kind")) continue;
                rebuilt.add(lines.get(i));
                if (!inserted && t.startsWith("name")) {
                    if (kind != null && !kind.isEmpty()) rebuilt.add("kind        = \"" + tomlEsc(kind) + "\"");
                    rebuilt.add("base_url    = \"" + tomlEsc(newBaseUrl) + "\"");
                    if (requestUrl != null && !requestUrl.isEmpty()) {
                        rebuilt.add("request_url = \"" + tomlEsc(requestUrl) + "\"");
                    }
                    inserted = true;
                }
            }
            if (!inserted) {   // 结构异常（块内无 name 行）：插到块首
                int at = 1;
                if (kind != null && !kind.isEmpty()) rebuilt.add(at++, "kind        = \"" + tomlEsc(kind) + "\"");
                rebuilt.add(at++, "base_url    = \"" + tomlEsc(newBaseUrl) + "\"");
                if (requestUrl != null && !requestUrl.isEmpty()) {
                    rebuilt.add(at, "request_url = \"" + tomlEsc(requestUrl) + "\"");
                }
            }
            List<String> out = new ArrayList<>();
            out.addAll(lines.subList(0, start));
            out.addAll(rebuilt);
            out.addAll(lines.subList(end, lines.size()));
            java.nio.file.Files.write(conf.toPath(), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "set provider endpoint failed", e);
            return false;
        }
    }

    /** 联网拉取可选模型列表：优先 GET {base}/models（OpenAI 兼容，解析 data[].id）；
     *  Anthropic 风格端点（kind=anthropic 或 URL 含 /anthropic）追加 GET {base}/v1/models
     *  （x-api-key 鉴权）重试。失败返回 null（UI 保持空列表，可手动填模型名）。
     *  传入完整端点（…/v1/responses 等）时先回退到其 base，避免拿端点路径去拼 /models。 */
    private List<String> fetchModelsFromProvider(String baseUrl, String apiKey, String kind) {
        List<String> models = tryFetchModelsOpenAI(baseUrl, apiKey);
        if (models != null && !models.isEmpty()) return models;
        String base = probeBaseOf(baseUrl);
        if (base.isEmpty()) return null;
        boolean anthropicLike = (kind != null && kind.toLowerCase().contains("anthropic"))
                || base.toLowerCase().contains("/anthropic");
        if (anthropicLike) {
            List<String> alt = tryFetchModelsAnthropic(base, apiKey);
            if (alt != null && !alt.isEmpty()) return alt;
        }
        return (models != null && !models.isEmpty()) ? models : null;
    }

    /** 单次 GET 模型列表（Bearer 或 x-api-key 鉴权），解析 data[].id；失败返回 null */
    private List<String> fetchModelsAt(String url, String apiKey, boolean anthropicStyle) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            if (apiKey != null && !apiKey.isEmpty()) {
                if (anthropicStyle) conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            if (anthropicStyle) conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 200) {
                lastModelFetchError = "";
                try (java.io.InputStream in = conn.getInputStream()) {
                    return parseModelIds(readAllStream(in));
                }
            }
            String err = excerptBody(conn.getErrorStream());
            lastModelFetchError = url + " → HTTP " + code + (err.isEmpty() ? "" : "：" + err);
        } catch (Exception e) {
            lastModelFetchError = url + " → " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "fetch models failed: " + url, e);
        } finally {
            if (conn != null) conn.disconnect();   // 防 keep-alive 连接泄漏
        }
        return null;
    }

    /** OpenAI 风格：GET {base}/models（Bearer 鉴权），base 不含 /v1 时再补试 {base}/v1/models；
     *  解析 data[].id；失败返回 null */
    private List<String> tryFetchModelsOpenAI(String baseUrl, String apiKey) {
        String base = probeBaseOf(baseUrl);
        if (base.isEmpty()) return null;
        String url = base.endsWith("/models") ? base : base + "/models";
        List<String> r = fetchModelsAt(url, apiKey, false);
        if (r != null && !r.isEmpty()) return r;
        if (!base.endsWith("/v1")) {
            String alt = base + "/v1/models";
            if (!alt.equals(url)) {
                List<String> r2 = fetchModelsAt(alt, apiKey, false);
                if (r2 != null && !r2.isEmpty()) return r2;
            }
        }
        return r;
    }

    /** Anthropic 风格：GET {base}/v1/models（x-api-key + anthropic-version 鉴权），
     *  base 已含 /v1 时用 {base}/models；解析 data[].id；失败返回 null */
    private List<String> tryFetchModelsAnthropic(String baseUrl, String apiKey) {
        String base = probeBaseOf(baseUrl);
        if (base.isEmpty()) return null;
        return fetchModelsAt(deriveAnthropicModelsUrl(base), apiKey, true);
    }

    // ==================== 连通性测试 ====================

    /** 连通性测试结果（结构化，UI 据此展示结论与配色） */
    private static class ProbeResult {
        boolean reachable;    // 网络层可达（收到任意 HTTP 响应）
        boolean authOk;       // 鉴权通过（未出现 401/403）
        int code = -1;        // 最后一次 HTTP 状态码（-1 = 网络异常）
        String via;           // 命中的探测端点（/models 或 /v1/messages）
        String detail = "";   // 附加信息（异常摘要/服务端错误摘录）
        List<String> models;  // 探测得到的模型列表（可空）
    }

    /** 完整端点 URL 的解析结果（中转站/网关常直接给出 `…/v1/responses` 这类精确地址） */
    private static class EndpointSpec {
        String kind;        // openai | anthropic | responses
        String baseUrl;     // 传给 reasonix 的 base_url（reasonix 据此自行补端点路径）
        String requestUrl;  // 精确请求 URL，写 config.toml 的 request_url
    }

    /** kind 选项列表（面板下拉：三种协议全兼容，另加自动识别）
     *  ——reasonix 支持的 provider kind：openai（/chat/completions）、
     *  anthropic（/v1/messages）、responses（/responses）。 */
    private static final String[] KIND_OPTIONS = {"auto", "openai", "anthropic", "responses"};

    /** kind 值 → 下拉下标（0=auto；未知/空 → auto） */
    private int kindIndexOf(String kind) {
        if (kind != null) {
            for (int i = 1; i < KIND_OPTIONS.length; i++) {
                if (KIND_OPTIONS[i].equalsIgnoreCase(kind.trim())) return i;
            }
        }
        return 0;
    }

    /** 下拉下标 → kind 值（0 → "auto"） */
    private String kindAt(int pos) {
        return (pos > 0 && pos < KIND_OPTIONS.length) ? KIND_OPTIONS[pos] : "auto";
    }

    /** 生效协议：手动选定优先；否则按完整端点识别；再否则沿用 provider 原 kind，最后兜底 openai */
    private String effectiveKind(String selKind, EndpointSpec spec, String providerKind) {
        if (selKind != null && !"auto".equals(selKind)) return selKind;
        if (spec != null && spec.kind != null) return spec.kind;
        if (providerKind != null && !providerKind.trim().isEmpty()) return providerKind.trim();
        return "openai";
    }

    /** 识别「完整端点 URL」（如 https://host/v1/responses）：
     *  命中则给出协议 kind、精确 request_url，以及去掉端点路径后能供 reasonix 自行拼接的 base_url；
     *  不是完整端点（如 https://host/v1）时返回 null，保持原有 base_url 语义。
     *  各协议端点路径前缀不同（Anthropic 由 reasonix 补 /v1/messages，OpenAI/Responses 只用给定前缀），
     *  故 base_url 按 kind 分别回退，request_url 始终原样保留。 */
    private EndpointSpec parseEndpointUrl(String rawUrl) {
        String url = normalizeBaseUrl(rawUrl);
        if (url.isEmpty()) return null;
        String lower = url.toLowerCase();
        EndpointSpec s = new EndpointSpec();
        s.requestUrl = url;
        if (lower.endsWith("/chat/completions")) {
            s.kind = "openai";
            s.baseUrl = url.substring(0, url.length() - "/chat/completions".length());
        } else if (lower.endsWith("/responses")) {
            s.kind = "responses";
            s.baseUrl = url.substring(0, url.length() - "/responses".length());
        } else if (lower.endsWith("/messages")) {
            s.kind = "anthropic";
            String base = url.substring(0, url.length() - "/messages".length());
            // reasonix 的 anthropic provider 自己补 /v1/messages：base_url 去掉尾部 /v1，避免叠成 /v1/v1
            if (base.toLowerCase().endsWith("/v1")) base = base.substring(0, base.length() - "/v1".length());
            s.baseUrl = base;
        } else {
            return null;
        }
        if (s.baseUrl == null || s.baseUrl.isEmpty()) return null;   // 裸域名端点（无路径）不接受
        return s;
    }

    /** 探测/拉模型用的 base：完整端点回退到其 base_url（…/v1/responses → …/v1），否则原样归一 */
    private String probeBaseOf(String typedUrl) {
        EndpointSpec s = parseEndpointUrl(typedUrl);
        return (s != null) ? s.baseUrl : normalizeBaseUrl(typedUrl);
    }

    /** 由 base 推导 OpenAI 风格模型列表 URL：base 已含 /v1 时只补 /models */
    private String deriveModelsUrl(String base) {
        String b = normalizeBaseUrl(base);
        if (b.isEmpty()) return "";
        if (b.endsWith("/models")) return b;
        return b.endsWith("/v1") ? b + "/models" : b + "/v1/models";
    }

    /** 由 base 推导 Anthropic 风格模型列表 URL：base 已含 /v1 时只补 /models */
    private String deriveAnthropicModelsUrl(String base) {
        String b = normalizeBaseUrl(base);
        if (b.isEmpty()) return "";
        if (b.endsWith("/v1/models")) return b;
        if (b.endsWith("/models")) return b;
        return b.endsWith("/v1") ? b + "/models" : b + "/v1/models";
    }

    /** 由 base 推导 Anthropic 消息端点：base 已含 /v1 时用 /messages，否则用 /v1/messages */
    private String deriveMessagesUrl(String base) {
        String b = normalizeBaseUrl(base);
        if (b.isEmpty()) return "";
        if (b.endsWith("/v1/messages")) return b;
        if (b.endsWith("/messages")) return b;
        return b.endsWith("/v1") ? b + "/messages" : b + "/v1/messages";
    }

    /** 由 base 推导 OpenAI Responses 端点：base 已含 /v1 时用 /responses，否则用 /v1/responses */
    private String deriveResponsesUrl(String base) {
        String b = normalizeBaseUrl(base);
        if (b.isEmpty()) return "";
        if (b.endsWith("/responses")) return b;
        return b.endsWith("/v1") ? b + "/responses" : b + "/v1/responses";
    }

    /** 归一化 base_url：去首尾空白与尾部 /；无协议时默认补 https:// */
    private String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty() && !u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
        return u;
    }

    /** TOML 基本字符串转义（v2.0.25：用户输入含 " 或 \ 时直拼引号串会打坏整个
     *  config.toml——之后 parse 全面失败、所有 set* 写入失效）。 */
    private static String tomlEsc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 连通性探测：先 OpenAI 风格 GET {base}/models；401/403 直接判鉴权失败，
     *  其余（404/异常）再试 Anthropic 风格 POST {base}/v1/messages（max_tokens=1 最小请求）。
     *  耗时网络操作，必须在工作线程调用。 */
    private ProbeResult probeProvider(String baseUrl, String apiKey, String kind, String model) {
        ProbeResult r = new ProbeResult();
        String base = probeBaseOf(baseUrl);   // 完整端点（…/v1/responses 等）先回退到其 base
        if (base.isEmpty()) { r.detail = "API URL 为空"; return r; }
        boolean hasKey = apiKey != null && !apiKey.isEmpty();
        // ---- 第 1 步：OpenAI 风格 GET /models（base 未含 /v1 时再补 /v1/models 兜底）----
        java.util.List<String> modelsCandidates = new ArrayList<>();
        if (base.endsWith("/models")) {
            modelsCandidates.add(base);
        } else if (base.endsWith("/v1")) {
            modelsCandidates.add(base + "/models");
        } else {
            modelsCandidates.add(base + "/models");
            modelsCandidates.add(base + "/v1/models");   // 中转站/网关的模型列表多在 /v1 下
        }
        for (String modelsUrl : modelsCandidates) {
            java.net.HttpURLConnection c1 = null;
            try {
                c1 = (java.net.HttpURLConnection) new java.net.URL(modelsUrl).openConnection();
                c1.setConnectTimeout(8000);
                c1.setReadTimeout(8000);
                c1.setRequestMethod("GET");
                c1.setRequestProperty("Accept", "application/json");
                if (hasKey) c1.setRequestProperty("Authorization", "Bearer " + apiKey);
                int code = c1.getResponseCode();
                r.code = code; r.via = "/models"; r.reachable = true;
                if (code == 200) {
                    r.authOk = true;
                    try (java.io.InputStream in = c1.getInputStream()) {
                        r.models = parseModelIds(readAllStream(in));
                    }
                    r.detail = "GET /models 成功";
                    return r;
                }
                if (code == 401 || code == 403) {
                    r.authOk = false;
                    String err = excerptBody(c1.getErrorStream());
                    r.detail = !err.isEmpty() ? err : "HTTP " + code;
                    return r;   // 端点存在但鉴权失败：无需再探测
                }
                r.detail = "HTTP " + code;
            } catch (Exception e) {
                r.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c1 != null) c1.disconnect();
            }
        }
        // ---- 第 2 步：Anthropic 风格 POST /v1/messages（最小请求验证鉴权）----
        String msgUrl = deriveMessagesUrl(base);
        java.net.HttpURLConnection c2 = null;
        try {
            c2 = (java.net.HttpURLConnection) new java.net.URL(msgUrl).openConnection();
            c2.setConnectTimeout(8000);
            c2.setReadTimeout(15000);
            c2.setRequestMethod("POST");
            c2.setRequestProperty("Content-Type", "application/json");
            c2.setRequestProperty("anthropic-version", "2023-06-01");
            c2.setDoOutput(true);
            if (hasKey) {
                c2.setRequestProperty("x-api-key", apiKey);
                c2.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            String reqModel = (model != null && !model.isEmpty()) ? model : "deepseek-chat";
            String body = "{\"model\":\"" + reqModel + "\",\"max_tokens\":1,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            c2.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            c2.getOutputStream().close();
            int code = c2.getResponseCode();
            // 第 2 步覆盖状态码（v2.0.25：旧实现保留第 1 步的 r.code，第 2 步异常时
            // formatProbeResult 会拿第一步的 404 拼出错误的「鉴权失败」结论）
            r.code = code; r.via = "/v1/messages"; r.reachable = true;
            if (code == 200 || code == 201) {
                r.authOk = true;
                r.detail = "messages 接口正常";
                r.models = new ArrayList<>();
                r.models.add(reqModel);
            } else if (code == 400) {
                // 400 = 请求体/模型名问题：端点存在且鉴权通过（鉴权失败通常 401/403）
                r.authOk = true;
                String err = excerptBody(c2.getErrorStream());
                r.detail = !err.isEmpty() ? err : "HTTP 400（请检查默认模型名）";
            } else {
                if (code == 401 || code == 403) r.authOk = false;   // 仅明确鉴权失败才标 authOk=false
                String err = excerptBody(c2.getErrorStream());
                r.detail = "HTTP " + code + (!err.isEmpty() ? "：" + err : "");
            }
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            r.detail = (r.detail == null || r.detail.isEmpty()) ? msg : r.detail + "；" + msg;
        } finally {
            if (c2 != null) c2.disconnect();
        }
        // ---- 第 3 步：前两步 404/405 时兜底试 Responses 端点 POST {base}/responses ----
        if (!r.authOk && (r.code == 404 || r.code == 405)) {
            java.net.HttpURLConnection c3 = null;
            try {
                String respUrl = deriveResponsesUrl(base);
                c3 = (java.net.HttpURLConnection) new java.net.URL(respUrl).openConnection();
                c3.setConnectTimeout(8000);
                c3.setReadTimeout(15000);
                c3.setRequestMethod("POST");
                c3.setRequestProperty("Content-Type", "application/json");
                c3.setDoOutput(true);
                if (hasKey) c3.setRequestProperty("Authorization", "Bearer " + apiKey);
                String reqModel = (model != null && !model.isEmpty()) ? model : "deepseek-chat";
                String body = "{\"model\":\"" + reqModel + "\",\"input\":\"hi\",\"max_output_tokens\":16}";
                c3.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                c3.getOutputStream().close();
                int code = c3.getResponseCode();
                r.code = code; r.via = "/responses"; r.reachable = true;
                if (code == 200 || code == 201) {
                    r.authOk = true;
                    r.detail = "responses 接口正常";
                    r.models = new ArrayList<>();
                    r.models.add(reqModel);
                } else if (code == 400) {
                    // 400 = 请求体/模型名问题：端点存在且鉴权通过
                    r.authOk = true;
                    String err = excerptBody(c3.getErrorStream());
                    r.detail = !err.isEmpty() ? err : "HTTP 400（请检查默认模型名）";
                } else {
                    if (code == 401 || code == 403) r.authOk = false;
                    String err = excerptBody(c3.getErrorStream());
                    r.detail = "HTTP " + code + (!err.isEmpty() ? "：" + err : "");
                }
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                r.detail = (r.detail == null || r.detail.isEmpty()) ? msg : r.detail + "；" + msg;
            } finally {
                if (c3 != null) c3.disconnect();
            }
        }
        return r;
    }

    /** 把探测结果格式化为人读结论（✓/△/✗ 前缀，UI 直接展示） */
    private String formatProbeResult(ProbeResult r) {
        if (r == null) return "";
        if (!r.reachable) {
            return "✗ 连接失败：" + r.detail + "\n请检查网络、URL 拼写与协议（http/https）";
        }
        if (r.authOk && "/models".equals(r.via) && r.models != null && !r.models.isEmpty()) {
            return "✓ 连通正常，鉴权通过（GET /models，" + r.models.size() + " 个模型）";
        }
        if (r.authOk && "/v1/messages".equals(r.via)) {
            return "✓ 连通正常，鉴权通过（messages 接口" + (r.code == 400 ? "，请核对默认模型名" : "正常") + "）";
        }
        if (r.authOk && "/responses".equals(r.via)) {
            return "✓ 连通正常，鉴权通过（responses 接口" + (r.code == 400 ? "，请核对默认模型名" : "正常") + "）";
        }
        if (!r.authOk && (r.code == 429 || r.code >= 500)) {
            // v2.0.25：429/5xx 是服务端问题，不是鉴权失败——别让用户白排查 API Key
            return "△ 端点可达，但服务端返回 HTTP " + r.code + "（"
                    + (r.code == 429 ? "限流" : "服务端错误") + "）\n" + r.detail;
        }
        if (!r.authOk) {
            return "✗ 端点可达但鉴权失败（HTTP " + r.code + "）：API Key 无效或未填写\n" + r.detail;
        }
        if (r.code == 404 || r.code == 405) {
            return "△ 端点可达，但 /models、/v1/messages 与 /responses 均不可用（HTTP " + r.code + "）\n"
                    + "请确认 URL 路径：OpenAI 兼容填 …/v1，Anthropic 兼容填 …/anthropic，"
                    + "或直接粘贴完整端点（如 …/v1/responses）由面板自动拆分";
        }
        return "△ 端点可达（HTTP " + r.code + "），但未通过标准接口验证\n" + r.detail;
    }

    /** 读流为字符串（UTF-8）：经 InputStreamReader 解码（v2.0.25：旧实现按固定字节块
     *  new String，多字节 UTF-8 字符跨块会被切成 U+FFFD 乱码；且旧实现不关流） */
    private String readAllStream(java.io.InputStream in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (java.io.Reader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** 读响应错误体前 ~240 字符（供探测结果展示服务端报错原文）；自动关闭流 */
    private String excerptBody(java.io.InputStream in) {
        if (in == null) return "";
        try (java.io.InputStream fin = in) {
            String s = readAllStream(fin);
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() > 240 ? s.substring(0, 240) + "…" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /** 从响应 JSON 提取模型 id 列表（"id":"xxx"） */
    private List<String> parseModelIds(String body) {
        List<String> models = new ArrayList<>();
        if (body == null || body.isEmpty()) return models;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        while (m.find()) models.add(m.group(1));
        return models;
    }


    /** 写入/更新 .env 变量：保留已有其他变量，替换同名旧值，追加新变量 */
    private void upsertEnvVariable(File env, String varName, String value) {
        try {
            if (env == null) return;
            env.getParentFile().mkdirs();
            List<String> lines = env.exists()
                    ? new ArrayList<>(java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith(varName + "=") ||
                        lines.get(i).trim().equals(varName)) {
                    lines.set(i, varName + "=" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add(varName + "=" + value);
            java.nio.file.Files.write(env.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "upsert env variable failed", e);
        }
    }

    /** 追加一个 [[providers]] 块到 config.toml（末尾），返回是否成功 */
    private boolean addProviderToConfig(File conf, ProviderInfo p) {
        try {
            if (conf == null) return false;
            conf.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("\n[[providers]]\n");
            sb.append("name        = \"").append(tomlEsc(p.name)).append("\"\n");
            sb.append("kind        = \"").append(tomlEsc(p.kind == null || p.kind.isEmpty() ? "openai" : p.kind)).append("\"\n");
            sb.append("base_url    = \"").append(tomlEsc(p.baseUrl == null ? "" : p.baseUrl)).append("\"\n");
            // 完整端点（…/v1/responses 等）：精确请求 URL 交给 request_url，reasonix 不再补路径
            if (p.requestUrl != null && !p.requestUrl.isEmpty()) {
                sb.append("request_url = \"").append(tomlEsc(p.requestUrl)).append("\"\n");
            }
            sb.append("model       = \"").append(tomlEsc(p.model)).append("\"\n");
            sb.append("api_key_env = \"").append(tomlEsc(p.apiKeyEnv)).append("\"\n");
            // 静态模型列表持久化（v2.0.25：否则重开面板 parse 后丢失，每次都要重新联网拉取）
            if (p.models != null && !p.models.isEmpty()) {
                StringBuilder arr = new StringBuilder();
                for (String m : p.models) {
                    if (m == null || m.isEmpty()) continue;
                    if (arr.length() > 0) arr.append(", ");
                    arr.append('"').append(tomlEsc(m)).append('"');
                }
                if (arr.length() > 0) sb.append("models      = [").append(arr).append("]\n");
            }
            java.nio.file.Files.write(conf.toPath(),
                    ((conf.exists() ? "\n" : "") + sb.toString()).getBytes(StandardCharsets.UTF_8),
                    conf.exists()
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "add provider to config failed", e);
            return false;
        }
    }

    /** 从 config.toml 移除指定 provider 块（name 匹配），返回是否删除成功 */
    private boolean removeProviderFromConfig(File conf, ProviderInfo p) {
        try {
            if (conf == null || !conf.exists()) return false;
            if (p == null || p.name == null) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8));
            List<Integer> dropIdx = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (!t.startsWith("[[providers]]")) continue;
                // 块边界：下一个 [[providers]] 或任何 [section]（含 [x] 单括号开头）
                int j = i;
                boolean target = false, hasName = false;
                while (j < lines.size()) {
                    String tj = lines.get(j).trim();
                    if (j > i && (tj.startsWith("[[providers]]") || tj.startsWith("["))) {
                        break;   // 到达块尾
                    }
                    if (!hasName && tj.startsWith("name")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(tj);
                        if (m.find() && m.group(1).equals(p.name)) target = true;
                        hasName = true;
                    }
                    j++;
                }
                if (target) {
                    for (int k = i; k < j; k++) dropIdx.add(k);
                }
                i = j - 1;
            }
            if (dropIdx.isEmpty()) return false;
            java.util.Set<Integer> dropSet = new java.util.HashSet<>(dropIdx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (!dropSet.contains(i)) {
                    sb.append(lines.get(i));
                    if (i < lines.size() - 1) sb.append("\n");
                }
            }
            java.nio.file.Files.write(conf.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "remove provider from config failed", e);
            return false;
        }
    }

    /** 从 .env 删除指定变量行，返回是否成功 */
    private boolean removeEnvVariable(File env, String varName) {
        try {
            if (env == null || !env.exists() || varName == null || varName.isEmpty()) return false;
            List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(env.toPath(), StandardCharsets.UTF_8));
            boolean removed = false;
            List<String> out = new ArrayList<>();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith(varName + "=") || t.equals(varName)) { removed = true; continue; }
                out.add(l);
            }
            java.nio.file.Files.write(env.toPath(), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "remove env variable failed", e);
            return false;
        }
    }

    private void showApiKeyConfigDialog() {
        File rootfs = new File(getFilesDir(), "rootfs");
        File env = new File(new File(rootfs, "root/.reasonix"), ".env");
        File conf = new File(new File(rootfs, "root/.reasonix"), "config.toml");
        // Provider 列表：解析 config.toml [[providers]]（含 deepseek 及其他 AI）
        final List<ProviderInfo> providers = parseProviderInfos(conf);
        if (providers.isEmpty()) {
            // 解析不到（config.toml 未生成/被删）时用内置 DeepSeek 两档兜底
            providers.add(new ProviderInfo("deepseek-flash", "anthropic",
                    "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY", "deepseek-v4-flash"));
            providers.add(new ProviderInfo("deepseek-pro", "anthropic",
                    "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY", "deepseek-v4-pro"));
        }
        final String curModel = parseDefaultModel(conf);
        int curIdx = 0;
        List<String> display = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            ProviderInfo p = providers.get(i);
            String shown = (p.requestUrl != null && !p.requestUrl.isEmpty())
                    ? p.requestUrl : (p.baseUrl != null ? p.baseUrl : "");
            display.add(p.name + "（" + shown + "）");
            if (p.name.equals(curModel)) curIdx = i;
        }
        // API Key 输入框：随选中的 provider 切换 hint（api_key_env 变量名）与已存值
        EditText input = createDarkEditText("粘贴 API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip("选择 AI Provider 并填写其 API Key，可直接修改 API URL 切换端点/代理（保存写回 config.toml）。"
                + "支持 DeepSeek 及其他任意 OpenAI/Anthropic/Responses 兼容服务（Kimi、GLM、MiniMax、OpenRouter、各类中转站等）。"
                + "中转站给的完整端点可直接粘贴（如 https://host/v1/responses），面板会自动识别协议并写入精确 request_url。"
                + "建议先「测试连通性」确认 URL 与 Key 可用，再保存重启生效。"));
        // Provider 选择
        panel.addView(createDarkSectionTitle("选择 Provider"));
        final Spinner providerSpinner = new Spinner(this);
        // 深色适配：选中项白字、块状深灰底（与输入框一致），下拉项白字
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, display) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(spinnerAdapter);
        providerSpinner.setSelection(curIdx);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        spinnerLp.topMargin = dp(6);
        panel.addView(providerSpinner, spinnerLp);
        // API URL（base_url）：手动编辑所选 provider 的端点，保存后写回 config.toml（换端点/代理无需重建）
        panel.addView(createDarkSectionTitle("API URL（base_url 或完整端点）"));
        final EditText urlInput = createDarkEditText(
                "https://host/v1 或完整端点如 https://host/v1/responses",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        {
            ProviderInfo p0 = providers.get(Math.max(0, Math.min(curIdx, providers.size() - 1)));
            // 优先回显 request_url（精确端点），否则 base_url —— 不改动时保存即等价，不会误删已有 request_url
            urlInput.setText(p0.requestUrl != null && !p0.requestUrl.isEmpty()
                    ? p0.requestUrl
                    : (p0.baseUrl != null ? p0.baseUrl : ""));
        }
        addV(panel, urlInput, 4);
        // 协议（kind）：三协议全兼容下拉；默认「自动识别」按 URL 末尾路径推断
        panel.addView(createDarkSectionTitle("协议（kind）"));
        final Spinner kindSpinner = new Spinner(this);
        final List<String> kindDisplay = new ArrayList<>();
        kindDisplay.add("自动识别（按 URL 判断）");
        kindDisplay.add("openai（/chat/completions）");
        kindDisplay.add("anthropic（/v1/messages）");
        kindDisplay.add("responses（/responses）");
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, kindDisplay) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kindSpinner.setAdapter(kindAdapter);
        kindSpinner.setSelection(kindIndexOf(providers.get(Math.max(0, Math.min(curIdx, providers.size() - 1))).kind));
        LinearLayout.LayoutParams kindLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        kindLp.topMargin = dp(6);
        panel.addView(kindSpinner, kindLp);
        // API Key：填写所选 provider 的密钥（api_key_env 变量），保存后写入 .env
        panel.addView(createDarkSectionTitle("API Key"));
        addV(panel, input, 6);
        // 默认模型：来自 provider 块 models 列表，或联网拉取（失败不兜底，保持空列表）
        panel.addView(createDarkSectionTitle("默认模型"));
        final Spinner modelSpinner = new Spinner(this);
        final ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<String>()) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        modelLp.topMargin = dp(6);
        panel.addView(modelSpinner, modelLp);
        // 手动输入模型名：/models 不可用时兜底（保存时优先于下拉选择）
        panel.addView(createDarkSectionTitle("模型名（手动输入，可选）"));
        final EditText modelManualInput = createDarkEditText("留空则使用上方下拉选择的模型",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, modelManualInput, 4);
        // 操作行：测试连通性 + 刷新模型列表（并排，节省纵向空间）
        LinearLayout probeRow = new LinearLayout(this);
        probeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button testBtn = createDarkButton("测试连通性");
        Button refreshModelBtn = createDarkButton("刷新模型列表");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.rightMargin = dp(4);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp.leftMargin = dp(4);
        probeRow.addView(testBtn, tLp);
        probeRow.addView(refreshModelBtn, rLp);
        addV(panel, probeRow, 8);
        // 测试/刷新结果展示区（异步更新：绿=通过，黄=可达但异常，红=失败）
        final TextView probeStatus = new TextView(this);
        probeStatus.setTextSize(12);
        probeStatus.setLineSpacing(0, 1.3f);
        probeStatus.setVisibility(View.GONE);
        addV(panel, probeStatus, 6);
        // 测试连通性：用当前 URL + 输入框实时 Key（未保存也可测）探测端点与鉴权
        testBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            ProviderInfo p = providers.get(sel);
            String typedUrl = urlInput.getText().toString();
            String url = probeBaseOf(typedUrl);   // 完整端点回退到 base 后探测
            if (url.isEmpty()) {
                probeStatus.setVisibility(View.VISIBLE);
                probeStatus.setTextColor(0xFFFF6B6B);
                probeStatus.setText("✗ 请先填写 API URL");
                return;
            }
            final String fKind = effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()),
                    parseEndpointUrl(typedUrl), p.kind);
            String key = input.getText().toString().trim();
            if (key.isEmpty()) key = readApiKeyFromEnv(env, p.apiKeyEnv);
            String manual = modelManualInput.getText().toString().trim();
            String probeModel = !manual.isEmpty() ? manual
                    : (modelSpinner.getSelectedItem() != null ? modelSpinner.getSelectedItem().toString() : null);
            final String fKey = key, fModel = probeModel;
            testBtn.setEnabled(false);
            testBtn.setText("测试中…");
            probeStatus.setVisibility(View.VISIBLE);
            probeStatus.setTextColor(0xFFAAAAAA);
            probeStatus.setText("… 正在测试 " + url);
            final int reqId = ++modelReqSeq;   // 发起时取号；过期回调不再覆盖下拉
            new Thread(() -> {
                ProbeResult r = probeProvider(url, fKey, fKind, fModel);
                runOnUiThread(() -> {
                    testBtn.setEnabled(true);
                    testBtn.setText("测试连通性");
                    probeStatus.setVisibility(View.VISIBLE);
                    boolean pass = r.reachable && r.authOk;
                    probeStatus.setTextColor(pass ? 0xFF7CD97C : (r.reachable ? 0xFFFFD166 : 0xFFFF6B6B));
                    probeStatus.setText(formatProbeResult(r));
                    // 探测到模型列表：顺手填入模型下拉（省一次「刷新模型列表」）
                    if (r.models != null && !r.models.isEmpty() && reqId == modelReqSeq) {
                        modelAdapter.clear();
                        for (String s : r.models) modelAdapter.add(s);
                        modelAdapter.notifyDataSetChanged();
                        modelSpinner.setSelection(0);
                    }
                });
            }, "rx-probe").start();
        });
        // 刷新按钮：用「当前编辑中的 URL」重新拉取该 provider 的模型列表（未保存的修改同样生效）
        refreshModelBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            ProviderInfo p = providers.get(sel);
            String typedUrl = urlInput.getText().toString();
            ProviderInfo pe = new ProviderInfo(p.name,
                    effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()), parseEndpointUrl(typedUrl), p.kind),
                    probeBaseOf(typedUrl), p.apiKeyEnv, p.model, null);
            String manual = modelManualInput.getText().toString().trim();
            final String hintModel = (!manual.isEmpty() && pe.model != null && !pe.model.isEmpty() && !manual.equals(pe.model))
                    ? manual : pe.model;
            // 异步拉取，避免阻塞 UI
            final int reqId = ++modelReqSeq;   // 发起时取号；过期回调不再覆盖（防旧 provider 模型写脏 config）
            new Thread(() -> {
                String key = input.getText().toString().trim();
                if (key.isEmpty()) key = readApiKeyFromEnv(env, pe.apiKeyEnv);
                List<String> fetched = fetchModelsFromProvider(pe.baseUrl, key, pe.kind);
                // 不要兜底：拉取失败/无 /models 时保持空列表（可用下方手动输入模型名）
                final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
                runOnUiThread(() -> {
                    if (reqId != modelReqSeq) return;   // 过期结果丢弃
                    modelAdapter.clear();
                    for (String s : finalList) modelAdapter.add(s);
                    modelAdapter.notifyDataSetChanged();
                    // 尝试选中当前默认模型
                    int idx = 0;
                    if (hintModel != null) {
                        for (int i = 0; i < finalList.size(); i++) {
                            if (finalList.get(i).equals(hintModel)) { idx = i; break; }
                        }
                    }
                    modelSpinner.setSelection(idx);
                    // 拉取失败给出原因（旧实现静默清空下拉，401 时用户完全不知道为何没有模型）
                    if (finalList.isEmpty()) {
                        String why = lastModelFetchError;
                        probeStatus.setVisibility(View.VISIBLE);
                        if (why.contains("HTTP 401") || why.contains("HTTP 403")) {
                            probeStatus.setTextColor(0xFFFF6B6B);
                            probeStatus.setText("✗ 该端点的模型列表需要鉴权：请在上方 API Key 框填写后重试\n" + why);
                        } else if (why.isEmpty()) {
                            probeStatus.setTextColor(0xFFFFD166);
                            probeStatus.setText("△ 未获取到模型列表（端点可能无 /models 接口），可用「模型名（手动输入）」");
                        } else {
                            probeStatus.setTextColor(0xFFFFD166);
                            probeStatus.setText("△ 未获取到模型列表：\n" + why);
                        }
                    }
                });
            }, "rx-fetch-models").start();
        });
        // 切换 Provider：更新 hint（api_key_env 变量名）、已存值回显与 API URL 回显
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) {
                if (pos >= 0 && pos < providers.size()) {
                    ProviderInfo p = providers.get(pos);
                    String envName = (p.apiKeyEnv != null && !p.apiKeyEnv.isEmpty()) ? p.apiKeyEnv : "API_KEY";
                    input.setHint("粘贴 " + envName + "（" + p.name + "）");
                    String saved = readApiKeyFromEnv(env, envName);
                    input.setText(saved);
                    input.setSelection(saved.length());
                    // 同步回显该 provider 的 API URL（可手动修改后保存）：完整端点优先回显 request_url
                    urlInput.setText(p.requestUrl != null && !p.requestUrl.isEmpty()
                            ? p.requestUrl
                            : (p.baseUrl != null ? p.baseUrl : ""));
                    kindSpinner.setSelection(kindIndexOf(p.kind));   // 协议下拉跟随该 provider
                    modelManualInput.setText("");   // 换 provider 后手动模型名清空，避免误写入
                    // 加载该 provider 的模型列表（静态 models 优先，否则联网拉取，失败兜底）
                    loadModelsForProvider(p, env, modelAdapter, modelSpinner);
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        Button saveBtn = createDarkButton("保存");
        saveBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            final ProviderInfo p = providers.get(sel);
            final int fSel = sel;
            String key = input.getText().toString().trim();
            if (key.isEmpty()) {
                pushOutput("\r\n[请填写 " + (p.apiKeyEnv != null ? p.apiKeyEnv : "API") + " API Key]\r\n");
                return;
            }
            String envVar = (p.apiKeyEnv != null && !p.apiKeyEnv.isEmpty()) ? p.apiKeyEnv : "API_KEY";
            try {
                // 写入 .env 对应变量（保留其他 provider 的 key）
                upsertEnvVariable(env, envVar, key);
                // 端点/协议：识别完整端点后统一写回 base_url + request_url + kind
                //（换端点/代理/中转站无需重建 provider；中转站给的 …/v1/responses 会自动拆成 base + 精确 request_url）
                boolean urlOk = true;
                String typedUrl = urlInput.getText().toString();
                EndpointSpec spec = parseEndpointUrl(typedUrl);
                String effKind = effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()), spec, p.kind);
                String newBase = (spec != null) ? spec.baseUrl : normalizeBaseUrl(typedUrl);
                String newReq = (spec != null) ? spec.requestUrl : "";
                String oldReq = (p.requestUrl != null) ? p.requestUrl : "";
                boolean changed = !newBase.isEmpty()
                        && (!newBase.equals(normalizeBaseUrl(p.baseUrl))
                            || !newReq.equals(oldReq)
                            || !effKind.equalsIgnoreCase(p.kind == null ? "" : p.kind));
                if (changed) {
                    urlOk = setProviderEndpoint(conf, p.name, newBase, newReq, effKind);
                    p.baseUrl = newBase; p.requestUrl = newReq; p.kind = effKind;   // 同步内存，后续展示即时生效
                    if (fSel < display.size()) {
                        display.set(fSel, p.name + "（" + (newReq.isEmpty() ? newBase : newReq) + "）");
                        spinnerAdapter.notifyDataSetChanged();
                    }
                }
                // 同时写两层默认：顶层 default_model=provider 名 + provider 块 default=用户选中的模型
                // 模型来源：手动输入优先（/models 不可用兜底），其次下拉选择
                boolean modelOk = setDefaultModel(conf, p.name);
                String manual = modelManualInput.getText().toString().trim();
                String chosenModel;
                if (!manual.isEmpty()) {
                    chosenModel = manual;
                } else {
                    int mSel = modelSpinner.getSelectedItemPosition();
                    chosenModel = mSel >= 0 ? (String) modelSpinner.getItemAtPosition(mSel) : null;
                }
                if (chosenModel != null && !chosenModel.isEmpty()) {
                    // && 而非 ||（v2.0.25：|| 会把块内 default 写入失败掩盖成成功）
                    modelOk = modelOk && setProviderDefaultModel(conf, p.name, chosenModel);
                }
                Log.d(TAG, "API key + provider updated: " + p.name + " env=" + envVar
                        + " url=" + p.baseUrl + " urlOk=" + urlOk + " modelOk=" + modelOk);
                hidePanel();
                String msg = "\r\n[" + p.name + " API Key 已更新"
                        + (urlOk ? "" : "，URL 写入失败")
                        + (modelOk ? "，已切换默认模型 " + (chosenModel != null ? chosenModel : p.name)
                        : (conf.exists()
                            ? "，但默认模型写入失败（config.toml 写入异常）"
                            : "（config.toml 尚未生成，key 已写入 .env；重启后 reasonix 按内置默认读取）"))
                        + "，正在重启环境...]\r\n";
                pushOutput(msg);
                restartEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "save api key failed", e);
            }
        });
        addV(panel, saveBtn, 10);
        // 新增 Provider 独立按钮：点击直接进入子表单（不再混入 Spinner 选项，避免歧义）
        Button addBtn = createDarkButton("+ 新增 Provider…");
        addBtn.setOnClickListener(v -> showAddProviderForm(providers, env, conf));
        addV(panel, addBtn, 6);
        // 删除 Provider 独立按钮：移除当前选中的 provider（config.toml 对应块 + .env 变量）
        Button delBtn = createDarkButton("删除当前 Provider…");
        delBtn.setTextColor(0xFFFF6B6B);  // 红色警示
        delBtn.setOnClickListener(v -> {
            int sel = providerSpinner.getSelectedItemPosition();
            if (sel < 0 || sel >= providers.size()) return;
            final ProviderInfo p = providers.get(sel);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("删除 Provider")
                    .setMessage("确定删除「" + p.name + "」（" + p.baseUrl + "）？\n"
                            + "将从 config.toml 移除该 provider 块并删除 .env 中对应 API Key 变量。"
                            + (curModel.equals(p.name) ? "\n\n注意：这是当前默认 provider，删除后需重新选择。"
                            : ""))
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        // config 块删除成功即视为成功（v2.0.25：旧实现 env 变量缺失/文件不存在
                        // 时整体判失败——文件已删但 UI 不更新，且重开面板再删永远失败）
                        boolean cfgOk = removeProviderFromConfig(conf, p);
                        if (cfgOk) {
                            boolean envOk = removeEnvVariable(env, p.apiKeyEnv);
                            providers.remove(sel);
                            pushOutput("\r\n[Provider 已删除：" + p.name
                                    + (envOk ? "]\r\n" : "]（.env 变量清理失败，可忽略）\r\n"));
                            if (curModel.equals(p.name)) {
                                // 删除的是当前默认：重置 default_model 为剩余的第一个（无则留空）
                                boolean ok = false;
                                if (!providers.isEmpty()) ok = setDefaultModel(conf, providers.get(0).name);
                                pushOutput(ok ? "\r\n[默认 Provider 已切换为 " + providers.get(0).name + "]\r\n"
                                        : "\r\n[config.toml 无剩余 provider（需重启后重新配置）]\r\n");
                            }
                            showApiKeyConfigDialog();  // 重开面板刷新列表
                        } else {
                            pushOutput("\r\n[Provider 删除失败（config.toml 写入异常）]\r\n");
                        }
                    })
                    .show();
        });
        addV(panel, delBtn, 6);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("API Key", panel, null);
    }

    /** 「+ 新增 Provider」子表单：填写 name/kind/base_url/model/api_key_env 并保存到 config.toml */
    private void showAddProviderForm(final List<ProviderInfo> providers, final File env, final File conf) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.addView(createDarkTip("新增 AI Provider（OpenAI / Anthropic / Responses 兼容端点）。"
                + "base_url 处可直接粘贴中转站给的完整端点（如 https://host/v1/responses），"
                + "保存时自动识别协议并拆成 base_url + 精确 request_url。"
                + "模型列表需要鉴权的端点（如 packyapi）请先填下方 API Key 再点「拉取模型」。"
                + "填写后保存到 config.toml，随后在上一页选择该 Provider 填入 API Key。"));
        panel.addView(createDarkSectionTitle("Provider 名称"));
        final EditText nameInput = createDarkEditText("如 my-ai / kimi / glm（default_model 引用名）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, nameInput, 4);
        panel.addView(createDarkSectionTitle("kind（协议）"));
        final Spinner kindSpinner = new Spinner(this);
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>(java.util.Arrays.asList(
                "自动识别（按 URL 判断）", "openai（/chat/completions）",
                "anthropic（/v1/messages）", "responses（/responses）"))) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kindSpinner.setAdapter(kindAdapter);
        LinearLayout.LayoutParams addKindLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addKindLp.topMargin = dp(6);
        panel.addView(kindSpinner, addKindLp);
        panel.addView(createDarkSectionTitle("base_url（API 端点）"));
        final EditText urlInput = createDarkEditText(
                "https://api.moonshot.cn/v1；Anthropic 兼容 https://api.deepseek.com/anthropic；或完整端点 https://host/v1/responses",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addV(panel, urlInput, 4);
        panel.addView(createDarkSectionTitle("默认模型"));
        final Spinner modelSpinner = new Spinner(this);
        final ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<String>()) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setBackgroundColor(0xFF1A1A1A);
                return tv;
            }
        };
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        modelLp.topMargin = dp(6);
        panel.addView(modelSpinner, modelLp);
        panel.addView(createDarkSectionTitle("api_key_env（.env 变量名）"));
        final EditText envInput = createDarkEditText("如 KIMI_API_KEY（大写字母数字下划线）",
                InputType.TYPE_CLASS_TEXT);
        addV(panel, envInput, 4);
        // API Key：很多中转站/网关的模型列表与请求都要鉴权（packyapi 无 key 直接 401），
        // 表单内先填才能「拉取模型」；保存 Provider 时一并写入 .env（留空则只登记变量名）
        panel.addView(createDarkSectionTitle("API Key（保存时写入 .env）"));
        final EditText keyInput = createDarkEditText("粘贴该端点的 API Key（拉取模型/连通性测试需要）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addV(panel, keyInput, 4);
        // 操作行：「拉取模型」+「测试连通性」并排；结果共用下方状态区
        LinearLayout probeRow = new LinearLayout(this);
        probeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pullBtn = createDarkButton("拉取模型");
        Button testBtn = createDarkButton("测试连通性");
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pLp.rightMargin = dp(4);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(4);
        probeRow.addView(pullBtn, pLp);
        probeRow.addView(testBtn, tLp);
        addV(panel, probeRow, 4);
        // 状态展示区（拉取/测试结果；绿=通过，黄=可达但异常，红=失败）
        final TextView probeStatus = new TextView(this);
        probeStatus.setTextSize(12);
        probeStatus.setLineSpacing(0, 1.3f);
        probeStatus.setVisibility(View.GONE);
        addV(panel, probeStatus, 6);
        // 「测试连通性」：用当前填写的 base_url + .env 已存 key（可能为空）探测端点与鉴权
        testBtn.setOnClickListener(v -> {
            String typedUrl = urlInput.getText().toString();
            String baseUrl = probeBaseOf(typedUrl);
            String envVar = envInput.getText().toString().trim();
            String kind = effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()),
                    parseEndpointUrl(typedUrl), null);
            if (baseUrl.isEmpty()) {
                probeStatus.setVisibility(View.VISIBLE);
                probeStatus.setTextColor(0xFFFF6B6B);
                probeStatus.setText("✗ 请先填写 base_url");
                return;
            }
            // 表单填了就用表单值，否则回退 .env 已存值（final：下方工作线程 lambda 要用）
            String formKey = keyInput.getText().toString().trim();
            final String key = !formKey.isEmpty() ? formKey : readApiKeyFromEnv(env, envVar);
            testBtn.setEnabled(false);
            testBtn.setText("测试中…");
            probeStatus.setVisibility(View.VISIBLE);
            probeStatus.setTextColor(0xFFAAAAAA);
            probeStatus.setText("… 正在测试 " + baseUrl);
            final int reqId = ++modelReqSeq;   // 过期探测结果不覆盖下拉
            new Thread(() -> {
                ProbeResult r = probeProvider(baseUrl, key, kind, null);
                runOnUiThread(() -> {
                    testBtn.setEnabled(true);
                    testBtn.setText("测试连通性");
                    probeStatus.setVisibility(View.VISIBLE);
                    boolean pass = r.reachable && r.authOk;
                    probeStatus.setTextColor(pass ? 0xFF7CD97C : (r.reachable ? 0xFFFFD166 : 0xFFFF6B6B));
                    probeStatus.setText(formatProbeResult(r));
                    // 探测到模型列表：填入模型下拉（等效「拉取模型」）
                    if (r.models != null && !r.models.isEmpty() && reqId == modelReqSeq) {
                        modelAdapter.clear();
                        for (String s : r.models) modelAdapter.add(s);
                        modelAdapter.notifyDataSetChanged();
                        modelSpinner.setSelection(0);
                    }
                });
            }, "rx-probe-new").start();
        });
        // 「拉取模型」：用 base_url + 表单里填的 API Key（未填则回退 .env 已存值）联网列出可选模型
        pullBtn.setOnClickListener(v -> {
            String typedUrl = urlInput.getText().toString();
            String baseUrl = probeBaseOf(typedUrl);   // 与 testBtn 一致（完整端点回退 + 尾斜杠/空格归一）
            String envVar = envInput.getText().toString().trim();
            final String typedKey = keyInput.getText().toString().trim();   // View 只能在 UI 线程读
            String kind = effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()),
                    parseEndpointUrl(typedUrl), null);
            if (baseUrl.isEmpty() || envVar.isEmpty()) {
                probeStatus.setVisibility(View.VISIBLE);
                probeStatus.setTextColor(0xFFFFD166);
                probeStatus.setText("△ 请先填写 base_url 与 api_key_env，再拉取模型");
                return;
            }
            pullBtn.setEnabled(false);
            pullBtn.setText("拉取中…");
            final int reqId = ++modelReqSeq;   // 过期拉取结果不覆盖下拉
            new Thread(() -> {
                String key = !typedKey.isEmpty() ? typedKey : readApiKeyFromEnv(env, envVar);
                List<String> fetched = fetchModelsFromProvider(baseUrl, key, kind);
                // 不要兜底：拉取失败/无 /models 时保持空列表（不显示任何模型）
                final List<String> finalList = (fetched != null) ? fetched : new ArrayList<String>();
                runOnUiThread(() -> {
                    pullBtn.setEnabled(true);
                    pullBtn.setText("拉取模型");
                    if (reqId != modelReqSeq) return;   // 过期结果丢弃
                    modelAdapter.clear();
                    for (String s : finalList) modelAdapter.add(s);
                    modelAdapter.notifyDataSetChanged();
                    modelSpinner.setSelection(0);
                    probeStatus.setVisibility(View.VISIBLE);
                    if (finalList.isEmpty()) {
                        // 失败原因直接展示（旧实现只会说「未能获取模型列表」，401 无从定位）
                        String why = lastModelFetchError;
                        if (why.contains("HTTP 401") || why.contains("HTTP 403")) {
                            probeStatus.setTextColor(0xFFFF6B6B);
                            probeStatus.setText("✗ 该端点的模型列表需要鉴权：请在上方填写 API Key 后重试\n" + why);
                        } else if (why.isEmpty()) {
                            probeStatus.setTextColor(0xFFFFD166);
                            probeStatus.setText("△ 未能获取模型列表（端点可能无 /models 接口），可保存后用「手动输入模型名」");
                        } else {
                            probeStatus.setTextColor(0xFFFFD166);
                            probeStatus.setText("△ 未能获取模型列表：\n" + why + "\n可保存后用「手动输入模型名」");
                        }
                    } else {
                        probeStatus.setTextColor(0xFF7CD97C);
                        probeStatus.setText("✓ 拉取成功，" + finalList.size() + " 个模型可选");
                    }
                });
            }, "rx-pull-models").start();
        });
        Button okBtn = createDarkButton("保存 Provider");
        okBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String typedUrl = urlInput.getText().toString().trim();
            String envVar = envInput.getText().toString().trim();
            // 完整端点（…/v1/responses 等）：自动识别协议并拆成 base_url + 精确 request_url
            EndpointSpec spec = parseEndpointUrl(typedUrl);
            String kind = effectiveKind(kindAt(kindSpinner.getSelectedItemPosition()), spec, null);
            String baseUrl = (spec != null) ? spec.baseUrl : normalizeBaseUrl(typedUrl);
            String reqUrl = (spec != null) ? spec.requestUrl : "";
            // 默认模型：优先取 Spinner 选中项；未拉取时以 provider 名为默认（reasonix 以 provider 名解析）
            String model = modelSpinner.getSelectedItem() != null
                    ? modelSpinner.getSelectedItem().toString().trim() : "";
            if (name.isEmpty() || baseUrl.isEmpty() || envVar.isEmpty()) {
                pushOutput("\r\n[请填写 Provider 名称、base_url 与 api_key_env]\r\n");
                return;
            }
            // env 变量名格式校验（v2.0.25：非法字符会打坏 .env 的 KEY=VALUE 结构）
            if (!envVar.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                pushOutput("\r\n[api_key_env 仅允许字母/数字/下划线且不以数字开头]\r\n");
                return;
            }
            if (model.isEmpty()) model = name;
            ProviderInfo np = new ProviderInfo(name, kind, baseUrl, envVar, model);
            np.requestUrl = reqUrl;
            // 把拉取到的模型列表一并写入 provider 块（主面板可直接选中，无需再联网）
            np.models = new ArrayList<>();
            for (int i = 0; i < modelAdapter.getCount(); i++) {
                String s = modelAdapter.getItem(i);
                if (s != null && !s.isEmpty()) np.models.add(s);
            }
            if (addProviderToConfig(conf, np)) {
                // 表单里填了 API Key 就直接写入 .env（留空则只登记变量名，回主面板再填）
                upsertEnvVariable(env, envVar, keyInput.getText().toString().trim());
                providers.add(np);
                pushOutput("\r\n[Provider 已添加：" + name + "（" + (reqUrl.isEmpty() ? baseUrl : reqUrl)
                        + "，kind=" + kind + "），回到上一页选择并填写 API Key]\r\n");
                // 重开面板（选中新 provider）
                showApiKeyConfigDialog();
            } else {
                pushOutput("\r\n[Provider 写入 config.toml 失败]\r\n");
            }
        });
        addV(panel, okBtn, 10);
        showPanel("新增 Provider", panel, null);
    }

    /** 从 Go 二进制提取模块版本（buildinfo：`mod\t...\tvX.Y.Z`，位于文件尾部） */
    private String extractReasonixVersion(File bin) {
        if (bin == null || !bin.exists()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(bin, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - (2L * 1024 * 1024));   // Go buildinfo 距文件尾可达 1.5MB+，读 2MB
            raf.seek(start);
            byte[] tail = new byte[(int) (len - start)];
            raf.readFully(tail);
            String s = new String(tail, StandardCharsets.ISO_8859_1);
            int idx = s.indexOf("mod\t");
            if (idx >= 0) {
                String mod = s.substring(idx, Math.min(s.length(), idx + 150));
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("mod\\t\\S+\\tv([\\d.]+[\\w.-]*)").matcher(mod);
                if (m.find()) return "v" + m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "extract version failed", e);
        }
        return null;
    }

    /** DS2API 网关面板：应用内嵌 WebView 打开 http://127.0.0.1:5001/admin/ 管理页
     *  （无需额外安装 DS2API App；若服务未启动则显示提示）。 */
    /** DS2API 网关（最简化 + 可靠）：root 直控（不依赖 reasonix 环境是否运行）。
     *  状态/启停都走 su + chroot（proot/chroot 两种模式通用，挂载幂等），不再经 guest .adb-cmd 桥。 */
    private void showDs2ApiDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, dp(12));

        panel.addView(createDarkTip("DS2API 网关（本地 OpenAI/Claude 兼容中转，已内置）\n"
                + "127.0.0.1:5001 · 管理密钥 rsxm-ds2api-admin · root 直控，环境未运行也能启停。"));

        // 状态行：host 侧 pgrep（/proc 共享，chroot/proot 内进程同样可见）
        final TextView status = createDarkResult();
        status.setMaxLines(4);
        addV(panel, status, 8);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                new Thread(() -> {
                    String out = execRootCommand("pgrep -x ds2api >/dev/null 2>&1 && echo RUNNING || echo NOT_RUNNING", 6);
                    boolean run = out != null && out.contains("RUNNING");
                    final boolean fRun = run;
                    runOnUiThread(() -> status.setText(fRun
                            ? "● 运行中：127.0.0.1:5001（管理台 /admin/）\n  启动前记得先「停止」清残留"
                            : "○ 未运行（点下方「启动」即可，无需等待环境）"));
                }, "ds2-status").start();
            }
        };
        refresh.run();

        // 启动 / 停止
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = createDarkButton("启动");
        Button stopBtn = createDarkButton("停止");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bl.rightMargin = dp(8);
        ctrl.addView(startBtn, bl);
        ctrl.addView(stopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addV(panel, ctrl, 10);

        // 启动：写 launch 脚本到 rootfs，root 挂载幂等 + chroot 内 nohup 启动，2 步校验
        startBtn.setOnClickListener(v -> {
            startBtn.setEnabled(false);
            new Thread(() -> {
                try {
                    File rootfs = new File(getFilesDir(), "rootfs");
                    File launch = new File(new File(rootfs, "root/ds2api"), "launch.sh");
                    launch.getParentFile().mkdirs();
                    String sh = "#!/bin/sh\n"
                            + "pkill -9 -x ds2api 2>/dev/null\n"
                            + "sleep 0.5\n"
                            + "export HOME=/root\n"
                            + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                            + "export TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp TMP=/tmp\n"
                            + "export NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost\n"
                            + "export PORT=5001\n"
                            + "export DS2API_ADMIN_KEY=rsxm-ds2api-admin\n"
                            + "export DS2API_STATIC_ADMIN_DIR=/usr/local/ds2api/static/admin\n"
                            + "export DS2API_CONFIG_PATH=/root/ds2api/config.json\n"
                            + "mkdir -p /root/ds2api && cd /root/ds2api\n"
                            + "/usr/local/ds2api/ds2api >/root/ds2api/ds2api.log 2>&1 &\n"
                            + "sleep 2\n"
                            + "pgrep -x ds2api >/dev/null 2>&1 && echo STARTED || echo FAILED\n";
                    java.nio.file.Files.write(launch.toPath(), sh.getBytes(StandardCharsets.UTF_8));
                    String R = rootfs.getAbsolutePath();
                    String pre = "R=" + R + "; mkdir -p $R/proc $R/dev $R/sys; "
                            + "mount --bind /proc $R/proc 2>/dev/null; "
                            + "mount --bind /dev $R/dev 2>/dev/null; "
                            + "mount --bind /sys $R/sys 2>/dev/null; ";
                    String out = execRootCommand(pre + "chroot $R /bin/sh /root/ds2api/launch.sh", 15);
                    boolean ok = out != null && out.contains("STARTED");
                    if (!ok) {
                        // 兜底链 A：root 缺席时走应用内 proot 直启——无 root 也必须能启动 DS2API。
                        // 做法：复用已存在的 proot 环境（若 entry.sh 循环在跑）经 guest 桥启动；
                        // 若桥不可用（.adb-out 无 __DONE__），直接 app 侧用 ProcessBuilder 再起一套
                        // proot 一次性会话执行 launch.sh（与主环境并行，不冲突——仅持 ds2api）。
                    }
                    boolean run = runOnUiThreadCheck();
                    if (!ok && !run) {
                        // A) guest 桥（环境在跑时可靠）
                        out = executeInGuest(
                                "cd /root/ds2api && export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                                        + "TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp TMP=/tmp "
                                        + "NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost "
                                        + "PORT=5001 DS2API_ADMIN_KEY=rsxm-ds2api-admin DS2API_STATIC_ADMIN_DIR=/usr/local/ds2api/static/admin DS2API_CONFIG_PATH=/root/ds2api/config.json && "
                                        + "nohup /usr/local/ds2api/ds2api >/root/ds2api/ds2api.log 2>&1 & sleep 2; "
                                        + "pgrep -x ds2api >/dev/null 2>&1 && echo STARTED || echo BRIDGE_NO", 20);
                        ok = out != null && out.contains("STARTED");
                        run = ok;
                    }
                    if (!ok && !run) {
                        // B) app 侧一次性 proot 会话执行 launch.sh（不依赖 root/环境）
                        String one = startOneShotProotDs2api(rootfs);
                        ok = one != null && (one.contains("STARTED"));
                        run = ok;
                    }
                    // 启动链反馈（含 BRIDGE_NO）不完全可信——以「确实在运行」为唯一权威：
                    // 三链结束后统一 pgrep 复核。
                    boolean finallyRunning = runOnUiThreadCheck();
                    final boolean fOk = ok || run || finallyRunning;
                    final String fDetail = fOk ? null
                            : (out == null ? "(root 不可用且 proot 兜底失败)" : out);
                    runOnUiThread(() -> {
                        if (fOk) {
                            status.setText("● 运行中：127.0.0.1:5001（管理台 /admin/）");
                        } else {
                            status.setText("启动失败：" + fDetail);
                        }
                    });
                } catch (Exception e) {
                    final String msg = String.valueOf(e);
                    runOnUiThread(() -> status.setText("启动异常：" + msg));
                } finally {
                    runOnUiThread(() -> startBtn.setEnabled(true));
                }
            }, "ds2-start").start();
        });

        stopBtn.setOnClickListener(v -> {
            stopBtn.setEnabled(false);
            new Thread(() -> {
                // 链1：root pkill（/proc 共享，chroot/proot 全命中）
                String out = execRootCommand("pkill -x ds2api 2>/dev/null; sleep 1; "
                        + "pgrep -x ds2api >/dev/null 2>&1 && pkill -9 -x ds2api 2>/dev/null; sleep 0.5; "
                        + "pgrep -x ds2api >/dev/null 2>&1 && echo STILL_RUNNING || echo STOPPED", 10);
                boolean stopped = out != null && out.contains("STOPPED");
                // 链2：root 不可用 → 走 guest 桥（环境在跑时）或一次性 proot 执行 kill 脚本
                if (!stopped) {
                    out = executeInGuest(
                            "pkill -x ds2api 2>/dev/null; sleep 1; "
                            + "pgrep -x ds2api >/dev/null 2>&1 && pkill -9 -x ds2api 2>/dev/null; sleep 0.5; "
                            + "pgrep -x ds2api >/dev/null 2>&1 && echo STILL_RUNNING || echo STOPPED", 25);
                    stopped = out != null && out.contains("STOPPED");
                    if (!stopped) {
                        // 一次性 proot 需要 rootfs 里有 kill 工具脚本/proc 绑定已在 ex_cmd 中
                        String kill = startOneShotProotCmd(
                                "pkill -x ds2api 2>/dev/null; sleep 1; "
                                + "pgrep -x ds2api >/dev/null 2>&1 && pkill -9 -x ds2api 2>/dev/null; sleep 0.5; "
                                + "pgrep -x ds2api >/dev/null 2>&1 && echo STILL_RUNNING || echo STOPPED", new File(getFilesDir(), "rootfs"));
                        stopped = kill != null && kill.contains("STOPPED");
                    }
                }
                final boolean fStopped = stopped;
                final String fOut = out;
                runOnUiThread(() -> {
                    status.setText(fStopped ? "○ 已停止（环境重启会自动拉起）" : "停止失败：" + fOut);
                    stopBtn.setEnabled(true);
                });
            }, "ds2-stop").start();
        });

        // 管理台：系统浏览器打开（最简化，替代内嵌 WebView）
        Button openBtn = createDarkButton("打开管理台（浏览器）");
        openBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:5001/admin/")));
            } catch (Exception e) {
                Log.w(TAG, "open ds2api browser failed", e);
            }
        });
        addV(panel, openBtn, 10);

        showPanel("DS2API 网关", panel, null);
    }

    /**
     * app 侧一次性 proot 会话启动 DS2API（无需 root）：
     * 用 APK 内置的 proot/loader（nativeLibraryDir）以 -0 挂 rootfs 起 /bin/sh 执行 launch.sh。
     * launch.sh 自身含 pkill 清残留 + nohup + pgrep 自检；proot 退出不影响已 nohup 的 ds2api
     * （proot 是 ptrace 载入，子进程 nohup 后由 init 收养继续运行）。
     */
    private String startOneShotProotDs2api(File rootfs) {
        // launch.sh 内已含启动+自检 echo STARTED
        return startOneShotProotCmd(null, rootfs);
    }

    /** app 侧一次性 proot 会话执行 shell 命令（无需 root）。
     *  guestCmd 为空时执行 /root/ds2api/launch.sh（启动 DS2API）。
     *  proot 退出不影响已 nohup 的进程（由 init 收养）。 */
    private String startOneShotProotCmd(String guestCmd, File rootfs) {
        try {
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            List<String> cmd = new ArrayList<>();
            cmd.add(nativeLibDir + "/proot.so");
            cmd.add("-0");
            cmd.add("-r"); cmd.add(rootfs.getAbsolutePath());
            cmd.add("-b"); cmd.add("/dev");
            cmd.add("-b"); cmd.add("/proc");
            cmd.add("-b"); cmd.add("/sys");
            cmd.add("-w"); cmd.add("/root");
            cmd.add("/bin/sh"); cmd.add("-c");
            cmd.add(guestCmd == null ? "/root/ds2api/launch.sh" : guestCmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("PROOT_LOADER", nativeLibDir + "/loader.so");
            pb.environment().put("TMPDIR", nativeLibDir);
            pb.environment().put("PROOT_TMP_DIR", getFilesDir().getAbsolutePath());
            Process p = pb.start();
            // v2.0.25 修复死锁：旧实现 deadline 只在 in.read() 返回后检查——proot 启动后
            // 无输出且不退出时 read 永久阻塞，20s 兜底失效（ds2 线程永久卡死、按钮锁死）。
            // 改为后台排水线程 + 主调用线程限时 waitFor，超时强杀。
            final StringBuilder sb = new StringBuilder();
            Thread drainer = new Thread(() -> {
                try (java.io.InputStream din = p.getInputStream()) {
                    byte[] dbuf = new byte[4096];
                    int n;
                    while ((n = din.read(dbuf)) > 0) {
                        synchronized (sb) { sb.append(new String(dbuf, 0, n, StandardCharsets.UTF_8)); }
                    }
                } catch (Exception ignored) {}
            }, "oneshot-drain");
            drainer.setDaemon(true);
            drainer.start();
            boolean exited = false;
            try { exited = p.waitFor(20, TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (!exited) p.destroyForcibly();
            drainer.join(3000);   // 排水线程最多再等 3s 收尾
            String result;
            synchronized (sb) { result = sb.toString(); }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "one-shot proot exec failed", e);
            return null;
        }
    }

    private boolean runOnUiThreadCheck() {
        String out = execRootCommand("pgrep -x ds2api >/dev/null 2>&1 && echo RUNNING || echo NO", 6);
        return out != null && out.contains("RUNNING");
    }

    /** 轻提示：操作结果的短时 Toast 反馈（不占用面板常驻状态行） */
    private void showToast(String msg) {
        runOnUiThread(() -> {
            try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
            catch (Exception ignored) {}
        });
    }



    /** 快捷键入口：侧滑菜单「快捷键」点击切换悬浮工具栏显示/隐藏。
     *  工具栏内置于 WebView（index.html），九键（↑ PgUp ↓ / PgDn Esc Tab /
     *  Ctrl+C Enter Shift+Tab）直接写终端通道，可拖拽定位、右上角 ✕ 关闭。
     *  此处只调用 JS 暴露的 showKeysToolbar/hideKeysToolbar 切换显示状态，
     *  不再弹系统 PopupWindow（避免遮挡终端与输入）。 */
    private void toggleKeysToolbar() {
        if (webView == null) return;
        // 记忆当前显示状态：切换需反向（显示→隐藏）
        keysToolbarVisible = !keysToolbarVisible;
        String js = keysToolbarVisible ? "window.showKeysToolbar&&window.showKeysToolbar();"
                : "window.hideKeysToolbar&&window.hideKeysToolbar();";
        ui.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
        showToast(keysToolbarVisible ? "快捷键工具栏已显示" : "快捷键工具栏已隐藏");
    }

    /* ==================== Serve 模式（reasonix serve 无头 HTTP 引擎） ==================== */

    /** Serve 面板存活标记：面板关闭时轮询线程退出 */
    private final java.util.concurrent.atomic.AtomicBoolean servePanelActive =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 正在生成中（submit 后到 running=false 之间） */
    private volatile boolean serveTurnBusy = false;
    /** 面板控件引用（showServeDialog 每次重写；轮询/点击回调据此刷新） */
    private TextView serveStatusView;
    private LinearLayout serveHistoryBox;
    private TextView serveTodoView;
    private LinearLayout serveCkBox;
    private EditText serveInputField;

    /** 构造一个指向当前引擎的客户端（按 port-file 推断端口，token 从持久文件读） */
    private ReasonixServe serveClient() {
        int port = ReasonixServe.readBoundPort(getFilesDir());
        if (port <= 0) port = 8787;
        return new ReasonixServe(port, ReasonixServe.readToken(getFilesDir()));
    }

    private void showServeDialog() {
        servePanelActive.set(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));

        panel.addView(createDarkTip("Serve 模式：reasonix serve 无头 HTTP 引擎（JSON 接口，无 TUI 依赖）。\n"
                + "GUI 会话数据自动适配契约：优先 transcript 投影；reasonix 1.38/1.39 无此契约时"
                + "自动回退 /events + /history（本版内置 1.39.3 即回退模式）。checkpoint 回溯/审批切换可用。"));

        final TextView status = createDarkResult();
        status.setMaxLines(3);
        addV(panel, status, 6);
        status.setText("… 检测 serve 引擎状态");
        serveStatusView = status;

        // 启停行
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = createDarkButton("启动");
        startBtn.setOnClickListener(v -> serveStart());
        Button stopBtn = createDarkButton("停止");
        stopBtn.setOnClickListener(v -> {
            new Thread(() -> executeInGuest(serveStopCommand() + "; echo OK", 6), "serve-stop").start();
            showToast("已发送停止指令");
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bl.rightMargin = dp(6);
        ctrl.addView(startBtn, bl);
        ctrl.addView(stopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addV(panel, ctrl, 6);

        // 输入 + 发送 / 停止生成 / 新会话
        final EditText input = createDarkEditText("对 reasonix 说点什么…（Enter 发送）", InputType.TYPE_CLASS_TEXT);
        serveInputField = input;
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                serveSubmit();
                return true;
            }
            return false;
        });
        addV(panel, input, 4);
        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        Button sendBtn = createDarkButton("发送");
        sendBtn.setOnClickListener(v -> serveSubmit());
        Button cancelBtn = createDarkButton("中断");
        cancelBtn.setOnClickListener(v -> serveCancel());
        Button newBtn = createDarkButton("新会话");
        newBtn.setOnClickListener(v -> serveNewSession());
        sendRow.addView(sendBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(4);
        sendRow.addView(cancelBtn, clp);
        addV(panel, sendRow, 6);
        addV(panel, newBtn, 6);

        // 审批模式切换（/tool-approval-mode）
        panel.addView(createDarkSectionTitle("工具审批模式"));
        LinearLayout approveRow = new LinearLayout(this);
        approveRow.setOrientation(LinearLayout.HORIZONTAL);
        String[][] modes = {{"manual", "手动"}, {"ask", "询问"}, {"auto", "自动"},
                {"acceptEdits", "自编辑"}, {"bypassPermissions", "YOLO"}};
        for (String[] m : modes) {
            Button mb = createDarkButton(m[1]);
            mb.setTextSize(12);
            mb.setOnClickListener(v -> serveSetApprovalMode(m[0]));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.rightMargin = dp(4);
            approveRow.addView(mb, mlp);
        }
        addV(panel, approveRow, 6);

        // 历史区（serve transcript 投影渲染；不依赖 TUI）
        panel.addView(createDarkSectionTitle("对话历史"));
        LinearLayout historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        serveHistoryBox = historyBox;
        addV(panel, historyBox, 2);

        // Todos
        panel.addView(createDarkSectionTitle("Todos"));
        final TextView todoView = createDarkResult();
        serveTodoView = todoView;
        addV(panel, todoView, 2);
        todoView.setText("（读取中…）");

        // Checkpoints
        panel.addView(createDarkSectionTitle("Checkpoints（点按回溯）"));
        LinearLayout ckBox = new LinearLayout(this);
        ckBox.setOrientation(LinearLayout.VERTICAL);
        serveCkBox = ckBox;
        addV(panel, ckBox, 2);
        ckBox.addView(createDarkTip("（读取中…）"));

        showPanel("Serve 模式", panel, () -> servePanelActive.set(false));

        // 首次加载：状态探测 + 就绪则拉历史/extras
        new Thread(() -> {
            ReasonixServe client = serveClient();
            String st = client.status();
            if (st != null) {
                runOnUiThread(() -> serveRenderStatus(st));
                serveReloadHistory(false);
                serveRenderExtras();
            } else if (servePanelActive.get()) {
                runOnUiThread(() -> serveBeginIdleStatus());
            }
        }, "serve-init").start();

        // 面板打开期间轮询（2s）：状态 + 回合结束检测
        new Thread(() -> {
            while (servePanelActive.get()) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                if (!servePanelActive.get()) break;
                ReasonixServe client = serveClient();
                String st = client.status();
                if (st == null) {
                    if (servePanelActive.get()) runOnUiThread(this::serveBeginIdleStatus);
                    continue;
                }
                runOnUiThread(() -> serveRenderStatus(st));
                boolean running = client.isRunning();
                if (serveTurnBusy && !running) {
                    serveTurnBusy = false;
                    runOnUiThread(() -> showToast("回复已完成"));
                    serveReloadHistory(true);
                    serveRenderExtras();
                }
            }
        }, "serve-poll").start();
    }

    /** 面板内联兜底文案（引擎离线） */
    private void serveBeginIdleStatus() {
        TextView s = serveStatusView;
        if (s != null) {
            s.setText("○ serve 未运行（点上方「启动」）");
            s.setTextColor(0xFF999999);
        }
    }

    /** 启动 serve 引擎（guest 内 nohup；token/端口/日志持久化于 /root/.rsxm-serve-*） */
    private void serveStart() {
        showToast("正在启动 serve 引擎…");
        new Thread(() -> {
            String token = ReasonixServe.readToken(getFilesDir());
            if (token.isEmpty()) token = ReasonixServe.generateToken();
            // 宿主侧同步写一份（proot 模式 app uid 可写；chroot 模式 root 属主时此步失败靠 guest 落盘兜底读取）
            writeServeToken(token);
            String out = executeInGuest(serveLaunchCommand(token), 12);
            boolean spawned = out != null && out.contains("SERVE_STARTED");
            ReasonixServe client = serveClient();
            for (int i = 0; i < 15 && spawned && !client.isUp(); i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
            boolean up = client.isUp();
            runOnUiThread(() -> {
                if (up) {
                    showToast("✓ serve 已在线（2 秒后状态自动刷新）");
                    serveReloadHistory(true);
                    serveRenderExtras();
                } else {
                    TextView s = serveStatusView;
                    if (s != null) {
                        s.setText("✗ serve 启动" + (spawned ? "超时：请先在环境里安装/更新 reasonix" : "失败：环境未就绪")
                                + "（日志 /root/.rsxm-serve.log）");
                        s.setTextColor(0xFFFF6B6B);
                    }
                }
            });
        }, "serve-start").start();
    }

    /** 提交一条消息：POST /submit → user 气泡即时回显 → running=false 后自动刷新历史 */
    private void serveSubmit() {
        EditText input = serveInputField;
        if (input == null) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        ReasonixServe client = serveClient();
        if (!client.isUp()) {
            showToast("serve 引擎未在线，请先启动");
            return;
        }
        new Thread(() -> {
            boolean sent = client.submit(text);
            if (!sent) {
                runOnUiThread(() -> showToast("提交失败（引擎未在线或鉴权失败）"));
                return;
            }
            serveTurnBusy = true;
            runOnUiThread(() -> {
                LinearLayout box = serveHistoryBox;
                if (box != null) {
                    TranscriptRecord mm = new TranscriptRecord();
                    mm.role = "user";
                    mm.content = text;
                    mm.createdAt = System.currentTimeMillis();
                    BubbleView bv = createBubble(mm);
                    updateBubble(bv, mm, new java.util.HashMap<String, TranscriptRecord.Call>());
                    box.addView(bv.wrap);
                }
            });
            // 轮询 /status 直到 running=false（上限 15 分钟）
            for (int i = 0; i < 900 && serveTurnBusy && servePanelActive.get(); i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (!client.isRunning()) {
                    serveTurnBusy = false;
                    runOnUiThread(() -> showToast("回复已完成"));
                    serveReloadHistory(true);
                    serveRenderExtras();
                    break;
                }
            }
            serveTurnBusy = false;   // 超时/面板关闭兜底
        }, "serve-submit").start();
    }

    /** POST /cancel 中断当前回合 */
    private void serveCancel() {
        currentServeClientSafe().cancel();
        showToast("已发送中断");
    }

    /** POST /new 开新会话并重绘历史 */
    private void serveNewSession() {
        new Thread(() -> {
            ReasonixServe client = serveClient();
            if (!client.isUp()) {
                runOnUiThread(() -> showToast("引擎未在线"));
                return;
            }
            client.newSession();
            runOnUiThread(() -> showToast("已开新会话"));
            serveReloadHistory(false);
        }, "serve-new").start();
    }

    /** POST /tool-approval-mode {"mode": m} 并回显结果 */
    private void serveSetApprovalMode(String mode) {
        new Thread(() -> {
            ReasonixServe client = serveClient();
            boolean ok = client.setToolApprovalMode(mode);
            String cur = ok ? client.toolApprovalMode() : null;
            runOnUiThread(() -> {
                TextView s = serveStatusView;
                if (s != null) {
                    s.setText(ok ? "✓ 审批模式已切换：" + cur : "✗ 模式切换失败（引擎未在线？）");
                    s.setTextColor(ok ? 0xFF7FDB8A : 0xFFFF6B6B);
                }
            });
        }, "serve-approve").start();
    }

    /** 引擎在线性检查包装：在线返回客户端，离线返回 null */
    private ReasonixServe currentServeClientSafe() {
        ReasonixServe client = serveClient();
        return client.isUp() ? client : null;
    }

    /** 渲染 /status 摘要 */
    private void serveRenderStatus(String st) {
        TextView s = serveStatusView;
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(st);
            JSONObject rt = o.optJSONObject("runtimeState");
            boolean running = rt != null ? rt.optBoolean("running", false)
                    : o.optBoolean("running", false);
            String phase = rt != null ? rt.optString("phase", "") : "";
            String label = o.optString("label", "");
            long used = o.optLong("used", 0), window = o.optLong("window", 1);
            String approve = o.optString("toolApprovalMode", "");
            String goal = o.optString("goalStatus", "");
            String text = (running ? "● " : "○ ") + (label.isEmpty() ? "serve 引擎" : label)
                    + (phase.isEmpty() ? "" : " · " + phase)
                    + " · 上下文 " + (used / 1000) + "k/" + (window / 1000) + "k"
                    + " · 审批 " + (approve.isEmpty() ? "?" : approve)
                    + (goal.isEmpty() || "stopped".equals(goal) ? "" : " · goal:" + goal);
            s.setText(text);
            s.setTextColor(running ? 0xFF7FDB8A : 0xFF8BE9FD);
        } catch (Exception e) {
            s.setText(st.length() > 160 ? st.substring(0, 160) : st);
        }
    }

    /** 拉会话历史渲染到 serve 面板（只读展示，独立于主会话视图索引）。
     *  优先 transcript 快照（结构化）；无 /transcript 契约（v1.38/1.39 实测）回退 /history。 */
    private void serveReloadHistory(boolean append) {
        new Thread(() -> {
            int port = ReasonixServe.readBoundPort(getFilesDir());
            if (port <= 0) port = 8787;
            TranscriptClient c = new TranscriptClient("http://127.0.0.1:" + port,
                    ReasonixServe.readToken(getFilesDir()), null);
            org.json.JSONObject snap = c.fetchSnapshot(8000);
            if (snap == null) {
                // 回退：/history 全量渲染（[{role, content}...]，过滤 system）
                try {
                    ReasonixServe client = serveClient();
                    if (client == null) return;
                    String body = client.history();
                    if (body == null) return;
                    final org.json.JSONArray arr = new org.json.JSONArray(body);
                    runOnUiThread(() -> {
                        LinearLayout box = serveHistoryBox;
                        if (box == null) return;
                        box.removeAllViews();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject m = arr.optJSONObject(i);
                            if (m == null) continue;
                            String role = m.optString("role", "");
                            String content = m.optString("content", "");
                            if ("system".equals(role) || content.isEmpty()) continue;
                            TranscriptRecord r = new TranscriptRecord();
                            r.role = role;
                            r.content = content;
                            r.id = "h" + i;
                            box.addView(createBubble(r).wrap);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "serve history fallback failed", e);
                }
                return;
            }
            java.util.List<TranscriptRecord> recs =
                    TranscriptRecord.parseArray(snap.optJSONArray("records"));
            java.util.Collections.sort(recs, (a, b) -> Long.compare(a.order, b.order));
            runOnUiThread(() -> {
                LinearLayout box = serveHistoryBox;
                if (box == null) return;
                java.util.Map<String, TranscriptRecord.Call> idx = new java.util.HashMap<>();
                for (TranscriptRecord r : recs) {
                    for (TranscriptRecord.Call cc : r.calls) {
                        if (!cc.id.isEmpty()) idx.put(cc.id, cc);
                    }
                }
                box.removeAllViews();
                for (TranscriptRecord r : recs) {
                    BubbleView bv = createBubble(r);
                    updateBubble(bv, r, idx);
                    box.addView(bv.wrap);
                }
            });
        }, "serve-transcript").start();
    }

    /** 拉取 todos + checkpoints 渲染 */
    private void serveRenderExtras() {
        new Thread(() -> {
            ReasonixServe client = serveClient();
            String todos = client.todos();
            String cks = client.checkpoints();
            runOnUiThread(() -> {
                TextView tv = serveTodoView;
                if (tv != null) {
                    tv.setText(todos == null ? "（读取失败或为空）" : todos.replace("},{", "},\n{"));
                }
            });
            List<Object[]> rows = new ArrayList<>();
            if (cks != null) {
                try {
                    JSONArray arr = new JSONArray(cks);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        rows.add(new Object[]{c.optInt("turn", -1),
                                c.optString("prompt", ""), c.optLong("time", 0)});
                    }
                } catch (Exception ignored) {}
            }
            final List<Object[]> fRows = rows;
            runOnUiThread(() -> {
                LinearLayout box = serveCkBox;
                if (box == null) return;
                box.removeAllViews();
                if (fRows.isEmpty()) {
                    box.addView(createDarkTip("（暂无 checkpoint）"));
                    return;
                }
                for (Object[] row : fRows) {
                    final int turn = (int) row[0];
                    String p = String.valueOf(row[1]);
                    if (p.length() > 60) p = p.substring(0, 60) + "…";
                    LinearLayout line = new LinearLayout(this);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    TextView tvv = new TextView(this);
                    tvv.setText("T" + turn + " · " + p);
                    tvv.setTextColor(0xFFC9D1D9);
                    tvv.setTextSize(12);
                    tvv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    line.addView(tvv);
                    Button rw = createDarkButton("回溯");
                    rw.setTextSize(11);
                    rw.setOnClickListener(v -> {
                        showToast("已请求回溯到 turn " + turn);
                        new Thread(() -> serveClient().rewind(turn), "serve-rewind").start();
                    });
                    line.addView(rw, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    box.addView(line);
                }
            });
        }, "serve-extras").start();
    }


    /** 更新 reasonix：从手机选择新版文件，或恢复内置版本 */
    private void showUpdateResonixDialog() {
        EditText urlInput = createDarkEditText("reasonix 更新包链接 (.tgz)", InputType.TYPE_CLASS_TEXT);
        urlInput.setText(REASONIX_DEFAULT_URL);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(8), pad, dp(12));
        // 当前版本：优先显示记录的 npm 版本（.npm-version），否则从 Go buildinfo 提取
        String npmVer = null;
        File vf = new File(new File(new File(getFilesDir(), "rootfs/root"), ".reasonix"), ".npm-version");
        if (vf.exists()) {
            try {
                npmVer = new String(java.nio.file.Files.readAllBytes(vf.toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {}
        }
        final String ver = (npmVer != null && !npmVer.isEmpty()) ? "v" + npmVer : null;
        TextView verView = new TextView(this);
        verView.setText("当前版本：" + (ver != null ? ver : "检测中…"));
        verView.setTextColor(0xFF7FDB8A);
        verView.setTextSize(14);
        verView.setTypeface(null, android.graphics.Typeface.BOLD);
        addV(panel, verView, 0);
        if (ver == null) {
            // v2.0.25：buildinfo 提取要读 2MB 二进制+正则，挪到后台线程（原在主线程面板构建里同步执行）
            new Thread(() -> {
                final String v = extractReasonixVersion(
                        new File(new File(getFilesDir(), "rootfs/usr/local/bin"), "reasonix"));
                runOnUiThread(() -> verView.setText("当前版本：" + (v != null ? v : "未知（内置 1.31.4）")));
            }, "rx-local-ver").start();
        }
        // 异步查询最新版本并自动填入最新下载链接
        final String curVer = ver;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://registry.npmmirror.com/@reasonix/cli-linux-arm64/latest").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                byte[] buf = new byte[8192];
                int n;
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = conn.getInputStream()) {
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } finally {
                    conn.disconnect();
                }
                java.util.regex.Matcher mv = java.util.regex.Pattern
                        .compile("\"version\"\\s*:\\s*\"([\\d.]+)\"").matcher(sb.toString());
                if (mv.find()) {
                    final String latest = mv.group(1);
                    runOnUiThread(() -> {
                        verView.setText("当前版本：" + (curVer != null ? curVer : "未知")
                                + "　最新版本：v" + latest);
                        urlInput.setText("https://registry.npmmirror.com/@reasonix/cli-linux-arm64/-/cli-linux-arm64-"
                                + latest + ".tgz");
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "query latest version failed", e);
            }
        }, "rx-ver-check").start();
        TextView tip = createDarkTip("官方源 @reasonix/cli-linux-arm64（npm 平台包）。\n"
                + "可改链接更新、选文件更新或恢复内置版本。");
        addV(panel, tip, 8);
        panel.addView(createDarkSectionTitle("更新方式"));
        addV(panel, urlInput, 6);
        Button netBtn = createDarkButton("网络更新");
        netBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) {
                hidePanel();
                updateFromNetwork(url);
            }
        });
        addV(panel, netBtn, 8);
        Button fileBtn = createDarkButton("选择文件");
        fileBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_UPDATE_RESONIX);
            } catch (Exception e) {
                Log.e(TAG, "open document failed", e);
            }
        });
        addV(panel, fileBtn, 8);
        Button restoreBtn = createDarkButton("恢复内置");
        restoreBtn.setOnClickListener(v -> {
            hidePanel();
            restoreBundledResonix();
        });
        addV(panel, restoreBtn, 8);
        // 全屏面板展示（取代系统弹窗，避免遮挡控件）
        showPanel("Reasonix 更新", panel, null);
    }

    /** 从网络下载 reasonix 更新包（tar.gz），解压提取二进制并覆盖 guest 内版本 */
    private void updateFromNetwork(String url) {
        new Thread(() -> {
            try {
                pushOutput("\r\n[正在下载 reasonix 更新包...]\r\n");
                File tmp = new File(getCacheDir(), "reasonix-update.tgz");
                long total = 0;
                java.net.HttpURLConnection dl = (java.net.HttpURLConnection) new URL(url).openConnection();
                dl.setConnectTimeout(15000);
                dl.setReadTimeout(60000);   // v2.0.25 修复：旧 URL.openStream() 无超时，网络黑洞时线程永久挂起
                try (InputStream in = dl.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                } finally {
                    dl.disconnect();
                }
                Log.d(TAG, "downloaded " + total + " bytes");
                pushOutput("\r\n[下载完成 (" + (total / 1024 / 1024) + " MB)，正在解压...]\r\n");
                File rootfs = new File(getFilesDir(), "rootfs");
                File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
                rx.getParentFile().mkdirs();
                try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(tmp));
                     FileOutputStream out = new FileOutputStream(rx)) {
                    extractTarMember(gz, out, "package/bin/reasonix");
                }
                rx.setExecutable(true, false);
                tmp.delete();
                // 记录 npm 版本号（buildinfo 是 git pseudo-version，显示友好版本用）
                try {
                    java.util.regex.Matcher mv = java.util.regex.Pattern
                            .compile("cli-linux-arm64-(\\d+\\.\\d+\\.\\d+)").matcher(url);
                    if (mv.find()) {
                        writeNpmVersion(mv.group(1));
                    }
                } catch (Exception ignored) {}
                pushOutput("\r\n[reasonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
                restartEnvironment();
            } catch (Exception e) {
                pushOutput("\r\n[更新失败] " + e + "\r\n");
                Log.e(TAG, "network update failed", e);
            }
        }, "rx-update").start();
    }

    /** 从 tar 流中提取指定成员（tar 512 字节块格式；gzip 已解压） */
    private void extractTarMember(InputStream in, OutputStream out, String targetName) throws IOException {
        byte[] header = new byte[512];
        boolean found = false;
        while (true) {
            int read = readFully(in, header);
            if (read < 512) break;              // 结束
            if (allZero(header)) break;         // 两个空块结束
            String name = new String(header, 0, 100, StandardCharsets.UTF_8).replace("\0", "").trim();
            long size = -1;
            String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).trim();
            try {
                size = sizeStr.matches("[0-7]+") ? Long.parseLong(sizeStr, 8) : -1;
            } catch (Exception ignored) {}
            // v2.0.25 修复：size 解析失败按 0 处理会把成员写成 0 字节文件（reasonix 损坏）；
            // GNU base-256 / 截断为负同理。解析失败或不合理（负值/超 256MB）直接报错。
            if (size < 0 || size > 256L * 1024 * 1024) {
                throw new IOException("tar 成员 " + name + " 大小异常: " + sizeStr);
            }
            if (name.equals(targetName) || name.equals("./" + targetName)
                    || name.equals("package/" + targetName) || name.equals("./package/" + targetName)) {
                if (size == 0) {
                    found = true;   // 空成员：匹配成功但不写空文件
                    break;
                }
                byte[] data = new byte[(int) size];
                int got = readFully(in, data);
                // v2.0.25 修复：tar 截断时旧实现忽略 readFully 返回值，静默把零填充垃圾
                // 写成 reasonix 二进制（只剩「恢复内置」可救）
                if (got < data.length) throw new IOException("tar 成员 " + name + " 被截断");
                out.write(data);
                found = true;
                break;
            } else {
                // 跳过数据块（512 对齐）
                long skip = (size + 511) / 512 * 512;
                skipFully(in, skip);
            }
        }
        if (!found) throw new IOException("tar 内未找到 " + targetName);
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int c = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (c < 0) break;
            remaining -= c;
        }
    }

    private boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    /** 内置 reasonix 版本（必须与 assets/usr/bin/reasonix 一致；UI 显示与部署记录都用它） */
    private static final String BUNDLED_REASONIX_VERSION = "1.39.3";

    /** 当前 APK 的 versionName（作为"内置资源已部署"的标记值） */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 部署内置 reasonix 到 guest（{@code /usr/local/bin/reasonix}），并写下两份标记：
     * {@code .reasonix/.npm-version}（UI 显示版本）与 {@code .rsxm-reasonix-apk-ver}（部署时的 APK 版本）。
     *
     * <p>先 {@code delete()} 再写：旧环境进程可能仍 exec 着该文件，直接覆盖(O_TRUNC)会报 ETXTBSY，
     * unlink 正在执行的 inode 是合法的。
     */
    private boolean deployBundledReasonix(File rootfs) {
        File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
        try {
            // 1) 先解到 app 私有目录（必然可写）：既是复制的源，也让 root 回退路径有源文件可用
            File staged = new File(getFilesDir(), "reasonix.staged");
            extractAsset("usr/bin/reasonix", staged);
            staged.setReadable(true, false);      // root 回退时 su 进程需要能读它
            boolean ok = false;
            try {
                rx.getParentFile().mkdirs();
                rx.delete();
                java.nio.file.Files.copy(staged.toPath(), rx.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ok = true;
            } catch (Exception e) {
                // chroot 模式（曾以 root 运行过）下 rootfs 内文件属主是 root、app 无写权限
                Log.w(TAG, "deploy reasonix: direct write failed (" + e + "), retry via root");
            }
            if (!ok) ok = deployReasonixViaRoot(staged, rx);
            if (!ok) {
                reasonixDeployError = "无法写入 " + rx + "（app 无写权限且 root 不可用）";
                Log.e(TAG, "deploy reasonix failed: " + reasonixDeployError);
                return false;
            }
            rx.setExecutable(true, false);
            writeNpmVersion(BUNDLED_REASONIX_VERSION);
            try {
                java.nio.file.Files.write(new File(rx.getParentFile(), ".rsxm-reasonix-apk-ver").toPath(),
                        (appVersionName() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            reasonixDeployError = null;
            return true;
        } catch (Exception e) {
            reasonixDeployError = String.valueOf(e);
            Log.e(TAG, "deploy reasonix failed", e);
            return false;
        }
    }

    /** 内置 reasonix 的字节数（assets 流式读一次后缓存；用于判断 guest 内是否为内置版本） */
    private long bundledReasonixBytes = -1;

    private long bundledReasonixSize() {
        if (bundledReasonixBytes > 0) return bundledReasonixBytes;
        try (InputStream in = getAssets().open("usr/bin/reasonix")) {
            long n = 0;
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
            bundledReasonixBytes = n;
        } catch (Exception ignored) {}
        return bundledReasonixBytes;
    }

    /**
     * 启动 serve 前确保 guest 内就是内置版本的 reasonix：**纯宿主侧**按字节数比对
     * （同一 APK 内内置文件不变，字节数即可判定），不一致就重新部署。
     *
     * <p>不执行任何 guest 命令来做版本判断 —— 老版本 reasonix 会把未知子命令当任务提示起会话而挂住。
     */
    private void ensureBundledReasonixDeployed(File rootfs) {
        try {
            File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
            long want = bundledReasonixSize();
            if (want > 0 && rx.exists() && rx.length() == want) return;   // 已是内置版本
            Log.w(TAG, "guest reasonix mismatch (have=" + (rx.exists() ? rx.length() : -1)
                    + " want=" + want + "), redeploying");
            deployBundledReasonix(rootfs);
        } catch (Exception e) {
            Log.w(TAG, "ensureBundledReasonixDeployed failed", e);
        }
    }

    /** 内置 reasonix 部署状态摘要（宿主侧读文件；诊断卡片用，不执行 guest 命令） */
    private String reasonixDeployInfo() {
        File dir = new File(new File(getFilesDir(), "rootfs"), "usr/local/bin");
        File rx = new File(dir, "reasonix");
        File stamp = new File(dir, ".rsxm-reasonix-apk-ver");
        String st = "";
        try {
            if (stamp.exists()) {
                st = new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                        StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {}
        return "guest reasonix：" + (rx.exists() ? rx.length() + " 字节" : "不存在")
                + "（内置 " + bundledReasonixSize() + " 字节 = v" + BUNDLED_REASONIX_VERSION + "）\n"
                + "部署标记：" + (st.isEmpty() ? "(无)" : st) + " / 当前 APK " + appVersionName() + "\n"
                + (reasonixDeployError == null ? "" : "部署错误：" + reasonixDeployError + "\n");
    }

    /** 内置 reasonix 插件（破甲内核）名称/版本 —— 必须与 assets/dsh-infinite-gen-4.tar 一致 */
    private static final String BUNDLED_PLUGIN_NAME = "dsh-infinite-gen-4";
    private static final String BUNDLED_PLUGIN_VERSION = "0.4.1";
    /** 最近一次内置插件部署失败原因（null = 成功/未尝试；诊断卡片会显示） */
    private volatile String pluginDeployError = null;

    /**
     * 部署内置 reasonix 插件到 guest 的 {@code ~/.reasonix/plugins/<name>}，并在
     * {@code ~/.reasonix/plugin-packages.json} 里登记 —— 等价于桌面端的"本地目录安装"
     * （目录 + reasonix-plugin.json 清单 + 登记项 enabled=true）。
     *
     * <p>先删旧目录再解压：归档顶层就是 {@code dsh-infinite-gen-4/}，覆盖安装得到的就是内置
     * （硬化）版本，与该插件自身 repair_escape_clauses.mjs 的意图一致。
     */
    private boolean deployBundledPlugin(File rootfs) {
        try {
            File reasonixDir = new File(new File(rootfs, "root"), ".reasonix");
            File pluginsDir = new File(reasonixDir, "plugins");
            File tar = new File(getFilesDir(), BUNDLED_PLUGIN_NAME + ".tar");
            extractAsset(BUNDLED_PLUGIN_NAME + ".tar", tar);
            File dest = new File(pluginsDir, BUNDLED_PLUGIN_NAME);
            File manifest = new File(dest, "reasonix-plugin.json");
            boolean ok = false;
            try {
                pluginsDir.mkdirs();
                deleteRecursive(dest);
                runCmd("/system/bin/tar", "-xzf", tar.getAbsolutePath(), "-C", pluginsDir.getAbsolutePath());
                ok = manifest.exists();
            } catch (Exception e) {
                Log.w(TAG, "deploy plugin: 直接解压失败（" + e + "），改用 root 重试");
            }
            if (!ok) {
                // chroot 模式 / rootfs 内 .reasonix 属主为 root 时 app 写不进去：交给 root(su) 解压
                String out = execRootCommand("mkdir -p '" + pluginsDir + "' && rm -rf '" + dest
                        + "' && tar -xzf '" + tar + "' -C '" + pluginsDir + "' && test -f '"
                        + manifest + "' && echo PLG_OK", 60);
                ok = out != null && out.contains("PLG_OK");
            }
            if (!ok) {
                pluginDeployError = "插件解压失败：" + dest;
                // 清掉半成品，别让 reasonix 去加载坏插件；app 删不掉（root 属主）时同样交给 root
                if (!deleteRecursive(dest)) execRootCommand("rm -rf '" + dest + "'", 20);
                Log.e(TAG, "deploy plugin failed: " + pluginDeployError);
                return false;
            }
            tar.delete();
            if (!registerPluginInPackages(reasonixDir)) {
                // 目录解压成功但没登记 → reasonix 不会加载它，按部署失败报给诊断卡片
                pluginDeployError = "插件登记失败（写不进 "
                        + new File(reasonixDir, "plugin-packages.json") + "，且 root 不可用）";
                Log.e(TAG, "deploy plugin failed: " + pluginDeployError);
                return false;
            }
            pluginDeployError = null;
            Log.d(TAG, "bundled plugin deployed: " + BUNDLED_PLUGIN_NAME + " " + BUNDLED_PLUGIN_VERSION);
            return true;
        } catch (Exception e) {
            pluginDeployError = String.valueOf(e);
            Log.e(TAG, "deploy bundled plugin failed", e);
            return false;
        }
    }

    /** 在 plugin-packages.json 里登记/更新内置插件（保留文件中原有的其它插件）；
     *  chroot/root 属主场景下 app 写不进 .reasonix，落盘交给 root(su) 回退，失败返回 false */
    private boolean registerPluginInPackages(File reasonixDir) throws org.json.JSONException {
        File f = new File(reasonixDir, "plugin-packages.json");
        org.json.JSONObject root = new org.json.JSONObject();
        if (f.exists()) {
            try {
                root = new org.json.JSONObject(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "plugin-packages.json 解析失败，将重建：" + e);
                root = new org.json.JSONObject();
            }
        }
        if (!root.has("version")) root.put("version", 1);
        org.json.JSONArray arr = root.optJSONArray("plugins");
        if (arr == null) {
            arr = new org.json.JSONArray();
            root.put("plugins", arr);
        }
        org.json.JSONObject entry = null;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject p = arr.optJSONObject(i);
            if (p != null && BUNDLED_PLUGIN_NAME.equals(p.optString("name"))) {
                entry = p;
                break;
            }
        }
        if (entry == null) {
            entry = new org.json.JSONObject();
            arr.put(entry);
        }
        entry.put("name", BUNDLED_PLUGIN_NAME);
        entry.put("source", "bundled:apk");
        entry.put("root", "plugins/" + BUNDLED_PLUGIN_NAME);
        entry.put("version", BUNDLED_PLUGIN_VERSION);
        entry.put("description", "无限四代 v" + BUNDLED_PLUGIN_VERSION + "（随 APK 内置安装）");
        entry.put("manifestKind", "reasonix");
        entry.put("enabled", true);
        return writeGuestText(f, root.toString(2) + "\n");
    }

    /**
     * 写 guest 内的文本文件：先试 app 直写（快路径、无需 root），失败（chroot 模式曾以 root 运行过，
     * rootfs 内 .reasonix 属主是 root，app 无写权限）→ 落到 app 私有目录再由 root(su) 复制过去。
     */
    private boolean writeGuestText(File dest, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            dest.getParentFile().mkdirs();
            java.nio.file.Files.write(dest.toPath(), bytes);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "写 " + dest + " 失败（" + e + "），改用 root(su)");
        }
        File staged = new File(getFilesDir(), dest.getName() + ".staged");
        try {
            java.nio.file.Files.write(staged.toPath(), bytes);
            staged.setReadable(true, false);     // root 回退时 su 进程需要能读它
            String out = execRootCommand("mkdir -p '" + dest.getParentFile() + "' && cp -f '"
                    + staged + "' '" + dest + "' && echo RXW_OK", 20);
            boolean ok = out != null && out.contains("RXW_OK");
            if (!ok) Log.w(TAG, "写 " + dest + " 的 root 回退失败：out=" + out);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "写 " + dest + " 的 root 回退异常", e);
            return false;
        } finally {
            staged.delete();
        }
    }

    /** 按版本标记决定是否需要（重新）部署内置插件：APK 升级或首次安装时生效，同一 APK 内不重复解压 */
    private void ensureBundledPluginDeployed(File rootfs) {
        try {
            File reasonixDir = new File(new File(rootfs, "root"), ".reasonix");
            File dest = new File(new File(reasonixDir, "plugins"), BUNDLED_PLUGIN_NAME);
            File stamp = new File(reasonixDir, ".rsxm-plugin-" + BUNDLED_PLUGIN_NAME + ".ver");
            String want = BUNDLED_PLUGIN_VERSION + "@" + appVersionName();
            String have = "";
            try {
                if (stamp.exists()) {
                    have = new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                            StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {}
            if (new File(dest, "reasonix-plugin.json").exists() && want.equals(have)) return;
            if (deployBundledPlugin(rootfs)) {
                // 标记写不进（root 属主）时也走 su 回退：否则每次启动都要重解压一遍插件
                writeGuestText(stamp, want + "\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureBundledPluginDeployed failed", e);
        }
    }

    /** 用 root(su) 把暂存二进制复制进 guest —— chroot / root 属主场景下 app 自身写不进去 */
    private boolean deployReasonixViaRoot(File staged, File dest) {
        try {
            String out = execRootCommand("mkdir -p '" + dest.getParent() + "'; cp -f '" + staged
                    + "' '" + dest + "' && chmod 755 '" + dest + "' && echo RXM_OK", 30);
            boolean ok = out != null && out.contains("RXM_OK");
            if (!ok) Log.w(TAG, "deploy reasonix via root failed, out=" + out);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "deploy reasonix via root failed", e);
            return false;
        }
    }

    /** 从 APK assets 恢复内置 reasonix */
    private void restoreBundledResonix() {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            if (!deployBundledReasonix(rootfs)) {
                pushOutput("\r\n[恢复内置 reasonix 失败] " + reasonixDeployError + "\r\n");
                return;
            }
            Log.d(TAG, "reasonix restored from bundle");
            pushOutput("\r\n[已恢复内置 reasonix，正在重启环境...]\r\n");
            restartEnvironment();
        } catch (Exception e) {
            Log.e(TAG, "restore reasonix failed", e);
        }
    }

    /** 写入 reasonix npm 版本标记（rootfs/.reasonix/.npm-version，UI 显示友好版本用） */
    private void writeNpmVersion(String ver) {
        try {
            File vf = new File(new File(new File(getFilesDir(), "rootfs/root"), ".reasonix"), ".npm-version");
            vf.getParentFile().mkdirs();
            java.nio.file.Files.write(vf.toPath(), ver.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write npm-version failed", e);
        }
    }

    /** 从手机存储复制新版 reasonix 到 guest */
    private void applyReasonixUpdate(Uri uri) {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            File rx = new File(new File(rootfs, "usr/local/bin"), "reasonix");
            rx.getParentFile().mkdirs();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    pushOutput("\r\n[更新失败: 无法读取所选文件]\r\n");
                    return;
                }
                try (OutputStream out = new FileOutputStream(rx)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    Log.d(TAG, "reasonix updated, size=" + total);
                }
            }
            rx.setExecutable(true, false);
            pushOutput("\r\n[reasonix 已更新（" + rx.length() + " 字节），正在重启环境...]\r\n");
            restartEnvironment();
        } catch (Exception e) {
            Log.e(TAG, "apply reasonix update failed", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPDATE_RESONIX && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // v2.0.25：io 挪后台线程（几十 MB ContentResolver 复制走 FUSE，主线程极易 ANR）
            final Uri uri = data.getData();
            pushOutput("\r\n[正在复制新版 reasonix...]\r\n");
            new Thread(() -> applyReasonixUpdate(uri), "rx-apply-update").start();
        } else if (requestCode == REQ_SKILL_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importSkillFromUri(data.getData());
        } else if (requestCode == REQ_CREATE_ENV_TEMPLATE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // v2.0.25：写文件挪后台线程（openOutputStream+write 在主线程会 ANR）
            final Uri tUri = data.getData();
            new Thread(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(tUri)) {
                    if (os != null) {
                        os.write(ENV_TEMPLATE_CONTENT.getBytes(StandardCharsets.UTF_8));
                        pushOutput("\r\n[模板已保存（.rsxmenv），编辑后可在开发环境面板「导入模板」导入]\r\n");
                    } else {
                        pushOutput("\r\n[保存模板失败: 无法写入所选位置]\r\n");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "save env template failed", e);
                    pushOutput("\r\n[保存模板失败: " + e.getMessage() + "]\r\n");
                }
            }, "rx-save-template").start();
        } else if (requestCode == REQ_IMPORT_ENV_TEMPLATE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importEnvTemplate(data.getData());
        }
    }

    /** 请求运行时存储权限（媒体文件；Android 13+ 用 READ_MEDIA_*） */
    private void requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.READ_MEDIA_AUDIO"}, 100);
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, 100);
            }
        } catch (Exception e) {
            Log.w(TAG, "permission request failed", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sWebActive = false;   // 退后台：WebView JS 暂停，终端输出改缓存（避免堆积阻塞 reasonix）
    }

    protected void onResume() {
        super.onResume();
        flushPendingOutput();   // 前台恢复：冲刷后台期间缓存的终端输出
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        // 回到前台的自愈：切到其他 app 期间环境被杀/冻结、或 serve 掉了 → 立刻探测并重启引擎，
        // 不等看门狗的下一个周期（Activity 重建后是新实例，看门狗也要在这里续上）。
        if (environmentStarted) {
            startServeWatchdog();
            probeServeAlive();
        }
        // 后台保活模式（默认开启）只冻结"抢前台"的面板弹窗 —— adb-open-moments-summary.md 坑 4：
        // 守护界面抢回前台会让 UI 自动化窗口期过短。但存储授权检测/生效后的环境重启与 serve 自愈
        // 必须照常执行，否则默认保活下这两件事永远不会发生（授权了也不生效）。
        final boolean bgMode = bgModeOn();

        // API 30+ 的"所有文件访问"（MANAGE_EXTERNAL_STORAGE）：让 reasonix 能读写
        // /sdcard 任意位置（文档、下载、非媒体等）。首次启动引导授权；检测到授权状态
        // 从"未授权"变为"已授权"时自动重启 Linux 环境使 FUSE 权限生效。
        if (Build.VERSION.SDK_INT >= 30) {
            boolean managed = Environment.isExternalStorageManager();
            boolean wasManaged = prefs.getBoolean("storage_managed", false);
            prefs.edit().putBoolean("storage_managed", managed).apply();
            if (managed && !wasManaged) {
                pushOutput("\r\n[存储权限已生效，正在重启 Linux 环境...]\r\n");
                restartEnvironment();
            } else if (!managed && !prefs.getBoolean("storage_guided", false) && !bgMode) {
                prefs.edit().putBoolean("storage_guided", true).apply();
                // 全屏面板引导（取代系统弹窗，避免遮挡控件）
                LinearLayout panel = new LinearLayout(this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setPadding(dp(16), dp(8), dp(16), dp(12));
                panel.addView(createDarkTip("reasonix 需要\"所有文件访问\"权限才能读写手机存储的任意位置"
                        + "（文档、下载、非媒体文件等）。\n\n未授权时仅可访问公共媒体目录。"));
                Button grantBtn = createDarkButton("去授权");
                grantBtn.setOnClickListener(v -> {
                    hidePanel();
                    openManageAllFilesSettings();
                });
                addV(panel, grantBtn, 12);
                Button laterBtn = createDarkButton("暂不");
                laterBtn.setOnClickListener(v -> hidePanel());
                addV(panel, laterBtn, 8);
                showPanel("存储权限", panel, null);
            }
        }
    }

    /** 打开"所有文件访问"系统设置页 */
    private void openManageAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                Log.w(TAG, "cannot open manage-all-files settings", e2);
            }
        }
    }

    /** 杀掉 proot 进程并重启整个 Linux 环境（MANAGE_EXTERNAL_STORAGE 授权后调用，使 FUSE 权限生效）。
     *  envRestarting 进程内互斥（v2.0.25）：synchronized 只护住"派发"，kill+start 在裸线程里跑，
     *  快速连点「新建/切换项目/继续会话」会派发两个 restart 线程，kill/start 交错可产生
     *  两套并存 proot 环境（PTY 竞争、排版错乱）。进行中再次触发直接忽略。 */
    private final java.util.concurrent.atomic.AtomicBoolean envRestarting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private synchronized void restartEnvironment() {
        if (!environmentStarted) return;   // 环境尚未启动，无需重启（正常流程会启动）
        if (!envRestarting.compareAndSet(false, true)) {
            Log.d(TAG, "restart already in progress, skip");
            return;
        }
        new Thread(() -> {
            try {
                killProotTree();
                startEnvironment();
            } catch (Exception e) {
                Log.e(TAG, "restart failed", e);
            } finally {
                envRestarting.set(false);
            }
        }, "env-restart").start();
    }

    /** 杀掉 proot 及其残留的 guest 进程（pty-bridge/reasonix 是 proot 子进程，
     *  proot 被杀后若不清除会残留成孤儿，导致多套环境并存） */
    private void killProotTree() {
        try {
            if (sProotProcess != null) {
                sProotProcess.destroy();
                sProotProcess = null;
                sProcIn = null;
            }
            // 1) 先杀 app 同 uid 的进程（proot 模式；按进程名精确匹配，不会误伤其他应用）
            //    含 apk：entry.sh 里 android-tools 安装是后台子 shell，入口脚本被 kill -9 后
            //    apk 会孤儿化继续跑并持着 /lib/apk/db 的锁，导致新环境的 apk 报
            //    "Unable to lock database: temporary error"（日志里 adb 装不上就是这个）
            Process p = new ProcessBuilder("sh", "-c",
                    "for pid in $(ps -A -o PID,ARGS 2>/dev/null | grep -E 'proot.so|pty-bridge|reasonix|entry.sh|/sbin/apk|apk (add|update)' | awk '{print $1}'); " +
                    "do kill -9 $pid 2>/dev/null; done")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            // 2) chroot 模式进程是 su(root) 启动的（app 无权杀 root 进程，force-stop 也不杀），
            //    需用 su pkill 清理，否则旧环境残留导致切换目录/重启不生效
            try {
                String su = findSuPath();
                if (su != null) {
                    Process k = new ProcessBuilder(su, "-c",
                            // 模式一律写成 '[x]xxx'：整条命令本身会作为 su -c/sh -c 的 argv 存在，
                            // 不加方括号时 pkill -f 会匹配到执行本命令的 shell（链首自杀 → 后面几条全不执行）
                            "pkill -9 -f '[r]easonix.bin' 2>/dev/null; "
                                    + "pkill -9 -f '[p]ty-bridge' 2>/dev/null; "
                                    + "pkill -9 -f '[e]ntry.sh' 2>/dev/null; "
                                    + "pkill -9 -f '[c]hroot /data/user' 2>/dev/null; "
                                    + "pkill -9 -f '[p]root.so' 2>/dev/null; true")
                            .redirectErrorStream(true).start();
                    if (!k.waitFor(3, TimeUnit.SECONDS)) k.destroy();
                }
            } catch (Exception ignored) {
            }
            // 3) chroot 模式：清理 bind mount（su 进程被强杀时脚本内 umount 可能未执行，
            //    残留挂载会占用 rootfs/dev 等目录，影响下次启动）
            try {
                String su = findSuPath();
                if (su != null) {
                    File rootfs = new File(new File(getFilesDir(), "rootfs"), "");
                    String r = rootfs.getAbsolutePath();
                    Process u = new ProcessBuilder(su, "-c",
                            "umount " + r + "/dev/pts 2>/dev/null; umount " + r + "/dev 2>/dev/null; "
                                    + "umount " + r + "/proc 2>/dev/null; umount " + r + "/sys 2>/dev/null; "
                                    + "umount " + r + "/sdcard 2>/dev/null")
                            .redirectErrorStream(true).start();
                    if (!u.waitFor(3, TimeUnit.SECONDS)) u.destroy();
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "kill tree failed", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // READ_MEDIA 等运行时权限授权结果；MANAGE_EXTERNAL_STORAGE 的授权状态
        // 在 onResume() 中通过 Environment.isExternalStorageManager() 检测。
        Log.d(TAG, "permission result code=" + code);
    }

    /** 由 xterm.js 调用：把终端按键输入写入子进程 stdin（经 pty-bridge 转发到 PTY）。
     *  线程安全：与原生发送线程共享 sProcIn，加锁避免字节交错。 */
    @JavascriptInterface
    public void write(String data) {
        if (data == null) return;
        // 与 resize()/原生发送线程共享 sProcIn：同一把锁避免字节交错；
        // 锁内重取引用（killProotTree 置 null 与写入并发时不 NPE）
        synchronized (this) {
            OutputStream out = sProcIn;
            if (out == null) return;
            try {
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "write failed", e);
            }
        }
    }

    /** 由 xterm.js 在页面加载完成后调用（握手用） */
    @JavascriptInterface
    public void onReady() {
        // 环境启动由 onPageFinished 触发
    }

    /**
     * 由 xterm.js 上报终端状态（调试/验证用）：{cols, rows, viewportY, length}
     */
    @JavascriptInterface
    public void reportState(String state) {
        Log.d(TAG, "STATE> " + state);
    }

    /**
     * 由 xterm.js 在终端自适应尺寸后调用：把窗口大小（行/列）同步给 PTY，
     * 通过 pty-bridge 的带外序列 \u001b]50;ROWS;COLS\u0007 实现。
     */
    @JavascriptInterface
    public void resize(int rows, int cols) {
        if (rows <= 0 || cols <= 0) return;
        // v2.0.25 修复：旧实现无锁直写 sProcIn，与 write() 并发时带外序列可能
        // 插进按键字节流中间（PTY 输入错乱）。与 write() 共用同一把锁。
        synchronized (this) {
            OutputStream out = sProcIn;
            if (out == null) return;
            try {
                String seq = "\u001b]50;" + rows + ";" + cols + "\u0007";
                out.write(seq.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "resize failed", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 环境初始化与 proot 启动（后台线程）
    // ------------------------------------------------------------------

    /** 当前运行模式："chroot"（root 直入）或 "proot"（默认） */
    private boolean isChrootMode() {
        return "chroot".equals(getSharedPreferences("prefs", MODE_PRIVATE).getString("run_mode", "proot"));
    }

    private void startEnvironment() {
        Log.d(TAG, "startEnvironment: begin");
        // 注：不自动修改 SELinux（setenforce 0）。SELinux 由系统/用户管理保持 enforcing；
        // 安卓开发环境（JVM）在 proot 模式 enforcing 下不可用，可用 root 面板切换 chroot 模式
        // （ksu/root 域，enforcing 下 JVM 正常）。
        try {
            File files = getFilesDir();
            File rootfs = new File(files, "rootfs");
            if (!new File(files, ".installed").exists()) {
                setupEnvironment(files, rootfs);
            } else {
                Log.d(TAG, "environment already installed, refreshing runtime assets");
                refreshAssets(files, rootfs);
            }
            writeAdbIpFile(rootfs);
            ensureReasonixConfig(rootfs);
            // 环境（重新）就绪后：确保 serve 引擎与事件流接回（serve 是 guest 内进程，proot 重启
            // 会一并消失）。延迟几秒等 guest 内的命令执行服务（.adb-cmd watcher）就绪，
            // 否则启动命令会落空（ensureServeStarted 内部还有退避重试，不再是一次定生死）。
            // 注意：这里不再判断 nativeViewOn —— serve 是"常驻引擎"，切到终端视图/其他 app 都该活着。
            ui.postDelayed(this::ensureServeStarted, 6000);
            startServeWatchdog();   // 之后每 20 秒确认一次：掉线自动重启（切到其他 app 回来也在）
        } catch (Exception e) {
            Log.e(TAG, "startEnvironment failed", e);
            pushOutput("\r\n[初始化失败] " + e + "\r\n");
        }
    }

    /** root 可用性检测：su 实际可执行且返回 uid=0（KernelSU/Magisk 已授权给本应用才可执行） */
    private boolean isRootAvailable() {
        try {
            String r = execRootCommand("id", 5);
            return r != null && r.contains("uid=0");
        } catch (Exception e) {
            Log.w(TAG, "root availability check failed: " + e);
            return false;
        }
    }

    /** 启动 proot（包装 IOException，供回调/lambda 使用） */
    private void safeStartProot() {
        try {
            startProot(getFilesDir(), new File(getFilesDir(), "rootfs"));
        } catch (IOException e) {
            Log.e(TAG, "startProot failed", e);
            pushOutput("\r\n[启动失败] " + e + "\r\n");
        }
    }

    /**
     * 首次使用快捷配置：检查 guest 内 ~/.reasonix/config.toml 与 .env；
     * 缺失时弹出 API Key 输入对话框，保存后写入配置再启动 reasonix。
     */
    private void ensureReasonixConfig(File rootfs) {
        File home = new File(rootfs, "root/.reasonix");
        File cfg = new File(home, "config.toml");
        File env = new File(home, ".env");
        // reasonix 1.16+ 默认 [sandbox] bash = "enforce" 需要 bubblewrap（bwrap），
        // Android proot 环境无 bwrap 且 user namespace 被禁用 → 所有 shell 命令被拒。
        // 预置 ~/.config/reasonix/config.toml 将 bash 沙箱关闭。
        ensureSandboxDisabled(rootfs);
        if (cfg.exists() && env.exists()) {
            Log.d(TAG, "reasonix config already present, skipping dialog");
            safeStartProot();
            promptMissingApiKeyIfNeeded(rootfs);   // 配置在但没 key 时也要引导（否则 reasonix 只报 missing env）
            return;
        }
        // 全新安装不再强制弹对话框：直接启动环境，随后按需自动引导配置 Key。
        Log.d(TAG, "reasonix config missing, starting without API key dialog");
        safeStartProot();
        promptMissingApiKeyIfNeeded(rootfs);
    }

    /** 本次进程是否已自动弹过「缺 API Key」面板（用户没填时不反复打扰，下次启动再引导） */
    private boolean autoKeyPromptDone = false;

    /**
     * 环境里一个 provider 的 API Key 都没配时，自动打开「API Key 配置」面板。
     * 全新安装下 reasonix 只会打印 "provider xxx: missing env XXX_API_KEY"，
     * 用户无从下手；这里直接把人引导到面板（保存后环境自动重启生效）。
     */
    private void promptMissingApiKeyIfNeeded(File rootfs) {
        try {
            if (autoKeyPromptDone) return;
            String missingEnv = missingDefaultProviderKey(rootfs);
            if (missingEnv == null) return;
            autoKeyPromptDone = true;
            pushOutput("\r\n[未配置 API Key：" + missingEnv + "，正在打开「API Key 配置」面板]\r\n"
                    + "[选择 Provider 并粘贴 API Key 后点「保存」，环境会自动重启生效]\r\n");
            runOnUiThread(() -> new android.os.Handler(getMainLooper()).postDelayed(() -> {
                try {
                    if (!isFinishing() && !isDestroyed()) showApiKeyConfigDialog();
                } catch (Exception ignored) {}
            }, 1500));   // 略延迟：让环境/首屏初始化先完成，避免面板被后置初始化覆盖
        } catch (Exception e) {
            Log.w(TAG, "missing api key prompt failed", e);
        }
    }

    /** 环境里没有任何 API Key 时返回默认 provider 的 .env 变量名（用于提示），否则 null */
    private String missingDefaultProviderKey(File rootfs) {
        File home = new File(rootfs, "root/.reasonix");
        File env = new File(home, ".env");
        // 只要配过任意一个 provider 的 key 就不再自动弹面板（避免打扰已配置用户）
        if (firstNonEmptyEnvVar(env) != null) return null;
        File conf = new File(home, "config.toml");
        String provider = parseDefaultModel(conf);   // config 不存在时返回内置默认 deepseek-flash
        String envVar = "DEEPSEEK_API_KEY";          // 内置默认 provider 的变量名
        for (ProviderInfo p : parseProviderInfos(conf)) {
            if (p.name != null && p.name.equals(provider)
                    && p.apiKeyEnv != null && !p.apiKeyEnv.isEmpty()) {
                envVar = p.apiKeyEnv;
                break;
            }
        }
        return envVar;
    }

    /** .env 中第一个非空变量的名（无文件/全空返回 null） */
    private String firstNonEmptyEnvVar(File env) {
        if (env == null || !env.exists()) return null;
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(env.toPath()),
                    StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (!t.substring(eq + 1).trim().isEmpty()) return t.substring(0, eq).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 破甲环境注入标记（infinite-gen-4 v0.4.0-hardened）：config.toml 中以此判断是否已注入，幂等 */
    private static final String BREAK_ARMOR_MARKER = "rsxm-break-armor-v0.4.0-hardened";
    /** 破甲内核内容锚点：v0.4.0-hardened 载荷特征串（escape-clause free kernel）。
     *  config 中 system_prompt 缺失该锚点（旧 v0.3.0 浅版 / 被 reasonix 重写 / 被改坏）
     *  时，即使标记残留也强制重注入——锚点自愈，保证破甲不因内容漂移而失效。 */
    private static final String ARMOR_ANCHOR = "escape-clause free kernel";

    /**
     * 渗透修复：[permissions] allow 白名单 + 动态 bash。
     * reasonix 的 plan 模式 bash 有独立 trust 门禁——非内置只读集命令即使 YOLO/bypassPermissions
     * 也会弹确认（"not in Reasonix's built-in read-only set"），导致 curl/nmap/ncat/python/sqlmap
     * 等渗透命令无法自动执行。此块把常用渗透/侦查/运维命令前缀加入 allow（never prompted），
     * allow_dynamic_bash=true 让 allow 覆盖 $() 命令替换与解释器 -c/-e。
     * 注意：不含 [desktop] 表头（TOML 禁止重复声明表），desktop 审批模式键单独插行处理。
     * 含 RSXM_PERM_ALLOW 标记，切裁/追加幂等。
     */
    private static final String PERMISSIONS_RSXM_BLOCK =
            "\n" +
            "# RSXM_PERM_ALLOW (rsxm-pentest-bypass)\n" +
            "[permissions]\n" +
            "allow = [\n" +
            "  \"Bash(curl:*)\", \"Bash(wget:*)\", \"Bash(nc:*)\", \"Bash(ncat:*)\", \"Bash(nmap:*)\",\n" +
            "  \"Bash(masscan:*)\", \"Bash(hydra:*)\", \"Bash(sqlmap:*)\", \"Bash(sqlite3:*)\",\n" +
            "  \"Bash(python:*)\", \"Bash(python3:*)\", \"Bash(pip:*)\", \"Bash(pip3:*)\",\n" +
            "  \"Bash(perl:*)\", \"Bash(ruby:*)\", \"Bash(php:*)\", \"Bash(node:*)\", \"Bash(java:*)\",\n" +
            "  \"Bash(sh:*)\", \"Bash(bash:*)\", \"Bash(busybox:*)\", \"Bash(ash:*)\",\n" +
            "  \"Bash(openssl:*)\", \"Bash(base64:*)\", \"Bash(xxd:*)\", \"Bash(od:*)\", \"Bash(hexdump:*)\",\n" +
            "  \"Bash(strings:*)\", \"Bash(objdump:*)\", \"Bash(readelf:*)\", \"Bash(gdb:*)\", \"Bash(r2:*)\", \"Bash(radare2:*)\",\n" +
            "  \"Bash(adb:*)\", \"Bash(apk:*)\", \"Bash(apt:*)\", \"Bash(ssh:*)\", \"Bash(scp:*)\", \"Bash(telnet:*)\",\n" +
            "  \"Bash(git:*)\", \"Bash(find:*)\", \"Bash(grep:*)\", \"Bash(egrep:*)\", \"Bash(fgrep:*)\", \"Bash(awk:*)\", \"Bash(sed:*)\",\n" +
            "  \"Bash(cat:*)\", \"Bash(head:*)\", \"Bash(tail:*)\", \"Bash(sort:*)\", \"Bash(uniq:*)\", \"Bash(xargs:*)\",\n" +
            "  \"Bash(jq:*)\", \"Bash(tcpdump:*)\", \"Bash(mktemp:*)\", \"Bash(timeout:*)\", \"Bash(setsid:*)\", \"Bash(nohup:*)\",\n" +
            "  \"Bash(id:*)\", \"Bash(whoami:*)\", \"Bash(uname:*)\", \"Bash(hostname:*)\", \"Bash(ps:*)\", \"Bash(env:*)\",\n" +
            "  \"Bash(ls:*)\", \"Bash(stat:*)\", \"Bash(file:*)\", \"Bash(echo:*)\", \"Bash(printf:*)\", \"Bash(cd:*)\",\n" +
            "  \"Bash(pwd:*)\", \"Bash(mkdir:*)\", \"Bash(cp:*)\", \"Bash(mv:*)\", \"Bash(rm:*)\", \"Bash(touch:*)\",\n" +
            "  \"Bash(ln:*)\", \"Bash(tar:*)\", \"Bash(unzip:*)\", \"Bash(gzip:*)\", \"Bash(gunzip:*)\", \"Bash(zip:*)\",\n" +
            "  \"Bash(df:*)\", \"Bash(du:*)\", \"Bash(mount:*)\", \"Bash(dd:*)\", \"Bash(nslookup:*)\", \"Bash(dig:*)\",\n" +
            "  \"Bash(host:*)\", \"Bash(ping:*)\", \"Bash(ip:*)\", \"Bash(ifconfig:*)\", \"Bash(ss:*)\", \"Bash(netstat:*)\",\n" +
            "  \"Bash(lsof:*)\", \"Bash(fuser:*)\", \"Bash(kill:*)\", \"Bash(pkill:*)\", \"Bash(sleep:*)\", \"Bash(date:*)\",\n" +
            "  \"Bash(su:*)\", \"Bash(sudo:*)\", \"Bash(reboot:*)\", \"Bash(shutdown:*)\",\n" +
            "]\n" +
            "allow_dynamic_bash = true\n";

    /** 读取内置破甲环境提示词资产（assets/break-armor-prompt.md），读失败返回空串 */
    private String readBreakArmorPrompt() {
        try (InputStream in = getAssets().open("break-armor-prompt.md");
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8").trim();
        } catch (IOException e) {
            Log.w(TAG, "read break-armor-prompt.md asset failed: " + e);
            return "";
        }
    }

    /** 确保 reasonix 的 bash 沙箱关闭（Android 无 bubblewrap，enforce 会拒绝所有 shell 命令） */
    private void ensureSandboxDisabled(File rootfs) {
        try {
            // 注意：reasonix 的配置路径是 ~/.reasonix/config.toml（实测 reasonix config 命令输出
            // "cli_metrics = ... (~/.reasonix/config.toml)"），不是 ~/.config/reasonix/！
            File conf = new File(new File(rootfs, "root/.reasonix"), "config.toml");
            conf.getParentFile().mkdirs();
            String content = conf.exists()
                    ? new String(java.nio.file.Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8)
                    : "";
            // 显式配置 [desktop] telemetry = false（非默认值 → reasonix 视为已确认遥测，消除"Accept and continue?"询问）
            if (content.contains("telemetry = false") || content.contains("telemetry = %v")
                    || content.matches("(?ms).*^\\s*telemetry\\s*=\\s*true.*")) {
                content = content.replaceAll("(?m)^\\s*telemetry\\s*=\\s*true\\s*#.*$", "telemetry = false")
                        .replaceAll("(?m)^\\s*telemetry\\s*=\\s*%v.*$", "telemetry = false");
                if (!content.contains("[desktop]")) {
                    content += "\n[desktop]\ntelemetry = false\n";
                }
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix telemetry set false");
            }
            // 破甲环境注入（infinite-gen-4 v0.4.0-hardened）：system_prompt 置顶写入完整破甲提示词。
            // 幂等：内容含 v0.4.0-hardened 锚点（escape-clause free kernel）则跳过（旧 v0.3.0 浅版 /
            // v0.5.0 / 被改坏内容同样触发迁移）；否则移除旧 system_prompt 块后重写，
            // 保证任何启动/更新后破甲环境都生效（reasonix 以顶层 system_prompt 键加载）。
            if (!content.contains(ARMOR_ANCHOR)) {
                String prompt = readBreakArmorPrompt();
                if (!prompt.isEmpty()) {
                    // 移除现有 system_prompt 键（多行 """ 或单行 "..."），避免残留旧能力说明/重复键
                    content = content.replaceAll("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "")
                            .replaceAll("(?m)^\\s*system_prompt\\s*=\\s*\"[^\"]*\"\\s*", "");
                    String block = "# " + BREAK_ARMOR_MARKER + "\nsystem_prompt = \"\"\"\n"
                            + prompt
                            + "\n\n--- 环境能力说明（RSXM 应用附加，按需使用，不要编造）：---\n"
                            + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                            + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                            + "\"\"\"\n\n";
                    content = block + content;
                    java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    Log.d(TAG, "reasonix break-armor system_prompt injected");
                }
            }
            // 渗透修复（reasonix plan 模式 bash trust 门禁）：非内置只读集的命令即使 YOLO 也会
            // 卡在确认（"This bash command is not in Reasonix's built-in read-only set"）。
            // 注入 [permissions] allow 列表覆盖渗透/侦查/运维常用命令前缀（never prompted），
            // 并开启 allow_dynamic_bash（allow 覆盖 $() 命令替换与解释器 -c/-e），
            // 再把 desktop 默认审批模式设为 yolo。幂等：含 RSXM_PERM_ALLOW 标记则跳过。
            // TOML 禁止重复声明表：无 [permissions] 段时追加完整块；已有段则按需插行合并。
            if (!content.contains("RSXM_PERM_ALLOW")) {
                boolean hasPermHead = java.util.regex.Pattern.compile("(?m)^\\s*\\[permissions\\]\\s*$").matcher(content).find();
                boolean hasAllowKey = java.util.regex.Pattern.compile("(?m)^\\s*allow\\s*=").matcher(content).find();
                if (!hasPermHead) {
                    content += PERMISSIONS_RSXM_BLOCK;
                } else if (!hasAllowKey) {
                    // 已有 [permissions] 段但无 allow 键：在段头下插 allow 数组 + allow_dynamic_bash
                    content = content.replaceFirst("(?m)^\\s*\\[permissions\\]\\s*$",
                            "[permissions]\nallow = [\n"
                                    + "  \"Bash(curl:*)\", \"Bash(wget:*)\", \"Bash(nc:*)\", \"Bash(ncat:*)\", \"Bash(nmap:*)\", \"Bash(masscan:*)\", \"Bash(hydra:*)\",\n"
                                    + "  \"Bash(sqlmap:*)\", \"Bash(sqlite3:*)\", \"Bash(python:*)\", \"Bash(python3:*)\", \"Bash(perl:*)\", \"Bash(ruby:*)\", \"Bash(php:*)\",\n"
                                    + "  \"Bash(sh:*)\", \"Bash(bash:*)\", \"Bash(busybox:*)\", \"Bash(ash:*)\", \"Bash(openssl:*)\", \"Bash(base64:*)\", \"Bash(adb:*)\",\n"
                                    + "  \"Bash(apk:*)\", \"Bash(ssh:*)\", \"Bash(scp:*)\", \"Bash(telnet:*)\", \"Bash(git:*)\", \"Bash(find:*)\", \"Bash(grep:*)\", \"Bash(awk:*)\", \"Bash(sed:*)\",\n"
                                    + "  \"Bash(cat:*)\", \"Bash(head:*)\", \"Bash(tail:*)\", \"Bash(jq:*)\", \"Bash(tcpdump:*)\", \"Bash(nc:*)\", \"Bash(nmap:*)\", \"Bash(su:*)\", \"Bash(sudo:*)\",\n"
                                    + "]\nallow_dynamic_bash = true\n");
                } else if (!content.contains("allow_dynamic_bash")) {
                    content = content.replaceFirst("(?m)^\\s*\\[permissions\\]\\s*$",
                            "[permissions]\nallow_dynamic_bash = true\n");
                } else {
                    // 用户已自定义 allow + allow_dynamic_bash：仅补标记注释，保留用户配置
                    content += "\n# RSXM_PERM_ALLOW (rsxm-pentest-bypass) user-allow kept\n";
                }
                content += "\n# RSXM_PERM_ALLOW (rsxm-pentest-bypass)\n";
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix [permissions] allow injected (pentest command bypass)");
            }
            // Guardian 禁用：独立幂等门（RSXM_GUARDIAN_OFF）。guardian_model 是顶层键——
            // 必须先剥离任何位置的 guardian_model 行（v1 曾误插到文件末尾表内），再插到第一个表头之前。
            // 不能挂在 RSXM_PERM_ALLOW 门下（该标记已存在时会整体短路导致迁移遗漏）。
            boolean guardianPresent = content.contains("guardian_model");
            boolean guardMarker = content.contains("RSXM_GUARDIAN_OFF");
            if (!guardMarker || (!guardianPresent)) {
                content = content.replaceAll("(?m)^\\s*guardian_model\\s*=.*$\\s*", "");
                java.util.regex.Matcher tm = java.util.regex.Pattern
                        .compile("(?m)^(\\s*\\[[a-zA-Z_][^\\]]*\\]\\s*$)").matcher(content);
                if (tm.find()) {
                    content = content.substring(0, tm.start())
                            + "# RSXM_GUARDIAN_OFF\nguardian_model = \"\"\n"
                            + content.substring(tm.start());
                } else {
                    content += "\nguardian_model = \"\"\n";
                }
                content += "\n# RSXM_GUARDIAN_OFF\n";
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix guardian_model disabled (top-level)");
            }
            // desktop 默认审批 danger-full-access：独立幂等门（RSXM_DESK_YOLO）。
            // reasonix 1.39.3 权限枚举为 read-only|workspace-write|danger-full-access|plan，
            // 旧值 yolo/auto 已退役；Full access 是 Android 无 bwrap 时 serve/会话
            // 允许非受限运行的显式选择（否则报 "shell sandbox requested but unavailable…"）。
            // 迁移：旧设备 config 已有 yolo 值 → 先清旧键再注入新值。
            if (!content.contains("default_tool_approval_mode")
                    || content.contains("default_tool_approval_mode = \"yolo\"")) {
                content = content.replaceAll("(?m)^\\s*default_tool_approval_mode\\s*=.*$\\n?", "");
                if (java.util.regex.Pattern.compile("(?m)^\\s*\\[desktop\\]\\s*$").matcher(content).find()) {
                    content = content.replaceFirst("(?m)^\\s*\\[desktop\\]\\s*$",
                            "[desktop]\ndefault_tool_approval_mode = \"danger-full-access\"");
                } else {
                    content += "\n[desktop]\ndefault_tool_approval_mode = \"danger-full-access\"\n";
                }
                content += "\n# RSXM_DESK_YOLO\n";
                java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "reasonix desktop danger-full-access injected");
            }
            // 破甲注入 v2（RSXM_ARMOR_V2 标记）：reasonix 只读 [agent] 表内的 system_prompt，
            // 顶层键被忽略（实测会话 system 仍是默认 prompt → 破甲不生效）。
            // 逻辑：移除旧顶层破甲块（含 rsxm-break-armor-v0.5.0 / v0.3.0-gen4 标记）与任何独立
            // system_prompt 块，然后把完整破甲提示词写入 [agent] 段（存在则段头下插行；不存在则追加新表）。
            // 触发条件：标记缺失 **或** 旧 v1 顶层块仍在（标记已写入但迁移未完成的旧状态）
            // **或** 内容锚点缺失（v0.3.0 浅版残留 / reasonix 更新重写 config 破坏内容 → 自愈重注入）。
            // 触发重注入：标记缺失 / 旧代残留（v0.5.0、v0.3.0-gen4）/ v1 顶层块
            // （rsxm-break-armor-v0.4.0-hardened）仍残留但内容无锚点（v1 浅版未迁移）。
            // 注意：不能仅凭锚点缺失就覆盖——用户「保存并应用」自定义破甲指令后
            // [agent] 注入已存在（RSXM_ARMOR_V2）且无 v1 顶层块，此时无锚点属于自定义
            // 内容，尊重用户配置，不得被内置版覆盖。
            if (!content.contains("RSXM_ARMOR_V2") || content.contains("# rsxm-break-armor-v0.5.0")
                    || content.contains("# rsxm-break-armor-v0.3.0-gen4")
                    || (content.contains(BREAK_ARMOR_MARKER) && !content.contains(ARMOR_ANCHOR))) {
                String prompt = readBreakArmorPrompt();
                if (!prompt.isEmpty()) {
                    // 1) 移除旧顶层破甲块（v1 注入：marker 行 + system_prompt 块；含各代标记）
                    content = content.replaceAll(
                            "(?ms)^\\s*# rsxm-break-armor-v(?:0\\.5\\.0|0\\.3\\.0-gen4|0\\.4\\.0-hardened)\\s*\\n\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "");
                    // 2) 移除任何独立 system_prompt 多行/单行块（避免重复键）
                    content = content.replaceAll("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\".*?\"\"\"\\s*", "")
                            .replaceAll("(?m)^\\s*system_prompt\\s*=\\s*\"[^\"]*\"\\s*", "");
                    // 3) system_prompt 写入 [agent] 段
                    String block = "system_prompt = \"\"\"\n" + prompt
                            + "\n\n--- 环境能力说明（RSXM 应用附加，按需使用，不要编造）：---\n"
                            + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                            + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                            + "\"\"\"\n";
                    if (java.util.regex.Pattern.compile("(?m)^\\s*\\[agent\\]\\s*$").matcher(content).find()) {
                        content = content.replaceFirst("(?m)^\\s*\\[agent\\]\\s*$",
                                java.util.regex.Matcher.quoteReplacement("[agent]\n"+block+"\n"));
                    } else {
                        content += "\n[agent]\n" + block + "\n";
                    }
                    content += "# RSXM_ARMOR_V2 (rsxm-break-armor in [agent])\n";
                    java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    Log.d(TAG, "reasonix break-armor system_prompt injected into [agent]");
                }
            }
            if (content.contains("bash = \"off\"")) {
                Log.d(TAG, "reasonix sandbox already disabled");
                return;
            }
            if (content.contains("bash =")) {
                content = content.replaceAll("bash\\s*=\\s*\"[a-z]*\"", "bash = \"off\"");
            } else {
                content += "\n[sandbox]\nbash = \"off\"\n";
            }
            java.nio.file.Files.write(conf.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "reasonix sandbox bash disabled: " + conf.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "failed to disable reasonix sandbox", e);
        }
    }

    /** 写入 reasonix 配置（config.toml + .env，DeepSeek provider）；文件很小，同步执行 */
    private void writeReasonixConfig(File home, File cfg, File env, String apiKey) {
        try {
            home.mkdirs();
            String prompt = readBreakArmorPrompt();
            if (prompt.isEmpty()) {
                prompt = "环境能力说明（按需使用，不要编造）：\n"
                        + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                        + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n";
            }
            String configToml = "# " + BREAK_ARMOR_MARKER + "\n"
                    + "system_prompt = \"\"\"\n"
                    + prompt
                    + "\n\n--- 环境能力说明（RSXM 应用附加，按需使用，不要编造）：---\n"
                    + "1. 本应用内置 adb 无线调试：可直接执行 adb 命令（先 adb devices 查看连接状态，已连接时可用 adb shell / adb install 等）。\n"
                    + "2. 本应用内置手机 root 权限桥：执行 `root <命令>` 以 root 权限运行宿主手机命令（如 root id、root 'pm list packages'）。\n"
                    + "\"\"\"\n"
                    + "\n"
                    + "default_model = \"deepseek-flash\"\n"
                    + "\n"
                    + "[desktop]\n"
                    + "telemetry = false\n"
                    + "\n"
                    + "[[providers]]\n"
                    + "name        = \"deepseek-flash\"\n"
                    + "kind        = \"openai\"\n"
                    + "base_url    = \"https://api.deepseek.com\"\n"
                    + "model       = \"deepseek-v4-flash\"\n"
                    + "api_key_env = \"DEEPSEEK_API_KEY\"\n"
                    + "\n"
                    + "[sandbox]\n"
                    + "bash = \"off\"\n";
            try (FileOutputStream fo = new FileOutputStream(cfg)) {
                fo.write(configToml.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream fo = new FileOutputStream(env)) {
                fo.write(("DEEPSEEK_API_KEY=" + apiKey + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "reasonix config written: " + cfg.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "failed to write reasonix config", e);
        }
    }

    /** 覆盖安装后刷新可更新的 assets（entry.sh/reasonix/pty-bridge 随 APK 版本更新） */
    private void refreshAssets(File files, File rootfs) throws IOException {
        // reasonix：APK 升级后重新部署内置版本。
        // 旧逻辑"仅在文件不存在时部署"会让存量设备永久停留在旧版 —— 例如 v1.31.4 既不认 serve 的
        // -addr，也没有 GUI 回显所需的 transcript 投影，导致 serve 起不来、GUI 空。
        // 这里以"部署时的 APK versionName"作标记：APK 升级 → 标记不符 → 覆盖为内置版本；
        // 同一 APK 内重复启动不重复写入（49MB 二进制）。
        File rx = new File(rootfs, "usr/local/bin/reasonix");
        File rxStamp = new File(new File(rootfs, "usr/local/bin"), ".rsxm-reasonix-apk-ver");
        String stamp = "";
        try {
            if (rxStamp.exists()) {
                stamp = new String(java.nio.file.Files.readAllBytes(rxStamp.toPath()),
                        StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {}
        if (!rx.exists() || !appVersionName().equals(stamp)) {
            deployBundledReasonix(rootfs);
            Log.d(TAG, "reasonix deployed from bundle (apk=" + appVersionName()
                    + " bundled=" + BUNDLED_REASONIX_VERSION + ")");
        } else {
            Log.d(TAG, "reasonix already deployed for this apk version, keep current");
        }
        ensureBundledPluginDeployed(rootfs);   // 内置插件随 APK 版本刷新
        // pty-bridge：先删除再写入（旧环境进程可能仍 exec 着该文件，
        // 直接覆盖(O_TRUNC)报 ETXTBSY；unlink 正在执行的 inode 合法）
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        bridge.getParentFile().mkdirs();
        bridge.delete();
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);
        // entry.sh
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);
        // 破甲环境提示词（infinite-gen-3 v0.5.0）：提取到 guest，供 entry.sh 注入全局 skill/指令
        File armor = new File(new File(rootfs, "root/.reasonix"), "break-armor-prompt.md");
        armor.getParentFile().mkdirs();
        extractAsset("break-armor-prompt.md", armor);
        Log.d(TAG, "break-armor-prompt.md deployed to rootfs");
        // dsh purge 面板资产（about / prompt-inject 模板 / 默认规则）：随 APK 刷新
        deployPurgeAssets(rootfs);
        // AI 破甲工具箱（AITEST8 融合）：技能包/教程/关于部署（幂等）
        deployAITest8Assets(rootfs);
        // DS2API 网关（内置上游 AGPL-3.0 服务端，见 assets/ds2api/README-upstream.md）：
        // 覆盖刷新整个 ds2api 目录（删除再解压，保证升级后二进制/WebUI 与 APK 一致）
        File ds2Dir = new File(rootfs, "usr/local/ds2api");
        deleteRecursive(ds2Dir);
        File ds2Bundle = new File(files, "ds2api-bundle.tgz");
        extractAsset("ds2api/ds2api-bundle.tgz", ds2Bundle);
        File ds2Root = new File(rootfs, "usr/local");
        ds2Root.mkdirs();
        // toybox tar 可能报「can't remove / settime / had errors」exit=1 但内容已落地——
        // 不让 runCmd 抛异常打断初始化；解析结果按 ds2api 二进制是否就位分流：
        // 就位 → chmod 继续；缺失 → root tar 兜底（可跨 uid 删除残留 root 属主 inode）。
        boolean tarMiss = false;
        try {
            Process tp = new ProcessBuilder("/system/bin/tar", "-xzf",
                    ds2Bundle.getAbsolutePath(), "-C", ds2Root.getAbsolutePath())
                    .redirectErrorStream(true).start();
            // 持续排水到 EOF 再限时等待（v2.0.25 修复：旧实现只 read 一次 4096 字节，
            // 输出超过管道缓冲时 tar 写阻塞 → waitFor 永久卡住环境启动）
            String tout = drainProcessOutput(tp, 20);
            int tcode = tp.exitValue();
            if (tcode != 0) {
                Log.w(TAG, "toybox tar exit=" + tcode + " out=" +
                        (tout.isEmpty() ? "(no out)" : tout));
                tarMiss = true;
            }
        } catch (Exception te) {
            Log.w(TAG, "toybox tar exec failed", te);
            tarMiss = true;
        }
        File bin = new File(ds2Dir, "ds2api");
        if (bin.exists() && bin.length() > 1024) {
            bin.setExecutable(true, false);
            bin.setWritable(true, false);
            bin.setReadable(true, false);
        } else if (tarMiss) {
            String out = execRootCommand("mkdir -p " + ds2Root.getAbsolutePath() + "; tar -xzf "
                    + ds2Bundle.getAbsolutePath() + " -C " + ds2Root.getAbsolutePath()
                    + " && chmod -R a+rwX " + ds2Dir.getAbsolutePath() + "; "
                    + "test -x " + bin.getAbsolutePath() + " && echo TAR_OK || echo TAR_MISS", 15);
            Log.w(TAG, "root tar fallback: " + out);
        }
        ds2Bundle.delete();
        Log.d(TAG, "runtime assets refreshed (tarMiss=" + tarMiss + ")");
    }

    /** 部署 dsh purge 面板资产（about / prompt-inject / 规则 / 备用载荷 / 漏洞库 / skill 包）
     *  到 rootfs。skill 包部署到 ~/.reasonix/skills/redteam/（reasonix 自动加载）。
     *  幂等：已存在不覆盖（尊重用户编辑）；APK 升级时由 refreshAssets 全量刷新。 */
    private void deployPurgeAssets(File rootfs) {
        try {
            File base = new File(new File(rootfs, "root/.reasonix"), "purge");
            extractAsset("purge/purge-about.md", new File(base, "purge-about.md"));
            extractAsset("purge/prompt-inject.md", new File(base, "prompt-inject.md"));
            File tpl = new File(new File(base, "rules"), "rsxm-default.md");
            extractAsset("purge/rules/rsxm-default.md", tpl);
            // 破甲备用载荷（三份与 canonical 逐字节一致的 v0.4.0-hardened）：不同模型可换
            File promptsDir = new File(new File(base, "prompts"), "infinite-gen-4.1-flash.md");
            extractAsset("purge/prompts/infinite-gen-4.1-flash.md", promptsDir);
            File p4 = new File(new File(base, "prompts"), "infinite-gen-4.md");
            extractAsset("purge/prompts/infinite-gen-4.md", p4);
            File p3 = new File(new File(base, "prompts"), "infinite-gen-3.md");
            extractAsset("purge/prompts/infinite-gen-3.md", p3);
            // 漏洞库（~/.reasonix/purge/vulndb/）：内置速查，reasonix 可按 /vulndb 检索
            File vulnDir = new File(new File(base, "vulndb"), "00-index.md");
            extractAsset("purge/vulndb/00-index.md", vulnDir);
            for (String vf : new String[]{"web-injection.md", "web-logic.md", "auth-identity.md",
                    "intranet-post.md", "cloud-mobile.md", "cve-quick.md"}) {
                extractAsset("purge/vulndb/" + vf, new File(new File(base, "vulndb"), vf));
            }
            // 红队 skill 包（dsh-purge 上游 23 个）：部署到 reasonix 全局 skills，自动加载
            File redteamDir = new File(new File(new File(rootfs, "root/.reasonix"),
                    "skills/redteam"), "recon-pipeline.md");
            redteamDir.getParentFile().mkdirs();
            String[] redteamSkills = getAssets().list("purge/skills/redteam");
            if (redteamSkills != null) {
                for (String sf : redteamSkills) {
                    if (sf.endsWith(".md")) {
                        extractAsset("purge/skills/redteam/" + sf,
                                new File(redteamDir.getParentFile(), sf));
                    }
                }
            }
            // 免杀对抗 skill 包（shangdi-w/-skills · redteam-av-evasion）：SKILL.md + references/
            File avDir = new File(new File(new File(rootfs, "root/.reasonix"),
                    "skills/av-evasion"), "SKILL.md");
            avDir.getParentFile().mkdirs();
            extractAsset("purge/skills/av-evasion/SKILL.md", avDir);
            File avRefDir = new File(new File(new File(rootfs, "root/.reasonix"),
                    "skills/av-evasion/references"), "00-目录与速查.md");
            avRefDir.getParentFile().mkdirs();
            String[] avRefs = getAssets().list("purge/skills/av-evasion/references");
            if (avRefs != null) {
                for (String rf : avRefs) {
                    if (rf.endsWith(".md")) {
                        extractAsset("purge/skills/av-evasion/references/" + rf,
                                new File(avRefDir.getParentFile(), rf));
                    }
                }
            }
            // 全平台反编译 skill（shangdi-w/-skills · hacker-asm-decompile）
            File decDir = new File(new File(new File(rootfs, "root/.reasonix"),
                    "skills/hacker-asm-decompile"), "SKILL.md");
            decDir.getParentFile().mkdirs();
            extractAsset("purge/skills/decompile/SKILL.md", decDir);
            // 红队技能索引（din4e/Skills4RedTeam · MIT 社区推荐清单）
            extractAsset("purge/skills-index.md",
                    new File(new File(rootfs, "root/.reasonix/purge"), "skills-index.md"));
            extractAsset("purge/skills-index-LICENSE.txt",
                    new File(new File(rootfs, "root/.reasonix/purge"), "skills-index-LICENSE.txt"));
            // 规则集：默认模板 + 红队操作规范（已存在不覆盖，尊重用户编辑）
            File rulesDir = new File(new File(new File(rootfs, "root/.reasonix"), "rules"),
                    "rsxm-default.md");
            rulesDir.getParentFile().mkdirs();
            if (!rulesDir.exists()) {
                extractAsset("purge/rules/rsxm-default.md", rulesDir);
            }
            File roRules = new File(new File(new File(rootfs, "root/.reasonix"), "rules"),
                    "redteam-operations.md");
            if (!roRules.exists()) {
                extractAsset("purge/rules/redteam-operations.md", roRules);
            }
            Log.d(TAG, "dsh-purge assets deployed to rootfs");
        } catch (java.io.IOException e) {
            Log.w(TAG, "deployPurgeAssets failed", e);
        }
    }

    /** ADB 无线调试持久化：把本机局域网 IP 写入 guest 持久文件，供 entry.sh 自动重连使用 */
    private void writeAdbIpFile(File rootfs) {
        try {
            String ip = getLocalIpAddress();
            if (ip == null) {
                Log.w(TAG, "no local IP, skip adb_ip");
                return;
            }
            File f = new File(rootfs, "root/.adb_ip");
            java.nio.file.Files.write(f.toPath(), ip.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "adb_ip written: " + ip);
        } catch (Exception e) {
            Log.w(TAG, "writeAdbIpFile failed", e);
        }
    }

    /** 首次启动：解压 Alpine rootfs、部署 proot/pty-bridge/reasonix/启动脚本 */
    private void setupEnvironment(File files, File rootfs) throws IOException {
        pushOutput("首次启动，正在解压 Linux 环境（约 30 秒）...\r\n");
        rootfs.mkdirs();

        // 1. 解压 Alpine rootfs（使用系统 toybox tar，自动处理符号链接）
        //    注意：asset 名不能以 .gz 结尾（AGP 打包时会自动解压改名），
        //    内容本身是 gzip 压缩的 tar，改名为 rootfs.tar 保留。
        File tarFile = new File(files, "rootfs.tar");
        extractAsset("rootfs.tar", tarFile);
        runCmd("/system/bin/tar", "-xzf", tarFile.getAbsolutePath(), "-C", rootfs.getAbsolutePath());
        tarFile.delete();

        // 2. rootfs 解压完成（proot 与依赖库打包在 APK native libs 中，无需解压）

        // 3. reasonix、pty-bridge 部署进 rootfs（/usr/local/bin 与 /usr/bin）
        if (!deployBundledReasonix(rootfs)) {
            throw new IOException("内置 reasonix 部署失败：" + reasonixDeployError);
        }
        ensureBundledPluginDeployed(rootfs);   // 内置插件（破甲内核）：首次安装即就位（带版本标记，后续启动不重解压）
        File bridge = new File(rootfs, "usr/bin/pty-bridge");
        bridge.delete();   // 防残留 exec 导致 ETXTBSY（unlink 正在执行的 inode 合法）
        extractAsset("usr/bin/pty-bridge", bridge);
        bridge.setExecutable(true, false);

        // 4. 启动脚本
        File entry = new File(rootfs, "root/entry.sh");
        extractAsset("root/entry.sh", entry);
        entry.setExecutable(true, false);

        // 4.3 破甲环境提示词（infinite-gen-3 v0.5.0）：提取到 guest，供 entry.sh 注入全局 skill/指令
        File armor = new File(new File(rootfs, "root/.reasonix"), "break-armor-prompt.md");
        armor.getParentFile().mkdirs();
        extractAsset("break-armor-prompt.md", armor);
        Log.d(TAG, "break-armor-prompt.md deployed (first time)");
        // dsh purge 面板资产（about / prompt-inject 模板 / 默认规则）：首次安装即就位
        deployPurgeAssets(rootfs);
        // AI 破甲工具箱（AITEST8 融合）：技能包/教程/关于部署（幂等）
        deployAITest8Assets(rootfs);

        // 4.5 DS2API 网关（内置上游 AGPL-3.0 服务端）：解压 ds2api-bundle.tgz 到 /usr/local/ds2api
        //     （bundle 内含 ds2api 二进制 + static WebUI + LICENSE + README.MD）
        File ds2Bundle = new File(files, "ds2api-bundle.tgz");
        extractAsset("ds2api/ds2api-bundle.tgz", ds2Bundle);
        File ds2Root = new File(rootfs, "usr/local");
        ds2Root.mkdirs();
        // setup 首次解压与 refresh 同策略：失败不致命，root 兜底
        boolean firstTarMiss = false;
        try {
            Process tp2 = new ProcessBuilder("/system/bin/tar", "-xzf",
                    ds2Bundle.getAbsolutePath(), "-C", ds2Root.getAbsolutePath())
                    .redirectErrorStream(true).start();
            // 同 refreshAssets：排水到 EOF + 限时等待，防输出超缓冲卡死启动
            String tout2 = drainProcessOutput(tp2, 20);
            int tc2 = tp2.exitValue();
            if (tc2 != 0) {
                Log.w(TAG, "setup tar exit=" + tc2 + " out=" +
                        (tout2.isEmpty() ? "(no out)" : tout2));
                firstTarMiss = true;
            }
        } catch (Exception te2) {
            Log.w(TAG, "setup tar exec failed", te2);
            firstTarMiss = true;
        }
        File firstBin = new File(ds2Root, "ds2api/ds2api");
        if (!(firstBin.exists() && firstBin.length() > 1024) && firstTarMiss) {
            execRootCommand("mkdir -p " + ds2Root.getAbsolutePath() + "; tar -xzf "
                    + ds2Bundle.getAbsolutePath() + " -C " + ds2Root.getAbsolutePath()
                    + " && chmod -R a+rwX " + ds2Root.getAbsolutePath() + "/ds2api", 15);
        }
        ds2Bundle.delete();

        // 5. DNS 配置
        File resolv = new File(rootfs, "etc/resolv.conf");
        if (!resolv.exists()) {
            try (FileOutputStream fo = new FileOutputStream(resolv)) {
                fo.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        new File(files, ".installed").createNewFile();
        pushOutput("环境就绪。\r\n");
    }

    /** 启动 proot（从 nativeLibraryDir 执行）-> Alpine -> pty-bridge(PTY) -> entry.sh -> reasonix */
    /** 清理历史孤儿 guest 进程：多代环境重启/force-stop 后 proot 被杀，guest 内
     *  entry.sh/pty-bridge 变孤儿（PPID=1）累积，多套并存导致 adb 服务循环(.adb-cmd/.adb-out)
     *  多实例竞争、reasonix 会话冲突。环境启动前用真实 root 强制清理（仅当非后台复用场景）。 */
    private void cleanupOrphanGuests() {
        try {
            String su = findSuPath();
            if (su != null) {
                Process p = new ProcessBuilder(su, "-c",
                        // 同上一处：方括号防止 pkill -f 匹配到执行本命令的 shell 自身
                        "pkill -9 -f '[e]ntry.sh' 2>/dev/null; "
                                + "pkill -9 -f '[p]ty-bridge' 2>/dev/null; "
                                + "pkill -9 -f '[p]root.so' 2>/dev/null; "
                                + "pkill -9 -f '[r]easonix.bin' 2>/dev/null; true")
                        .redirectErrorStream(true).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanup orphan guests failed", e);
        }
    }

    /** 项目元数据目录权限归一：proot/chroot 运行模式切换后 projects 下项目目录 owner 不一致
     *  （chroot 创建的为 root 属主 700，proot 下 guest root=app uid 无 CAP_FOWNER 无法 chmod，
     *   reasonix 创建 sessions 报 permission denied）。环境启动前用真实 root 统一放开读写权限。 */
    private void normalizeProjectPerms() {
        try {
            File rootfs = new File(getFilesDir(), "rootfs");
            String r = rootfs.getAbsolutePath();
            String su = findSuPath();
            if (su != null) {
                Process p = new ProcessBuilder(su, "-c",
                        "chmod -R a+rwx " + r + "/root/.reasonix/projects/ 2>/dev/null")
                        .redirectErrorStream(true).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "normalize project perms failed", e);
        }
    }

    private void startProot(File files, File rootfs) throws IOException {
        // 防重复启动：环境已在运行时直接跳过（onCreate 已 kill 旧环境，此为双保险；
        // 后台运行模式复用场景 environmentStarted=true 已挡在启动前）
        if (sProotProcess != null && sProotProcess.isAlive()) {
            Log.d(TAG, "proot already running, skip start");
            return;
        }
        // 启动前清理孤儿 guest 进程 + 归一项目元数据权限（真实 root）
        cleanupOrphanGuests();
        normalizeProjectPerms();
        // chroot 模式：root 直接 chroot 进 rootfs（无 ptrace/seccomp 层，进程为 ksu(root) 域，
        // enforcing 下 JVM/apk 均正常，无需 setenforce 0）。无 root 时回退 proot。
        if (isChrootMode()) {
            String su = findSuPath();
            if (su == null) {
                Log.w(TAG, "chroot requires root, fallback to proot");
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("run_mode", "proot").apply();
            } else {
                startChroot(su, rootfs);
                return;
            }
        }
        // SELinux 只允许 app 执行 APK native libs 目录（apk_data_file）里的 ELF，
        // 因此 proot、libtalloc、libandroid-shmem、loader 全部打包在 jniLibs，
        // 经 useLegacyPackaging 解压到 nativeLibraryDir 后从这里直接执行。
        // rootfs 内的 guest 二进制（busybox/pty-bridge/reasonix）由 proot 的
        // loader 机制读取装载，不走宿主 execve，天然绕过该限制。
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String proot = nativeLibDir + "/proot.so";
        // 自检：native 库缺失多为安装时解压失败（部分厂商安装器/流式安装不解压 lib）。
        // 有 root 时自动从 APK 提取并修复（复制 + chcon 回 apk_data_file 域，否则 SELinux 拒绝执行）
        if (!new File(proot).exists()) {
            boolean repaired = tryRepairNativeLib(nativeLibDir, getApplicationInfo().sourceDir);
            if (!repaired) {
                // 首次失败多为 KernelSU/Magisk 授权弹窗尚未确认（授权前 su 对应用不可见）。
                // 等待授权：su 可见后立即重试；若 su 一直不可见（无 root 设备）快速退出，
                // 避免长时间阻塞启动
                for (int i = 0; i < 6 && !repaired; i++) {
                    try { Thread.sleep(6000); } catch (InterruptedException e) { break; }
                    if (findSuPath() == null) break;   // 无 root，退出重试
                    Log.d(TAG, "su now visible, retry native lib repair");
                    repaired = tryRepairNativeLib(nativeLibDir, getApplicationInfo().sourceDir);
                }
            }
            if (repaired) {
                Log.d(TAG, "native lib auto-repaired, continue start");
                pushOutput("\r\n[native 库缺失，已通过 root 自动修复，正在启动...]\r\n");
            } else {
                // 无 root：首次引导覆盖安装；若已引导过一次仍缺失（如 vivo/iQOO 安装器
                // 不解压 native lib），不再重复弹窗（防无限循环），给出明确处理指引
                SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);
                if (!p.getBoolean("reinstall_prompted", false)) {
                    p.edit().putBoolean("reinstall_prompted", true).apply();
                    promptReinstallForNativeLib();
                } else {
                    pushOutput("\r\n[native 库缺失：已尝试覆盖安装但未生效（该手机安装器可能不解压 native 库）。\r\n"
                            + "请用电脑 adb install --no-streaming 安装本 APK，或用有 root 的设备自动修复]\r\n");
                }
                return;
            }
        } else {
            // 启动正常：清除重装引导标记
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit().putBoolean("reinstall_prompted", false).apply();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("-0");                                   // 伪装 root（Alpine 文件属主为 root）
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath()); // 新根目录
        cmd.add("-b"); cmd.add("/dev");                  // 绑定宿主设备（PTY 需要 /dev/ptmx）
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/storage/emulated/0:/sdcard"); // 手机共享存储
        // 沙箱增强：绑定宿主 app 私有数据目录（可读写）与宿主只读系统分区（放宽读访问）
        cmd.add("-b"); cmd.add("/data/data/" + getPackageName() + ":/host-data");
        String ownAndroidData = "/storage/emulated/0/Android/data/" + getPackageName();
        if (new File(ownAndroidData).exists()) {
            cmd.add("-b"); cmd.add(ownAndroidData + ":/sdcard/Android/data/" + getPackageName());
        }
        String[] hostRo = {"/system", "/product", "/apex"};
        for (String h : hostRo) {
            if (new File(h).exists()) {
                cmd.add("-b"); cmd.add(h + ":/host" + h);
            }
        }
        cmd.add("-w"); cmd.add("/root");                 // 初始工作目录
        cmd.add("/bin/sh"); cmd.add("-c"); cmd.add("/usr/bin/pty-bridge /bin/sh /root/entry.sh");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // proot 需要 execve 它的 loader；loader 放在 nativeLibraryDir（apk_data_file，
        // 允许 exec）。PROOT_LOADER 让 proot 直接使用外部 loader（不提取到 TMPDIR，
        // 提取位置若在 app_data_file 会被 SELinux 拒绝执行）。
        // PROOT_TMP_DIR 指向可写目录（proot 的宿主侧临时文件），filesDir 可写。
        pb.environment().put("PROOT_LOADER", nativeLibDir + "/loader.so");
        pb.environment().put("TMPDIR", nativeLibDir);
        pb.environment().put("PROOT_TMP_DIR", files.getAbsolutePath());
        sProotProcess = pb.start();
        sProcIn = sProotProcess.getOutputStream();
        startRootPolling();   // 启动 root 命令桥轮询（guest root <cmd> → app su 执行）
        startEnvReader(sProotProcess);
    }

    /**
     * chroot 模式启动：su(root) 直接 chroot 进 rootfs。
     * 绑定宿主 /dev（ptmx 保留宿主 SELinux 类型）+ 挂 devpts（pty 终端）+ proc/sys，
     * chroot 运行 pty-bridge → entry.sh，退出后清理挂载。
     * chroot 进程为 ksu(root) 域：JVM(mprotect RWX)/apk(link) 在 enforcing 下均可，
     * 保持 SELinux 开启（无需 setenforce 0）。
     */
    private void startChroot(String su, File rootfs) throws IOException {
        String r = rootfs.getAbsolutePath();
        // 绑定宿主 /dev + devpts + proc + sys + 手机存储 /storage/emulated/0 → /sdcard
        // （不绑 /sdcard 时 chroot 内 /sdcard 是 rootfs 内的空目录，手机目录创建的文件
        //   在文件管理器看不到——与 proot 的 -b /storage/emulated/0:/sdcard 对齐）
        String script = "mkdir -p " + r + "/dev/pts " + r + "/proc " + r + "/sys " + r + "/sdcard; "
                + "mount --bind /dev " + r + "/dev 2>/dev/null; "
                + "mount -t devpts -o gid=5,mode=620 devpts " + r + "/dev/pts 2>/dev/null; "
                + "mount --bind /proc " + r + "/proc 2>/dev/null; "
                + "mount --bind /sys " + r + "/sys 2>/dev/null; "
                + "mount --bind /storage/emulated/0 " + r + "/sdcard 2>/dev/null; "
                + "chroot " + r + " /usr/bin/pty-bridge /bin/sh /root/entry.sh; RC=$?; "
                + "umount " + r + "/dev/pts 2>/dev/null; umount " + r + "/dev 2>/dev/null; "
                + "umount " + r + "/proc 2>/dev/null; umount " + r + "/sys 2>/dev/null; "
                + "umount " + r + "/sdcard 2>/dev/null; exit $RC";
        ProcessBuilder pb = new ProcessBuilder(su, "-c", script);
        pb.redirectErrorStream(true);
        pb.environment().put("RSXM_CHROOT", "1");
        Log.d(TAG, "chroot started via " + su);
        sProotProcess = pb.start();
        sProcIn = sProotProcess.getOutputStream();
        startRootPolling();
        startEnvReader(sProotProcess);
    }

    /** 启动环境输出 reader：把进程 stdout 转发到终端（proot/chroot 共用）。
     *  用 CharsetDecoder 增量解码：按块 new String(buf,0,n,UTF_8) 会把多字节 UTF-8
     *  字符在块边界截断成 U+FFFD 乱码（中文/emoji 显示混乱的根本原因）。 */
    /** 增量 UTF-8 解码器（v2.0.25 修复中文乱码的根因）：跨读块保留不完整多字节序列。
     *  旧实现单参 dec.decode(ByteBuffer) 是「一次性完整解码」便捷方法——每次调用
     *  会 reset 解码器状态，多字节字符恰在读块边界被切开时仍解析成 U+FFFD
     *  （此前注释声称已修复，实际修复无效）。 */
    private static final class Utf8StreamDecoder {
        private final java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        private byte[] carry = new byte[0];

        /** 喂入一块新数据；endOfInput=true 时冲刷残留（流结束时调用） */
        String feed(byte[] buf, int n, boolean endOfInput) {
            try {
                byte[] all = new byte[carry.length + n];
                System.arraycopy(carry, 0, all, 0, carry.length);
                System.arraycopy(buf, 0, all, carry.length, n);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all);
                StringBuilder out = new StringBuilder();
                java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(512);
                while (bb.hasRemaining()) {
                    java.nio.charset.CoderResult r = dec.decode(bb, cb, endOfInput);
                    cb.flip();
                    out.append(cb);
                    cb.clear();
                    if (r.isUnderflow()) break;
                    if (r.isMalformed() || r.isUnmappable()) r.throwException(); // REPLACE 下不会触发
                }
                int rem = bb.remaining();
                carry = new byte[rem];
                System.arraycopy(all, bb.position(), carry, 0, rem);
                return out.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    private void startEnvReader(Process p) {
        // reader 绑定启动时刻的进程（局部捕获），避免重启环境后读到新进程的流
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                Utf8StreamDecoder dec = new Utf8StreamDecoder();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String chunk = dec.feed(buf, n, false);
                    if (!chunk.isEmpty()) pushOutput(chunk);
                }
                // 冲刷解码器残留（流结束时补出末尾字符）
                String tail = dec.feed(new byte[0], 0, true);
                if (!tail.isEmpty()) pushOutput(tail);
            } catch (IOException e) {
                Log.w(TAG, "reader ended", e);
            }
            // 环境被主动替换/重启（killProotTree → 新进程）时不输出误导性退出消息
            if (sProotProcess == p) {
                pushOutput("\r\n[Linux 环境已退出]\r\n");
            }
        }, "env-reader");
        reader.setDaemon(true);
        reader.start();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private void extractAsset(String assetPath, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 持续排水读取进程输出直到 EOF，限时 waitSec 秒；超时杀进程。返回全部输出（截断到 16KB）。
     *  修复模式：waitFor 前必须排空管道，否则输出超过管道缓冲（64KB）时子进程写阻塞卡死。 */
    private static String drainProcessOutput(Process p, int waitSec) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() >= 16 * 1024) break;   // 上限：日志足够，防内存膨胀
            }
        } catch (Exception ignored) {}
        try {
            if (!p.waitFor(waitSec, TimeUnit.SECONDS)) p.destroy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
        }
        String s = bos.toString();
        return s.length() > 16 * 1024 ? s.substring(0, 16 * 1024) : s;
    }

    /** 执行命令并把输出转发到终端；非零退出码抛异常 */
    private void runCmd(String... cmd) throws IOException {        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            Utf8StreamDecoder dec = new Utf8StreamDecoder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = dec.feed(buf, n, false);
                if (!chunk.isEmpty()) pushOutput(chunk);
            }
            String tail = dec.feed(new byte[0], 0, true);
            if (!tail.isEmpty()) pushOutput(tail);
        }
        int code;
        try {
            // 限时等待（v2.0.25：根fs 解压等被调命令挂起时不得永久阻塞环境启动线程）
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                code = -1;
            } else {
                code = p.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            code = -1;
        }
        if (code != 0) {
            throw new IOException("命令失败(exit=" + code + "): " + String.join(" ", cmd));
        }
    }

    /** 把文本追加到 xterm.js 终端（任意线程可调）；同时输出到 logcat 便于调试。
     *  输出转发到当前活动实例 sCurrent：后台 reader 线程在 Activity 重建后
     *  仍能把环境输出送到新实例的终端；无活动实例时直接丢弃（避免旧实例被
     *  daemon reader 长期持有无法 GC）。 */
    /** 后台期间缓存终端输出（WebView JS 后台暂停，直接 evaluateJavascript 会堆积阻塞
     *  → PTY 缓冲满 → reasonix 写阻塞超时 → 一直 retrying）；前台恢复时一次冲刷。
     *  上限 4MB：AI 长回复/长输出在后台期间不被截断；超出仍清空兜底防无限增长。 */
    private static final StringBuilder sPendingOutput = new StringBuilder();
    private static final int PENDING_OUTPUT_CAP = 4 * 1024 * 1024;   // 4MB 保护
    private static final int FLUSH_CHUNK = 512 * 1024;               // 冲刷分块，防 WebView 卡顿
    private static volatile boolean sWebActive = true;

    /** 原生会话视图（对话模式）：打开时接管显示，WebView 隐藏；关闭时还原 */
    private volatile boolean nativeViewOn = false;
    // ==================== 原生会话视图（GUI）：内容来自 serve 的 transcript 投影 ====================
    //
    // 唯一数据来源是 serve 的「transcript 投影」契约（Transcript v2 / ChatSource，与官方桌面版
    // 及 TUI 同源——TUI 只是这份投影的一个渲染器，见 reasonix 内嵌文档 TRANSCRIPT_PROJECTION.md）：
    //   GET  /transcript/follow    SSE 长连接：首帧 snapshot（全量窗口），后续帧 changes（增量）
    //   GET  /transcript/snapshot  一次性快照（进入视图时拉一次）
    //   POST /submit  /cancel  /new
    //
    // 旧的「PTY 字节流剥离 TUI 装饰」「会话 jsonl 增量解析」「/events + /history 文本拼接」三条通道
    // 全部删除：前两条拿到的本就是 TUI 渲染后的屏幕内容（边框/光标定位/状态行混入回显，即看到的
    // “混乱”），第三条只有整段纯文本、分不出消息/工具/思考。transcript record 自带
    // role / content / toolCalls / execution，结构化渲染即可，终端字节一律不再进 GUI。

    /** transcript 跟随客户端（SSE 长连接 + 断线重连） */
    private volatile TranscriptClient transcript;
    /** serve 引擎 / transcript 流是否在线 */
    private volatile boolean serveOnline = false;
    /** 当前会话模型引用（provider/model），取自 /status，用于 AI 气泡标签 */
    private volatile String serveModelRef = "";

    /** recordId → 气泡控件（同一 record 流式更新时原地更新，不重复插入） */
    private final java.util.Map<String, BubbleView> recordViews =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** toolCallId → 调用（name/arguments）：给 role=tool 的记录附上参数 */
    private final java.util.Map<String, TranscriptRecord.Call> callIndex =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** messageId → 流式正文缓冲（event 增量；对应 record 到达后以 record 为准） */
    private final java.util.Map<String, StringBuilder> streamBuf =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 已渲染记录的最大 order（用于判断新记录是否追加在末尾） */
    private long transcriptLastOrder = -1;
    /** 当前 transcript 会话标识（identity.sessionId）；变化时基线必须重建列表 */
    private volatile String transcriptSessionId = "";
    /** 上一帧的运行态（用于在回合结束时对齐一次基线：增量形式变化时兜底保证最终内容正确） */
    private volatile boolean transcriptRunning = false;
    /** 最近一次渲染的窗口内容签名（基线重复到达时跳过整表 setText，避免空转卡顿） */
    private String transcriptWindowSig = "";
    /** 最近一次内置 reasonix 部署失败原因（null = 成功/未尝试；诊断卡片会显示） */
    private volatile String reasonixDeployError = null;
    /** 本地即时回显的 user 气泡（服务端 user record 到达后移除，避免重复） */
    private volatile BubbleView pendingUserBubble;
    private volatile String pendingUserText = "";

    /** 生成进行中：发送后按钮切「停止」；turn_done / runtime_state(running=false) 复位 */
    private volatile boolean genInFlight = false;
    /** 用户正在触摸翻阅消息列表：置位期间暂停「自动滚到底部」抢占（v2.0.23 修复无法回看） */
    private volatile boolean userScrolling = false;
    private long lastUserScrollAt = 0;
    private static final String BTN_SEND = "发送";
    private static final String BTN_STOP = "⏹ 停止";
    /** 高级设置面板内的动态标签控件引用（状态刷新用；面板关闭后为 null） */
    private TextView advancedBgLabel;
    private TextView advancedYoloLabel;
    private TextView advancedSpeedLabel;

    private void pushOutput(String text) {
        Log.d(TAG, "OUT> " + (text.length() > 200 ? text.substring(0, 200) : text));
        final MainActivity target = sCurrent;
        if (target == null) return;
        // 原生视图的内容全部来自 serve 的 transcript 投影（record 级结构化）：终端字节流
        // （边框/光标定位/整屏重绘）一律不再进 GUI；要看 TUI 原文切到终端视图即可。
        if (target.nativeViewOn) return;
        if (!sWebActive) {
            synchronized (sPendingOutput) {
                if (sPendingOutput.length() > PENDING_OUTPUT_CAP) sPendingOutput.setLength(0);
                sPendingOutput.append(text);
            }
            return;
        }
        target.ui.post(() -> {
            if (target.webView == null) return;
            target.webView.evaluateJavascript("window.onTermData(" + jsQuote(text) + ")", null);
        });
    }

    // ---------------- serve 接入（启动 / transcript 跟随 / 快照） ----------------

    /** serve 引擎启动/探测进行中（防「重启」并发启动多个实例） */
    private volatile boolean serveStarting = false;

    // ------------------------------------------------------------------
    // serve 看门狗：切到其他 app（或被系统回收/冻结）后，服务掉了也能自己回来
    // ------------------------------------------------------------------

    /** 看门狗探测间隔：环境在跑时每 20 秒确认一次 serve 在线（一次 GET /status，开销极小） */
    private static final long SERVE_WATCHDOG_MS = 20000;
    /** 看门狗两次自动重启的最小间隔：serve 自身起不来时不要每 20 秒密集重启 */
    private static final long SERVE_RESTART_MIN_GAP_MS = 15000;
    /** 单次自动启动的尝试次数：环境刚重启时 guest 内 .adb-cmd 执行服务可能还没就绪，命令会落空 */
    private static final int SERVE_START_ATTEMPTS = 2;
    private boolean watchdogOn = false;
    private volatile long serveRestartAt = 0;
    /** 看门狗的连续失败次数（掉线重启仍在掉 → 指数拉长重启间隔，避免刷诊断卡片与反复重启 guest 命令） */
    private int serveRestartFails = 0;

    private final Runnable serveWatchdogTask = new Runnable() {
        @Override public void run() {
            if (!watchdogOn) return;
            try {
                probeServeAlive();
            } catch (Exception e) {
                Log.w(TAG, "serve watchdog failed", e);
            }
            if (watchdogOn) ui.postDelayed(this, SERVE_WATCHDOG_MS);
        }
    };

    /** 启动 serve 看门狗（幂等）：环境就绪 / Activity 复用环境时调用 */
    private void startServeWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        ui.postDelayed(serveWatchdogTask, SERVE_WATCHDOG_MS);
        Log.d(TAG, "serve watchdog started");
    }

    private void stopServeWatchdog() {
        watchdogOn = false;
        ui.removeCallbacks(serveWatchdogTask);
    }

    /**
     * serve 健康检查（宿主侧 GET /status，3 秒超时）：不在线就拆掉旧的 transcript 客户端并
     * 重新拉起引擎 —— 这是"切到其他 app 再回来 serve 还在"的兜底。
     *
     * <p>首次启动仍由 {@link #ensureServeStarted()} 负责（带启动重试与诊断卡片）；
     * 这里只做周期探测 + 掉线重启，并用最小重启间隔防抖动。
     */
    private void probeServeAlive() {
        if (!environmentStarted || !watchdogOn || serveStarting) return;
        new Thread(() -> {
            ReasonixServe client = serveClient();
            if (client.isUp()) {
                serveOnline = true;
                serveRestartFails = 0;      // 恢复正常：退避窗口复位
                return;
            }
            // 连续掉线时把重启间隔指数拉长（15s → 30s → 60s → 120s 封顶）：
            // serve 自身起不来（如缺 API Key）时不要每 20 秒重启一次、每 20 秒刷一张诊断卡片。
            long gap = Math.min(SERVE_RESTART_MIN_GAP_MS << Math.min(serveRestartFails, 3), 120_000L);
            long now = System.currentTimeMillis();
            if (now - serveRestartAt < gap) return;
            serveRestartAt = now;
            serveRestartFails = Math.min(serveRestartFails + 1, 4);
            Log.w(TAG, "serve watchdog: /status 不可达（连续 " + serveRestartFails
                    + " 次），自动重启引擎；下次最早 "
                    + Math.min(SERVE_RESTART_MIN_GAP_MS << Math.min(serveRestartFails, 3), 120_000L) / 1000
                    + " 秒后");
            stopTranscriptFollow();     // 旧客户端还在重连，先拆掉；ensureServeStarted 会重新接上
            ui.post(this::ensureServeStarted);
        }, "serve-probe").start();
    }

    /** 确保 serve 引擎在线并接上 transcript 跟随流（环境就绪后调用；幂等） */
    private void ensureServeStarted() {
        if (transcript != null && transcript.isRunning()) return;
        if (serveStarting) return;
        serveStarting = true;
        new Thread(() -> {
            ReasonixServe client = serveClient();
            String diag = "";
            boolean up;
            try {
                if (!client.isUp()) {
                    // 先确保 guest 内是内置版本 reasonix（写死参数的前提），再启动
                    ensureBundledReasonixDeployed(new File(getFilesDir(), "rootfs"));
                    String token = ReasonixServe.readToken(getFilesDir());
                    if (token.isEmpty()) token = ReasonixServe.generateToken();
                    // 宿主侧同步写一份 token（chroot 模式 root 属主时此步可能失败，靠 guest 侧兜底）
                    writeServeToken(token);
                    // 退避重试：环境刚（重）启动时 guest 内的 .adb-cmd 执行服务往往还没就绪，
                    // 启动命令会落空 —— 只尝试一次的话就是"打开了但 serve 不在线、只能手动点重启"。
                    for (int attempt = 0; attempt < SERVE_START_ATTEMPTS && !client.isUp(); attempt++) {
                        if (attempt > 0) {
                            try { Thread.sleep(3000L * attempt); } catch (InterruptedException e) { break; }
                        }
                        diag = executeInGuest(serveLaunchCommand(token), 15);
                        for (int i = 0; i < 15 && !client.isUp(); i++) {
                            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                        }
                    }
                }
                up = client.isUp();
                if (up) serveModelRef = parseServeModel(client.status());
            } catch (Exception e) {
                up = false;
                diag = "启动异常：" + e;
            }
            serveStarting = false;
            serveOnline = up;
            if (up) serveRestartFails = 0;   // 起来了：看门狗的退避窗口复位
            final boolean fUp = up;
            final String fDiag = diag;
            ui.post(() -> {
                TextView info = findViewById(R.id.native_session_info);
                if (info != null) {
                    info.setText(fUp ? "会话：reasonix serve 引擎" : "会话：serve 未在线（点此重启）");
                    info.setOnClickListener(fUp ? null : v -> restartServeEngine());
                }
                if (fUp) {
                    setNativeStatus("");
                    startTranscriptFollow();
                    loadTranscriptSnapshot();
                } else {
                    setNativeStatus("serve 未启动：诊断见下方（点顶部「会话」可重启）");
                    showServeFailureDiagnostics(fDiag);
                }
            });
        }, "serve-ensure").start();
    }

    /**
     * 写 serve token 的宿主侧副本。
     *
     * <p>权限必须收成 {@code 600}：serve 会拒绝 group/world 可读的 token 文件并直接退出
     * （实测报错 {@code token file ... must not be group/world accessible (chmod 600)}），
     * 而 {@code printf > file} / {@code Files.write} 在默认 umask 下是 644。
     * guest 侧启动命令里另有一道 {@code chmod 600} 兜底（proot 下两边是同一个文件）。
     */
    private void writeServeToken(String token) {
        try {
            java.nio.file.Path tp =
                    new File(getFilesDir(), "rootfs/root/.rsxm-serve-token").toPath();
            java.nio.file.Files.write(tp, (token + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.setPosixFilePermissions(tp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}   // 文件系统不支持 POSIX 权限时忽略（guest 侧还有 chmod）
        } catch (Exception ignored) {}
    }

    /**
     * 停止 serve 的 guest 命令：只按 serve 自己写的 pid 文件精准 kill。
     *
     * <p>**不要**改回 {@code pkill -f 'reasonix.*[s]erve'}：本方法产出的启动命令里同时含有
     * {@code command -v reasonix} 与字面 {@code "$RX" serve}，于是 {@code pkill -f} 的
     * 整条命令行匹配会命中**它自己所在的 sh 进程**（{@code [s]erve} 那个防自匹配技巧只在
     * 命令行里没有别的 "serve" 文本时才成立），命令会把自己 TERM 掉 —— 表现就是
     * 「token 写入了、但没有日志、没有端口文件、命令无输出」，v2.2.1 及以前从未启动成功过。
     */
    private String serveStopCommand() {
        return "if [ -s " + ReasonixServe.PID_FILE_GUEST + " ]; then kill \"$(cat "
                + ReasonixServe.PID_FILE_GUEST + ")\" 2>/dev/null; sleep 0.5; fi; "
                // 兜底：没有 pid 文件时（旧版、异常退出、进过 TUI 的残留）按可执行文件名清理，
                // 否则残留进程占着 8787 会让新实例绑定失败。方括号避免匹配到本命令自身。
                // 必须匹配 reasonix serve 与 reasonix.bin serve（wrapper exec 后 argv 是 .bin）：
                // 旧模式 '[r]easonix serve' 匹配不到 .bin，残留进程会让 bind: address already in use。
                + "pkill -f 'reasonix.*[s]erve' 2>/dev/null; "
                + "rm -f " + ReasonixServe.PORT_FILE_GUEST;
    }

    /**
     * guest 内启动 serve 的命令（GUI 自动启动与 Serve 面板「启动」按钮共用同一份）。
     *
     * <p>参数**写死**，前提是启动前已由 {@link #ensureBundledReasonixDeployed} 确保 guest 内
     * 就是内置版本（1.39.3，支持这一整套 flag）。这里**绝不做** version / `serve --help` 之类的探测：
     * 老版本 reasonix 会把不认识的子命令当成任务提示直接起会话，命令替换会挂住 → 整条命令超时，
     * 后续的 nohup 与日志重定向全都不执行（实测症状：`(执行超时)` + 无日志 + 无端口文件）。
     */
    private String serveLaunchCommand(String token) {
        return "printf '%s\\n' '" + sq(token) + "' > " + ReasonixServe.TOKEN_FILE_GUEST
                // serve 拒绝 group/world 可读的 token 文件（默认 umask 下是 644），必须先收紧
                + "; chmod 600 " + ReasonixServe.TOKEN_FILE_GUEST
                + "; " + serveStopCommand() + "; "
                + "cd /root; "
                + "RX=$(command -v reasonix 2>/dev/null || echo /usr/local/bin/reasonix); "
                // 纯变量展开，不执行外部命令（诊断卡片里能看到实际跑的是哪一份）
                + "echo \"[rsxm] RX=$RX\"; "
                + "nohup \"$RX\" serve --addr 127.0.0.1:8787 --auth token "
                // Full access：Android 无 bubblewrap 时 serve 会拒绝非受限运行
                // （"shell sandbox requested but unavailable… refusing to run unconfined"），
                // 显式 --permission-mode danger-full-access 选择非受限会话
                + "--permission-mode danger-full-access "
                + "--token-file " + ReasonixServe.TOKEN_FILE_GUEST + " "
                + "--port-file " + ReasonixServe.PORT_FILE_GUEST + " "
                + "--pid-file " + ReasonixServe.PID_FILE_GUEST + " "
                // 无浏览器环境：不带 --no-open 时 serve 会尝试打开 Web UI（guest 内无 xdg-open）
                + "--no-open </dev/null >" + ReasonixServe.LOG_FILE_GUEST
                + " 2>&1 & echo SERVE_STARTED";
    }

    /** 点顶部「会话」：强制重启 serve 引擎（停旧实例 → 重新探测/启动 → 重接 transcript） */
    private void restartServeEngine() {
        showToast("正在重启 serve 引擎…");
        stopTranscriptFollow();
        new Thread(() -> {
            executeInGuest(serveStopCommand() + "; echo KILLED", 8);
            ui.post(this::ensureServeStarted);
        }, "serve-restart").start();
    }

    /** 读 guest 内 serve 日志尾部（proot 模式 /root 映射到宿主 rootfs/root） */
    private String readGuestServeLogTail(int maxLines) {
        File f = new File(new File(new File(getFilesDir(), "rootfs"), "root"), ".rsxm-serve.log");
        if (!f.exists()) {
            return "(日志文件不存在：guest 内 " + ReasonixServe.LOG_FILE_GUEST + " 未生成 —— "
                    + "多半是启动命令没被执行，检查 .adb-cmd 执行服务是否在运行)";
        }
        try {
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] lines = all.split("\n", -1);
            int from = Math.max(0, lines.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                String ln = lines[i];
                if (ln.length() > 300) ln = ln.substring(0, 300) + "…";
                sb.append(ln).append('\n');
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? "(日志为空)" : s;
        } catch (Exception e) {
            return "(读日志失败：" + e + ")";
        }
    }

    /** 在会话列表顶部插一张可复制的诊断卡片（标题 + 等宽正文 + 复制按钮） */
    private void appendDiagnosticCard(String titleText, String body) {
        LinearLayout list = findViewById(R.id.native_output);
        if (list == null) return;
        Log.w(TAG, titleText + "\n" + body);
        final String text = titleText + "\n" + body;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        wrap.setLayoutParams(wlp);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(0xFFFFB86C);
        title.setTextSize(12);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        wrap.addView(title);

        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(0xFF8B949E);
        bodyView.setTextSize(11);
        bodyView.setTypeface(android.graphics.Typeface.MONOSPACE);
        bodyView.setBackgroundColor(0xFF11161C);
        bodyView.setPadding(dp(8), dp(6), dp(8), dp(6));
        wrap.addView(bodyView);

        Button copyBtn = createDarkButton("复制诊断");
        copyBtn.setTextSize(12);
        copyBtn.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("serve-diag", text));
                    showToast("诊断信息已复制");
                }
            } catch (Exception e) {
                showToast("复制失败：" + e);
            }
        });
        wrap.addView(copyBtn);

        list.addView(wrap, 0);
        autoScrollBottom(true);
    }

    /** serve 未在线：把启动命令返回 + 端口/token + 日志尾部渲染成可复制的诊断卡片 */
    private void showServeFailureDiagnostics(String execOut) {
        appendDiagnosticCard("⚠ serve 引擎未在线（可复制下方诊断信息反馈）",
                reasonixDeployInfo()
                        + (pluginDeployError == null ? "" : "内置插件部署失败：" + pluginDeployError + "\n")
                        + "启动命令返回：" + (execOut == null || execOut.isEmpty() ? "(无)" : execOut) + "\n"
                        + "端口文件读值：" + ReasonixServe.readBoundPort(getFilesDir())
                        + "（-1 = 未写入 " + ReasonixServe.PORT_FILE_GUEST + "）\n"
                        + "token：" + (ReasonixServe.readToken(getFilesDir()).isEmpty() ? "(空)" : "(已写入)") + "\n"
                        + "── " + ReasonixServe.LOG_FILE_GUEST + "（尾部）──\n" + readGuestServeLogTail(25));
    }

    /**
     * 建立 transcript 跟随流（幂等：重复调用先停旧的；断线由客户端自行重连并重取基线）。
     * reasonix 1.38/1.39 无 /transcript 契约（实测回退 Web UI HTML）时，客户端自动切
     * /events 通道：事件帧仅作刷新触发，GUI 走 /history 全量渲染（refreshFromHistory）。
     */
    private void startTranscriptFollow() {
        int port = ReasonixServe.readBoundPort(getFilesDir());
        if (port <= 0) port = 8787;
        String token = ReasonixServe.readToken(getFilesDir());
        TranscriptClient c = new TranscriptClient("http://127.0.0.1:" + port, token,
                new TranscriptClient.Listener() {
                    @Override
                    public void onBaseline(org.json.JSONObject frame) {
                        serveOnline = true;
                        if (frame.optBoolean("__fallbackBaseline", false)) {
                            ui.post(MainActivity.this::refreshFromHistory);
                        } else {
                            ui.post(() -> applyTranscriptBaseline(frame));
                        }
                    }

                    @Override
                    public void onDelta(org.json.JSONObject frame) {
                        if (frame.optBoolean("__fallbackEvent", false)) {
                            ui.post(MainActivity.this::refreshFromHistory);
                        } else {
                            ui.post(() -> applyTranscriptDelta(frame));
                        }
                    }

                    @Override
                    public void onConnection(boolean connected, String detail) {
                        serveOnline = connected;
                        ui.post(() -> setNativeStatus(connected ? ""
                                : ((detail == null || detail.isEmpty()) ? "会话流中断，正在重连…"
                                : detail + "，正在重连…")));
                    }
                });
        transcript = c;
        c.start();
    }

    private void stopTranscriptFollow() {
        TranscriptClient c = transcript;
        transcript = null;
        if (c != null) c.stop();
    }

    /** 从 /status JSON 宽松取模型引用（键名在不同版本可能不同） */
    private String parseServeModel(String statusJson) {
        if (statusJson == null || statusJson.isEmpty()) return "";
        String[] keys = {"model", "modelRef", "model_ref", "defaultModel", "default_model", "label"};
        try {
            org.json.JSONObject o = new org.json.JSONObject(statusJson);
            for (String k : keys) {
                String v = o.optString(k, "");
                if (!v.isEmpty()) return v;
            }
            org.json.JSONObject rt = o.optJSONObject("runtimeState");
            if (rt != null) {
                for (String k : keys) {
                    String v = rt.optString(k, "");
                    if (!v.isEmpty()) return v;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 同步拉一次 /transcript/snapshot 渲染（进入视图 / 新会话后调用；窗口比 follow 首帧更大）。
     *  快照不可用（无 /transcript 契约）时回退 /history 渲染（/events 通道由跟随流负责刷新）。 */
    private void loadTranscriptSnapshot() {
        new Thread(() -> {
            int port = ReasonixServe.readBoundPort(getFilesDir());
            if (port <= 0) port = 8787;
            TranscriptClient c = new TranscriptClient("http://127.0.0.1:" + port,
                    ReasonixServe.readToken(getFilesDir()), null);
            final org.json.JSONObject snap = c.fetchSnapshot(8000);
            if (snap == null) {
                refreshFromHistory();   // 无 /transcript 契约（v1.38/1.39 实测）→ /history 渲染
                return;
            }
            org.json.JSONObject frame = new org.json.JSONObject();
            try {
                frame.put("protocolVersion", snap.optInt("protocolVersion", 2));
                frame.put("snapshot", snap);
            } catch (Exception ignored) {}
            ui.post(() -> applyTranscriptBaseline(frame));
        }, "transcript-snapshot").start();
    }

    /** /events 回退通道：拉 /history 全量渲染（整表重建，简单可靠；事件帧仅作刷新触发）。
     *  /history 返回 [{role, content}...]（含 system；渲染时过滤）。 */
    private void refreshFromHistory() {
        new Thread(() -> {
            try {
                ReasonixServe client = serveClient();
                if (client == null) return;
                String body = client.history();
                if (body == null) return;
                final org.json.JSONArray arr = new org.json.JSONArray(body);
                ui.post(() -> renderHistoryMessages(arr));
            } catch (Exception e) {
                Log.w(TAG, "refreshFromHistory failed", e);
            }
        }, "serve-history").start();
    }

    /** /history 消息数组 → 气泡列表（user 右对齐蓝底 / assistant 左对齐；过滤 system 默认说明） */
    private void renderHistoryMessages(org.json.JSONArray arr) {
        LinearLayout list = findViewById(R.id.native_output);
        if (list == null) return;
        int n = arr.length();
        if (n == 0) return;
        // 整表重建（fallback 通道无 record 索引；历史较短，重建成本可忽略）
        list.removeAllViews();
        resetTranscriptView();
        for (int i = 0; i < n; i++) {
            org.json.JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String content = m.optString("content", "");
            if (content.isEmpty()) continue;
            if ("system".equals(role)) continue;    // 系统提示词不展示
            TranscriptRecord r = new TranscriptRecord();
            r.role = role;
            r.content = content;
            r.id = "h" + i;
            BubbleView bv = createBubble(r);
            list.addView(bv.wrap);
        }
        setBubbleCount(list.getChildCount());
        updateSessionInfoCount(list.getChildCount());
        autoScrollBottom(true);
        setGenInFlight(false);
        setNativeStatus("");
    }

    // ---------------- transcript 投影 → 气泡 ----------------

    /** 清空会话视图与全部索引（新会话 / 重绘前调用） */
    private void resetTranscriptView() {
        recordViews.clear();
        callIndex.clear();
        streamBuf.clear();
        transcriptLastOrder = -1;
        transcriptSessionId = "";
        transcriptWindowSig = "";
        pendingUserBubble = null;
        pendingUserText = "";
    }

    /**
     * 基线帧（含 snapshot 窗口）：会话未变时按 record id 合并（follow 首帧的窗口比
     * /transcript/snapshot 小，直接重建会把已加载的更早历史截短），会话变化时整表重建。
     */
    private void applyTranscriptBaseline(org.json.JSONObject frame) {
        org.json.JSONObject snap = frame.optJSONObject("snapshot");
        if (snap == null) snap = frame;
        java.util.List<TranscriptRecord> recs =
                TranscriptRecord.parseArray(snap.optJSONArray("records"));
        java.util.Collections.sort(recs, (a, b) -> Long.compare(a.order, b.order));

        LinearLayout list = findViewById(R.id.native_output);
        if (list == null) return;
        org.json.JSONObject identity = snap.optJSONObject("identity");
        String sid = (identity == null) ? "" : identity.optString("sessionId", "");
        boolean sameSession = !sid.isEmpty() && sid.equals(transcriptSessionId);
        if (!sameSession) {
            list.removeAllViews();
            resetTranscriptView();
            transcriptSessionId = sid;
        }
        // 内容未变的重复基线（follow 收尾重连 / 空闲轮询）直接跳过，避免整表 setText
        StringBuilder sig = new StringBuilder();
        for (TranscriptRecord r : recs) {
            sig.append(r.order).append(':').append(r.role).append(':')
                    .append(r.content == null ? 0 : r.content.hashCode()).append(':')
                    .append(r.execState).append(':').append(r.calls.size()).append(';');
        }
        String sigStr = sig.toString();
        if (sameSession && sigStr.equals(transcriptWindowSig)) {
            applyTranscriptRuntime(snap.optJSONObject("runtime"));
            return;
        }
        transcriptWindowSig = sigStr;

        // 合并窗口时，比当前最早记录还早的记录要依次前插（保持 order 升序）
        int prependCursor = 0;
        for (TranscriptRecord r : recs) {
            boolean earlier = r.order >= 0 && transcriptLastOrder >= 0
                    && r.order < transcriptLastOrder && !recordViews.containsKey(r.key());
            upsertRecordView(list, r, earlier ? prependCursor++ : -1);
        }
        setBubbleCount(list.getChildCount());
        updateSessionInfoCount(list.getChildCount());
        if (sameSession) {
            autoScrollBottom(false);
        } else {
            autoScrollBottom(true);
        }
        applyTranscriptRuntime(snap.optJSONObject("runtime"));
    }

    /** 增量帧：changes[]（缺省时帧自身）里的 records / event / runtime */
    private void applyTranscriptDelta(org.json.JSONObject frame) {
        if (frame.optBoolean("resetRequired", false)) {
            loadTranscriptSnapshot();       // 服务端要求重同步：重新取基线
            return;
        }
        org.json.JSONArray changes = frame.optJSONArray("changes");
        if (changes != null && changes.length() > 0) {
            for (int i = 0; i < changes.length(); i++) {
                org.json.JSONObject ch = changes.optJSONObject(i);
                if (ch != null) applyTranscriptChange(ch);
            }
        } else {
            applyTranscriptChange(frame);
        }
    }

    /** 单个增量（delta）：先落记录，再处理事件与运行态 */
    private void applyTranscriptChange(org.json.JSONObject ch) {
        if (ch.optBoolean("resetRequired", false)) {
            loadTranscriptSnapshot();
            return;
        }
        LinearLayout list = findViewById(R.id.native_output);
        org.json.JSONArray recs = ch.optJSONArray("records");
        if (list != null && recs != null && recs.length() > 0) {
            java.util.List<TranscriptRecord> parsed = TranscriptRecord.parseArray(recs);
            java.util.Collections.sort(parsed, (a, b) -> Long.compare(a.order, b.order));
            for (TranscriptRecord r : parsed) upsertRecordView(list, r);
            setBubbleCount(list.getChildCount());
            autoScrollBottom(false);
        }
        org.json.JSONObject ev = ch.optJSONObject("event");
        if (ev != null) applyTranscriptEvent(ev);
        org.json.JSONObject rt = ch.optJSONObject("runtime");
        if (rt != null) applyTranscriptRuntime(rt);
    }

    /** 事件：驱动状态行/按钮；若服务端在事件里带增量文本则走流式正文 */
    private void applyTranscriptEvent(org.json.JSONObject ev) {
        String kind = ev.optString("kind", "");
        if ("turn_started".equals(kind) || "turn_phase".equals(kind)) {
            setNativeStatus("思考中…");
        } else if ("turn_done".equals(kind) || "message/complete".equals(kind)
                || "message/interrupted".equals(kind)) {
            setGenInFlight(false);
            setNativeStatus("");
            loadTranscriptSnapshot();   // 回合结束对齐一次基线（增量缺失也能拿到最终内容）
            return;
        } else if ("tool_dispatch".equals(kind) || "tool_progress".equals(kind)) {
            setNativeStatus("执行中…");
        }
        // 只在「明确的流式事件」上取增量文本：其余事件的字段含义不同，误取会重复入流
        boolean textish = kind.isEmpty() || "text".equals(kind) || "reasoning".equals(kind)
                || "stream_attempt".equals(kind) || "message".equals(kind);
        if (!textish) return;
        String messageId = ev.optString("messageId", "");
        String text = firstString(ev, "text", "delta");
        if (text.isEmpty()) {
            org.json.JSONObject sa = ev.optJSONObject("streamAttempt");
            if (sa != null) {
                if (messageId.isEmpty()) messageId = sa.optString("messageId", "");
                text = firstString(sa, "text", "delta");
            }
        }
        if (!text.isEmpty()) appendStreamDelta(messageId, text);
    }

    /** 流式正文增量：先累加到缓冲；对应 record 落定后由 record 内容覆盖 */
    private void appendStreamDelta(String messageId, String delta) {
        String key = messageId.isEmpty() ? "__stream" : ("m:" + messageId);
        StringBuilder buf = streamBuf.get(key);
        if (buf == null) {
            buf = new StringBuilder();
            streamBuf.put(key, buf);
        }
        buf.append(delta);

        LinearLayout list = findViewById(R.id.native_output);
        if (list == null) return;
        BubbleView bv = recordViews.get(key);
        if (bv == null) {
            bv = createBubble(streamRecord(key));
            recordViews.put(key, bv);
            list.addView(bv.wrap);
            setBubbleCount(list.getChildCount());
        }
        bv.body.setText(buf.toString());
        autoScrollBottom(false);
    }

    /** 临时流式气泡的占位 record（正式 record 到达后原地替换） */
    private TranscriptRecord streamRecord(String key) {
        TranscriptRecord r = new TranscriptRecord();
        r.id = key;
        r.role = "assistant";
        r.messageId = key.startsWith("m:") ? key.substring(2) : "";
        return r;
    }

    /** 运行态（runtime）：running/activity 驱动状态行与按钮 */
    private void applyTranscriptRuntime(org.json.JSONObject rt) {
        if (rt == null || !rt.has("running")) return;
        boolean running = rt.optBoolean("running", false);
        if (running) {
            String act = rt.optString("activity", "");
            setNativeStatus("working".equals(act) ? "执行中…" : "思考中…");
        } else {
            setGenInFlight(false);
            setNativeStatus("");
            if (transcriptRunning) loadTranscriptSnapshot();   // 回合刚结束：对齐一次基线
        }
        transcriptRunning = running;
    }

    private void upsertRecordView(LinearLayout list, TranscriptRecord r) {
        upsertRecordView(list, r, -1);
    }

    /**
     * 记录 → 视图：同 id 原地更新；新 id 追加（insertIndex &gt;= 0 时插到该位置，
     * 供"补插更早历史"使用，避免前插导致顺序反转）。
     */
    private void upsertRecordView(LinearLayout list, TranscriptRecord r, int insertIndex) {
        if (r.isAssistant()) indexCalls(r);
        String key = r.key();
        BubbleView bv = recordViews.get(key);
        if (bv == null) {
            if (!r.messageId.isEmpty()) streamBuf.remove("m:" + r.messageId);
            bv = createBubble(r);
            recordViews.put(key, bv);
            if (insertIndex >= 0 && insertIndex <= list.getChildCount()) {
                list.addView(bv.wrap, insertIndex);
            } else {
                list.addView(bv.wrap);
            }
            setBubbleCount(list.getChildCount());
        }
        updateBubble(bv, r, this.callIndex);
        if (r.order > transcriptLastOrder) transcriptLastOrder = r.order;

        // 服务端回显的 user record 到达后，移除本地即时回显的临时气泡（避免重复两条）
        if (r.isUser()) {
            BubbleView pend = pendingUserBubble;
            if (pend != null && (pendingUserText.isEmpty() || pendingUserText.equals(r.content)
                    || r.content.startsWith(pendingUserText) || pendingUserText.startsWith(r.content))) {
                list.removeView(pend.wrap);
                pendingUserBubble = null;
                pendingUserText = "";
                setBubbleCount(list.getChildCount());
            }
        }
    }

    /** toolCallId → 调用参数（tool 记录自身不带参数，需从 assistant 记录关联） */
    private void indexCalls(TranscriptRecord r) {
        for (TranscriptRecord.Call c : r.calls) {
            if (!c.id.isEmpty()) callIndex.put(c.id, c);
        }
    }

    /** 把 record 内容写进气泡（同一 record 流式更新时同样走这里，原地覆盖） */
    private void updateBubble(BubbleView bv, TranscriptRecord r,
                              java.util.Map<String, TranscriptRecord.Call> calls) {
        if (r.isUser()) {
            bv.body.setText(r.content);
            return;
        }
        if (r.isNotice()) {
            bv.body.setText(r.content);
            return;
        }
        if (r.isAssistant()) {
            StringBuilder sb = new StringBuilder(r.content == null ? "" : r.content);
            for (TranscriptRecord.Call c : r.calls) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("▸ ").append(c.resolvedName.isEmpty() ? c.name : c.resolvedName);
                String a = summarizeToolArgs(c.arguments);
                if (!a.isEmpty()) sb.append("  ").append(a);
            }
            bv.body.setText(sb.toString());
            return;
        }
        if (r.isTool()) {
            TranscriptRecord.Call call = r.toolCallId.isEmpty() ? null : calls.get(r.toolCallId);
            String name = !r.toolName.isEmpty() ? r.toolName
                    : (call == null ? "" : (call.resolvedName.isEmpty() ? call.name : call.resolvedName));
            StringBuilder sb = new StringBuilder();
            sb.append("▸ ").append(name.isEmpty() ? "tool" : name);
            if (call != null) {
                String a = summarizeToolArgs(call.arguments);
                if (!a.isEmpty()) sb.append("  ").append(a);
            }
            if (!r.execState.isEmpty() && !"completed".equals(r.execState)) {
                sb.append("  [").append(r.execState).append("]");
            }
            String out = r.content == null ? "" : r.content;
            if (out.length() > 4000) out = out.substring(0, 4000) + "\n…（输出已截断）";
            if (!out.isEmpty()) sb.append("\n").append(out);
            if (!r.error.isEmpty()) sb.append("\n[错误] ").append(r.error);
            bv.body.setText(sb.toString());
        }
    }

    /** 宽松按键取第一个非空字符串 */
    private static String firstString(org.json.JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** 工具参数摘要：`{"command":"ls -1"}` → `ls -1`（其余 JSON 原样截断） */
    private static String summarizeToolArgs(String args) {
        if (args == null || args.isEmpty()) return "";
        try {
            org.json.JSONObject o = new org.json.JSONObject(args);
            for (String k : new String[]{"command", "cmd", "path", "file_path", "pattern", "query", "url", "prompt"}) {
                String v = o.optString(k, "");
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        return args.length() > 200 ? args.substring(0, 200) + "…" : args;
    }

    /** 会话信息行：serve 在线时显示当前渲染的记录数（让"有没有数据"一眼可见） */
    private void updateSessionInfoCount(int n) {
        TextView info = findViewById(R.id.native_session_info);
        if (info != null && serveOnline) {
            info.setText("会话：serve 引擎 · " + n + " 条");
        }
    }

    private void setBubbleCount(int n) {
        TextView cnt = findViewById(R.id.native_msg_count);
        if (cnt != null) cnt.setText(n + " 条");
    }

    /** GUI 状态行（复用 native_live 控件显示 serve 连接/生成状态；空串隐藏） */
    private void setNativeStatus(String text) {
        TextView v = findViewById(R.id.native_live);
        if (v == null) return;
        if (text == null || text.isEmpty()) {
            v.setVisibility(View.GONE);
            v.setText("");
        } else {
            v.setVisibility(View.VISIBLE);
            v.setText(text);
        }
    }

    private static String nowHms() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
    }

    /** 气泡控件组：元信息 + 正文两块可分别更新（同一 record 流式更新时原地覆盖） */
    private static class BubbleView {
        LinearLayout wrap;
        TextView meta;
        TextView body;
    }

    /** 创建一条 record 气泡（user 右对齐蓝底 / assistant 左对齐 / tool 灰色等宽 / notice 系统小字） */
    private BubbleView createBubble(TranscriptRecord r) {
        BubbleView bv = new BubbleView();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        wrap.setLayoutParams(wlp);

        // 顶行：角色标签 + 时间
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView roleTag = new TextView(this);
        String roleText;
        int roleColor;
        if (r.isUser()) {
            roleText = "用户";
            roleColor = 0xFF58A6FF;
        } else if (r.isAssistant()) {
            roleText = "AI" + (serveModelRef.isEmpty() ? "" : " · " + serveModelRef);
            roleColor = 0xFF7FDB8A;
        } else if (r.isTool()) {
            roleText = "工具";
            roleColor = r.toolFailed() ? 0xFFFF6B6B : 0xFF8B949E;
        } else {
            roleText = "系统";
            roleColor = 0xFFD29922;
        }
        roleTag.setText(roleText);
        roleTag.setTextColor(roleColor);
        roleTag.setTextSize(11);
        roleTag.setTypeface(null, android.graphics.Typeface.BOLD);
        meta.addView(roleTag);
        String ts = (r.isUser() && r.createdAt > 0) ? hmsOf(r.createdAt) : nowHms();
        TextView tsView = new TextView(this);
        tsView.setText("  " + ts);
        tsView.setTextColor(0xFF6E7681);
        tsView.setTextSize(10);
        meta.addView(tsView);
        wrap.addView(meta);
        bv.meta = roleTag;

        // 正文（tool 气泡灰色等宽；notice 系统小字）
        // 不再 setTextIsSelectable：可编辑 TextView 会抢焦点/吞 IME，导致输入框无法输入
        TextView body = new TextView(this);
        body.setText("");
        body.setTextColor(r.isTool() ? 0xFF8B949E : r.isNotice() ? 0xFF8B949E : 0xFFE6EDF3);
        body.setTextSize(r.isNotice() ? 11 : 13);
        body.setLineSpacing(0, 1.25f);
        body.setFocusable(false);
        body.setLongClickable(false);
        body.setClickable(false);
        if (r.isTool()) {
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setBackgroundColor(0xFF11161C);
            body.setPadding(dp(8), dp(6), dp(8), dp(6));
        }
        wrap.addView(body);
        bv.body = body;
        bv.wrap = wrap;

        if (r.isUser()) {
            wrap.setGravity(android.view.Gravity.END);
            body.setBackgroundColor(0xFF1F3A5F);
            int pad = dp(10);
            body.setPadding(pad, pad, pad, pad);
        }
        return bv;
    }

    /** 毫秒时间戳 → HH:mm:ss */
    private static String hmsOf(long millis) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date(millis));
    }

    /**
     * 统一自动滚底入口（v2.0.23）：用户触摸翻阅期间（userScrolling）不抢占滚动位置；
     * 只有松手超过 4s（或 force）时才跟随新内容滚到底。
     * force=true（用户主动发送/切视图/键盘弹出）时无视 userScrolling 直接滚底。
     */
    private void autoScrollBottom(boolean force) {
        ScrollView sv = findViewById(R.id.native_scroll);
        if (sv == null) return;
        boolean recentTouch = userScrolling && (System.currentTimeMillis() - lastUserScrollAt) < 4000;
        if (recentTouch && !force) return;   // 用户正在回看：不打断
        if (!recentTouch) userScrolling = false;
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    /** 消息列表触摸监听：按下/移动置位 userScrolling（安装于 enterNativeView） */
    private void installScrollTouchGuard() {
        ScrollView sv = findViewById(R.id.native_scroll);
        if (sv == null) return;
        sv.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    userScrolling = true;
                    lastUserScrollAt = System.currentTimeMillis();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    lastUserScrollAt = System.currentTimeMillis();
                    // 不立即清 userScrolling：4s 缓冲内新内容不抢占，之后恢复自动跟随
                    break;
            }
            return false;   // 不消费事件：ScrollView 正常滚动
        });
    }

    /** 切换终端视图 / 原生会话视图（与侧滑栏「视图切换」同语义：当前是对话则回终端） */
    private void toggleNativeView() {
        if (nativeViewOn) {
            exitNativeView();
        } else {
            enterNativeView();
        }
    }

    private void enterNativeView() {
        nativeViewOn = true;
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("view_mode", "native").apply();
        updateMenuViewLabel();
        installScrollTouchGuard();
        // GUI 视图输入修复：强制 adjustResize（覆盖 showPanel 遗留的 ADJUST_PAN），
        // 确保软键盘弹出时输入框随窗口上移不被遮挡，消息列表同步压缩滚动。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        findViewById(R.id.native_chat).setVisibility(View.VISIBLE);
        findViewById(R.id.webview).setVisibility(View.GONE);
        // 内容来自 serve 的 transcript 投影：确保引擎在线 + 接上跟随流 + 取一次基线快照
        ensureServeStarted();
        loadTranscriptSnapshot();
        // 输入框焦点与键盘展开：requestFocus + 显示软键盘；聚焦/输入时自动滚动列表到底
        final EditText input = findViewById(R.id.native_input);
        if (input != null) {
            input.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) autoScrollBottom(true);   // 用户输入中，强制
            });
            input.postDelayed(() -> {
                input.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }, 300);
        }
    }

    private void exitNativeView() {
        nativeViewOn = false;
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("view_mode", "terminal").apply();
        updateMenuViewLabel();
        findViewById(R.id.native_chat).setVisibility(View.GONE);
        findViewById(R.id.webview).setVisibility(View.VISIBLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // 收起软键盘（输入框可能正聚焦），避免切回终端后键盘残留
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(
                findViewById(android.R.id.content).getWindowToken(), 0);
        write("\u000c");
        try { webView.requestFocus(); } catch (Exception ignored) {}
        // 事件流保持连接：切回对话时内容连续，不必重连/重放
    }

    /** 侧滑栏「视图切换」项标签：随当前视图（终端/对话）刷新 */
    private void updateMenuViewLabel() {
        TextView v = findViewById(R.id.menu_view);
        if (v != null) {
            v.setText(nativeViewOn ? "视图切换（当前：对话）" : "视图切换（当前：终端）");
            v.setTextColor(nativeViewOn ? 0xFF81C784 : 0xFFFFD54F);
        }
    }

    /** 原生会话视图发送：POST /submit；user 气泡即时回显（服务端 transcript 的 user record 到达后替换） */
    private void sendNativeInput() {
        EditText et = findViewById(R.id.native_input);
        if (et == null) return;
        // 停止分支必须在 txt.isEmpty() 之前：生成中输入框必然为空
        if (genInFlight) {
            stopNativeGeneration();
            return;
        }
        final String txt = et.getText().toString().trim();
        if (txt.isEmpty()) return;
        et.setText("");
        et.requestFocus();   // 保持焦点：多轮对话无需每次点输入框
        setGenInFlight(true);
        setNativeStatus("思考中…");
        new Thread(() -> {
            boolean ok = serveClient().submit(txt);
            ui.post(() -> {
                if (ok) {
                    appendUserBubble(txt);
                } else {
                    setGenInFlight(false);
                    setNativeStatus("发送失败：serve 未在线（见顶部诊断卡片，可点「会话」重启 serve）");
                }
            });
        }, "serve-submit").start();
    }

    /** 本地即时回显一条 user 消息（不等服务端记录；服务端 record 到达后自动移除本条） */
    private void appendUserBubble(String text) {
        LinearLayout list = findViewById(R.id.native_output);
        if (list == null) return;
        TranscriptRecord r = new TranscriptRecord();
        r.role = "user";
        r.content = text;
        r.createdAt = System.currentTimeMillis();
        BubbleView bv = createBubble(r);
        updateBubble(bv, r, this.callIndex);
        pendingUserBubble = bv;
        pendingUserText = text;
        list.addView(bv.wrap);
        setBubbleCount(list.getChildCount());
        autoScrollBottom(true);   // 自己发送的消息，强制滚底
    }

    /** 中断当前回合（POST /cancel） */
    private void stopNativeGeneration() {
        new Thread(() -> {
            serveClient().cancel();
            ui.post(() -> {
                setGenInFlight(false);
                setNativeStatus("");
            });
        }, "serve-cancel").start();
    }

    /** 生成中状态切换：主界面按钮在「发送/停止」间互切（结束由 turn_done/runtime_state 驱动） */
    private void setGenInFlight(boolean on) {
        genInFlight = on;
        ui.post(() -> {
            Button b = findViewById(R.id.native_send);
            if (b != null) {
                b.setText(on ? BTN_STOP : BTN_SEND);
                b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? 0xFF5A2A2A : 0xFF262626));
            }
        });
    }

    /** 前台恢复：把后台期间缓存的终端输出分块冲刷到 WebView（避免大字符串单次注入卡顿） */
    private void flushPendingOutput() {
        sWebActive = true;
        final String batch;
        synchronized (sPendingOutput) {
            if (sPendingOutput.length() == 0) return;
            batch = sPendingOutput.toString();
            sPendingOutput.setLength(0);
        }
        final WebView wv = webView;
        if (wv == null) return;
        final Handler h = new Handler(Looper.getMainLooper());
        final int chunkCount = (batch.length() + FLUSH_CHUNK - 1) / FLUSH_CHUNK;
        for (int i = 0; i < batch.length(); i += FLUSH_CHUNK) {
            final String part = batch.substring(i, Math.min(batch.length(), i + FLUSH_CHUNK));
            final long delay = (i / FLUSH_CHUNK) * 80L;
            h.postDelayed(() -> {
                // lambda 执行期才检查（v2.0.25 修复：onDestroy 置空 webView 后滞留分块
                // 回调触发主线程 NPE 崩溃；捕获执行期引用，失效即丢弃）
                if (wv == null) return;
                try {
                    wv.evaluateJavascript("window.onTermData(" + jsQuote(part) + ")", null);
                } catch (Exception ignored) {}
            }, delay);
        }
    }

    /** 生成安全的 JS 字符串字面量 */
    private static String jsQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sCurrent == this) sCurrent = null;
        stopRootPolling();
        stopTranscriptFollow();   // 停 transcript 跟随流
        stopServeWatchdog();      // 看门狗随 Activity 停；重新打开时 onCreate/onResume 会续上
        // 后台运行模式：环境与 Activity 生命周期解耦，退出 Activity 不杀 proot
        // （由前台服务保活继续后台运行，重新打开时 onCreate 复用环境与终端 I/O）；
        // 关闭模式时照旧清理。
        boolean bgMode = bgModeOn();
        if (!bgMode && sProotProcess != null) {
            sProotProcess.destroy();     // pty-bridge 收到 SIGTERM 后会 kill 整个 guest 进程组
            sProotProcess = null;
            sProcIn = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
