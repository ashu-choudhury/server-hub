package com.termux.app.serverhub;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public class SetupPreferences {

    private static final String PREF_NAME = "serverhub_setup_prefs";
    private static final String LEGACY_PREF_NAME = "termux_setup_prefs";
    private static final String KEY_SETUP_COMPLETED = "setup_completed";
    private static final String KEY_PASSWORD_CONFIGURED = "password_configured";
    private static final String KEY_SSH_PORT = "ssh_port";

    private static SharedPreferences getPrefs(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        // Seamless migration: If new prefs are empty, check legacy prefs from previous backups
        if (!prefs.contains(KEY_SETUP_COMPLETED)) {
            SharedPreferences legacy = context.getApplicationContext().getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE);
            if (legacy.contains(KEY_SETUP_COMPLETED) || legacy.contains(KEY_PASSWORD_CONFIGURED)) {
                SharedPreferences.Editor editor = prefs.edit();
                if (legacy.contains(KEY_SETUP_COMPLETED)) {
                    editor.putBoolean(KEY_SETUP_COMPLETED, legacy.getBoolean(KEY_SETUP_COMPLETED, false));
                }
                if (legacy.contains(KEY_PASSWORD_CONFIGURED)) {
                    editor.putBoolean(KEY_PASSWORD_CONFIGURED, legacy.getBoolean(KEY_PASSWORD_CONFIGURED, false));
                }
                if (legacy.contains(KEY_SSH_PORT)) {
                    editor.putInt(KEY_SSH_PORT, legacy.getInt(KEY_SSH_PORT, 8022));
                }
                editor.apply();
            }
        }
        return prefs;
    }

    public static boolean isSetupCompleted(@NonNull Context context) {
        return getPrefs(context).getBoolean(KEY_SETUP_COMPLETED, false);
    }

    public static void setSetupCompleted(@NonNull Context context, boolean completed) {
        getPrefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply();
    }

    public static boolean isPasswordConfigured(@NonNull Context context) {
        return getPrefs(context).getBoolean(KEY_PASSWORD_CONFIGURED, false);
    }

    public static void setPasswordConfigured(@NonNull Context context, boolean configured) {
        getPrefs(context).edit().putBoolean(KEY_PASSWORD_CONFIGURED, configured).apply();
    }

    public static int getSshPort(@NonNull Context context) {
        return getPrefs(context).getInt(KEY_SSH_PORT, 8022);
    }

    public static void setSshPort(@NonNull Context context, int port) {
        getPrefs(context).edit().putInt(KEY_SSH_PORT, port).apply();
    }
}
