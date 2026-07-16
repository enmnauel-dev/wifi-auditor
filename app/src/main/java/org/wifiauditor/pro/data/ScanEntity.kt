package org.wifiauditor.pro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val auth: String,
    val cipher: String,
    val band: String,
    val score: Int,
    val risk: String,
    val vendor: String
)
