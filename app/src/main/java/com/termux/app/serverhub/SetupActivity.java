package com.termux.app.serverhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.app.models.wireguard.PortRule;
import com.termux.app.models.wireguard.WireGuardConfig;
import com.termux.app.models.wireguard.WireGuardNetworkStore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class SetupActivity extends AppCompatActivity {

    public static final String EXTRA_FORCE_SETUP = "force_setup";

    // Step 1 Views
    private TextView tvStep1Badge;
    private ProgressBar progressStep1;
    private MaterialButton btnStep1Install;
    private ScrollView scrollStep1Logs;
    private TextView tvStep1Logs;

    // Step 2 Views
    private MaterialCardView cardStep2;
    private TextView tvStep2Badge;
    private TextView tvStep2UserInfo;
    private TextInputEditText etSshPassword;
    private TextInputEditText etSshPasswordConfirm;
    private MaterialButton btnStep2SetPassword;

    // Step 3 Views
    private MaterialCardView cardStep3;
    private TextView tvStep3Badge;
    private MaterialButton btnStep3ImportConf;
    private LinearLayout layoutStep3Info;
    private TextView tvStep3Address;
    private TextView tvStep3Endpoint;
    private MaterialButton btnStep3TestConnect;

    // Step 4 Views
    private MaterialCardView cardStep4Complete;
    private TextView tvSummaryUsername;
    private TextView tvSummaryIpv6;
    private TextView tvSummarySshCommand;
    private MaterialButton btnGoToHub;

    private WireGuardNetworkStore networkStore;
    private WireGuardConfig pendingConfig;

    private final ActivityResultLauncher<String> importConfLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onConfFileSelected);

    private final BroadcastReceiver wireguardLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && WireGuardService.ACTION_LOG_UPDATE.equals(intent.getAction())) {
                String line = intent.getStringExtra(WireGuardService.EXTRA_LOG_LINE);
                if (line != null && line.contains("WireGuard userspace process started")) {
                    onWireGuardConnected();
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean forceSetup = getIntent().getBooleanExtra(EXTRA_FORCE_SETUP, false);
        if (!forceSetup && SetupPreferences.isSetupCompleted(this)) {
            startActivity(new Intent(this, ServerHubActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_setup);
        networkStore = new WireGuardNetworkStore(this);

        initViews();
        checkInitialStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WireGuardService.ACTION_LOG_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wireguardLogReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wireguardLogReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(wireguardLogReceiver);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        tvStep1Badge = findViewById(R.id.tv_step1_badge);
        progressStep1 = findViewById(R.id.progress_step1);
        btnStep1Install = findViewById(R.id.btn_step1_install);
        scrollStep1Logs = findViewById(R.id.scroll_step1_logs);
        tvStep1Logs = findViewById(R.id.tv_step1_logs);

        cardStep2 = findViewById(R.id.card_step2);
        tvStep2Badge = findViewById(R.id.tv_step2_badge);
        tvStep2UserInfo = findViewById(R.id.tv_step2_user_info);
        etSshPassword = findViewById(R.id.et_ssh_password);
        etSshPasswordConfirm = findViewById(R.id.et_ssh_password_confirm);
        btnStep2SetPassword = findViewById(R.id.btn_step2_set_password);

        cardStep3 = findViewById(R.id.card_step3);
        tvStep3Badge = findViewById(R.id.tv_step3_badge);
        btnStep3ImportConf = findViewById(R.id.btn_step3_import_conf);
        layoutStep3Info = findViewById(R.id.layout_step3_info);
        tvStep3Address = findViewById(R.id.tv_step3_address);
        tvStep3Endpoint = findViewById(R.id.tv_step3_endpoint);
        btnStep3TestConnect = findViewById(R.id.btn_step3_test_connect);

        cardStep4Complete = findViewById(R.id.card_step4_complete);
        tvSummaryUsername = findViewById(R.id.tv_summary_username);
        tvSummaryIpv6 = findViewById(R.id.tv_summary_ipv6);
        tvSummarySshCommand = findViewById(R.id.tv_summary_ssh_command);
        btnGoToHub = findViewById(R.id.btn_go_to_hub);

        String username = ServerManager.getUsername();
        tvStep2UserInfo.setText("SSH User: " + username + " | Internal Port: 8022");

        btnStep1Install.setOnClickListener(v -> startStep1Installation());
        btnStep2SetPassword.setOnClickListener(v -> startStep2SetPassword());
        btnStep3ImportConf.setOnClickListener(v -> importConfLauncher.launch("*/*"));
        btnStep3TestConnect.setOnClickListener(v -> startStep3TestConnection());
        btnGoToHub.setOnClickListener(v -> {
            SetupPreferences.setSetupCompleted(this, true);
            startActivity(new Intent(this, ServerHubActivity.class));
            finish();
        });
    }

    private void checkInitialStatus() {
        boolean opensshReady = ServerManager.isOpenSSHInstalled();
        boolean bootstrapReady = ServerManager.isBootstrapExtracted();

        if (bootstrapReady && opensshReady) {
            markStep1Done();
            unlockStep2();
        } else {
            lockStep2();
            lockStep3();
        }

        if (SetupPreferences.isPasswordConfigured(this) || ServerManager.isSSHDRunning()) {
            markStep2Done();
            unlockStep3();
        }

        WireGuardConfig savedConfig = networkStore.getConfig();
        if (savedConfig != null) {
            displayStep3Config(savedConfig);
        }
    }

    private void startStep1Installation() {
        btnStep1Install.setEnabled(false);
        progressStep1.setVisibility(View.VISIBLE);
        scrollStep1Logs.setVisibility(View.VISIBLE);
        tvStep1Logs.setText("[SETUP] Preparing sandbox environment...\n");
        tvStep1Badge.setText("RUNNING");
        tvStep1Badge.setTextColor(Color.parseColor("#80D8FF"));

        if (!ServerManager.isBootstrapExtracted()) {
            tvStep1Logs.append("[BOOTSTRAP] Extracting core Termux system packages...\n");
            TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
                runOnUiThread(() -> {
                    tvStep1Logs.append("[BOOTSTRAP] Core system ready. Installing OpenSSH...\n");
                    runPackageInstall();
                });
            });
        } else {
            runPackageInstall();
        }
    }

    private void runPackageInstall() {
        ServerManager.installRequiredPackages(new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {
                tvStep1Logs.append(line + "\n");
                scrollStep1Logs.post(() -> scrollStep1Logs.fullScroll(View.FOCUS_DOWN));
            }

            @Override
            public void onComplete(int exitCode) {
                progressStep1.setVisibility(View.GONE);
                if (exitCode == 0 || ServerManager.isOpenSSHInstalled()) {
                    tvStep1Logs.append("[SUCCESS] Environment and OpenSSH server installed successfully!\n");
                    markStep1Done();
                    unlockStep2();
                    Toast.makeText(SetupActivity.this, "Step 1 Completed!", Toast.LENGTH_SHORT).show();
                } else {
                    btnStep1Install.setEnabled(true);
                    tvStep1Badge.setText("FAILED");
                    tvStep1Badge.setTextColor(Color.parseColor("#FF5252"));
                    tvStep1Logs.append("[ERROR] Package installation exited with code " + exitCode + ".\n");
                }
            }
        });
    }

    private void markStep1Done() {
        tvStep1Badge.setText("✓ READY");
        tvStep1Badge.setTextColor(Color.parseColor("#00E676"));
        btnStep1Install.setEnabled(false);
        btnStep1Install.setText("✓ Environment & Packages Ready");
        btnStep1Install.setBackgroundColor(Color.parseColor("#2E7D32"));
    }

    private void unlockStep2() {
        cardStep2.setAlpha(1.0f);
        tvStep2Badge.setText("READY");
        tvStep2Badge.setTextColor(Color.parseColor("#FFB74D"));
        btnStep2SetPassword.setEnabled(true);
    }

    private void lockStep2() {
        cardStep2.setAlpha(0.5f);
        tvStep2Badge.setText("LOCKED");
        tvStep2Badge.setTextColor(Color.parseColor("#777777"));
        btnStep2SetPassword.setEnabled(false);
    }

    private void startStep2SetPassword() {
        String pass = etSshPassword.getText() != null ? etSshPassword.getText().toString().trim() : "";
        String confirm = etSshPasswordConfirm.getText() != null ? etSshPasswordConfirm.getText().toString().trim() : "";

        if (pass.isEmpty()) {
            etSshPassword.setError("Password cannot be empty");
            return;
        }
        if (pass.length() < 4) {
            etSshPassword.setError("Password must be at least 4 characters");
            return;
        }
        if (!pass.equals(confirm)) {
            etSshPasswordConfirm.setError("Passwords do not match");
            return;
        }

        btnStep2SetPassword.setEnabled(false);
        btnStep2SetPassword.setText("Applying Password...");

        ServerManager.setSSHPassword(pass, new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {}

            @Override
            public void onComplete(int exitCode) {
                SetupPreferences.setPasswordConfigured(SetupActivity.this, true);
                markStep2Done();
                unlockStep3();
                Toast.makeText(SetupActivity.this, "SSH Password configured successfully!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void markStep2Done() {
        tvStep2Badge.setText("✓ READY");
        tvStep2Badge.setTextColor(Color.parseColor("#00E676"));
        btnStep2SetPassword.setEnabled(false);
        btnStep2SetPassword.setText("✓ SSH Password Set & Server Running");
        btnStep2SetPassword.setBackgroundColor(Color.parseColor("#2E7D32"));
    }

    private void unlockStep3() {
        cardStep3.setAlpha(1.0f);
        tvStep3Badge.setText("PENDING");
        tvStep3Badge.setTextColor(Color.parseColor("#FFB74D"));
        btnStep3ImportConf.setEnabled(true);
    }

    private void lockStep3() {
        cardStep3.setAlpha(0.5f);
        tvStep3Badge.setText("LOCKED");
        tvStep3Badge.setTextColor(Color.parseColor("#777777"));
        btnStep3ImportConf.setEnabled(false);
    }

    private void onConfFileSelected(@Nullable Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            WireGuardConfig config = WireGuardConfig.parse(sb.toString());
            if (config.getPrivateKey() == null || config.getPeerPublicKey() == null) {
                Toast.makeText(this, "Invalid WireGuard config: missing keys", Toast.LENGTH_LONG).show();
                return;
            }

            pendingConfig = config;
            displayStep3Config(config);

        } catch (Exception e) {
            Toast.makeText(this, "Failed to read .conf: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void displayStep3Config(@NonNull WireGuardConfig config) {
        layoutStep3Info.setVisibility(View.VISIBLE);
        tvStep3Address.setText("IPv6 Address: " + (config.getAddress() != null ? config.getAddress() : "-"));
        tvStep3Endpoint.setText("Endpoint: " + (config.getEndpoint() != null ? config.getEndpoint() : "-"));
        btnStep3TestConnect.setVisibility(View.VISIBLE);
    }

    private void startStep3TestConnection() {
        if (pendingConfig == null) {
            pendingConfig = networkStore.getConfig();
        }
        if (pendingConfig == null) {
            Toast.makeText(this, "Please select a WireGuard .conf file first", Toast.LENGTH_SHORT).show();
            return;
        }

        btnStep3TestConnect.setEnabled(false);
        btnStep3TestConnect.setText("Testing Connection...");
        tvStep3Badge.setText("CONNECTING");
        tvStep3Badge.setTextColor(Color.parseColor("#80D8FF"));

        // Save config
        networkStore.saveConfig(pendingConfig);

        // Ensure default port rules (SSH: 2222 -> 8022 TCP, Game: 7777 -> 7777 UDP)
        List<PortRule> rules = networkStore.getPortRules();
        boolean hasSsh = false;
        boolean hasGame = false;
        for (PortRule r : rules) {
            if ("SSH Access".equalsIgnoreCase(r.getName()) || r.getListenPort() == 2222) hasSsh = true;
            if ("NVGT Game Server".equalsIgnoreCase(r.getName()) || r.getListenPort() == 7777) hasGame = true;
        }
        if (!hasSsh) {
            rules.add(0, new PortRule("SSH Access", "tcp", 2222, "127.0.0.1", 8022, true));
        }
        if (!hasGame) {
            rules.add(new PortRule("NVGT Game Server", "udp", 7777, "127.0.0.1", 7777, true));
        }
        networkStore.savePortRules(rules);

        // Start WireGuard service
        WireGuardService.startService(this);

        // Verify connection within 2 seconds
        btnStep3TestConnect.postDelayed(() -> {
            if (WireGuardService.isServiceRunning()) {
                onWireGuardConnected();
            } else {
                btnStep3TestConnect.setEnabled(true);
                btnStep3TestConnect.setText("Retry WireGuard Connection");
                tvStep3Badge.setText("FAILED");
                tvStep3Badge.setTextColor(Color.parseColor("#FF5252"));
                Toast.makeText(this, "WireGuard failed to start. Check your .conf keys.", Toast.LENGTH_LONG).show();
            }
        }, 2500);
    }

    private void onWireGuardConnected() {
        tvStep3Badge.setText("✓ CONNECTED");
        tvStep3Badge.setTextColor(Color.parseColor("#00E676"));
        btnStep3TestConnect.setEnabled(false);
        btnStep3TestConnect.setText("✓ WireGuard Tunnel Active");
        btnStep3TestConnect.setBackgroundColor(Color.parseColor("#2E7D32"));

        showCompletionSummary();
    }

    private void showCompletionSummary() {
        cardStep4Complete.setVisibility(View.VISIBLE);

        String username = ServerManager.getUsername();
        String ipv6 = (pendingConfig != null && pendingConfig.getAddress() != null) ?
                pendingConfig.getAddress().replaceAll("/.*", "") : "::1";

        tvSummaryUsername.setText("Username: " + username);
        tvSummaryIpv6.setText("Public IPv6: " + ipv6);
        tvSummarySshCommand.setText("SSH Command: ssh " + username + "@" + ipv6 + " -p 2222");

        cardStep4Complete.getParent().requestChildFocus(cardStep4Complete, cardStep4Complete);
    }
}
