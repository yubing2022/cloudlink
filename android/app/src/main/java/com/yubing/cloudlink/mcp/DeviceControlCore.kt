package com.yubing.cloudlink.mcp

import android.util.Log
import com.yubing.cloudlink.data.api.ApiService
import com.yubing.cloudlink.data.local.TokenStore
import com.yubing.cloudlink.data.model.DeviceActionRequest
import com.yubing.cloudlink.data.model.DeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备控制的唯一业务实现。
 *
 * 在此之前，`listDevices` / `getState` / `control` 三个能力的全部逻辑
 * （过滤、0 实体剔除、空结果报错、data 校验、拼写纠错、离线检测、错误码映射）
 * 都长在 [com.yubing.cloudlink.capability.CloudLinkCapabilityService] 里，
 * 只能被 AgentBridge 通道使用。现在本地 MCP server（PalmClaw）要暴露同样
 * 三个能力，若复制一份，两条通道的错误文案与纠错逻辑必然漂移。
 *
 * 所以下沉到这里：suspend 函数 + 抛 [CapError] + 返回 JSONObject，
 * 两条通道各自负责自己的传输格式（Bundle / MCP ToolResult）。
 */
@Singleton
class DeviceControlCore @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {

    // --------------------------------------------------------------- actions

    /**
     * @throws CapError INVALID_FILTER（过滤值不存在或组合为空）、NOT_AUTHENTICATED
     */
    suspend fun listDevices(areaRaw: String, domainRaw: String): JSONObject {
        val token = requireToken()
        val area = areaRaw.trim()
        val domain = domainRaw.trim().lowercase()
        val all = api.listDevices(token)

        // 云端 HADevice 是 HA 设备注册表的全量镜像，里面混着集成 hub 设备
        // （HACS / Xiaomi Home / Tuya bridge …）、add-on 设备（Mosquitto /
        // Samba / Supervisor …），以及实体被 category 或域过滤掉的条目。
        // 这些 entries 的 entities 为空，对控制能力没有意义，只会污染
        // 调用方的选择，所以在出口处剔除。
        val devices = all.filter { it.entities.isNotEmpty() }
        if (devices.size != all.size) {
            Log.i(TAG, "list: dropped ${all.size - devices.size} devices with 0 entities (${all.size} → ${devices.size})")
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
        return JSONObject().put("count", filtered.size).put("devices", arr)
    }

    /**
     * @throws CapError INVALID_PARAMS、ENTITY_NOT_FOUND、NOT_AUTHENTICATED
     */
    suspend fun getState(entityIdRaw: String): JSONObject {
        val entityId = entityIdRaw.trim().lowercase()
        if (entityId.isEmpty()) {
            throw CapError(INVALID_PARAMS, "missing required param: entity_id")
        }
        requireToken()

        val state = try {
            api.getEntityState(entityId)
        } catch (e: HttpException) {
            // Only fall back to a full-list scan if this looks like an old
            // backend that doesn't have the /state endpoint at all (404/405).
            // For every other status code, route through mapHttpError so the
            // caller sees the actual backend error instead of a misleading
            // "entity not found" — previously 500/503 on /state were silently
            // swallowed and misclassified as ENTITY_NOT_FOUND.
            if (e.code() == 404 || e.code() == 405) {
                val token = requireToken()
                api.listDevices(token)
                    .asSequence()
                    .flatMap { it.entities.asSequence() }
                    .firstOrNull { it.entity_id == entityId }
                    ?.let { DeviceState(it.entity_id, it.state, it.attributes, it.lastStateChange) }
                    ?: throw mapHttpError(e, entityId)
            } else {
                throw mapHttpError(e, entityId)
            }
        }
        return stateJson(state)
    }

    /**
     * @throws CapError INVALID_PARAMS、DOMAIN_MISMATCH、SERVICE_NOT_FOUND、
     *                  ENTITY_NOT_FOUND、DEVICE_OFFLINE、HA_UNAVAILABLE、HA_TIMEOUT
     */
    suspend fun control(
        entityIdRaw: String,
        domainRaw: String,
        serviceRaw: String,
        data: Map<String, Any?>,
    ): JSONObject {
        // Normalise to lower case: HA entity_ids are always lower case and
        // the backend compares against the DB value byte-for-byte. Without
        // this, `domain="Light"` or `LIGHT.xxx` produced DOMAIN_MISMATCH /
        // ENTITY_NOT_FOUND even though the intent was unambiguous, while
        // `service="TURN_ON"` worked (HA lower-cases it) — three fields,
        // three different rules.
        val entityId = entityIdRaw.trim().lowercase()
        val domain = domainRaw.trim().lowercase()
        val service = serviceRaw.trim().lowercase()
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

        val post = try {
            api.controlDevice(token, entityId, DeviceActionRequest(domain, service, data))
        } catch (e: HttpException) {
            throw mapHttpError(e, entityId, domain, service)
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

        return stateJson(state, ignoredDataKeys(data, state.attributes))
    }

    // --------------------------------------------------------------- helpers

    /**
     * `data` 必须是 JSON object。调用方若直接用 `optJSONObject` 取，
     * 传入 `"brightness=50"` 时会静默拿到 null —— 参数被丢掉，service 调用
     * 却照样报成功。这里统一在入口处拒绝。
     */
    fun requireDataMap(raw: Any?): Map<String, Any?> = when (raw) {
        null, JSONObject.NULL -> emptyMap()
        is Map<*, *> -> raw.entries.associate { it.key.toString() to toPlain(it.value) }
        is JSONObject -> {
            val out = mutableMapOf<String, Any?>()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = toPlain(raw.get(k))
            }
            out
        }
        is String -> throw CapError(
            INVALID_PARAMS,
            "data 必须是 JSON 对象（object），收到字符串：$raw。" +
                "正确写法：{\"brightness\":100}，不是 \"brightness=100\"",
        )
        else -> throw CapError(INVALID_PARAMS, "data 必须是 JSON 对象（object），收到 ${jsonTypeName(raw)}：$raw")
    }

    /** Returns a ready-to-use `Bearer ...` header, or signals the UI never logged in. */
    private suspend fun requireToken(): String {
        val t = tokenStore.getAccessToken()
        if (t.isNullOrEmpty()) {
            throw CapError(NOT_AUTHENTICATED, "CloudLink 未登录：请先打开 CloudLink App 完成登录，再重试")
        }
        return "Bearer $t"
    }

    private fun stateJson(s: DeviceState, warnings: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("entity_id", s.entity_id)
            .put("state", s.state)
            .put("attributes", JSONObject(s.attributes as Map<*, *>))
            .put("last_state_change", s.lastStateChange ?: JSONObject.NULL)
            .apply { if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings)) }

