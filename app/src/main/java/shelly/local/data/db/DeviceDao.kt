package shelly.local.data.db

import androidx.room.*
import shelly.local.model.Device
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<Device>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: Device)

    @Delete
    suspend fun delete(device: Device)

    @Query("UPDATE devices SET generation = :gen WHERE id = :id")
    suspend fun updateGeneration(id: String, gen: String)

    @Query("UPDATE devices SET reportedGeneration = :gen WHERE id = :id")
    suspend fun updateReportedGeneration(id: String, gen: Int)

    /** Only ever used to lift a device out of UNKNOWN -- see DeviceRepository. */
    @Query("UPDATE devices SET type = :type WHERE id = :id")
    suspend fun updateType(id: String, type: String)
}
