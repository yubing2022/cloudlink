package com.yubing.cloudlink.data.model

/**
 * 首页卡片快捷按钮指向哪个实体 —— 也就是「这台设备的主控件」。
 *
 * 不能简单取「第一个可开关域的实体」。设备挂哪些域、实体以什么顺序返回
 * 都是集成的实现细节，实测踩到的三个反例：
 *
 *  - **Sound X**：3 个 switch（音乐律动灯 / 蓝牙 / DLNA 投播）+ 1 个
 *    media_player。旧规则 `switch` 排在 `media_player` 前 ⇒ 音箱的电源键
 *    实际控制的是「音乐律动灯」。
 *  - **米家智能插座3（cuco.plug.v3）**：7 个 switch，第一个是
 *    `on_p_8_1`「快捷倒计时关闭」。真正的插座通断电是 siid=2 的
 *    `on_p_2_1`，名字就是「米家智能插座3 开关 开关」。
 *  - **佛山照明智能插座**：第一个 switch 是「指示灯」，不是「电源开关」。
 *
 * 挑选分三级：
 *  1. **域优先级** —— 能定义「设备是什么」的域排在前面，switch 是附属
 *     能力（蓝牙开关、指示灯、定时开关），排后面。
 *  2. **排除附属功能** —— 名字里带「指示灯 / 童锁 / 定时 / 巡航 …」的
 *     一律排除；排空了就回退到原集合，保证一定有按钮。
 *  3. **优先主控件** —— xiaomi_miot 集成用 `*` 标记非主属性
 *     （「设备名 * 功能名 属性名」），所以名字里没有 `*` 的才是主控件；
 *     再优先名字含「开关 / 电源 / 主控」的。
 */
private val PRIMARY_DOMAINS = listOf(
    "light",
    "media_player",
    "climate",
    "cover",
    "lock",
    "fan",
    "humidifier",
    "water_heater",
    "vacuum",
    "switch",
    "button",
)

/** 名字里出现这些词 → 这是附属功能，不是主控件。 */
private val AUX_NAME_KEYWORDS = listOf(
    // 灯/显示类附属
    "指示灯", "夜灯", "氛围灯", "律动", "屏保",
    // 锁
    "童锁", "儿童锁", "物理控制锁", "按键锁", "面板锁",
    // 定时/任务
    "定时", "倒计时", "循环", "提醒",
    // 影音能力开关
    "投播", "dlna", "蓝牙", "音效", "灯效", "静音", "勿扰",
    // 摄像头附加功能
    "巡航", "追踪", "侦测", "检测", "报警", "警报", "遮罩", "隐私",
    // 维护/配置
    "校准", "自检", "恢复", "重置", "复位", "升级", "固件",
    // 用电保护
    "功率限制", "充电保护", "限制", "保护",
)

/** 名字里出现这些词 → 更像主控件。 */
private val MAIN_NAME_HINTS = listOf("开关", "电源", "主控", "插头", "插座")

private val Entity.isAuxiliary: Boolean
    get() = AUX_NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }

/**
 * 从同一域的候选里挑主控件：先排掉米家用 `*` 标记的附属属性，
 * 再优先名字像「开关 / 电源」的那个。
 */
private fun List<Entity>.preferMain(): Entity {
    val plain = filter { '*' !in it.name }
    val source = plain.ifEmpty { this }
    return source.firstOrNull { e ->
        MAIN_NAME_HINTS.any { e.name.contains(it, ignoreCase = true) }
    } ?: source.first()
}

/** 见文件头说明：卡片快捷按钮要控制的那颗实体。 */
fun HomeDevice.primaryEntityFor(): Entity? {
    if (entities.isEmpty()) return null
    for (domain in PRIMARY_DOMAINS) {
        val candidates = entities.filter { it.domain == domain }
        if (candidates.isEmpty()) continue
        // lock 域的实体名字里天然带「锁」，不做附属排除
        val pool = if (domain == "lock") {
            candidates
        } else {
            candidates.filterNot { it.isAuxiliary }.ifEmpty { candidates }
        }
        return pool.preferMain()
    }
    return entities.first()
}
