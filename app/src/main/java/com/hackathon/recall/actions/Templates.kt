package com.hackathon.recall.actions

import com.hackathon.recall.model.DocType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class RecencyRule {
    /** Effective date within the last [Recency.months] months. */
    @SerialName("within_months") WITHIN_MONTHS,

    /** One document per calendar month; months from (current month − [Recency.months]) onward count. */
    @SerialName("distinct_months") DISTINCT_MONTHS,

    /** Expiry date absent or today or later. */
    @SerialName("not_expired") NOT_EXPIRED,

    /** The most recent documents, optionally only those within [Recency.months] months (0 = any age). */
    @SerialName("most_recent") MOST_RECENT,
}

@Serializable
data class Recency(val rule: RecencyRule, val months: Int = 0)

@Serializable
data class TemplateItem(
    @SerialName("doc_type") val docType: DocType,
    val count: Int = 1,
    /** How many documents satisfy the item; defaults to [count]. */
    val min: Int? = null,
    val optional: Boolean = false,
    val recency: Recency? = null,
) {
    val required: Int get() = min ?: count
}

@Serializable
data class TaskTemplate(
    val id: String,
    @SerialName("title_en") val titleEn: String,
    val items: List<TemplateItem>,
)

@Serializable
data class TemplateCatalog(
    val templates: List<TaskTemplate>,
    @SerialName("renewal_for") val renewalFor: Map<DocType, String> = emptyMap(),
    @SerialName("default_renewal") val defaultRenewal: String = "renewal_generic",
) {
    fun byId(id: String): TaskTemplate? = templates.firstOrNull { it.id == id }

    /** Template opened from an expiry reminder for a document of [type]. */
    fun renewalTemplateFor(type: DocType): TaskTemplate =
        byId(renewalFor[type] ?: defaultRenewal) ?: byId(defaultRenewal) ?: templates.first()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TemplateCatalog = json.decodeFromString(serializer(), text)
    }
}
