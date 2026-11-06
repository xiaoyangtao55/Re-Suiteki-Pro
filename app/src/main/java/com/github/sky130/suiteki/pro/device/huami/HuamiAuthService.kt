package com.github.sky130.suiteki.pro.device.huami

import com.github.sky130.suiteki.pro.logic.ble.DeviceStatus
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.util.BytesUtils
import com.github.sky130.suiteki.pro.util.CryptoUtils
import com.github.sky130.suiteki.pro.util.ECDH_B163
import org.apache.commons.lang3.ArrayUtils
import java.nio.ByteBuffer
import java.util.Random

class HuamiAuthService(private val device: HuamiDevice) {
    private lateinit var publicEC: ByteArray
    private lateinit var sharedEC: ByteArray
    /** 设备原始密钥，认证全程只读。会话密钥单独放 [authKey]，否则二次认证会拿上一轮的会话密钥去握手。 */
    private val deviceKey: ByteArray = BytesUtils.getSecretKey(device.key)
    private var authKey: ByteArray = deviceKey.copyOf()
    private var writeHandle: Byte = 0
    private var privateEC = ByteArray(24)
    private var remotePublicEC = ByteArray(48)
    private val remoteRandom = ByteArray(16)
    private val finalSharedSessionAES = ByteArray(16)
    private var encryptedSequenceNr = 0
    private var currentHandle: Byte? = null
    private var currentType = 0
    private var currentLength = 0
    private var reassemblyBuffer: ByteBuffer? = null


    fun startAuth() {
        // 每次认证都从原始密钥重新算会话密钥。安装途中遇到 10D203 会触发二次认证，
        // 不复位就会拿上一轮的 finalSharedSessionAES 去握手，设备必然回 10 05 25。
        authKey = deviceKey.copyOf()
        encryptedSequenceNr = 0
        currentHandle = null
        currentType = 0
        currentLength = 0
        reassemblyBuffer = null
        Random().nextBytes(privateEC)
        publicEC = ECDH_B163.ecdh_generate_public(privateEC)
        val sendPubkeyCommand = ByteArray(48 + 4)
        sendPubkeyCommand[0] = 0x04
        sendPubkeyCommand[1] = 0x02
        sendPubkeyCommand[2] = 0x00
        sendPubkeyCommand[3] = 0x02
        System.arraycopy(publicEC, 0, sendPubkeyCommand, 4, 48)
        huamiWrite(0x0082.toShort(), sendPubkeyCommand, true, false)
    }

