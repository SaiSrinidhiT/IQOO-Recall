package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.model.DocType

/**
 * The previous reply, so "it", "that one", "the second one" and "and my wife's?" have something to
 * point at. Each message used to be understood on its own, which is how "when does it expire?" listed
 * every expiring document instead of the one just shown.
 */
data class ChatContext(
    val query: String,
    val docTypes: List<DocType>,
    /** Documents the previous reply showed, in the order they were shown. */
    val shown: List<DocumentEntity>,
) {
    val shownIds: Set<Long> get() = shown.mapTo(HashSet()) { it.id }

    /** For Qwen's intent prompt: types and owners only, never OCR text or numbers. */
    fun describe(): String = buildString {
        append("Previous message: \"").append(query.take(MAX_QUERY_CHARS)).append('"')
        append("\nDocuments shown in the reply, in order:")
        if (shown.isEmpty()) append(" none")
        shown.take(MAX_DESCRIBED).forEachIndexed { i, d ->
            append("\n").append(i + 1).append(". ").append(d.effectiveType().name)
            d.ownerName?.let { append(" (belongs to ").append(it).append(')') }
        }
    }

    private companion object {
        const val MAX_QUERY_CHARS = 200
        const val MAX_DESCRIBED = 6
    }
}

sealed interface FollowUpPlan {
    /** Answer a question from these documents only ("when does it expire?", "what's the total?"). */
    data class Ask(val docs: List<DocumentEntity>) : FollowUpPlan

    /** Show these again, possibly with something to do to them ("share it", "the second one", "delete it"). */
    data class Show(val docs: List<DocumentEntity>, val action: DocAction?) : FollowUpPlan

    /** Search the same types again: other documents ("another one"), or another person's ("and my wife's?"). */
    data class Again(val types: List<DocType>, val excludeIds: Set<Long>) : FollowUpPlan
}

object FollowUp {
    /**
     * How a message relates to the previous reply, or null when it is a new request. A message that
     * names a different document type, asks for photos or a task, or is small talk ("ok", "thanks")
     * is never a follow-up.
     */
    fun plan(cue: RuleFallbackParser.FollowUpCue, ruled: QueryIntent, ctx: ChatContext?): FollowUpPlan? {
        if (ctx == null || ctx.shown.isEmpty() || !cue.any) return null
        if (ruled.kind == IntentKind.CHAT || ruled.kind == IntentKind.PHOTOS || ruled.kind == IntentKind.PACK || ruled.listAll) return null
        // "When does this salary slip expire?" right after salary slips is still about them; naming any other type is new.
        if (ruled.docTypes.isNotEmpty() && !(ruled.docTypes.all { it in ctx.docTypes } && (cue.reference || cue.ordinal != null))) return null

        val types = ctx.docTypes.ifEmpty { ctx.shown.map { it.effectiveType() }.distinct() }
        val picked = cue.ordinal?.let { i -> if (i < 0) ctx.shown.lastOrNull() else ctx.shown.getOrNull(i) }
        val targets = picked?.let(::listOf) ?: ctx.shown
        return when {
            cue.action != null -> FollowUpPlan.Show(targets, cue.action)
            cue.attribute -> FollowUpPlan.Ask(targets)
            picked != null -> FollowUpPlan.Show(targets, null)
            cue.ownerSwitch -> FollowUpPlan.Again(types, emptySet())
            cue.older -> FollowUpPlan.Again(types, ctx.shownIds)
            cue.reference -> FollowUpPlan.Show(targets, null)
            else -> null
        }
    }
}
