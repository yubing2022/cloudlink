package com.agentbridge.sdk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** D-114：Capability APK provider 元数据（AgentBridge APK 扫描后随 node.capability.register 上报）。 */
public final class NodeProviderMetadata {
    public enum ProviderType { AGENTBRIDGE, CAPABILITY_APK }
    public enum Platform { ANDROID, IOS, WINDOWS, MACOS, LINUX }

    public final String providerId;
    public final ProviderType providerType;
    public final Platform platform;
    public final String appName;
    public final String version;
    public final String sdkVersion;

    public NodeProviderMetadata(String providerId, ProviderType providerType, Platform platform,
                                String appName, String version, String sdkVersion) {
        this.providerId = providerId;
        this.providerType = providerType;
        this.platform = platform;
        this.appName = appName;
        this.version = version;
        this.sdkVersion = sdkVersion;
    }

    public static NodeProviderMetadata fromContext(Context context) {
        String pkg = context.getPackageName();
        String appName = pkg;
        String version = "0";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName = String.valueOf(pm.getApplicationLabel(ai));
            version = pm.getPackageInfo(pkg, 0).versionName;
        } catch (Exception ignored) {
        }
        return new NodeProviderMetadata(pkg, ProviderType.AGENTBRIDGE, Platform.ANDROID,
                appName, version, "1.0");
    }

    /** Capability APK 侧：service 自己上报的元数据 → Bundle */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("providerId", providerId);
        b.putString("providerType", providerType.name());
        b.putString("platform", platform.name());
        b.putString("appName", appName);
        b.putString("version", version);
        b.putString("sdkVersion", sdkVersion);
        return b;
    }

    /** AgentBridge APK 侧：AIDL 查询结果 → wire dict（序列化成 JSON 字符串交给上层） */
    public static String bundleToJson(Bundle b) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("providerId", b.getString("providerId"));
            o.put("providerType", b.containsKey("providerType") ? b.getString("providerType") : "CAPABILITY_APK");
            o.put("platform", b.containsKey("platform") ? b.getString("platform") : "ANDROID");
            o.put("appName", b.getString("appName"));
            o.put("version", b.getString("version"));
            o.put("sdkVersion", b.containsKey("sdkVersion") ? b.getString("sdkVersion") : "1.0");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
