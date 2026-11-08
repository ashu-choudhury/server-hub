package com.termux.app.serverhub;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BackupManager {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface BackupCallback {
        void onProgress(String status);
        void onSuccess(File backupFile, String details);
        void onError(String error);
    }

    public interface RestoreCallback {
        void onProgress(String status);
        void onSuccess();
        void onError(String error);
    }

    public static void exportBackup(@NonNull Context context, @NonNull BackupCallback callback) {
        new Thread(() -> {
            try {
                postProgress(callback, "Preparing sandbox files for backup...");

                File appDir = new File("/data/data/com.termux");
                File filesDir = new File(appDir, "files");
                if (!filesDir.exists()) {
                    postError(callback, "Sandbox files directory does not exist.");
                    return;
                }

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String filename = "backtalk_backup_" + timestamp + ".tar.gz";

                // First archive directly to app cache to ensure write permissions succeed
                File cacheDir = context.getCacheDir();
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File tempArchive = new File(cacheDir, filename);
                if (tempArchive.exists()) tempArchive.delete();

                postProgress(callback, "Compressing Linux environment & configurations...");

                File termuxTar = new File(filesDir, "usr/bin/tar");
                String tarBin = (termuxTar.exists() && termuxTar.canExecute()) ?
                        termuxTar.getAbsolutePath() : "/system/bin/tar";

                // Exclude temp directory and unix domain sockets
                String[] cmd = new String[] {
                    tarBin,
                    "-czf",
                    tempArchive.getAbsolutePath(),
                    "--exclude=files/usr/tmp/*",
                    "-C",
                    "/data/data/com.termux",
                    "files",
                    "shared_prefs"
                };

                Process process = Runtime.getRuntime().exec(cmd);
                int exitCode = process.waitFor();

                if (!tempArchive.exists() || tempArchive.length() < 1024) {
                    postError(callback, "Backup failed during compression (code " + exitCode + ").");
                    return;
                }

                // Copy to public Downloads directory
                postProgress(callback, "Saving archive to Downloads...");
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File targetFile = new File(downloadDir, filename);

                boolean copiedToDownloads = false;
                try {
                    copyFile(tempArchive, targetFile);
                    copiedToDownloads = targetFile.exists() && targetFile.length() > 0;
                } catch (Exception ignored) {}

                File finalFile = copiedToDownloads ? targetFile : tempArchive;
                long sizeMb = finalFile.length() / (1024 * 1024);
                String details = String.format(Locale.US, "Archive: %s (%d MB)\nSaved to: %s",
                        filename, sizeMb, finalFile.getAbsolutePath());

                MAIN_HANDLER.post(() -> callback.onSuccess(finalFile, details));

            } catch (Exception e) {
                postError(callback, "Error during backup export: " + e.getMessage());
            }
        }).start();
    }

    public static void restoreBackup(@NonNull Context context, @NonNull Uri backupUri, @NonNull RestoreCallback callback) {
        new Thread(() -> {
            File tempArchive = null;
            try {
                postProgress(callback, "Reading backup archive from file...");

                File cacheDir = context.getCacheDir();
                if (!cacheDir.exists()) cacheDir.mkdirs();
                tempArchive = new File(cacheDir, "restore_temp_" + System.currentTimeMillis() + ".tar.gz");

                try (InputStream is = context.getContentResolver().openInputStream(backupUri);
                     OutputStream os = new FileOutputStream(tempArchive)) {
                    if (is == null) {
                        postError(callback, "Failed to open input stream for backup file.");
                        return;
                    }
                    byte[] buffer = new byte[65536];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                    os.flush();
                }

                if (!tempArchive.exists() || tempArchive.length() < 1024) {
                    postError(callback, "Invalid backup archive: file is empty or unreadable.");
                    return;
                }

                postProgress(callback, "Stopping running services before restore...");
                try {
                    WireGuardService.stopService(context);
                    Runtime.getRuntime().exec("pkill -9 sshd").waitFor();
                    Runtime.getRuntime().exec("pkill -9 libwireguard.so").waitFor();
                } catch (Exception ignored) {}

                postProgress(callback, "Extracting Linux sandbox, configs & keys...");

                // Use system tar for extraction
                String[] extractCmd = new String[] {
                    "/system/bin/tar",
                    "-xzf",
                    tempArchive.getAbsolutePath(),
                    "-C",
                    "/data/data/com.termux"
                };

                Process process = Runtime.getRuntime().exec(extractCmd);
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    // Try fallback with usr/bin/tar if system tar failed
                    File termuxTar = new File("/data/data/com.termux/files/usr/bin/tar");
                    if (termuxTar.exists() && termuxTar.canExecute()) {
                        process = Runtime.getRuntime().exec(new String[] {
                            termuxTar.getAbsolutePath(),
                            "-xzf",
                            tempArchive.getAbsolutePath(),
                            "-C",
                            "/data/data/com.termux"
                        });
                        exitCode = process.waitFor();
                    }
                }

                postProgress(callback, "Restoring executable permissions...");
                try {
                    Runtime.getRuntime().exec(new String[] {
                        "/system/bin/chmod", "-R", "755",
                        "/data/data/com.termux/files/usr/bin",
                        "/data/data/com.termux/files/usr/lib",
                        "/data/data/com.termux/files/usr/libexec"
                    }).waitFor();

                    Runtime.getRuntime().exec(new String[] {
                        "/system/bin/chmod", "700",
                        "/data/data/com.termux/files/home"
                    }).waitFor();

                    File sshDir = new File("/data/data/com.termux/files/home/.ssh");
                    if (sshDir.exists()) {
                        Runtime.getRuntime().exec(new String[] {
                            "/system/bin/chmod", "-R", "700",
                            sshDir.getAbsolutePath()
                        }).waitFor();
                    }

                    File etcSsh = new File("/data/data/com.termux/files/usr/etc/ssh");
                    if (etcSsh.exists()) {
                        Runtime.getRuntime().exec(new String[] {
                            "/system/bin/chmod", "755",
                            etcSsh.getAbsolutePath()
                        }).waitFor();
                    }
                } catch (Exception ignored) {}

                // Verify restored state
                File bash = new File("/data/data/com.termux/files/usr/bin/bash");
                File sshd = new File("/data/data/com.termux/files/usr/bin/sshd");

                if (!bash.exists() || !sshd.exists()) {
                    postError(callback, "Restore completed but essential binaries (bash/sshd) were not found.");
                    return;
                }

                postProgress(callback, "Restore completed successfully!");
                MAIN_HANDLER.post(callback::onSuccess);

            } catch (Exception e) {
                postError(callback, "Restore failed: " + e.getMessage());
            } finally {
                if (tempArchive != null && tempArchive.exists()) {
                    tempArchive.delete();
                }
            }
        }).start();
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    private static void postProgress(BackupCallback callback, String status) {
        MAIN_HANDLER.post(() -> callback.onProgress(status));
    }

    private static void postProgress(RestoreCallback callback, String status) {
        MAIN_HANDLER.post(() -> callback.onProgress(status));
    }

    private static void postError(BackupCallback callback, String error) {
        MAIN_HANDLER.post(() -> callback.onError(error));
    }

    private static void postError(RestoreCallback callback, String error) {
        MAIN_HANDLER.post(() -> callback.onError(error));
    }
}
