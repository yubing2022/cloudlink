package com.yubing.cloudlink.capability

import android.os.Bundle
import android.util.Log
import com.agentbridge.sdk.AgentBridgeFunctionProviderService
import com.agentbridge.sdk.CapabilitySchemas
import com.agentbridge.sdk.NodeProviderMetadata
import com.yubing.cloudlink.data.api.ApiService
import com.yubing.cloudlink.data.local.TokenStore
import com.yubing.cloudlink.data.model.DeviceActionRequest
import com.yubing.cloudlink.data.model.DeviceState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.util.ArrayList

/**
 * AgentBridge capability provider — exposes CloudLink's device control to the
 * Mac-side agent runtime, reusing the *existing* app network stack:
 *
 *   - [ApiService]      : Retrofit client (same BASE_URL as the UI)
 *   - [TokenStore]      : DataStore-backed credentials the login screen writes
 *   - AuthInterceptor   : injects Bearer token + transparently refreshes on 401
 *
 * So logging in through the normal CloudLink UI is all the setup this needs —
 * there is no second login screen and no duplicate HTTP stack.
 *
 * Passive model (ADR-0114): AgentBridgeClient scans + binds this service; the
 * service itself never dials out to AgentBridge.
 *
 * Capabilities (namespace `cloudlink`, same ids as the standalone prototype):
 *   - cloudlink.device.list@v1
 *   - cloudlink.device.get_state@v1
 *   - cloudlink.device.control@v1
 */
class CloudLinkCapabilityService : AgentBridgeFunctionProviderService() {

