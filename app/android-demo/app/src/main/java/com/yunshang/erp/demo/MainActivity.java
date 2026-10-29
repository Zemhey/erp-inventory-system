package com.yunshang.erp.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 离线优先的移动端外壳：界面（HTML/CSS/JS）内置在 assets/www，接口仍打真实后端。
 *
 * 为什么文件仍然不大：移动端界面是 uni-app 构建出的 Web 包（约 700KB gzip 前），
 * 壳层只负责装载它并把 /api 交给真实网络，所以体积等于"界面包 + 图标"，与原生渲染无关。
 *
 * 关键机制（也是"不再是套壳在线网页"的地方）：
 * 1. 文档地址用站点真实域名，但主文档与静态资源由 assets 直接回给 WebView，
 *    所以冷启动不依赖 DNS、不依赖服务器，断网也能立刻打开到登录页；
 * 2. 页面里的请求写成相对路径（/api/v1），域名与文档同源 → 浏览器不触发 CORS，
 *    登录这类带请求体的 POST 可以原样走真实网络（shouldInterceptRequest 拿不到请求体，
 *    所以接口一律不拦截，交给 WebView 自己发）；
 * 3. 壳层没有 ActionBar、没有进度条、没有地址徽标，状态栏配色对齐 H5 导航条，
 *    看上去就是应用自己的顶栏。
 */
public class MainActivity extends Activity {
    private static final String PREFS = "yunshang_erp";
    private static final String KEY_SITE = "site_origin";

    /** 内置界面包的根目录（assets 下的相对路径）。 */
    private static final String ASSET_ROOT = "www/";

    /** 明确走真实网络的路径前缀：接口与探针，绝不从内置包回答。 */
    private static final String[] NETWORK_PREFIXES = {"/api", "/readyz", "/actuator", "/swagger-ui", "/v3/api-docs"};

    /** 只有这些前缀或这些扩展名才尝试命中内置包，其余保持原样请求服务器。 */
    private static final String[] LOCAL_PREFIXES = {"/assets/", "/static/", "/uni_modules/", "/favicon"};
    private static final String[] LOCAL_EXT = {".js", ".css", ".html", ".json", ".svg", ".png", ".jpg",
            ".jpeg", ".gif", ".webp", ".ico", ".woff", ".woff2", ".ttf", ".map"};

    private static final int CAMERA_REQUEST_CODE = 41;

    private WebView webView;

    /** 后端站点源（含协议，不含结尾斜杠）。改成内网地址即可联调，界面仍来自内置包。 */
    private String siteOrigin;
    private String siteHost;

    private boolean isNightMode;
    private boolean backPressedOnce = false;