    fun huamiWrite(
        type: Short, data: ByteArray, extendedFlags: Boolean,
        encrypt: Boolean,
    ) {
        var data = data
        if (encrypt && authKey == null) return
        writeHandle++
        var remaining = data.size
        val length = data.size
        var count: Byte = 0
        var header_size = 10
        if (extendedFlags) {
            header_size++
        }
        if (extendedFlags && encrypt) {
            val messagekey = ByteArray(16)
            for (i in 0..15) {
                messagekey[i] = (authKey[i].toInt() xor writeHandle.toInt()).toByte()
            }
            var encrypted_length = length + 8
            val overflow = encrypted_length % 16
            if (overflow > 0) {
                encrypted_length += 16 - overflow
            }
            val encryptable_payload = ByteArray(encrypted_length)
            System.arraycopy(data, 0, encryptable_payload, 0, length)
            encryptable_payload[length] = (encryptedSequenceNr and 0xff).toByte()
            encryptable_payload[length + 1] = (encryptedSequenceNr shr 8 and 0xff).toByte()
            encryptable_payload[length + 2] = (encryptedSequenceNr shr 16 and 0xff).toByte()
            encryptable_payload[length + 3] = (encryptedSequenceNr shr 24 and 0xff).toByte()
            encryptedSequenceNr++
            val checksum: Int = BytesUtils.getCRC32(encryptable_payload, 0, length + 4)
            encryptable_payload[length + 4] = (checksum and 0xff).toByte()
            encryptable_payload[length + 5] = (checksum shr 8 and 0xff).toByte()
            encryptable_payload[length + 6] = (checksum shr 16 and 0xff).toByte()
            encryptable_payload[length + 7] = (checksum shr 24 and 0xff).toByte()
            remaining = encrypted_length
            data = try {
                CryptoUtils.encryptAES(encryptable_payload, messagekey)
            } catch (e: Exception) {
                return
            }
        }
        while (remaining > 0) {
            val MAX_CHUNKLENGTH = 20 - header_size
            val copyBytes = Math.min(remaining, MAX_CHUNKLENGTH)
            val chunk = ByteArray(copyBytes + header_size)
            var flags: Byte = 0
            if (encrypt) {
                flags = (flags.toInt() or 0x08).toByte()
            }
            if (count.toInt() == 0) {
                flags = (flags.toInt() or 0x01).toByte()
                var i = 4
                if (extendedFlags) {
                    i++
                }
                chunk[i++] = (length and 0xff).toByte()
                chunk[i++] = (length shr 8 and 0xff).toByte()
                chunk[i++] = (length shr 16 and 0xff).toByte()
                chunk[i++] = (length shr 24 and 0xff).toByte()
                chunk[i++] = (type.toInt() and 0xff).toByte()
                chunk[i] = (type.toInt() shr 8 and 0xff).toByte()
            }
            if (remaining <= MAX_CHUNKLENGTH) {
                flags = (flags.toInt() or 0x06).toByte() // last chunk?
            }
            chunk[0] = 0x03
            chunk[1] = flags
            if (extendedFlags) {
                chunk[2] = 0
                chunk[3] = writeHandle
                chunk[4] = count
            } else {
                chunk[2] = writeHandle
                chunk[3] = count
            }
            System.arraycopy(data, data.size - remaining, chunk, header_size, copyBytes)
            write(
                chunk,
                HuamiService.UUID_SERVICE_MIBAND_SERVICE,
                HuamiService.UUID_CHARACTERISTIC_AUTH_WRITE
            )
            remaining -= copyBytes
            header_size = 4
            if (extendedFlags) {
                header_size++
            }
            count++
        }
    }

