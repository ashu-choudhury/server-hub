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
    private TextView tvHeroStateTitle;
    private TextView tvHeroStateDesc;
    private SwitchMaterial switchTunnel;
    private TextView tvPublicIpv6;
    private MaterialButton btnCopyIpv6;
    private TextView tvSshConnectCmd;
    private MaterialButton btnCopySshCmd;
    private MaterialButton btnRestartSshd;
    private TextView tvUsername;
    private TextView tvSshStatus;
    private TextView tvTunnelEndpoint;
    private TextView tvActivePortsCount;

    // Ports Tab Views
    private RecyclerView rvPorts;
    private TextView tvEmptyPorts;
    private MaterialButton btnAddPort;

    // Settings Tab Views
    private TextView tvSettingsConfSummary;
    private MaterialButton btnImportConf;
    private MaterialButton btnChangePassword;
    private MaterialButton btnBatterySettings;
    private MaterialButton btnOpenSetup;

    private final ActivityResultLauncher<String> importConfLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onConfFileSelected);

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
        tvHeroStateTitle = findViewById(R.id.tv_hero_state_title);
        tvHeroStateDesc = findViewById(R.id.tv_hero_state_desc);
        switchTunnel = findViewById(R.id.switch_tunnel);
        tvPublicIpv6 = findViewById(R.id.tv_public_ipv6);
        btnCopyIpv6 = findViewById(R.id.btn_copy_ipv6);
        tvSshConnectCmd = findViewById(R.id.tv_ssh_connect_cmd);
        btnCopySshCmd = findViewById(R.id.btn_copy_ssh_cmd);
        btnRestartSshd = findViewById(R.id.btn_restart_sshd);
        tvUsername = findViewById(R.id.tv_username);
        tvSshStatus = findViewById(R.id.tv_ssh_status);
        tvTunnelEndpoint = findViewById(R.id.tv_tunnel_endpoint);
        tvActivePortsCount = findViewById(R.id.tv_active_ports_count);

        // Ports Tab
        rvPorts = findViewById(R.id.rv_ports);
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
        btnOpenSetup = findViewById(R.id.btn_open_setup);

        // Actions
        btnCopyIpv6.setOnClickListener(v -> copyToClipboard("IPv6", tvPublicIpv6.getText().toString()));
        btnCopySshCmd.setOnClickListener(v -> copyToClipboard("SSH Command", tvSshConnectCmd.getText().toString()));

        btnRestartSshd.setOnClickListener(v -> {
            btnRestartSshd.setEnabled(false);
            btnRestartSshd.setText("Restarting...");
            ServerManager.restartSSHD(new ServerManager.CommandListener() {
                @Override
                public void onOutput(@NonNull String line) {}
                @Override
                public void onComplete(int exitCode) {
                    runOnUiThread(() -> {
                        btnRestartSshd.setEnabled(true);
                        btnRestartSshd.setText("Restart SSH");
                        refreshServerInfo();
                        Toast.makeText(ServerHubActivity.this, "OpenSSH service restarted", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        switchTunnel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return; // User-triggered only
            if (isChecked) {
                if (networkStore.getConfig() == null) {
                    switchTunnel.setChecked(false);
                    Toast.makeText(this, "Please import a WireGuard .conf file first", Toast.LENGTH_LONG).show();
                    return;
                }
                WireGuardService.startService(this);
                updateBadge(true);
            } else {
                WireGuardService.stopService(this);
                updateBadge(false);
            }
        });

        btnAddPort.setOnClickListener(v -> showAddPortDialog());
        btnImportConf.setOnClickListener(v -> importConfLauncher.launch("*/*"));
        btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        btnBatterySettings.setOnClickListener(v -> openBatteryOptimizationSettings());

        btnOpenSetup.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_FORCE_SETUP, true);
            startActivity(intent);
        });
    }

    private void loadCurrentState() {
        refreshServerInfo();
        refreshPorts();
        updateSettingsSummary();
    }

    private void refreshServerInfo() {
        String username = ServerManager.getUsername();
        tvUsername.setText(username);

        WireGuardConfig config = networkStore.getConfig();
        String ipv6 = (config != null && config.getAddress() != null) ?
                config.getAddress().replaceAll("/.*", "") : "No config loaded";
        tvPublicIpv6.setText(ipv6);

        if (config != null && config.getEndpoint() != null) {
            tvTunnelEndpoint.setText(config.getEndpoint());
        } else {
            tvTunnelEndpoint.setText("No Broker Loaded");
        }

        // Check if external SSH port rule exists
        int sshExternalPort = 2222;
        List<PortRule> rules = networkStore.getPortRules();
        for (PortRule r : rules) {
            if ("tcp".equalsIgnoreCase(r.getProtocol()) && r.getDestinationPort() == 8022) {
                sshExternalPort = r.getListenPort();
                break;
            }
        }

        if (config != null && config.getAddress() != null) {
            tvSshConnectCmd.setText("ssh " + username + "@" + ipv6 + " -p " + sshExternalPort);
        } else {
            tvSshConnectCmd.setText("ssh " + username + "@[IPv6] -p " + sshExternalPort);
        }

        int activeCount = 0;
        for (PortRule r : rules) {
            if (r.isEnabled()) activeCount++;
        }
        tvActivePortsCount.setText(activeCount + " Active Rule" + (activeCount == 1 ? "" : "s"));

        new Thread(() -> {
            boolean running = ServerManager.isSSHDRunning();
            runOnUiThread(() -> {
                if (running) {
                    tvSshStatus.setText("Port 8022 (Active)");
                    tvSshStatus.setTextColor(Color.parseColor("#00E676"));
                } else {
                    tvSshStatus.setText("Port 8022 (Stopped)");
                    tvSshStatus.setTextColor(Color.parseColor("#FF5252"));
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
        switchTunnel.setChecked(active);
        updateBadge(active);
    }

    private void updateBadge(boolean active) {
        if (active) {
            tvStatusBadge.setText("ONLINE");
            tvStatusBadge.setBackgroundColor(Color.parseColor("#102A16"));
            tvStatusBadge.setTextColor(Color.parseColor("#00E676"));
            viewStatusDot.setBackgroundColor(Color.parseColor("#00E676"));
            tvHeroStateTitle.setText("Server Online");
            tvHeroStateDesc.setText("WireGuard userspace tunnel connected");
        } else {
            tvStatusBadge.setText("PAUSED");
            tvStatusBadge.setBackgroundColor(Color.parseColor("#201212"));
            tvStatusBadge.setTextColor(Color.parseColor("#FF5252"));
            viewStatusDot.setBackgroundColor(Color.parseColor("#FF5252"));
            tvHeroStateTitle.setText("Server Paused");
            tvHeroStateDesc.setText("Tap switch above to activate WireGuard tunnel");
        }
    }

    private void refreshPorts() {
        List<PortRule> rules = networkStore.getPortRules();
        if (rules.isEmpty()) {
            tvEmptyPorts.setVisibility(View.VISIBLE);
            rvPorts.setVisibility(View.GONE);
        } else {
            tvEmptyPorts.setVisibility(View.GONE);
            rvPorts.setVisibility(View.VISIBLE);
            portRulesAdapter.submitList(rules);
        }
        refreshServerInfo();
    }

    private void updateSettingsSummary() {
        WireGuardConfig config = networkStore.getConfig();
        if (config != null) {
            String addr = config.getAddress() != null ? config.getAddress() : "-";
            String ep = config.getEndpoint() != null ? config.getEndpoint() : "-";
            tvSettingsConfSummary.setText("Tunnel IP: " + addr + "\nEndpoint: " + ep);
        } else {
            tvSettingsConfSummary.setText("No WireGuard .conf file imported yet.");
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

            if (switchTunnel.isChecked()) {
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

                if (switchTunnel.isChecked()) {
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
        if (switchTunnel.isChecked()) {
            WireGuardService.restartService(this);
        }
    }

    @Override
    public void onRuleDeleted(PortRule rule) {
        networkStore.removePortRule(rule.getId());
        refreshPorts();
        if (switchTunnel.isChecked()) {
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
}
