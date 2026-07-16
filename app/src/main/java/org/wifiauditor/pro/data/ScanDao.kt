package org.wifiauditor.pro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScanDao {
    @Insert
    suspend fun insertAll(scans: List<ScanEntity>)

    @Query("SELECT * FROM scans ORDER BY timestamp DESC LIMIT 500")
    suspend fun getAll(): List<ScanEntity>

    @Query("SELECT DISTINCT timestamp FROM scans ORDER BY timestamp DESC LIMIT 100")
    suspend fun getScanTimestamps(): List<Long>

    @Query("DELETE FROM scans")
    suspend fun clearAll()
}
