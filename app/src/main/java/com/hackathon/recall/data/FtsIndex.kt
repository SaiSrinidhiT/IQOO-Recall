package com.hackathon.recall.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.hackathon.recall.extract.DigitNormalizer
import java.text.Normalizer

/**
 * Keyword index over chunk text and titles. Uses FTS5 when this SQLCipher build supports it (checked
 * on the device at first open), else FTS4. `unicode61` treats combining marks as separators, which
 * would split Hindi and Telugu words at every vowel sign, so those marks are declared as token chars.
 */
class FtsIndex(private val database: () -> SupportSQLiteDatabase) {
    enum class Module { FTS5, FTS4 }

    @Volatile
    var module: Module = Module.FTS4
        private set

    fun ensureTable(db: SupportSQLiteDatabase) {
        val existing = db.query("SELECT sql FROM sqlite_master WHERE name = 'fts_chunks'").use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        if (existing != null) {
            module = if (existing.contains("fts5", ignoreCase = true)) Module.FTS5 else Module.FTS4
            return
        }
        module = if (supportsFts5(db)) Module.FTS5 else Module.FTS4
        val marks = indicMarks()
        db.execSQL(
            when (module) {
                Module.FTS5 ->
                    "CREATE VIRTUAL TABLE fts_chunks USING fts5(text, title, doc_id UNINDEXED, " +
                        "tokenize = \"unicode61 remove_diacritics 0 tokenchars '$marks'\")"
                Module.FTS4 ->
                    "CREATE VIRTUAL TABLE fts_chunks USING fts4(text, title, doc_id, notindexed=doc_id, " +
                        "tokenize=unicode61 \"remove_diacritics=0\" \"tokenchars=$marks\")"
            },
        )
    }

    private fun supportsFts5(db: SupportSQLiteDatabase): Boolean = try {
        db.execSQL("CREATE VIRTUAL TABLE temp.fts5_probe USING fts5(x)")
        db.execSQL("DROP TABLE temp.fts5_probe")
        true
    } catch (_: Exception) {
        false
    }

    fun insert(chunkId: Long, docId: Long, text: String, title: String) {
        database().execSQL(
            "INSERT INTO fts_chunks(rowid, text, title, doc_id) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(chunkId, normalize(text), normalize(title), docId),
        )
    }

    fun deleteDoc(docId: Long) {
        database().execSQL("DELETE FROM fts_chunks WHERE doc_id = ?", arrayOf<Any>(docId))
    }

    /** Documents ranked by keyword relevance (best first). Tokens are OR-ed prefix matches. */
    fun search(query: String, limit: Int = 50): List<Long> {
        val tokens = normalize(query).lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(16)
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" OR ") { "$it*" }
        val sql = when (module) {
            Module.FTS5 -> "SELECT doc_id FROM fts_chunks WHERE fts_chunks MATCH ? ORDER BY bm25(fts_chunks) LIMIT ?"
            Module.FTS4 -> "SELECT doc_id, COUNT(*) AS hits FROM fts_chunks WHERE fts_chunks MATCH ? GROUP BY doc_id ORDER BY hits DESC LIMIT ?"
        }
        val out = LinkedHashSet<Long>()
        database().query(sql, arrayOf<Any>(match, limit * 4)).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out.take(limit)
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(DigitNormalizer.normalize(s), Normalizer.Form.NFC).replace("\u200C", "").replace("\u200D", "")

    companion object {
        /** Combining marks in the Devanagari and Telugu blocks. */
        fun indicMarks(): String {
            val sb = StringBuilder()
            for (cp in (0x0900..0x097F) + (0x0C00..0x0C7F)) {
                when (Character.getType(cp).toByte()) {
                    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> sb.appendCodePoint(cp)
                }
            }
            return sb.toString()
        }
    }
}