    private final Handler backHandler = new Handler(Looper.getMainLooper());
    private PermissionRequest pendingCameraRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isNightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        applyWindowChrome();

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(getColor(R.color.shell_bg));
        FrameLayout root = new FrameLayout(this);
        root.addView(webView);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // 内置资源不经过 HTTP 缓存；接口每次都要新数据，不能让用户看到昨天的库存
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(settings.getUserAgentString() + " YunshangERP/0.3");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        prepareSite(prefs().getString(KEY_SITE, getString(R.string.site_origin_default)));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return intercept(request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // 主文档来自内置包，正常不会失败；真失败（比如 assets 缺失）时给一页可操作的提示，而不是白屏
                if (failingUrl != null && failingUrl.equals(siteOrigin + "/index.html")) {
                    showError(description == null ? "" : description);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handlePermissionRequest(request);
            }
        });

        // 长按＝唯一的调试入口（没有菜单栏，顶栏要留给界面本身）
        webView.setOnLongClickListener(v -> {
            showSiteDialog();
            return true;
        });

        loadShell();
    }

    /**
     * 顶栏配色：状态栏跟 H5 导航条同色（theme.json 的 navBg），导航栏跟 tabBar 同色，
     * 这样手机系统栏与应用顶栏连成一片，不会出现"上面挡一条别的颜色"。
     */
    private void applyWindowChrome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(getColor(R.color.shell_status_bar));
            getWindow().setNavigationBarColor(getColor(R.color.shell_nav_bar));
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void prepareSite(String raw) {
        String origin = raw == null ? "" : raw.trim();
        if (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        if (origin.isEmpty() || !origin.startsWith("http")) {
            origin = getString(R.string.site_origin_default);
        }
        Uri uri = Uri.parse(origin);
        if (uri.getHost() == null) {
            origin = getString(R.string.site_origin_default);
            uri = Uri.parse(origin);
        }
        siteOrigin = origin;
        siteHost = uri.getHost();
    }

    private void loadShell() {
        webView.loadUrl(siteOrigin + "/index.html");
    }

    /**
     * 静态资源来自安装包，接口原样放行。
     *
     * @return null 表示不拦截，交给 WebView 真实网络请求
     */
    private WebResourceResponse intercept(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(siteHost)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String key = path.toLowerCase(Locale.ROOT);
        for (String network : NETWORK_PREFIXES) {
            if (key.equals(network) || key.startsWith(network + "/")) {
                return null;
            }
        }
        if (!looksStatic(key)) {
            return null;
        }
        String assetPath = assetPathFor(path);
        InputStream stream = openAsset(assetPath);
        if (stream == null && isHtmlPage(key)) {
            // uni H5 走 hash 路由，正常不会请求到 /pages/xxx；这条回落是给手敲地址或服务端将来改 history 模式留的保险
            assetPath = ASSET_ROOT + "index.html";
            stream = openAsset(assetPath);
        }
        if (stream == null) {
            // 线上改版换了带 hash 的文件名而安装包没跟上：回源取一次，保证不会白屏
            return null;
        }
        return new WebResourceResponse(mimeFor(assetPath), encodingFor(assetPath), stream);
    }

    private boolean looksStatic(String lowerPath) {
        for (String prefix : LOCAL_PREFIXES) {
            if (lowerPath.startsWith(prefix)) {
                return true;
            }
        }
        for (String ext : LOCAL_EXT) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        // 无扩展名的路径只可能是前端路由，不是后端接口
        return !lowerPath.contains(".");
    }

    private boolean isHtmlPage(String lowerPath) {
        return !lowerPath.contains(".") || lowerPath.endsWith(".html");
    }

    private String assetPathFor(String path) {
        if (path.equals("/")) {
            return ASSET_ROOT + "index.html";
        }
        String clean = path.replace("..", "");
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        return ASSET_ROOT + clean;
    }

    private InputStream openAsset(String assetPath) {
        try {
            return getAssets().open(assetPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String mimeFor(String assetPath) {
        String lower = assetPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private String encodingFor(String assetPath) {
        String lower = assetPath.toLowerCase(Locale.ROOT);
        boolean text = lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css")
                || lower.endsWith(".json") || lower.endsWith(".svg") || lower.endsWith(".map");
        return text ? "utf-8" : null;
    }

    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // 没有能处理该 scheme 的应用时留在当前页
        }
        return true;
    }

    /** 内置包缺失且回源也失败时的兜底页，避免白屏。 */
    private void showError(String detail) {
        String html = "<html><body style='font-family:sans-serif;padding:28px;background:"
                + colorHex(R.color.shell_bg) + ";color:" + colorHex(R.color.shell_text) + "'>"
                + "<h2>界面没能载入</h2>"
                + "<p>安装包内的移动端界面缺失或已损坏，请联系发布者重新获取 APK。</p>"
                + "<p style='opacity:.7'>" + (detail == null ? "" : detail) + "</p>"
                + "</body></html>";
        webView.loadDataWithBaseURL(siteOrigin + "/", html, "text/html", "utf-8", null);
    }

    private String colorHex(int resId) {
        return String.format("#%06X", 0xFFFFFF & getColor(resId));
    }

    /** H5 扫码页请求摄像头时：先过系统运行时权限，拒绝也不阻断，页面仍可手输条码。 */
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
                .setPositiveButton("允许", (dialog, which) ->
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE))
                .setNegativeButton("拒绝", (dialog, which) -> denyPendingRequest())
                .setOnCancelListener(dialog -> denyPendingRequest())
                .show();
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

    private void showSiteDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(siteOrigin);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("后端地址")
                .setMessage("界面已内置在应用里，这里只改数据来自哪台服务器。"
                        + "默认 " + getString(R.string.site_origin_default) + "；"
                        + "内网联调可填 http://<电脑局域网IP>:8082。\n\n长按页面随时可以改回来。")
                .setView(input)
                .setPositiveButton("重开", (dialog, which) -> {
                    prefs().edit().putString(KEY_SITE, input.getText().toString().trim()).apply();
                    prepareSite(input.getText().toString());
                    loadShell();
                })
                .setNegativeButton("默认", (dialog, which) -> {
                    prefs().edit().remove(KEY_SITE).apply();
                    prepareSite(getString(R.string.site_origin_default));
                    loadShell();
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private int dialogTheme() {
        return isNightMode ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    @Override
    public void onBackPressed() {
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
        if (!isFinishing()) {
            denyPendingRequest();
        }
    }

    @Override
    protected void onDestroy() {
        backHandler.removeCallbacksAndMessages(null);
        denyPendingRequest();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
