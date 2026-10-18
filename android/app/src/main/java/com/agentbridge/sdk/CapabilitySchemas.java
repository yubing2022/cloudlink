package com.agentbridge.sdk;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** CapabilitySchema ↔ Bundle 编码（AIDL 传输用）。key 契约 Capability APK 与 AgentBridge APK 共用。 */
public final class CapabilitySchemas {
    public static final String KEY_CAPABILITY_ID = "capabilityId";
    public static final String KEY_NAMESPACE = "namespace";
    public static final String KEY_NAME = "name";
    public static final String KEY_VERSION = "version";
    public static final String KEY_DESCRIPTION = "description";
    public static final String KEY_INPUT_SCHEMA = "inputSchema";
    public static final String KEY_OUTPUT_SCHEMA = "outputSchema";
    public static final String KEY_REQUIRED_PERMISSIONS = "requiredPermissions";
    public static final String KEY_ESTIMATED_DURATION_MS = "estimatedDurationMs";
    // ADR-0121 Stage 1 Step 1.2: 补 5 KEY 常量（per Brief-0121 §Step 1.2 + audit-0121-stage0 §2.2）
    public static final String KEY_SUPPORTS_STREAMING = "supportsStreaming";
    public static final String KEY_SUPPORTS_ASYNC = "supportsAsync";
    public static final String KEY_MAX_INPUT_BYTES = "maxInputBytes";
    public static final String KEY_MAX_OUTPUT_BYTES = "maxOutputBytes";
    public static final String KEY_EXAMPLES = "examples";

    private CapabilitySchemas() {}

    public static Bundle fromJson(JSONObject schema) {
        Bundle b = new Bundle();
        b.putString(KEY_CAPABILITY_ID, schema.optString(KEY_CAPABILITY_ID));
        b.putString(KEY_NAMESPACE, schema.optString(KEY_NAMESPACE));
        b.putString(KEY_NAME, schema.optString(KEY_NAME));
        b.putString(KEY_VERSION, schema.optString(KEY_VERSION, "1.0"));
        b.putString(KEY_DESCRIPTION, schema.optString(KEY_DESCRIPTION));
        b.putString(KEY_INPUT_SCHEMA, schema.optString(KEY_INPUT_SCHEMA, ""));
        b.putString(KEY_OUTPUT_SCHEMA, schema.optString(KEY_OUTPUT_SCHEMA, ""));
        b.putStringArrayList(KEY_REQUIRED_PERMISSIONS, new ArrayList<>());
        b.putInt(KEY_ESTIMATED_DURATION_MS, schema.optInt(KEY_ESTIMATED_DURATION_MS, 5000));
        // ADR-0121 Stage 1 Step 1.2: 5 个新 KEY 写入 Bundle（per F1.3-F1.8 修复）
        b.putBoolean(KEY_SUPPORTS_STREAMING, schema.optBoolean(KEY_SUPPORTS_STREAMING, false));
        b.putBoolean(KEY_SUPPORTS_ASYNC, schema.optBoolean(KEY_SUPPORTS_ASYNC, false));
        b.putInt(KEY_MAX_INPUT_BYTES, schema.optInt(KEY_MAX_INPUT_BYTES, 0));
        b.putInt(KEY_MAX_OUTPUT_BYTES, schema.optInt(KEY_MAX_OUTPUT_BYTES, 102400));
        // examples 是 JSONArray（list of object），原样存 string（接收端解析）
        b.putString(KEY_EXAMPLES, schema.optString(KEY_EXAMPLES, ""));
        return b;
    }

    public static Bundle of(String capabilityId, String namespace, String name,
                            String version, String description, String inputSchemaJson) {
        Bundle b = new Bundle();
        b.putString(KEY_CAPABILITY_ID, capabilityId);
        b.putString(KEY_NAMESPACE, namespace);
        b.putString(KEY_NAME, name);
        b.putString(KEY_VERSION, version);
        b.putString(KEY_DESCRIPTION, description);
        b.putString(KEY_INPUT_SCHEMA, inputSchemaJson == null ? "" : inputSchemaJson);
        b.putString(KEY_OUTPUT_SCHEMA, "");
        b.putStringArrayList(KEY_REQUIRED_PERMISSIONS, new ArrayList<>());
        b.putInt(KEY_ESTIMATED_DURATION_MS, 5000);
        return b;
    }
}