    private fun decode(data: ByteArray) {
        // 最小帧头是 5 字节。设备异常重连 / 半包 notify 时会推空包或短包，
        // 原来直接 data[0] 就 ArrayIndexOutOfBounds。
        if (data.size < 5) {
            return
        }
        var i = 0
        if (data[i++].toInt() != 0x03) {
            return
        }
        val flags = data[i++]
        val encrypted = flags.toInt() and 0x08 == 0x08
        val firstChunk = flags.toInt() and 0x01 == 0x01
        val lastChunk = flags.toInt() and 0x02 == 0x02
        i++
        val handle = data[i++]
        if (currentHandle != null && currentHandle != handle) {
            return
        }
        val count = data[i++]
        if (firstChunk) { // beginning
            // 首块还要带 4 字节长度 + 2 字节类型
            if (data.size < i + 6) {
                return
            }
            var full_length =
                data[i++].toInt() and 0xff or (data[i++].toInt() and 0xff shl 8) or (data[i++].toInt() and 0xff shl 16) or (data[i++].toInt() and 0xff shl 24)
            currentLength = full_length
            if (encrypted) {
                var encrypted_length = full_length + 8
                val overflow = encrypted_length % 16
                if (overflow > 0) {
                    encrypted_length += 16 - overflow
                }
                full_length = encrypted_length
            }
            reassemblyBuffer = ByteBuffer.allocate(full_length)
            currentType = data[i++].toInt() and 0xff or (data[i++].toInt() and 0xff shl 8)
            currentHandle = handle
        }
        // 非首块但缓冲还没建立（首块丢了），直接丢弃，不要 NPE
        val buffer = reassemblyBuffer ?: return
        val copySize = minOf(data.size - i, buffer.remaining())
        if (copySize > 0) {
            buffer.put(data, i, copySize)
        }
        if (lastChunk) { // end
            var buf = reassemblyBuffer!!.array()
            if (encrypted) {
                if (authKey == null) {
                    currentHandle = null
                    currentType = 0
                    return
                }
                val messagekey = ByteArray(16)
                for (j in 0..15) {
                    messagekey[j] = (authKey!![j].toInt() xor handle.toInt()).toByte()
                }
                try {
                    buf = CryptoUtils.decryptAES(buf, messagekey)
                    buf = ArrayUtils.subarray(buf, 0, currentLength)
                } catch (e: Exception) {
                    e.printStackTrace()
                    currentHandle = null
                    currentType = 0
                    return
                }
            }
            try {
                val finalBuf = buf
                handle2021Payload(currentType.toShort(), finalBuf)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentHandle = null
            currentType = 0
        }
    }

    private fun handle2021Payload(type: Short, payload: ByteArray) {
        device.handlePayload(payload)
        if (payload[0].toInt() == 0x10 && payload[1].toInt() == 0x04 && payload[2].toInt() == 0x01) {
            System.arraycopy(payload, 3, remoteRandom, 0, 16)
            System.arraycopy(payload, 19, remotePublicEC, 0, 48)
            sharedEC = ECDH_B163.ecdh_generate_shared(privateEC, remotePublicEC)
            val encryptedSequenceNumber =
                sharedEC[0].toInt() and 0xff or (sharedEC[1].toInt() and 0xff shl 8) or (sharedEC[2].toInt() and 0xff shl 16) or (sharedEC[3].toInt() and 0xff shl 24)
            val secretKey = authKey
            for (i in 0..15) {
                finalSharedSessionAES[i] =
                    (sharedEC[i + 8].toInt() xor secretKey!![i].toInt()).toByte()
            }
            encryptedSequenceNr = encryptedSequenceNumber
            authKey = finalSharedSessionAES
            try {
                val encryptedRandom1: ByteArray = CryptoUtils.encryptAES(remoteRandom, secretKey)
                val encryptedRandom2: ByteArray =
                    CryptoUtils.encryptAES(remoteRandom, finalSharedSessionAES)
                if (encryptedRandom1.size == 16 && encryptedRandom2.size == 16) {
                    val command = ByteArray(33)
                    command[0] = 0x05
                    System.arraycopy(encryptedRandom1, 0, command, 1, 16)
                    System.arraycopy(encryptedRandom2, 0, command, 17, 16)
                    huamiWrite(0x0082.toShort(), command, true, false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (payload[0].toInt() == 0x10 && payload[1].toInt() == 0x05 && payload[2].toInt() == 0x01) {
            device.status.value = DeviceStatus.Connected
            device.onAuth()
            return
        } else if (payload.size >= 3 && payload[0].toInt() == 0x10 && payload[1].toInt() == 0x05) {
            // 设备明确回绝了认证，payload[2] 是错误码。0x25 = 密钥校验不通过，
            // 这是"密钥不对"而不是"设备没响应"，必须让用户看到区别。
            val code = payload[2].toInt() and 0xff
            val reason = when (code) {
                0x25 -> "授权密钥与设备不匹配（0x25），请在设备详情中填写这台手环的专用密钥"
                else -> "设备拒绝认证，错误码 0x%02X".format(code)
            }
            SuitekiManager.log("auth-failed", reason)
            device.onAuthFailure(reason)
            return
        } else if (payload[0].toInt() == 0x10) {
            // 只有 0x10 前缀才是认证命令。0x00a0（应用列表）、0x0026（显示项）之类的
            // 业务响应也会进到这里，不能因为"不是认证命令"就把状态打成认证失败。
            if (device.status.value == DeviceStatus.Authing) device.status.value =
                DeviceStatus.AuthFailure
        }
    }

    fun write(bytes: ByteArray, service: String, characteristics: String) {
        SuitekiManager.log("write", service, characteristics, bytes)
        device.write(bytes, service, characteristics)
    }

    fun handleData(bytes: ByteArray, service: String, characteristics: String) {
        if (service != HuamiService.UUID_SERVICE_MIBAND_SERVICE &&
            characteristics != HuamiService.UUID_CHARACTERISTIC_AUTH_NOTIFY
        ) return
        SuitekiManager.log("handleData", service, characteristics, bytes)
        decode(bytes)
    }
}