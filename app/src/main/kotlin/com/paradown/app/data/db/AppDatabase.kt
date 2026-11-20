package com.paradown.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.SegmentEntity

@Database(
    entities = [DownloadEntity::class, SegmentEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "paradown.db",
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