    /** Hilt bridge: the service is instantiated by the OS, so pull deps via EntryPoint. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun api(): ApiService
        fun tokenStore(): TokenStore
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

    override fun onGetCapabilities(providerId: String): List<Bundle> {
        val caps = ArrayList<Bundle>()

        val listSchema = JSONObject()
            .put("capabilityId", "cloudlink.device.list@v1")
            .put("namespace", NAMESPACE)
            .put("name", "device.list")
            .put("version", "1.0")
            .put(
                "description",
                "列出已接入 CloudLink 的智能家居设备及其实体（entity）。可按 area / domain 过滤。" +
                    "每个设备含 name/area 与 entities（entity_id, domain, state, attributes）。" +
                    "只返回至少含一个实体的设备；HA 集成/add-on 这类无实体的条目已被剔除。",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("area", JSONObject().put("type", "string").put("description", "房间/区域过滤（如 客厅），留空=全部"))
                        .put("domain", JSONObject().put("type", "string").put("description", "实体 domain 过滤（light/switch/climate/...），留空=全部")),
                ).toString(),
            )
        caps.add(CapabilitySchemas.fromJson(listSchema))

        val getSchema = JSONObject()
            .put("capabilityId", "cloudlink.device.get_state@v1")
            .put("namespace", NAMESPACE)
            .put("name", "device.get_state")
            .put("version", "1.0")
            .put("description", "获取单个设备实体的当前状态与属性（如灯是否开、亮度值、空调设定温度）。")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject().put(
                        "entity_id",
                        JSONObject().put("type", "string").put("description", "实体 ID，如 light.living_room"),
                    ),
                ).put("required", JSONArray().put("entity_id")).toString(),
            )
        caps.add(CapabilitySchemas.fromJson(getSchema))

        val ctrlSchema = JSONObject()
            .put("capabilityId", "cloudlink.device.control@v1")
            .put("namespace", NAMESPACE)
            .put("name", "device.control")
            .put("version", "1.0")
            .put(
                "description",
                "调用 Home Assistant service 控制设备（开/关灯、调亮度、开关插座、设定空调等），底层等价于 hass.services.call。",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("entity_id", JSONObject().put("type", "string").put("description", "目标实体 ID"))
                        .put("domain", JSONObject().put("type", "string").put("description", "实体 domain，如 light/switch/climate"))
                        .put("service", JSONObject().put("type", "string").put("description", "HA service，如 turn_on/turn_off/set_temperature"))
                        .put(
                            "data",
                            JSONObject().put("type", "object")
                                .put("description", "service 参数，如 {\"brightness\":200,\"color_temp\":300}"),
                        ),
                ).put("required", JSONArray().put("entity_id").put("domain").put("service")).toString(),
            )
        caps.add(CapabilitySchemas.fromJson(ctrlSchema))

        return caps
    }

    override fun onInvokeCapability(
        providerId: String,
        capabilityId: String,
        args: Bundle,
        timeoutMs: Long,
    ): Bundle {
        return try {
            val parts = ArrayList<Bundle>()
            val input = parseJsonArgs(args)
            when (capabilityId.removePrefix("$NAMESPACE.")) {
                "device.list@v1" -> parts.add(listDevices(input))
                "device.get_state@v1" -> parts.add(getState(input))
                "device.control@v1" -> parts.add(control(input))
                else -> return fail("capability_not_found", "unknown capability: $capabilityId")
            }
            Bundle().apply {
                putParcelableArrayList("parts", parts)
                putBoolean("isError", false)
            }
        } catch (e: CapError) {
            fail(e.code, e.message ?: e.javaClass.simpleName)
        } catch (e: HttpException) {
            val mapped = mapHttpError(e)
            fail(mapped.code, mapped.message ?: e.javaClass.simpleName)        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            fail("INTERNAL_ERROR", msg)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun listDevices(o: JSONObject): Bundle {
        val token = requireToken()
        val area = o.optString("area", "")
        val domain = o.optString("domain", "")
        val all = runBlocking { deps.api().listDevices(token) }

        // 云端 HADevice 是 HA 设备注册表的全量镜像，里面混着集成 hub 设备
        // （HACS / Xiaomi Home / Tuya bridge …）、add-on 设备（Mosquitto /
        // Samba / Supervisor …），以及实体被 category 或域过滤掉的条目。
        // 这些 entries 的 entities 为空，对控制能力没有意义，只会污染
        // 调用方的选择，所以在能力出口处剔除。
        val devices = all.filter { it.entities.isNotEmpty() }
        if (devices.size != all.size) {
            Log.i(TAG, "device.list: dropped ${all.size - devices.size} devices with 0 entities (${all.size} → ${devices.size})")
        }

        val filtered = devices.filter { d ->
            (area.isEmpty() || (d.area ?: "").equals(area, ignoreCase = true)) &&
                (domain.isEmpty() || d.entities.any { it.domain.equals(domain, ignoreCase = true) })
        }

        val arr = JSONArray()
        for (d in filtered) {
            val entities = JSONArray()
            for (e in d.entities) {
                entities.put(
                    JSONObject()
                        .put("entity_id", e.entity_id)
                        .put("domain", e.domain)
                        .put("name", e.name)
                        .put("state", e.state)
                        .put("attributes", JSONObject(e.attributes as Map<*, *>)),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("area", d.area)
                    .put("manufacturer", d.manufacturer)
                    .put("model", d.model)
                    .put("entities", entities),
            )
        }
        return jsonPart(JSONObject().put("count", filtered.size).put("devices", arr))
    }

    private fun getState(o: JSONObject): Bundle {
        val entityId = o.optString("entity_id", "")
        if (entityId.isEmpty()) {
            throw CapError(INVALID_PARAMS, "missing required param: entity_id")
        }
        val token = requireToken()

        val state = runBlocking {
            try {
                deps.api().getEntityState(entityId)
            } catch (e: HttpException) {
                // Only fall back to a full-list scan if this looks like
                // an old backend that doesn't have the /state endpoint
                // at all (404/405). For every other status code, route
                // through mapHttpError so the user sees the actual
                // backend error instead of a misleading "entity not
                // found" — previously 500/503 on /state were silently
                // swallowed and misclassified as ENTITY_NOT_FOUND.
                if (e.code() == 404 || e.code() == 405) {
                    deps.api().listDevices(token)
                        .asSequence()
                        .flatMap { it.entities.asSequence() }
                        .firstOrNull { it.entity_id == entityId }
                        ?.let {
                            DeviceState(
                                it.entity_id, it.state, it.attributes, it.lastStateChange
                            )
                        }
                        ?: throw mapHttpError(e, entityId)
                } else {
                    throw mapHttpError(e, entityId)
                }
            }
        }
        return statePart(state)
    }

    private fun control(o: JSONObject): Bundle {
        val entityId = o.optString("entity_id", "")
        val domain = o.optString("domain", "")
        val service = o.optString("service", "")
        val missing = listOfNotNull(
            "entity_id".takeIf { entityId.isEmpty() },
            "domain".takeIf { domain.isEmpty() },
            "service".takeIf { service.isEmpty() },
        )
        if (missing.isNotEmpty()) {
            throw CapError(
                INVALID_PARAMS,
                "missing required param" + (if (missing.size > 1) "s" else "") +
                    ": " + missing.joinToString(", "),
            )
        }
        val token = requireToken()
        val data = parseDataMap(o.optJSONObject("data"))

        runBlocking {
            try {
                deps.api().controlDevice(token, entityId, DeviceActionRequest(domain, service, data))
            } catch (e: HttpException) {
                throw mapHttpError(e, entityId, domain, service)
            }
        }

        // Best-effort read-back: HA echo may lag, so retry briefly before
        // falling back to "unknown". Never let a read-back failure fail the
        // control call — the command has already been accepted.
        val readBack = runBlocking {
            repeat(3) { attempt ->
                val s = runCatching { deps.api().getEntityState(entityId) }.getOrNull()
                if (s != null && (attempt == 2 || domain != "light" || s.state.isNotEmpty())) return@runBlocking s
                delay(400)
            }
            runCatching { deps.api().getEntityState(entityId) }.getOrNull()
        }

        return if (readBack != null) {
            statePart(readBack)
        } else {
            jsonPart(
                JSONObject()
                    .put("entity_id", entityId)
                    .put("state", "unknown")
                    .put("attributes", JSONObject())
                    .put("note", "command accepted; state read-back unavailable"),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Returns a ready-to-use `Bearer ...` header, or signals the UI never logged in. */
    private fun requireToken(): String {
        val t = runBlocking { deps.tokenStore().getAccessToken() }
        if (t.isNullOrEmpty()) {
            throw CapError(NOT_AUTHENTICATED, "CloudLink 未登录：请先打开 CloudLink App 完成登录，再重试")
        }
        return "Bearer $t"
    }

