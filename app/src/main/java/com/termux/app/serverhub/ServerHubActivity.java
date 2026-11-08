package com.termux.app.serverhub;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;
import com.termux.app.TermuxActivity;
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

    private TextView tvStatusBadge;
    private TextView tvTunnelAddress;
    private TextView tvTunnelEndpoint;
    private SwitchMaterial switchTunnel;
    private TextView tvEmptyPorts;
    private RecyclerView rvPorts;

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

    private void initViews() {
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvTunnelAddress = findViewById(R.id.tv_tunnel_address);
        tvTunnelEndpoint = findViewById(R.id.tv_tunnel_endpoint);
        switchTunnel = findViewById(R.id.switch_tunnel);
        tvEmptyPorts = findViewById(R.id.tv_empty_ports);
        rvPorts = findViewById(R.id.rv_ports);

        MaterialButton btnImportConf = findViewById(R.id.btn_import_conf);
        btnImportConf.setOnClickListener(v -> importConfLauncher.launch("*/*"));

        MaterialButton btnAddPort = findViewById(R.id.btn_add_port);
        btnAddPort.setOnClickListener(v -> showAddPortDialog());

        MaterialButton btnOpenTerminal = findViewById(R.id.btn_open_terminal);
        btnOpenTerminal.setOnClickListener(v -> {
            Intent intent = new Intent(ServerHubActivity.this, TermuxActivity.class);
            startActivity(intent);
        });

        portRulesAdapter = new PortRulesAdapter(this);
        rvPorts.setLayoutManager(new LinearLayoutManager(this));
        rvPorts.setAdapter(portRulesAdapter);

        switchTunnel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                WireGuardConfig config = networkStore.getConfig();
                if (config == null || !config.isValid()) {
                    buttonView.setChecked(false);
                    Toast.makeText(this, "Please import a valid WireGuard configuration first", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean generated = networkStore.generateWireproxyConfig();
                if (generated) {
                    networkStore.setTunnelActive(true);
                    updateStatusUI(true);
                    Toast.makeText(this, "Userspace WireGuard configuration generated and active!", Toast.LENGTH_SHORT).show();
                } else {
                    buttonView.setChecked(false);
                    Toast.makeText(this, "Failed to write WireGuard config", Toast.LENGTH_SHORT).show();
                }
            } else {
                networkStore.setTunnelActive(false);
                updateStatusUI(false);
                Toast.makeText(this, "WireGuard Tunnel Stopped", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadCurrentState() {
        WireGuardConfig config = networkStore.getConfig();
        updateConfigUI(config);

        boolean active = networkStore.isTunnelActive();
        switchTunnel.setChecked(active);
        updateStatusUI(active);

        refreshPortRules();
    }

    private void updateConfigUI(@Nullable WireGuardConfig config) {
        if (config != null && config.isValid()) {
            tvTunnelAddress.setText(String.format("Public IPv6: %s", config.getAddress()));
            tvTunnelEndpoint.setText(String.format("Endpoint: %s", config.getPeerEndpoint()));
            tvTunnelAddress.setTextColor(Color.parseColor("#80D8FF"));
        } else {
            tvTunnelAddress.setText("Public IPv6: No config loaded");
            tvTunnelEndpoint.setText("Endpoint: - ");
            tvTunnelAddress.setTextColor(Color.parseColor("#CCCCCC"));
        }
    }

    private void updateStatusUI(boolean active) {
        if (active) {
            tvStatusBadge.setText("TUNNEL: ACTIVE");
            tvStatusBadge.setTextColor(Color.parseColor("#B9F6CA"));
            tvStatusBadge.setBackgroundColor(Color.parseColor("#1B5E20"));
        } else {
            tvStatusBadge.setText("TUNNEL: OFF");
            tvStatusBadge.setTextColor(Color.parseColor("#FF8A80"));
            tvStatusBadge.setBackgroundColor(Color.parseColor("#333333"));
        }
    }

    private void refreshPortRules() {
        List<PortRule> rules = networkStore.getPortRules();
        if (rules.isEmpty()) {
            tvEmptyPorts.setVisibility(View.VISIBLE);
            rvPorts.setVisibility(View.GONE);
        } else {
            tvEmptyPorts.setVisibility(View.GONE);
            rvPorts.setVisibility(View.VISIBLE);
            portRulesAdapter.setRules(rules);
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

            String confContent = sb.toString();
            WireGuardConfig config = WireGuardConfig.parseFromConfString(confContent);

            if (config.isValid()) {
                networkStore.saveConfig(config);
                updateConfigUI(config);
                networkStore.generateWireproxyConfig();
                Toast.makeText(this, "WireGuard configuration imported successfully!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Invalid WireGuard .conf file (missing Interface or Peer)", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Toast.makeText(this, "Error reading file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddPortDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_port, null);
        EditText etName = dialogView.findViewById(R.id.et_rule_name);
        EditText etPort = dialogView.findViewById(R.id.et_port_number);
        EditText etTargetPort = dialogView.findViewById(R.id.et_target_port);
        RadioGroup rgProtocol = dialogView.findViewById(R.id.rg_protocol);

        new AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Add Port", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = "Game Server";

                String portStr = etPort.getText().toString().trim();
                int port = portStr.isEmpty() ? 7777 : Integer.parseInt(portStr);

                String targetPortStr = etTargetPort.getText().toString().trim();
                int targetPort = targetPortStr.isEmpty() ? port : Integer.parseInt(targetPortStr);

                String protocol = (rgProtocol.getCheckedRadioButtonId() == R.id.rb_tcp) ? "tcp" : "udp";

                PortRule rule = new PortRule(name, protocol, port, "127.0.0.1", targetPort, true);
                networkStore.addPortRule(rule);
                networkStore.generateWireproxyConfig();
                refreshPortRules();
                Toast.makeText(this, "Port " + port + " (" + protocol.toUpperCase() + ") added", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onRuleToggled(PortRule rule, boolean enabled) {
        rule.setEnabled(enabled);
        List<PortRule> rules = networkStore.getPortRules();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getId().equals(rule.getId())) {
                rules.set(i, rule);
                break;
            }
        }
        networkStore.savePortRules(rules);
        networkStore.generateWireproxyConfig();
    }

    @Override
    public void onRuleDeleted(PortRule rule) {
        networkStore.removePortRule(rule.getId());
        networkStore.generateWireproxyConfig();
        refreshPortRules();
        Toast.makeText(this, "Port rule removed", Toast.LENGTH_SHORT).show();
    }
}
