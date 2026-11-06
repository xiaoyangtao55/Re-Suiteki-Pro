package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_FIRMWARE_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_RPK_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_UPLOAD_START
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_WATCHFACE_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.RPK_COMMAND_TYPE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.SYSTEM_COMMAND_TYPE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_FIRMWARE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_UNKNOWN
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.UPLOAD_COMMAND_TYPE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.WATCHFACE_COMMAND_TYPE
import com.github.sky130.suiteki.pro.logic.ble.InstallStatus
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.Command
import com.github.sky130.suiteki.pro.util.CheckSums
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.ArrayUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

class XiaomiInstallHelper(val device: XiaomiDevice, private val fw: ByteArray) {
    private val helper by lazy { XiaomiFWHelper(fw) }
    private val support get() = device.support
    private var chunkSize = 2048


    fun install() {
        device.scope.launch(Dispatchers.IO) {
            helper.init()
            // 空文件或格式不认识时，helper.type 还是 -1，发出去只会让设备困惑
            if (helper.fileType == TYPE_UNKNOWN) {
                installFailure(0, "无法识别的文件格式，请确认是表盘 / 固件 / 快应用包")
                return@launch
            }
            if (helper.fileType == TYPE_FIRMWARE) {
                support.sendCommand(
                    Command.newBuilder()
                        .setType(helper.type)
                        .setSubtype(helper.subType)
                        .setSystem(
                            XiaomiProto.System.newBuilder()
                                .setFirmwareInstallRequest(
                                    XiaomiProto.FirmwareInstallRequest.newBuilder()
                                        .setUnknown1(0)
                                        .setUnknown2(0)
                                        .setVersion(helper.versionName)
                                        .setMd5(helper.md5)
                                        .build()
                                ).build()
                        ).build()
                )
                return@launch
            }
            support.sendCommand(
                Command.newBuilder().setType(helper.type).setSubtype(helper.subType)
                    .apply {
                        when (helper.type) {
                            WATCHFACE_COMMAND_TYPE -> {
                                setWatchface(
                                    XiaomiProto.Watchface.newBuilder().setWatchfaceInstallStart(
                                        XiaomiProto.WatchfaceInstallStart.newBuilder()
                                            .setId(helper.id).setSize(helper.bytes!!.size)
                                    )
                                )
                            }

                            RPK_COMMAND_TYPE -> {
                                setRpkMessage(
                                    XiaomiProto.RpkMessage.newBuilder().setRpkInfo(
                                        XiaomiProto.RpkInfo.newBuilder().setId(helper.id)
                                            .setSize(helper.bytes!!.size).setUnknown2(3)
                                    )
                                )
                            }
                        }
                    }.build()
            )
        }
    }

    private fun requestUpload() {
        support.sendCommand(
            Command.newBuilder()
                .setType(UPLOAD_COMMAND_TYPE)
                .setSubtype(CMD_UPLOAD_START)
                .setDataUpload(
                    XiaomiProto.DataUpload.newBuilder()
                        .setDataUploadRequest(
                            XiaomiProto.DataUploadRequest.newBuilder()
                                .setType(helper.fileType)
                                .setMd5Sum(ByteString.copyFrom(CheckSums.md5(fw)))
                                .setSize(fw.size)
                        )
                ).build()
        )
    }

    private fun handleWatchFaceCommand(cmd: Command) {
        when (cmd.subtype) {
            CMD_WATCHFACE_INSTALL -> {
                requestUpload()
            }
        }
    }

    private fun handleUploadCommand(cmd: Command) {
        when (cmd.subtype) {

            CMD_UPLOAD_START -> {
                val dataUploadAck = cmd.dataUpload.dataUploadAck
//
//                if (dataUploadAck.unknown2 != 0 || dataUploadAck.resumePosition != 0) {
//                    installFailure(0, "Unknown Error")
//                    return
//                }


                if (dataUploadAck.unknown2 != 0) {
                    installFailure(0, "Unknown Error")
                    return
                }

                chunkSize = if (dataUploadAck.hasChunkSize()) {
                    dataUploadAck.chunkSize
                } else {
                    2048
                }

                doUpload(dataUploadAck.resumePosition)
            }
        }
    }