    /**
     * Capability-level error with a stable machine-readable code. The code
     * travels through AgentBridgeClient → Relay → Mac unchanged and becomes
     * the MCP error shell `[capabilityId] CODE: message`, so callers can
     * branch on error type instead of string-matching messages.
     */
    private class CapError(val code: String, message: String) : Exception(message)

    /**
     * Map an upstream HTTP failure from the CloudLink backend to a semantic
     * capability error. Never surfaces raw status codes: callers get a code
     * plus an actionable message.
     *
     * Tolerates both detail shapes: a plain string (current backend) and a
     * structured {"error_type", "message"} object (planned backend change).
     */
    private fun mapHttpError(
        e: HttpException,
        entityId: String = "",
        domain: String = "",
        service: String = "",
    ): CapError {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val root = runCatching { JSONObject(body) }.getOrNull()
        val detailObj = root?.optJSONObject("detail")
        val errorType = detailObj?.optString("error_type")?.takeIf { it.isNotBlank() && it != "null" }
        val detail = detailObj?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            ?: root?.optString("detail")?.takeIf { it.isNotBlank() && it != "null" }
            ?: body.takeIf { it.isNotBlank() }
        val suffix = detail?.let { "：$it" } ?: ""
        return when (e.code()) {
            401 -> CapError(NOT_AUTHENTICATED, "CloudLink 登录已失效：请打开 CloudLink App 重新登录")
            404 -> CapError(
                ENTITY_NOT_FOUND,
                if (entityId.isEmpty()) "实体不存在（可先调用 cloudlink.device.list@v1 确认实体 ID）$suffix"
                else "未找到实体 $entityId（可先调用 cloudlink.device.list@v1 确认实体 ID）$suffix",
            )
            400 -> when {
                errorType == "service_not_found" ->
                    CapError(SERVICE_NOT_FOUND, "domain $domain 不支持 service $service$suffix")
                detail?.contains("Domain mismatch", ignoreCase = true) == true ->
                    CapError(DOMAIN_MISMATCH, "domain 与实体不符：$detail")
                else -> CapError(INVALID_PARAMS, "请求被后端拒绝$suffix")
            }
            502 -> when (errorType) {
                "service_not_found" ->
                    CapError(SERVICE_NOT_FOUND, "domain $domain 不支持 service $service$suffix")
                else -> CapError(SERVICE_CALL_FAILED, "HA 服务调用失败${suffix.ifEmpty { "（HA 侧返回错误）" }}")
            }
            503 -> CapError(HA_UNAVAILABLE, "HA 实例离线，请稍后重试$suffix")
            504 -> CapError(HA_TIMEOUT, "HA 未在时限内回执动作（动作可能已生效，可用 get_state 确认）")
            else -> CapError(UPSTREAM_ERROR, "CloudLink 后端异常（code=${e.code()}）$suffix")
        }
    }

