package org.wifiauditor.pro.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScanEntity::class, DeviceEntity::class, DeviceEvent::class, IpDeviceEntity::class],
    version = 3, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wifi_auditor.db")
                    .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
