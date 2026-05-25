package com.pearlnode.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceType
import com.pearlnode.model.ShellyGeneration

@Database(entities = [Device::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shelly.db"
                ).build().also { INSTANCE = it }
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
