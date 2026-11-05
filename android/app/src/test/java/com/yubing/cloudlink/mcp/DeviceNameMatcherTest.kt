package com.yubing.cloudlink.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备名近邻匹配的单元测试。
 *
 * ## 真机事故（2026-11-05）
 *
 * 用户问 "turn on the bedside lamp"（英文），端侧 2B 模型调
 * `device_control`，参数给的是 `{"device_name":"床灯","service":"turn_on"}`。
 *
 * 服务端的匹配链是「精确 → 忽略大小写 → 连续子串包含」三档，
 * 「床灯」三档全不中：
 *  - 不等于「床尾灯」；
 *  - 「床尾灯」的 床/尾/灯 三字**不相邻**，所以「床灯」不是它的连续子串；
 *  - 「床头灯」同样不含「床灯」。
 *
 * 结果 `ENTITY_NOT_FOUND`，模型反问用户「床头灯还是床尾灯」。
 *
 * 关键观察：**同一个模型在中文提问下给的是「床尾灯」，一字不差。**
 * 差别在于英文提问时它没去照抄清单，而是自行直译了一个简称。
 * 这说明问题不是「抄不准」，是「不抄」——
 * 所以服务端的近邻匹配只能兜住「差一个字」这一类，
 * 真正该修的是 instructions 里那条「照抄原名」的约束。
 *
 * ## 本测试锁的是什么
 *
 * 两条边界，一条都不能松：
 *  1. **唯一近邻 → 纠偏**（模型只错一个字且没有第二个同样近的候选）；
 *  2. **打平 → 不纠偏**（床灯 到 床头灯/床尾灯 距离都是 1，
 *     猜错就是打开了另一盏灯，属物理动作、不可撤销）。
 *
 * 同时锁住错误信息的**顺序**：近邻必须在最前。
 */
class DeviceNameMatcherTest {

    /** 真机上的真实设备清单（顺序保持服务端返回的原序）。 */
    private val devices = listOf(
        "EPSON L3250 Series",
        "HUAWEI PixLab X1",
        "书房摄像头",
        "华为AI音箱 2e",
        "米家智能插座3",
        "Sound X",
        "主卧温湿度计",
        "床头灯",
        "床尾灯",
        "遥控大师空调伴侣",
        "Xiaomi智能家庭屏Mini",
        "智能插座",
        "落地灯",
        "次卧温湿度计",
    )

    // ------------------------------------------------------------ 编辑距离

    @Test
    fun `distance counts character edits`() {
        assertEquals(0, DeviceNameMatcher.distance("床尾灯", "床尾灯"))
        assertEquals(1, DeviceNameMatcher.distance("床灯", "床尾灯"))
        assertEquals(1, DeviceNameMatcher.distance("床头灯", "床尾灯"))
        assertEquals(1, DeviceNameMatcher.distance("主卧温湿度计", "次卧温湿度计"))
        assertEquals(8, DeviceNameMatcher.distance("落地灯", "遥控大师空调伴侣"))
    }

    @Test
    fun `distance handles empty strings`() {
        assertEquals(3, DeviceNameMatcher.distance("", "床尾灯"))
        assertEquals(0, DeviceNameMatcher.distance("", ""))
    }

    // ------------------------------------------------------------ 唯一近邻纠偏

    @Test
    fun `a unique near match is corrected`() {
        // 「落地灯」漏抄一个字 → 「地灯」：本机只有「落地灯」这一台近邻，可以纠。
        assertEquals(
            "落地灯",
            DeviceNameMatcher.uniqueNearMatch("地灯", devices),
        )
    }

    @Test
    fun `a near match that dropped two characters is corrected`() {
        // 「主卧温湿度计」漏抄「湿」→「主卧温度」：距离 2，次佳 3，唯一 → 纠。
        // 阈值 1 时这一类够不着（模型漏两三个字是常态），所以阈值取 2。
        assertEquals(
            "主卧温湿度计",
            DeviceNameMatcher.uniqueNearMatch("主卧温度", devices),
        )
    }

    @Test
    fun `a name that is already exact is not treated as a near match`() {
        // 完全匹配时距离是 0，但纠偏本身不该介入（正常路径更早命中）。
        assertEquals(
            "床尾灯",
            DeviceNameMatcher.uniqueNearMatch("床尾灯", devices),
        )
    }

    @Test
    fun `an ambiguous near match is not corrected`() {
        // **本测试最重要的一条。**
        // 「床灯」到「床头灯」「床尾灯」距离都是 1 ⇒ 指哪一个不可知 ⇒ 不猜。
        assertNull(DeviceNameMatcher.uniqueNearMatch("床灯", devices))
    }

    @Test
    fun `a tie is not corrected even when several candidates share the best distance`() {
        // 「声X」「次卧」这类：多个候选并列最佳（距离 3），一律不纠。
        assertNull(DeviceNameMatcher.uniqueNearMatch("声X", devices))
        assertNull(DeviceNameMatcher.uniqueNearMatch("次卧", devices))
    }

    @Test
    fun `a far away name is not corrected`() {
        // 距离远超阈值 2，绝不能纠。
        assertNull(DeviceNameMatcher.uniqueNearMatch("空调", devices))
        assertNull(DeviceNameMatcher.uniqueNearMatch("打印机", devices))
    }

    @Test
    fun `a single character name is never corrected`() {
        // 「灯」这种泛指词纠成某台具体灯几乎一定是错的。
        assertNull(DeviceNameMatcher.uniqueNearMatch("灯", devices))
    }

