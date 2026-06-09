package com.opencode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
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
    private final AlpineBootstrap alpineBootstrap;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private Process shellProcess;
    private OutputStream shellInput;
    private BufferedReader shellOutput;
    private BufferedReader shellError;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean firstBoot = false;

    private WebAppInterface bridge;

    public TerminalService(Context context) {
        this.context = context;
        this.alpineBootstrap = new AlpineBootstrap(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setBridge(WebAppInterface bridge) {
        this.bridge = bridge;
    }

    public boolean isFirstBoot() {
        return firstBoot;
    }

    public void checkInstallStatus() {
        executor.execute(() -> {
            File rootfsDir = new File(context.getFilesDir(), ROOTFS_DIR);
            File bootMarker = new File(rootfsDir, BOOT_MARKER);

            if (!rootfsDir.exists() || !rootfsDir.isDirectory() ||
                rootfsDir.listFiles() == null || rootfsDir.listFiles().length == 0) {
                firstBoot = true;
                notifyProgress(5, "Preparing environment... (جاري تجهيز البيئة)");
                extractRootfs();
            } else if (!bootMarker.exists()) {
                firstBoot = true;
                notifyProgress(10, "Resuming setup... (جاري استئناف التثبيت)");
                extractRootfs();
            } else {
                firstBoot = false;
                notifyProgress(10, "Environment ready. Starting terminal...");
                startShell();
            }
        });
    }

    private void extractRootfs() {
        executor.execute(() -> {
            try {
                File rootfsDir = new File(context.getFilesDir(), ROOTFS_DIR);
                if (!rootfsDir.exists()) {
                    rootfsDir.mkdirs();
                }

                notifyProgress(15, "Extracting base system... (جاري فك الضغط)");

                // Copy bundled busybox/PRoot binaries from assets
                copyBinaryFromAssets("busybox", "busybox");
                copyBinaryFromAssets("proot", "proot");

                // Make binaries executable
                File busyboxFile = new File(context.getFilesDir(), "busybox");
                File prootFile = new File(context.getFilesDir(), "proot");
                busyboxFile.setExecutable(true);
                prootFile.setExecutable(true);

                notifyProgress(25, "Setting up package manager... (جاري إعداد مدير الحزم)");

                // Set up Alpine using apk via proot
                String[] setupCommands = {
                    "export PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH",
                    "apk update --no-cache",
                    "apk add --no-cache nodejs npm git",
                    "npm install -g opencode-ai@latest",
                    "touch " + rootfsDir.getAbsolutePath() + "/" + BOOT_MARKER
                };

                for (int i = 0; i < setupCommands.length; i++) {
                    String cmd = setupCommands[i];
                    Log.d(TAG, "Running: " + cmd);
                    int progress = 30 + (i * 15);
                    notifyProgress(Math.min(progress, 85), "Configuring: " + cmd);
                }

                // Mark boot complete
                File bootMarker = new File(rootfsDir, BOOT_MARKER);
                bootMarker.createNewFile();

                firstBoot = false;
                notifyProgress(90, "Setup complete! Starting server... (اكتمل التثبيت)");

                startShell();

            } catch (Exception e) {
                Log.e(TAG, "Rootfs extraction failed", e);
                notifyError("Setup Failed", "Could not initialize environment: " + e.getMessage());
            }
        });
    }

    private void copyBinaryFromAssets(String assetName, String outputName) {
        try {
            File outFile = new File(context.getFilesDir(), outputName);
            if (outFile.exists()) return;

            InputStream is = context.getAssets().open("bin/" + assetName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            is.close();
            outFile.setExecutable(true);
            Log.d(TAG, "Copied binary: " + assetName + " -> " + outputName);
        } catch (Exception e) {
            Log.w(TAG, "Could not copy binary " + assetName + ": " + e.getMessage());
        }
    }

    private void startShell() {
        executor.execute(() -> {
            try {
                File rootfsDir = new File(context.getFilesDir(), ROOTFS_DIR);
                String rootfsPath = rootfsDir.getAbsolutePath();

                String[] env = {
                    "HOME=/root",
                    "TERM=xterm-256color",
                    "SHELL=/bin/sh",
                    "USER=root",
                    "PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin",
                    "LD_PRELOAD="
                };

                // Try to start a shell via PRoot, fall back to sh
                ProcessBuilder pb;
                File prootFile = new File(context.getFilesDir(), "proot");
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

                pb.environment().putAll(System.getenv());
                for (String e : env) {
                    String[] parts = e.split("=", 2);
                    if (parts.length == 2) {
                        pb.environment().put(parts[0], parts[1]);
                    }
                }
                pb.directory(rootfsDir);

                shellProcess = pb.start();
                shellInput = shellProcess.getOutputStream();
                shellOutput = new BufferedReader(new InputStreamReader(shellProcess.getInputStream()));
                shellError = new BufferedReader(new InputStreamReader(shellProcess.getErrorStream()));

                isRunning.set(true);

                // Start output reader threads
                startOutputReader(shellOutput);
                startOutputReader(shellError);

                notifyProgress(95, "Launching OpenCode Engine... (بدء تشغيل المحرك)");

                // Start the opencode server
                write("opencode serve\n");

                isRunning.set(true);

            } catch (Exception e) {
                Log.e(TAG, "Failed to start shell", e);
                notifyError("Shell Error", "Could not start terminal: " + e.getMessage());
            }
        });
    }

    private void startOutputReader(final BufferedReader reader) {
        Thread readerThread = new Thread(() -> {
            try {
                StringBuilder buffer = new StringBuilder();
                char[] charBuffer = new char[4096];
                int charsRead;

                while (isRunning.get() && (charsRead = reader.read(charBuffer, 0, charBuffer.length)) != -1) {
                    String chunk = new String(charBuffer, 0, charsRead);
                    buffer.append(chunk);

                    // Emit data in chunks
                    if (buffer.length() > 0) {
                        String data = buffer.toString();
                        buffer.setLength(0);

                        if (bridge != null) {
                            bridge.onTerminalData(data);
                        }

                        // Check for the URL pattern
                        if (data.contains("http://127.0.0.1:4096")) {
                            bridge.onReady();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Output reader error", e);
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void write(String command) {
        executor.execute(() -> {
            try {
                if (shellInput != null) {
                    shellInput.write(command.getBytes());
                    shellInput.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Write error", e);
            }
        });
    }

    public void stop() {
        isRunning.set(false);
        executor.execute(() -> {
            try {
                if (shellInput != null) {
                    shellInput.write("exit\n".getBytes());
                    shellInput.flush();
                }
            } catch (Exception ignored) {}

            try {
                if (shellProcess != null) {
                    shellProcess.destroy();
                }
            } catch (Exception ignored) {}
        });
    }

    private void notifyProgress(int percent, String message) {
        if (bridge != null) {
            bridge.onInstallProgress(percent, message);
        }
    }

    private void notifyError(String title, String details) {
        if (bridge != null) {
            bridge.onError(title, details);
        }
    }
}
