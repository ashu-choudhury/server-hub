package com.termux.app.serverhub;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.app.models.wireguard.PortRule;
import com.termux.app.models.wireguard.WireGuardConfig;
import com.termux.app.models.wireguard.WireGuardNetworkStore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class ServerHubActivity extends AppCompatActivity implements PortRulesAdapter.OnPortRuleChangeListener {

    private WireGuardNetworkStore networkStore;
    private PortRulesAdapter portRulesAdapter;

    // Navigation & Tabs
    private BottomNavigationView bottomNav;
    private ViewFlipper viewFlipperTabs;

    // Header & Status
    private View viewStatusDot;
    private TextView tvStatusBadge;

    // Overview Tab Views
    private SwitchMaterial switchTunnel;
    private TextView tvPublicIpv6;
    private MaterialCardView cardSshConnect;
    private TextView tvSshConnectCmd;
    private MaterialButton btnRestartSshd;
    private TextView tvUsername;
    private TextView tvSshStatus;
    private TextView tvActivePortsCount;
    private TextView tvOverviewFirewallStatus;

    // Ports Tab Views
    private SwitchMaterial switchFirewall;
    private TextView tvFirewallStatusDesc;
    private RecyclerView rvPorts;
    private TextView tvEmptyPorts;
    private MaterialButton btnAddPort;

    // Settings Tab Views
    private TextView tvSettingsConfSummary;
    private MaterialButton btnImportConf;
    private MaterialButton btnChangePassword;
    private MaterialButton btnBatterySettings;
    private MaterialButton btnExportBackup;
    private MaterialButton btnRestoreBackup;
    private MaterialButton btnOpenSetup;

    private final ActivityResultLauncher<String> importConfLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onConfFileSelected);

    private final ActivityResultLauncher<String> restoreBackupLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onRestoreBackupSelected);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_hub);

        networkStore = new WireGuardNetworkStore(this);

        initViews();
        loadCurrentState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        syncTunnelState();
        refreshServerInfo();

        ServerManager.ensureSSHDRunning(new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {}
            @Override
            public void onComplete(int exitCode) {
                runOnUiThread(() -> refreshServerInfo());
            }
        });
    }

    private void initViews() {
        // Navigation
        bottomNav = findViewById(R.id.bottom_navigation);
        viewFlipperTabs = findViewById(R.id.view_flipper_tabs);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_overview) {
                viewFlipperTabs.setDisplayedChild(0);
                refreshServerInfo();
                return true;
            } else if (id == R.id.nav_ports) {
                viewFlipperTabs.setDisplayedChild(1);
                refreshPorts();
                return true;
            } else if (id == R.id.nav_settings) {
                viewFlipperTabs.setDisplayedChild(2);
                updateSettingsSummary();
                return true;
            }
            return false;
        });

        // Top Status Header
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvStatusBadge = findViewById(R.id.tv_status_badge);

        // Overview Tab
        switchTunnel = findViewById(R.id.switch_master_tunnel);
        tvPublicIpv6 = findViewById(R.id.tv_public_ipv6);
        cardSshConnect = findViewById(R.id.card_ssh_connect);
        tvSshConnectCmd = findViewById(R.id.tv_ssh_connect_cmd);
        btnRestartSshd = findViewById(R.id.btn_restart_sshd);
        tvUsername = findViewById(R.id.tv_local_user);
        tvSshStatus = findViewById(R.id.tv_ssh_status);
        tvActivePortsCount = findViewById(R.id.tv_active_ports_count);
        tvOverviewFirewallStatus = findViewById(R.id.tv_overview_firewall_status);

        // Ports Tab
        switchFirewall = findViewById(R.id.switch_firewall);
        tvFirewallStatusDesc = findViewById(R.id.tv_firewall_status_desc);
        rvPorts = findViewById(R.id.rv_port_rules);
        tvEmptyPorts = findViewById(R.id.tv_empty_ports);
        btnAddPort = findViewById(R.id.btn_add_port);

        rvPorts.setLayoutManager(new LinearLayoutManager(this));
        portRulesAdapter = new PortRulesAdapter(this);
        rvPorts.setAdapter(portRulesAdapter);

        // Settings Tab
        tvSettingsConfSummary = findViewById(R.id.tv_settings_conf_summary);
        btnImportConf = findViewById(R.id.btn_import_conf);
        btnChangePassword = findViewById(R.id.btn_change_password);
        btnBatterySettings = findViewById(R.id.btn_battery_settings);
        btnExportBackup = findViewById(R.id.btn_export_backup);
        btnRestoreBackup = findViewById(R.id.btn_restore_backup);
        btnOpenSetup = findViewById(R.id.btn_open_setup);

        // Single-tap copy actions
        cardSshConnect.setOnClickListener(v -> copyToClipboard("SSH Command", tvSshConnectCmd.getText().toString()));
        tvPublicIpv6.setOnClickListener(v -> copyToClipboard("IPv6 Address", tvPublicIpv6.getText().toString()));

        btnRestartSshd.setOnClickListener(v -> {
            btnRestartSshd.setEnabled(false);
            btnRestartSshd.setText("Restarting SSH daemon...");
            ServerManager.restartSSHD(new ServerManager.CommandListener() {
                @Override
                public void onOutput(@NonNull String line) {}
                @Override
                public void onComplete(int exitCode) {
                    runOnUiThread(() -> {
                        btnRestartSshd.setEnabled(true);
                        btnRestartSshd.setText("Restart SSH Daemon (Port 8022)");
                        refreshServerInfo();
                        Toast.makeText(ServerHubActivity.this, "SSH server restarted", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        // Robust click listener for tunnel master switch (accessible & loop-free)
        switchTunnel.setOnClickListener(v -> {
            boolean isChecked = switchTunnel.isChecked();
            if (isChecked) {
                if (networkStore.getConfig() == null) {
                    switchTunnel.setChecked(false);
                    Toast.makeText(this, "Please import a WireGuard .conf file first", Toast.LENGTH_LONG).show();
                    return;
                }
                networkStore.setTunnelActive(true);
                WireGuardService.startService(this);
                updateBadge(true);
            } else {
                networkStore.setTunnelActive(false);
                WireGuardService.stopService(this);
                updateBadge(false);
            }
        });

        // Robust click listener for firewall switch (accessible & loop-free)
        boolean fwOn = networkStore.isFirewallEnabled();
        switchFirewall.setChecked(fwOn);
        updateFirewallUI(fwOn);

        switchFirewall.setOnClickListener(v -> {
            boolean wantEnabled = switchFirewall.isChecked();
            if (!wantEnabled) {
                // User clicked to turn off: keep switch visibly ON until confirmed in dialog
                switchFirewall.setChecked(true);
                new AlertDialog.Builder(this)
                    .setTitle("⚠️ Disable Firewall Protection?")
                    .setMessage("Disabling the firewall will expose all incoming ports on your WireGuard address directly to your sandbox environment without rule restrictions.\n\nOnly proceed if you trust incoming network traffic.")
                    .setPositiveButton("Disable Firewall", (dialog, which) -> {
                        networkStore.setFirewallEnabled(false);
                        updateFirewallUI(false);
                        if (switchTunnel != null && switchTunnel.isChecked()) {
                            WireGuardService.restartService(this);
                        }
                        Toast.makeText(this, "Firewall disabled: All incoming ports are open", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Keep Protected", null)
                    .show();
            } else {
                networkStore.setFirewallEnabled(true);
                updateFirewallUI(true);
                if (switchTunnel != null && switchTunnel.isChecked()) {
                    WireGuardService.restartService(this);
                }
                Toast.makeText(this, "Firewall enabled: Traffic filtered by port rules", Toast.LENGTH_SHORT).show();
            }
        });

        btnAddPort.setOnClickListener(v -> showAddPortDialog());
        btnImportConf.setOnClickListener(v -> importConfLauncher.launch("*/*"));
        btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        btnBatterySettings.setOnClickListener(v -> openBatteryOptimizationSettings());
        btnExportBackup.setOnClickListener(v -> performExportBackup());
        btnRestoreBackup.setOnClickListener(v -> restoreBackupLauncher.launch("*/*"));

        btnOpenSetup.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_FORCE_SETUP, true);
            startActivity(intent);
        });
    }

    private void updateFirewallUI(boolean enabled) {
        if (switchFirewall != null) {
            switchFirewall.setChecked(enabled);
        }
        if (enabled) {
            if (tvFirewallStatusDesc != null) {
                tvFirewallStatusDesc.setText("● Firewall Active: Only allowed rules below forward traffic to your sandbox.");
                tvFirewallStatusDesc.setTextColor(Color.parseColor("#00E676"));
            }
            if (tvOverviewFirewallStatus != null) {
                tvOverviewFirewallStatus.setText("Active (Protected)");
                tvOverviewFirewallStatus.setTextColor(Color.parseColor("#00E676"));
            }
            if (switchFirewall != null) {
                switchFirewall.setContentDescription("Port forwarding firewall is currently enabled and protecting sandbox");
            }
        } else {
            if (tvFirewallStatusDesc != null) {
                tvFirewallStatusDesc.setText("⚠️ Firewall Disabled: All incoming ports are open to sandbox services.");
                tvFirewallStatusDesc.setTextColor(Color.parseColor("#FFB300"));
            }
            if (tvOverviewFirewallStatus != null) {
                tvOverviewFirewallStatus.setText("Disabled (Open DMZ)");
                tvOverviewFirewallStatus.setTextColor(Color.parseColor("#FFB300"));
            }
            if (switchFirewall != null) {
                switchFirewall.setContentDescription("Warning: Port forwarding firewall is currently disabled, all incoming ports are open");
            }
        }
    }

    private void loadCurrentState() {
        refreshServerInfo();
        refreshPorts();
        updateSettingsSummary();
    }

    private void refreshServerInfo() {
        String username = ServerManager.getUsername();
        if (tvUsername != null) tvUsername.setText(username);

        WireGuardConfig config = networkStore.getConfig();
        String ipv6 = (config != null && config.getAddress() != null) ?
                config.getAddress().replaceAll("/.*", "") : "No config loaded";
        if (tvPublicIpv6 != null) tvPublicIpv6.setText(ipv6);

        // Check if external SSH port rule exists
        int sshExternalPort = 2222;
        List<PortRule> rules = networkStore.getPortRules();
        for (PortRule r : rules) {
            if ("tcp".equalsIgnoreCase(r.getProtocol()) && r.getDestinationPort() == 8022) {
                sshExternalPort = r.getListenPort();
                break;
            }
        }

        String sshCmd = "ssh " + username + "@" + (config != null && config.getAddress() != null ? ipv6 : "[IPv6]") + " -p " + sshExternalPort;
        if (tvSshConnectCmd != null) {
            tvSshConnectCmd.setText(sshCmd);
        }
        if (cardSshConnect != null) {
            cardSshConnect.setContentDescription("SSH connection command: " + sshCmd + ". Tap to copy to clipboard.");
        }

        int activeCount = 0;
        for (PortRule r : rules) {
            if (r.isEnabled()) activeCount++;
        }
        if (tvActivePortsCount != null) {
            tvActivePortsCount.setText(activeCount + " Active Rule" + (activeCount == 1 ? "" : "s"));
        }

        updateFirewallUI(networkStore.isFirewallEnabled());

        new Thread(() -> {
            boolean running = ServerManager.isSSHDRunning();
            runOnUiThread(() -> {
                if (tvSshStatus != null) {
                    if (running) {
                        tvSshStatus.setText("Port 8022 (Active)");
                        tvSshStatus.setTextColor(Color.parseColor("#00E676"));
                    } else {
                        tvSshStatus.setText("Port 8022 (Stopped)");
                        tvSshStatus.setTextColor(Color.parseColor("#FF5252"));
                    }
                }
            });
        }).start();
    }

    private void syncTunnelState() {
        boolean active = WireGuardService.isServiceRunning();
        if (!active && networkStore.isTunnelActive() && networkStore.getConfig() != null) {
            WireGuardService.startService(this);
            active = true;
        }
        if (switchTunnel != null) switchTunnel.setChecked(active);
        updateBadge(active);
    }

    private void updateBadge(boolean active) {
        if (tvStatusBadge != null) {
            if (active) {
                tvStatusBadge.setText("ONLINE");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#102A16"));
                tvStatusBadge.setTextColor(Color.parseColor("#00E676"));
                tvStatusBadge.setContentDescription("Server status: Online and tunnel active");
                if (viewStatusDot != null) viewStatusDot.setBackgroundColor(Color.parseColor("#00E676"));
            } else {
                tvStatusBadge.setText("PAUSED");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#201212"));
                tvStatusBadge.setTextColor(Color.parseColor("#FF5252"));
                tvStatusBadge.setContentDescription("Server status: Paused");
                if (viewStatusDot != null) viewStatusDot.setBackgroundColor(Color.parseColor("#FF5252"));
            }
        }
    }

    private void refreshPorts() {
        List<PortRule> rules = networkStore.getPortRules();
        if (rules.isEmpty()) {
            if (tvEmptyPorts != null) tvEmptyPorts.setVisibility(View.VISIBLE);
            if (rvPorts != null) rvPorts.setVisibility(View.GONE);
        } else {
            if (tvEmptyPorts != null) tvEmptyPorts.setVisibility(View.GONE);
            if (rvPorts != null) {
                rvPorts.setVisibility(View.VISIBLE);
                portRulesAdapter.submitList(rules);
            }
        }
        updateFirewallUI(networkStore.isFirewallEnabled());
        refreshServerInfo();
    }

    private void updateSettingsSummary() {
        WireGuardConfig config = networkStore.getConfig();
        if (config != null) {
            String addr = config.getAddress() != null ? config.getAddress() : "-";
            String ep = config.getEndpoint() != null ? config.getEndpoint() : "-";
            if (tvSettingsConfSummary != null) {
                tvSettingsConfSummary.setText("Tunnel IP: " + addr + "\nEndpoint: " + ep);
            }
        } else {
            if (tvSettingsConfSummary != null) {
                tvSettingsConfSummary.setText("No WireGuard .conf file imported yet.");
            }
        }
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

            networkStore.saveConfig(config);
            loadCurrentState();
            Toast.makeText(this, "WireGuard configuration loaded!", Toast.LENGTH_SHORT).show();

            if (switchTunnel != null && switchTunnel.isChecked()) {
                WireGuardService.restartService(this);
            }

        } catch (Exception e) {
            Toast.makeText(this, "Failed to load config: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showAddPortDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_port, null);
        EditText etName = dialogView.findViewById(R.id.et_rule_name);
        RadioGroup rgProto = dialogView.findViewById(R.id.rg_protocol);
        EditText etListen = dialogView.findViewById(R.id.et_port_number);
        EditText etDest = dialogView.findViewById(R.id.et_target_port);

        new AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Add Rule", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = "Rule " + (networkStore.getPortRules().size() + 1);

                String proto = rgProto.getCheckedRadioButtonId() == R.id.rb_udp ? "udp" : "tcp";

                int listenPort;
                int destPort;
                try {
                    listenPort = Integer.parseInt(etListen.getText().toString().trim());
                    destPort = Integer.parseInt(etDest.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Please enter valid port numbers", Toast.LENGTH_SHORT).show();
                    return;
                }

                PortRule rule = new PortRule(name, proto, listenPort, "127.0.0.1", destPort, true);
                networkStore.addPortRule(rule);
                refreshPorts();

                if (switchTunnel != null && switchTunnel.isChecked()) {
                    WireGuardService.restartService(this);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showChangePasswordDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null, false);
        TextInputEditText etPass = view.findViewById(R.id.et_dialog_new_password);
        TextInputEditText etConfirm = view.findViewById(R.id.et_dialog_confirm_password);

        new AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save Password", (dialog, which) -> {
                String pass = etPass.getText() != null ? etPass.getText().toString().trim() : "";
                String confirm = etConfirm.getText() != null ? etConfirm.getText().toString().trim() : "";

                if (pass.length() < 4) {
                    Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pass.equals(confirm)) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                    return;
                }

                ServerManager.setSSHPassword(pass, new ServerManager.CommandListener() {
                    @Override
                    public void onOutput(@NonNull String line) {}
                    @Override
                    public void onComplete(int exitCode) {
                        runOnUiThread(() -> {
                            Toast.makeText(ServerHubActivity.this, "SSH password updated successfully!", Toast.LENGTH_SHORT).show();
                            refreshServerInfo();
                        });
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                String packageName = getPackageName();
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                } else {
                    intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                }
                startActivity(intent);
            } else {
                openAppDetails();
            }
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onRuleToggled(PortRule rule, boolean enabled) {
        rule.setEnabled(enabled);
        networkStore.savePortRules(portRulesAdapter.getCurrentRules());
        refreshServerInfo();
        if (switchTunnel != null && switchTunnel.isChecked()) {
            WireGuardService.restartService(this);
        }
    }

    @Override
    public void onRuleDeleted(PortRule rule) {
        networkStore.removePortRule(rule.getId());
        refreshPorts();
        if (switchTunnel != null && switchTunnel.isChecked()) {
            WireGuardService.restartService(this);
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(label, text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, label + " copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void performExportBackup() {
        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("Exporting Sandbox Backup")
            .setMessage("Compressing Linux environment, installed packages, and configs...")
            .setCancelable(false)
            .create();
        progressDialog.show();

        BackupManager.exportBackup(this, new BackupManager.BackupCallback() {
            @Override
            public void onProgress(String status) {
                runOnUiThread(() -> progressDialog.setMessage(status));
            }

            @Override
            public void onSuccess(java.io.File backupFile, String details) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(ServerHubActivity.this)
                        .setTitle("✓ Backup Exported Successfully")
                        .setMessage(details)
                        .setPositiveButton("Share Archive", (d, w) -> shareBackupFile(backupFile))
                        .setNegativeButton("OK", null)
                        .show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(ServerHubActivity.this)
                        .setTitle("Backup Failed")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show();
                });
            }
        });
    }

    private void shareBackupFile(java.io.File file) {
        try {
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/gzip");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Sandbox Backup"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open share menu: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onRestoreBackupSelected(@Nullable Uri uri) {
        if (uri == null) return;
        new AlertDialog.Builder(this)
            .setTitle("⚠️ Restore Sandbox from Backup?")
            .setMessage("Restoring from backup will replace your current Linux sandbox and settings with the archive data.\n\nAre you sure you want to proceed?")
            .setPositiveButton("Restore", (dialog, which) -> {
                AlertDialog progressDialog = new AlertDialog.Builder(this)
                    .setTitle("Restoring Sandbox")
                    .setMessage("Reading backup archive...")
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
                            loadCurrentState();
                            ServerManager.ensureSSHDRunning(null);
                            if (switchTunnel != null && switchTunnel.isChecked()) {
                                WireGuardService.restartService(ServerHubActivity.this);
                            }
                            Toast.makeText(ServerHubActivity.this, "Sandbox restored successfully!", Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            new AlertDialog.Builder(ServerHubActivity.this)
                                .setTitle("Restore Failed")
                                .setMessage(error)
                                .setPositiveButton("OK", null)
                                .show();
                        });
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
