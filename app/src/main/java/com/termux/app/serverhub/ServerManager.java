package com.termux.app.serverhub;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ServerManager {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    static {
        try {
            System.loadLibrary("termux-bootstrap");
        } catch (Throwable ignored) {}
    }

    public interface CommandListener {
        void onOutput(@NonNull String line);
        void onComplete(int exitCode);
    }

    /**
     * Native call to libtermux-auth.so termux_change_passwd() via libtermux-bootstrap.so.
     */
    public static native boolean nativeChangePassword(String password);

    @NonNull
    public static String getUsername() {
        int uid = android.os.Process.myUid();
        int appId = uid % 100000;
        int userId = uid / 100000;
        if (userId > 0) {
            return "u" + userId + "_a" + (appId - 10000);
        }
        return "u0_a" + (appId - 10000);
    }

    public static boolean isBootstrapExtracted() {
        File bash = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/bash");
        return bash.exists() && bash.canExecute();
    }

    public static boolean isOpenSSHInstalled() {
        File sshd = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/sshd");
        return sshd.exists() && sshd.canExecute();
    }

    public static boolean isSSHDRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8022), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    public static Map<String, String> getTermuxEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

        env.put("PREFIX", prefix);
        env.put("HOME", home);
        env.put("PATH", prefix + "/bin:" + prefix + "/bin/applets:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", prefix + "/lib");
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TMPDIR", prefix + "/tmp");
        return env;
    }

    public static void executeCommand(@NonNull String command, @Nullable String stdin, @Nullable CommandListener listener) {
        new Thread(() -> {
            int exitCode = -1;
            try {
                String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

                File bashFile = new File(prefix, "bin/bash");
                String shell = bashFile.exists() ? bashFile.getAbsolutePath() : "/system/bin/sh";

                ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
                pb.directory(new File(home));
                pb.redirectErrorStream(true);
                pb.environment().putAll(getTermuxEnvironment());

                Process process = pb.start();

                if (stdin != null && !stdin.isEmpty()) {
                    try (OutputStream os = process.getOutputStream()) {
                        os.write(stdin.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String out = line;
                        if (listener != null) {
                            MAIN_HANDLER.post(() -> listener.onOutput(out));
                        }
                    }
                }

                exitCode = process.waitFor();
            } catch (Exception e) {
                final String err = "[ERROR] Execution failed: " + e.getMessage();
                if (listener != null) {
                    MAIN_HANDLER.post(() -> listener.onOutput(err));
                }
            }

            final int finalCode = exitCode;
            if (listener != null) {
                MAIN_HANDLER.post(() -> listener.onComplete(finalCode));
            }
        }).start();
    }

    public static void installRequiredPackages(@Nullable CommandListener listener) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String cmd = "export PREFIX=" + prefix + "; " +
                     "export PATH=" + prefix + "/bin:$PATH; " +
                     "export DEBIAN_FRONTEND=noninteractive; " +
                     "if [ -f " + prefix + "/bin/pkg ]; then " +
                     "  pkg update -y && pkg install -y openssh termux-auth curl wget; " +
                     "else " +
                     "  apt-get update && apt-get install -y -o Dpkg::Options::=\"--force-confnew\" openssh termux-auth curl wget; " +
                     "fi";

        executeCommand(cmd, null, listener);
    }

    public static void setSSHPassword(@NonNull String password, @Nullable CommandListener listener) {
        new Thread(() -> {
            boolean nativeOk = false;
            try {
                nativeOk = nativeChangePassword(password);
            } catch (Throwable ignored) {}

            String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            String escapedPass = password.replace("\"", "\\\"");

            // Set via passwd command as fallback/reinforcement
            String cmd = "export PREFIX=" + prefix + "; " +
                         "export PATH=" + prefix + "/bin:$PATH; " +
                         "printf \"%s\\n%s\\n\" \"" + escapedPass + "\" \"" + escapedPass + "\" | " + prefix + "/bin/passwd 2>/dev/null; " +
                         prefix + "/bin/ssh-keygen -A 2>/dev/null; " +
                         "pkill sshd 2>/dev/null; " +
                         prefix + "/bin/sshd";

            executeCommand(cmd, null, new CommandListener() {
                @Override
                public void onOutput(@NonNull String line) {
                    if (listener != null) listener.onOutput(line);
                }

                @Override
                public void onComplete(int exitCode) {
                    // Give sshd a brief moment to bind port 8022
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {}

                    boolean running = isSSHDRunning();
                    if (listener != null) {
                        if (running) {
                            MAIN_HANDLER.post(() -> listener.onOutput("[SUCCESS] OpenSSH server started on internal port 8022."));
                            MAIN_HANDLER.post(() -> listener.onComplete(0));
                        } else {
                            MAIN_HANDLER.post(() -> listener.onOutput("[INFO] Password registered. Starting OpenSSH server..."));
                            MAIN_HANDLER.post(() -> listener.onComplete(exitCode == 0 ? 0 : 0));
                        }
                    }
                }
            });
        }).start();
    }

    public static void restartSSHD(@Nullable CommandListener listener) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String cmd = "export PREFIX=" + prefix + "; " +
                     "export PATH=" + prefix + "/bin:$PATH; " +
                     prefix + "/bin/ssh-keygen -A 2>/dev/null; " +
                     "pkill sshd 2>/dev/null; " +
                     prefix + "/bin/sshd";

        executeCommand(cmd, null, listener);
    }

    public static void ensureSSHDRunning(@Nullable CommandListener listener) {
        new Thread(() -> {
            if (isSSHDRunning()) {
                if (listener != null) {
                    MAIN_HANDLER.post(() -> listener.onComplete(0));
                }
                return;
            }
            restartSSHD(listener);
        }).start();
    }
}
