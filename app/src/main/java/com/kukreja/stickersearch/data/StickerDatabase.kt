package com.kukreja.stickersearch.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StickerEntity::class, StickerFileEntity::class, StickerFrameEntity::class, StickerTagEntity::class,
        UserCorrectionEntity::class, ScanRunEntity::class, StickerFtsEntity::class,
        StickerEdgeEntity::class, FrameVectorEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // v2 adds the sticker graph (two new tables); existing data is kept.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class StickerDatabase : RoomDatabase() {
    abstract fun stickers(): StickerDao
    abstract fun files(): FileDao
    abstract fun frames(): FrameDao
    abstract fun runs(): ScanRunDao
    abstract fun ai(): AiDao
    abstract fun graph(): GraphDao

    companion object {
        @Volatile private var instance: StickerDatabase? = null

        fun get(context: Context): StickerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StickerDatabase::class.java, "stickers.db")
                .build().also { instance = it }
        }
    }
}
