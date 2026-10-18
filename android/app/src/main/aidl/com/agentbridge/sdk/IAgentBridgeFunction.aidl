// D-114 / ADR-0114（per 2026-09-23 yubing 拍板 · Capability APK 是被动方，AgentBridge APK 是 pull 方）
// Capability APK 声明 <service> + intent-filter "com.agentbridge.sdk.ACTION_CAPABILITY_PROVIDER"，
// AgentBridge APK 启动时 PackageManager.queryIntentServices 扫描 → bindService（lazy-load）
// → 查询 providers / capabilities → invoke。
package com.agentbridge.sdk;

interface IAgentBridgeFunction {
    /** 列出本 APK 暴露的所有 capability provider（被动响应，不要求 APK 曾被启动） */
    List<android.os.Bundle> listProviders() = 1;

    /** 列出指定 provider 的能力 schema（Bundle 编码，key 见 CapabilitySchemas） */
    List<android.os.Bundle> getCapabilities(String providerId) = 2;

    /** 调用能力（AgentBridge APK 转发 Mac 端 node.capability.invoke） */
    android.os.Bundle invokeCapability(String providerId, String capabilityId,
        in android.os.Bundle args, long timeoutMs) = 3;
}
