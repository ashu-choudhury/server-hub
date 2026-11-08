package com.termux.app.models.wireguard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.Serializable;

public class WireGuardConfig implements Serializable {
    private String privateKey = "";
    private String address = "";
    private String dns = "";
    private String peerPublicKey = "";
    private String peerEndpoint = "";
    private String allowedIps = "::/0, 0.0.0.0/0";
    private String rawConfig = "";

    public WireGuardConfig() {}

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getDns() {
        return dns;
    }

    public void setDns(String dns) {
        this.dns = dns;
    }

    public String getPeerPublicKey() {
        return peerPublicKey;
    }

    public void setPeerPublicKey(String peerPublicKey) {
        this.peerPublicKey = peerPublicKey;
    }

    public String getPeerEndpoint() {
        return peerEndpoint;
    }

    public String getEndpoint() {
        return peerEndpoint;
    }

    @NonNull
    public static WireGuardConfig parse(@NonNull String confText) {
        return parseFromConfString(confText);
    }

    public void setPeerEndpoint(String peerEndpoint) {
        this.peerEndpoint = peerEndpoint;
    }

    public String getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(String allowedIps) {
        this.allowedIps = allowedIps;
    }

    public String getRawConfig() {
        return rawConfig;
    }

    public void setRawConfig(String rawConfig) {
        this.rawConfig = rawConfig;
    }

    public boolean isValid() {
        return !privateKey.isEmpty() && !address.isEmpty() && !peerPublicKey.isEmpty() && !peerEndpoint.isEmpty();
    }

    @NonNull
    public static WireGuardConfig parseFromConfString(@NonNull String confText) {
        WireGuardConfig config = new WireGuardConfig();
        config.setRawConfig(confText);

        try (BufferedReader reader = new BufferedReader(new StringReader(confText))) {
            String line;
            String currentSection = "";

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim().toLowerCase();
                    continue;
                }

                int eqIdx = line.indexOf('=');
                if (eqIdx == -1) continue;

                String key = line.substring(0, eqIdx).trim().toLowerCase();
                String value = line.substring(eqIdx + 1).trim();

                if ("interface".equals(currentSection)) {
                    switch (key) {
                        case "privatekey":
                            config.setPrivateKey(value);
                            break;
                        case "address":
                            config.setAddress(value);
                            break;
                        case "dns":
                            config.setDns(value);
                            break;
                    }
                } else if ("peer".equals(currentSection)) {
                    switch (key) {
                        case "publickey":
                            config.setPeerPublicKey(value);
                            break;
                        case "endpoint":
                            config.setPeerEndpoint(value);
                            break;
                        case "allowedips":
                            config.setAllowedIps(value);
                            break;
                    }
                }
            }
        } catch (IOException ignored) {}

        return config;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("privateKey", privateKey);
        obj.put("address", address);
        obj.put("dns", dns);
        obj.put("peerPublicKey", peerPublicKey);
        obj.put("peerEndpoint", peerEndpoint);
        obj.put("allowedIps", allowedIps);
        obj.put("rawConfig", rawConfig);
        return obj;
    }

    @Nullable
    public static WireGuardConfig fromJson(@Nullable String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            WireGuardConfig config = new WireGuardConfig();
            config.setPrivateKey(obj.optString("privateKey", ""));
            config.setAddress(obj.optString("address", ""));
            config.setDns(obj.optString("dns", ""));
            config.setPeerPublicKey(obj.optString("peerPublicKey", ""));
            config.setPeerEndpoint(obj.optString("peerEndpoint", ""));
            config.setAllowedIps(obj.optString("allowedIps", "::/0, 0.0.0.0/0"));
            config.setRawConfig(obj.optString("rawConfig", ""));
            return config;
        } catch (JSONException e) {
            return null;
        }
    }
}
