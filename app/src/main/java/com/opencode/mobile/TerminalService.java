package com.opencode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TerminalService — النهج الجديد بدون Alpine/proot
 *
 * المشكلة مع النهج القديم:
 *   - Alpine minirootfs يحتوي symlinks → Android يمنعها في filesDir
 *   - noexec على SD Card يمنع تنفيذ أي binary خارج nativeLibraryDir
 *   - proot نفسه يحتاج تنفيذ → نفس المشكلة
 *
 * الحل الجديد:
 *   1. تحميل Node.js static binary (libnode.so في nativeLibraryDir)
 *   2. تحميل npm كـ zip وفكّه بـ Java순 في filesDir (ملفات .js فقط، لا symlinks)
 *   3. تشغيل opencode مباشرة عبر node
 */
public class TerminalService {

    private static final String TAG = "TerminalService";
    private static final String BOOT_MARKER = ".node_ready";

    // Node.js 20 LTS static binary — aarch64 Android
    // libnode.so = node binary مُعاد تسميته للتثبيت في nativeLibraryDir
    // (يُضاف إلى jniLibs/arm64-v8a في مرحلة البناء)

    // npm تحمل كـ tarball وتُفك بـ Java순 (ملفات JS فقط، لا symlinks)
    private static final String[] NPM_URLS = {
        "https://registry.npmjs.org/npm/-/npm-10.8.2.tgz",
        "https://registry.npmjs.org/npm/-/npm-10.5.0.tgz",
    };

    // opencode-ai
    private static final String[] OPENCODE_URLS = {
        "https://registry.npmjs.org/opencode-ai/-/opencode-ai-0.1.116.tgz",
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

    public void setBridge(WebAppInterface bridge) { this.bridge = bridge; }
    public boolean isFirstBoot() { return firstBoot; }

    // node binary في nativeLibraryDir
    private File getNodeFile() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libnode.so");
    }

    // مجلد العمل الرئيسي
    private File getWorkDir() {
        return context.getFilesDir();
    }

    // -----------------------------------------------------------------------
    public void checkInstallStatus() {
        executor.submit(() -> {
            File bootMarker = new File(getWorkDir(), BOOT_MARKER);
            File nodeFile   = getNodeFile();

            Log.d(TAG, "node: " + nodeFile.getAbsolutePath()
                + " exists=" + nodeFile.exists()
                + " canExec=" + nodeFile.canExecute());

            if (!nodeFile.exists()) {
                notifyError("Node.js not found",
                    "libnode.so missing from:\n" + nodeFile.getAbsolutePath()
                    + "\n\nRun setup_and_push.py to add it to jniLibs.");
                return;
            }

            if (!bootMarker.exists()) {
                firstBoot = true;
                notifyProgress(5, "Setting up environment...");
                doSetup();
            } else {
                firstBoot = false;
                notifyProgress(10, "Starting opencode...");
                doStartOpencode();
            }
        });
    }

