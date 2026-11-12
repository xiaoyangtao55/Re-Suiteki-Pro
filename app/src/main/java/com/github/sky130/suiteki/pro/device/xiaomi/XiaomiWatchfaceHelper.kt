package com.github.sky130.suiteki.pro.device.xiaomi

import androidx.compose.runtime.mutableStateListOf
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_WATCHFACE_DELETE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_WATCHFACE_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_WATCHFACE_LIST
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.WATCHFACE_COMMAND_TYPE
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.ble.WatchfaceInfo
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto

/**
 * 表盘列表（命令类型 4）。
 *
 * 之前只有 RPK 列表，装了表盘在应用管理里看不见、也删不掉。
 * 设备在 4/0 里会把内置表盘一起报上来，用 [XiaomiProto.WatchfaceInfo.canDelete]
 * 区分能不能删——内置的一律不可删，界面不能只凭 id 猜。
 */
class XiaomiWatchfaceHelper(private val device: XiaomiDevice) {

    val list = mutableStateListOf<WatchfaceInfo>()

    fun handleCommand(command: XiaomiProto.Command) {
        if (command.type != WATCHFACE_COMMAND_TYPE) return
        when (command.subtype) {
            CMD_WATCHFACE_LIST -> {
                if (!command.hasWatchface() || !command.watchface.hasWatchfaceList()) return
                val parsed = parse(command.watchface.watchfaceList)
                list.clear()
                list.addAll(parsed)
                SuitekiManager.log(
                    "watchface-list",
                    "${parsed.size} 个，当前 ${parsed.firstOrNull { it.active }?.id ?: "-"}"
                )
            }

            // 删除是异步的：设备真正删完才回 4/2，此时重拉一次，
            // 保证界面和手环状态一致（而不是本地先删了、失败就再也对不上）。
            CMD_WATCHFACE_DELETE -> requestList()

            CMD_WATCHFACE_INSTALL -> SuitekiManager.log(
                "watchface-install-ack",
                command.watchface.installStatus.toString()
            )
        }
    }

    fun requestList() {
        device.support.sendCommand(WATCHFACE_COMMAND_TYPE, CMD_WATCHFACE_LIST)
    }

    fun delete(id: String) {
        val target = list.firstOrNull { it.id == id } ?: return
        if (!target.canDelete) {
            "内置表盘不能删除".toast()
            return
        }
        if (target.active) {
            "正在使用的表盘不能删除，先换一个".toast()
            return
        }
        device.support.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(WATCHFACE_COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_DELETE)
                .setWatchface(
                    XiaomiProto.Watchface.newBuilder().setWatchfaceId(id)
                )
                .build()
        )
    }

    companion object {
        fun parse(watchfaceList: XiaomiProto.WatchfaceList): List<WatchfaceInfo> =
            watchfaceList.watchfaceList.map { w ->
                WatchfaceInfo(
                    id = w.id,
                    // 用户自制的表盘有的不带名字，退回 id 总比空白强
                    name = w.name.trim().ifBlank { w.id },
                    active = w.active,
                    canDelete = w.canDelete,
                )
            }
    }
}
