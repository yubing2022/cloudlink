package com.yubing.cloudlink.mcp

/**
 * 设备名的**近邻匹配**。
 *
 * ## 为什么需要它
 *
 * [DeviceControlCore.resolveDevice] 的匹配链只有三档：
 * 精确 → 忽略大小写 → **连续子串包含**。这在中文短名字上够用，
 * 但真机实测（2026-11-05）撞到了它的边界：
 *
 * ```
 * 用户：turn on the bedside lamp
 * 模型：{"device_name":"床灯","service":"turn_on"}     ← 直译 bed+lamp，不是抄清单
 * 服务端：device_name=床灯 不存在。可用设备名：…床头灯 / 床尾灯…
 * ```
 *
 * 「床灯」匹配不上「床尾灯」，因为 **床/尾/灯 三个字不相邻** ——
 * 「连续子串」这一档天然抓不到「漏抄中间一个字」这种失败模式。
 *
 * 而模型**明明抄过一次对的**：同一台设备在中文提问下模型给的是
 * 「床尾灯」（一字不差）。差别在于英文提问时模型没去抄清单，
 * 而是自行翻译了一个简称。**这说明它不是抄不准，是不抄。**
 *
 * ## 为什么不直接按编辑距离纠偏
 *
 * 「床灯」到「床头灯」和「床尾灯」的编辑距离**都是 1**。
 * 两个候选一样近 ⇒ 模型指向哪一个是**不可知**的 ⇒ 任何自动纠偏都是在替它猜，
 * 而猜错的后果是「打开了另一盏灯」（物理动作，不可撤销）。
 *
 * 所以这里的策略是：
 * - **唯一且足够近** → 纠偏（模型只错了一个字，且没有第二个同样近的候选）；
 * - **打平 / 不够近** → **不纠**，但把最近的候选**排到错误信息的最前面**，
 *   让模型自己一眼看到「床头灯 / 床尾灯」，用**原名**重试。
 *
 * 后者才是本对象的主要价值：错误信息里的**顺序**比**内容**更影响小模型。
 * 14 个设备名平铺时，「床头灯 / 床尾灯」淹没在中间；把它们提到最前，
 * 模型下一轮就能直接抄对。
 *
 * ## 与「不替模型猜」原则的关系
 *
 * [DeviceControlCore.resolveDevice] 对**同名**设备的处理是「列出来让模型加
 * area 区分」，因为同名设备靠名字本身分不出来。本对象是同一条原则的延伸：
 * **只在服务端能确定「只有一个合理答案」时才动手**，其余一律交回模型。
 */
internal object DeviceNameMatcher {

    /**
     * 允许自动纠偏的最大编辑距离。
     *
     * 取 2 是量出来的。模型漏抄的字数不固定，本机真机实测：
     *
     * | 模型写的 | 最近的真实设备 | 距离 | 次佳 | 结论 |
     * |---|---|---|---|---|
     * | 落地 | 落地灯 | 1 | 3 | 唯一 → 纠 |
     * | 智能插 | 智能插座 | 1 | 3 | 唯一 → 纠 |
     * | 主卧温度 | 主卧温湿度计 | 2 | 3 | 唯一 → 纠 |
     * | 床灯 | 床头灯 / 床尾灯 | 1 | 1 | **打平 → 不纠** |
     *
     * 阈值 1 太严（漏抄两字就够不着），阈值 2 又必须靠
     * 「次佳严格更远」这条规则把打平的情况挡住 —— 两者一起用才安全。
     *
     * 真正的安全保证不是这个数字，而是 [uniqueNearMatch] 里
     * 「最佳必须唯一」那条规则：物理动作猜错就是打开了另一盏灯。
     */
    const val MAX_AUTO_CORRECT_DISTANCE = 2

    /**
     * 候选列表里最多展示几个近邻。
     *
     * 3 个足够覆盖「床头灯 / 床尾灯」这种真实歧义，又不至于把错误信息
     * 撑成一长串 —— 错误信息是要进模型上下文的，每多一个名字都是 token。
     */
    const val MAX_SUGGESTIONS = 3

