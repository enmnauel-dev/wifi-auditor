package org.wifiauditor.pro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ip_devices")
data class IpDeviceEntity(
    @PrimaryKey val ipAddress: String,
    val name: String = "",
    val vendor: String = "",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)
