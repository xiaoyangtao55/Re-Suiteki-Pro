package com.github.sky130.suiteki.pro.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import com.github.sky130.suiteki.pro.device.huami.HuamiService.BASE_UUID
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppPacket.Companion.CHANNEL_MASS
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppPacket.Companion.CHANNEL_PROTO_RX
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppPacket.Companion.DATA_TYPE_ENCRYPTED
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppPacket.Companion.PACKET_PREAMBLE
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.String
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

class XiaomiSppSupport(device: XiaomiDevice) : XiaomiAbstractSupport(device), XiaomiChannelHandler {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    lateinit var dev: BluetoothDevice
    private val frameCounter = AtomicInteger(0)
    private val encryptionCounter = AtomicInteger(0)
    private val serviceUUID = UUID.fromString(String.format(BASE_UUID, "1101"))
    private lateinit var socket: BluetoothSocket
    private val scope get() = device.scope

    @Volatile
    private var mDisposed = false
    private val outputStream by lazy { socket.outputStream }
    private val inputStream by lazy { socket.inputStream }
    private val mChannelHandlers = HashMap<Int, XiaomiChannelHandler>()


    private val readThread = object : Thread("Read Thread") {
        override fun run() {
            val buffer = ByteArray(1024)
            var nRead: Int
            while (!mDisposed) {
                try {
                    inputStream?.let {
                        nRead = it.read(buffer)
                        if (nRead == -1) {
                            throw IOException("End of stream")
                        }
                        onSocketRead(buffer.copyOf(nRead))
                    }
                } catch (ex: IOException) {
                    break
                }
            }
            device.onDisconnect()
        }
    }

    private val writeHandlerThread = HandlerThread("Write Handler", THREAD_PRIORITY_BACKGROUND)

    /**
     * 懒加载 + 用 HandlerThread.looper 构造。
     *
     * 原来 writeHandler 是 lateinit，在 onLooperPrepared() 里赋值；而 start() 在
     * writeHandlerThread.start() 之后立刻 obtainMessage(0)，两者是异步的，
     * 慢一点的机型上会撞上 UninitializedPropertyAccessException（连不上，日志里只有一行崩溃）。
     * HandlerThread.getLooper() 本身会阻塞到 looper 就绪，所以这里不再有竞态；
     * 顺带也避免了同一个实例被 start 两次时 IllegalThreadStateException。
     */
    private val writeHandler: Handler by lazy {
        if (!writeHandlerThread.isAlive) {
            writeHandlerThread.start()
        }
        Handler(writeHandlerThread.looper) { msg ->
            handleWriteMessage(msg)
            true
        }
    }

    private fun handleWriteMessage(msg: Message) {
        when (msg.what) {
            0 -> {
                try {
                    socket.connect()
                    readThread.start()
                    device.auth()
                } catch (e: Exception) {
                    device.onDisconnect()
                    SuitekiManager.log(
                        e.message.toString(),
                        e.stackTrace.joinToString()
                    )
                }
            }

            1 -> {
                val obj = msg.obj as XiaomiSppPacket
                try {
                    outputStream?.let { stream ->
                        obj.encode(device.authService, encryptionCounter).let {
                            stream.write(it)
                            stream.flush()
                        }
                    }
                } catch (e: Exception) {
                    SuitekiManager.log(
                        "outputStreamError",
                        e.message.toString(),
                        e.stackTrace.joinToString()
                    )
                    if (::socket.isInitialized) {
                        SuitekiManager.log(
                            socket.isConnected
                        )
                    }
                }
            }

            2 -> {
                val obj = msg.obj as Pair<XiaomiSppPacket, () -> Unit>
                try {
                    outputStream?.let { stream ->
                        obj.first.encode(device.authService, encryptionCounter)
                            .let {
                                stream.write(it)
                                stream.flush()
                            }
                    }
                } catch (e: Exception) {
                    SuitekiManager.log(
                        "outputStreamError",
                        e.message.toString(),
                        e.stackTrace.joinToString()
                    )
                    if (::socket.isInitialized) {
                        SuitekiManager.log(
                            socket.isConnected
                        )
                    }
                } finally {
                    obj.second()
                }
            }
        }
    }

    init {
        mChannelHandlers[CHANNEL_PROTO_RX] = this
    }


    override fun handle(payload: ByteArray?) {
        payload?.let {
            try {
                device.handleCommand(XiaomiProto.Command.parseFrom(it))
            } catch (e: Exception) {
                return
            }
        }
    }


