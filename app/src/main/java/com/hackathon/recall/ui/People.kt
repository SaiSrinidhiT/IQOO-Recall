package com.hackathon.recall.ui

import com.hackathon.recall.R
import com.hackathon.recall.data.KvRow
import com.hackathon.recall.data.RecallDb
import com.hackathon.recall.search.Relation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which stored owner name is "You", "Father", "Mother"… so chat can resolve "my father's" ([OwnerIntent])
 * and the document detail screen can show a relation label instead of a bare name. Kept in the
 * database's kv table (like recent searches): it's personal, and a handful of rows is too small to
 * justify a dedicated table and a schema migration.
 */
object People {
    private const val KEY = "people_relations"
    private const val MANUAL_KEY = "owner_manual_doc_ids"

    /** Documents whose owner the user set by hand, so re-extraction never overwrites their choice. */
    suspend fun manuallyTagged(db: RecallDb): Set<Long> = withContext(Dispatchers.IO) {
        db.kv().get(MANUAL_KEY).orEmpty().split(',').mapNotNullTo(HashSet()) { it.trim().toLongOrNull() }
    }

    suspend fun markManuallyTagged(db: RecallDb, docId: Long) {
        val updated = manuallyTagged(db) + docId
        withContext(Dispatchers.IO) { db.kv().put(KvRow(MANUAL_KEY, updated.joinToString(","))) }
    }

    /** [Relation.db] -> the name stored for it. */
    suspend fun relations(db: RecallDb): Map<String, String> = withContext(Dispatchers.IO) {
        db.kv().get(KEY).orEmpty().lineSequence().mapNotNull { line ->
            val key = line.substringBefore('=', "")
            if (key.isEmpty()) null else key to line.substringAfter('=')
        }.toMap()
    }

    suspend fun setRelation(db: RecallDb, relation: Relation, name: String) {
        val updated = relations(db) + (relation.db to name)
        withContext(Dispatchers.IO) {
            db.kv().put(KvRow(KEY, updated.entries.joinToString("\n") { (k, v) -> "$k=${v.replace('\n', ' ')}" }))
        }
    }

    /** The relation label for [name] if one is tagged (e.g. "Father"), for display next to the raw name. */
    suspend fun relationLabelFor(db: RecallDb, name: String): Int? =
        relations(db).entries.firstOrNull { it.value == name }?.key?.let { Relation.fromDb(it) }?.let { relationLabel(it) }
}

fun relationLabel(relation: Relation): Int = when (relation) {
    Relation.SELF -> R.string.relation_self
    Relation.FATHER -> R.string.relation_father
    Relation.MOTHER -> R.string.relation_mother
    Relation.SPOUSE -> R.string.relation_spouse
    Relation.SON -> R.string.relation_son
    Relation.DAUGHTER -> R.string.relation_daughter
}
