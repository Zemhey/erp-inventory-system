package com.yunshang.erp.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 云端优先的 WebView 壳：默认加载移动端 H5 站点，加载失败自动回落到线上网页版。
 *
 * 这不是 HBuilderX 云打包的正式 uni-app 安装包（正式包需要 DCloud 账号），
 * 但它不再依赖开发电脑：地址默认指向公网，手机在任何网络下都能打开。
 *
 * 地址策略（m.zemworld.cn 可能还没解析生效，所以必须带回落）：
 * 1. 启动先试 server_url_default（https://m.zemworld.cn/）；
 * 2. 主文档加载失败（DNS 不通 / 连不上 / 超时 / HTTP 4xx 5xx）就自动改试
 *    server_url_fallback（https://xuliu.zemworld.cn/），并在左下角提示当前用的是哪个；
 * 3. 「设置服务器」里填的地址（内网 3008 调试仍要用）优先于上面两个，且不做自动回落。
 */
public class MainActivity extends Activity {
    private static final String PREFS = "erp_demo";
    private static final String KEY_URL = "server_url";

    /** 首屏迟迟不返回时的回落等待时长；DNS 未解析通常几百毫秒就报错，这里是兜住"连上但卡住"。 */
    private static final long PRIMARY_TIMEOUT_MS = 10_000L;

    /** 扫码页调用摄像头时需要的运行时权限请求码。 */
    private static final int CAMERA_REQUEST_CODE = 41;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView addressBadge;

    /** 云端两个地址：默认（移动 H5 站点）与兜底（线上网页版），从字符串资源读，便于改配置。 */
    private String primaryUrl;
    private String fallbackUrl;

    private String currentUrl = "";
    /** 页面实际落地的地址（可能因重定向与请求地址不同），只用于界面显示。 */
    private String effectiveUrl;
    // 本次加载是否已经尘埃落定（拿到页面或已经显示错误页），用来避免超时与错误回调重复触发
    private boolean pageSettled = false;
    // 当前正在加载的是不是兜底地址
    private boolean loadingFallback = false;
    // 本次会话是否已经因失败自动切换过一次，防止默认↔兜底来回打转
    private boolean autoSwitched = false;

