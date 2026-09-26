package com.hackathon.recall.search

/** A household relationship a document can be tagged with, so chat understands "my father's...". */
enum class Relation(val db: String) {
    SELF("self"), FATHER("father"), MOTHER("mother"), SPOUSE("spouse"), SON("son"), DAUGHTER("daughter");

    companion object {
        fun fromDb(v: String): Relation? = entries.firstOrNull { it.db == v }
    }
}

/**
 * Resolves a possessive in a query ("my father's Aadhaar") to the name stored for that relation, so
 * [HybridSearch] can filter hits by person. Independent of intent/doc-type parsing: it only ever adds
 * an owner filter, never changes what the query otherwise matches.
 */
object OwnerIntent {
    // Specific relations are checked before SELF, so "my father's" resolves to FATHER, not SELF.
    private val WORDS: List<Pair<Relation, Regex>> = listOf(
        Relation.FATHER to word("father|dad|daddy|papa"),
        Relation.MOTHER to word("mother|mom|mum|mummy"),
        Relation.SPOUSE to word("wife|husband|spouse"),
        Relation.SON to word("son"),
        Relation.DAUGHTER to word("daughter"),
        Relation.SELF to word("my|mine|myself"),
    )

    private fun word(alternatives: String) = Regex("(?<![a-z])(?:$alternatives)(?:'s)?(?![a-z])", RegexOption.IGNORE_CASE)

    /** [relations] maps a [Relation.db] key to the stored name for it (see [com.hackathon.recall.ui.People]). */
    fun resolve(query: String, relations: Map<String, String>): String? {
        for ((relation, pattern) in WORDS) {
            if (pattern.containsMatchIn(query)) relations[relation.db]?.let { return it }
        }
        return null
    }
}
