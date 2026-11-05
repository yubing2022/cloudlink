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
     * 「有哪些设备」的模型载荷：**一份可以直接照抄的纯文本清单**。
     *
     * ## 为什么不是 JSON
     *
     * 真机事故（2026-11-04）：这一层原本返回 JSON 数组
     * `{"count":14,"devices":[{"name":"落地灯","area":"客厅"},...]}`。
     * 工具侧完全正常（14 台设备、name/area 配对都对），但端侧 2B 模型
     * **无法把嵌套数组还原成列表** —— 它把 14 个 name + 13 个 area
     * 摊平成 **27 个标量**输出，配对关系当场丢失（名字和区域对不上），
     * 第二轮更是在没调工具的情况下凭这份残骸臆造出**不存在的设备**。
     *
     * 根因不在模型「不听话」，而在**载荷要求模型做结构变换**：
     * JSON 数组 → 列表，模型得先解析对象、抽出字段、重新配对、再排版。
     * 2B 模型做不了这个。
     *
     * ## 载荷长什么样
     *
     * ```
     * 共 14 台设备：
     * - 落地灯（客厅）
     * - 主卧温湿度计（卧室）
     * ```
     *
     * 每行一台设备，**行内自带名字与区域**，模型的工作退化成
     * 「把这几行原样抄进回答里」—— 零结构变换、零重新配对。
     * 顺带解决了渲染问题：`-` 后面有空格，Markdown 不会把它
     * 当成段落的续行（lazy continuation）把多行并成一段。
     *
     * ## 抄整行也能用
     *
     * 模型很可能把整行「落地灯（客厅）」原样塞进 `device_name` 参数。
     * [DeviceControlCore.resolveDevice] 会把这类形态逐级拆开，
     * 括号里的区域顺手当消歧依据 —— 模型**不需要编辑**，只需要抄。
     *
     * 15 台设备 → 约 300 字节（原 9818，且模型不用再解一遍）。
     *
     * ## 区域只给「重名」的设备
     *
     * 真机实测（2026-11-05）：把「名字（区域）」原样给模型，它**抄不准** ——
     * 工具结果 218 字符，模型输出 168 字符，差的 50 字符正好是 14 个 `（区域）`。
     * 模型把 `名字（区域）` 理解成「名字 + 元数据」，只可靠地抄走名字部分，
     * 区域要么丢失、要么错位到别的行（`Sound` 和 `卧室` 被拆成两行）。
     *
     * 所以清单默认**只给设备名** —— 名字才是模型必须一字不差搬过去的东西。
     * 只有**重名**的设备才补上区域：那是唯一真正需要消歧的地方，
     * 也是唯一值得让模型多抄几个字的地方。
     */
    fun deviceListText(coreResult: JSONObject): String {
        val devices = coreResult.optJSONArray("devices") ?: JSONArray()
        val rows = ArrayList<Pair<String, String>>()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val name = d.optString("name").trim()
            if (name.isBlank() || DeviceControlCore.isEntityIdLike(name)) continue
            val rawArea = d.opt("area")
            val area = if (rawArea is String) rawArea.trim() else ""
            rows.add(name to area)
        }
        // 只有重名的设备才需要区域。
        val nameCounts = rows.groupingBy { it.first }.eachCount()
        val lines = StringBuilder()
        for ((name, area) in rows) {
            val duplicated = (nameCounts[name] ?: 0) > 1
            lines.append("- ").append(name)
            if (duplicated && area.isNotEmpty()) lines.append("（").append(area).append("）")
            lines.append('\n')
        }
        return buildString {
            append("共 ").append(rows.size).append(" 台设备：\n")
            append(lines)
        }
    }

    // ------------------------------------------------------------------ 实体列表

    /**
     * 单台设备的实体清单：**纯文本**，每行一个实体。
     *
     * 和 [deviceListText] 同一个理由 —— 这是「模型可能要复述给用户看」的列表，
     * 给 JSON 数组就会触发「摊平成标量流」的毛病（见 [deviceListText] 的事故记录）。
     *
     * 行的格式是 `- 实体名（domain）：状态`。实体名放在**最前面**，
     * 因为模型要把它当 `entity_name` 参数抄给控制工具 ——
     * 服务端会剥掉行首符号与括号部分再匹配（见 [pickEntityByShortName]）。
     *
     * 实体名精简：`落地灯  灯` → `落地灯`；`主卧温湿度计  温湿度传感器 温度` →
     * `温湿度传感器 温度`（压缩连续空格，保留区分度）。
     */
    fun entitiesText(device: ResolvedDevice): String {
        val entities = device.entities.filterNot { isDiagnostic(it) }
        val sb = StringBuilder()
        sb.append("设备「").append(device.name).append("」")
        if (!device.area.isNullOrBlank()) sb.append("（").append(device.area).append("）")
        sb.append("下有 ").append(entities.size).append(" 个实体：\n")
        for (e in entities) {
            sb.append("- ")
                .append(shortEntityName(device.name, e.name, e.domain))
                .append("（").append(e.domain).append("）")
                .append("：").append(e.state)
                .append('\n')
        }
        return sb.toString()
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
     *
     * ## 归一化
     *
     * 模型最省力的做法是**把实体清单那一行整行抄进来**：
     * 「- 温度（sensor）：23.5」。服务端负责剥掉行首符号、
     * 括号里的 domain、冒号后的状态，只留实体名再匹配 ——
     * 要求模型「编辑」成干净的「温度」是在为难它，而它一旦编辑就会出错。
     */
    fun pickEntityByShortName(device: ResolvedDevice, entityName: String): Entity? {
        val want = normalizeEntityRef(entityName)
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
     * 把模型抄来的实体引用还原成干净的实体名。
     *
     * ## 剥离顺序有讲究
     *
     * 行首是「- 温湿度传感器 温度（sensor）：23.5」，
     * 要还原成「温湿度传感器 温度」，必须**先切状态、再切括号**：
     * 先切括号的话，「（sensor）」后面还跟着「：23.5」，根本不在行尾，切不掉。
     *
     * 顺序：行首列表符 → 冒号后的状态 → 行尾括号段（domain）→ 尾部标点与 markdown 强调号。
     */
    private fun normalizeEntityRef(raw: String): String {
        var s = raw.trim()
        // ① 行首列表符（模型抄的整行以 "- " 开头）
        s = s.replace(Regex("^[-*+•]+\\s*"), "")
        // ② 冒号及其之后的状态
        s = s.replace(Regex("\\s*[:：].*$"), "")
        // ③ 行尾括号段（domain）
        s = s.replace(Regex("\\s*[（(][^（()）]*[）)]\\s*$"), "")
        // ④ 尾部标点与 markdown 强调号
        s = s.replace(Regex("[\\*_`\"'“”’。．.,，]+$"), "").trim()
        return s
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
