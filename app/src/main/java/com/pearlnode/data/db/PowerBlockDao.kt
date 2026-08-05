package com.pearlnode.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pearlnode.model.PowerBlock
import kotlinx.coroutines.flow.Flow

@Dao
interface PowerBlockDao {
    /**
     * Overlapping, not contained: a day block that begins before the window
     * still says something about the part of it that falls inside, and the
     * merge splits it proportionally.
     */
    @Query("""
        SELECT * FROM power_blocks
        WHERE deviceId = :deviceId AND startUtc < :toUtc AND startUtc + durationSec > :fromUtc
        ORDER BY tier ASC, startUtc ASC
    """)
    fun observeRange(deviceId: String, fromUtc: Long, toUtc: Long): Flow<List<PowerBlock>>

    @Query("SELECT MIN(startUtc) FROM power_blocks WHERE deviceId = :deviceId")
    suspend fun earliestStart(deviceId: String): Long?

    @Query("SELECT MAX(startUtc + durationSec) FROM power_blocks WHERE deviceId = :deviceId")
    suspend fun latestEnd(deviceId: String): Long?

    @Query("SELECT COUNT(*) FROM power_blocks WHERE deviceId = :deviceId")
    suspend fun count(deviceId: String): Int

    // A block already stored is replaced rather than skipped: the newest read of
    // the same start is always at least as complete, which is what lets the
    // running block and a tier's still-growing pending run be written every
    // sync without piling up duplicates.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<PowerBlock>)

    // There is deliberately no delete. Removing a device from the list leaves
    // its history here: the plug thins its own archive out, so what is stored
    // here cannot be fetched again, and a device removed by accident and added
    // back under the same id finds its years intact.
}
