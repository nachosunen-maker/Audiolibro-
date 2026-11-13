package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [BookEntity::class, ChapterEntity::class, ChapterChunkEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun audiobookDao(): AudiobookDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE books ADD COLUMN lastPlayedChapterIndex INTEGER")
          db.execSQL(
            "ALTER TABLE books ADD COLUMN lastPlayedPositionMs INTEGER NOT NULL DEFAULT 0"
          )

          db.execSQL(
            "ALTER TABLE chapters ADD COLUMN audioDurationMs INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE chapters ADD COLUMN audioSizeBytes INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE chapters ADD COLUMN generatedChunks INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL("ALTER TABLE chapters ADD COLUMN totalChunks INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE chapters ADD COLUMN generationError TEXT")

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_chunks` (
              `id` TEXT NOT NULL,
              `bookId` TEXT NOT NULL,
              `chapterId` TEXT NOT NULL,
              `chapterIndex` INTEGER NOT NULL,
              `chunkIndex` INTEGER NOT NULL,
              `textContent` TEXT NOT NULL,
              `voiceName` TEXT NOT NULL,
              `status` TEXT NOT NULL,
              `wavFilePath` TEXT,
              `byteSize` INTEGER NOT NULL,
              `durationMs` INTEGER NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
              .trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_chunks_chapterId` ON `chapter_chunks` (`chapterId`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_chunks_bookId` ON `chapter_chunks` (`bookId`)"
          )
        }
      }

    fun getInstance(context: Context): AppDatabase {
      return INSTANCE
        ?: synchronized(this) {
          val instance =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "audiolibro.db",
              )
              .addMigrations(MIGRATION_1_2)
              .build()
          INSTANCE = instance
          instance
        }
    }
  }
}
