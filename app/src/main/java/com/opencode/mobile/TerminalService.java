package com.opencode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;
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
    private static final String ROOTFS_DIR  = "alpine";
    private static final String BOOT_MARKER = ".boot_complete";

    // ✅ Alpine Linux minirootfs — aarch64
    private static final String[] ALPINE_ROOTFS_URLS = {
        "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.7-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
        "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.9-aarch64.tar.gz"
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
    // الحصول على مسار proot وbusybox من nativeLibraryDir
    // هذا هو الحل الحقيقي لـ "Permission denied" على Android 10+
    // -----------------------------------------------------------------------
    private File getProotFile() {
        // nativeLibraryDir: مجلد يسمح Android بتنفيذ الملفات منه
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, "libproot.so");
    }

    private File getBusyboxFile() {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, "libbusybox.so");
    }

    // -----------------------------------------------------------------------
    public void checkInstallStatus() {
        executor.submit(() -> {
            File rootfsDir  = new File(context.getFilesDir(), ROOTFS_DIR);
            File bootMarker = new File(rootfsDir, BOOT_MARKER);
            File prootFile  = getProotFile();
            File busyboxFile = getBusyboxFile();

            Log.d(TAG, "proot path: " + prootFile.getAbsolutePath()
                + " exists=" + prootFile.exists()
                + " size=" + prootFile.length());
            Log.d(TAG, "busybox path: " + busyboxFile.getAbsolutePath()
                + " exists=" + busyboxFile.exists()
                + " size=" + busyboxFile.length());

            boolean rootfsReady = rootfsDir.exists()
                && rootfsDir.isDirectory()
                && bootMarker.exists()
                && new File(rootfsDir, "bin/sh").exists();

            if (!rootfsReady) {
                firstBoot = true;
                notifyProgress(3, "Preparing environment... (تهيئة البيئة)");
                doSetupRootfs();
            } else {
                firstBoot = false;
                notifyProgress(10, "Environment ready. Starting...");
                doStartShell();
            }
        });
    }

    // -----------------------------------------------------------------------
    // تحميل Alpine rootfs وفكّه
    // proot وbusybox مضمّنان في APK ← لا حاجة لتحميلهما
    // -----------------------------------------------------------------------
    private void doSetupRootfs() {
        File prootFile   = getProotFile();
        File busyboxFile = getBusyboxFile();

        // التحقق من وجود الـ binaries في nativeLibraryDir
        if (!prootFile.exists()) {
            notifyError("proot not found",
                "libproot.so missing from nativeLibraryDir.\n"
                + "Path: " + prootFile.getAbsolutePath()
                + "\nMake sure jniLibs/arm64-v8a/libproot.so exists in the project.");
            return;
        }
        if (!busyboxFile.exists()) {
            notifyError("busybox not found",
                "libbusybox.so missing from nativeLibraryDir.\n"
                + "Path: " + busyboxFile.getAbsolutePath()
                + "\nMake sure jniLibs/arm64-v8a/libbusybox.so exists in the project.");
            return;
        }

        Log.d(TAG, "proot OK in nativeLibraryDir: " + prootFile.length() + "b");
        Log.d(TAG, "busybox OK in nativeLibraryDir: " + busyboxFile.length() + "b");

        File filesDir  = context.getFilesDir();
        File rootfsDir = new File(filesDir, ROOTFS_DIR);
        File binSh     = new File(rootfsDir, "bin/sh");

        if (binSh.exists()) {
            Log.d(TAG, "Alpine already extracted");
            doInstallPackages();
            return;
        }

        // تحميل Alpine minirootfs
        File tarFile = new File(filesDir, "alpine-rootfs.tar.gz");
        try {
            notifyProgress(15, "Downloading Alpine Linux... (تحميل Alpine)");
            boolean ok = downloadFileWithFallback(ALPINE_ROOTFS_URLS, tarFile, 15, 45);

            if (!ok || !tarFile.exists() || tarFile.length() < 500_000) {
                notifyError("Alpine download failed",
                    "Could not download Alpine Linux rootfs.\nCheck internet connection.");
                return;
            }
            Log.d(TAG, "Alpine tar: " + tarFile.length() + " bytes");

            if (!rootfsDir.exists()) rootfsDir.mkdirs();

            notifyProgress(48, "Extracting Alpine... (فك الضغط)");
            extractTarGz(tarFile, rootfsDir, busyboxFile);
            tarFile.delete();

            if (!binSh.exists()) {
                notifyError("Extraction failed",
                    "/bin/sh not found after extraction.\nAlpine rootfs may be corrupted.");
                return;
            }
            Log.d(TAG, "Alpine extracted OK");

        } catch (Exception e) {
            Log.e(TAG, "Rootfs setup error", e);
            if (tarFile.exists()) tarFile.delete();
            notifyError("Alpine Setup Failed", e.getMessage());
            return;
        }

        doInstallPackages();
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // فك ضغط tar.gz بـ Java순 — بدون أي binary خارجي
    // الحل النهائي لـ noexec على SD Card وW^X على Android 10+
    // -----------------------------------------------------------------------
    private void extractTarGz(File tarFile, File destDir, File busyboxFile) throws Exception {
        Log.d(TAG, "Extracting tar.gz with pure Java: " + tarFile.getAbsolutePath());
        if (bridge != null) bridge.onTerminalData("Extracting Alpine Linux...\r\n");

        try (FileInputStream     fis  = new FileInputStream(tarFile);
             GZIPInputStream     gzip = new GZIPInputStream(fis, 65536);) {
            extractTar(gzip, destDir);
        }
        Log.d(TAG, "Extraction complete");
    }

    /**
     * قارئ tar نقي بـ Java — يدعم:
     * POSIX ustar, GNU tar, PAX headers
     * regular files, directories, symlinks, hard links
     */
    private void extractTar(java.io.InputStream tarStream, File destDir) throws Exception {
        byte[] header = new byte[512];
        int fileCount = 0;
        String pendingLongName = null; // GNU LongLink
        java.util.Map<String, String> pendingSymlinks = new java.util.LinkedHashMap<>();

        while (true) {
            // قراءة header block
            int bytesRead = readFully(tarStream, header, 512);
            if (bytesRead < 512) break;

            // EOF: block فارغ = نهاية الأرشيف
            if (isZeroBlock(header)) {
                readFully(tarStream, header, 512); // الـ block الثاني
                break;
            }

            // استخراج الحقول
            String name     = readString(header, 0,   100);
            long   size     = readOctal(header,  124, 12);
            int    typeFlag = header[156] & 0xFF;
            String linkName = readString(header, 157, 100);

            // prefix (ustar)
            String prefix = readString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;

            // GNU LongLink: الاسم الطويل في block منفصل
            if (typeFlag == 'L') {
                byte[] longNameBytes = new byte[(int) size];
                readFully(tarStream, longNameBytes, longNameBytes.length);
                skipPadding(tarStream, size);
                pendingLongName = new String(longNameBytes, "UTF-8").trim().replace("\0", "");
                continue;
            }
            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }

            // تنظيف الاسم
            name = name.replace("\0", "").trim();
            if (name.isEmpty() || name.equals("./")) {
                skipEntry(tarStream, size);
                continue;
            }
            if (name.startsWith("./")) name = name.substring(2);

            File outFile = new File(destDir, name);

            // منع Path Traversal
            if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                Log.w(TAG, "Skipping unsafe path: " + name);
                skipEntry(tarStream, size);
                continue;
            }

            char type = (typeFlag == 0) ? '0' : (char) typeFlag;

            if (type == '5' || name.endsWith("/")) {
                // Directory
                outFile.mkdirs();
                skipEntry(tarStream, size);

            } else if (type == '2') {
                // Symlink
                if (outFile.exists()) outFile.delete();
                outFile.getParentFile().mkdirs();
                boolean symlinkOk = false;
                try {
                    java.nio.file.Files.createSymbolicLink(
                        outFile.toPath(),
                        java.nio.file.Paths.get(linkName)
                    );
                    symlinkOk = true;
                    Log.d(TAG, "Symlink: " + name + " -> " + linkName);
                } catch (Exception e) {
                    Log.w(TAG, "Symlink failed, will resolve later: " + name + " -> " + linkName);
                }
                // إذا فشل symlink: حفظ الزوج للحل لاحقاً
                if (!symlinkOk) {
                    pendingSymlinks.put(name, linkName);
                }
                skipEntry(tarStream, size);

            } else if (type == '1') {
                // Hard link — نسخ الملف المصدر
                outFile.getParentFile().mkdirs();
                File linkSrc = new File(destDir, linkName.startsWith("./") ? linkName.substring(2) : linkName);
                if (linkSrc.exists() && !linkSrc.isDirectory()) {
                    copyFileSimple(linkSrc, outFile);
                    Log.d(TAG, "HardLink copied: " + name + " <- " + linkName);
                } else {
                    pendingSymlinks.put(name, linkName);
                }
                skipEntry(tarStream, size);

            } else {
                // Regular file (type '0', '\0', or unknown)
                outFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    long remaining = size;
                    byte[] buf = new byte[65536];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = tarStream.read(buf, 0, toRead);
                        if (n < 0) throw new Exception("Unexpected EOF in entry: " + name);
                        fos.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                skipPadding(tarStream, size);
                fileCount++;
                if (fileCount % 200 == 0 && bridge != null) {
                    bridge.onTerminalData("Extracted " + fileCount + " files...\r\n");
                }
            }
        }
        Log.d(TAG, "tar: extracted " + fileCount + " files");

        // حل الـ symlinks المعلقة — بعد اكتمال الاستخراج
        int resolvedCount = 0;
        for (java.util.Map.Entry<String, String> entry : pendingSymlinks.entrySet()) {
            String symName = entry.getKey();
            String symTarget = entry.getValue();
            File symFile = new File(destDir, symName);
            symFile.getParentFile().mkdirs();
            if (symFile.exists()) symFile.delete();

            // محاولة symlink مرة أخرى
            try {
                java.nio.file.Files.createSymbolicLink(
                    symFile.toPath(),
                    java.nio.file.Paths.get(symTarget)
                );
                resolvedCount++;
                continue;
            } catch (Exception ignored) {}

            // fallback: نسخ الملف الحقيقي مباشرة
            String cleanTarget = symTarget.startsWith("/") ? symTarget.substring(1) : symTarget;
            if (cleanTarget.startsWith("./")) cleanTarget = cleanTarget.substring(2);
            File srcFile = new File(destDir, cleanTarget);
            if (srcFile.exists() && !srcFile.isDirectory()) {
                try {
                    copyFileSimple(srcFile, symFile);
                    resolvedCount++;
                    Log.d(TAG, "Symlink fallback copy: " + symName + " <- " + cleanTarget);
                } catch (Exception e2) {
                    Log.w(TAG, "Could not resolve symlink: " + symName + " -> " + symTarget);
                }
            } else {
                Log.w(TAG, "Symlink target missing: " + symName + " -> " + symTarget + " (src=" + srcFile.getAbsolutePath() + ")");
            }
        }

        Log.d(TAG, "tar: extracted=" + fileCount + " symlinks_resolved=" + resolvedCount + "/" + pendingSymlinks.size());
        if (bridge != null) bridge.onTerminalData("Extracted " + fileCount + " files, " + resolvedCount + " symlinks OK\r\n");
    }

    private void copyFileSimple(File src, File dst) throws Exception {
        if (dst.exists()) dst.delete();
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    // ── tar helpers ─────────────────────────────────────────────────────────

    private int readFully(java.io.InputStream in, byte[] buf, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private boolean isZeroBlock(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private String readString(byte[] buf, int offset, int maxLen) throws Exception {
        int end = offset;
        while (end < offset + maxLen && buf[end] != 0) end++;
        return new String(buf, offset, end - offset, "UTF-8").trim();
    }

    private long readOctal(byte[] buf, int offset, int len) {
        // دعم base-256 encoding (GNU tar للملفات الكبيرة)
        if ((buf[offset] & 0x80) != 0) {
            long val = 0;
            for (int i = 1; i < len; i++) val = (val << 8) | (buf[offset + i] & 0xFF);
            return val;
        }
        long val = 0;
        for (int i = offset; i < offset + len; i++) {
            if (buf[i] == 0 || buf[i] == ' ') break;
            if (buf[i] >= '0' && buf[i] <= '7') val = val * 8 + (buf[i] - '0');
        }
        return val;
    }

    private void skipPadding(java.io.InputStream in, long size) throws Exception {
        long rem = (512 - (size % 512)) % 512;
        if (rem > 0) {
            byte[] pad = new byte[(int) rem];
            readFully(in, pad, (int) rem);
        }
    }

    private void skipEntry(java.io.InputStream in, long size) throws Exception {
        long total = size + ((512 - (size % 512)) % 512);
        long skipped = 0;
        byte[] buf = new byte[65536];
        while (skipped < total) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, total - skipped));
            if (n < 0) break;
            skipped += n;
        }
    }

    // -----------------------------------------------------------------------
    // تثبيت الحزم داخل Alpine
    // -----------------------------------------------------------------------
    private void doInstallPackages() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            File prootFile = getProotFile();
            File prootTmp  = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();

            String rootfsPath  = rootfsDir.getAbsolutePath();
            String prootPath   = prootFile.getAbsolutePath();
            String tmpPath     = prootTmp.getAbsolutePath();

            // إعداد DNS
            setupResolvConf(rootfsDir);

            notifyProgress(52, "Updating packages... (تحديث الحزم)");
            boolean ok = runInProot(prootPath, rootfsPath, tmpPath, "apk update --no-cache");
            if (!ok) {
                setupResolvConf(rootfsDir); // retry بعد إعادة DNS
                ok = runInProot(prootPath, rootfsPath, tmpPath, "apk update --no-cache");
            }
            if (!ok) {
                notifyError("apk update failed", "Cannot reach Alpine package servers.");
                return;
            }

            notifyProgress(65, "Installing Node.js... (تثبيت Node.js)");
            ok = runInProot(prootPath, rootfsPath, tmpPath, "apk add --no-cache nodejs npm git");
            if (!ok) {
                notifyError("apk add failed", "Could not install nodejs/npm");
                return;
            }

            notifyProgress(82, "Installing opencode-ai...");
            ok = runInProot(prootPath, rootfsPath, tmpPath, "npm install -g opencode-ai@latest");
            if (!ok) {
                notifyError("npm install failed", "Could not install opencode-ai");
                return;
            }

            new File(rootfsDir, BOOT_MARKER).createNewFile();
            firstBoot = false;

            notifyProgress(92, "Setup complete! (اكتمل التثبيت)");
            doStartShell();

        } catch (Exception e) {
            Log.e(TAG, "Install packages failed", e);
            notifyError("Setup Failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private void setupResolvConf(File rootfsDir) {
        try {
            File etc = new File(rootfsDir, "etc");
            if (!etc.exists()) etc.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(etc, "resolv.conf"));
            fos.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "resolv.conf write failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private boolean downloadFileWithFallback(String[] urls, File outFile,
                                              int startPct, int endPct) {
        for (int i = 0; i < urls.length; i++) {
            try {
                notifyProgress(startPct, "Source " + (i+1) + "/" + urls.length + "...");
                Log.d(TAG, "Trying: " + urls[i]);
                downloadFile(urls[i], outFile, startPct, endPct);
                if (outFile.exists() && outFile.length() > 10_000) {
                    Log.d(TAG, "✅ OK: " + urls[i]);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "❌ " + urls[i] + ": " + e.getMessage());
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
            throw new Exception("HTTP " + code);
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
            if (total > 0)
                notifyProgress(Math.min(startPct + (int)(done*(endPct-startPct)/total), endPct), null);
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
            Log.d(TAG, "[proot] '" + cmd + "' exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "runInProot error: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    private void doStartShell() {
        try {
            File filesDir  = context.getFilesDir();
            File rootfsDir = new File(filesDir, ROOTFS_DIR);
            File prootFile = getProotFile();
            File prootTmp  = new File(filesDir, "proot-tmp");
            if (!prootTmp.exists()) prootTmp.mkdirs();

            Log.d(TAG, "Starting shell — proot: " + prootFile.getAbsolutePath()
                + " canExecute=" + prootFile.canExecute());

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

            notifyProgress(96, "Launching OpenCode Engine...");
            writeDirectly("opencode serve\n");

        } catch (Exception e) {
            Log.e(TAG, "doStartShell error", e);
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
                Log.e(TAG, "Reader error: " + e.getMessage());
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
            try { if (shellProcess != null) shellProcess.destroy(); }
            catch (Exception ignored) {}
        });
    }

    private void notifyProgress(int percent, String message) {
        if (bridge != null) bridge.onInstallProgress(percent, message);
    }

    private void notifyError(String title, String details) {
        if (bridge != null) bridge.onError(title, details);
    }
}