    // -----------------------------------------------------------------------
    // الإعداد: تحميل npm + opencode وتثبيتهما
    // -----------------------------------------------------------------------
    private void doSetup() {
        File workDir = getWorkDir();
        File nodeModules = new File(workDir, "node_modules");
        File npmDir  = new File(nodeModules, "npm");
        File opencodeDir = new File(nodeModules, "opencode-ai");

        try {
            // --- تثبيت npm ---
            if (!npmDir.exists() || !new File(npmDir, "bin/npm-cli.js").exists()) {
                notifyProgress(15, "Downloading npm...");
                File npmTgz = new File(workDir, "npm.tgz");
                boolean ok = downloadFileWithFallback(NPM_URLS, npmTgz, 15, 40);
                if (!ok) {
                    notifyError("npm download failed", "Could not download npm");
                    return;
                }
                notifyProgress(42, "Installing npm...");
                extractTgzFlat(npmTgz, npmDir, "package/");
                npmTgz.delete();
                Log.d(TAG, "npm installed: " + npmDir.getAbsolutePath());
            }

            // --- تثبيت opencode-ai ---
            if (!opencodeDir.exists()) {
                notifyProgress(55, "Downloading opencode-ai...");

                // جلب أحدث إصدار من npm registry
                String latestUrl = getLatestOpencodeUrl();
                String[] urls = latestUrl != null
                    ? new String[]{latestUrl, OPENCODE_URLS[0]}
                    : OPENCODE_URLS;

                File tgz = new File(workDir, "opencode.tgz");
                boolean ok = downloadFileWithFallback(urls, tgz, 55, 80);
                if (!ok) {
                    notifyError("opencode download failed", "Could not download opencode-ai");
                    return;
                }
                notifyProgress(82, "Installing opencode-ai...");
                extractTgzFlat(tgz, opencodeDir, "package/");
                tgz.delete();

                // تثبيت dependencies الأساسية لـ opencode
                installOpencodeDepsFallback(opencodeDir, nodeModules);
                Log.d(TAG, "opencode installed: " + opencodeDir.getAbsolutePath());
            }

            new File(workDir, BOOT_MARKER).createNewFile();
            firstBoot = false;

            notifyProgress(92, "Setup complete! Starting...");
            doStartOpencode();

        } catch (Exception e) {
            Log.e(TAG, "Setup failed", e);
            notifyError("Setup Failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // الحصول على رابط أحدث إصدار من opencode-ai
    // -----------------------------------------------------------------------
    private String getLatestOpencodeUrl() {
        try {
            URL url = new URL("https://registry.npmjs.org/opencode-ai/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "DevPocket/1.0");
            conn.connect();
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                conn.disconnect();
                String json = sb.toString();
                // استخراج tarball URL بدون JSON parser خارجي
                int idx = json.indexOf("\"tarball\":\"");
                if (idx >= 0) {
                    int start = idx + 11;
                    int end = json.indexOf("\"", start);
                    if (end > start) return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not fetch latest opencode version: " + e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // فك ضغط .tgz بـ Java순 — ملفات JS فقط، لا symlinks
    // -----------------------------------------------------------------------
    private void extractTgzFlat(File tgzFile, File destDir, String stripPrefix)
            throws Exception {
        destDir.mkdirs();
        Log.d(TAG, "Extracting " + tgzFile.getName() + " -> " + destDir.getAbsolutePath());

        try (FileInputStream fis  = new FileInputStream(tgzFile);
             GZIPInputStream gzip = new GZIPInputStream(fis, 65536)) {

            byte[] header = new byte[512];
            int fileCount = 0;
            String pendingLongName = null;

            while (true) {
                if (readFully(gzip, header, 512) < 512) break;
                if (isZeroBlock(header)) { readFully(gzip, header, 512); break; }

                String name     = readString(header, 0,   100);
                long   size     = readOctal(header,  124, 12);
                int    typeFlag = header[156] & 0xFF;
                String linkName = readString(header, 157, 100);
                String prefix   = readString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;

                // GNU LongLink
                if (typeFlag == 'L') {
                    byte[] lb = new byte[(int) size];
                    readFully(gzip, lb, lb.length);
                    skipPadding(gzip, size);
                    pendingLongName = new String(lb, "UTF-8").replace("\0","").trim();
                    continue;
                }
                if (pendingLongName != null) { name = pendingLongName; pendingLongName = null; }

                name = name.replace("\0","").trim();
                if (name.isEmpty()) { skipEntry(gzip, size); continue; }

                // إزالة prefix (مثل "package/")
                if (!stripPrefix.isEmpty() && name.startsWith(stripPrefix)) {
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty() || name.startsWith("..")) { skipEntry(gzip, size); continue; }

                char type = (typeFlag == 0 || typeFlag == '0') ? '0' : (char) typeFlag;
                File outFile = new File(destDir, name);

                // أمان
                if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    skipEntry(gzip, size); continue;
                }

                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs();
                    skipEntry(gzip, size);
                } else if (type == '2') {
                    // symlink — نتجاهله لأن npm/opencode لا يحتاجانه للعمل
                    skipEntry(gzip, size);
                } else {
                    // ملف عادي
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        long rem = size;
                        byte[] buf = new byte[65536];
                        while (rem > 0) {
                            int n = gzip.read(buf, 0, (int) Math.min(buf.length, rem));
                            if (n < 0) break;
                            fos.write(buf, 0, n);
                            rem -= n;
                        }
                    }
                    skipPadding(gzip, size);
                    fileCount++;
                }
            }
            Log.d(TAG, "Extracted " + fileCount + " files from " + tgzFile.getName());
        }
    }

    // -----------------------------------------------------------------------
    // تثبيت dependencies بسيطة لـ opencode (إن احتاج)
    // -----------------------------------------------------------------------
    private void installOpencodeDepsFallback(File opencodeDir, File nodeModules)
            throws Exception {
        // opencode-ai في الغالب self-contained — نتحقق فقط
        File pkgJson = new File(opencodeDir, "package.json");
        if (!pkgJson.exists()) return;

        // قراءة package.json للتحقق من dependencies
        BufferedReader reader = new BufferedReader(
            new java.io.FileReader(pkgJson));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        String json = sb.toString();
        // إذا لم يكن self-contained سنضيف المنطق هنا لاحقاً
        Log.d(TAG, "opencode package.json read OK, size=" + json.length());
    }

    // -----------------------------------------------------------------------
    // تشغيل opencode
    // -----------------------------------------------------------------------
    private void doStartOpencode() {
        try {
            File workDir    = getWorkDir();
            File nodeFile   = getNodeFile();
            File nodeModules = new File(workDir, "node_modules");
            File opencodeMain = findOpencodeMain(nodeModules);

            if (opencodeMain == null) {
                notifyError("opencode not found",
                    "Could not locate opencode entry point in:\n"
                    + nodeModules.getAbsolutePath());
                return;
            }

            Log.d(TAG, "Starting: " + nodeFile.getAbsolutePath()
                + " " + opencodeMain.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(
                nodeFile.getAbsolutePath(),
                opencodeMain.getAbsolutePath(),
                "serve"
            );
            pb.environment().put("HOME",        workDir.getAbsolutePath());
            pb.environment().put("PATH",        workDir.getAbsolutePath() + ":/system/bin");
            pb.environment().put("NODE_PATH",   nodeModules.getAbsolutePath());
            pb.environment().put("TMPDIR",      context.getCacheDir().getAbsolutePath());
            pb.environment().put("LD_PRELOAD",  "");
            pb.directory(workDir);

            shellProcess = pb.start();
            shellInput   = shellProcess.getOutputStream();
            isRunning.set(true);

            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getInputStream())));
            startOutputReader(new BufferedReader(
                new InputStreamReader(shellProcess.getErrorStream())));

            notifyProgress(96, "Launching OpenCode Engine...");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start opencode", e);
            notifyError("Start Failed", e.getMessage());
        }
    }

    private File findOpencodeMain(File nodeModules) {
        // المسارات المحتملة لـ opencode entry point
        String[] candidates = {
            "opencode-ai/dist/index.js",
            "opencode-ai/index.js",
            "opencode-ai/src/index.js",
            "opencode-ai/bin/opencode.js",
            ".bin/opencode",
        };
        for (String c : candidates) {
            File f = new File(nodeModules, c);
            if (f.exists()) {
                Log.d(TAG, "Found opencode at: " + f.getAbsolutePath());
                return f;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // تحميل مع fallback
    // -----------------------------------------------------------------------
    private boolean downloadFileWithFallback(String[] urls, File outFile,
                                              int startPct, int endPct) {
        for (int i = 0; i < urls.length; i++) {
            try {
                notifyProgress(startPct, "Source " + (i+1) + "/" + urls.length + "...");
                Log.d(TAG, "Downloading: " + urls[i]);
                downloadFile(urls[i], outFile, startPct, endPct);
                if (outFile.exists() && outFile.length() > 1000) {
                    Log.d(TAG, "OK: " + urls[i] + " [" + outFile.length() + "b]");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed [" + urls[i] + "]: " + e.getMessage());
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
        if (code != 200) { conn.disconnect(); throw new Exception("HTTP " + code); }
        long total = conn.getContentLengthLong(), done = 0;
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n); done += n;
                if (total > 0)
                    notifyProgress(Math.min(startPct+(int)(done*(endPct-startPct)/total), endPct), null);
            }
        }
        conn.disconnect();
    }

    // ── tar helpers ─────────────────────────────────────────────────────────
    private int readFully(InputStream in, byte[] buf, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private boolean isZeroBlock(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    private String readString(byte[] buf, int off, int len) throws Exception {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, "UTF-8").trim();
    }

    private long readOctal(byte[] buf, int off, int len) {
        if ((buf[off] & 0x80) != 0) {
            long v = 0;
            for (int i = 1; i < len; i++) v = (v << 8) | (buf[off+i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off+len; i++) {
            if (buf[i] == 0 || buf[i] == ' ') break;
            if (buf[i] >= '0' && buf[i] <= '7') v = v*8 + (buf[i]-'0');
        }
        return v;
    }

    private void skipPadding(InputStream in, long size) throws Exception {
        long rem = (512 - (size % 512)) % 512;
        if (rem > 0) { byte[] p = new byte[(int)rem]; readFully(in, p, (int)rem); }
    }

    private void skipEntry(InputStream in, long size) throws Exception {
        long total = size + ((512-(size%512))%512);
        byte[] buf = new byte[65536]; long skipped = 0;
        while (skipped < total) {
            int n = in.read(buf, 0, (int)Math.min(buf.length, total-skipped));
            if (n < 0) break; skipped += n;
        }
    }

    // -----------------------------------------------------------------------
    private void startOutputReader(final BufferedReader reader) {
        Thread t = new Thread(() -> {
            try {
                char[] buf = new char[4096]; int n;
                while (isRunning.get() && (n = reader.read(buf, 0, buf.length)) != -1) {
                    String chunk = new String(buf, 0, n);
                    if (bridge != null) {
                        bridge.onTerminalData(chunk);
                        if (chunk.contains("http://127.0.0.1:4096")) bridge.onReady();
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Reader: " + e.getMessage()); }
        });
        t.setDaemon(true); t.start();
    }

    private void writeDirectly(String data) {
        try {
            if (shellInput != null && isRunning.get()) {
                shellInput.write(data.getBytes("UTF-8"));
                shellInput.flush();
            }
        } catch (Exception e) { Log.e(TAG, "write: " + e.getMessage()); }
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

    private void notifyProgress(int pct, String msg) {
        if (bridge != null) bridge.onInstallProgress(pct, msg);
    }

    private void notifyError(String title, String details) {
        if (bridge != null) bridge.onError(title, details);
    }
}
