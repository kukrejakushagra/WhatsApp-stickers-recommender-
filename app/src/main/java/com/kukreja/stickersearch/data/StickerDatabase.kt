package com.kukreja.stickersearch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StickerEntity::class, StickerFileEntity::class, StickerFrameEntity::class, StickerTagEntity::class,
        UserCorrectionEntity::class, ScanRunEntity::class, StickerFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StickerDatabase : RoomDatabase() {
    abstract fun stickers(): StickerDao
    abstract fun files(): FileDao
    abstract fun frames(): FrameDao
    abstract fun runs(): ScanRunDao
    abstract fun ai(): AiDao

    companion object {
        @Volatile private var instance: StickerDatabase? = null

        fun get(context: Context): StickerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StickerDatabase::class.java, "stickers.db")
                .build().also { instance = it }
        }
    }
}
