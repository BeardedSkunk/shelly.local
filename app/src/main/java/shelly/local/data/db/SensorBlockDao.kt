package shelly.local.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import shelly.local.model.SensorBlock
import shelly.local.model.SensorKind
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

    /**
     * The newest reading on file, whatever window is on screen.
     *
     * Stands in for "now" when the sensor cannot be reached: the copy from
     * openSenseMap is up to half an hour behind, which is a great deal better
     * than a dash.
     */
    @Query("""
        SELECT milliValue FROM sensor_blocks
        WHERE deviceId = :deviceId AND kind = :kind
        ORDER BY startUtc DESC LIMIT 1
    """)
    suspend fun latestValue(deviceId: String, kind: SensorKind): Long?

    @Query("SELECT COUNT(*) FROM sensor_blocks WHERE deviceId = :deviceId")
    suspend fun count(deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<SensorBlock>)

    @Query("DELETE FROM sensor_blocks WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: String)
}
