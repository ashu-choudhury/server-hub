package com.termux.app.serverhub;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;
import com.termux.app.models.wireguard.PortRule;

import java.util.ArrayList;
import java.util.List;

public class PortRulesAdapter extends RecyclerView.Adapter<PortRulesAdapter.PortRuleViewHolder> {

    public interface OnPortRuleChangeListener {
        void onRuleToggled(PortRule rule, boolean enabled);
        void onRuleDeleted(PortRule rule);
    }

    private final List<PortRule> rules = new ArrayList<>();
    private final OnPortRuleChangeListener listener;

    public PortRulesAdapter(OnPortRuleChangeListener listener) {
        this.listener = listener;
    }

    public void setRules(List<PortRule> newRules) {
        rules.clear();
        if (newRules != null) {
            rules.addAll(newRules);
        }
        notifyDataSetChanged();
    }

    public void submitList(List<PortRule> newRules) {
        setRules(newRules);
    }

    public List<PortRule> getCurrentRules() {
        return new ArrayList<>(rules);
    }

    @NonNull
    @Override
    public PortRuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_port_rule, parent, false);
        return new PortRuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PortRuleViewHolder holder, int position) {
        PortRule rule = rules.get(position);
        boolean isTcp = "tcp".equalsIgnoreCase(rule.getProtocol());
        if (holder.tvBadge != null) {
            holder.tvBadge.setText(isTcp ? "TCP" : "UDP");
            holder.tvBadge.setBackgroundColor(android.graphics.Color.parseColor(isTcp ? "#102A38" : "#281A38"));
            holder.tvBadge.setTextColor(android.graphics.Color.parseColor(isTcp ? "#80D8FF" : "#E1BEE7"));
        }

        holder.tvTitle.setText(rule.getName() + " (Port " + rule.getPort() + ")");
        holder.tvDescription.setText(String.format("Ext: %d → Int: %s:%d", rule.getPort(), rule.getTargetHost(), rule.getTargetPort()));

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(rule.isEnabled());
        holder.switchEnabled.setContentDescription(String.format("%s on port %d, %s",
            rule.getName(), rule.getPort(), rule.isEnabled() ? "enabled" : "disabled"));

        holder.switchEnabled.setOnCheckedChangeListener((btn, isChecked) -> {
            rule.setEnabled(isChecked);
            if (listener != null) listener.onRuleToggled(rule, isChecked);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onRuleDeleted(rule);
        });
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    static class PortRuleViewHolder extends RecyclerView.ViewHolder {
        final TextView tvBadge;
        final TextView tvTitle;
        final TextView tvDescription;
        final SwitchMaterial switchEnabled;
        final ImageButton btnDelete;

        PortRuleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvBadge = itemView.findViewById(R.id.tv_port_protocol_badge);
            tvTitle = itemView.findViewById(R.id.tv_port_title);
            tvDescription = itemView.findViewById(R.id.tv_port_description);
            switchEnabled = itemView.findViewById(R.id.switch_port_enabled);
            btnDelete = itemView.findViewById(R.id.btn_delete_port);
        }
    }
}
