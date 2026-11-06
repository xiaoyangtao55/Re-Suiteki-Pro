package com.github.sky130.suiteki.pro.device.huami

import android.bluetooth.BluetoothGatt
import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.callback.BleMtuChangedCallback
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 华米链路。
 *
 * 关键顺序（与 1.5.1 实测可跑的顺序一致，不能改）：
 *   connect → requestMtu(512) → 等 onMtuChanged → 逐个订阅 notify → auth()
 *
 * 原实现用 FastBle 默认 MTU(23) 就去订阅 + 认证。认证层分片刚好是 20 字节所以没暴露问题；
 * 但安装层按 244 字节写，MTU 23 时 writeCharacteristic 直接返回 false，
 * onWriteFailure 又是空的 —— 表现就是点安装后进度条停在 0%。
 */
class HuamiBleSupport(private val device: HuamiDevice) : BleGattCallback() {
    private lateinit var bleDevice: BleDevice
    private val manager get() = BleManager.getInstance()
    // 每次连接重建一个 scope：disconnect() 会 cancel，复用同一个 Job 会导致重连后协程不再执行
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前链路实际协商到的 MTU，安装层用它算分片大小 */
    var mtu: Int = 23
        private set

    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        manager.connect(device.mac, this)
    }

    override fun onStartConnect() {}

    override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
        device.onConnectFailed(exception.description ?: "连接失败")
    }

    override fun onConnectSuccess(bleDevice: BleDevice, gatt: BluetoothGatt, status: Int) {
        this.bleDevice = bleDevice
        scope.launch {
            // 1. MTU：必须等协商结果；失败退回 23（安装会慢，但不会写不出去）
            mtu = withContext(Dispatchers.IO) { requestMtu(bleDevice, 512) }
            Log.d("TAG", "mtu negotiated: $mtu")

            // 2. 逐个订阅，中间留 250ms，订阅太快设备会丢
            for ((service, characteristics) in device.uuids) {
                for (i in characteristics) {
                    manager.notify(bleDevice, service, i, object : BleNotifyCallback() {
                        override fun onNotifySuccess() {}

                        override fun onNotifyFailure(exception: BleException) {
                            Log.d("TAG", "notify failed: $service / $i / ${exception.description}")
                        }

                        override fun onCharacteristicChanged(data: ByteArray) {
                            device.handleChannel(data, service, i)
                        }
                    })
                    delay(250)
                }
            }

            // 3. Band 7 的固件/表盘通道（0x1530/1531/1532）不校验 authKey。
            //    1.8.x 连接后直接发 D0，全包没有 0x0082 握手，也没有密钥输入框。
            //    这里若继续强制认证，设备回 10 05 25 会把状态打成 AuthFailure，安装入口直接被挡死。
            device.onChannelReady()
        }
    }

    override fun onDisConnected(
        isActiveDisConnected: Boolean, device: BleDevice, gatt: BluetoothGatt, status: Int
    ) {
        this@HuamiBleSupport.device.onDisconnect()
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
        // 部分机型不回调 onMtuChanged，6 秒后按 23 处理，不能卡死在这
        return withTimeoutOrNull(6000) { deferred.await() } ?: 23
    }

    private fun completeOnce(deferred: CompletableDeferred<Int>, value: Int) {
        if (!deferred.isCompleted) {
            deferred.complete(value)
        }
    }

    fun write(data: ByteArray, service: String, characteristics: String) {
        if (!::bleDevice.isInitialized) {
            Log.d("TAG", "write skipped: not connected")
            return
        }
        manager.write(bleDevice, service, characteristics, data, object : BleWriteCallback() {
            override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {}

            override fun onWriteFailure(exception: BleException?) {
                Log.d("TAG", "write failed: ${exception?.description}")
            }
        })
    }

    fun disconnect() {
        scope.cancel()
        if (::bleDevice.isInitialized) {
            manager.disconnect(bleDevice)
        }
    }
}
