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
     * 列出设备（**紧凑版**，无过滤参数）。
     *
     * 为什么做成「紧凑 + 零参数」，而不是把全量实体一次倒给调用方：
     *
     * 1) **上下文**：HA 的实体清单里塞满与决策无关的噪音（打印机的碳粉余量、
     *    重复的 friendly_name、supported_features …）。实测本机 14 台设备 / 91 个实体，
     *    全量返回 **22KB ≈ 8~11K token** —— 一次列表就能把端侧小模型的上下文吃掉大半，
     *    之后它就开始胡说（反问用户要参数、叫错工具名）。
     *    现在只返回「设备 + 能控制的实体」，实测降到 ~5KB；要属性用 [getState]
     *    单查，要只读实体用 [listEntities] 钻取。
     *
     * 2) **别让模型填空**：过滤参数（area/domain）曾经是可选的，但模型看到
     *    schema 里有两个 string 属性就会认为「必须填」，于是反问用户要区域名。
     *    把过滤彻底移出 list，模型就没有可问的东西了 —— 区域信息本来就在
     *    每台设备的 area 字段里，模型自己就能挑。
     *
     * @throws CapError NOT_AUTHENTICATED
     */
    suspend fun listDevices(): JSONObject {
        val token = requireToken()
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

        val arr = JSONArray()
        for (d in devices) {
            val controllable = JSONArray()
            var readonly = 0
            for (e in d.entities) {
                if (e.domain.lowercase() in READ_ONLY_DOMAINS) {
                    readonly++
                    continue
                }
                controllable.put(
                    JSONObject()
                        .put("entity_id", e.entity_id)
                        .put("domain", e.domain)
                        .put("name", e.name)
                        .put("state", e.state),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("area", d.area)
                    .put("manufacturer", d.manufacturer)
                    .put("model", d.model)
                    .put("domains", JSONArray(d.entities.map { it.domain.lowercase() }.distinct().sorted()))
                    .put("entity_count", d.entities.size)
                    .put("readonly_count", readonly)
                    .put("entities", controllable),
            )
        }
        return JSONObject().put("count", devices.size).put("devices", arr)
    }

    /**
     * 列出实体（含只读传感器），可按 **device_name / area / domain** 过滤，
     * 三个参数**全部可选**，全都不传就是全部实体。
     *
     * 为什么要把过滤放在这里、而不是放回 [listDevices]：
     * - [listDevices] 是「有哪些设备」的入口，必须**零参数**。一旦它带上可填属性，
     *   弱模型就会认为必须填，转而去反问用户要区域（实测踩过）。
     * - 过滤能力本身不能丢 —— 大模型做「查客厅的灯」这类检索时，
     *   一次 `device_entities(area="客厅", domain="light")` 就够，比
     *   list → entities 两轮更干净。
     *
     * 匹配规则：device_name 先精确、再忽略大小写、最后包含；
     * area / domain 忽略大小写精确匹配。不中就把可用取值列出来
     * （别让调用方把拼写错误当成事实结论）。
     *
     * @throws CapError ENTITY_NOT_FOUND（设备名不存在）、INVALID_FILTER（区域/domain 不存在）
     */
    suspend fun listEntities(deviceNameRaw: String, areaRaw: String, domainRaw: String): JSONObject {
        val token = requireToken()
        val wantName = deviceNameRaw.trim()
        val wantArea = areaRaw.trim()
        val wantDomain = domainRaw.trim().lowercase()

        val devices = api.listDevices(token).filter { it.entities.isNotEmpty() }

        if (wantName.isNotEmpty()) {
            val hit = matchDeviceByName(devices, wantName)
            if (hit == null) {
                val names = devices.map { it.name }.sorted()
                throw CapError(
                    ENTITY_NOT_FOUND,
                    "device_name=$wantName 不存在。可用设备名：${names.joinToString(" / ")}",
                )
            }
            return JSONObject()
                .put("count", hit.entities.size)
                .put("devices", JSONArray().put(deviceJson(hit, hit.entities)))
        }

        // 按区域过滤：列的是「有实体的设备所属区域」，与 listDevices 的口径一致。
        if (wantArea.isNotEmpty()) {
            val areas = devices.mapNotNull { it.area?.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            if (areas.none { it.equals(wantArea, ignoreCase = true) }) {
                throw CapError(
                    INVALID_FILTER,
                    "area=$wantArea 不存在。可用 area：${areas.joinToString(" / ")}；不传 area 表示全部",
                )
            }
        }
        if (wantDomain.isNotEmpty()) {
            val domains = devices.flatMap { it.entities }.map { it.domain.lowercase() }.distinct().sorted()
            if (wantDomain !in domains) {
                throw CapError(
                    INVALID_FILTER,
                    "domain=$wantDomain 不存在。可用 domain：${domains.joinToString(" / ")}；不传 domain 表示全部",
                )
            }
        }

        val matched = devices.filter { d ->
            (wantArea.isEmpty() || (d.area ?: "").equals(wantArea, ignoreCase = true)) &&
                d.entities.any { wantDomain.isEmpty() || it.domain.lowercase() == wantDomain }
        }
        val arr = JSONArray()
        var total = 0
        for (d in matched) {
            val picked = d.entities.filter { wantDomain.isEmpty() || it.domain.lowercase() == wantDomain }
            total += picked.size
            arr.put(deviceJson(d, picked))
        }
        return JSONObject().put("count", total).put("devices", arr)
    }

    /** 设备名匹配：先精确、再忽略大小写、最后包含。与 [listEntities] 共用同一套规则。 */
    private fun matchDeviceByName(
        devices: List<com.yubing.cloudlink.data.model.HomeDevice>,
        wantName: String,
    ): com.yubing.cloudlink.data.model.HomeDevice? =
        devices.firstOrNull { it.name == wantName }
            ?: devices.firstOrNull { it.name.equals(wantName, ignoreCase = true) }
            ?: devices.firstOrNull { it.name.contains(wantName, ignoreCase = true) }

    /**
     * 按名称**唯一地**解析出一台设备。
     *
     * ## 为什么需要它
     *
     * [listEntities] 遇到多个同名设备时**只取第一个**（[matchDeviceByName] 返回首个命中）
     * —— 对本地 MCP server 来说这是危险的：模型问「落地灯」，如果家里有两台
     * 「落地灯」，会静默选中错误的那台，然后去控制它。
     *
     * 本地 MCP 层用它做**消歧**：命中唯一就采用；命中多个就把候选列出来，
     * 让模型改用 `area` 区分 —— **不替模型猜**。
     *
     * ## 名字形态：模型抄什么我们都能接住
     *
     * 真机事故（2026-11-04）：`device_list` 的返回被模型摊平成 27 个标量，
     * 名字与区域的配对丢失；第二轮模型干脆不调工具，凭残骸臆造设备。
     * 改成纯文本清单后，模型的最优行为是**整行照抄**「- 落地灯（客厅）」——
     * 于是 `device_name` 收到的是带列表符、带区域括号的整行，不是干净的「落地灯」。
     *
     * 所以这里不要求模型「编辑」，只要求它「抄」：
     * [deviceRefCandidates] 把原始输入拆成若干个 (名字, 区域) 候选，
     * 逐个尝试匹配，**括号里的区域直接当消歧依据** ——
     * 「落地灯（客厅）」比「落地灯」更精确，不需要模型再填一次 area。
     *
     * ## 对其它通道的影响
     *
     * 这是**新增**的只读查询，不改变 [listDevices] / [listEntities] / [getState] /
     * [control] 任何现有方法的行为，AgentBridge 通道完全不受影响。
     *
     * @throws CapError INVALID_PARAMS（缺 device_name）、ENTITY_NOT_FOUND（不存在或有多个同名）
     */
    suspend fun resolveDevice(deviceNameRaw: String, areaRaw: String = ""): ResolvedDevice {
        val candidates = deviceRefCandidates(deviceNameRaw, areaRaw)
        if (candidates.isEmpty()) {
            throw CapError(INVALID_PARAMS, "缺少必填参数：device_name")
        }
        val token = requireToken()
        val devices = api.listDevices(token)
            .filter { it.entities.isNotEmpty() }
            // 伪设备（名字其实是 entity_id）不出现在模型可见的任何地方，
            // 包括「设备名不存在」错误里列出的候选。
            .filterNot { isEntityIdLike(it.name) }

        // 逐个候选尝试：先精确、再忽略大小写、最后包含。
        // 命中唯一就返回；命中多个先记下来，继续试下一个候选
        // （括号里的区域往往能把同名设备区分开）。
        var ambiguous: List<com.yubing.cloudlink.data.model.HomeDevice>? = null
        var ambiguousName = ""

        for ((wantName, wantArea) in candidates) {
            // 先用 area 收窄 —— 用户给了区域时，同名设备就能被区分开。
            val scoped = if (wantArea.isEmpty()) {
                devices
            } else {
                devices.filter { (it.area ?: "").equals(wantArea, ignoreCase = true) }
            }
            if (scoped.isEmpty()) continue

            val exact = scoped.filter { it.name == wantName }
                .ifEmpty { scoped.filter { it.name.equals(wantName, ignoreCase = true) } }
            val hits = if (exact.isNotEmpty()) {
                exact
            } else {
                scoped.filter { it.name.contains(wantName, ignoreCase = true) }
            }
            if (hits.isEmpty()) continue
            if (hits.size == 1) {
                val hit = hits.first()
                return ResolvedDevice(name = hit.name, area = hit.area, entities = hit.entities)
            }
            if (ambiguous == null) {
                ambiguous = hits
                ambiguousName = wantName
            }
        }

        if (ambiguous != null) {
            val where = ambiguous.joinToString(" / ") { d -> "${d.name}（${d.area ?: "未知区域"}）" }
            throw CapError(
                ENTITY_NOT_FOUND,
                "有 ${ambiguous.size} 个设备叫「$ambiguousName」：$where。" +
                    "请加上 area 参数区分后重试。",
            )
        }

        val names = devices.map { it.name }.distinct().sorted()
        throw CapError(
            ENTITY_NOT_FOUND,
            "device_name=${deviceNameRaw.trim()} 不存在。可用设备名：${names.joinToString(" / ")}",
        )
    }

    /**
     * 把模型填的 `device_name` 拆成若干个「(设备名, 区域)」候选，按可靠度排序。
     *
     * ## 为什么需要
     *
     * 模型最省力的做法是**把 `device_list` 返回的那一行原样抄进参数**：
     * 「- 落地灯（客厅）」。这行里带着列表符、区域括号，有时还带 markdown 强调号。
     * 要求模型「编辑」成干净的「落地灯」是在为难它 —— 而它一旦编辑就会出错
     * （实测把名字抄错、漏字、带上前缀）。
     *
     * 所以服务端负责拆：候选从「最接近原样」逐步退化到「最宽松」，
     * 第一个能唯一命中的候选直接采用。
     *
     * 候选来源：
     * 1. 原样（设备真名就叫「落地灯（客厅）」时命中这个）；
     * 2. 剥掉列表符 / markdown 强调 / 引号；
     * 3. 剥掉尾部括号，括号里的内容**当区域用** —— 这让「落地灯（客厅）」
     *    直接命中客厅那台，同名设备不用模型再补 area；
     * 4. 剥掉尾部括号后仍保留显式 area（显式参数优先于括号内容）。
     */
    private fun deviceRefCandidates(rawName: String, rawArea: String): List<Pair<String, String>> {
        val explicitArea = rawArea.trim()
        val out = LinkedHashMap<String, Pair<String, String>>()

        fun put(name: String, area: String) {
            val n = name.trim()
            if (n.isEmpty()) return
            val key = "$n $area"
            out.putIfAbsent(key, n to area)
        }

        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return emptyList()

        // ① 原样
        put(trimmed, explicitArea)

        // ② 剥 markdown 残留：列表符、强调号、引号
        var cleaned = trimmed
            .replace(Regex("^[\\*_`\"'“”‘’]+"), "")
            .replace(Regex("[\\*_`\"'“”‘’]+$"), "")
            .trim()
        cleaned = cleaned
            .replace(Regex("^[-*+•]+\\s*"), "")
            .replace(Regex("^\\d+[.、)]\\s*"), "")
            .trim()
        put(cleaned, explicitArea)

        // ③ 剥尾部括号：名字（区域）
        val paren = Regex("^(.*?)\\s*[（(]\\s*([^（()）]*?)\\s*[）)]$").find(cleaned)
        if (paren != null) {
            val name = paren.groupValues[1].trim()
            val areaInParen = paren.groupValues[2].trim()
            if (name.isNotEmpty()) {
                // 显式 area 优先；没有显式 area 时用括号里的区域（顺带完成消歧）
                put(name, explicitArea)
                if (explicitArea.isEmpty() && areaInParen.isNotEmpty()) {
                    put(name, areaInParen)
                }
            }
        }
        return out.values.toList()
    }

    /**
     * 某个 service 常见于哪些 domain —— [COMMON_SERVICES] 的反向索引。
     *
     * 用来在「一台设备有多个可控制实体」时挑对目标：比如「米家智能插座3」有 28 个
     * 实体，其中可控制的有开关、物理控制锁、勿扰模式开关…… 光看 domain 分不出来，
     * 但 `turn_on` 只在 switch/light 这类 domain 上有意义。
     *
     * 同样只是**新增**查询，不影响任何现有行为。
     */
    fun domainsSupportingService(service: String): List<String> {
        val want = service.trim().lowercase()
        if (want.isEmpty()) return emptyList()
        return COMMON_SERVICES.filterValues { services -> services.any { it.equals(want, ignoreCase = true) } }
            .keys
            .sorted()
    }

    /** 单台设备的实体块。两条过滤分支共用，保证输出结构完全一致。 */
    private fun deviceJson(
        device: com.yubing.cloudlink.data.model.HomeDevice,
        entities: List<com.yubing.cloudlink.data.model.Entity>,
    ): JSONObject = JSONObject()
        .put("device", device.name)
        .put("area", device.area)
        .put("entity_count", entities.size)
        .put("entities", JSONArray().apply {
            for (e in entities) {
                put(
                    JSONObject()
                        .put("entity_id", e.entity_id)
                        .put("domain", e.domain)
                        .put("name", e.name)
                        .put("state", e.state)
                        .put("controllable", e.domain.lowercase() !in READ_ONLY_DOMAINS),
                )
            }
        })

    /**
     * @throws CapError INVALID_PARAMS、ENTITY_NOT_FOUND、NOT_AUTHENTICATED
     *
     * `entityIdRaw` 与 `deviceNameRaw` **二选一**。
     *
     * 为什么允许按设备名查：端侧小模型抄 entity_id 的失败率极高
     * （实测把 `1208334138` 抄成 `1_20_8_3_3_8`，把传感器 ID 截断成
     * `..._dove_tempe`），而 entity_id 长得根本不像人话。让模型只报设备名，
     * 剩下的匹配交给服务端 —— 它本来就有全量实体清单。
     */
    suspend fun getState(entityIdRaw: String, deviceNameRaw: String = ""): JSONObject {
        val entityId = entityIdRaw.trim().lowercase()
        val deviceName = deviceNameRaw.trim()
        if (entityId.isEmpty() && deviceName.isEmpty()) {
            throw CapError(
                INVALID_PARAMS,
                "缺少必填参数：entity_id 与 device_name 二选一。" +
                    "不知道 entity_id 时直接传 device_name（如 \"落地灯\"），服务端会自己匹配。",
            )
        }
        requireToken()

        // 按设备名查：走 listEntities 同一套匹配规则，服务端负责挑实体。
        if (entityId.isEmpty()) {
            return stateByDeviceName(deviceName)
        }

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
                val entities = api.listDevices(token)
                    .asSequence()
                    .flatMap { it.entities.asSequence() }
                    .toList()
                val resolution = resolveEntity(entityId, entities)
                when {
                    resolution.exact != null -> DeviceState(
                        resolution.exact.entity_id,
                        resolution.exact.state,
                        resolution.exact.attributes,
                        resolution.exact.lastStateChange,
                    )
                    // 抄错了一位，但唯一候选足够接近 —— 替模型纠一次，比让它再猜对更有用。
                    resolution.corrected != null -> {
                        Log.i(
                            TAG,
                            "getState: corrected entity_id '$entityId' → '${resolution.corrected.entity_id}'",
                        )
                        readStateOrNull(resolution.corrected.entity_id)
                            ?: DeviceState(
                                resolution.corrected.entity_id,
                                resolution.corrected.state,
                                resolution.corrected.attributes,
                                resolution.corrected.lastStateChange,
                            )
                    }
                    else -> throw CapError(
                        ENTITY_NOT_FOUND,
                        "未找到实体 $entityId。" +
                            suggestionSuffix(resolution.suggestions) +
                            "可先调用 cloudlink.device.entities@v1 按设备名查（device_name 参数），" +
                            "或调用 cloudlink.device.list@v1 确认实体 ID",
                    )
                }
            } else {
                throw mapHttpError(e, entityId)
            }
        }
        return stateJson(state)
    }

    /**
     * 按设备名取状态。
     *
     * 设备可能有多个实体（主卧温湿度计 = 温度 / 湿度 / 电池），这里挑一个代表：
     * 优先**可控制的**实体 —— 「灯是开还是关」「空调设了多少度」问的都是可控实体；
     * 全是只读传感器时才退到第一个，并在 warnings 里把同设备其它实体列出来，
     * 让模型知道它还可以追问别的字段。
     */
    private suspend fun stateByDeviceName(deviceName: String): JSONObject {
        val token = requireToken()
        val devices = api.listDevices(token).filter { it.entities.isNotEmpty() }
        val hit = matchDeviceByName(devices, deviceName)
            ?: throw CapError(
                ENTITY_NOT_FOUND,
                "device_name=$deviceName 不存在。可用设备名：${devices.map { it.name }.sorted().joinToString(" / ")}",
            )
        val entity = hit.entities.firstOrNull { it.domain.lowercase() !in READ_ONLY_DOMAINS }
            ?: hit.entities.first()
        val state = readStateOrNull(entity.entity_id)
            ?: DeviceState(entity.entity_id, entity.state, entity.attributes, entity.lastStateChange)
        val warnings = if (hit.entities.size > 1) {
            listOf(
                "设备「${hit.name}」共有 ${hit.entities.size} 个实体，本次返回的是 " +
                    "${entity.name}（${entity.entity_id}）。" +
                    "要看其它字段请改用 cloudlink.device.entities@v1（device_name=\"${hit.name}\"）。",
            )
        } else {
            emptyList()
        }
        return stateJson(state, warnings)
    }

    /** 重新读一次实时状态；读不到就用清单里的快照，不因为一次网络抖动把整个调用打失败。 */
    private suspend fun readStateOrNull(entityId: String): DeviceState? =
        runCatching { api.getEntityState(entityId) }.getOrNull()

    /**
     * entity_id 精确匹配 → 唯一近似候选 → 候选提示，三级降级。
     *
     * 近似匹配只在**最佳候选唯一**且距离足够近时才纠正 —— 编辑距离打平
     * （多个候选一样近）说明模型是凭空造的 ID（实测 `thermostat.main_bedroom_temp`
     * 与真实候选并列 21），这时任何猜测都是错的，只能把候选摆出来让模型自己选。
     */
    private fun resolveEntity(
        requested: String,
        entities: List<com.yubing.cloudlink.data.model.Entity>,
    ): EntityResolution {
        val exact = entities.firstOrNull { it.entity_id == requested }
        if (exact != null) return EntityResolution(exact = exact)

        val target = normalizeEntityId(requested)
        val scored = entities
            .map { it to distance(target, normalizeEntityId(it.entity_id)) }
            .sortedBy { it.second }
        val best = scored.firstOrNull()
        val runnerUp = scored.getOrNull(1)
        val threshold = maxOf(MIN_CORRECTION_DISTANCE, target.length / 4)
        val corrected = if (
            best != null &&
            best.second <= threshold &&
            (runnerUp == null || best.second < runnerUp.second)
        ) {
            best.first
        } else {
            null
        }
        return EntityResolution(corrected = corrected, suggestions = scored.take(3).map { it.first })
    }

    /** 编辑距离只在「去掉分隔符后」比较，否则下划线错位会放大距离。 */
    private fun normalizeEntityId(id: String): String =
        id.lowercase().filter { it.isLetterOrDigit() }

    private fun suggestionSuffix(suggestions: List<com.yubing.cloudlink.data.model.Entity>): String {
        if (suggestions.isEmpty()) return ""
        return "最接近的实体：" + suggestions.joinToString(" / ") { it.entity_id } + "。"
    }

    /** entity_id 解析结果。 */
    private data class EntityResolution(
        val exact: com.yubing.cloudlink.data.model.Entity? = null,
        val corrected: com.yubing.cloudlink.data.model.Entity? = null,
        val suggestions: List<com.yubing.cloudlink.data.model.Entity> = emptyList(),
    )

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
            // 实体不存在时，先试着把抄错的 entity_id 纠回来再执行 ——
            // 「关灯」因为 ID 抄错一位而没关掉，比报错更糟。
            if (e.code() == 404) {
                val entities = api.listDevices(requireToken())
                    .asSequence()
                    .flatMap { it.entities.asSequence() }
                    .toList()
                val resolution = resolveEntity(entityId, entities)
                val corrected = resolution.corrected
                if (corrected == null) {
                    throw CapError(
                        ENTITY_NOT_FOUND,
                        "未找到实体 $entityId。" +
                            suggestionSuffix(resolution.suggestions) +
                            "可先调用 cloudlink.device.entities@v1 按设备名查（device_name 参数），" +
                            "或调用 cloudlink.device.list@v1 确认实体 ID",
                    )
                }
                val actualDomain = corrected.domain.lowercase()
                if (domain != actualDomain) {
                    throw CapError(
                        DOMAIN_MISMATCH,
                        "domain $domain 与实体 $entityId 不符：该实体的 domain 是 $actualDomain。" +
                            "请用 domain=$actualDomain 重试",
                    )
                }
                Log.i(TAG, "control: corrected entity_id '$entityId' → '${corrected.entity_id}'")
                api.controlDevice(token, corrected.entity_id, DeviceActionRequest(domain, service, data))
            } else {
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

    /**
     * 取一个**可选**的字符串参数，容忍小模型把「没填」写成 null / {} / []。
     *
     * 为什么不能直接用 `JSONObject.optString(name, "")`：Android 这个 API 有两个坑，
     *  1. 值为 JSON null 时返回的是字符串 `"null"` —— 因为 `JSONObject.NULL` 不是
     *     Java null，`optString` 走 `String.valueOf()` 把它转成了 "null"，
     *     fallback 根本不生效（实测 area=null 会变成 `area="null"`）；
     *  2. 值为对象/数组时返回的是该值的 JSON 文本（如 `"{}"`、`"[]"`）。
     *
     * 本地小模型很爱把没填的可选参数写成 null 或 {}，于是 area 变成字符串 "null"，
     * 服务端报 `INVALID_FILTER: area=null 不存在`；模型读不懂这句话就把锅甩给
     * 「参数类型不对」，反问用户要 area —— 而一次不过滤的 list 调用本来就能回答。
     *
     * 现在：null / 缺失 → 空串（对过滤参数 = 不过滤）；其它非字符串类型 → INVALID_PARAMS，
     * 并说清「期望类型 + 实际类型 + 怎么写才对」，让模型能自我纠正而不是甩锅给用户。
     */
    fun optionalString(args: JSONObject, key: String, example: String): String {
        if (!args.has(key)) return ""
        return coerceString(args.opt(key), key, example)
    }

    /**
     * 取一个**必填**的字符串参数。
     *
     * 缺失或 JSON null → 提示缺参；类型不对 → 提示正确写法。
     * 与 [optionalString] 共用同一套类型判定，避免两条通道对同一份入参给出不同结论。
     */
    fun requiredString(args: JSONObject, key: String, example: String): String {
        if (!args.has(key)) {
            throw CapError(INVALID_PARAMS, "缺少必填参数 $key。正确写法：$example")
        }
        val v = coerceString(args.opt(key), key, example)
        if (v.isEmpty()) {
            throw CapError(INVALID_PARAMS, "参数 $key 不能为空。正确写法：$example")
        }
        return v
    }

    private fun coerceString(raw: Any?, key: String, example: String): String = when (raw) {
        null, JSONObject.NULL -> ""
        is String -> raw
        is Boolean, is Number, is JSONArray, is JSONObject -> throw CapError(
            INVALID_PARAMS,
            "$key 必须是字符串（如 $example），收到 ${jsonTypeName(raw)} 类型（$raw）。" +
                "不要传 null 或空对象；不需要这个参数时直接省略它，不要为此反问用户。",
        )
        else -> raw.toString()
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

    companion object {
        const val TAG = "CloudLinkCore"

        /**
         * 设备名长得像 entity_id —— HA 侧没有 friendly_name 时拿 ID 兜底了
         * （实测出现过 `light.cuco_cn_2051041278_v3_s_13_indicator_light`）。
         *
         * 这种「设备名」对模型是毒：它分不清这是名字还是 ID，
         * 于是可能把它当 entity_id 填进参数。所以模型可见的列表里要剔掉。
         */
        fun isEntityIdLike(name: String): Boolean =
            name.trim().lowercase().matches(Regex("^[a-z_]+\\.[a-z0-9_]{9,}$"))

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
         * entity_id 自动纠偏的最大编辑距离。
         *
         * 取 8 是量出来的：实测模型把 `1208334138` 抄成 `1_20_8_3_3_8` 时，
         * 与真 ID 的距离是 7（次佳候选 22）；把传感器 ID 截断时是 9（次佳 12）。
         * 而模型**凭空造**的 ID（如 `thermostat.main_bedroom_temp`）距离是 21，
         * 且与多个候选打平 —— 那种情况一律不猜。
         */
        const val MIN_CORRECTION_DISTANCE = 8

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

/**
 * 唯一解析出来的一台设备。
 *
 * 只给本地 MCP 层用（消歧 + 取该设备的实体），
 * 不参与 AgentBridge 通道的返回结构。
 */
data class ResolvedDevice(
    val name: String,
    val area: String?,
    val entities: List<com.yubing.cloudlink.data.model.Entity>,
)