    private fun statePart(s: DeviceState): Bundle = jsonPart(
        JSONObject()
            .put("entity_id", s.entity_id)
            .put("state", s.state)
            .put("attributes", JSONObject(s.attributes as Map<*, *>))
            .put("last_state_change", s.lastStateChange ?: JSONObject.NULL),
    )

    private fun parseJsonArgs(args: Bundle): JSONObject {
        val parts = args.getParcelableArrayList<Bundle>("parts")
        if (parts != null) {
            for (p in parts) {
                if ("json" == p.getString("type")) return JSONObject(p.getString("json", "{}"))
            }
        }
        return JSONObject()
    }

    private fun parseDataMap(j: JSONObject?): Map<String, Any?> {
        if (j == null) return emptyMap()
        val out = mutableMapOf<String, Any?>()
        val keys = j.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = toPlain(j.get(k))
        }
        return out
    }

    private fun toPlain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> buildMap {
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, toPlain(v.get(k)))
            }
        }
        is JSONArray -> (0 until v.length()).map { toPlain(v.get(it)) }
        else -> v
    }

    private fun jsonPart(o: JSONObject): Bundle = Bundle().apply {
        putString("type", "json")
        putString("json", o.toString())
    }

    private fun fail(code: String, message: String): Bundle = Bundle().apply {
        putBoolean("isError", true)
        putString("errorCode", code)
        putString("errorMessage", message)
    }

    private companion object {
        const val NAMESPACE = "cloudlink"
        const val TAG = "CloudLinkCapability"

        // Stable error codes surfaced to the MCP caller (see CapError / mapHttpError).
        const val NOT_AUTHENTICATED = "NOT_AUTHENTICATED"
        const val INVALID_PARAMS = "INVALID_PARAMS"
        const val ENTITY_NOT_FOUND = "ENTITY_NOT_FOUND"
        const val DOMAIN_MISMATCH = "DOMAIN_MISMATCH"
        const val SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND"
        const val SERVICE_CALL_FAILED = "SERVICE_CALL_FAILED"
        const val HA_UNAVAILABLE = "HA_UNAVAILABLE"
        const val HA_TIMEOUT = "HA_TIMEOUT"
        const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    }
}
