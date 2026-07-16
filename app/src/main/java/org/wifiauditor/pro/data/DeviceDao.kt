package org.wifiauditor.pro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Query("SELECT * FROM known_devices ORDER BY isApproved DESC, lastSeen DESC")
    suspend fun getAllDevices(): List<DeviceEntity>

    @Query("SELECT * FROM known_devices WHERE mac = :mac")
    suspend fun getDevice(mac: String): DeviceEntity?

    @Query("DELETE FROM known_devices WHERE mac = :mac")
    suspend fun deleteDevice(mac: String)

    @Insert
    suspend fun insertEvent(event: DeviceEvent)

    @Query("SELECT * FROM device_events ORDER BY timestamp DESC LIMIT 200")
    suspend fun getAllEvents(): List<DeviceEvent>

    @Query("SELECT * FROM device_events WHERE mac = :mac ORDER BY timestamp DESC LIMIT 50")
    suspend fun getDeviceEvents(mac: String): List<DeviceEvent>

    @Query("DELETE FROM device_events")
    suspend fun clearEvents()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIpDevice(device: IpDeviceEntity)

    @Query("SELECT * FROM ip_devices WHERE ipAddress = :ip")
    suspend fun getIpDevice(ip: String): IpDeviceEntity?

    @Query("SELECT * FROM ip_devices ORDER BY lastSeen DESC")
    suspend fun getAllIpDevices(): List<IpDeviceEntity>

    @Query("DELETE FROM ip_devices WHERE ipAddress = :ip")
    suspend fun deleteIpDevice(ip: String)
}