    @Test
    fun `an empty name is never corrected`() {
        assertNull(DeviceNameMatcher.uniqueNearMatch("", devices))
        assertNull(DeviceNameMatcher.uniqueNearMatch("   ", devices))
    }

    @Test
    fun `correction respects an explicit maximum distance`() {
        // 「主卧温度」距离 2：默认阈值 2 纠，收紧到 1 就不纠了。
        assertEquals(
            "主卧温湿度计",
            DeviceNameMatcher.uniqueNearMatch("主卧温度", devices),
        )
        assertNull(DeviceNameMatcher.uniqueNearMatch("主卧温度", devices, maxDistance = 1))
    }

    // ------------------------------------------------------------ 排序

    @Test
    fun `ranking puts the closest names first`() {
        val ranked = DeviceNameMatcher.rankByDistance("床灯", devices)
        assertEquals("床头灯", ranked[0])
        assertEquals("床尾灯", ranked[1])
    }

    @Test
    fun `ranking keeps the original order for equal distances`() {
        // 距离相同时保持清单原序 —— 清单顺序对模型是有意义的（它照着清单抄）。
        val ranked = DeviceNameMatcher.rankByDistance("床灯", devices)
        val head = ranked.indexOf("床头灯")
        val tail = ranked.indexOf("床尾灯")
        assertTrue("床头灯 应排在 床尾灯 之前（清单原序）：$head vs $tail", head < tail)
    }

    // ------------------------------------------------------------ 错误信息

    @Test
    fun `the suggestion leads with the closest names`() {
        val text = DeviceNameMatcher.suggestionText(listOf("床灯"), devices)
        assertTrue("近邻应排在最前：$text", text.indexOf("床头灯") < text.indexOf("EPSON"))
        assertTrue("近邻应排在最前：$text", text.indexOf("床尾灯") < text.indexOf("EPSON"))
    }

    @Test
    fun `the suggestion tells the model to retry with the exact name`() {
        // 唯一近邻（「主卧温度」→ 主卧温湿度计）：明确让它用原名重试。
        val text = DeviceNameMatcher.suggestionText(listOf("主卧温度"), devices)
        assertTrue("应给出明确的重试指令：$text", text.contains("请用上面的原名重试"))
    }

    @Test
    fun `the suggestion still lists the remaining devices`() {
        val text = DeviceNameMatcher.suggestionText(listOf("床灯"), devices)
        assertTrue("其余设备名不应丢失：$text", text.contains("落地灯"))
        assertTrue("其余设备名不应丢失：$text", text.contains("EPSON L3250 Series"))
    }

    @Test
    fun `a tie lists every tied candidate`() {
        // 歧义时**不能**截断：模型要靠这份清单反问用户选了哪一台，
        // 截掉一个就等于把歧义又藏回 14 个名字里。
        val text = DeviceNameMatcher.suggestionText(listOf("床灯"), devices)
        assertTrue("并列近邻都要露出：$text", text.contains("床头灯"))
        assertTrue("并列近邻都要露出：$text", text.contains("床尾灯"))
        assertTrue("歧义时提示语要说明要确认：$text", text.contains("确认用户指的是哪一个"))
    }

    @Test
    fun `a runner up is not dragged into the tied list`() {
        // 「床灯」的近邻里混着一个距离 2 的「落地灯」—— 它是**次佳**，
        // 不是并列最佳，所以不该被拖进并列清单（那会让提示语误导模型）。
        // 真正的并列只有「床头灯 / 床尾灯」（都是距离 1）。
        val text = DeviceNameMatcher.suggestionText(listOf("床灯"), devices)
        assertTrue(
            "并列清单只含最佳距离的候选：$text",
            text.contains("最接近的设备名：床头灯 / 床尾灯。"),
        )
        assertTrue(
            "次佳候选应归入「其他设备名」：$text",
            text.contains("其他设备名：落地灯"),
        )
    }

    @Test
    fun `a unique best candidate is not called ambiguous`() {
        // 「主卧温度」只有「主卧温湿度计」一个近邻（距离 2，次佳 3），
        // 不是并列 ⇒ 提示语应是「用原名重试」，而不是「都很接近」。
        val text = DeviceNameMatcher.suggestionText(listOf("主卧温度"), devices)
        assertTrue("唯一近邻时用「用原名重试」：$text", text.contains("请用上面的原名重试"))
        assertTrue("唯一近邻时不该说歧义：$text", !text.contains("都很接近"))
    }

    @Test
    fun `a two character drop is surfaced as a near match`() {
        // 「主卧温度」距离 2，阈值 2 内 ⇒ 应出现在近邻里，而不是退回普通列表。
        val text = DeviceNameMatcher.suggestionText(listOf("主卧温度"), devices)
        assertTrue("应命中近邻：$text", text.contains("最接近的设备名"))
        assertTrue("近邻里应含主卧温湿度计：$text", text.contains("主卧温湿度计"))
    }

    @Test
    fun `a far away name falls back to the plain device list`() {
        val text = DeviceNameMatcher.suggestionText(listOf("打印机"), devices)
        assertTrue("无近邻时应退回普通列表：$text", text.startsWith("可用设备名："))
    }

    @Test
    fun `markdown noise in the name does not break the distance`() {
        // 模型抄了整行「- 床尾灯（卧室）」，距离必须基于剥干净的「床尾灯」算。
        val text = DeviceNameMatcher.suggestionText(
            listOf("- 床尾灯（卧室）", "床尾灯"),
            devices,
        )
        assertTrue("应命中近邻：$text", text.contains("最接近的设备名"))
        assertTrue("不应退回普通列表：$text", !text.startsWith("可用设备名："))
    }
}