    // 记录系统当前是否深色，壳层配色与弹窗主题都据此切换
    private boolean isNightMode;
    // 首屏“再按一次退出”的冷却标记
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());

    // 摄像头授权弹窗与 WebView 请求要跨异步回调保留
    private PermissionRequest pendingCameraRequest;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable primaryTimeoutGuard = new Runnable() {
        @Override
        public void run() {
            if (!pageSettled) {
                onMainFrameFailed("加载超时（10 秒内没有响应）");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        primaryUrl = getString(R.string.server_url_default);
        fallbackUrl = getString(R.string.server_url_fallback);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6, Gravity.TOP);
        progressBar.setLayoutParams(progressParams);
        progressBar.setMax(100);
        root.addView(progressBar);

        // 左下角地址徽标：自动回落时唯一能看见"现在用的是哪个站点"的地方，点开就是设置服务器
        addressBadge = new TextView(this);
        addressBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        addressBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        addressBadge.setSingleLine(true);
        addressBadge.setTextIsSelectable(false);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        badgeParams.setMargins(dp(8), 0, dp(8), dp(8));
        addressBadge.setLayoutParams(badgeParams);
        addressBadge.setOnClickListener(v -> showUrlDialog(currentUrl, false));
        root.addView(addressBadge);

        setContentView(root);

        // 让壳层配色跟随系统深色：读当前 uiMode，再据此设置状态栏/导航栏/WebView 底色
        isNightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 两套资源里状态栏/导航栏都是深色，所以始终配浅色图标，无需切换 LIGHT 标记
            getWindow().setStatusBarColor(getColor(R.color.shell_status_bar));
            getWindow().setNavigationBarColor(getColor(R.color.shell_nav_bar));
        }
        // WebView 先铺一层壳层底色，避免加载 H5 前出现白底闪烁
        webView.setBackgroundColor(getColor(R.color.shell_bg));
        styleAddressBadge();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " YunshangERP/0.2");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                settle();
                // 只拿实际落地的地址显示（可能被重定向到 /login），失败判定仍用 load() 记录的请求地址
                effectiveUrl = url;
                updateAddressBadge();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    onMainFrameFailed(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                // 站点还没配好时也可能连通并回一个 404/502，这类同样要触发回落
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    onMainFrameFailed("HTTP " + errorResponse.getStatusCode());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handlePermissionRequest(request);
            }
        });

        webView.setOnLongClickListener(v -> {
            showUrlDialog(prefs().getString(KEY_URL, currentUrl), false);
            return true;
        });

        String saved = prefs().getString(KEY_URL, null);
        if (saved == null || saved.trim().isEmpty()) {
            // 首次启动：先试云端默认地址，失败自动回落，不再弹窗要求填电脑地址
            load(primaryUrl, false);
        } else {
            load(saved, fallbackUrl.equals(saved.trim()));
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // 没有可处理该 scheme 的应用时留在当前页
        }
        return true;
    }

    /**
     * 加载一个地址，并把结果记进偏好（自动回落那一次不记，见三参重载）。
     *
     * @param isFallback 是否是回落后的兜底地址（决定后续失败要不要再切换）
     */
    private void load(String raw, boolean isFallback) {
        load(raw, isFallback, true);
    }

    /**
     * @param persist 是否把该地址写回偏好；自动兜底时传 false，
     *                否则下次冷启动就永远停在兜底站点，不会再试移动端站点（阶段 A 上线后也接不上）。
     */
    private void load(String raw, boolean isFallback, boolean persist) {
        String url = normalize(raw);
        if (url.isEmpty()) {
            return;
        }
        if (persist) {
            prefs().edit().putString(KEY_URL, url).apply();
        }
        currentUrl = url;
        effectiveUrl = null;
        loadingFallback = isFallback;
        pageSettled = false;
        uiHandler.removeCallbacks(primaryTimeoutGuard);
        // 只有云端默认地址需要超时兜底；用户手填的内网地址失败就直接给提示页
        if (!isFallback && url.equals(primaryUrl)) {
            uiHandler.postDelayed(primaryTimeoutGuard, PRIMARY_TIMEOUT_MS);
        }
        progressBar.setVisibility(View.VISIBLE);
        updateAddressBadge();
        webView.loadUrl(url);
    }

    /** 补协议头：云端一律 https，内网调试写 172.x.x.x:3008 也能用（明文已在 network_security_config 放行）。 */
    private String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        // 只写了 host:port 时：本机与内网地址按 http（3008 预览服务是明文），其余一律 https
        return isLocalAddress(url) ? "http://" + url : "https://" + url;
    }

    private boolean isLocalAddress(String hostPort) {
        String host = hostPort.split("/", 2)[0].split(":", 2)[0].toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        // 127/8、10/8、192.168/16、172.16-31、169.254/16 —— 开发机所在网段都是私有地址
        return host.matches("^(127\\.|10\\.|192\\.168\\.|169\\.254\\.).*")
                || host.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** 主文档加载失败：默认站点→兜底站点，兜底也失败→错误提示页。 */
    private void onMainFrameFailed(String detail) {
        if (pageSettled) {
            return;
        }
        settle();
        boolean isPrimary = !loadingFallback && primaryUrl.equals(currentUrl);
        if (isPrimary && !autoSwitched) {
            autoSwitched = true;
            Toast.makeText(this, "移动端站点打不开，已自动切到线上版", Toast.LENGTH_LONG).show();
            load(fallbackUrl, true, false);
            return;
        }
        showLoadError(detail);
    }

    private void settle() {
        pageSettled = true;
        uiHandler.removeCallbacks(primaryTimeoutGuard);
    }

    private void updateAddressBadge() {
        if (addressBadge == null) {
            return;
        }
        String tag;
        if (primaryUrl.equals(currentUrl)) {
            tag = "移动端站点";
        } else if (fallbackUrl.equals(currentUrl)) {
            tag = autoSwitched ? "线上版（兜底）" : "线上版";
        } else {
            tag = "自定义地址";
        }
        String shown = (effectiveUrl == null || effectiveUrl.isEmpty()) ? currentUrl : effectiveUrl;
        addressBadge.setText("当前：" + shown + " · " + tag);
    }

    private void styleAddressBadge() {
        if (addressBadge == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(isNightMode ? "#CCF6F2EA" : "#B314283F"));
        bg.setCornerRadius(dp(14));
        addressBadge.setBackground(bg);
        addressBadge.setTextColor(Color.parseColor(isNightMode ? "#14120F" : "#F6F2EA"));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** H5 扫码页请求摄像头时：先过系统运行时权限，再决定 grant/deny；拒绝不阻断，页面仍可手输条码。 */
    private void handlePermissionRequest(final PermissionRequest request) {
        List<String> resources = Arrays.asList(request.getResources());
        if (!resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(request.getResources());
            return;
        }
        pendingCameraRequest = request;
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("需要摄像头权限")
                .setMessage("页面请求调用摄像头用于扫码。拒绝后仍可在扫码页手动输入条码。")
                .setPositiveButton("允许", (dialog, which) -> requestCameraPermission())
                .setNegativeButton("拒绝", (dialog, which) -> denyPendingRequest())
                .setOnCancelListener(dialog -> denyPendingRequest())
                .show();
    }

    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
    }

    private void denyPendingRequest() {
        if (pendingCameraRequest != null) {
            pendingCameraRequest.deny();
            pendingCameraRequest = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_REQUEST_CODE || pendingCameraRequest == null) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingCameraRequest.grant(pendingCameraRequest.getResources());
        } else {
            Toast.makeText(this, "未授予摄像头权限，扫码页请手动输入条码", Toast.LENGTH_SHORT).show();
            pendingCameraRequest.deny();
        }
        pendingCameraRequest = null;
    }

    private void showUrlDialog(String initial, boolean firstRun) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initial);
        input.setSelectAllOnFocus(true);

        // 深浅模式下分别使用对应的 Material 对话框主题，免得深色系统里弹出白底弹窗
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("服务器地址")
                .setMessage("默认 " + primaryUrl + "（移动端 H5）；打不开会自动改用 " + fallbackUrl + "（线上版）。\n"
                        + "内网调试时填运行手机预览的电脑地址，例如 http://172.17.41.78:3008/，"
                        + "此时手机与电脑要在同一局域网，且电脑上的 启动系统.cmd 要保持运行。\n"
                        + "也可以长按页面重新打开这个设置。")
                .setView(input)
                .setPositiveButton("打开", (dialog, which) -> {
                    autoSwitched = false;
                    String url = normalize(input.getText().toString());
                    load(url, fallbackUrl.equals(url));
                })
                .setNegativeButton(firstRun ? "退出" : "取消", (dialog, which) -> {
                    if (firstRun) finish();
                })
                .setCancelable(!firstRun)
                .show();
    }

    private int dialogTheme() {
        return isNightMode ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    private void showLoadError(String detail) {
        // 错误页配色走资源，随系统深色切换，不在此写死两套 hex
        String bg = colorHex(R.color.shell_bg);
        String fg = colorHex(R.color.shell_text);
        String html = "<html><body style='font-family:sans-serif;padding:24px;background:" + bg + ";color:" + fg + "'>"
                + "<h2>打不开页面</h2><p>已尝试：<b>" + currentUrl + "</b><br>原因：" + detail + "</p>"
                + "<p>1. 云端地址：<b>" + primaryUrl + "</b>（移动端站点，若尚未解析生效会自动改用 "
                + "<b>" + fallbackUrl + "</b>）；<br>"
                + "2. 内网调试：确认电脑上的 <b>启动系统.cmd</b> 正在运行，手机与电脑在同一局域网，"
                + "并在菜单里把地址改成 <b>http://&lt;电脑局域网IP&gt;:3008/</b>；<br>"
                + "3. 点右上角菜单 → 设置服务器，或长按页面重新打开设置。</p>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // 把颜色资源转成 #RRGGBB，拼进错误页 HTML 时复用
    private String colorHex(int resId) {
        return String.format("#%06X", 0xFFFFFF & getColor(resId));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "设置服务器");
        menu.add(0, 2, 1, "重新加载");
        menu.add(0, 3, 2, "改用云端地址");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) {
            showUrlDialog(prefs().getString(KEY_URL, currentUrl), false);
            return true;
        }
        if (id == 2) {
            load(currentUrl, loadingFallback);
            return true;
        }
        if (id == 3) {
            // 手动切回云端默认地址：重新给一次自动回落的机会
            autoSwitched = false;
            load(primaryUrl, false);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // WebView 还能后退就先后退；只在首屏（没有历史）才走“再按一次退出”，避免演示时误触直接关掉
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (backPressedOnce) {
            super.onBackPressed();
            return;
        }
        backPressedOnce = true;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        backHandler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 弹窗还没回答就离开页面时，把请求结掉，免得 WebView 挂着一个永远不返回的授权
        if (!isFinishing()) {
            denyPendingRequest();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(primaryTimeoutGuard);
        backHandler.removeCallbacksAndMessages(null);
        denyPendingRequest();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
