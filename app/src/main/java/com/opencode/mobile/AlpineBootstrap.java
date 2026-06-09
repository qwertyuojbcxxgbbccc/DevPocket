package com.opencode.mobile;

import android.content.Context;
import android.util.Log;

import java.io.File;

public class AlpineBootstrap {

    private static final String TAG = "AlpineBootstrap";
    private static final String ROOTFS_DIR_NAME = "usr";
    private static final String BOOT_MARKER = ".boot_complete";

    private final Context context;
    private final File rootfsDir;

    public AlpineBootstrap(Context context) {
        this.context = context;
        this.rootfsDir = new File(context.getFilesDir(), ROOTFS_DIR_NAME);
    }

    public boolean isBootCompleted() {
        File bootMarker = new File(rootfsDir, BOOT_MARKER);
        return rootfsDir.exists() && rootfsDir.isDirectory() && bootMarker.exists();
    }

    public boolean isFirstBoot() {
        return !isBootCompleted();
    }

    public File getRootfsDir() {
        return rootfsDir;
    }

    public File getBusyboxPath() {
        return new File(context.getFilesDir(), "busybox");
    }

    public File getProotPath() {
        return new File(context.getFilesDir(), "proot");
    }

    public boolean hasRequiredBinaries() {
        return getBusyboxPath().exists() && getProotPath().exists();
    }

    public long getRootfsSize() {
        return getDirSize(rootfsDir);
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                size += getDirSize(file);
            } else {
                size += file.length();
            }
        }
        return size;
    }
}
