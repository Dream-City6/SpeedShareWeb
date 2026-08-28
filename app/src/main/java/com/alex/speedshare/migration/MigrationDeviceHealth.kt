package com.alex.speedshare.migration

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class MigrationDeviceHealth(
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTemperatureC: Float?,
    val thermalStatus: Int
) {
    val batteryLabel: String
        get() = if (batteryPercent >= 0) "$batteryPercent%" else "未知"

    val temperatureLabel: String
        get() = batteryTemperatureC?.let { String.format("%.1f°C", it) } ?: "未知"

    fun recommendations(): List<String> = buildList {
        if (!charging) {
            add(
                if (batteryPercent in 0..29) {
                    "当前电量较低，建议接上充电器再进行长时间换机；不会限制继续。"
                } else {
                    "长时间换机建议接上充电器，避免中途因省电策略影响速度。"
                }
            )
        }
        val temperature = batteryTemperatureC
        if (temperature != null && temperature >= 42f) {
            add("设备当前偏热，建议保持散热；温度过高时 Android 可能主动降低 Wi‑Fi、CPU 或存储性能。")
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            add("系统报告设备温度较高，建议保持散热后再进行大容量迁移。")
        }
    }
}

internal object MigrationDeviceHealthReader {
    fun read(context: Context): MigrationDeviceHealth {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperatureTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temperature = temperatureTenths.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return MigrationDeviceHealth(
            batteryPercent = percent,
            charging = plugged != 0,
            batteryTemperatureC = temperature,
            thermalStatus = thermal
        )
    }
}
