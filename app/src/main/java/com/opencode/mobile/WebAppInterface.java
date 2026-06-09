package com.opencode.mobile;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class WebAppInterface {

    private static final String TAG = "WebAppInterface";
    private final TerminalService terminalService;
    private final MainActivity activity;
    private final Handler mainHandler;
    private boolean isOnOpenCodeServer = false;

    public WebAppInterface(MainActivity activity, TerminalService terminalService) {
        this.activity = activity;
        this.terminalService = terminalService;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void sendPayload(String command) {
        Log.d(TAG, "sendPayload: " + command);
        if (terminalService != null) {
            terminalService.write(command + "\n");
        }
    }

    @JavascriptInterface
    public void sendRawData(String data) {
        if (terminalService != null) {
            terminalService.write(data);
        }
    }

    @JavascriptInterface
    public boolean isFirstBoot() {
        return terminalService != null && terminalService.isFirstBoot();
    }

    @JavascriptInterface
    public void checkInstallStatus() {
        if (terminalService != null) {
            terminalService.checkInstallStatus();
        }
    }

    @JavascriptInterface
    public void sendCtrlC() {
        if (terminalService != null) {
            terminalService.write("\u0003");
        }
    }

    @JavascriptInterface
    public void navigateToServer() {
        mainHandler.post(() -> {
            isOnOpenCodeServer = true;
            WebView wv = activity.getWebView();
            if (wv != null) {
                wv.loadUrl("http://127.0.0.1:4096/");
            }
        });
    }

    @JavascriptInterface
    public void navigateToDashboard() {
        mainHandler.post(() -> {
            isOnOpenCodeServer = false;
            WebView wv = activity.getWebView();
            if (wv != null) {
                wv.loadUrl("file:///android_asset/ui/index.html");
            }
        });
    }

    @JavascriptInterface
    public boolean isOnServerPage() {
        return isOnOpenCodeServer;
    }

    public void onOpenCodeServerLoaded() {
        isOnOpenCodeServer = true;
    }

    public void onReturnToDashboard() {
        isOnOpenCodeServer = false;
    }

    public void onTerminalData(final String chunk) {
        mainHandler.post(() -> {
            WebView wv = activity.getWebView();
            if (wv != null) {
                String escaped = chunk
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
                wv.evaluateJavascript(
                    "window.onTerminalData('" + escaped + "')",
                    null
                );
            }
        });
    }

    public void onInstallProgress(final int percent, final String message) {
        mainHandler.post(() -> {
            WebView wv = activity.getWebView();
            if (wv != null) {
                String escaped = (message != null) ?
                    message.replace("'", "\\'").replace("\n", " ") : "";
                wv.evaluateJavascript(
                    "window.onInstallProgress(" + percent + ", '" + escaped + "')",
                    null
                );
            }
        });
    }

    public void onReady() {
        mainHandler.post(() -> {
            WebView wv = activity.getWebView();
            if (wv != null) {
                wv.evaluateJavascript("window.onBootReady()", null);
            }
        });
    }

    public void onError(final String title, final String details) {
        mainHandler.post(() -> {
            WebView wv = activity.getWebView();
            if (wv != null) {
                String escapedTitle = title != null ?
                    title.replace("'", "\\'").replace("\n", " ") : "";
                String escapedDetails = details != null ?
                    details.replace("'", "\\'").replace("\n", "\\n") : "";
                wv.evaluateJavascript(
                    "window.onBootError('" + escapedTitle + "', '" + escapedDetails + "')",
                    null
                );
            }
        });
    }
}
