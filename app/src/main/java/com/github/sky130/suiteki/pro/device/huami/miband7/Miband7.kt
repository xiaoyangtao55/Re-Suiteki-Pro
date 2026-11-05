package com.github.sky130.suiteki.pro.device.huami.miband7

import com.github.sky130.suiteki.pro.logic.ble.Suiteki
import com.github.sky130.suiteki.pro.device.huami.HuamiDevice

// 广播名后缀不一定有四位，大小写也不固定；匹配失败时 SuitekiManager 是静默跳过的，
// 所以这里放宽。用 (?! Pro) 排除 Band 7 Pro，避免抢注册。
@Suiteki(pattern = "(?i)^Xiaomi Smart Band 7(?! Pro)( [a-z0-9]{4})?$")
class Miband7(name: String, mac: String, key: String) : HuamiDevice(name, mac, key)