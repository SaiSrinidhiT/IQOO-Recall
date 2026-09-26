package com.hackathon.recall.search

import com.hackathon.recall.data.type
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Runs docs/EVAL.md's queries against the documents on this phone (brief §9, Phase 3 gate: hit@3 ≥ 80%
 * overall and ≥ 70% per language). A find/question query hits when a document of an expected type is
 * in the top 3; a pack query hits when it opens the expected template.
 */
class EvalRunner(private val engine: QueryEngine) {
    @Serializable
    data class Case(
        val id: Int,
        val lang: String,
        val query: String,
        @SerialName("expect_types") val expectTypes: List<String> = emptyList(),
        @SerialName("expect_template") val expectTemplate: String? = null,
    )

    data class Outcome(val case: Case, val hit: Boolean, val got: String)

    suspend fun run(json: String, useLlm: Boolean): List<Outcome> {
        val cases = Json.decodeFromString<List<Case>>(json)
        return cases.map { c ->
            val r = engine.ask(c.query, useLlm = useLlm)
            when (r) {
                is QueryResult.Pack -> Outcome(c, c.expectTemplate != null && r.templateId == c.expectTemplate, "pack:${r.templateId}")
                is QueryResult.Reminders -> {
                    val top = r.docs.take(3).map { it.type() }
                    Outcome(c, top.any { it.name in c.expectTypes }, "reminders:" + top.joinToString(","))
                }
                is QueryResult.Found -> {
                    val top = r.hits.take(3).map { it.doc.type() }
                    Outcome(c, top.any { it.name in c.expectTypes }, top.joinToString(",").ifEmpty { "none" })
                }
            }
        }
    }

    companion object {
        fun report(outcomes: List<Outcome>): String {
            fun pct(list: List<Outcome>) = if (list.isEmpty()) 0 else list.count { it.hit } * 100 / list.size
            val byLang = outcomes.groupBy { it.case.lang }
            return buildString {
                appendLine("hit@3 overall ${pct(outcomes)}% (${outcomes.count { it.hit }}/${outcomes.size})")
                for ((lang, list) in byLang) appendLine("  $lang: ${pct(list)}% (${list.count { it.hit }}/${list.size})")
                for (o in outcomes.filter { !it.hit }) appendLine("  miss #${o.case.id} \"${o.case.query}\" → ${o.got}")
            }
        }
    }
}
