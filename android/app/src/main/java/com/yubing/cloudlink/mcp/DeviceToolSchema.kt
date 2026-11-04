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
            "每台设备只有 name（设备名）与 area（所在区域）。" +
            "**本工具不需要任何参数，直接调用即可**，不要反问用户要区域或类型。" +
            "设备名是后续所有查询与控制的入口 —— 拿到名字后再问「它是什么状态」或「把它打开」。"

    const val DESC_ENTITIES =
        "查询**某一台设备**下的实体（灯、开关、传感器等）及其当前状态。" +
            "**device_name 必填**，用「列出设备」返回里的 name 原样填写（如 \"落地灯\"）；" +
            "**同名设备请加 area 参数区分**（如 \"客厅\"）。" +
            "返回 {device, area, count, entities[]}，每个实体含 name（简短实体名）、" +
            "domain（light/switch/sensor…）与 state（当前状态）。" +
            "本工具**不需要 entity_id** —— 实体名（简短中文名）就够用。"

    const val DESC_GET_STATE =
        "查询**某一台设备**现在的状态与关键属性（如灯是否开、亮度、空调温度）。" +
            "**device_name 必填**（用「列出设备」返回里的 name 原样填写）；" +
            "**同名设备请加 area 参数区分**。" +
            "返回 {device, area, state, attributes, last_state_change, other_entities[]}，" +
            "attributes 只含有意义的字段（brightness / color_temp_kelvin / temperature / humidity…）。" +
            "state 是 HA 原始状态值；unavailable 表示设备已离线（此时 control 不会真正生效）。" +
            "想知道这台设备**有哪些**实体，用「查询设备实体」工具。"

    const val DESC_CONTROL =
        "控制设备：开关灯、调亮度、开关插座、设定空调等，底层等价于 hass.services.call。" +
            "**device_name 必填**（用「列出设备」返回里的 name 原样填写）；" +
            "**同名设备请加 area 参数区分**；**service 必填**。" +
            "服务端会自动挑这台设备上支持该 service 的实体，你**不需要**给 entity_id 或 domain。" +
            "service 常用值：turn_on（开）/ turn_off（关）/ toggle（切换）；" +
            "climate → set_temperature / set_hvac_mode；light → set_brightness / set_color_temp_kelvin；" +
            "media_player → volume_set / media_play / media_pause；button → press。" +
            "成功时返回动作后的 {device, area, entity, state, attributes, last_state_change}；" +
            "state=unavailable 会以 DEVICE_OFFLINE 报错（HA 接收了指令但设备未执行）。"

    // ----------------------------------------------------------- inputSchema

    /**
     * 零属性 —— 不是漏了，是刻意的。
     *
     * 一旦这里出现任何可填属性，弱模型就会去凑值；把「填什么」的决策权
     * 全部交回服务端（用 device_entities 按设备名钻取），模型只需无参调用。
     */
    const val INPUT_LIST =
        """{"type":"object","properties":{}}"""

    const val INPUT_ENTITIES =
        """{"type":"object","properties":{"device_name":{"type":"string","description":"设备名，必填。用「列出设备」返回里的 name 原样填写（如 \"落地灯\"、\"主卧温湿度计\"）。不要写 entity_id，不要自己编。"},"area":{"type":"string","description":"可选。区域名（如 \"客厅\"）。**只有同名设备时才需要**用它区分。传了它就只在该区域内匹配。"}},"required":["device_name"]}"""

    const val INPUT_GET_STATE =
        """{"type":"object","properties":{"device_name":{"type":"string","description":"设备名，必填。用「列出设备」返回里的 name 原样填写（如 \"落地灯\"）。不要写 entity_id，不要凭猜测构造。"},"area":{"type":"string","description":"可选。区域名（如 \"客厅\"）。**只有同名设备时才需要**用它区分。"}},"required":["device_name"]}"""

    const val INPUT_CONTROL =
        """{"type":"object","properties":{"device_name":{"type":"string","description":"设备名，必填。用「列出设备」返回里的 name 原样填写（如 \"落地灯\"）。不要写 entity_id，不要自己编。"},"area":{"type":"string","description":"可选。区域名（如 \"客厅\"）。**只有同名设备时才需要**用它区分。"},"service":{"type":"string","description":"必填。HA service 名。常用：turn_on（打开）/ turn_off（关闭）/ toggle（切换）；climate → set_temperature / set_hvac_mode；light → set_brightness / set_color_temp_kelvin；media_player → volume_set / media_play / media_pause；button → press。sensor / binary_sensor 是只读域，没有可调用的 service。传错时返回 SERVICE_NOT_FOUND，并在 message 里附可用 service。"},"entity_name":{"type":"string","description":"可选。实体名（如 \"温度\"、\"开关\"），用「查询设备实体」返回里的 name 原样填写。只有同一台设备有多个实体、你要控制其中特定一个时才需要。不传就由服务端自动挑主实体。"},"data":{"type":"object","description":"service 参数，必须是 JSON 对象（object），不能是字符串。key 必须是该 service 的真实字段名。例：{\"brightness\":200,\"color_temp_kelvin\":3000}。边界：brightness 有效范围 1-255；brightness=0 等同关灯。不需要额外参数时省略本参数，不要传 null。"}},"required":["device_name","service"]}"""

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
