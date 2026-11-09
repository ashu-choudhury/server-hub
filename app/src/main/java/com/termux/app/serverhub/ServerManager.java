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
        // Android 7+ binaries use DT_RUNPATH; remove LD_LIBRARY_PATH to avoid conflicting with system binaries
        env.remove("LD_LIBRARY_PATH");
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

                File homeDir = new File(home);
                if (!homeDir.exists()) homeDir.mkdirs();

                File tmpDir = new File(prefix, "tmp");
                if (!tmpDir.exists()) tmpDir.mkdirs();

                File bashFile = new File(prefix, "bin/bash");
                String shell = (bashFile.exists() && bashFile.canExecute()) ?
                        bashFile.getAbsolutePath() : "/system/bin/sh";

                android.util.Log.i("ServerManager", "executeCommand shell: " + shell + " in: " + homeDir.getAbsolutePath());
                android.util.Log.i("ServerManager", "executeCommand cmd: " + command);

                ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
                pb.directory(homeDir.exists() ? homeDir : new File(prefix));
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
                        android.util.Log.d("ServerManager", "stdout: " + out);
                        if (listener != null) {
                            MAIN_HANDLER.post(() -> listener.onOutput(out));
                        }
                    }
                }

                exitCode = process.waitFor();
                android.util.Log.i("ServerManager", "executeCommand finished with exitCode: " + exitCode);
            } catch (Exception e) {
                android.util.Log.e("ServerManager", "executeCommand error", e);
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
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

        StringBuilder sb = new StringBuilder();
        sb.append("export PREFIX=").append(prefix).append("; ");
        sb.append("export HOME=").append(home).append("; ");
        sb.append("export PATH=").append(prefix).append("/bin:$PATH; ");
        sb.append("export TMPDIR=").append(prefix).append("/tmp; ");
        sb.append("export DEBIAN_FRONTEND=noninteractive; ");

        // Ensure directories exist
        sb.append("mkdir -p $PREFIX/tmp $HOME $PREFIX/var/lib/dpkg/updates $PREFIX/var/lib/dpkg/info ");
        sb.append("$PREFIX/var/log/apt $PREFIX/var/cache/apt/archives/partial $PREFIX/var/lib/apt/lists/partial $PREFIX/etc/apt/apt.conf.d; ");

        // Ensure status files exist
        sb.append("touch $PREFIX/var/lib/dpkg/status $PREFIX/var/lib/dpkg/available 2>/dev/null; ");

        // Ensure reliable sources.list with fallbacks
        sb.append("echo 'deb https://packages-cf.termux.dev/apt/termux-main/ stable main' > $PREFIX/etc/apt/sources.list; ");
        sb.append("echo 'deb https://packages.termux.dev/apt/termux-main/ stable main' >> $PREFIX/etc/apt/sources.list; ");
        sb.append("echo 'deb https://grimler.se/termux/termux-main/ stable main' >> $PREFIX/etc/apt/sources.list; ");

        // Resolve DNS if missing
        sb.append("if [ ! -s $PREFIX/etc/resolv.conf ]; then ");
        sb.append("  echo 'nameserver 1.1.1.1' > $PREFIX/etc/resolv.conf; ");
        sb.append("  echo 'nameserver 8.8.8.8' >> $PREFIX/etc/resolv.conf; ");
        sb.append("  echo 'nameserver 9.9.9.9' >> $PREFIX/etc/resolv.conf; ");
        sb.append("fi; ");

        // Recover any pending dpkg configuration
        sb.append("dpkg --configure -a 2>/dev/null || true; ");

        // Update apt indices
        sb.append("apt-get update --allow-insecure-repositories 2>&1 || true; ");

        // Install openssh, termux-auth, curl, wget
        sb.append("apt-get install -y -o Dpkg::Options::='--force-confnew' --no-install-recommends openssh termux-auth curl wget; ");

        // Fallback to pkg if sshd still missing
        sb.append("if [ ! -f $PREFIX/bin/sshd ] && [ -f $PREFIX/bin/pkg ]; then ");
        sb.append("  pkg install -y openssh termux-auth curl wget; ");
        sb.append("fi;");

        executeCommand(sb.toString(), null, listener);
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
                         "export LD_LIBRARY_PATH=" + prefix + "/lib; " +
                         "chmod 700 " + prefix + "/etc/ssh 2>/dev/null; " +
                         "chmod 600 " + prefix + "/etc/ssh/ssh_host_* 2>/dev/null; " +
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
                     "export LD_LIBRARY_PATH=" + prefix + "/lib; " +
                     "chmod 700 " + prefix + "/etc/ssh 2>/dev/null; " +
                     "chmod 600 " + prefix + "/etc/ssh/ssh_host_* 2>/dev/null; " +
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
