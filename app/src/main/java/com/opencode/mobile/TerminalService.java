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
    private static final String ROOTFS_DIR = "usr";
    private static final String BOOT_MARKER = ".boot_complete";

    // روابط تحميل ثابتة من GitHub Releases — arm64 static builds
    private static final String PROOT_URL =
        "https://github.com/termux/proot/releases/download/v5.1.107/proot-aarch64";
    private static final String BUSYBOX_URL =
        "https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox-armv8l";

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
        this.context = context;
        this.executor = Executors.newCachedThreadPool();
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setBridge(WebAppInterface bridge) {
        this.bridge = bridge;
    }

    public boolean isFirstBoot() {
        return firstBoot;
    }

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
    // تحميل proot و busybox إذا لم يكونا موجودَين
    // -----------------------------------------------------------------------
    private void doDownloadBinariesThenInstall() {
        File filesDir   = context.getFilesDir();
        File prootFile  = new File(filesDir, "proot");
        File busyboxFile = new File(filesDir, "busybox");

        try {
            // --- proot ---
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyProgress(8, "Downloading proot... (تحميل proot)");
                downloadFile(PROOT_URL, prootFile, 8, 20);
                prootFile.setExecutable(true);
                Log.d(TAG, "proot downloaded: " + prootFile.length() + " bytes");
            }

            // --- busybox ---
            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyProgress(22, "Downloading busybox... (تحميل busybox)");
                downloadFile(BUSYBOX_URL, busyboxFile, 22, 35);
                busyboxFile.setExecutable(true);
                Log.d(TAG, "busybox downloaded: " + busyboxFile.length() + " bytes");
            }

        } catch (Exception e) {
            Log.e(TAG, "Binary download failed", e);
            notifyError(
                "Download Failed (فشل التحميل)",
                "Could not download proot/busybox. Check internet connection.\n" + e.getMessage()
            );
            return;
        }

        // تحقق أن الملفات سليمة بعد التحميل
        if (!prootFile.exists() || prootFile.length() < 100_000) {
            notifyError("proot invalid", "Downloaded file is too small or corrupt.");
            return;
        }
        if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
            notifyError("busybox invalid", "Downloaded file is too small or corrupt.");
            return;
        }

        // بعد التحميل نكمل التثبيت
        doExtractAndInstall();
    }

    /**
     * تحميل ملف من URL مع تتبع التقدم بين startPct و endPct
     */
    private void downloadFile(String urlStr, File outFile, int startPct, int endPct)
            throws Exception {

        // حذف ملف قديم ناقص إن وُجد
        if (outFile.exists()) outFile.delete();

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "DevPocket/1.0 Android");
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + responseCode + " for " + urlStr);
        }

        long totalBytes = conn.getContentLengthLong();
        long downloaded = 0;

        InputStream in  = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buffer = new byte[8192];
        int n;

        while ((n = in.read(buffer)) != -1) {
            fos.write(buffer, 0, n);
            downloaded += n;

            // تحديث شريط التقدم
            if (totalBytes > 0) {
                int pct = startPct + (int) ((downloaded * (endPct - startPct)) / totalBytes);
                notifyProgress(Math.min(pct, endPct), null);
            }
        }

        fos.flush();
        fos.close();
        in.close();
        conn.disconnect();
    }

    // -----------------------------------------------------------------------
    // تثبيت Alpine عبر proot
    // -----------------------------------------------------------------------
    private void doExtractAndInstall() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            String rootfsPath = rootfsDir.getAbsolutePath();
            String prootPath  = new File(filesDir, "proot").getAbsolutePath();

            notifyProgress(38, "Updating package manager... (تحديث مدير الحزم)");
            boolean ok = runInProot(prootPath, rootfsPath, "apk update --no-cache");
            if (!ok) { notifyError("apk update failed", "Check network connectivity"); return; }

            notifyProgress(55, "Installing Node.js & npm... (تثبيت النود)");
            ok = runInProot(prootPath, rootfsPath, "apk add --no-cache nodejs npm git");
            if (!ok) { notifyError("apk add failed", "Could not install nodejs/npm"); return; }

            notifyProgress(75, "Installing opencode-ai... (تثبيت أوبن كود)");
            ok = runInProot(prootPath, rootfsPath, "npm install -g opencode-ai@latest");
            if (!ok) { notifyError("npm install failed", "Could not install opencode-ai"); return; }

            // كتابة علامة اكتمال التثبيت فقط بعد النجاح الفعلي
            new File(rootfsDir, BOOT_MARKER).createNewFile();
            firstBoot = false;

            notifyProgress(90, "Setup complete! Starting server... (اكتمل التثبيت)");
            doStartShell();

        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            notifyError("Setup Failed", "Could not initialize environment: " + e.getMessage());
        }
    }

    private boolean runInProot(String prootPath, String rootfsPath, String cmd) {
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
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("LD_PRELOAD", "");
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String l = line;
                if (bridge != null) bridge.onTerminalData(l + "\r\n");
            }

            int exitCode = proc.waitFor();
            Log.d(TAG, "Command [" + cmd + "] exit=" + exitCode);
            return exitCode == 0;

        } catch (Exception e) {
            Log.e(TAG, "runInProot error: " + e.getMessage());
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
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("LD_PRELOAD", "");
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
            notifyError("Shell Error", "Could not start terminal: " + e.getMessage());
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
                        if (chunk.contains("http://127.0.0.1:4096")) {
                            bridge.onReady();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Output reader error: " + e.getMessage());
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
            Log.e(TAG, "writeDirectly error: " + e.getMessage());
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
