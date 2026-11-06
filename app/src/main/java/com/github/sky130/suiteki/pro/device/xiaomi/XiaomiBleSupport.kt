package com.github.sky130.suiteki.pro.device.xiaomi

import android.bluetooth.BluetoothGatt
import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleMtuChangedCallback
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto.Command
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 小米 BLE 通道（fe95 / 0052）。
 *
 * 帧格式（与设备固件一致，改动前请先对照 Gadgetbridge 的 XiaomiCharacteristic）：
 *
 *   单帧   ::= 00 00 | 02 | 01 | seq(2, LE) | 密文
 *   分块头 ::= 00 00 | 00 | 加密标志 | 包数(2, LE)
 *   分块体 ::= 每包 [包序号(2, LE, 从 1 开始) | 最多 242 字节]
 *
 * 旧实现把 [seq + 密文] 算出来后又重新分配了 6 字节 buffer，载荷整段被丢弃，
 * 实际只发出一个包头，设备永远收不到命令（Band 8 连认证都过不去）。
 */
class XiaomiBleSupport(device: XiaomiDevice) : XiaomiAbstractSupport(device) {

    companion object {
        /** 一个协议帧的固定长度，含 2 字节分片序号 */
        private const val FRAME_SIZE = 244

        /** 协议头长度 */
        private const val HEADER_SIZE = 6

        /** Band 8 实测最常见的是 247（512 协商后被截断） */
        private const val REQUEST_MTU = 247
    }

    private lateinit var bleDevice: BleDevice
    private val manager get() = BleManager.getInstance()

    // 每次 start() 重建，避免 cancel 之后重连时协程再也起不来
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前链路协商到的 MTU，用于设置 FastBle 的分片大小 */
    var mtu: Int = 23
        private set

    /** 设备发起的分块传输：总包数与已收到的包（key 从 1 开始） */
    private var expectedChunks = 0
    private val receivedChunks = HashMap<Int, ByteArray>()

    /** 当前这轮分块是否加密，取自设备的分块开始帧 */
    private var chunkEncrypted = false

    private val uuids = mapOf(
        XiaomiService.UUID_SERVICE to listOf(XiaomiService.UUID_CHARACTERISTIC_COMMAND_READ),
    )

