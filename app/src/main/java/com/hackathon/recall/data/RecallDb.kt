package com.hackathon.recall.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        DocumentEntity::class, EntityRow::class, ChunkRow::class, ImageVectorRow::class,
        PageRow::class, ReminderRow::class, IndexStateRow::class, KvRow::class,
    ],
    version = 1,
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

    companion object {
        /**
         * Opens the SQLCipher database. [passphrase] is SQLCipher's raw-key form (`x'<64 hex>'`), so
         * no PBKDF2 runs at open. The FTS table is created outside Room because Room has no FTS5
         * annotation and the module (FTS5 or FTS4) is chosen at runtime.
         */
        fun open(context: Context, passphrase: ByteArray, fts: FtsIndex): RecallDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, RecallDb::class.java, "recall.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) = fts.ensureTable(db)
                })
                .build()
        }
    }
}
