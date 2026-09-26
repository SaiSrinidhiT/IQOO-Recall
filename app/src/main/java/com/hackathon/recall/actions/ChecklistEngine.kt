package com.hackathon.recall.actions

import com.hackathon.recall.model.DocType
import java.time.LocalDate
import java.time.YearMonth

/** The fields of a stored document the checklist needs. */
data class DocSummary(
    val id: Long,
    val type: DocType,
    val issuedOn: LocalDate?,
    val capturedOn: LocalDate,
    val expiryOn: LocalDate?,
    val owner: String? = null,
    val confidence: Float = 1f,
    val userConfirmed: Boolean = false,
) {
    /** Recency is judged by issued_on, falling back to when the image was captured. */
    val effectiveDate: LocalDate get() = issuedOn ?: capturedOn
}

enum class ItemStatus { COMPLETE, PARTIAL, MISSING, OPTIONAL_MISSING }

enum class IgnoreReason { TOO_OLD, EXPIRED, SAME_MONTH, EXTRA }

data class ItemResult(
    val item: TemplateItem,
    val found: List<DocSummary>,
    val status: ItemStatus,
    /** Documents of the right type that did not count, with why (for "1 older statement ignored"). */
    val ignored: List<Pair<DocSummary, IgnoreReason>>,
    /** Set when the item was added for the document a reminder is about. */
    val pinned: Boolean = false,
) {
    val missingCount: Int get() = (item.required - found.size).coerceAtLeast(0)
}

data class ChecklistResult(val template: TaskTemplate, val items: List<ItemResult>) {
    val complete: Boolean get() = items.all { it.status == ItemStatus.COMPLETE || it.status == ItemStatus.OPTIONAL_MISSING }

    /** Found documents in template order, each once: this is the pack's page order. */
    val packDocIds: List<Long> get() = items.flatMap { r -> r.found.map { it.id } }.distinct()
}

/** Found/missing per template item, with counts and recency rules (brief §7). Pure: no I/O. */
object ChecklistEngine {
    fun evaluate(
        template: TaskTemplate,
        docs: List<DocSummary>,
        today: LocalDate,
        ownerFilter: String? = null,
        pinned: DocSummary? = null,
    ): ChecklistResult {
        val pool = if (ownerFilter.isNullOrBlank()) docs else docs.filter { it.owner?.contains(ownerFilter, ignoreCase = true) == true }
        val items = ArrayList<ItemResult>()
        if (pinned != null && template.items.none { it.docType == pinned.type }) {
            items += ItemResult(TemplateItem(pinned.type, 1), listOf(pinned), ItemStatus.COMPLETE, emptyList(), pinned = true)
        }
        for (item in template.items) {
            val pin = pinned?.takeIf { it.type == item.docType }
            items += evaluateItem(item, pool, today, pin)
        }
        return ChecklistResult(template, items)
    }

    fun evaluateItem(item: TemplateItem, docs: List<DocSummary>, today: LocalDate, pinned: DocSummary? = null): ItemResult {
        val candidates = docs.filter { it.type == item.docType }
            .sortedWith(compareByDescending<DocSummary> { it.effectiveDate }.thenByDescending { it.userConfirmed }.thenByDescending { it.confidence })
        val ignored = ArrayList<Pair<DocSummary, IgnoreReason>>()
        val recency = item.recency
        val eligible: List<DocSummary> = if (recency == null) candidates else when (recency.rule) {
            RecencyRule.WITHIN_MONTHS -> {
                val cutoff = today.minusMonths(recency.months.toLong())
                candidates.filter { d -> (!d.effectiveDate.isBefore(cutoff)).also { if (!it) ignored += d to IgnoreReason.TOO_OLD } }
            }
            RecencyRule.DISTINCT_MONTHS -> {
                val firstMonth = YearMonth.from(today).minusMonths(recency.months.toLong())
                val recent = candidates.filter { d ->
                    (!YearMonth.from(d.effectiveDate).isBefore(firstMonth)).also { if (!it) ignored += d to IgnoreReason.TOO_OLD }
                }
                val perMonth = LinkedHashMap<YearMonth, DocSummary>()
                for (d in recent) {
                    val ym = YearMonth.from(d.effectiveDate)
                    if (ym in perMonth) ignored += d to IgnoreReason.SAME_MONTH else perMonth[ym] = d
                }
                perMonth.values.toList()
            }
            RecencyRule.NOT_EXPIRED -> candidates.filter { d ->
                val expiry = d.expiryOn
                (expiry == null || !expiry.isBefore(today)).also { if (!it) ignored += d to IgnoreReason.EXPIRED }
            }
            RecencyRule.MOST_RECENT -> {
                if (recency.months <= 0) candidates else {
                    val cutoff = today.minusMonths(recency.months.toLong())
                    candidates.filter { d -> (!d.effectiveDate.isBefore(cutoff)).also { if (!it) ignored += d to IgnoreReason.TOO_OLD } }
                }
            }
        }
        // The document a reminder is about always counts for its own item, first.
        val ordered = if (pinned != null) listOf(pinned) + eligible.filter { it.id != pinned.id } else eligible
        val found = ordered.take(item.count)
        ordered.drop(item.count).forEach { ignored += it to IgnoreReason.EXTRA }
        val status = when {
            found.size >= item.required -> ItemStatus.COMPLETE
            item.optional && found.isEmpty() -> ItemStatus.OPTIONAL_MISSING
            found.isNotEmpty() -> ItemStatus.PARTIAL
            else -> ItemStatus.MISSING
        }
        return ItemResult(item, found, status, ignored, pinned = pinned != null)
    }
}
