package com.github.sky130.suiteki.pro.logic.database.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "suiteki_device", indices = [Index(value = ["mac"], unique = true)])
@Keep
data class Device(
    /** 用户起的备注名，只用于展示，不再参与驱动匹配 */
    val name: String,
    val mac: String,
    val key: String,
    /** 驱动标识，对应 @Suiteki(id = ...)。为空表示旧数据，连接时按名字兜底一次 */
    @ColumnInfo(defaultValue = "")
    val driverId: String = "",
) {
    @PrimaryKey(autoGenerate = true)
    var index: Int = 0
}