package com.agentbridge.sdk;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * D-114：Capability APK 被动 Service 基类。
 *
 * Capability APK 只需：
 * 1. AndroidManifest 声明 <service> + intent-filter ACTION_CAPABILITY_PROVIDER（android:exported="true"）
 * 2. 继承本类实现 {@link #onListProviders} / {@link #onGetCapabilities} / {@link #onInvokeCapability}
 *
 * 不需要 Application.onCreate 主动调 SDK（per 2026-09-23 yubing 拍板"动态扫描"）。
 * Service 进程由 AgentBridge APK bindService lazy-load。
 */
public abstract class AgentBridgeFunctionProviderService extends Service {

    /** 返回本 APK 暴露的所有 provider 元数据 */
    protected abstract List<NodeProviderMetadata> onListProviders();

    /** 返回指定 provider 的能力 schema Bundle 列表 */
    protected abstract List<Bundle> onGetCapabilities(String providerId);

    /**
     * 执行能力。args 约定：{"parts": ArrayList<Bundle>}，part Bundle key 见 ContentParts。
     * 返回 Bundle 约定同 args + isError/errorCode/errorMessage。
     */
    protected abstract Bundle onInvokeCapability(String providerId, String capabilityId,
                                                 Bundle args, long timeoutMs);

    private final IAgentBridgeFunction.Stub binder = new IAgentBridgeFunction.Stub() {
        @Override
        public List<Bundle> listProviders() throws RemoteException {
            List<Bundle> out = new ArrayList<>();
            for (NodeProviderMetadata m : onListProviders()) {
                out.add(m.toBundle());
            }
            return out;
        }

        @Override
        public List<Bundle> getCapabilities(String providerId) throws RemoteException {
            List<Bundle> caps = onGetCapabilities(providerId);
            return caps == null ? new ArrayList<>() : caps;
        }

        @Override
        public Bundle invokeCapability(String providerId, String capabilityId,
                                       Bundle args, long timeoutMs) throws RemoteException {
            return onInvokeCapability(providerId, capabilityId, args, timeoutMs);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
