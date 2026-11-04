package com.yubing.cloudlink.mcp

/**
 * 设备控制三个工具的唯一契约来源。
 *
 * 同一份文案同时喂给两条对外通道：
 *  - AgentBridge capability（[com.yubing.cloudlink.capability.CloudLinkCapabilityService]）
 *  - 本地 MCP server（[com.yubing.cloudlink.mcp.CloudLinkMcpServer]，供 PalmClaw 消费）
 *
 * 之前描述与 inputSchema 只存在于 capability 的 JSONObject 里，MCP 侧若要
 * 暴露同样三个工具就得复制一份 —— 两份文案迟早会漂移，而 Agent 是靠
 * description 决定怎么用工具的，漂移等于埋雷。所以抽到一处。
 *
 * 文案改动 = 契约改动，两条通道同时生效。
 */
object DeviceToolSchema {

    // ------------------------------------------------------------------ names

    /**
     * MCP tool 名。
     *
     * 不带 `cloudlink_` 前缀：PalmClaw 会自动加 `mcp_<serverName>_` 前缀
     * （serverName=cloudlink），带上前缀会变成 `mcp_cloudlink_cloudlink_device_list`
     * 这种重复两次的名字 —— 既难看，小模型对冗长重复的名字也更容易选错。
     * 现在是 `mcp_cloudlink_device_list`。
     */
    const val LIST = "device_list"
    const val ENTITIES = "device_entities"
    const val GET_STATE = "device_get_state"
    const val CONTROL = "device_control"

    /** AgentBridge capability id（保留历史命名空间，与 MCP tool 名并存）。 */
    const val CAP_LIST = "cloudlink.device.list@v1"
    const val CAP_ENTITIES = "cloudlink.device.entities@v1"
    const val CAP_GET_STATE = "cloudlink.device.get_state@v1"
    const val CAP_CONTROL = "cloudlink.device.control@v1"

    // ---------------------------------------------------------- descriptions

    /**
     * 列出设备 —— **零参数**。
     *
     * 措辞是给弱模型看的，所以反过来写：不是「你可以传 area/domain」，而是
     * 「**本工具不需要任何参数，直接调用**」。模型看到 schema 里有可填的
     * string 属性就会以为必须填，然后反问用户要区域名；把属性彻底去掉，
     * 它就没有可问的东西了。
     */
    const val DESC_LIST =
        "列出已接入 CloudLink 的智能家居设备。返回 {count, devices[]}，" +
            "每台设备含 name/area/manufacturer/model/domains/entity_count/readonly_count/entities[]。" +
            "entities[] 只列**能控制的**实体（灯、插座、空调、音箱等），每项含 " +
            "entity_id/domain/name/state；只读实体（温湿度计、传感器）只给 readonly_count，" +
            "需要它们的 entity_id 时再调用「列出设备实体」工具。" +
            "【重要】本工具**不需要任何参数，直接调用即可**，不要反问用户要区域或类型。" +
            "要控制设备，从返回的 entities[] 里取 entity_id 与 domain，再调用控制工具。"

    const val DESC_ENTITIES =
        "列出实体（含只读传感器），可按 device_name / area / domain 过滤，" +
            "**三个参数全部可选，全都不传就是全部实体**。" +
            "返回 {count, devices[]}，每台设备含 device/area/entity_count/entities[]，" +
            "每个实体含 entity_id/domain/name/state/controllable" +
            "（controllable=false 表示只读，不能控制）。" +
            "典型用法：查某个区域的某类设备 → device_name 省略、传 area + domain；" +
            "查某台设备的完整实体 → 传 device_name（用「列出设备」返回里的 name 原样填写）。" +
            "**只想知道某台设备现在是开还是关 → 传 device_name 即可，本工具的返回里就带 state**，" +
            "不需要再单独查状态。" +
            "只是想看有哪些设备的话，用「列出设备」工具就够了（零参数）。"