    override fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        manager.connect(device.mac, this)
    }

    override fun onStartConnect() {}

    override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
        SuitekiManager.log("connect failed", exception.description ?: "")
    }

    override fun onConnectSuccess(bleDevice: BleDevice, gatt: BluetoothGatt, status: Int) {
        scope.launch {
            this@XiaomiBleSupport.bleDevice = bleDevice
            // 244 字节的帧需要 MTU >= 247；协商不出来就退回 23，由 FastBle 按 20 字节拆
            mtu = withContext(Dispatchers.IO) { requestMtu(bleDevice, REQUEST_MTU) }
            val split = (mtu - 3).coerceIn(20, FRAME_SIZE)
            manager.splitWriteNum = split
            Log.d("TAG", "xiaomi mtu=$mtu splitWriteNum=$split")

            for ((service, characteristics) in uuids) {
                for (i in characteristics) {
                    manager.notify(bleDevice, service, i, object : BleNotifyCallback() {
                        override fun onNotifySuccess() {}

                        override fun onNotifyFailure(exception: BleException) {
                            SuitekiManager.log("notify failed", service, i, exception.description ?: "")
                        }

                        override fun onCharacteristicChanged(data: ByteArray) {
                            handleNotify(data)
                        }

                    })
                    delay(250)
                }
            }
            device.auth()
        }
    }

    override fun onDisConnected(
        isActiveDisConnected: Boolean, bleDevice: BleDevice, gatt: BluetoothGatt, status: Int
    ) {
        device.onDisconnect()
    }

    /**
     * 发送一条 proto 命令。
     *
     * 握手第一阶段（nonce）还没建立会话密钥，必须明文发送；之前的实现无条件加密，
     * 等于用全零密钥把第一条认证包加密了，设备无法解析。
     */
    override fun sendCommand(command: Command) {
        if (!::bleDevice.isInitialized) {
            SuitekiManager.log("sendCommand skipped", "not connected")
            return
        }
        val auth = device.authService
        val encrypt = device.isEncrypted && auth.encryptionInitialized
        val plain = command.toByteArray()
        val body = if (encrypt) auth.encrypt(plain, auth.encryptedIndex) else plain

        // 帧长跟着 MTU 走：MTU=247 时是 244，MTU 更小时等比例缩小
        val frameSize = (mtu - 3).coerceIn(20, FRAME_SIZE)
        val chunkPayload = frameSize - 2

        // 单帧：密文 + 6 字节头塞得下时走单帧，设备对短命令只认这种
        if (encrypt && body.size + HEADER_SIZE <= frameSize) {
            val frame = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(auth.encryptedIndex++.toShort())
                .put(body)
                .array()
            writeFrames(listOf(frame), "single command")
            return
        }

        // 分块：明文（握手）或密文过长，都走分块
        val encryptedFlag: Byte
        val payload: ByteArray
        if (encrypt) {
            encryptedFlag = 1
            payload = ByteBuffer.allocate(2 + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(auth.encryptedIndex++.toShort())
                .put(body)
                .array()
        } else {
            encryptedFlag = 0
            payload = plain
        }

        val count = (payload.size + chunkPayload - 1) / chunkPayload
        val frames = ArrayList<ByteArray>(count + 1)
        frames.add(
            ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0)
                .put(0.toByte())
                .put(encryptedFlag)
                .putShort(count.toShort())
                .array()
        )

        var offset = 0
        var index = 1
        while (offset < payload.size) {
            val end = minOf(offset + chunkPayload, payload.size)
            frames.add(
                ByteBuffer.allocate(2 + end - offset).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(index++.toShort())
                    .put(payload, offset, end - offset)
                    .array()
            )
            offset = end
        }
        writeFrames(frames, "chunked command")
    }

    /**
     * 接收方向同样有 4 字节头：[包号(2) | 类型(1) | 标志(1)]。
     * 旧实现直接把整包丢给 CCM 解密，连 ACK 都会被当成密文，必然抛异常。
     *
     * 类型：0 分块开始请求，1 分块 ACK，2 单帧命令，3 普通 ACK
     */
    private fun handleNotify(data: ByteArray) {
        if (data.size < 4) {
            SuitekiManager.log("notify too short", data.size)
            return
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val chunkId = buf.short.toInt()

        // 分块体：[包号从 1 开始 | 数据]
        if (chunkId != 0) {
            if (expectedChunks <= 0 || chunkId > expectedChunks) {
                SuitekiManager.log("unexpected chunk", chunkId, expectedChunks)
                return
            }
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            receivedChunks[chunkId] = bytes
            if (receivedChunks.size == expectedChunks) {
                val joined = ByteArrayOutputStream()
                for (i in 1..expectedChunks) {
                    val part = receivedChunks[i]
                    if (part == null) {
                        SuitekiManager.log("missing chunk", i)
                        expectedChunks = 0
                        receivedChunks.clear()
                        return
                    }
                    joined.write(part)
                }
                expectedChunks = 0
                receivedChunks.clear()
                writeRaw(byteArrayOf(0, 0, 1, 0))   // 分块结束 ACK
                // 用设备在分块开始帧里声明的加密标志，而不是本地状态：
                // 认证前设备发来的分块是明文，按 isEncrypted 去解会抛异常（然后被吞掉）。
                dispatch(joined.toByteArray(), chunkEncrypted)
            }
            return
        }

        when (val type = buf.get().toInt()) {
            0 -> {
                // 设备要发分块：标志(1) + 包数(2)，回一个开始 ACK
                if (buf.remaining() < 3) {
                    SuitekiManager.log("chunk start truncated", data.size)
                    return
                }
                chunkEncrypted = buf.get().toInt() == 1
                expectedChunks = buf.short.toInt()
                receivedChunks.clear()
                writeRaw(byteArrayOf(0, 0, 1, 1))
            }

            1 -> {
                // 分块 ACK，发送侧没做等待，这里只记录
                val subtype = if (buf.hasRemaining()) buf.get().toInt() else -1
                SuitekiManager.log("chunk ack", subtype)
            }

            2 -> {
                // 单帧命令：标志(1) + 载荷，标志为 1 表示密文
                if (!buf.hasRemaining()) {
                    SuitekiManager.log("command frame empty", data.size)
                    return
                }
                val encryption = buf.get().toInt()
                val rest = ByteArray(buf.remaining()).also { buf.get(it) }
                writeRaw(byteArrayOf(0, 0, 3, 0))   // 每收一条命令都要回 ACK
                dispatch(rest, encryption == 1)
            }

            3 -> {
                val result = if (buf.hasRemaining()) buf.get().toInt() else -1
                SuitekiManager.log("ack", result)
            }

            else -> SuitekiManager.log("unknown notify type", type, data.size)
        }
    }

    private fun dispatch(payload: ByteArray, encrypted: Boolean) {
        try {
            val plain = if (encrypted) device.authService.decrypt(payload) else payload
            device.handleCommand(Command.parseFrom(plain))
        } catch (e: Exception) {
            SuitekiManager.log("parse command failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeRaw(data: ByteArray) {
        if (!::bleDevice.isInitialized) return
        manager.write(
            bleDevice,
            XiaomiService.UUID_SERVICE,
            XiaomiService.UUID_CHARACTERISTIC_COMMAND_WRITE,
            data,
            object : BleWriteCallback() {
                override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {}

                override fun onWriteFailure(exception: BleException?) {
                    SuitekiManager.log("raw write failed", exception?.description ?: "")
                }
            }
        )
    }

    /**
     * 按顺序写帧：等上一帧的 onWriteSuccess 再写下一帧。
     * FastBle 自带队列，但失败是异步吞掉的，这里显式串行才能把失败暴露出来。
     */
    private fun writeFrames(frames: List<ByteArray>, task: String) {
        var index = 0

        fun sendNext() {
            if (index >= frames.size) {
                return
            }
            val frame = frames[index++]
            // FastBle 2.4.0 的 write 是 void，发送失败只会走 onWriteFailure
            manager.write(
                bleDevice,
                XiaomiService.UUID_SERVICE,
                XiaomiService.UUID_CHARACTERISTIC_COMMAND_WRITE,
                frame,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {
                        sendNext()
                    }

                    override fun onWriteFailure(exception: BleException?) {
                        SuitekiManager.log(
                            "write failed", task, "frame ${index - 1}/${frames.size}",
                            exception?.description ?: ""
                        )
                    }
                }
            )
        }

        sendNext()
    }

    override fun sendDataChunk(data: ByteArray, onSend: () -> Unit) {
        manager.write(
            bleDevice,
            XiaomiService.UUID_SERVICE,
            XiaomiService.UUID_CHARACTERISTIC_DATA_UPLOAD,
            data,
            object : BleWriteCallback() {
                override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {
                    onSend()
                }

                override fun onWriteFailure(exception: BleException?) {
                    SuitekiManager.log("upload write failed", exception?.description ?: "")
                }
            }
        )
    }

    private suspend fun requestMtu(target: BleDevice, value: Int): Int {
        val deferred = CompletableDeferred<Int>()
        manager.setMtu(target, value, object : BleMtuChangedCallback() {
            override fun onSetMTUFailure(exception: BleException) {
                completeOnce(deferred, 23)
            }

            override fun onMtuChanged(mtu: Int) {
                completeOnce(deferred, if (mtu > 23) mtu else 23)
            }
        })
        // 部分机型不回调 onMtuChanged，5 秒后按 23 处理
        return withTimeoutOrNull(5000) { deferred.await() } ?: 23
    }

    private fun completeOnce(deferred: CompletableDeferred<Int>, value: Int) {
        if (!deferred.isCompleted) {
            deferred.complete(value)
        }
    }

    fun write(data: ByteArray, service: String, characteristics: String) {
        if (!::bleDevice.isInitialized) {
            SuitekiManager.log("write skipped", "not connected")
            return
        }
        manager.write(bleDevice, service, characteristics, data, object : BleWriteCallback() {
            override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {}

            override fun onWriteFailure(exception: BleException?) {
                SuitekiManager.log("write failed", service, characteristics, exception?.description ?: "")
            }
        })
    }

    fun disconnect() {
        scope.cancel()
        if (::bleDevice.isInitialized) {
            manager.disconnect(bleDevice)
        }
    }

    override fun terminate() {
        disconnect()
    }
}
