package com.github.sky130.suiteki.pro.logic.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.logic.database.dao.DeviceDAO
import com.github.sky130.suiteki.pro.logic.database.model.Device

@Database(
    version = 12,
    entities = [Device::class],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun device(): DeviceDAO

    companion object {
        /** 11 -> 12：设备表加 driverId，旧记录留空，连接时按名字兜底一次 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE suiteki_device ADD COLUMN driverId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val instance by lazy {
            Room.databaseBuilder(
                context, AppDatabase::class.java, "app_database"
            ).apply {
                addMigrations(MIGRATION_11_12)
                fallbackToDestructiveMigration()
            }.build()
        }
    }
}