package com.yubing.cloudlink.data.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WindPower
import androidx.compose.ui.graphics.vector.ImageVector
import com.yubing.cloudlink.data.model.DeviceCategory
import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.data.model.category

/**
 * 设备品类 → 矢量图标。单一来源，首页卡片与详情页头部共用。
 *
 * 这是「产品实物图拿不到时」的兜底：只有小米生态的设备能在
 * miot-spec.com 找到产品照，其余厂商（华为、EPSON、佛山照明…）
 * 没有公开图源，只能靠品类图标表达「这是台什么东西」。
 */
fun HomeDevice.categoryIcon(): ImageVector = when (category()) {
    DeviceCategory.LIGHT -> Icons.Filled.Lightbulb
    DeviceCategory.PLUG -> Icons.Filled.Power
    DeviceCategory.SPEAKER -> Icons.Filled.Speaker
    DeviceCategory.TV -> Icons.Filled.Tv
    DeviceCategory.PRINTER -> Icons.Filled.Print
    DeviceCategory.CAMERA -> Icons.Filled.Videocam
    DeviceCategory.CLIMATE -> Icons.Filled.DeviceThermostat
    DeviceCategory.COVER -> Icons.Filled.Blinds
    DeviceCategory.LOCK -> Icons.Filled.Lock
    DeviceCategory.VACUUM -> Icons.Filled.CleaningServices
    DeviceCategory.FAN -> Icons.Filled.WindPower
    DeviceCategory.HUMIDIFIER -> Icons.Filled.WaterDrop
    DeviceCategory.WATER_HEATER -> Icons.Filled.Shower
    DeviceCategory.ENV_SENSOR -> Icons.Filled.DeviceThermostat
    DeviceCategory.POWER_SENSOR -> Icons.Filled.Bolt
    DeviceCategory.SENSOR -> Icons.Filled.Sensors
    DeviceCategory.UNKNOWN -> Icons.Filled.DevicesOther
}
