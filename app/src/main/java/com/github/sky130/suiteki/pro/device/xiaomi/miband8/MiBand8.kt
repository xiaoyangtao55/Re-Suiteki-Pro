package com.github.sky130.suiteki.pro.device.xiaomi.miband8

import com.github.sky130.suiteki.pro.logic.ble.Suiteki
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiDevice
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiBleSupport

@Suiteki(
    id = "band8",
    displayName = "小米手环 8",
    needsKey = true,
    hints = ["Xiaomi Smart Band 8"],
)
class MiBand8(name: String, mac: String, key: String) : XiaomiDevice(name, mac, key) {
    override val isEncrypted = true
    override val support = XiaomiBleSupport(this)
}