    /**
     * 编辑距离（Levenshtein）。
     *
     * 与 [DeviceControlCore] 里那个私有的 `distance` 同算法，但这里
     * **独立一份**：那个服务于 entity_id（要先剥分隔符），这个服务中文设备名
     * （要保留原字）。抽到公共工具类会引入 Android 依赖，破坏纯 JVM 单测。
     */
    fun distance(a: String, b: String): Int {
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
     * 把候选设备名按「与请求名的距离」升序排。
     *
     * 距离相同的按**原清单顺序**保持稳定 —— 清单顺序对模型是有意义的
     * （它就是照着清单抄的），不该被排序打乱。
     *
     * @param want 模型给的设备名
     * @param candidates 真实设备名列表（保持清单原序）
     * @return 排好序的候选名，距离小的在前
     */
    fun rankByDistance(want: String, candidates: List<String>): List<String> {
        val wantTrimmed = want.trim()
        if (wantTrimmed.isEmpty()) return candidates
        return candidates
            .mapIndexed { index, name -> Triple(name, distance(wantTrimmed, name), index) }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    /**
     * 唯一的近邻候选 —— 只有**唯一一个**候选足够近时才返回。
     *
     * 打平就返回 null：「床灯」到「床头灯」「床尾灯」距离都是 1，
     * 两个一样近说明模型指的是哪一个不可知，服务端不猜。
     *
     * @param want 模型给的设备名
     * @param candidates 真实设备名列表
     * @param maxDistance 允许的最大距离
     * @return 唯一近邻的名字；没有或打平则 null
     */
    fun uniqueNearMatch(
        want: String,
        candidates: List<String>,
        maxDistance: Int = MAX_AUTO_CORRECT_DISTANCE,
    ): String? {
        val wantTrimmed = want.trim()
        if (wantTrimmed.isEmpty() || candidates.isEmpty()) return null
        // 单字名不纠偏：距离 1 命中另一个单字设备名的概率不高，
        // 而「灯」这种泛指词被纠成某台具体灯，几乎一定是错的。
        if (wantTrimmed.length < 2) return null
        val scored = candidates.map { it to distance(wantTrimmed, it) }.sortedBy { it.second }
        val best = scored.first()
        if (best.second > maxDistance) return null
        // 次佳必须**严格更远**才纠偏。打平 ⇒ 模型指哪一个不可知，
        // 猜错就是打开了另一盏灯（物理动作，不可撤销）。
        //
        // 这里比「相等就放弃」更严一档：要求次佳至少比最佳多 1，
        // 而不是「不等于最佳」。否则「最佳 2 / 次佳 2」也会被放行 ——
        // 那是另一个同样近的候选，依然是猜。
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second <= best.second) return null
        return best.first
    }

    /**
     * 错误信息里的候选文本：**近邻在前，其余在后**。
     *
     * 近邻先行的意义在于顺序：小模型读一长串名字时，最容易注意到开头的几个。
     * 「床头灯 / 床尾灯」出现在开头，模型下一轮大概率直接抄对；
     * 埋在 14 个名字中间，它可能又去翻译一次。
     *
     * 命中近邻时给出**明确的改写指令**（「用上面的原名重试」），
     * 因为模型现在知道的是「这个名字不对」，但不知道要改成哪个。
     *
     * @param wants 模型的原始输入拆出的候选名（可能含 markdown 残留），
     *              取其中**能匹配上最近设备**的那个来算距离
     * @param candidates 真实设备名列表（保持清单原序）
     * @param maxDistance 近邻判定距离
     */
    fun suggestionText(
        wants: List<String>,
        candidates: List<String>,
        maxDistance: Int = MAX_AUTO_CORRECT_DISTANCE,
    ): String {
        if (candidates.isEmpty()) return ""
        // 多个候选名（原样 / 剥 markdown / 剥括号）里挑最贴近清单的那个来算距离，
        // 否则模型抄了整行「- 落地灯（客厅）」时，距离全是噪音、排序失去意义。
        val scored = wants
            .filter { it.isNotBlank() }
            .map { want ->
                val ranked = rankByDistance(want, candidates)
                val bestDist = ranked.minOfOrNull { distance(want.trim(), it) } ?: Int.MAX_VALUE
                want.trim() to bestDist
            }
            .minByOrNull { it.second }
            ?: return ""
        val want = scored.first

        val ranked = candidates
            .mapIndexed { index, name -> Triple(name, distance(want, name), index) }
            .sortedWith(compareBy({ it.second }, { it.third }))
        val nearAll = ranked.filter { it.second <= maxDistance }
        // 歧义 = **最佳距离打平**（不止一个候选与模型写的同样近）。
        // 注意不能用「近邻里存在并列」当判据：阈值 2 时「床灯」的近邻里有
        // 落地灯（距离 2），但它是**次佳**距离，不是最佳 —— 那不是歧义，
        // 只是「还有一个没那么像的」。按后者判会把不相干的设备也拖进
        // 「都很接近」的措辞里，反而误导模型。
        val bestDist = nearAll.firstOrNull()?.second ?: Int.MAX_VALUE
        val tied = nearAll.filter { it.second == bestDist }
        val isTie = tied.size > 1
        // 歧义场景必须把**并列最佳**的候选**全部**露出 —— 模型要靠这份清单
        // 反问用户选了哪一台，截掉一个就等于把歧义又藏起来了。
        val near = if (isTie) tied else nearAll.take(MAX_SUGGESTIONS)
        val rest = ranked.drop(near.size).map { it.first }

        return buildString {
            if (near.isNotEmpty()) {
                append("最接近的设备名：")
                append(near.joinToString(" / ") { it.first })
                if (isTie) {
                    append("。这几个名字都很接近，请确认用户指的是哪一个再重试。")
                } else {
                    append("。请用上面的原名重试。")
                }
                if (rest.isNotEmpty()) {
                    append("其他设备名：")
                    append(rest.joinToString(" / "))
                    append("。")
                }
            } else {
                append("可用设备名：")
                append(candidates.joinToString(" / "))
            }
        }
    }
}
