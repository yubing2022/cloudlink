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

    /** MCP tool 名。PalmClaw 会加 `mcp_<serverName>_` 前缀（serverName=cloudlink）。 */
    const val LIST = "cloudlink_device_list"
    const val GET_STATE = "cloudlink_device_get_state"
    const val CONTROL = "cloudlink_device_control"

    /** AgentBridge capability id（保留历史命名空间，与 MCP tool 名并存）。 */
    const val CAP_LIST = "cloudlink.device.list@v1"
    const val CAP_GET_STATE = "cloudlink.device.get_state@v1"
    const val CAP_CONTROL = "cloudlink.device.control@v1"

    // ---------------------------------------------------------- descriptions

    const val DESC_LIST =
        "列出已接入 CloudLink 的智能家居设备及其实体（entity）。返回 {count, devices[]}：" +
            "devices[] 每项含 id/name/area/manufacturer/model/entities[]，" +
            "entities[] 每项含 entity_id/domain/name/state/attributes。" +
            "只返回至少含一个实体的设备；HA 集成/add-on 这类无实体的条目已被剔除。" +
            "area/domain 可省略；传入不存在的过滤值会返回 INVALID_FILTER 并列出可用值，不会静默返回空列表。" +
            "要控制设备，先用本工具取 entity_id 与 domain，再调 cloudlink.device.control@v1。"

    const val DESC_GET_STATE =
        "获取单个设备实体的当前状态与属性（如灯是否开、亮度值、空调设定温度）。" +
            "返回 {entity_id, state, attributes, last_state_change}。" +
            "state 是 HA 原始状态值；unavailable 表示设备已离线（此时 control 不会真正生效）。"

    const val DESC_CONTROL =
        "调用 Home Assistant service 控制设备（开关灯、调亮度、开关插座、设定空调等），" +
            "底层等价于 hass.services.call。" +
            "成功时返回动作后的 {entity_id, state, attributes, last_state_change}，" +
            "state=unavailable 会以 DEVICE_OFFLINE 报错（HA 接收了指令但设备未执行）；" +
            "data 里疑似拼错的字段名会出现在 warnings 数组里。" +
            "参数大小写不敏感，但 domain 必须与 entity_id 前缀一致（服务端只校验、不推断）。"

    // ----------------------------------------------------------- inputSchema

    const val INPUT_LIST =
        """{"type":"object","properties":{"area":{"type":"string","description":"房间/区域过滤，大小写不敏感（如 客厅）。留空=全部。未知值返回 INVALID_FILTER 并附可用 area 列表。"},"domain":{"type":"string","description":"实体 domain 过滤，大小写不敏感。本账号实际出现的 domain：light / switch / climate / media_player / number / select / text / button / sensor / binary_sensor（以设备实际接入为准）。留空=全部。未知值返回 INVALID_FILTER 并附可用 domain 列表。"}}}"""

    const val INPUT_GET_STATE =
        """{"type":"object","properties":{"entity_id":{"type":"string","description":"实体 ID。必须先调用 cloudlink.device.list@v1 获取（形如 light.yeelink_cn_1208334138_mbulb3_s_2，全小写带 domain 前缀），不要凭猜测构造。大小写不敏感（内部统一转小写）。"}},"required":["entity_id"]}"""

    const val INPUT_CONTROL =
        """{"type":"object","properties":{"entity_id":{"type":"string","description":"目标实体 ID，形如 light.yeelink_cn_1208334138_mbulb3_s_2（全小写）。必须先调用 cloudlink.device.list@v1 获取，不要凭猜测构造。大小写不敏感。"},"domain":{"type":"string","description":"实体 domain，必须与 entity_id 的前缀一致（light.xxx → light，switch.xxx → switch）。服务端只校验不推断，不一致返回 DOMAIN_MISMATCH。大小写不敏感。常见值：light / switch / climate / media_player / number / select / text / button。"},"service":{"type":"string","description":"HA service 名，大小写不敏感。各 domain 常用 service：light、switch → turn_on / turn_off / toggle；climate → set_temperature / set_hvac_mode / turn_on / turn_off；media_player → volume_set / media_play / media_pause / turn_on / turn_off；number → set_value；select → select_option；text → set_value；button → press。sensor / binary_sensor 为只读域，没有可调用的 service。传错时返回 SERVICE_NOT_FOUND，并在 message 里附该 domain 可用的 service。"},"data":{"type":"object","description":"service 参数，必须是 JSON 对象（object），不能是字符串。key 必须是该 service 的真实字段名：HA 对未知字段多数情况静默忽略（部分 service 会直接报错，如 light 会提示 did you mean ...）；疑似拼错时返回结果里的 warnings 也会提示。例：{\"brightness\":200,\"color_temp_kelvin\":3000}。边界：brightness 有效范围 1-255，超出由 HA clamp 到 255；brightness=0 等同关灯。"}},"required":["entity_id","domain","service"]}"""

    // ---------------------------------------------------------- outputSchema
    // AgentBridge 需要 outputSchema（用于结果契约）；MCP 侧不需要，结果直接是 JSON 文本。

    const val OUTPUT_LIST =
        """{"type":"object","properties":{"count":{"type":"integer"},"devices":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"},"area":{"type":"string","description":"可能为 null"},"manufacturer":{"type":"string","description":"可能为 null"},"model":{"type":"string","description":"可能为 null"},"entities":{"type":"array","items":{"type":"object","properties":{"entity_id":{"type":"string"},"domain":{"type":"string"},"name":{"type":"string"},"state":{"type":"string"},"attributes":{"type":"object"}}}}}}}}}"""

    const val OUTPUT_STATE =
        """{"type":"object","properties":{"entity_id":{"type":"string"},"state":{"type":"string"},"attributes":{"type":"object"},"last_state_change":{"type":"string","description":"ISO8601，可能为 null"}}}"""

    const val OUTPUT_CONTROL =
        """{"type":"object","properties":{"entity_id":{"type":"string"},"state":{"type":"string"},"attributes":{"type":"object"},"last_state_change":{"type":"string","description":"ISO8601，可能为 null"},"warnings":{"type":"array","items":{"type":"string"},"description":"仅当 data 里有疑似拼错的字段名时出现"}}}"""

    /**
     * 三个工具在 MCP `tools/list` 里的声明顺序。
     * list 必须排第一：Agent 的正常使用路径是 list → get_state/control。
     */
    val MCP_TOOLS: List<Triple<String, String, String>> = listOf(
        Triple(LIST, DESC_LIST, INPUT_LIST),
        Triple(GET_STATE, DESC_GET_STATE, INPUT_GET_STATE),
        Triple(CONTROL, DESC_CONTROL, INPUT_CONTROL),
    )
}
