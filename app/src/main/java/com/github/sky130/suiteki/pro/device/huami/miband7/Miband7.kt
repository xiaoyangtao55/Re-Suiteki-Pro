package com.github.sky130.suiteki.pro.device.huami.miband7

import com.github.sky130.suiteki.pro.logic.ble.Suiteki
import com.github.sky130.suiteki.pro.device.huami.HuamiDevice

// Band 7 的表盘/固件走 0x1530/1531/1532，不需要授权密钥；hints 只用于扫描时的型号推荐。
@Suiteki(
    id = "band7",
    displayName = "小米手环 7",
    needsKey = false,
    hints = ["Xiaomi Smart Band 7"],
)
class Miband7(name: String, mac: String, key: String) : HuamiDevice(name, mac, key)