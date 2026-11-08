package com.termux.app.serverhub;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
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

    // Server Info Views
    private TextView tvUsername;
    private TextView tvSshStatus;
    private TextView tvPublicIpv6;
    private TextView tvSshConnectCmd;
    private MaterialButton btnCopyUser;
    private MaterialButton btnCopyIpv6;
    private MaterialButton btnCopySshCmd;
    private MaterialButton btnRestartSshd;
    private MaterialButton btnOpenSetup;

    // Tunnel Views
    private TextView tvStatusBadge;
    private TextView tvTunnelAddress;
    private TextView tvTunnelEndpoint;
    private SwitchMaterial switchTunnel;

    // Ports Views
    private TextView tvEmptyPorts;
    private RecyclerView rvPorts;

    // Logs Views
    private TextView tvLogs;
    private ScrollView scrollLogs;

    private final ActivityResultLauncher<String> importConfLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), this::onConfFileSelected);

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && WireGuardService.ACTION_LOG_UPDATE.equals(intent.getAction())) {
                String logLine = intent.getStringExtra(WireGuardService.EXTRA_LOG_LINE);
                if (logLine != null && tvLogs != null) {
                    tvLogs.append(logLine + "\n");
                    if (scrollLogs != null) {
                        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
                    }
                }
            }
        }
    };

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
        IntentFilter filter = new IntentFilter(WireGuardService.ACTION_LOG_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        syncTunnelState();
        refreshServerInfo();

        if (tvLogs != null && tvLogs.getText().toString().isEmpty()) {
            List<String> logs = WireGuardService.getRecentLogs();
            for (String l : logs) {
                tvLogs.append(l + "\n");
            }
            if (scrollLogs != null) {
                scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
            }
        }

        ServerManager.ensureSSHDRunning(new ServerManager.CommandListener() {
            @Override
            public void onOutput(@NonNull String line) {
                runOnUiThread(() -> {
                    if (tvLogs != null) tvLogs.append(line + "\n");
                });
            }
            @Override
            public void onComplete(int exitCode) {
                runOnUiThread(() -> refreshServerInfo());
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        // Server Info
        tvUsername = findViewById(R.id.tv_username);
        tvSshStatus = findViewById(R.id.tv_ssh_status);
        tvPublicIpv6 = findViewById(R.id.tv_public_ipv6);
        tvSshConnectCmd = findViewById(R.id.tv_ssh_connect_cmd);
        btnCopyUser = findViewById(R.id.btn_copy_user);
        btnCopyIpv6 = findViewById(R.id.btn_copy_ipv6);
        btnCopySshCmd = findViewById(R.id.btn_copy_ssh_cmd);
        btnRestartSshd = findViewById(R.id.btn_restart_sshd);
        btnOpenSetup = findViewById(R.id.btn_open_setup);

        // Tunnel
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvTunnelAddress = findViewById(R.id.tv_tunnel_address);
        tvTunnelEndpoint = findViewById(R.id.tv_tunnel_endpoint);
        switchTunnel = findViewById(R.id.switch_tunnel);

        // Ports
        tvEmptyPorts = findViewById(R.id.tv_empty_ports);
        rvPorts = findViewById(R.id.rv_ports);

        // Logs
        tvLogs = findViewById(R.id.tv_logs);
        scrollLogs = findViewById(R.id.scroll_logs);

        // Port Recycler
        rvPorts.setLayoutManager(new LinearLayoutManager(this));
        portRulesAdapter = new PortRulesAdapter(this);
        rvPorts.setAdapter(portRulesAdapter);

        // Button Listeners
        findViewById(R.id.btn_import_conf).setOnClickListener(v -> importConfLauncher.launch("*/*"));
        findViewById(R.id.btn_add_port).setOnClickListener(v -> showAddPortDialog());
        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> tvLogs.setText(""));

        btnOpenSetup.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_FORCE_SETUP, true);
            startActivity(intent);
        });

        btnCopyUser.setOnClickListener(v -> copyToClipboard("Username", tvUsername.getText().toString()));
        btnCopyIpv6.setOnClickListener(v -> copyToClipboard("IPv6", tvPublicIpv6.getText().toString()));
        btnCopySshCmd.setOnClickListener(v -> copyToClipboard("SSH Command", tvSshConnectCmd.getText().toString()));

        btnRestartSshd.setOnClickListener(v -> {
            btnRestartSshd.setEnabled(false);
            tvLogs.append("[SSH] Restarting OpenSSH daemon...\n");
            ServerManager.restartSSHD(new ServerManager.CommandListener() {
                @Override
                public void onOutput(@NonNull String line) {
                    tvLogs.append(line + "\n");
                }

                @Override
                public void onComplete(int exitCode) {
                    btnRestartSshd.setEnabled(true);
                    refreshServerInfo();
                    Toast.makeText(ServerHubActivity.this, "OpenSSH service restarted", Toast.LENGTH_SHORT).show();
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
    }

    private void loadCurrentState() {
        refreshServerInfo();

        WireGuardConfig config = networkStore.getConfig();
        if (config != null) {
            tvTunnelAddress.setText("Tunnel IP: " + (config.getAddress() != null ? config.getAddress() : "-"));
            tvTunnelEndpoint.setText("Endpoint: " + (config.getEndpoint() != null ? config.getEndpoint() : "-"));
        } else {
            tvTunnelAddress.setText("Tunnel IP: No config loaded");
            tvTunnelEndpoint.setText("Endpoint: - ");
        }

        refreshPorts();
    }

    private void refreshServerInfo() {
        String username = ServerManager.getUsername();
        tvUsername.setText(username);

        WireGuardConfig config = networkStore.getConfig();
        String ipv6 = (config != null && config.getAddress() != null) ?
                config.getAddress().replaceAll("/.*", "") : "No config loaded";
        tvPublicIpv6.setText(ipv6);

        // Check if SSH port rule exists
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

        new Thread(() -> {
            boolean running = ServerManager.isSSHDRunning();
            runOnUiThread(() -> {
                if (running) {
                    tvSshStatus.setText("Internal Port 8022 (Active)");
                    tvSshStatus.setTextColor(Color.parseColor("#B9F6CA"));
                } else {
                    tvSshStatus.setText("Internal Port 8022 (Stopped)");
                    tvSshStatus.setTextColor(Color.parseColor("#FF8A80"));
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
            tvStatusBadge.setText("TUNNEL: ACTIVE");
            tvStatusBadge.setBackgroundColor(Color.parseColor("#1B5E20"));
            tvStatusBadge.setTextColor(Color.parseColor("#B9F6CA"));
        } else {
            tvStatusBadge.setText("TUNNEL: OFF");
            tvStatusBadge.setBackgroundColor(Color.parseColor("#333333"));
            tvStatusBadge.setTextColor(Color.parseColor("#FF8A80"));
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
            .setTitle("Add Port Forwarding Rule")
            .setView(dialogView)
            .setPositiveButton("Add", (dialog, which) -> {
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
                refreshServerInfo();

                if (switchTunnel.isChecked()) {
                    WireGuardService.restartService(this);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onRuleToggled(PortRule rule, boolean enabled) {
        rule.setEnabled(enabled);
        networkStore.savePortRules(portRulesAdapter.getCurrentRules());
        if (switchTunnel.isChecked()) {
            WireGuardService.restartService(this);
        }
    }

    @Override
    public void onRuleDeleted(PortRule rule) {
        networkStore.removePortRule(rule.getId());
        refreshPorts();
        refreshServerInfo();
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
