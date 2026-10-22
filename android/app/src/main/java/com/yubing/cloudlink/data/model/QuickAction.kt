package com.yubing.cloudlink.data.model

/**
 * 设备卡片右下角「快捷操作按钮」的行为契约。
 *
 * ## 设计原则
 * 按钮图标**只表达「按下去会发生什么」，不表达设备品类**：
 * 品类由卡片中央的大图标（miot-spec 设备图或其兜底向量图）表达。
 *
 * 这样在整个卡片网格里，同一个位置的按钮语义始终只有三种（第四种不渲染）：
 *
 * | kind | 图标 | 含义 |
 * |---|---|---|
 * | [QuickActionKind.TOGGLE] | 电源键 | 按一次在开 / 关之间切换 |
 * | [QuickActionKind.POSITION] | 拨杆 | 按一次在开合之间切换（窗帘、阀门这类有机械行程的） |
 * | [QuickActionKind.MOMENTARY] | 播放键 | 执行一次，本身没有状态 |
 * | [QuickActionKind.READ_ONLY] | 不渲染 | 传感器等只读实体，点了只会报 service_not_found |
 *
 * 首页面与详情页共用这张表，避免两处各写一份 `ON_STATES` 而漂移。
 */
enum class QuickActionKind {
    TOGGLE,
    POSITION,
    MOMENTARY,
    READ_ONLY,
}

/**
 * 各域通用的「关」状态集合。
 *
 * 用**排除法**判定「开」而不是穷举 on 状态：HA 的 climate / water_heater /
 * vacuum 等域的 state 是运行模式（cool / heat_pump / cleaning …），穷举必然漏。
 */
private val DEFAULT_OFF_STATES = setOf(
    "off", "closed", "locked", "idle", "standby", "docked",
    "unavailable", "unknown", "",
)

/**
 * @param turnOnService 当前为「关」时要调用的 service。
 * @param turnOffService 当前为「开」时要调用的 service。
 * @param offStates 该域判定为「关」的状态集合。
 * @param turnOnData 开的时候要附带的 service data（按实体当前属性算，见 light）。
 */
data class QuickAction(
    val kind: QuickActionKind,
    val turnOnService: String = "turn_on",
    val turnOffService: String = "turn_off",
    val offStates: Set<String> = DEFAULT_OFF_STATES,
    val turnOnData: (Entity) -> Map<String, Any?> = { emptyMap() },
) {
    /** 只读实体没有开 / 关概念，恒返回 false。 */
    fun isOn(state: String): Boolean =
        kind != QuickActionKind.READ_ONLY && state !in offStates

    /** 按当前状态算出这一次该调哪个 service。瞬时动作只有一个 service。 */
    fun serviceFor(state: String): String = when {
        kind == QuickActionKind.MOMENTARY -> turnOnService
        isOn(state) -> turnOffService
        else -> turnOnService
    }
}

private val DOMAIN_ACTIONS: Map<String, QuickAction> = mapOf(
    // —— 两态开关：统一电源键 ——
    // 开灯必须带 brightness：不带的话 HA 会恢复上次亮度，而上次可能是 1（几乎全黑）
    "light" to QuickAction(
        QuickActionKind.TOGGLE,
        turnOnData = { e ->
            if (e.supportsBrightness) mapOf("brightness" to e.effectiveBrightness(fallback = 255))
            else emptyMap()
        },
    ),
    "switch" to QuickAction(QuickActionKind.TOGGLE),
    "input_boolean" to QuickAction(QuickActionKind.TOGGLE),
    "fan" to QuickAction(QuickActionKind.TOGGLE),
    "siren" to QuickAction(QuickActionKind.TOGGLE),
    // state 是运行模式（cool / heat / auto / dry / fan_only / off），靠 offStates 排除法判定
    "climate" to QuickAction(QuickActionKind.TOGGLE),
    "humidifier" to QuickAction(QuickActionKind.TOGGLE),
    "water_heater" to QuickAction(QuickActionKind.TOGGLE),
    "vacuum" to QuickAction(QuickActionKind.TOGGLE),
    // media_player 的 idle / paused 表示「已通电」，不算关
    "media_player" to QuickAction(
        QuickActionKind.TOGGLE,
        offStates = setOf("off", "standby", "unavailable", "unknown", ""),
    ),
    // lock 的「开」= 已解锁
    "lock" to QuickAction(
        QuickActionKind.TOGGLE,
        turnOnService = "unlock",
        turnOffService = "lock",
    ),

    // —— 位置 / 行程类：拨杆 ——
    "cover" to QuickAction(
        QuickActionKind.POSITION,
        turnOnService = "open_cover",
        turnOffService = "close_cover",
    ),
    "valve" to QuickAction(
        QuickActionKind.POSITION,
        turnOnService = "open_valve",
        turnOffService = "close_valve",
    ),

    // —— 瞬时动作：播放键 ——
    "button" to QuickAction(QuickActionKind.MOMENTARY, turnOnService = "press"),
    "scene" to QuickAction(QuickActionKind.MOMENTARY),
    "script" to QuickAction(QuickActionKind.MOMENTARY),
    "automation" to QuickAction(QuickActionKind.MOMENTARY, turnOnService = "trigger"),

    // —— 只读：不渲染按钮 ——
    "sensor" to QuickAction(QuickActionKind.READ_ONLY),
    "binary_sensor" to QuickAction(QuickActionKind.READ_ONLY),
    "camera" to QuickAction(QuickActionKind.READ_ONLY),
    "device_tracker" to QuickAction(QuickActionKind.READ_ONLY),
    "person" to QuickAction(QuickActionKind.READ_ONLY),
    "weather" to QuickAction(QuickActionKind.READ_ONLY),
    "sun" to QuickAction(QuickActionKind.READ_ONLY),
    "update" to QuickAction(QuickActionKind.READ_ONLY),
    // 这些是「可写配置」而不是开关，首页不适合给一个开关键，详情页里有对应控件
    "number" to QuickAction(QuickActionKind.READ_ONLY),
    "input_number" to QuickAction(QuickActionKind.READ_ONLY),
    "select" to QuickAction(QuickActionKind.READ_ONLY),
    "input_select" to QuickAction(QuickActionKind.READ_ONLY),
    "text" to QuickAction(QuickActionKind.READ_ONLY),
)

/**
 * 未收录的域：HA 里绝大多数可控域都提供 turn_on / turn_off，
 * 按可开关处理比直接隐藏操作入口更保险（隐藏后卡片就完全没法操作了）。
 */
private val FALLBACK_TOGGLE = QuickAction(QuickActionKind.TOGGLE)

fun quickActionFor(domain: String): QuickAction = DOMAIN_ACTIONS[domain] ?: FALLBACK_TOGGLE
