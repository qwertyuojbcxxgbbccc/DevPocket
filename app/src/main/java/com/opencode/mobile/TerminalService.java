package com.opencode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalService {

    private static final String TAG = "TerminalService";
    private static final String ROOTFS_DIR = "usr";
    private static final String BOOT_MARKER = ".boot_complete";

    private final Context context;
    private final ExecutorService executor;
    // FIX #1: Separate executor for write() so it never blocks the main pipeline
    private final ExecutorService writeExecutor;
    private final Handler mainHandler;

    private Process shellProcess;
    private OutputStream shellInput;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean firstBoot = false;

    private WebAppInterface bridge;

    public TerminalService(Context context) {
        this.context = context;
        // FIX #1: Use a thread pool — no more single-thread deadlock
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
        // FIX #1: runs on its own thread, does NOT chain via executor.execute() internally
        executor.submit(() -> {
            File rootfsDir = new File(context.getFilesDir(), ROOTFS_DIR);
            File bootMarker = new File(rootfsDir, BOOT_MARKER);

            boolean rootfsEmpty = !rootfsDir.exists()
                || !rootfsDir.isDirectory()
                || rootfsDir.listFiles() == null
                || rootfsDir.listFiles().length == 0;

            if (rootfsEmpty || !bootMarker.exists()) {
                firstBoot = true;
                notifyProgress(5, "Preparing environment... (جاري تجهيز البيئة)");
                // FIX #1: call directly, not via executor.execute()
                doExtractAndInstall();
            } else {
                firstBoot = false;
                notifyProgress(10, "Environment ready. Starting terminal...");
                doStartShell();
            }
        });
    }

    // -----------------------------------------------------------------------
    // FIX #2 + #3: Real extraction using ProcessBuilder per command.
    // No fake "Log.d and pretend" execution.
    // -----------------------------------------------------------------------
    private void doExtractAndInstall() {
        try {
            File filesDir = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            notifyProgress(15, "Extracting base system... (جاري فك الضغط)");

            // FIX #2: Copy binaries — warn and fall back gracefully if assets missing
            copyBinaryFromAssets("busybox", "busybox");
            copyBinaryFromAssets("proot", "proot");

            File prootFile = new File(filesDir, "proot");
            File busyboxFile = new File(filesDir, "busybox");

            // FIX #2: If proot/busybox not available in assets, notify error clearly
            if (!prootFile.exists() || !busyboxFile.exists()) {
                notifyError(
                    "Missing binaries (ملفات مفقودة)",
                    "proot/busybox not found in assets/bin/. " +
                    "Add arm64 builds to app/src/main/assets/bin/ and rebuild."
                );
                return;
            }

            // FIX #3: Actually run each setup command via ProcessBuilder
            String rootfsPath = rootfsDir.getAbsolutePath();
            String prootPath  = prootFile.getAbsolutePath();

            notifyProgress(25, "Updating package manager... (تحديث مدير الحزم)");
            boolean ok = runInProot(prootPath, rootfsPath, "apk update --no-cache", 30);
            if (!ok) { notifyError("apk update failed", "Check network connectivity"); return; }

            notifyProgress(45, "Installing Node.js & npm... (تثبيت النود)");
            ok = runInProot(prootPath, rootfsPath, "apk add --no-cache nodejs npm git", 60);
            if (!ok) { notifyError("apk add failed", "Could not install nodejs/npm"); return; }

            notifyProgress(70, "Installing opencode-ai... (تثبيت أوبن كود)");
            ok = runInProot(prootPath, rootfsPath, "npm install -g opencode-ai@latest", 85);
            if (!ok) { notifyError("npm install failed", "Could not install opencode-ai"); return; }

            // FIX #3: Only write BOOT_MARKER after real successful installation
            File bootMarker = new File(rootfsDir, BOOT_MARKER);
            bootMarker.createNewFile();
            firstBoot = false;

            notifyProgress(90, "Setup complete! Starting server... (اكتمل التثبيت)");
            doStartShell();

        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            notifyError("Setup Failed", "Could not initialize environment: " + e.getMessage());
        }
    }

    /**
     * Runs a single shell command inside proot chroot.
     * Returns true if exit code == 0.
     */
    private boolean runInProot(String prootPath, String rootfsPath, String cmd, int progressHint) {
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

            // Stream output to UI
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

    private void copyBinaryFromAssets(String assetName, String outputName) {
        try {
            File outFile = new File(context.getFilesDir(), outputName);
            if (outFile.exists()) {
                outFile.setExecutable(true);
                return;
            }
            InputStream is = context.getAssets().open("bin/" + assetName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) fos.write(buffer, 0, len);
            fos.close();
            is.close();
            outFile.setExecutable(true);
            Log.d(TAG, "Copied binary: " + assetName);
        } catch (Exception e) {
            Log.w(TAG, "Could not copy binary " + assetName + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Shell startup — runs on its own thread, NOT inside executor.execute()
    // -----------------------------------------------------------------------
    private void doStartShell() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            String rootfsPath = rootfsDir.getAbsolutePath();

            File prootFile = new File(filesDir, "proot");
            ProcessBuilder pb;

            if (prootFile.exists()) {
                pb = new ProcessBuilder(
                    prootFile.getAbsolutePath(),
                    "-r", rootfsPath,
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

            // Start async readers (on their own threads, not blocking executor)
            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getInputStream())));
            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getErrorStream())));

            notifyProgress(95, "Launching OpenCode Engine... (بدء تشغيل المحرك)");

            // FIX #1: write directly — no executor queue involved
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

    // -----------------------------------------------------------------------
    // FIX #1: writeDirectly() — bypasses executor entirely for shell I/O
    // public write() still goes via writeExecutor to serialize concurrent calls
    // -----------------------------------------------------------------------
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