    private fun handleRpkCommand(cmd: Command) {
        when (cmd.subtype) {
            CMD_RPK_INSTALL -> {
                requestUpload()
            }
        }
    }

    private fun handleSystemCommand(cmd: Command) {
        when (cmd.subtype) {
            CMD_FIRMWARE_INSTALL -> {
                requestUpload()
            }
        }
    }

    fun handleCommand(cmd: Command) {
        when (cmd.type) {
            WATCHFACE_COMMAND_TYPE -> handleWatchFaceCommand(cmd)
            RPK_COMMAND_TYPE -> handleRpkCommand(cmd)
            SYSTEM_COMMAND_TYPE -> handleSystemCommand(cmd)
            UPLOAD_COMMAND_TYPE -> handleUploadCommand(cmd)
        }
    }

    private fun doUpload(index: Int = 0) {
        SuitekiManager.log("doUpload")
        // type + md5 + size + bytes + crc32
        val buf1 = ByteBuffer.allocate(2 + 16 + 4 + fw.size  - index).order(ByteOrder.LITTLE_ENDIAN)
        val md5 = CheckSums.md5(fw)
        if (md5 == null) {
            installFailure(0, "MD5 Missing")
            return
        }

        buf1.put(0.toByte())
        // 这里要的是"文件类型"（表盘 16 / 固件 32 / 小程序 64），不是命令子类型。
        // 原来写的是 helper.subType（表盘是 4），设备按 4 去解析上传流，校验必然不过。
        buf1.put(helper.fileType.toByte())
        buf1.put(md5)
        buf1.putInt(fw.size)
        buf1.put(ArrayUtils.subarray(fw, index, fw.size))

        val buf2 = ByteBuffer.allocate(buf1.capacity() + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf2.put(buf1.array())
        buf2.putInt(CheckSums.getCRC32(buf1.array()))

        val payload = buf2.array()
        val partSize = chunkSize - 4 // 2 + 2 at beginning of each for total and progress
        val totalParts = ceil((payload.size / partSize.toFloat()).toDouble()).toInt()

        // 一次只发一片，等这一片写完成再发下一片。
        // 原来是 while 循环把全部分片一次性塞进 FastBle 的写队列（300KB 表盘约 147 片），
        // 队列深度不够时要么丢包要么 write busy，而 onWriteFailure 是空的，失败完全不可见。
        sendPart(payload, partSize, totalParts, 1)
    }

    private fun sendPart(payload: ByteArray, partSize: Int, totalParts: Int, currentPart: Int) {
        if (currentPart > totalParts) {
            return
        }
        val startIndex = (currentPart - 1) * partSize
        if (startIndex >= payload.size) {
            return
        }
        val endIndex = min(currentPart.toDouble() * partSize, payload.size.toDouble()).toInt()
        val chunkToSend = ByteArray(4 + (endIndex - startIndex))
        writeUint16(chunkToSend, 0, totalParts)
        writeUint16(chunkToSend, 2, currentPart)
        System.arraycopy(payload, startIndex, chunkToSend, 4, endIndex - startIndex)

        support.sendDataChunk(chunkToSend) {
            val progress = ((currentPart.toFloat() / totalParts.toFloat()) * 100).toInt()
            SuitekiManager.log("doUploadProgress", progress)
            if (currentPart >= totalParts) {
                installSuccess(100)
            } else {
                updateProgress(progress)
                sendPart(payload, partSize, totalParts, currentPart + 1)
            }
        }
    }

    private fun updateProgress(progress: Int) {
        SuitekiManager.installStatus.value = InstallStatus.Installing(progress)
    }

    private fun installFailure(progress: Int, messages: String) {
        SuitekiManager.installStatus.value = InstallStatus.InstallFailure(progress, messages)
        device.onInstallFinish()
    }

    private fun installSuccess(progress: Int) {
        SuitekiManager.installStatus.value = InstallStatus.InstallSuccess(progress)
        device.onInstallFinish()
    }

    fun writeUint16(array: ByteArray, offset: Int, value: Int) {
        array[offset] = value.toByte()
        array[offset + 1] = (value shr 8).toByte()
    }

}