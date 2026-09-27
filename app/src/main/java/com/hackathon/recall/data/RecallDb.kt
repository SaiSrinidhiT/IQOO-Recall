package com.hackathon.recall.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        DocumentEntity::class, EntityRow::class, ChunkRow::class, ImageVectorRow::class,
        PageRow::class, ReminderRow::class, IndexStateRow::class, KvRow::class, PhotoRow::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class RecallDb : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun entities(): EntityDao
    abstract fun chunks(): ChunkDao
    abstract fun imageVectors(): ImageVectorDao
    abstract fun pages(): PageDao
    abstract fun reminders(): ReminderDao
    abstract fun indexState(): IndexStateDao
    abstract fun kv(): KvDao
    abstract fun photos(): PhotoDao

    companion object {
        /** v2 adds the gallery photos table; existing documents and index are untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photos` (`uri` TEXT NOT NULL, `category` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                        "`taken_at` INTEGER NOT NULL, `relative_path` TEXT, `lat` REAL, `lon` REAL, `faces` INTEGER NOT NULL, " +
                        "`doc_id` INTEGER, `trip_id` INTEGER, `vector` BLOB, PRIMARY KEY(`uri`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_category` ON `photos` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_trip_id` ON `photos` (`trip_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_taken_at` ON `photos` (`taken_at`)")
            }
        }

        /** v3 names where geotagged photos were taken (nearest town), for trip titles and "photos from Ongole". */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `photos` ADD COLUMN `place` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_place` ON `photos` (`place`)")
            }
        }

        /**
         * Opens the SQLCipher database. [passphrase] is SQLCipher's raw-key form (`x'<64 hex>'`), so
         * no PBKDF2 runs at open. The FTS table is created outside Room because Room has no FTS5
         * annotation and the module (FTS5 or FTS4) is chosen at runtime.
         */
        fun open(context: Context, passphrase: ByteArray, fts: FtsIndex): RecallDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, RecallDb::class.java, "recall.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) = fts.ensureTable(db)
                })
                .build()
        }
    }
}
