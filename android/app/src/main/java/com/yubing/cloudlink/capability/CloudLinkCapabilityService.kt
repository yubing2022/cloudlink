package com.yubing.cloudlink.capability

import com.agentbridge.sdk.AgentBridgeFunctionProviderService
import com.agentbridge.sdk.CapabilitySchemas
import com.agentbridge.sdk.NodeProviderMetadata
import com.yubing.cloudlink.mcp.CapError
import com.yubing.cloudlink.mcp.DeviceControlCore
import com.yubing.cloudlink.mcp.DeviceToolSchema
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import retrofit2.HttpException
import java.util.ArrayList

/**
 * AgentBridge capability provider — exposes CloudLink's device control to the
 * Mac-side agent runtime.
 *
 * 本类现在只做两件事：
 *  1. 声明能力契约（description / inputSchema / outputSchema），文案来自
 *     [DeviceToolSchema]，与本地 MCP server（PalmClaw）共用同一份。
 *  2. 把调用转发给 [DeviceControlCore]，并把结果转成 AgentBridge 的 Bundle。
 *
 * 业务规则（过滤、纠错、离线检测、错误码）全部在 [DeviceControlCore] 里，
 * 换个词说：AgentBridge 通道和本地 MCP 通道不可能再出现行为分叉。
 *
 * Passive model (ADR-0114): AgentBridgeClient scans + binds this service; the
 * service itself never dials out to AgentBridge.
 *
 * Capabilities (namespace `cloudlink`):
 *   - cloudlink.device.list@v1
 *   - cloudlink.device.get_state@v1
 *   - cloudlink.device.control@v1
 */
class CloudLinkCapabilityService : AgentBridgeFunctionProviderService() {

    /** Hilt bridge: the service is instantiated by the OS, so pull deps via EntryPoint. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun core(): DeviceControlCore
    }

    private val deps: Deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
    }

    private val providerId: String get() = packageName

    // ---------------------------------------------------------------- providers

    override fun onListProviders(): List<NodeProviderMetadata> =
        listOf(
            NodeProviderMetadata(
                providerId,
                NodeProviderMetadata.ProviderType.CAPABILITY_APK,
                NodeProviderMetadata.Platform.ANDROID,
                "CloudLink",
                appVersionName(),
                "1.0",
            ),
        )

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    // -------------------------------------------------------------- capabilities

    override fun onGetCapabilities(providerId: String): List<android.os.Bundle> {
        val caps = ArrayList<android.os.Bundle>()
        caps.add(
            CapabilitySchemas.fromJson(
                JSONObject()
                    .put("capabilityId", DeviceToolSchema.CAP_LIST)
                    .put("namespace", NAMESPACE)
                    .put("name", "device.list")
                    .put("version", "1.0")
                    .put("description", DeviceToolSchema.DESC_LIST)
                    .put("inputSchema", DeviceToolSchema.INPUT_LIST)
                    .put("outputSchema", DeviceToolSchema.OUTPUT_LIST),
            ),
        )
        caps.add(
            CapabilitySchemas.fromJson(
                JSONObject()
                    .put("capabilityId", DeviceToolSchema.CAP_GET_STATE)
                    .put("namespace", NAMESPACE)
                    .put("name", "device.get_state")
                    .put("version", "1.0")
                    .put("description", DeviceToolSchema.DESC_GET_STATE)
                    .put("inputSchema", DeviceToolSchema.INPUT_GET_STATE)
                    .put("outputSchema", DeviceToolSchema.OUTPUT_STATE),
            ),
        )
        caps.add(
            CapabilitySchemas.fromJson(
                JSONObject()
                    .put("capabilityId", DeviceToolSchema.CAP_CONTROL)
                    .put("namespace", NAMESPACE)
                    .put("name", "device.control")
                    .put("version", "1.0")
                    .put("description", DeviceToolSchema.DESC_CONTROL)
                    .put("inputSchema", DeviceToolSchema.INPUT_CONTROL)
                    .put("outputSchema", DeviceToolSchema.OUTPUT_CONTROL),
            ),
        )
        return caps
    }

    override fun onInvokeCapability(
        providerId: String,
        capabilityId: String,
        args: android.os.Bundle,
        timeoutMs: Long,
    ): android.os.Bundle {
        return try {
            val input = parseJsonArgs(args)
            val result = runBlocking {
                when (capabilityId.removePrefix("$NAMESPACE.")) {
                    "device.list@v1" -> deps.core().listDevices(
                        input.optString("area", ""),
                        input.optString("domain", ""),
                    )

                    "device.get_state@v1" -> deps.core().getState(input.optString("entity_id", ""))

                    "device.control@v1" -> deps.core().control(
                        input.optString("entity_id", ""),
                        input.optString("domain", ""),
                        input.optString("service", ""),
                        deps.core().requireDataMap(
                            if (input.has("data") && !input.isNull("data")) input.opt("data") else null,
                        ),
                    )

                    else -> throw CapError("capability_not_found", "unknown capability: $capabilityId")
                }
            }
            android.os.Bundle().apply {
                putParcelableArrayList("parts", ArrayList<android.os.Bundle>().apply { add(jsonPart(result)) })
                putBoolean("isError", false)
            }
        } catch (e: CapError) {
            fail(e.code, e.message ?: e.javaClass.simpleName)
        } catch (e: HttpException) {
            // DeviceControlCore already maps HttpException, but keep a net here:
            // an exception thrown while marshalling the result must still leave
            // the caller with a code instead of a raw stack trace.
            fail("UPSTREAM_ERROR", "CloudLink 后端异常（code=${e.code()}）")
        } catch (e: Exception) {
            fail("INTERNAL_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun parseJsonArgs(args: android.os.Bundle): JSONObject {
        val parts = args.getParcelableArrayList<android.os.Bundle>("parts")
        if (parts != null) {
            for (p in parts) {
                if ("json" == p.getString("type")) return JSONObject(p.getString("json", "{}"))
            }
        }
        return JSONObject()
    }

    private fun jsonPart(o: JSONObject): android.os.Bundle = android.os.Bundle().apply {
        putString("type", "json")
        putString("json", o.toString())
    }

    private fun fail(code: String, message: String): android.os.Bundle = android.os.Bundle().apply {
        putBoolean("isError", true)
        putString("errorCode", code)
        putString("errorMessage", message)
    }

    private companion object {
        const val NAMESPACE = "cloudlink"
        const val TAG = "CloudLinkCapability"
    }
}
