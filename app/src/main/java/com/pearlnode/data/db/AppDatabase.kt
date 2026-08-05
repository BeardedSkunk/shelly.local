package com.pearlnode.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceType
import com.pearlnode.model.PowerBlock
import com.pearlnode.model.ShellyGeneration

@Database(entities = [Device::class, PowerBlock::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun powerBlockDao(): PowerBlockDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Migrated rather than rebuilt. The power history is the one table in
        // here that cannot be fetched again: the plug thins its own archive
        // out, so what this app copied last month may no longer exist there at
        // that resolution, or at all.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS power_blocks (
                        deviceId TEXT NOT NULL,
                        tier INTEGER NOT NULL,
                        startUtc INTEGER NOT NULL,
                        durationSec INTEGER NOT NULL,
                        energyMwh INTEGER NOT NULL,
                        PRIMARY KEY(deviceId, tier, startUtc)
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_power_blocks_deviceId_startUtc " +
                        "ON power_blocks (deviceId, startUtc)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shelly.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter fun fromDeviceType(v: DeviceType): String = v.name
    @TypeConverter fun toDeviceType(v: String): DeviceType =
        runCatching { DeviceType.valueOf(v) }.getOrDefault(DeviceType.UNKNOWN)
    @TypeConverter fun fromGen(v: ShellyGeneration): String = v.name
    @TypeConverter fun toGen(v: String): ShellyGeneration = ShellyGeneration.valueOf(v)
}
