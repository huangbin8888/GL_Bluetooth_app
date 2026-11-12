package com.guanglu.caliper.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.guanglu.caliper.core.database.entity.Batch
import com.guanglu.caliper.core.database.entity.Device
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.database.entity.Template
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    /** 7 路并发写库用单事务批量提交（基线 10.3）；成功返回自增 id 后才更新 UI 计数。 */
    @Insert
    suspend fun insertAll(items: List<Measurement>): List<Long>

    @Query("SELECT * FROM measurement ORDER BY captured_at DESC")
    fun observeAll(): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement WHERE batch_id = :batchId ORDER BY captured_at DESC")
    fun observeBatch(batchId: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement WHERE channel_no = :channelNo ORDER BY captured_at DESC")
    fun observeChannel(channelNo: Int): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement ORDER BY captured_at DESC")
    suspend fun all(): List<Measurement>

    @Query("SELECT COUNT(*) FROM measurement")
    fun observeCount(): Flow<Int>

    @Query("UPDATE measurement SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM measurement WHERE channel_no = :channelNo ORDER BY elapsed_realtime DESC LIMIT 1")
    suspend fun latestOfChannel(channelNo: Int): Measurement?
}

@Dao
interface BatchDao {
    @Insert
    suspend fun insert(batch: Batch): Long

    @Query("SELECT * FROM batch ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Batch>>

    @Query("SELECT * FROM batch WHERE id = :id")
    suspend fun find(id: Long): Batch?
}

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: Template): Long

    @Query("SELECT * FROM template ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Template>>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: Device)

    @Query("SELECT * FROM device WHERE mac = :mac")
    suspend fun find(mac: String): Device?

    @Query("SELECT * FROM device ORDER BY last_connected_at DESC")
    fun observeAll(): Flow<List<Device>>

    @Query("UPDATE device SET is_last_used = 0")
    suspend fun clearLastUsed()

    @Transaction
    @Query("UPDATE device SET is_last_used = 1 WHERE mac = :mac")
    suspend fun markLastUsed(mac: String)
}