    const val DESC_GET_STATE =
        "获取单个设备实体的当前状态与属性（如灯是否开、亮度值、空调设定温度）。" +
            "**entity_id 与 device_name 二选一**：知道 ID 时传 entity_id，" +
            "只知道设备名时传 device_name（如 \"落地灯\"），服务端会自己匹配实体。" +
            "**优先用 device_name** —— entity_id 很长（如 light.yeelink_cn_1208334138_mbulb3_s_2），" +
            "抄错一位就会查不到。" +
            "返回 {entity_id, state, attributes, last_state_change}。" +
            "state 是 HA 原始状态值；unavailable 表示设备已离线（此时 control 不会真正生效）。" +
            "也可以用「列出设备实体」工具按设备名查，它的返回里直接带 state。"

    const val DESC_CONTROL =
        "调用 Home Assistant service 控制设备（开关灯、调亮度、开关插座、设定空调等），" +
            "底层等价于 hass.services.call。" +
            "成功时返回动作后的 {entity_id, state, attributes, last_state_change}，" +
            "state=unavailable 会以 DEVICE_OFFLINE 报错（HA 接收了指令但设备未执行）；" +
            "data 里疑似拼错的字段名会出现在 warnings 数组里。" +
            "参数大小写不敏感，但 domain 必须与 entity_id 前缀一致（服务端只校验、不推断）。"

    // ----------------------------------------------------------- inputSchema

    /**
     * 零属性 —— 不是漏了，是刻意的。
     * 一旦这里出现任何可填属性，弱模型就会去凑值；把「填什么」的决策权
     * 全部交回服务端（用 device_entities 按设备名钻取），模型只需无参调用。
     */
    const val INPUT_LIST =
        """{"type":"object","properties":{}}"""

    const val INPUT_ENTITIES =
        """{"type":"object","properties":{"device_name":{"type":"string","description":"可选。设备名，用「列出设备」工具返回里的 name 原样填写（如 \"主卧温湿度计\"）。不要写 entity_id，不要自己编。不传=全部设备。"},"area":{"type":"string","description":"可选。区域名，如 客厅。不传=全部区域。未知值返回 INVALID_FILTER 并附可用 area 列表。"},"domain":{"type":"string","description":"可选。实体 domain，如 light / sensor / switch。不传=全部 domain。未知值返回 INVALID_FILTER 并附可用 domain 列表。"}}}"""

    /**
     * entity_id 与 device_name **二选一**，都不必填。
     *
     * 之前 entity_id 是 required，弱模型只能硬着头皮去抄那串长 ID，
     * 抄错一位就查不到（实测把 1208334138 抄成 1_20_8_3_3_8）。
     * 既然「设备名 → 实体」本来就是服务端的事，就别让模型背这串东西。
     */
    const val INPUT_GET_STATE =
        """{"type":"object","properties":{"entity_id":{"type":"string","description":"实体 ID，形如 light.yeelink_cn_1208334138_mbulb3_s_2（全小写带 domain 前缀）。与 device_name 二选一；**你不需要记住这串长 ID，拿不准就用 device_name**。不要凭猜测构造。"},"device_name":{"type":"string","description":"设备名（如 \"落地灯\"、\"主卧温湿度计\"），服务端会自动匹配到它的实体。与 entity_id 二选一，**不确定 ID 时优先用它**。"}},"required":[]}"""

    const val INPUT_CONTROL =
        """{"type":"object","properties":{"entity_id":{"type":"string","description":"目标实体 ID，形如 light.yeelink_cn_1208334138_mbulb3_s_2（全小写）。必须先用「列出设备」工具获取，不要凭猜测构造。大小写不敏感。"},"domain":{"type":"string","description":"实体 domain，必须与 entity_id 的前缀一致（light.xxx → light，switch.xxx → switch）。服务端只校验不推断，不一致返回 DOMAIN_MISMATCH。大小写不敏感。常见值：light / switch / climate / media_player / number / select / text / button。"},"service":{"type":"string","description":"HA service 名，大小写不敏感。各 domain 常用 service：light、switch → turn_on / turn_off / toggle；climate → set_temperature / set_hvac_mode / turn_on / turn_off；media_player → volume_set / media_play / media_pause / turn_on / turn_off；number → set_value；select → select_option；text → set_value；button → press。sensor / binary_sensor 为只读域，没有可调用的 service。传错时返回 SERVICE_NOT_FOUND，并在 message 里附该 domain 可用的 service。"},"data":{"type":"object","description":"service 参数，必须是 JSON 对象（object），不能是字符串。key 必须是该 service 的真实字段名：HA 对未知字段多数情况静默忽略（部分 service 会直接报错，如 light 会提示 did you mean ...）；疑似拼错时返回结果里的 warnings 也会提示。例：{\"brightness\":200,\"color_temp_kelvin\":3000}。边界：brightness 有效范围 1-255，超出由 HA clamp 到 255；brightness=0 等同关灯。不需要额外参数时省略本参数，不要传 null。"}},"required":["entity_id","domain","service"]}"""

