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
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.app.models.wireguard.PortRule;
import com.termux.app.models.wireguard.WireGuardConfig;
import com.termux.app.models.wireguard.WireGuardNetworkStore;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class SetupActivity extends AppCompatActivity {

    public static final String EXTRA_FORCE_SETUP = "force_setup";

    // Header Indicators
    private TextView tvStepIndicator;
    private TextView tvOverallPercentage;
    private ProgressBar progressOverall;
    private ViewFlipper viewFlipper;

    // Page 0: Environment
    private TextView tvStep1Percent;
    private ProgressBar progressStep1Bar;
    private TextView tvStep1StatusMsg;
    private MaterialButton btnStep1Start;
    private MaterialButton btnRestoreFromBackup;
    private MaterialButton btnPage0Next;

    // Page 1: SSH Credentials
    private TextView tvPage1UserInfo;
    private TextInputEditText etPage1Password;
    private TextInputEditText etPage1Confirm;
    private MaterialButton btnPage1Apply;
    private TextView tvPage1Status;
    private MaterialButton btnPage1Back;
    private MaterialButton btnPage1Next;

    // Page 2: WireGuard Tunnel
    private MaterialButton btnPage2SelectConf;
    private LinearLayout layoutPage2ConfInfo;
    private TextView tvPage2Address;
    private TextView tvPage2Endpoint;
    private MaterialButton btnPage2Connect;
    private TextView tvPage2Status;
    private MaterialButton btnPage2Back;
    private MaterialButton btnPage2Next;

    // Page 3: Summary
    private TextView tvPage3User;
    private TextView tvPage3Ipv6;
    private TextView tvPage3SshCmd;
    private MaterialButton btnPage3Finish;
    private MaterialButton btnPage3Back;

    private WireGuardNetworkStore networkStore;
    private WireGuardConfig pendingConfig;

    private final ActivityResultLauncher<String> importConfLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onConfFileSelected);

    private final ActivityResultLauncher<String> restoreBackupLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onRestoreBackupFileSelected);

    private final BroadcastReceiver wireguardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (WireGuardService.ACTION_HANDSHAKE_OK.equals(action)) {
                onWireGuardConnected();
            } else if (WireGuardService.ACTION_LOG_UPDATE.equals(action)) {
                String line = intent.getStringExtra(WireGuardService.EXTRA_LOG_LINE);
                if (line != null && (line.contains("Received handshake response") || line.contains("now Up") || line.contains("UDPServerTunnel listening"))) {
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
        IntentFilter filter = new IntentFilter();
        filter.addAction(WireGuardService.ACTION_LOG_UPDATE);
        filter.addAction(WireGuardService.ACTION_HANDSHAKE_OK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wireguardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wireguardReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(wireguardReceiver);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        // Persistent Header
        tvStepIndicator = findViewById(R.id.tv_step_indicator);
        tvOverallPercentage = findViewById(R.id.tv_overall_percentage);
        progressOverall = findViewById(R.id.progress_overall);
        viewFlipper = findViewById(R.id.view_flipper_setup);
        viewFlipper.setInAnimation(this, android.R.anim.fade_in);
        viewFlipper.setOutAnimation(this, android.R.anim.fade_out);

        // Page 0
        tvStep1Percent = findViewById(R.id.tv_step1_percent);
        progressStep1Bar = findViewById(R.id.progress_step1_bar);
        tvStep1StatusMsg = findViewById(R.id.tv_step1_status_msg);
        btnStep1Start = findViewById(R.id.btn_step1_start);
        btnRestoreFromBackup = findViewById(R.id.btn_restore_from_backup);
        btnPage0Next = findViewById(R.id.btn_page0_next);

        // Page 1
        tvPage1UserInfo = findViewById(R.id.tv_page1_user_info);
        etPage1Password = findViewById(R.id.et_page1_password);
        etPage1Confirm = findViewById(R.id.et_page1_confirm);
        btnPage1Apply = findViewById(R.id.btn_page1_apply);
        tvPage1Status = findViewById(R.id.tv_page1_status);
        btnPage1Back = findViewById(R.id.btn_page1_back);
        btnPage1Next = findViewById(R.id.btn_page1_next);

        // Page 2
        btnPage2SelectConf = findViewById(R.id.btn_page2_select_conf);
        layoutPage2ConfInfo = findViewById(R.id.layout_page2_conf_info);
        tvPage2Address = findViewById(R.id.tv_page2_address);
        tvPage2Endpoint = findViewById(R.id.tv_page2_endpoint);
        btnPage2Connect = findViewById(R.id.btn_page2_connect);
        tvPage2Status = findViewById(R.id.tv_page2_status);
        btnPage2Back = findViewById(R.id.btn_page2_back);
        btnPage2Next = findViewById(R.id.btn_page2_next);

        // Page 3
        tvPage3User = findViewById(R.id.tv_page3_user);
        tvPage3Ipv6 = findViewById(R.id.tv_page3_ipv6);
        tvPage3SshCmd = findViewById(R.id.tv_page3_ssh_cmd);
        btnPage3Finish = findViewById(R.id.btn_page3_finish);
        btnPage3Back = findViewById(R.id.btn_page3_back);

        // Listeners - Navigation
        btnPage0Next.setOnClickListener(v -> goToPage(1));
        btnPage1Back.setOnClickListener(v -> goToPage(0));
        btnPage1Next.setOnClickListener(v -> goToPage(2));
        btnPage2Back.setOnClickListener(v -> goToPage(1));
        btnPage2Next.setOnClickListener(v -> goToPage(3));
        btnPage3Back.setOnClickListener(v -> goToPage(2));

        btnStep1Start.setOnClickListener(v -> startInstallationProcess());
        if (btnRestoreFromBackup != null) {
            btnRestoreFromBackup.setOnClickListener(v -> restoreBackupLauncher.launch("*/*"));
        }
        btnPage1Apply.setOnClickListener(v -> applySSHPassword());
        btnPage2SelectConf.setOnClickListener(v -> importConfLauncher.launch("*/*"));
        btnPage2Connect.setOnClickListener(v -> startWireGuardConnection());

        btnPage3Finish.setOnClickListener(v -> {
            SetupPreferences.setSetupCompleted(this, true);
            startActivity(new Intent(this, ServerHubActivity.class));
            finish();
        });

        String username = ServerManager.getUsername();
        tvPage1UserInfo.setText("Linux User: " + username + " | Default SSH Port: 8022");
    }

    private void goToPage(int pageIndex) {
        viewFlipper.setDisplayedChild(pageIndex);
        switch (pageIndex) {
            case 0:
                tvStepIndicator.setText("Step 1 of 4: Environment");
                tvOverallPercentage.setText("25%");
                progressOverall.setProgress(25);
                break;
            case 1:
                tvStepIndicator.setText("Step 2 of 4: SSH Credentials");
                tvOverallPercentage.setText("50%");
                progressOverall.setProgress(50);
                break;
            case 2:
                tvStepIndicator.setText("Step 3 of 4: WireGuard Tunnel");
                tvOverallPercentage.setText("75%");
                progressOverall.setProgress(75);
                break;
            case 3:
                tvStepIndicator.setText("Step 4 of 4: All Set!");
                tvOverallPercentage.setText("100%");
                progressOverall.setProgress(100);
                updateSummaryDetails();
                break;
        }
    }

    private void checkInitialStatus() {
        if (ServerManager.isBootstrapExtracted() && ServerManager.isOpenSSHInstalled()) {
            setStep1Progress(100, "✓ Sandbox Environment & OpenSSH Installed");
            btnStep1Start.setEnabled(false);
            btnStep1Start.setText("✓ Already Installed");
            btnPage0Next.setEnabled(true);
        }

        if (SetupPreferences.isPasswordConfigured(this)) {
            tvPage1Status.setText("✓ SSH Password configured and OpenSSH service active.");
            btnPage1Next.setEnabled(true);
            ServerManager.ensureSSHDRunning(null);
        } else {
            new Thread(() -> {
                boolean running = ServerManager.isSSHDRunning();
                if (running) {
                    runOnUiThread(() -> {
                        tvPage1Status.setText("✓ SSH Password configured and OpenSSH service active.");
                        btnPage1Next.setEnabled(true);
                    });
                }
            }).start();
        }

        WireGuardConfig savedConfig = networkStore.getConfig();
        if (savedConfig != null) {
            displayPage2Config(savedConfig);
        }
    }

    private void setStep1Progress(int percent, String message) {
        tvStep1Percent.setText(percent + "%");
        progressStep1Bar.setProgress(percent);
        tvStep1StatusMsg.setText(message);
    }

    private void startInstallationProcess() {
        btnStep1Start.setEnabled(false);
        btnStep1Start.setText("Installing Environment...");
        setStep1Progress(15, "Checking sandbox components...");

        if (!ServerManager.isBootstrapExtracted()) {
            // Clean up any partially extracted/corrupt prefix so bootstrap extracts fresh
            File prefixDir = new File(com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            if (prefixDir.exists()) {
                com.termux.shared.file.FileUtils.deleteFile("corrupted prefix", prefixDir.getAbsolutePath(), true);
            }
            setStep1Progress(30, "Extracting core sandbox packages...");
            TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
                runOnUiThread(() -> {
                    setStep1Progress(60, "Installing OpenSSH server & networking tools...");
                    runPackageInstallation();
                });
            });
        } else {
            setStep1Progress(50, "Installing OpenSSH server & networking tools...");
            runPackageInstallation();
        }
    }

    private void runPackageInstallation() {
        ServerManager.installRequiredPackages(new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {
                android.util.Log.i("SetupActivity", "Install output: " + line);
                runOnUiThread(() -> {
                    tvStep1StatusMsg.setText(line);
                    if (line.contains("Get:") || line.contains("Hit:")) {
                        setStep1Progress(70, "Downloading packages...");
                    } else if (line.contains("Unpacking") || line.contains("Preparing")) {
                        setStep1Progress(85, "Unpacking OpenSSH server...");
                    } else if (line.contains("Setting up")) {
                        setStep1Progress(95, "Configuring OpenSSH server...");
                    }
                });
            }

            @Override
            public void onComplete(int exitCode) {
                android.util.Log.i("SetupActivity", "Install complete exitCode=" + exitCode + ", sshd=" + ServerManager.isOpenSSHInstalled());
                runOnUiThread(() -> {
                    if (exitCode == 0 || ServerManager.isOpenSSHInstalled()) {
                        setStep1Progress(100, "✓ Environment & OpenSSH Ready!");
                        btnStep1Start.setEnabled(false);
                        btnStep1Start.setText("✓ Installation Complete");
                        btnPage0Next.setEnabled(true);
                        Toast.makeText(SetupActivity.this, "Step 1 completed! Tap Next.", Toast.LENGTH_SHORT).show();
                    } else {
                        btnStep1Start.setEnabled(true);
                        btnStep1Start.setText("Retry Installation");
                        setStep1Progress(0, "Installation failed (code " + exitCode + "). Tap Retry.");
                    }
                });
            }
        });
    }

    private void applySSHPassword() {
        String pass = etPage1Password.getText() != null ? etPage1Password.getText().toString().trim() : "";
        String confirm = etPage1Confirm.getText() != null ? etPage1Confirm.getText().toString().trim() : "";

        if (pass.isEmpty()) {
            etPage1Password.setError("Password cannot be empty");
            return;
        }
        if (pass.length() < 4) {
            etPage1Password.setError("Password must be at least 4 characters");
            return;
        }
        if (!pass.equals(confirm)) {
            etPage1Confirm.setError("Passwords do not match");
            return;
        }

        btnPage1Apply.setEnabled(false);
        btnPage1Apply.setText("Applying Password...");
        tvPage1Status.setText("Starting OpenSSH daemon with your password...");

        ServerManager.setSSHPassword(pass, new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {}

            @Override
            public void onComplete(int exitCode) {
                SetupPreferences.setPasswordConfigured(SetupActivity.this, true);
                btnPage1Apply.setEnabled(true);
                btnPage1Apply.setText("✓ Password Saved");
                tvPage1Status.setText("✓ SSH Server running on port 8022.");
                btnPage1Next.setEnabled(true);
                Toast.makeText(SetupActivity.this, "SSH password saved! Tap Next.", Toast.LENGTH_SHORT).show();
            }
        });
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
            displayPage2Config(config);

        } catch (Exception e) {
            Toast.makeText(this, "Failed to read .conf file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void displayPage2Config(@NonNull WireGuardConfig config) {
        layoutPage2ConfInfo.setVisibility(View.VISIBLE);
        tvPage2Address.setText("Public IPv6: " + (config.getAddress() != null ? config.getAddress() : "-"));
        tvPage2Endpoint.setText("Endpoint: " + (config.getEndpoint() != null ? config.getEndpoint() : "-"));
        btnPage2Connect.setVisibility(View.VISIBLE);
        btnPage2Connect.setEnabled(true);
        btnPage2Connect.setText("Connect & Test Tunnel");
        tvPage2Status.setText("");
    }

    private void startWireGuardConnection() {
        if (pendingConfig == null) {
            pendingConfig = networkStore.getConfig();
        }
        if (pendingConfig == null) {
            Toast.makeText(this, "Please select a WireGuard .conf file first", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPage2Connect.setEnabled(false);
        btnPage2Connect.setText("Connecting to Tunnel...");
        tvPage2Status.setText("Starting WireGuard userspace engine and sending handshake...");
        tvPage2Status.setTextColor(Color.parseColor("#80D8FF"));

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

        // Verification safety timeout
        btnPage2Connect.postDelayed(() -> {
            if (WireGuardService.isServiceRunning()) {
                onWireGuardConnected();
            } else {
                btnPage2Connect.setEnabled(true);
                btnPage2Connect.setText("Retry Connection");
                tvPage2Status.setText("WireGuard failed to start. Check your .conf keys.");
                tvPage2Status.setTextColor(Color.parseColor("#FF5252"));
            }
        }, 3000);
    }

    private void onWireGuardConnected() {
        btnPage2Connect.setEnabled(false);
        btnPage2Connect.setText("✓ WireGuard Connected");
        btnPage2Connect.setBackgroundColor(Color.parseColor("#2E7D32"));
        tvPage2Status.setText("✓ Tunnel established with broker! Public IPv6 is active.");
        tvPage2Status.setTextColor(Color.parseColor("#00E676"));
        btnPage2Next.setEnabled(true);
    }

    private void updateSummaryDetails() {
        String username = ServerManager.getUsername();
        WireGuardConfig config = pendingConfig != null ? pendingConfig : networkStore.getConfig();
        String ipv6 = (config != null && config.getAddress() != null) ?
                config.getAddress().replaceAll("/.*", "") : "::1";

        tvPage3User.setText("Username: " + username);
        tvPage3Ipv6.setText("Public IPv6: " + ipv6);
        tvPage3SshCmd.setText("SSH Command: ssh " + username + "@" + ipv6 + " -p 2222");
    }

    private void onRestoreBackupFileSelected(@Nullable Uri uri) {
        if (uri == null) return;
        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("Restoring Sandbox")
            .setMessage("Reading backup archive from file...")
            .setCancelable(false)
            .create();
        progressDialog.show();

        BackupManager.restoreBackup(this, uri, new BackupManager.RestoreCallback() {
            @Override
            public void onProgress(String status) {
                runOnUiThread(() -> progressDialog.setMessage(status));
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    SetupPreferences.setSetupCompleted(SetupActivity.this, true);
                    new AlertDialog.Builder(SetupActivity.this)
                        .setTitle("Restore Complete")
                        .setMessage("Sandbox and configurations restored successfully! Server Hub will now restart to load all restored configurations.")
                        .setCancelable(false)
                        .setPositiveButton("Restart Now", (dialog, which) -> {
                            BackupManager.restartApp(SetupActivity.this);
                        })
                        .show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(SetupActivity.this)
                        .setTitle("Restore Failed")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show();
                });
            }
        });
    }
}
