package com.opencode.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;

public class MainActivity extends Activity {

    private WebView webView;
    private WebAppInterface webAppInterface;
    private TerminalService terminalService;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupImmersiveMode();

        FrameLayout container = new FrameLayout(this);
        container.setId(View.generateViewId());
        container.setBackgroundColor(Color.parseColor("#0D1117"));
        setContentView(container);

        terminalService = new TerminalService(this);
        webAppInterface = new WebAppInterface(this, terminalService);

        webView = new WebView(this);
        webView.setId(View.generateViewId());
        webView.setBackgroundColor(Color.parseColor("#0D1117"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(webAppInterface, "AndroidTerminalBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.equals("http://127.0.0.1:4096/") || url.startsWith("http://127.0.0.1:4096")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.equals("http://127.0.0.1:4096/") || url.startsWith("http://127.0.0.1:4096")) {
                    webAppInterface.onOpenCodeServerLoaded();
                }
            }
        });

        webView.loadUrl("file:///android_asset/ui/index.html");

        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        container.addView(webView, webParams);
    }

    private void setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onBackPressed() {
        String currentUrl = webView.getUrl();
        if (currentUrl != null && currentUrl.contains("127.0.0.1:4096")) {
            webView.loadUrl("file:///android_asset/ui/index.html");
            webAppInterface.onReturnToDashboard();
            return;
        }

        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (terminalService != null) {
            terminalService.stop();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    public WebView getWebView() {
        return webView;
    }
}