    // ---------------------------------------------------------- outputSchema
    // AgentBridge 需要 outputSchema（用于结果契约）；MCP 侧不需要，结果直接是 JSON 文本。

    const val OUTPUT_LIST =
        """{"type":"object","properties":{"count":{"type":"integer"},"devices":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"},"area":{"type":"string","description":"可能为 null"},"manufacturer":{"type":"string","description":"可能为 null"},"model":{"type":"string","description":"可能为 null"},"domains":{"type":"array","items":{"type":"string"}},"entity_count":{"type":"integer"},"readonly_count":{"type":"integer"},"entities":{"type":"array","items":{"type":"object","properties":{"entity_id":{"type":"string"},"domain":{"type":"string"},"name":{"type":"string"},"state":{"type":"string"}}}}}}}}}"""

    const val OUTPUT_ENTITIES =
        """{"type":"object","properties":{"count":{"type":"integer"},"devices":{"type":"array","items":{"type":"object","properties":{"device":{"type":"string"},"area":{"type":"string","description":"可能为 null"},"entity_count":{"type":"integer"},"entities":{"type":"array","items":{"type":"object","properties":{"entity_id":{"type":"string"},"domain":{"type":"string"},"name":{"type":"string"},"state":{"type":"string"},"controllable":{"type":"boolean"}}}}}}}}}"""

    const val OUTPUT_STATE =
        """{"type":"object","properties":{"entity_id":{"type":"string"},"state":{"type":"string"},"attributes":{"type":"object"},"last_state_change":{"type":"string","description":"ISO8601，可能为 null"}}}"""

    const val OUTPUT_CONTROL =
        """{"type":"object","properties":{"entity_id":{"type":"string"},"state":{"type":"string"},"attributes":{"type":"object"},"last_state_change":{"type":"string","description":"ISO8601，可能为 null"},"warnings":{"type":"array","items":{"type":"string"},"description":"仅当 data 里有疑似拼错的字段名时出现"}}}"""

    /**
     * 工具级注解（MCP `tools/list` 的 `annotations`）。
 *
     * `readOnlyHint=true` 不只是描述，它决定了**客户端能不能安全重试**：
     * Kratu Agent 在工具调用超时后会重试一次，而写操作（device_control）
     * 一旦「请求可能已经送达」就不能重放 —— 那会把「开灯」执行两遍。
     * 声明了只读的工具，超时/网络失败时重试就是零风险的。
     */
    val ANNOTATIONS_READ_ONLY = """{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}"""

    /** 写操作：可能改变设备状态，且不保证幂等，绝不能被自动重放。 */
    val ANNOTATIONS_MUTATING = """{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":false,"openWorldHint":true}"""

    /**
     * 四个工具在 MCP `tools/list` 里的声明顺序。
     * list 必须排第一：Agent 的正常使用路径是 list → get_state/control。
     */
    val MCP_TOOLS: List<Triple<String, String, String>> = listOf(
        Triple(LIST, DESC_LIST, INPUT_LIST),
        Triple(ENTITIES, DESC_ENTITIES, INPUT_ENTITIES),
        Triple(GET_STATE, DESC_GET_STATE, INPUT_GET_STATE),
        Triple(CONTROL, DESC_CONTROL, INPUT_CONTROL),
    )

    /** 工具名 → annotations JSON。 */
    fun annotationsFor(toolName: String): String = when (toolName) {
        LIST, ENTITIES, GET_STATE -> ANNOTATIONS_READ_ONLY
        CONTROL -> ANNOTATIONS_MUTATING
        else -> ANNOTATIONS_READ_ONLY
    }
}