    val buffer: ByteArrayOutputStream = ByteArrayOutputStream()

    private fun onSocketRead(data: ByteArray) {
        SuitekiManager.log("onSocketRead", data)
        try {
            buffer.write(data)
        } catch (_: IOException) {
        }
        processBuffer()
    }

    private fun findNextPossiblePreamble(haystack: ByteArray): Int {
        var i = 1
        while (i + 2 < haystack.size) {
            // check if first byte matches
            if (haystack[i] == PACKET_PREAMBLE[0]) {
                return i
            }
            i++
        }

        // did not find preamble
        return -1
    }

    private fun processBuffer() {
        // wait until at least an empty packet is in the buffer
        while (buffer.size() >= 11) {
            // start preamble compare
            val bufferState = buffer.toByteArray()
            val headerBuffer = ByteBuffer.wrap(bufferState, 0, 7).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(PACKET_PREAMBLE.size)
            headerBuffer[preamble]

            if (!Arrays.equals(PACKET_PREAMBLE, preamble)) {
                val preambleOffset: Int = findNextPossiblePreamble(bufferState)

                if (preambleOffset == -1) {
                    buffer.reset()
                } else {
                    val remaining = ByteArray(bufferState.size - preambleOffset)
                    System.arraycopy(bufferState, preambleOffset, remaining, 0, remaining.size)
                    buffer.reset()
                    try {
                        buffer.write(remaining)
                    } catch (_: IOException) {
                    }
                }

                // continue processing at beginning of new buffer
                continue
            }

            headerBuffer.getShort() // skip flags and channel ID
            val payloadSize = headerBuffer.getShort().toInt() and 0xffff
            val packetSize = payloadSize + 8 // payload size includes payload header

            if (bufferState.size < packetSize) {
                return
            }
            val receivedPacket = XiaomiSppPacket.decode(bufferState) // remaining bytes unaffected

            onPacketReceived(receivedPacket)

            // extract remaining bytes from buffer
            val remaining = ByteArray(bufferState.size - packetSize)
            System.arraycopy(bufferState, packetSize, remaining, 0, remaining.size)

            buffer.reset()

            try {
                buffer.write(remaining)
            } catch (_: IOException) {
            }
        }
    }

    private fun onPacketReceived(packet: XiaomiSppPacket?) {
        if (packet == null) {
            return
        }
        var payload: ByteArray = packet.payload!!

        if (packet.dataType == 1) {
            payload = device.authService.decrypt(payload)
        }

        val channel: Int = packet.channel
        mChannelHandlers[channel]?.apply {
            handle(payload)
        }
    }

    /**
     * 原来这里只把 mDisposed 置 true，socket 不关、线程不停，而且全仓库没有调用点。
     * 结果是切设备后旧 socket 一直占着 RFCOMM 通道，新连接设备只认一条，
     * 表现为"连上了但不响应"，Read Thread / Write Handler 也会越积越多。
     */
    fun dispose() {
        if (mDisposed) {
            return
        }
        mDisposed = true
        // 先关 socket：read() 阻塞在这里，close 才能让它抛 IOException 退出循环
        if (::socket.isInitialized) {
            runCatching { socket.close() }
        }
        runCatching { readThread.interrupt() }
        runCatching { writeHandlerThread.quitSafely() }
        SuitekiManager.log("spp-disposed")
    }

    override fun terminate() {
        dispose()
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        SuitekiManager.log("onStartConnect")
        adapter.cancelDiscovery()
        dev = adapter.getRemoteDevice(device.mac)
        socket = dev.createInsecureRfcommSocketToServiceRecord(serviceUUID)
        // 第一次访问 writeHandler 时才会启动 HandlerThread 并等 looper 就绪
        writeHandler.obtainMessage(0).sendToTarget()
    }

    override fun sendCommand(command: XiaomiProto.Command) {
        SuitekiManager.log("sendCommand", command.toString())
        writeHandler.obtainMessage(
            1, XiaomiSppPacket.fromXiaomiCommand(
                command, frameCounter.getAndIncrement(), false
            )
        ).sendToTarget()
    }

    override fun sendDataChunk(data: ByteArray, onSend: () -> Unit) {
        writeHandler.obtainMessage(
            2,
            XiaomiSppPacket.newBuilder().channel(CHANNEL_MASS).needsResponse(false).flag(true)
                .opCode(2).frameSerial(frameCounter.getAndIncrement()).dataType(DATA_TYPE_ENCRYPTED)
                .payload(data).build() to onSend
        ).sendToTarget()
    }


}
