package com.yubing.cloudlink.data.model

/**
 * 设备**品类** —— 卡片中央大图标按它选，而不是按 `primaryEntity.domain`。
 *
 * 为什么不按主实体的域：一个设备挂哪些域是集成实现细节，不代表它是什么。
 * 实测的三个反例：
 *
 *  - Sound X 挂了 3 个 switch（蓝牙/灯/DLNA）+ 1 个 media_player，
 *    主实体被选成 switch ⇒ 音箱显示成开关图标；
 *  - 书房摄像头（海雀）没有 camera 实体，只有 switch + sensor + button
 *    ⇒ 摄像头显示成开关图标；
 *  - HUAWEI PixLab X1 / EPSON L3250 只有 sensor 实体（墨量/状态机）
 *    ⇒ 打印机显示成「传感器」雷达波。
 *
 * 所以品类按「强信号 → 域 → 传感器细分」三级推断，见 [category]。
 */
enum class DeviceCategory {
    LIGHT,
    PLUG,
    SPEAKER,
    TV,
    PRINTER,
    CAMERA,
    CLIMATE,
    COVER,
    LOCK,
    VACUUM,
    FAN,
    HUMIDIFIER,
    WATER_HEATER,

    /** 温湿度/气压/光照/空气质量类传感器。 */
    ENV_SENSOR,

    /** 电量/功率/能耗类传感器。 */
    POWER_SENSOR,
    SENSOR,
    UNKNOWN,
}

/**
 * 推断设备品类。
 *
 * 三级：
 *  1. **名称/厂商/型号关键词** —— 最可靠。同一批设备里"打印机"和"温湿度计"
 *     可能都是纯 sensor 实体，只有名字能区分。
 *  2. **实体域集合** —— `media_player` / `camera` 必须排在 `switch` 之前，
 *     否则音箱、摄像头会被它自带的开关实体抢走。
 *  3. **device_class / 名称兜底** —— 把传感器再分成环境类、电量类和普通类。
 */
fun HomeDevice.category(): DeviceCategory {
    val hay = listOfNotNull(name, manufacturer, model)
        .joinToString(" ") { it.lowercase() }
    val domains = entities.mapTo(HashSet()) { it.domain }
    val deviceClasses = entities
        .mapNotNull { it.attributes["device_class"] as? String }
        .toSet()

    fun has(vararg keys: String): Boolean = keys.any { hay.contains(it) }

    // —— 1. 关键词（强信号） ——
    if (has(
            "打印", "pixlab", "printer", "laserjet", "deskjet", "l3250",
            "墨盒", "墨量", "碳粉", "cartridge", "ink",
        )
    ) return DeviceCategory.PRINTER

    if (has("摄像", "camera", "webcam", "监控", "ipc", "doorbell")) {
        return DeviceCategory.CAMERA
    }

    if (has("音箱", "音响", "speaker", "sound x", "soundx", "回音壁", "soundbar")) {
        return DeviceCategory.SPEAKER
    }

    if (has("扫地", "扫拖", "vacuum", "robot vacuum")) return DeviceCategory.VACUUM

    if (has("窗帘", "curtain", "blind", "卷帘", "百叶")) return DeviceCategory.COVER

    // —— 2. 实体域 ——
    // 顺序有讲究：能定义「设备是什么」的域排在前面，
    // switch 是附属能力（蓝牙开关、DLNA 开关），放最后。
    when {
        "light" in domains -> return DeviceCategory.LIGHT
        "media_player" in domains -> return DeviceCategory.SPEAKER
        "camera" in domains -> return DeviceCategory.CAMERA
        "climate" in domains -> return DeviceCategory.CLIMATE
        "cover" in domains -> return DeviceCategory.COVER
        "lock" in domains -> return DeviceCategory.LOCK
        "vacuum" in domains -> return DeviceCategory.VACUUM
        "humidifier" in domains -> return DeviceCategory.HUMIDIFIER
        "water_heater" in domains -> return DeviceCategory.WATER_HEATER
        "fan" in domains -> return DeviceCategory.FAN
        "switch" in domains -> return DeviceCategory.PLUG
        "siren" in domains -> return DeviceCategory.SENSOR
    }

    // —— 3. 传感器细分 ——
    val envClasses = setOf(
        "temperature", "humidity", "pressure", "illuminance",
        "co2", "pm1", "pm10", "pm25", "aqi", "volatile_organic_compounds",
    )
    val powerClasses = setOf(
        "power", "energy", "voltage", "current", "battery", "monetary",
    )
    if (deviceClasses.any { it in envClasses }) return DeviceCategory.ENV_SENSOR
    if (deviceClasses.any { it in powerClasses }) return DeviceCategory.POWER_SENSOR

    if (has("温湿度", "温度", "湿度", "温度计", "温湿度计")) return DeviceCategory.ENV_SENSOR
    if (has("电量", "功率", "耗电", "能耗")) return DeviceCategory.POWER_SENSOR

    return if ("sensor" in domains || "binary_sensor" in domains) {
        DeviceCategory.SENSOR
    } else {
        DeviceCategory.UNKNOWN
    }
}
