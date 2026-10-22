package com.yubing.cloudlink.data.model

/**
 * 设备详情页的**槽位模板** —— 决定「这一品类应该有哪些行」。
 *
 * ## 为什么需要它
 *
 * 同一品类在不同集成下暴露的实体完全不同。实测两个插座：
 *
 * | | 米家智能插座3 (cuco.plug.v3) | 智能插座 (佛山照明 Z1-Z3B-101/ZK) |
 * |---|---|---|
 * | 实体总数 | 28 | 10 |
 * | switch | 8（含倒计时 / 功率限制 / 充电保护 / 循环任务 …） | 3（童锁 / 指示灯 / 电源） |
 * | sensor | 5（耗电量 / 电功率 / 温度 / 故障 / 开关次数） | 5（功率 / 电量 / 电压 / 电流 / 固件版本） |
 * | number + text | 14 | 0 |
 * | binary_sensor | 0 | 2（电流过载 / 设备异常） |
 *
 * 两边真正对得上的只有「电源 / 功率 / 电量」；电压电流只有佛山有，
 * 设备温度只有小米有，剩下 20 多条是各集成私有的参数。
 *
 * 详情页如果直接按 `device.entities` 平铺，结果必然是：行数不同、行顺序
 * 由集成的返回顺序决定、行文案是集成给的实体名（小米叫「开关 开关」，
 * 佛山叫「华为 电源开关」）。品类相同却长得毫无关系。
 *
 * ## 做法
 *
 * 反过来：先声明「插座详情页应该有哪几行」（[Slot]），每行带
 *
 *  - 固定文案（由模板给，不取实体名），
 *  - 命中规则（[Slot.pickFrom]）—— 从实体池里挑一个实体填进来。
 *
 * 挑得到就显示这一行，挑不到就**整行隐藏**（不是显示「无」，也不是留空）。
 * 槽位吃剩下的实体全部折进「高级设置 / 设备信息」折叠区。
 *
 * 于是同一品类的详情页，行结构、行顺序、行文案都由模板决定，与集成无关。
 */

/** 槽位这一行右侧渲染成什么。 */
enum class SlotRender {
    /** 两态开关，按 [quickActionFor] 决定调哪个 service。 */
    SWITCH,

    /** 只读读数，显示 state + 单位。 */
    READING,

    /** 瞬时动作（press 一下就完，没有状态）。 */
    MOMENTARY,
}

/**
 * @param title 行标题，**固定文案**，不取实体名 —— 这是两个品牌显示一致的前提。
 * @param pick 从实体池里挑一个实体填这个槽位；返回 null 则整行隐藏。
 *             `used` 是已经被前面的槽位占用的 entity_id，避免同一个实体
 *             被两行重复引用（比如主开关又被「童锁」行选中）。
 */
data class Slot(
    val title: String,
    val render: SlotRender,
    private val pick: (HomeDevice, Set<String>) -> Entity?,
) {
    fun pickFrom(device: HomeDevice, used: Set<String>): Entity? = pick(device, used)
}

/** 一个填上了实体的槽位，即详情页上的一行。 */
data class FilledSlot(
    val slot: Slot,
    val entity: Entity,
)

/**
 * @param slots 槽位序列，顺序即详情页行顺序。
 * @param foldRest true = 槽位没吃掉的实体一律折进折叠区，不单独平铺。
 *   PLUG 用 true：小米那 20 多条私有参数一旦平铺就前功尽弃。
 *   通用品类用 false：没有模板可依，平铺至少保证功能不丢。
 */
data class DeviceTemplate(
    val slots: List<Slot>,
    val foldRest: Boolean,
)

// ——— 实体属性读取的小工具 ———

private fun Entity.deviceClass(): String? = attributes["device_class"] as? String

private fun Entity.unit(): String? = attributes["unit_of_measurement"] as? String

/**
 * 在未被占用的实体里挑第一个满足 [predicate]、且名字不含 [exclude] 词的。
 *
 * `exclude` 用来挡掉「看起来像但其实不是」的实体：小米的
 * 「* 指示灯**勿扰模式** 勿扰模式」名字里有「指示灯」，但它不是指示灯开关。
 */
private fun List<Entity>.pickFirst(
    used: Set<String>,
    exclude: List<String> = emptyList(),
    predicate: (Entity) -> Boolean,
): Entity? = firstOrNull { e ->
    e.entity_id !in used &&
        predicate(e) &&
        exclude.none { w -> e.name.contains(w, ignoreCase = true) }
}

// ——— 槽位定义 ———

/**
 * 主控件：复用首页卡片那套挑选规则（见 PrimaryEntity.kt）。
 *
 * 必须过滤只读实体 —— [HomeDevice.primaryEntityFor] 对一个纯传感器设备
 * （温湿度计只有 3 个 sensor）会兜底返回 `entities.first()`，而槽位渲染是
 * 写死的 SWITCH，结果温湿度计详情页顶上多出一个控制不了任何东西的
 * 「电源」开关。挑不到可控实体时就让这个槽位整体消失。
 */
private val SLOT_PRIMARY = Slot("电源", SlotRender.SWITCH) { dev, used ->
    dev.primaryEntityFor()?.takeIf {
        it.entity_id !in used && quickActionFor(it.domain).kind != QuickActionKind.READ_ONLY
    }
}

private fun readingSlot(
    title: String,
    vararg deviceClasses: String,
    exclude: List<String> = emptyList(),
): Slot = Slot(title, SlotRender.READING) { dev, used ->
    dev.entities.pickFirst(used, exclude) {
        it.domain == "sensor" && it.deviceClass() in deviceClasses
    }
}

