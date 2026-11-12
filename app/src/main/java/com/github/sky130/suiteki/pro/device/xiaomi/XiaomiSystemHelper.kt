package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_BATTERY
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_DEVICE_INFO
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_DEVICE_STATE_GET
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.SYSTEM_COMMAND_TYPE
import com.github.sky130.suiteki.pro.logic.ble.BatteryInfo
import com.github.sky130.suiteki.pro.logic.ble.HardwareInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto

/**
 * 小米系统服务（命令类型 2）里与「设备现在怎么样」相关的部分。
 *
 * 已接：
 * - 2/2 设备信息：serialNumber / firmware / model
 * - 2/1 电量：level / state（1 充电，2、3 未充电）
 * - 2/78 基本状态：batteryLevel / isCharging，部分机型只回这一条
 *
 * 2/79 是设备主动推的 DeviceState（只有充电/佩戴/睡眠，没有百分比），
 * 需要时在 [handleCommand] 里再加分支即可。
 */
class XiaomiSystemHelper(private val device: XiaomiDevice) {

    fun handleCommand(command: XiaomiProto.Command) {
        if (command.type != SYSTEM_COMMAND_TYPE) return
        when (command.subtype) {
            CMD_BATTERY -> {
                if (!command.hasSystem() || !command.system.hasPower()) return
                val power = command.system.power
                if (!power.hasBattery()) return
                val info = parseBattery(power.battery) ?: return
                device.applyBattery(info.level, info.charging)
                SuitekiManager.log("battery", "${info.level}% charging=${info.charging}")
            }

            CMD_DEVICE_STATE_GET -> {
                if (!command.hasSystem() || !command.system.hasBasicDeviceState()) return
                val info = parseBasicState(command.system.basicDeviceState) ?: return
                device.applyBattery(info.level, info.charging)
                SuitekiManager.log("device-state", "${info.level}% charging=${info.charging}")
            }

            CMD_DEVICE_INFO -> {
                if (!command.hasSystem() || !command.system.hasDeviceInfo()) return
                val info = parse(command.system.deviceInfo)
                device.applyHardware(info)
                SuitekiManager.log(
                    "device-info",
                    "fw=${info.firmware} model=${info.model} sn=${info.serial}"
                )
            }
        }
    }

    fun request() {
        device.support.sendCommand(SYSTEM_COMMAND_TYPE, CMD_DEVICE_INFO)
    }

    fun requestBattery() {
        device.support.sendCommand(SYSTEM_COMMAND_TYPE, CMD_BATTERY)
    }

    fun requestBasicState() {
        device.support.sendCommand(SYSTEM_COMMAND_TYPE, CMD_DEVICE_STATE_GET)
    }

    companion object {
        /** 抽成纯函数，单测可以直接构造 proto 验证字段映射 */
        fun parse(info: XiaomiProto.DeviceInfo): HardwareInfo = HardwareInfo(
            firmware = info.firmware.trim(),
            model = info.model.trim(),
            serial = info.serialNumber.trim(),
        )

        /**
         * 2/1。level 是百分比，state 是充电状态：
         * 1 = 充电中，2/3 = 未充电，其它值按「未知」处理（不猜）。
         */
        fun parseBattery(battery: XiaomiProto.Battery): BatteryInfo? {
            if (!battery.hasLevel()) return null
            val level = battery.level
            if (level !in 0..100) return null
            return BatteryInfo(level = level, charging = chargingOf(battery.state, battery.hasState()))
        }

        /** 2/78。batteryLevel 是可选的，没有就交给 2/1 去问，别写 0 */
        fun parseBasicState(state: XiaomiProto.BasicDeviceState): BatteryInfo? {
            if (!state.hasBatteryLevel()) return null
            val level = state.batteryLevel
            if (level !in 0..100) return null
            return BatteryInfo(level = level, charging = state.isCharging)
        }

        private fun chargingOf(state: Int, hasState: Boolean): Boolean? {
            if (!hasState) return null
            return when (state) {
                1 -> true
                2, 3 -> false
                else -> null
            }
        }
    }
}
