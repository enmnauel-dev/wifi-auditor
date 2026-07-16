package org.wifiauditor.pro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    val name: String = "",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isApproved: Boolean = false,
    val ipAddress: String = "",
    val vendor: String = ""
)