/**
 * 插座模板。
 *
 * 顺序 = 详情页行顺序：先控制，再读数，最后附属开关。
 * 小米能填 6 行、佛山能填 8 行，双方共有的 5 行（电源 / 功率 / 电量 /
 * 童锁 / 故障）文案与位置完全一致；各自特有的行（电压电流 / 温度）
 * 在缺的那台设备上整行隐藏。
 */
val PLUG_TEMPLATE = DeviceTemplate(
    foldRest = true,
    slots = listOf(
        SLOT_PRIMARY,
        readingSlot("实时功率", "power"),
        readingSlot("累计电量", "energy"),
        readingSlot("电压", "voltage"),
        readingSlot("电流", "current"),
        // 小米的「设备温度」挂在「* 开关次数」这个 siid 下，名字里带「开关次数」
        // 但 device_class 是 temperature，靠 device_class 命中即可。
        readingSlot("设备温度", "temperature"),
        Slot("童锁", SlotRender.SWITCH) { dev, used ->
            dev.entities.pickFirst(used) {
                it.domain == "switch" && CHILD_LOCK_WORDS.any { w -> it.name.contains(w) }
            }
        },
        Slot("指示灯", SlotRender.SWITCH) { dev, used ->
            dev.entities.pickFirst(used, exclude = listOf("勿扰")) {
                it.domain == "switch" && it.name.contains("指示灯")
            }
        },
        Slot("故障状态", SlotRender.READING) { dev, used ->
            dev.entities.pickFirst(used) {
                it.domain == "binary_sensor" && it.deviceClass() == "problem"
            } ?: dev.entities.pickFirst(used) {
                it.domain == "sensor" && ("故障" in it.name || "异常" in it.name)
            }
        },
    ),
)

/** 「童锁」在不同集成下的叫法。 */
private val CHILD_LOCK_WORDS = listOf("童锁", "儿童锁", "物理控制锁", "按键锁", "面板锁")

/**
 * 兜底模板：只有主控件一个槽位，其余实体平铺。
 *
 * 没有模板的品类（灯、音箱、摄像头 …）先走它，保证功能不丢，
 * 等对应的品类模板补上再切换。
 */
val GENERIC_TEMPLATE = DeviceTemplate(
    foldRest = false,
    slots = listOf(SLOT_PRIMARY),
)

/** 按品类取模板。新增品类模板时在这里加一行即可。 */
fun HomeDevice.template(): DeviceTemplate = when (category()) {
    DeviceCategory.PLUG -> PLUG_TEMPLATE
    else -> GENERIC_TEMPLATE
}

/**
 * 按模板把实体填进槽位，返回详情页要渲染的行。
 *
 * 同一个实体只会被第一个命中的槽位吃掉（[used]），后面的槽位继续往后找，
 * 所以「电源」不会又被「童锁」行引用一次。
 */
fun HomeDevice.filledSlots(): List<FilledSlot> {
    val slots = template().slots
    val used = HashSet<String>(entities.size)
    val out = ArrayList<FilledSlot>(slots.size)
    for (slot in slots) {
        val e = slot.pickFrom(this, used) ?: continue
        used.add(e.entity_id)
        out += FilledSlot(slot, e)
    }
    return out
}

// ——— 槽位吃剩下的实体怎么归类 ———

/** 剩余实体的归属分区。 */
enum class RestKind {
    /** 可开关的附属开关（倒计时、功率限制 …）。 */
    CONTROL,

    /** 只读读数（sensor / binary_sensor）。 */
    READING,

    /** 可写参数：number / text / select。 */
    CONFIG,

    /** 诊断信息：固件版本、开关次数、信号强度 …。 */
    DIAGNOSTIC,
}

/** 名字出现这些词 → 归到诊断区，而不是当普通读数展示。 */
// 注意别放太短的词：这里用的是子串匹配，"ip" 这类词会误伤中文名里
// 恰好含这两个字母的实体。
private val DIAGNOSTIC_WORDS = listOf(
    "固件", "版本", "version", "开关次数", "信号", "signal", "mac地址",
    "uptime", "运行时间", "在线状态", "connectivity", "timestamp",
)

/** 这些域是「可写参数」而不是开关，放配置区。 */
private val CONFIG_DOMAINS = setOf(
    "number", "input_number", "select", "input_select", "text", "input_text",
)

private fun Entity.isDiagnostic(): Boolean =
    entityCategory != null || DIAGNOSTIC_WORDS.any { name.contains(it, ignoreCase = true) }

/**
 * 把没被槽位占用的实体按 [RestKind] 分好类。
 *
 * 分类顺序有讲究：诊断先判（否则「固件版本」会被当成普通读数平铺出来，
 * 而它既不能控也不是用户想看的读数），配置次之，再按域分控制 / 读数。
 */
fun HomeDevice.restEntities(used: Set<String>): Map<RestKind, List<Entity>> {
    val rest = entities.filter { it.entity_id !in used }
    val out = mutableMapOf<RestKind, MutableList<Entity>>()
    for (e in rest) {
        val kind = when {
            e.isDiagnostic() -> RestKind.DIAGNOSTIC
            e.domain in CONFIG_DOMAINS -> RestKind.CONFIG
            quickActionFor(e.domain).kind == QuickActionKind.READ_ONLY -> RestKind.READING
            else -> RestKind.CONTROL
        }
        out.getOrPut(kind) { mutableListOf() }.add(e)
    }
    return out
}
