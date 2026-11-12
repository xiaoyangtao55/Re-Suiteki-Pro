package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_DEVICE_INFO
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.SYSTEM_COMMAND_TYPE
import com.github.sky130.suiteki.pro.logic.ble.HardwareInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto

/**
 * 小米系统服务（命令类型 2）里与「设备是什么」相关的部分。
 *
 * 目前只接 2/2（设备信息）：设备回 serialNumber / firmware / model。
 * 电量（2/1）、状态（2/78、2/79）、显示项（2/29）都在这条总线上，
 * 要加就在 [handleCommand] 里再开一个分支，不用改通道。
 */
class XiaomiSystemHelper(private val device: XiaomiDevice) {

    fun handleCommand(command: XiaomiProto.Command) {
        if (command.type != SYSTEM_COMMAND_TYPE) return
        when (command.subtype) {
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

    companion object {
        /** 抽成纯函数，单测可以直接构造 proto 验证字段映射 */
        fun parse(info: XiaomiProto.DeviceInfo): HardwareInfo = HardwareInfo(
            firmware = info.firmware.trim(),
            model = info.model.trim(),
            serial = info.serialNumber.trim(),
        )
    }
}
