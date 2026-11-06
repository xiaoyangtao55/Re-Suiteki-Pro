package com.github.sky130.suiteki.pro.device.xiaomi

import androidx.compose.runtime.mutableStateListOf
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_RPK_DELETE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_RPK_LIST
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.RPK_COMMAND_TYPE
import com.github.sky130.suiteki.pro.logic.ble.AppInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.Command
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.RpkInfoList
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.RpkList
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.RpkMessage
import com.google.protobuf.ByteString

class XiaomiAppListHelper(val device: XiaomiDevice) {

    val list = mutableStateListOf<AppInfo>()
    private val shaMap = hashMapOf<String, ByteString>()

    fun handleCommand(command: Command) {
        if (command.type != RPK_COMMAND_TYPE) return
        when (command.subtype) {
            CMD_RPK_LIST -> {
                handleRpkList(command.rpkMessage.rpkList)
            }
        }
    }

    fun delete(id: String) {
        // 列表还没加载完就点删除、或点了已被别处删掉的条目时，shaMap 里可能没有这个 id。
        // 原来直接 shaMap[id]!! 会 NPE，后面的 removeAt(-1) 也会越界。
        val sha = shaMap[id]
        if (sha == null) {
            SuitekiManager.log("delete-skip", "$id 没有 sha，列表未就绪")
            return
        }
        device.support.sendCommand(
            Command.newBuilder().setType(RPK_COMMAND_TYPE).setSubtype(
                CMD_RPK_DELETE
            ).setRpkMessage(
                RpkMessage.newBuilder().setRpkDel(
                    RpkInfoList.newBuilder().setId(id)
                        .setSha(sha).build()
                ).build()
            ).build()
        )
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list.removeAt(index)
        shaMap.remove(id)
    }

    fun requestRpkList() {
        device.support.sendCommand(RPK_COMMAND_TYPE, CMD_RPK_LIST)
    }

    private fun handleRpkList(rpkList: RpkList) {
        list.clear()
        for (i in rpkList.rpkInfoList) {
            list.add(AppInfo(i.id, i.name))
            shaMap[i.id] = i.sha
        }
    }

}