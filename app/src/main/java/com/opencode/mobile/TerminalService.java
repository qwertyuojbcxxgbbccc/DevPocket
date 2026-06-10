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

    // ✅ proot — روابط مع fallback
    private static final String[] PROOT_URLS = {
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot",
        "https://cdn.jsdelivr.net/gh/skirsten/proot-portable-android-binaries@latest/aarch64/proot"
    };

    // ✅ busybox — binary مباشر لـ Android بدون APK
    private static final String[] BUSYBOX_URLS = {
        "https://raw.githubusercontent.com/EXALAB/Busybox-static/main/busybox_arm64",
        "https://raw.githubusercontent.com/xerta555/Busybox-Binaries/master/busybox-arm64",
        "https://raw.githubusercontent.com/shutingrz/busybox-static-binaries-fat/main/busybox-aarch64-linux-gnu"
    };

    // ✅ Alpine Linux minirootfs aarch64 — المشكلة الأساسية: rootfs لم يكن يُحمَّل أبداً!
    private static final String[] ALPINE_ROOTFS_URLS = {
        "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.7-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.9-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
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
    // المرحلة 1: تحميل proot و busybox و Alpine rootfs
    // -----------------------------------------------------------------------
    private void doDownloadBinariesThenInstall() {
        File filesDir    = context.getFilesDir();
        File prootFile   = new File(filesDir, "proot");
        File busyboxFile = new File(filesDir, "busybox");

        try {
            // --- proot ---
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyProgress(5, "Downloading proot... (تحميل proot)");
                boolean ok = downloadFileWithFallback(PROOT_URLS, prootFile, 5, 18);
                if (ok) {
                    prootFile.setExecutable(true);
                    Log.d(TAG, "proot OK: " + prootFile.length() + " bytes");
                }
            }
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyError("proot download failed",
                    "All sources failed.\nTried:\n" + String.join("\n", PROOT_URLS));
                return;
            }

            // --- busybox: binary مباشر ---
            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyProgress(20, "Downloading busybox... (تحميل busybox)");
                boolean ok = downloadFileWithFallback(BUSYBOX_URLS, busyboxFile, 20, 32);
                if (ok) {
                    busyboxFile.setExecutable(true);
                    Log.d(TAG, "busybox OK: " + busyboxFile.length() + " bytes");
                }
            }
            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyError("busybox download failed",
                    "All sources failed.\nTried:\n" + String.join("\n", BUSYBOX_URLS));
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "Binary download failed", e);
            notifyError("Download Failed (فشل التحميل)", e.getMessage());
            return;
        }

        doDownloadAndExtractRootfs();
    }

    // -----------------------------------------------------------------------
    // المرحلة 2: تحميل Alpine minirootfs وفكّه — هذا كان مفقوداً تماماً!
    // -----------------------------------------------------------------------
    private void doDownloadAndExtractRootfs() {
        File filesDir  = context.getFilesDir();
        File rootfsDir = new File(filesDir, ROOTFS_DIR);

        // التحقق: هل Alpine موجودة مسبقاً (bin/sh)؟
        File binSh = new File(rootfsDir, "bin/sh");
        if (binSh.exists()) {
            Log.d(TAG, "Alpine rootfs already extracted, skipping download");
            doInstallPackages();
            return;
        }

        File tarFile = new File(filesDir, "alpine-rootfs.tar.gz");
        try {
            notifyProgress(33, "Downloading Alpine Linux... (تحميل Alpine)");
            boolean ok = downloadFileWithFallback(ALPINE_ROOTFS_URLS, tarFile, 33, 52);
            if (!ok || !tarFile.exists() || tarFile.length() < 500_000) {
                notifyError("Alpine download failed",
                    "Could not download Alpine rootfs.\nTried:\n"
                    + String.join("\n", ALPINE_ROOTFS_URLS));
                return;
            }
            Log.d(TAG, "Alpine tar OK: " + tarFile.length() + " bytes");

            // إنشاء rootfsDir
            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            notifyProgress(54, "Extracting Alpine Linux... (فك ضغط Alpine)");
            extractTarGz(tarFile, rootfsDir);
            tarFile.delete();

            // التحقق من نجاح الاستخراج
            if (!binSh.exists()) {
                notifyError("Extract failed",
                    "Alpine extraction incomplete — /bin/sh not found in rootfs.");
                return;
            }
            Log.d(TAG, "Alpine extracted OK");

        } catch (Exception e) {
            Log.e(TAG, "Rootfs setup failed", e);
            if (tarFile.exists()) tarFile.delete();
            notifyError("Alpine Setup Failed", e.getMessage());
            return;
        }

        doInstallPackages();
    }

    // -----------------------------------------------------------------------
    // فك ضغط tar.gz باستخدام busybox
    // -----------------------------------------------------------------------
    private void extractTarGz(File tarFile, File destDir) throws Exception {
        File busyboxFile = new File(context.getFilesDir(), "busybox");

        // استخدم busybox tar لفك الضغط
        ProcessBuilder pb = new ProcessBuilder(
            busyboxFile.getAbsolutePath(),
            "tar", "-xzf", tarFile.getAbsolutePath(),
            "-C", destDir.getAbsolutePath()
        );
        pb.environment().put("HOME", "/tmp");
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
            if (bridge != null) bridge.onTerminalData(line + "\r\n");
        }
        int exit = proc.waitFor();
        Log.d(TAG, "tar exit=" + exit + " output=" + output.toString().substring(
            0, Math.min(200, output.length())));

        if (exit != 0) {
            throw new Exception("tar extraction failed (exit=" + exit + ")\n" + output);
        }
    }

    // -----------------------------------------------------------------------
    // المرحلة 3: تثبيت الحزم داخل Alpine
    // -----------------------------------------------------------------------
    private void doInstallPackages() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            String rootfsPath = rootfsDir.getAbsolutePath();
            String prootPath  = new File(filesDir, "proot").getAbsolutePath();

            File prootTmp = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();
            String prootTmpPath = prootTmp.getAbsolutePath();

            // إعداد /etc/resolv.conf للشبكة داخل proot
            setupResolvConf(rootfsDir);

            notifyProgress(58, "Updating package list... (تحديث قائمة الحزم)");
            boolean ok = runInProot(prootPath, rootfsPath, prootTmpPath,
                "apk update --no-cache");
            if (!ok) {
                // محاولة ثانية بعد إصلاح DNS
                Log.w(TAG, "apk update failed, retrying after DNS fix...");
                setupResolvConf(rootfsDir);
                ok = runInProot(prootPath, rootfsPath, prootTmpPath,
                    "apk update --no-cache");
            }
            if (!ok) {
                notifyError("apk update failed",
                    "Could not reach Alpine package servers.\nCheck internet connection.");
                return;
            }

            notifyProgress(68, "Installing Node.js & npm... (تثبيت Node.js)");
            ok = runInProot(prootPath, rootfsPath, prootTmpPath,
                "apk add --no-cache nodejs npm git");
            if (!ok) {
                notifyError("apk add failed", "Could not install nodejs/npm");
                return;
            }

            notifyProgress(82, "Installing opencode-ai... (تثبيت opencode)");
            ok = runInProot(prootPath, rootfsPath, prootTmpPath,
                "npm install -g opencode-ai@latest");
            if (!ok) {
                notifyError("npm install failed", "Could not install opencode-ai");
                return;
            }

            new File(rootfsDir, BOOT_MARKER).createNewFile();
            firstBoot = false;

            notifyProgress(92, "Setup complete! Starting... (اكتمل التثبيت)");
            doStartShell();

        } catch (Exception e) {
            Log.e(TAG, "Install packages failed", e);
            notifyError("Setup Failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // إعداد DNS داخل rootfs حتى تعمل الشبكة داخل proot
    // -----------------------------------------------------------------------
    private void setupResolvConf(File rootfsDir) {
        try {
            File etcDir = new File(rootfsDir, "etc");
            if (!etcDir.exists()) etcDir.mkdirs();
            File resolv = new File(etcDir, "resolv.conf");
            FileOutputStream fos = new FileOutputStream(resolv);
            fos.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes("UTF-8"));
            fos.close();
            Log.d(TAG, "resolv.conf written");
        } catch (Exception e) {
            Log.w(TAG, "Could not write resolv.conf: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // تحميل مع fallback
    // -----------------------------------------------------------------------
    private boolean downloadFileWithFallback(String[] urls, File outFile,
                                              int startPct, int endPct) {
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            try {
                notifyProgress(startPct,
                    "Source " + (i + 1) + "/" + urls.length + "...");
                Log.d(TAG, "Downloading: " + url);
                downloadFile(url, outFile, startPct, endPct);
                if (outFile.exists() && outFile.length() > 10_000) {
                    Log.d(TAG, "✅ OK: " + url + " [" + outFile.length() + "b]");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "❌ [" + url + "]: " + e.getMessage());
            }
            if (outFile.exists()) outFile.delete();
        }
        return false;
    }

    private void downloadFile(String urlStr, File outFile, int startPct, int endPct)
            throws Exception {
        if (outFile.exists()) outFile.delete();

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
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
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            fos.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = startPct + (int)((done * (endPct - startPct)) / total);
                notifyProgress(Math.min(pct, endPct), null);
            }
        }
        fos.flush(); fos.close();
        in.close(); conn.disconnect();
    }

    // -----------------------------------------------------------------------
    private boolean runInProot(String prootPath, String rootfsPath,
                               String prootTmpDir, String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                prootPath,
                "--kill-on-exit",
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
                if (bridge != null) bridge.onTerminalData(line + "\r\n");
            }
            int exit = proc.waitFor();
            Log.d(TAG, "[proot] " + cmd + " => exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "runInProot failed: " + e.getMessage());
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
            if (prootFile.exists() && rootfsDir.exists()) {
                pb = new ProcessBuilder(
                    prootFile.getAbsolutePath(),
                    "--kill-on-exit",
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

            notifyProgress(96, "Launching OpenCode Engine... (بدء تشغيل المحرك)");
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
            try {
                if (shellProcess != null) shellProcess.destroy();
            } catch (Exception ignored) {}
        });
    }

    private void notifyProgress(int percent, String message) {
        if (bridge != null) bridge.onInstallProgress(percent, message);
    }

    private void notifyError(String title, String details) {
        if (bridge != null) bridge.onError(title, details);
    }
}
