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

    // ✅ روابط محدثة ومستقرة
    private static final String PROOT_URL = "https://github.com/termux/proot/releases/download/v5.3.1/proot-aarch64";
    private static final String BUSYBOX_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/main/aarch64/busybox-static-1.36.1-r6.apk";

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
        this.context     = context;
        this.executor    = Executors.newCachedThreadPool();
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setBridge(WebAppInterface bridge) {
        this.bridge = bridge;
    }

    // ✅ تمت إضافة الدالة التي كان يفتقدها الـ WebAppInterface
    public void write(String command) {
        writeExecutor.submit(() -> writeDirectly(command));
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
                notifyProgress(3, "Checking required tools...");
                doDownloadBinariesThenInstall();
            } else {
                firstBoot = false;
                notifyProgress(10, "Environment ready. Starting terminal...");
                doStartShell();
            }
        });
    }

    private void doDownloadBinariesThenInstall() {
        File filesDir    = context.getFilesDir();
        File prootFile   = new File(filesDir, "proot");
        File busyboxFile = new File(filesDir, "busybox");

        try {
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyProgress(8, "Downloading proot...");
                downloadFile(PROOT_URL, prootFile, 8, 25);
                prootFile.setExecutable(true);
            }

            if (!prootFile.exists() || prootFile.length() < 100_000) {
                notifyError("Download Failed", "proot binary missing or corrupt.");
                return;
            }

            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyProgress(28, "Downloading busybox...");
                File apkFile = new File(filesDir, "busybox.apk");
                downloadFile(BUSYBOX_URL, apkFile, 28, 42);
                extractBusyboxFromApk(apkFile, busyboxFile);
                apkFile.delete();
                busyboxFile.setExecutable(true);
            }

            if (!busyboxFile.exists() || busyboxFile.length() < 100_000) {
                notifyError("Download Failed", "busybox binary missing or corrupt.");
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "Binary download failed", e);
            notifyError("Download Failed", "Check internet connection.");
            return;
        }

        doExtractAndInstall();
    }

    private void extractBusyboxFromApk(File apkFile, File outFile) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkFile);
        java.util.zip.ZipEntry entry = zip.getEntry("bin/busybox");
        if (entry == null) entry = zip.getEntry("usr/bin/busybox");
        if (entry == null) {
            zip.close();
            throw new Exception("busybox entry not found inside APK");
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

    private void downloadFile(String urlStr, File outFile, int startPct, int endPct) throws Exception {
        if (outFile.exists()) outFile.delete();
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.connect();

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + conn.getResponseCode());
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
        fos.flush(); fos.close();
        in.close(); conn.disconnect();
    }

    private void doExtractAndInstall() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            String prootPath = new File(filesDir, "proot").getAbsolutePath();
            File prootTmp = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();

            notifyProgress(46, "Updating package manager...");
            boolean ok = runInProot(prootPath, rootfsDir.getAbsolutePath(), prootTmp.getAbsolutePath(), "apk update --no-cache");
            
            if (ok) {
                notifyProgress(60, "Installing environment...");
                ok = runInProot(prootPath, rootfsDir.getAbsolutePath(), prootTmp.getAbsolutePath(), "apk add --no-cache nodejs npm git");
            }
            
            if (ok) {
                notifyProgress(78, "Installing components...");
                ok = runInProot(prootPath, rootfsDir.getAbsolutePath(), prootTmp.getAbsolutePath(), "npm install -g opencode-ai@latest");
            }

            if (ok) {
                new File(rootfsDir, BOOT_MARKER).createNewFile();
                firstBoot = false;
                notifyProgress(90, "Setup complete!");
                doStartShell();
            } else {
                notifyError("Setup Failed", "Installation step failed.");
            }
        } catch (Exception e) {
            notifyError("Setup Failed", e.getMessage());
        }
    }

    private boolean runInProot(String prootPath, String rootfsPath, String prootTmpDir, String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(prootPath, "-r", rootfsPath, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root", "/bin/sh", "-c", cmd);
            pb.environment().put("HOME", "/root");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            pb.environment().put("PROOT_TMP_DIR", prootTmpDir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            return proc.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private void doStartShell() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            File prootFile = new File(filesDir, "proot");
            File prootTmp  = new File(filesDir, "proot-tmp");
            
            ProcessBuilder pb = new ProcessBuilder(prootFile.getAbsolutePath(), "-r", rootfsDir.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root", "/bin/sh");
            pb.environment().put("HOME", "/root");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            
            shellProcess = pb.start();
            shellInput   = shellProcess.getOutputStream();
            isRunning.set(true);
            startOutputReader(new BufferedReader(new InputStreamReader(shellProcess.getInputStream())));
            writeDirectly("opencode serve\n");
        } catch (Exception e) { notifyError("Shell Error", e.getMessage()); }
    }

    private void startOutputReader(final BufferedReader reader) {
        new Thread(() -> {
            try {
                String line;
                while (isRunning.get() && (line = reader.readLine()) != null) {
                    if (bridge != null) bridge.onTerminalData(line + "\r\n");
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void writeDirectly(String data) {
        try {
            if (shellInput != null && isRunning.get()) {
                shellInput.write(data.getBytes("UTF-8"));
                shellInput.flush();
            }
        } catch (Exception ignored) {}
    }

    public void stop() {
        isRunning.set(false);
        try { if (shellProcess != null) shellProcess.destroy(); } catch (Exception ignored) {}
    }

    private void notifyProgress(int p, String m) { if (bridge != null) bridge.onInstallProgress(p, m); }
    private void notifyError(String t, String d) { if (bridge != null) bridge.onError(t, d); }
}
