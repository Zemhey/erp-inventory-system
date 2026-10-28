package com.yunshang.erp.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * 本地演示壳：用 WebView 托管 app/uniapp 的 H5 产物。
 *
 * 这不是 HBuilderX 云打包的正式 uni-app 安装包（正式包需要 DCloud 账号 + 签名证书）。
 * 它解决的是“现在就要在安卓手机上看界面”的问题：手机与电脑同一局域网，
 * 电脑上运行 启动系统.cmd 的 3008 预览服务，本壳加载该地址即可。
 */
public class MainActivity extends Activity {
    private static final String PREFS = "erp_demo";
    private static final String KEY_URL = "server_url";

    private WebView webView;
    private ProgressBar progressBar;

    // 记录系统当前是否深色，壳层配色与弹窗主题都据此切换
    private boolean isNightMode;
    // 首屏“再按一次退出”的冷却标记
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " YunshangERP-Demo/0.1");
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
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError(String.valueOf(error.getDescription()));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });

        webView.setOnLongClickListener(v -> {
            showUrlDialog(prefs().getString(KEY_URL, getString(R.string.server_url_default)), false);
            return true;
        });

        String saved = prefs().getString(KEY_URL, null);
        if (saved == null || saved.trim().isEmpty()) {
            showUrlDialog(getString(R.string.server_url_default), true);
        } else {
            load(saved);
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

    private void load(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        prefs().edit().putString(KEY_URL, url).apply();
        progressBar.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void showUrlDialog(String initial, boolean firstRun) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initial);
        input.setSelectAllOnFocus(true);

        // 深浅模式下分别使用对应的 Material 对话框主题，免得深色系统里弹出白底弹窗
        int theme = isNightMode ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        new AlertDialog.Builder(this, theme)
                .setTitle("服务器地址")
                .setMessage("填运行手机预览的电脑地址，例如 http://172.17.41.78:3008/。\n"
                        + "手机与电脑要在同一局域网，电脑上的 启动系统.cmd 要保持运行。\n"
                        + "也可以长按页面重新打开这个设置。")
                .setView(input)
                .setPositiveButton("打开", (dialog, which) -> load(input.getText().toString()))
                .setNegativeButton(firstRun ? "退出" : "取消", (dialog, which) -> {
                    if (firstRun) finish();
                })
                .setCancelable(!firstRun)
                .show();
    }

    private void showLoadError(String detail) {
        // 错误页配色走资源，随系统深色切换，不在此写死两套 hex
        String bg = colorHex(R.color.shell_bg);
        String fg = colorHex(R.color.shell_text);
        String html = "<html><body style='font-family:sans-serif;padding:24px;background:" + bg + ";color:" + fg + "'>"
                + "<h2>打不开页面</h2><p>" + detail + "</p>"
                + "<p>1. 确认电脑上的 <b>启动系统.cmd</b> 正在运行；<br>"
                + "2. 确认手机和电脑在同一局域网；<br>"
                + "3. 点右上角菜单 → 设置服务器，改成电脑的局域网地址；<br>"
                + "4. 或长按页面重新打开设置。</p>"
                + "<p>地址示例：<b>" + getString(R.string.server_url_default) + "</b></p>"
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
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showUrlDialog(prefs().getString(KEY_URL, getString(R.string.server_url_default)), false);
            return true;
        }
        if (item.getItemId() == 2) {
            webView.reload();
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
        Toast.makeText(this, "再按一次退出演示", Toast.LENGTH_SHORT).show();
        backHandler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
