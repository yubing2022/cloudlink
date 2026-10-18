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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
                "列出已接入 CloudLink 的智能家居设备及其实体（entity）。返回 {count, devices[]}：" +
                    "devices[] 每项含 id/name/area/manufacturer/model/entities[]，" +
                    "entities[] 每项含 entity_id/domain/name/state/attributes。" +
                    "只返回至少含一个实体的设备；HA 集成/add-on 这类无实体的条目已被剔除。" +
                    "area/domain 可省略；传入不存在的过滤值会返回 INVALID_FILTER 并列出可用值，不会静默返回空列表。" +
                    "要控制设备，先用本工具取 entity_id 与 domain，再调 cloudlink.device.control@v1。",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put(
                            "area",
                            JSONObject().put("type", "string").put(
                                "description",
                                "房间/区域过滤，大小写不敏感（如 客厅）。留空=全部。" +
                                    "未知值返回 INVALID_FILTER 并附可用 area 列表。",
                            ),
                        )
                        .put(
                            "domain",
                            JSONObject().put("type", "string").put(
                                "description",
                                "实体 domain 过滤，大小写不敏感。本账号实际出现的 domain：" +
                                    "light / switch / climate / media_player / number / select / text / " +
                                    "button / sensor / binary_sensor（以设备实际接入为准）。留空=全部。" +
                                    "未知值返回 INVALID_FILTER 并附可用 domain 列表。",
                            ),
                        ),
                ).toString(),
            )
            .put(
                "outputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("count", JSONObject().put("type", "integer"))
                        .put(
                            "devices",
                            JSONObject().put("type", "array").put(
                                "items",
                                JSONObject().put("type", "object").put(
                                    "properties",
                                    JSONObject()
                                        .put("id", JSONObject().put("type", "integer"))
                                        .put("name", JSONObject().put("type", "string"))
                                        .put("area", JSONObject().put("type", "string").put("description", "可能为 null"))
                                        .put("manufacturer", JSONObject().put("type", "string").put("description", "可能为 null"))
                                        .put("model", JSONObject().put("type", "string").put("description", "可能为 null"))
                                        .put(
                                            "entities",
                                            JSONObject().put("type", "array").put(
                                                "items",
                                                JSONObject().put("type", "object").put(
                                                    "properties",
                                                    JSONObject()
                                                        .put("entity_id", JSONObject().put("type", "string"))
                                                        .put("domain", JSONObject().put("type", "string"))
                                                        .put("name", JSONObject().put("type", "string"))
                                                        .put("state", JSONObject().put("type", "string"))
                                                        .put("attributes", JSONObject().put("type", "object")),
                                                ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                ).toString(),
            )
        caps.add(CapabilitySchemas.fromJson(listSchema))

        val getSchema = JSONObject()
            .put("capabilityId", "cloudlink.device.get_state@v1")
            .put("namespace", NAMESPACE)
            .put("name", "device.get_state")
            .put("version", "1.0")
            .put(
                "description",
                "获取单个设备实体的当前状态与属性（如灯是否开、亮度值、空调设定温度）。" +
                    "返回 {entity_id, state, attributes, last_state_change}。" +
                    "state 是 HA 原始状态值；unavailable 表示设备已离线（此时 control 不会真正生效）。",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject().put(
                        "entity_id",
                        JSONObject().put("type", "string").put(
                            "description",
                            "实体 ID。必须先调用 cloudlink.device.list@v1 获取（形如 " +
                                "light.yeelink_cn_1208334138_mbulb3_s_2，全小写带 domain 前缀），不要凭猜测构造。" +
                                "大小写不敏感（内部统一转小写）。",
                        ),
                    ),
                ).put("required", JSONArray().put("entity_id")).toString(),
            )
            .put(
                "outputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("entity_id", JSONObject().put("type", "string"))
                        .put("state", JSONObject().put("type", "string"))
                        .put("attributes", JSONObject().put("type", "object"))
                        .put("last_state_change", JSONObject().put("type", "string").put("description", "ISO8601，可能为 null")),
                ).toString(),
            )
        caps.add(CapabilitySchemas.fromJson(getSchema))

        val ctrlSchema = JSONObject()
            .put("capabilityId", "cloudlink.device.control@v1")
            .put("namespace", NAMESPACE)
            .put("name", "device.control")
            .put("version", "1.0")
            .put(
                "description",
                "调用 Home Assistant service 控制设备（开关灯、调亮度、开关插座、设定空调等），" +
                    "底层等价于 hass.services.call。" +
                    "成功时返回动作后的 {entity_id, state, attributes, last_state_change}，" +
                    "state=unavailable 会以 DEVICE_OFFLINE 报错（HA 接收了指令但设备未执行）；" +
                    "data 里疑似拼错的字段名会出现在 warnings 数组里。" +
                    "参数大小写不敏感，但 domain 必须与 entity_id 前缀一致（服务端只校验、不推断）。",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put(
                            "entity_id",
                            JSONObject().put("type", "string").put(
                                "description",
                                "目标实体 ID，形如 light.yeelink_cn_1208334138_mbulb3_s_2（全小写）。" +
                                    "必须先调用 cloudlink.device.list@v1 获取，不要凭猜测构造。大小写不敏感。",
                            ),
                        )
                        .put(
                            "domain",
                            JSONObject().put("type", "string").put(
                                "description",
                                "实体 domain，必须与 entity_id 的前缀一致（light.xxx → light，switch.xxx → switch）。" +
                                    "服务端只校验不推断，不一致返回 DOMAIN_MISMATCH。大小写不敏感。" +
                                    "常见值：light / switch / climate / media_player / number / select / text / button。",
                            ),
                        )
                        .put(
                            "service",
                            JSONObject().put("type", "string").put(
                                "description",
                                "HA service 名，大小写不敏感。各 domain 常用 service：" +
                                    "light、switch → turn_on / turn_off / toggle；" +
                                    "climate → set_temperature / set_hvac_mode / turn_on / turn_off；" +
                                    "media_player → volume_set / media_play / media_pause / turn_on / turn_off；" +
                                    "number → set_value；select → select_option；text → set_value；button → press。" +
                                    "sensor / binary_sensor 为只读域，没有可调用的 service。" +
                                    "传错时返回 SERVICE_NOT_FOUND，并在 message 里附该 domain 可用的 service。",
                            ),
                        )
                        .put(
                            "data",
                            JSONObject().put("type", "object")
                                .put(
                                    "description",
                                    "service 参数，必须是 JSON 对象（object），不能是字符串。" +
                                        "key 必须是该 service 的真实字段名：HA 对未知字段多数情况静默忽略" +
                                        "（部分 service 会直接报错，如 light 会提示 did you mean ...）；" +
                                        "疑似拼错时返回结果里的 warnings 也会提示。" +
                                        "例：{\"brightness\":200,\"color_temp_kelvin\":3000}。" +
                                        "边界：brightness 有效范围 1-255，超出由 HA clamp 到 255；brightness=0 等同关灯。",
                                ),
                        ),
                ).put("required", JSONArray().put("entity_id").put("domain").put("service")).toString(),
            )
            .put(
                "outputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("entity_id", JSONObject().put("type", "string"))
                        .put("state", JSONObject().put("type", "string"))
                        .put("attributes", JSONObject().put("type", "object"))
                        .put("last_state_change", JSONObject().put("type", "string").put("description", "ISO8601，可能为 null"))
                        .put(
                            "warnings",
                            JSONObject().put("type", "array").put(
                                "items", JSONObject().put("type", "string"),
                            ).put("description", "仅当 data 里有疑似拼错的字段名时出现"),
                        ),
                ).toString(),
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
            fail(mapped.code, mapped.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            fail("INTERNAL_ERROR", msg)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun listDevices(o: JSONObject): Bundle {
        val token = requireToken()
        val area = o.optString("area", "").trim()
        val domain = o.optString("domain", "").trim().lowercase()
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

        val availableAreas = devices.mapNotNull { it.area?.trim() }
            .filter { it.isNotEmpty() }.distinct().sorted()
        val availableDomains = devices.flatMap { it.entities }
            .map { it.domain.lowercase() }.distinct().sorted()

        // 过滤值写错时，旧实现返回 {"count":0,"devices":[]} —— 和「这个房间
        // 真的没有设备」完全无法区分，调用方会把拼写错误当成事实结论。
        // 现在直接报错并列出真实存在的取值。
        if (area.isNotEmpty() && availableAreas.none { it.equals(area, ignoreCase = true) }) {
            throw CapError(
                INVALID_FILTER,
                if (availableAreas.isEmpty()) {
                    "area=$area 无法匹配：该账号下没有任何设备（请先在 CloudLink App 完成登录与 HA 绑定）"
                } else {
                    "area=$area 不存在。可用 area：${availableAreas.joinToString(" / ")}；不传 area 表示全部"
                },
            )
        }
        if (domain.isNotEmpty() && domain !in availableDomains) {
            throw CapError(
                INVALID_FILTER,
                if (availableDomains.isEmpty()) {
                    "domain=$domain 无法匹配：该账号下没有任何实体"
                } else {
                    "domain=$domain 不存在。可用 domain：${availableDomains.joinToString(" / ")}；不传 domain 表示全部"
                },
            )
        }

        val filtered = devices.filter { d ->
            (area.isEmpty() || (d.area ?: "").equals(area, ignoreCase = true)) &&
                (domain.isEmpty() || d.entities.any { it.domain.equals(domain, ignoreCase = true) })
        }

        // 单个过滤值合法、但组合结果为空（如「客厅里没有 light」）时，
        // 同样说清楚是哪一维不匹配，而不是回一个空列表。
        // 不带过滤条件时保持原语义：空列表就是空列表（账号下确实没设备）。
        if (filtered.isEmpty() && (area.isNotEmpty() || domain.isNotEmpty())) {
            val domainsInArea = devices
                .filter { area.isEmpty() || (it.area ?: "").equals(area, ignoreCase = true) }
                .flatMap { it.entities }.map { it.domain.lowercase() }.distinct().sorted()
            throw CapError(
                INVALID_FILTER,
                "area=${area.ifEmpty { "（全部）" }} 下没有 domain=$domain 的实体。" +
                    "该范围内可用 domain：${domainsInArea.joinToString(" / ")}",
            )
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
        val entityId = o.optString("entity_id", "").trim().lowercase()
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
        // Normalise to lower case: HA entity_ids are always lower case and
        // the backend compares against the DB value byte-for-byte. Without
        // this, `domain="Light"` or `LIGHT.xxx` produced DOMAIN_MISMATCH /
        // ENTITY_NOT_FOUND even though the intent was unambiguous, while
        // `service="TURN_ON"` worked (HA lower-cases it) — three fields,
        // three different rules.
        val entityId = o.optString("entity_id", "").trim().lowercase()
        val domain = o.optString("domain", "").trim().lowercase()
        val service = o.optString("service", "").trim().lowercase()
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
        val data = requireDataObject(o)
        val token = requireToken()

        val post = runBlocking {
            try {
                deps.api().controlDevice(token, entityId, DeviceActionRequest(domain, service, data))
            } catch (e: HttpException) {
                throw mapHttpError(e, entityId, domain, service)
            }
        }

        // The backend already returns the state the plugin read from
        // hass.states right after the blocking service call, which is
        // fresher than a follow-up GET /state (that reads the DB replica).
        // Only poll when the reply carries no usable state — i.e. an older
        // plugin that doesn't echo state back.
        val state = if (post.state.isBlank() || post.state.equals("unknown", ignoreCase = true)) {
            readBackState(entityId) ?: post
        } else {
            post
        }

        // HA accepts a service call for an unavailable entity and returns
        // without raising, so nothing on the device actually happens. The
        // old code then reported plain success, and an agent would tell the
        // user "done" — the exact opposite of what happened. Surface it.
        if (state.state.equals("unavailable", ignoreCase = true)) {
            throw CapError(
                DEVICE_OFFLINE,
                "设备 $entityId 当前离线（state=unavailable）：HA 已接收指令，但设备未执行。" +
                    "请检查设备电源/网络后重试，或用 cloudlink.device.get_state@v1 确认是否恢复在线",
            )
        }

        return statePart(state, ignoredDataKeys(data, state.attributes))
    }

    /**
     * `data` must be a JSON object. It used to be read with
     * `optJSONObject("data")`, which quietly returns null when the caller
     * passes `"brightness=50"` — the parameters were dropped on the floor
     * and the service call ran with no data at all, then reported success.
     */
    private fun requireDataObject(o: JSONObject): Map<String, Any?> {
        if (!o.has("data") || o.isNull("data")) return emptyMap()
        val raw = o.opt("data")
        if (raw is JSONObject) return parseDataMap(raw)
        throw CapError(
            INVALID_PARAMS,
            "data 必须是 JSON 对象（object），收到 ${jsonTypeName(raw)}：$raw。" +
                "正确写法：{\"brightness\":100}，不是 \"brightness=100\"",
        )
    }

    private fun jsonTypeName(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> v.javaClass.simpleName
    }

    private fun readBackState(entityId: String): DeviceState? = runBlocking {
        // HA echo may lag, so retry briefly. Never let a read-back failure
        // fail the control call — the command has already been accepted.
        withContext(Dispatchers.IO) {
            repeat(3) { attempt ->
                val s = runCatching { deps.api().getEntityState(entityId) }.getOrNull()
                if (s != null && (attempt == 2 || s.state.isNotEmpty())) return@withContext s
                delay(400)
            }
            runCatching { deps.api().getEntityState(entityId) }.getOrNull()
        }
    }

    /**
     * HA silently ignores unknown service-data keys, so a typo like
     * `{"brightnes": 100}` "succeeds" without doing anything. We can't
     * validate against the per-service field schema, but we can spot the
     * realistic failure mode — a key that is one or two edits away from an
     * attribute name the entity actually has — and return a non-fatal hint.
     */
    private fun ignoredDataKeys(data: Map<String, Any?>, attrs: Map<String, Any?>): List<String> {
        if (data.isEmpty() || attrs.isEmpty()) return emptyList()
        val attrNames = attrs.keys.filter { it.length >= 4 }
        val out = ArrayList<String>()
        for (key in data.keys) {
            val k = key.lowercase()
            if (k.length < 4) continue
            if (attrs.keys.any { it.equals(k, ignoreCase = true) }) continue
            val near = attrNames.minByOrNull { distance(k, it.lowercase()) } ?: continue
            if (distance(k, near.lowercase()) <= 2) {
                out.add("$key 不是该实体的属性名，最接近的是 '$near'；HA 会静默忽略未知字段，请核对后再试")
            }
        }
        return out
    }

    /** Levenshtein distance, used only for the typo hint above. */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
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
                    CapError(SERVICE_NOT_FOUND, "domain $domain 不支持 service $service$suffix${serviceHint(domain)}")
                detail?.contains("Domain mismatch", ignoreCase = true) == true ->
                    CapError(DOMAIN_MISMATCH, "domain 与实体不符：$detail")
                else -> CapError(INVALID_PARAMS, "请求被后端拒绝$suffix")
            }
            502 -> when (errorType) {
                "service_not_found" ->
                    CapError(SERVICE_NOT_FOUND, "domain $domain 不支持 service $service$suffix${serviceHint(domain)}")
                else -> CapError(SERVICE_CALL_FAILED, "HA 服务调用失败${suffix.ifEmpty { "（HA 侧返回错误）" }}")
            }
            503 -> CapError(HA_UNAVAILABLE, "HA 实例离线，请稍后重试$suffix")
            504 -> CapError(HA_TIMEOUT, "HA 未在时限内回执动作（动作可能已生效，可用 get_state 确认）")
            else -> CapError(UPSTREAM_ERROR, "CloudLink 后端异常（code=${e.code()}）$suffix")
        }
    }

    /**
     * "domain X doesn't support service Y" alone leaves the caller with no
     * way to recover — there is no other tool that lists services, so the
     * only option was brute-force guessing. Append the services that domain
     * actually accepts.
     */
    private fun serviceHint(domain: String): String {
        if (domain in READ_ONLY_DOMAINS) {
            return "。domain $domain 是只读域，没有可调用的 service；读状态请用 cloudlink.device.get_state@v1"
        }
        val known = COMMON_SERVICES[domain] ?: return ""
        return "。domain $domain 常用 service：${known.joinToString(" / ")}"
    }

    private fun statePart(s: DeviceState, warnings: List<String> = emptyList()): Bundle = jsonPart(
        JSONObject()
            .put("entity_id", s.entity_id)
            .put("state", s.state)
            .put("attributes", JSONObject(s.attributes as Map<*, *>))
            .put("last_state_change", s.lastStateChange ?: JSONObject.NULL)
            .apply { if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings)) },
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
        const val INVALID_FILTER = "INVALID_FILTER"
        const val ENTITY_NOT_FOUND = "ENTITY_NOT_FOUND"
        const val DOMAIN_MISMATCH = "DOMAIN_MISMATCH"
        const val SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND"
        const val SERVICE_CALL_FAILED = "SERVICE_CALL_FAILED"
        const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
        const val HA_UNAVAILABLE = "HA_UNAVAILABLE"
        const val HA_TIMEOUT = "HA_TIMEOUT"
        const val UPSTREAM_ERROR = "UPSTREAM_ERROR"

        /** Read-only HA domains: they expose state but no services worth calling. */
        val READ_ONLY_DOMAINS = setOf("sensor", "binary_sensor", "device_tracker", "weather", "person")

        /**
         * Commonly used services per domain. Static on purpose: the backend
         * doesn't expose HA's /api/services, and this list only ever appears
         * inside an error message as a hint (never used to validate).
         */
        val COMMON_SERVICES = mapOf(
            "light" to listOf("turn_on", "turn_off", "toggle"),
            "switch" to listOf("turn_on", "turn_off", "toggle"),
            "fan" to listOf("turn_on", "turn_off", "set_percentage"),
            "cover" to listOf("open_cover", "close_cover", "set_cover_position"),
            "lock" to listOf("lock", "unlock"),
            "climate" to listOf("turn_on", "turn_off", "set_temperature", "set_hvac_mode"),
            "media_player" to listOf("turn_on", "turn_off", "volume_set", "media_play", "media_pause"),
            "number" to listOf("set_value"),
            "select" to listOf("select_option"),
            "text" to listOf("set_value"),
            "button" to listOf("press"),
            "vacuum" to listOf("start", "stop", "return_to_base"),
        )
    }
}
