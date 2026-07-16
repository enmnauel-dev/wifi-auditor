package org.wifiauditor.pro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_events")
data class DeviceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val ip: String = "",
    val event: String,
    val timestamp: Long = System.currentTimeMillis()
)
