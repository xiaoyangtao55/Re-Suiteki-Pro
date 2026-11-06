package com.github.sky130.suiteki.pro.device.xiaomi.watchs3

import com.github.sky130.suiteki.pro.logic.ble.Suiteki
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiDevice
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiSppSupport

@Suiteki(
    id = "watchs3",
    displayName = "Xiaomi Watch S3",
    needsKey = true,
    hints = ["Xiaomi Watch S3"],
)
class WatchS3(name: String, mac: String, key: String) : XiaomiDevice(
    name, mac, key,
) {
    override val isEncrypted = true
    override val support = XiaomiSppSupport(this)
}