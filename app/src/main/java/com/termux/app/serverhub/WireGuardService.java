package com.termux.app.serverhub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.models.wireguard.WireGuardConfig;
import com.termux.app.models.wireguard.WireGuardNetworkStore;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WireGuardService extends Service {
    private static final String LOG_TAG = "WireGuardService";
    private static final String CHANNEL_ID = "game_server_wireguard_channel";
    private static final int NOTIFICATION_ID = 2026;

    public static final String ACTION_START = "com.termux.app.wireguard.START";
    public static final String ACTION_STOP = "com.termux.app.wireguard.STOP";
    public static final String ACTION_LOG_UPDATE = "com.termux.app.wireguard.LOG_UPDATE";
    public static final String EXTRA_LOG_LINE = "log_line";
    public static final String ACTION_HANDSHAKE_OK = "com.termux.app.wireguard.HANDSHAKE_OK";

    public interface TunnelStateListener {
        void onTunnelStateChanged(boolean running);
    }

    private static final List<TunnelStateListener> stateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addStateListener(@NonNull TunnelStateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public static void removeStateListener(@NonNull TunnelStateListener listener) {
        stateListeners.remove(listener);
    }

    private static void notifyTunnelState(boolean running) {
        for (TunnelStateListener listener : stateListeners) {
            try {
                listener.onTunnelStateChanged(running);
            } catch (Exception ignored) {}
        }
    }

    private static volatile boolean isRunning = false;
    private static final List<String> recentLogs = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LOG_LINES = 150;

    private PowerManager.WakeLock wakeLock;
    private Process wireguardProcess;
    private WireGuardNetworkStore networkStore;

    public static boolean isRunning() {
        return isRunning;
    }

    public static boolean isServiceRunning() {
        return isRunning;
    }

    public static void startService(@NonNull Context context) {
        start(context);
    }

    public static void stopService(@NonNull Context context) {
        stop(context);
    }

    public static void restartService(@NonNull Context context) {
        stop(context);
        start(context);
    }

    public static List<String> getRecentLogs() {
        synchronized (recentLogs) {
            return new ArrayList<>(recentLogs);
        }
    }

    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, WireGuardService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(@NonNull Context context) {
        Intent intent = new Intent(context, WireGuardService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        networkStore = new WireGuardNetworkStore(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_START.equals(intent.getAction())) {
            startTunnel();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startTunnel() {
        if (isRunning) return;

        acquireWakeLock();

        // Ensure internal OpenSSH server is running so port 2222 forwards work
        ServerManager.ensureSSHDRunning(null);

        WireGuardConfig config = networkStore.getConfig();
        String address = (config != null && !config.getAddress().isEmpty()) ? config.getAddress() : "Active";
        startForeground(NOTIFICATION_ID, buildNotification("WireGuard Tunnel Active", "IPv6: " + address));

        networkStore.generateWireproxyConfig();
        File configFile = networkStore.getWireproxyConfigFile();

        File binaryFile = getBinaryPath();
        if (binaryFile == null || !binaryFile.exists()) {
            appendLog("[ERROR] libwireguard.so binary not found in native library directory!");
            stopSelf();
            return;
        }

        new Thread(() -> runProcess(binaryFile, configFile)).start();
    }

    private File getBinaryPath() {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        File bin = new File(nativeDir, "libwireguard.so");
        if (bin.exists()) return bin;

        // Fallback check
        File fallback = new File(getFilesDir(), "libwireguard.so");
        if (fallback.exists()) return fallback;

        return bin;
    }

    private void runProcess(File binaryFile, File configFile) {
        try {
            appendLog("[SYSTEM] Launching userspace WireGuard engine...");
            appendLog("[SYSTEM] Binary: " + binaryFile.getAbsolutePath());
            appendLog("[SYSTEM] Config: " + configFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath(), "-c", configFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());

            wireguardProcess = pb.start();
            isRunning = true;
            networkStore.setTunnelActive(true);
            notifyTunnelState(true);

            appendLog("[SUCCESS] WireGuard userspace process started (PID active)");

            try (InputStream is = wireguardProcess.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }

            int exitCode = wireguardProcess.waitFor();
            appendLog("[SYSTEM] WireGuard process exited with code " + exitCode);

        } catch (Exception e) {
            if (!(e instanceof java.io.InterruptedIOException)) {
                appendLog("[ERROR] Failed to run WireGuard engine: " + e.getMessage());
                Logger.logStackTraceWithMessage(LOG_TAG, "WireGuard process failure", e);
            }
        } finally {
            isRunning = false;
            networkStore.setTunnelActive(false);
            notifyTunnelState(false);
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopTunnel() {
        appendLog("[SYSTEM] Stopping WireGuard tunnel...");
        if (wireguardProcess != null) {
            try {
                wireguardProcess.destroy();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wireguardProcess.destroyForcibly();
                }
            } catch (Exception ignored) {}
            wireguardProcess = null;
        }
        isRunning = false;
        if (networkStore != null) {
            networkStore.setTunnelActive(false);
        }
        notifyTunnelState(false);
        releaseWakeLock();
        stopForeground(true);
    }

    private void appendLog(String line) {
        if (line == null) return;
        android.util.Log.i(LOG_TAG, line);

        // Filter internal crypto worker & keepalive noise from UI view
        if (line.contains("Routine:") || line.contains("keepalive") || line.contains("UAPI:")) {
            return;
        }

        String userLine = line;
        if (line.contains("Received handshake response")) {
            userLine = "[SUCCESS] Handshake verified with tunnel broker! Tunnel is active.";
        } else if (line.contains("Sending handshake initiation")) {
            userLine = "[INFO] Sending WireGuard handshake to tunnel broker...";
        } else if (line.contains("TCPServerTunnel listening")) {
            userLine = "[PORT] " + line.substring(line.indexOf("TCPServerTunnel"));
        } else if (line.contains("UDPServerTunnel listening")) {
            userLine = "[PORT] " + line.substring(line.indexOf("UDPServerTunnel"));
        } else if (line.contains("connection refused")) {
            userLine = "[WARN] Connection refused to internal port. Ensuring OpenSSH is active...";
            ServerManager.restartSSHD(null);
        }

        synchronized (recentLogs) {
            if (recentLogs.size() >= MAX_LOG_LINES) {
                recentLogs.remove(0);
            }
            recentLogs.add(userLine);
        }
        Intent intent = new Intent(ACTION_LOG_UPDATE);
        intent.putExtra(EXTRA_LOG_LINE, userLine);
        sendBroadcast(intent);

        if (line.contains("Received handshake response") || line.contains("UDPServerTunnel listening") || line.contains("TCPServerTunnel listening")) {
            Intent okIntent = new Intent(ACTION_HANDSHAKE_OK);
            sendBroadcast(okIntent);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameServerHub:WireGuardWakeLock");
                wakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Game Server WireGuard Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active status of the WireGuard game server tunnel");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, ServerHubActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }
}
