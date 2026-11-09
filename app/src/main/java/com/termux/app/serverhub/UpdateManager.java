package com.termux.app.serverhub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.termux.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/ashu-choudhury/server-hub/releases/latest";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static class ReleaseInfo {
        public final String tagName;
        public final String title;
        public final String notes;
        public final String apkUrl;
        public final String apkName;
        public final long sizeBytes;

        public ReleaseInfo(String tagName, String title, String notes, String apkUrl, String apkName, long sizeBytes) {
            this.tagName = tagName;
            this.title = title;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.sizeBytes = sizeBytes;
        }

        public double getSizeMb() {
            return Math.round((sizeBytes / (1024.0 * 1024.0)) * 10.0) / 10.0;
        }
    }

    public interface CheckCallback {
        void onUpdateAvailable(@NonNull ReleaseInfo releaseInfo);
        void onAlreadyLatest(@NonNull String currentVersion);
        void onError(@NonNull String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onSuccess(@NonNull File apkFile);
        void onError(@NonNull String error);
    }

    public static void checkUpdate(@NonNull Context context, @NonNull CheckCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "ServerHub-App");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    MAIN_HANDLER.post(() -> callback.onError("GitHub API returned HTTP " + code));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject releaseJson = new JSONObject(sb.toString());
                String tagName = releaseJson.optString("tag_name", "");
                String title = releaseJson.optString("name", tagName);
                String notes = releaseJson.optString("body", "");
                JSONArray assets = releaseJson.optJSONArray("assets");

                if (tagName.isEmpty()) {
                    MAIN_HANDLER.post(() -> callback.onError("No valid release tag found on GitHub."));
                    return;
                }

                String currentVersion = BuildConfig.VERSION_NAME;
                boolean isNewer = isNewerVersion(tagName, currentVersion);

                if (!isNewer) {
                    MAIN_HANDLER.post(() -> callback.onAlreadyLatest(currentVersion));
                    return;
                }

                if (assets == null || assets.length() == 0) {
                    MAIN_HANDLER.post(() -> callback.onError("Update " + tagName + " found, but no APK assets are attached."));
                    return;
                }

                JSONObject bestAsset = selectBestAsset(assets);
                if (bestAsset == null) {
                    MAIN_HANDLER.post(() -> callback.onError("No compatible APK asset found in release " + tagName));
                    return;
                }

                String apkName = bestAsset.optString("name", "server-hub-update.apk");
                String apkUrl = bestAsset.optString("browser_download_url", "");
                long size = bestAsset.optLong("size", 0);

                ReleaseInfo info = new ReleaseInfo(tagName, title, notes, apkUrl, apkName, size);
                MAIN_HANDLER.post(() -> callback.onUpdateAvailable(info));

            } catch (Exception e) {
                MAIN_HANDLER.post(() -> callback.onError("Check failed: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static boolean isNewerVersion(@NonNull String latestTag, @NonNull String currentVersion) {
        String cleanLatest = latestTag.replaceAll("^[vV]", "").replaceAll("[+-].*", "").trim();
        String cleanCurrent = currentVersion.replaceAll("^[vV]", "").replaceAll("[+-].*", "").trim();

        String[] latestParts = cleanLatest.split("\\.");
        String[] currentParts = cleanCurrent.split("\\.");

        int len = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < len; i++) {
            int lat = (i < latestParts.length) ? parseSafeInt(latestParts[i]) : 0;
            int cur = (i < currentParts.length) ? parseSafeInt(currentParts[i]) : 0;
            if (lat > cur) return true;
            if (lat < cur) return false;
        }
        return false;
    }

    private static int parseSafeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    @Nullable
    public static JSONObject selectBestAsset(@NonNull JSONArray assets) {
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        JSONObject universalMatch = null;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase();
            if (!name.endsWith(".apk")) continue;

            if (name.contains("universal")) {
                universalMatch = asset;
            }

            for (String abi : supportedAbis) {
                if (name.contains(abi.toLowerCase())) {
                    return asset; // Priority 1: Exact CPU architecture match
                }
            }
        }

        if (universalMatch != null) return universalMatch; // Priority 2: Universal package

        // Fallback: Return any available .apk asset
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                return asset;
            }
        }
        return null;
    }

    public static void downloadAndInstall(@NonNull Activity activity, @NonNull ReleaseInfo releaseInfo, @NonNull DownloadCallback callback) {
        new Thread(() -> {
            File cacheDir = activity.getCacheDir();
            File updatesDir = new File(cacheDir, "updates");
            if (!updatesDir.exists()) updatesDir.mkdirs();
            File apkFile = new File(updatesDir, releaseInfo.apkName);
            if (apkFile.exists()) apkFile.delete();

            HttpURLConnection conn = null;
            try {
                URL url = new URL(releaseInfo.apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "ServerHub-App");
                conn.connect();

                int code = conn.getResponseCode();
                // Handle 301/302/307 redirects (GitHub assets redirect to Amazon S3 CDN)
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    String redirectUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    conn.setRequestProperty("User-Agent", "ServerHub-App");
                    conn.connect();
                }

                long totalBytes = conn.getContentLengthLong();
                if (totalBytes <= 0) totalBytes = releaseInfo.sizeBytes;

                try (InputStream is = new BufferedInputStream(conn.getInputStream());
                     OutputStream os = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[65536];
                    long downloaded = 0;
                    int read;
                    long lastProgressUpdate = 0;

                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                        downloaded += read;

                        long now = System.currentTimeMillis();
                        if (now - lastProgressUpdate > 100 || downloaded == totalBytes) {
                            lastProgressUpdate = now;
                            int percent = totalBytes > 0 ? (int) ((downloaded * 100) / totalBytes) : -1;
                            long finalDownloaded = downloaded;
                            long finalTotal = totalBytes;
                            MAIN_HANDLER.post(() -> callback.onProgress(percent, finalDownloaded, finalTotal));
                        }
                    }
                    os.flush();
                }

                MAIN_HANDLER.post(() -> {
                    callback.onSuccess(apkFile);
                    launchInstaller(activity, apkFile);
                });

            } catch (Exception e) {
                MAIN_HANDLER.post(() -> callback.onError("Download failed: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static void launchInstaller(@NonNull Activity activity, @NonNull File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    new AlertDialog.Builder(activity)
                        .setTitle("Permission Required")
                        .setMessage("Server Hub requires permission to install app updates directly.\n\nPlease enable 'Allow from this source' on the next screen.")
                        .setPositiveButton("Settings", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                    return;
                }
            }

            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);

        } catch (Exception e) {
            new AlertDialog.Builder(activity)
                .setTitle("Installation Error")
                .setMessage("Could not start package installer: " + e.getMessage())
                .setPositiveButton("OK", null)
                .show();
        }
    }
}
