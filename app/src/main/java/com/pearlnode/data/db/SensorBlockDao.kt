package com.pearlnode.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pearlnode.model.SensorBlock
import com.pearlnode.model.SensorKind
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorBlockDao {
    /**
     * Overlapping, not contained: a block that began before the window still
     * describes the part of it that falls inside.
     */
    @Query("""
        SELECT * FROM sensor_blocks
        WHERE deviceId = :deviceId AND kind = :kind
          AND startUtc < :toUtc AND startUtc + durationSec > :fromUtc
        ORDER BY startUtc ASC
    """)
    fun observeRange(deviceId: String, kind: SensorKind, fromUtc: Long, toUtc: Long): Flow<List<SensorBlock>>

    @Query("SELECT MIN(startUtc) FROM sensor_blocks WHERE deviceId = :deviceId")
    suspend fun earliestStart(deviceId: String): Long?

    /**
     * Where the copy reaches to, which is where the next fetch starts.
     *
     * The last block of a fetch has no successor yet, so its duration is a
     * guess that the next fetch corrects -- hence the start rather than the end
     * of the newest block: re-reading the last point costs one row and makes
     * the guess right.
     */
    @Query("SELECT MAX(startUtc) FROM sensor_blocks WHERE deviceId = :deviceId AND kind = :kind")
    suspend fun latestStart(deviceId: String, kind: SensorKind): Long?

    @Query("SELECT COUNT(*) FROM sensor_blocks WHERE deviceId = :deviceId")
    suspend fun count(deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<SensorBlock>)

    @Query("DELETE FROM sensor_blocks WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: String)
}
