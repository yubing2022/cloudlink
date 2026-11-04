package com.yubing.cloudlink.mcp

import com.yubing.cloudlink.data.model.Entity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 MCP server 的**模型友好视图层**。
 *
 * ## 为什么要有这一层
 *
 * 业务逻辑在 [DeviceControlCore]，那是 AgentBridge 与本地 MCP **共用**的一份，
 * 它的返回面向「程序」——字段全、语义全，一台设备连诊断实体一起倒出来。
 * 但端侧 2B 模型面对这样的返回会崩：
 *
 *  - `device_list` 原本 9818 字节 / 15 台设备 / 58 个实体。模型问「有哪些设备」，
 *    答案只需要 15 行（名字 + 区域 = 572 字节），却要读完 58 条实体记录。
 *  - 实体名是 HA 的 friendly_name，`落地灯  灯` 这种带重复空格的形态，
 *    模型要自己猜「灯」是设备名的一部分还是实体类型。
 *  - `attributes` 里混着 `supported_features` 这种 bitmask，模型读不懂也用不上。
 *
 * 所以**裁剪必须发生在接口层，而不是靠压缩器**：
 * 压缩器是无脑砍，砍掉的是信息本身（曾把 15 台设备砍成 4 台）；
 * 视图层是**按语义分层** —— 模型用不到的字段根本不出现，它需要的字段一个不少。
 *
 * ## 边界
 *
 * 这一层只做**裁剪与挑选**，不重新实现业务逻辑：
 * 匹配规则、错误码、状态读取仍全部走 [DeviceControlCore]。
 * AgentBridge 通道不经过这里，行为完全不变。
 */
internal object McpDeviceView {

    /** 只读域：这些实体暴露状态但没有可调用的 service，不该被当成控制目标。 */
    private val READ_ONLY_DOMAINS =
        setOf("sensor", "binary_sensor", "device_tracker", "weather", "person")

    /**
     * HA 的 friendly_name 常见形态是 `{设备名} {域中文名}`，
     * 尾部与域同名时那半截是废话，直接用设备名。
     */
    private val DOMAIN_LABELS = mapOf(
        "light" to "灯",
        "switch" to "开关",
        "sensor" to "传感器",
        "binary_sensor" to "二进制传感器",
        "media_player" to "播放器",
        "climate" to "空调",
        "cover" to "窗帘",
        "fan" to "风扇",
        "lock" to "锁",
        "camera" to "摄像头",
        "button" to "按钮",
        "vacuum" to "吸尘器",
        "water_heater" to "热水器",
    )

    /**
     * `attributes` 白名单：只保留模型/用户真的用得上的。
     *
     * 用白名单而不是黑名单 —— 后端随时可能加新字段，黑名单一定会漏。
     */
    private val ATTRIBUTE_ALLOWLIST = setOf(
        "friendly_name",
        "brightness", "color_temp_kelvin", "color_temp", "rgb_color", "rgbw_color", "hs_color",
        "supported_color_modes", "max_color_temp_kelvin", "min_color_temp_kelvin",
        "temperature", "current_temperature", "humidity", "target_temp_high", "target_temp_low",
        "battery_level", "battery", "current_power", "power", "voltage", "energy",
        "percentage", "mode", "fan_mode", "hvac_action", "hvac_modes", "preset_mode", "swing_mode",
        "device_class", "unit_of_measurement", "state_class",
        "effect", "source", "transition", "media_title", "volume_level",
    )

    /** 同设备其它实体的摘要上限 —— 给够上下文，又不至于把结果撑大。 */
    private const val OTHER_ENTITY_LIMIT = 6

    // ------------------------------------------------------------------ 设备列表

