package com.opencode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalService {

    private static final String TAG = "TerminalService";
    private static final String ROOTFS_DIR  = "usr";
    private static final String BOOT_MARKER = ".boot_complete";

    // ✅ روابط proot مع fallback — مرتّبة حسب الأولوية
    private static final String[] PROOT_URLS = {
        // GitHub Pages (ثابت ولا يتغير)
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot",
        // jsDelivr CDN من نفس المصدر
        "https://cdn.jsdelivr.net/gh/skirsten/proot-portable-android-binaries@latest/aarch64/proot",
        // نسخة محددة من termux (v5.1.107-1 الصحيحة)
        "https://github.com/termux/proot/releases/download/v5.1.107-1/proot-aarch64"
    };

    // ✅ busybox من Alpine CDN — arm64 musl static
    private static final String[] BUSYBOX_URLS = {
        "https://dl-cdn.alpinelinux.org/alpine/edge/main/aarch64/busybox-static-1.36.1-r2.apk",
        "https://dl-cdn.alpinelinux.org/alpine/v3.19/main/aarch64/busybox-static-1.36.1-r2.apk",
        "https://dl-cdn.alpinelinux.org/alpine/v3.18/main/aarch64/busybox-static-1.36.1-r0.apk"
    };

    private final Context context;
    private final ExecutorService executor;
    private final ExecutorService writeExecutor;
    private final Handler mainHandler;

    private Process shellProcess;
    private OutputStream shellInput;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean firstBoot = false;

    private WebAppInterface bridge;

    public TerminalService(Context context) {
        this.context       = context;
        this.executor      = Executors.newCachedThreadPool();
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler   = new Handler(Looper.getMainLooper());
    }

    public void setBridge(WebAppInterface bridge) {
        this.bridge = bridge;
    }

    public boolean isFirstBoot() {
        return firstBoot;
    }

    // -----------------------------------------------------------------------
    public void checkInstallStatus() {
        executor.submit(() -> {
            File rootfsDir  = new File(context.getFilesDir(), ROOTFS_DIR);
            File bootMarker = new File(rootfsDir, BOOT_MARKER);

            boolean rootfsEmpty = !rootfsDir.exists()
                || !rootfsDir.isDirectory()
                || rootfsDir.listFiles() == null
                || rootfsDir.listFiles().length == 0;

            if (rootfsEmpty || !bootMarker.exists()) {
                firstBoot = true;
                notifyProgress(3, "Checking required tools... (فحص الأدوات المطلوبة)");
                doDownloadBinariesThenInstall();
            } else {
                firstBoot = false;
                notifyProgress(10, "Environment ready. Starting terminal...");
                doStartShell();
            }
        });
    }

    // -----------------------------------------------------------------------
    // تحميل proot و busybox مع دعم fallback
    // -----------------------------------------------------------------------
    private void doDownloadBinariesThenInstall() {
        File filesDir    = context.getFilesDir();
        File prootFile   = new File(filesDir, "proot");
        File busyboxFile = new File(filesDir, "busybox");

        try {
            // --- proot (مع fallback) ---
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyProgress(8, "Downloading proot... (تحميل proot)");
                boolean downloaded = downloadFileWithFallback(PROOT_URLS, prootFile, 8, 25);
                if (downloaded) {
                    prootFile.setExecutable(true);
                    Log.d(TAG, "proot OK: " + prootFile.length() + " bytes");
                }
            }

            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyError(
                    "proot download failed",
                    "All download sources failed.\nTried:\n- " + String.join("\n- ", PROOT_URLS)
                );
                return;
            }

            // --- busybox (مع fallback) ---
            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyProgress(28, "Downloading busybox... (تحميل busybox)");
                File apkFile = new File(filesDir, "busybox.apk");
                boolean downloaded = downloadFileWithFallback(BUSYBOX_URLS, apkFile, 28, 42);
                if (downloaded) {
                    extractBusyboxFromApk(apkFile, busyboxFile);
                    apkFile.delete();
                    busyboxFile.setExecutable(true);
                    Log.d(TAG, "busybox OK: " + busyboxFile.length() + " bytes");
                }
            }

            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyError(
                    "busybox download failed",
                    "All download sources failed.\nTried:\n- " + String.join("\n- ", BUSYBOX_URLS)
                );
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "Binary download failed", e);
            notifyError(
                "Download Failed (فشل التحميل)",
                "Check internet connection.\n" + e.getMessage()
            );
            return;
        }

        doExtractAndInstall();
    }

    // -----------------------------------------------------------------------
    // تحميل مع fallback — يجرّب كل رابط بالترتيب
    // -----------------------------------------------------------------------
    private boolean downloadFileWithFallback(String[] urls, File outFile,
                                              int startPct, int endPct) {
        Exception lastError = null;
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            try {
                notifyProgress(startPct, "Trying source " + (i + 1) + "/" + urls.length + "...");
                Log.d(TAG, "Downloading from: " + url);
                downloadFile(url, outFile, startPct, endPct);
                if (outFile.exists() && outFile.length() > 100_000) {
                    Log.d(TAG, "✅ Downloaded OK from: " + url);
                    return true;
                } else {
                    Log.w(TAG, "⚠️ File too small from: " + url + " size=" + outFile.length());
                }
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "❌ Failed [" + url + "]: " + e.getMessage());
                if (outFile.exists()) outFile.delete(); // cleanup قبل المحاولة التالية
            }
        }
        Log.e(TAG, "All sources failed. Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
        return false;
    }

    // -----------------------------------------------------------------------
    // استخراج busybox من Alpine APK (zip)
    // -----------------------------------------------------------------------
    private void extractBusyboxFromApk(File apkFile, File outFile) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkFile);

        // جرّب المسارات المحتملة داخل APK
        String[] possiblePaths = { "bin/busybox", "usr/bin/busybox", "busybox" };
        java.util.zip.ZipEntry entry = null;
        for (String path : possiblePaths) {
            entry = zip.getEntry(path);
            if (entry != null) {
                Log.d(TAG, "Found busybox at: " + path);
                break;
            }
        }

        if (entry == null) {
            zip.close();
            // طباعة محتويات الـ APK للتشخيص
            StringBuilder contents = new StringBuilder();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                contents.append(entries.nextElement().getName()).append("\n");
            }
            throw new Exception("busybox entry not found inside APK.\nContents:\n" + contents);
        }

        InputStream in  = zip.getInputStream(entry);
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        fos.close();
        in.close();
        zip.close();
    }

    // -----------------------------------------------------------------------
    private void downloadFile(String urlStr, File outFile, int startPct, int endPct)
            throws Exception {
        if (outFile.exists()) outFile.delete();

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "DevPocket/1.0 Android");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + urlStr);
        }

        long total = conn.getContentLengthLong();
        long done  = 0;

        InputStream in  = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            fos.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = startPct + (int)((done * (endPct - startPct)) / total);
                notifyProgress(Math.min(pct, endPct), null);
            }
        }
        fos.flush();
        fos.close();
        in.close();
        conn.disconnect();
    }

    // -----------------------------------------------------------------------
    // تثبيت Alpine
    // -----------------------------------------------------------------------
    private void doExtractAndInstall() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            String rootfsPath = rootfsDir.getAbsolutePath();
            String prootPath  = new File(filesDir, "proot").getAbsolutePath();

            File prootTmp = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();

            notifyProgress(46, "Updating package manager... (تحديث مدير الحزم)");
            boolean ok = runInProot(prootPath, rootfsPath, prootTmp.getAbsolutePath(),
                "apk update --no-cache");
            if (!ok) { notifyError("apk update failed", "Check network connectivity"); return; }

            notifyProgress(60, "Installing Node.js & npm... (تثبيت النود)");
            ok = runInProot(prootPath, rootfsPath, prootTmp.getAbsolutePath(),
                "apk add --no-cache nodejs npm git");
            if (!ok) { notifyError("apk add failed", "Could not install nodejs/npm"); return; }

            notifyProgress(78, "Installing opencode-ai... (تثبيت أوبن كود)");
            ok = runInProot(prootPath, rootfsPath, prootTmp.getAbsolutePath(),
                "npm install -g opencode-ai@latest");
            if (!ok) { notifyError("npm install failed", "Could not install opencode-ai"); return; }

            new File(rootfsDir, BOOT_MARKER).createNewFile();
            firstBoot = false;

            notifyProgress(90, "Setup complete! Starting server... (اكتمل التثبيت)");
            doStartShell();

        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            notifyError("Setup Failed", e.getMessage());
        }
    }

    private boolean runInProot(String prootPath, String rootfsPath,
                               String prootTmpDir, String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                prootPath,
                "-r", rootfsPath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "/bin/sh", "-c", cmd
            );
            pb.environment().put("HOME", "/root");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PATH",
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("LD_PRELOAD", "");
            pb.environment().put("PROOT_TMP_DIR", prootTmpDir);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String l = line;
                if (bridge != null) bridge.onTerminalData(l + "\r\n");
            }
            int exit = proc.waitFor();
            Log.d(TAG, "[" + cmd + "] exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "runInProot: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // تشغيل الـ shell
    // -----------------------------------------------------------------------
    private void doStartShell() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            File prootFile = new File(filesDir, "proot");
            File prootTmp  = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();

            ProcessBuilder pb;
            if (prootFile.exists()) {
                pb = new ProcessBuilder(
                    prootFile.getAbsolutePath(),
                    "-r", rootfsDir.getAbsolutePath(),
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-w", "/root",
                    "/bin/sh"
                );
            } else {
                pb = new ProcessBuilder("/system/bin/sh");
            }

            pb.environment().put("HOME", "/root");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("SHELL", "/bin/sh");
            pb.environment().put("USER", "root");
            pb.environment().put("PATH",
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("LD_PRELOAD", "");
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            pb.directory(rootfsDir.exists() ? rootfsDir : filesDir);

            shellProcess = pb.start();
            shellInput   = shellProcess.getOutputStream();
            isRunning.set(true);

            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getInputStream())));
            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getErrorStream())));

            notifyProgress(95, "Launching OpenCode Engine... (بدء تشغيل المحرك)");
            writeDirectly("opencode serve\n");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start shell", e);
            notifyError("Shell Error", e.getMessage());
        }
    }

    private void startOutputReader(final BufferedReader reader) {
        Thread t = new Thread(() -> {
            try {
                char[] buf = new char[4096];
                int n;
                while (isRunning.get() && (n = reader.read(buf, 0, buf.length)) != -1) {
                    String chunk = new String(buf, 0, n);
                    if (bridge != null) {
                        bridge.onTerminalData(chunk);
                        if (chunk.contains("http://127.0.0.1:4096")) bridge.onReady();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Reader: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void writeDirectly(String data) {
        try {
            if (shellInput != null && isRunning.get()) {
                shellInput.write(data.getBytes("UTF-8"));
                shellInput.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "writeDirectly: " + e.getMessage());
        }
    }

    public void write(String command) {
        writeExecutor.submit(() -> writeDirectly(command));
    }

    public void stop() {
        isRunning.set(false);
        writeExecutor.submit(() -> {
            writeDirectly("exit\n");
            try { if (shellProcess != null) shellProcess.destroy(); } catch (Exception ignored) {}
        });
    }

    private void notifyProgress(int percent, String message) {
        if (bridge != null) bridge.onInstallProgress(percent, message);
    }

    private void notifyError(String title, String details) {
        if (bridge != null) bridge.onError(title, details);
    }
}
