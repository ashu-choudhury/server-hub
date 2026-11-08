package com.termux.app.models.wireguard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.UUID;

public class PortRule implements Serializable {
    private String id;
    private String name;
    private String protocol; // "tcp" or "udp"
    private int port;
    private String targetHost;
    private int targetPort;
    private boolean enabled;

    public PortRule() {
        this.id = UUID.randomUUID().toString();
        this.name = "Game Server";
        this.protocol = "udp";
        this.port = 7777;
        this.targetHost = "127.0.0.1";
        this.targetPort = 7777;
        this.enabled = true;
    }

    public PortRule(String name, String protocol, int port, String targetHost, int targetPort, boolean enabled) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.protocol = protocol.toLowerCase();
        this.port = port;
        this.targetHost = targetHost != null ? targetHost : "127.0.0.1";
        this.targetPort = targetPort > 0 ? targetPort : port;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol.toLowerCase();
    }

    public int getPort() {
        return port;
    }

    public int getListenPort() {
        return port;
    }

    public int getDestinationPort() {
        return targetPort;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("protocol", protocol);
        obj.put("port", port);
        obj.put("targetHost", targetHost);
        obj.put("targetPort", targetPort);
        obj.put("enabled", enabled);
        return obj;
    }

    @Nullable
    public static PortRule fromJson(@NonNull JSONObject obj) {
        PortRule rule = new PortRule();
        rule.setId(obj.optString("id", UUID.randomUUID().toString()));
        rule.setName(obj.optString("name", "Port"));
        rule.setProtocol(obj.optString("protocol", "udp"));
        rule.setPort(obj.optInt("port", 7777));
        rule.setTargetHost(obj.optString("targetHost", "127.0.0.1"));
        rule.setTargetPort(obj.optInt("targetPort", rule.getPort()));
        rule.setEnabled(obj.optBoolean("enabled", true));
        return rule;
    }
}