    private suspend fun readBackState(entityId: String): DeviceState? =
        // HA echo may lag, so retry briefly. Never let a read-back failure
        // fail the control call — the command has already been accepted.
        withContext(Dispatchers.IO) {
            repeat(3) { attempt ->
                val s = runCatching { api.getEntityState(entityId) }.getOrNull()
                if (s != null && (attempt == 2 || s.state.isNotEmpty())) return@withContext s
                delay(400)
            }
            runCatching { api.getEntityState(entityId) }.getOrNull()
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

    /**
     * Map an upstream HTTP failure from the CloudLink backend to a semantic
     * error. Never surfaces raw status codes: callers get a code plus an
     * actionable message.
     *
     * Tolerates both detail shapes: a plain string and the structured
     * {"error_type", "message"} object.
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

    private fun jsonTypeName(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> v.javaClass.simpleName
    }

    private companion object {
        const val TAG = "CloudLinkCore"

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

/**
 * 能力级错误，带稳定机器可读码。
 *
 * 该码经两条对外通道原样传给调用方：
 *  - AgentBridge：变成 MCP 错误外壳 `[capabilityId] CODE: message`
 *  - 本地 MCP：`tools/call` 结果文本里的 `error_code`
 *
 * 调用方据此分支处理，而不是匹配中文消息串。
 */
class CapError(val code: String, message: String) : Exception(message)
