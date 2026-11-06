package com.github.sky130.suiteki.pro.device.xiaomi.miband8pro

import com.github.sky130.suiteki.pro.logic.ble.Suiteki
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiDevice
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppSupport

@Suiteki(
    id = "band8pro",
    displayName = "小米手环 8 Pro",
    needsKey = true,
    hints = ["Xiaomi Smart Band 8 Pro"],
)
class MiBand8Pro(name: String, mac: String, key: String) : XiaomiDevice(
    name, mac, key,
) {
    override val isEncrypted = true
    override val support = XiaomiSppSupport(this)
}