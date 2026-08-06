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
import com.pearlnode.model.SensorBlock
import com.pearlnode.model.SensorKind
import com.pearlnode.model.ShellyGeneration

@Database(
    entities = [Device::class, PowerBlock::class, SensorBlock::class],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun powerBlockDao(): PowerBlockDao
    abstract fun sensorBlockDao(): SensorBlockDao

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

        // Up to version 3 of the plug's archive, the sign of a stored block
        // followed the plug's reverse metering flag, and nothing in a row said
        // how that flag stood when it was written. Rows from before then cannot
        // be told apart from rows after, so they go and the archive is fetched
        // again. It costs whatever the plug no longer holds -- hours, in
        // practice -- and it is the only way to be sure the history does not
        // have an invisible sign flip in the middle of it.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM power_blocks")
            }
        }

        // The generation a device reports for itself, so an unreachable one is
        // not described by its protocol family instead.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN reportedGeneration INTEGER")
            }
        }

        // A device that has no network of its own: a Shelly BLU sensor, which
        // is reached through the Shelly it was paired with.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN hostDeviceId TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN bleAddress TEXT")
            }
        }

        // Temperature and humidity, kept the same way the power history is: a
        // local copy that outlives whatever it was read from.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sensor_blocks (
                        deviceId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        startUtc INTEGER NOT NULL,
                        durationSec INTEGER NOT NULL,
                        milliValue INTEGER NOT NULL,
                        PRIMARY KEY (deviceId, kind, startUtc)
                    )
                """)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sensor_blocks_deviceId_kind_startUtc " +
                        "ON sensor_blocks (deviceId, kind, startUtc)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shelly.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter fun fromDeviceType(v: DeviceType): String = v.name
    @TypeConverter fun toDeviceType(v: String): DeviceType =
        runCatching { DeviceType.valueOf(v) }.getOrDefault(DeviceType.UNKNOWN)
    @TypeConverter fun fromGen(v: ShellyGeneration): String = v.name
    @TypeConverter fun toGen(v: String): ShellyGeneration = ShellyGeneration.valueOf(v)
    @TypeConverter fun fromSensorKind(v: SensorKind): String = v.name
    @TypeConverter fun toSensorKind(v: String): SensorKind = SensorKind.valueOf(v)
}