    /**
     * 「有哪些设备」的模型视图：**只有名字和区域**。
     *
     * 15 台设备 → 约 572 字节（原 9818）。
     * area 必须留着 —— 它是同名设备唯一的消歧依据。
     */
    fun deviceListView(coreResult: JSONObject): JSONObject {
        val devices = coreResult.optJSONArray("devices") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val name = d.optString("name")
            if (name.isBlank() || DeviceControlCore.isEntityIdLike(name)) continue
            // core 的 entities[] 里只留了**可控制**的实体（只读域已被剔除），
            // 所以「有没有它」直接回答了「这台设备能不能开/关」——
            // 模型就不会对着打印机去调 turn_on 了。
            val controllable = d.optJSONArray("entities")?.length() ?: 0
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("area", d.opt("area") ?: JSONObject.NULL)
                    .put("controllable", controllable > 0),
            )
        }
        return JSONObject().put("count", out.length()).put("devices", out)
    }

    // ------------------------------------------------------------------ 实体列表

    /**
     * 单台设备的实体视图。
     *
     * 实体名精简：`落地灯  灯` → `落地灯`；`主卧温湿度计  温湿度传感器 温度` →
     * `温湿度传感器 温度`（压缩连续空格，保留区分度）。
     */
    fun entitiesView(device: ResolvedDevice): JSONObject {
        val entities = device.entities.filterNot { isDiagnostic(it) }
        val arr = JSONArray()
        for (e in entities) {
            arr.put(
                JSONObject()
                    .put("name", shortEntityName(device.name, e.name, e.domain))
                    .put("domain", e.domain)
                    .put("state", e.state),
            )
        }
        return JSONObject()
            .put("device", device.name)
            .put("area", device.area ?: JSONObject.NULL)
            .put("count", arr.length())
            .put("entities", arr)
    }

    /**
     * HA 把诊断 / 配置类实体在名字里加 ` * ` 前缀（`米家智能插座3 * 指示灯勿扰模式 开启时间`）。
     * 这类实体对「开灯 / 关灯 / 多少度」毫无用处，却占了实体列表的大半。
     */
    private fun isDiagnostic(entity: Entity): Boolean = entity.name.contains(" * ")

    /**
     * 实体名精简：去掉设备名前缀，尾部若只是域中文名就并回设备名。
     */
    fun shortEntityName(deviceName: String, entityName: String, domain: String): String {
        val dn = deviceName.trim()
        val en = entityName.trim()
        if (dn.isEmpty() || en.isEmpty()) return en
        if (en.equals(dn, ignoreCase = true)) return dn
        if (!en.startsWith(dn, ignoreCase = true)) return en.replace(Regex("\\s+"), " ")
        val tail = en.substring(dn.length).trim()
        if (tail.isEmpty()) return dn
        if (DOMAIN_LABELS[domain.lowercase()] == tail) return dn
        return tail.replace(Regex("\\s+"), " ")
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 状态视图：白名单属性。
     *
     * `supported_features` 这种 bitmask 对模型完全无意义；
     * `supported_color_modes` / `max_color_temp_kelvin` 要留 —— 模型要靠它
     * 判断「调色温」这类参数能不能传。
     */
    fun stateView(state: JSONObject, deviceName: String = ""): JSONObject {
        val attrs = state.optJSONObject("attributes")
        val domain = state.optString("entity_id").substringBefore('.')
        val kept = JSONObject()
        if (attrs != null) {
            for (key in attrs.keys()) {
                if (key !in ATTRIBUTE_ALLOWLIST) continue
                // friendly_name 是 HA 原始值（`落地灯  灯`），同样精简过再给模型。
                if (key == "friendly_name" && deviceName.isNotEmpty()) {
                    kept.put(key, shortEntityName(deviceName, attrs.optString(key), domain))
                } else {
                    kept.put(key, attrs.opt(key))
                }
            }
        }
        return JSONObject()
            .put("entity_id", state.optString("entity_id"))
            .put("state", state.optString("state"))
            .put("attributes", kept)
            .put(
                "last_state_change",
                state.opt("last_state_change") ?: JSONObject.NULL,
            )
    }

    // ------------------------------------------------------------ 实体挑选

    /**
     * 查状态时代表这台设备的实体。
     *
     * 优先**可控制**的（「落地灯是开还是关」问的是灯，不是它的电池电量）；
     * 同 domain 有多个时取**精简后名字最短**的 —— 主功能的实体名通常最简
     * （「开关」比「物理控制锁」「勿扰模式」更像主功能）。
     */
    fun pickPrimaryEntity(device: ResolvedDevice): Entity? {
        val controllable = device.entities.filter { it.domain.lowercase() !in READ_ONLY_DOMAINS }
        val pool = controllable.ifEmpty { device.entities }
        return pool.minWithOrNull(
            compareBy(
                { shortEntityName(device.name, it.name, it.domain).length },
                { it.entity_id },
            ),
        )
    }

    /**
     * 一台设备的状态视图：主实体 + 同设备其它实体的状态摘要。
     *
     * 「主卧温湿度计」有 3 个实体（温度 / 湿度 / 电池）。只返回温度的话，
     * 模型答完「多少度」就不知道还能问湿度；所以把同设备的其它实体一并给出，
     * 但**限量**（诊断 / 配置类实体已经被 [isDiagnostic] 滤掉）。
     */
    fun deviceStateView(device: ResolvedDevice, primary: Entity, primaryState: JSONObject): JSONObject {
        val out = JSONObject()
            .put("device", device.name)
            .put("area", device.area ?: JSONObject.NULL)
            .put("name", shortEntityName(device.name, primary.name, primary.domain))
            .put("domain", primary.domain)
        val view = stateView(primaryState, device.name)
        for (key in view.keys()) out.put(key, view.get(key))

        val others = device.entities
            .filter { it.entity_id != primary.entity_id }
            .filterNot { isDiagnostic(it) }
            .sortedBy { shortEntityName(device.name, it.name, it.domain).length }
            .take(OTHER_ENTITY_LIMIT)
            .map {
                JSONObject()
                    .put("name", shortEntityName(device.name, it.name, it.domain))
                    .put("domain", it.domain)
                    .put("state", it.state)
            }
        if (others.isNotEmpty()) out.put("other_entities", JSONArray(others))
        return out
    }

    /** 控制结果的视图：额外告诉模型「实际控制的是哪个实体」。 */
    fun controlResultView(device: ResolvedDevice, target: Entity, result: JSONObject): JSONObject {
        val out = JSONObject()
            .put("device", device.name)
            .put("area", device.area ?: JSONObject.NULL)
            .put("entity", shortEntityName(device.name, target.name, target.domain))
            .put("domain", target.domain)
        val view = stateView(result, device.name)
        for (key in view.keys()) out.put(key, view.get(key))
        return out
    }

    /**
     * 按**精简后的实体名**挑实体。
     *
     * 这是 entity_id 的替代品：短中文名（"温度"、"开关"）比
     * `sensor.cgllc_cn_blt_3_1pm1n4qhk4o00_dove_temperature_p_2_1` 好抄得多 ——
     * 端侧模型抄长 ID 的失败率极高，抄短中文名几乎不会错。
     */
    fun pickEntityByShortName(device: ResolvedDevice, entityName: String): Entity? {
        val want = entityName.trim()
        if (want.isEmpty()) return null
        val byExact = device.entities.firstOrNull {
            shortEntityName(device.name, it.name, it.domain).equals(want, ignoreCase = true)
        }
        if (byExact != null) return byExact
        return device.entities.firstOrNull {
            shortEntityName(device.name, it.name, it.domain).contains(want, ignoreCase = true)
        }
    }

    /**
     * 控制时挑目标实体。
     *
     * 一台设备可能有多个可控制实体（「米家智能插座3」有 28 个），光看 domain 分不出来。
     * 用 `service` 反查它常见于哪些 domain（`turn_on` → light/switch/fan/…），
     * 先按 domain 收窄，再按名字最短挑主功能。
     */
    fun pickControlEntity(
        device: ResolvedDevice,
        service: String,
        supportingDomains: List<String>,
    ): Entity? {
        val controllable = device.entities.filter { it.domain.lowercase() !in READ_ONLY_DOMAINS }
        if (controllable.isEmpty()) return null
        val byService = if (supportingDomains.isEmpty()) {
            controllable
        } else {
            controllable.filter { it.domain.lowercase() in supportingDomains }.ifEmpty { controllable }
        }
        return byService.minWithOrNull(
            compareBy(
                { shortEntityName(device.name, it.name, it.domain).length },
                { it.entity_id },
            ),
        )
    }
}
