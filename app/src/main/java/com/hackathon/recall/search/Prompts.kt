package com.hackathon.recall.search

import com.hackathon.recall.ml.LlmClient
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/** Prompts for the short structured jobs Qwen does (brief §3.3, §6). Every reply is JSON. */
object Prompts {
    private val DOC_TYPES = DocType.entries.joinToString(", ") { it.name }

    fun intentSystem(today: LocalDate): String {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        return """
            You turn a user's request about their personal documents into JSON. Reply with one JSON object and nothing else.
            Today's date is $today.
            Fields:
            - intent: "find" (show documents), "pack" (get documents ready for a task), "emergency" (medical emergency), "reminders" (what expires or needs renewal), "question" (a fact inside a document).
            - task_template: home_loan, health_insurance_claim, vehicle_insurance_renewal, emergency_health, passport, or null.
            - doc_types: zero or more of $DOC_TYPES.
            - query_en: a short English search query.
            - date_from, date_to: YYYY-MM-DD or null. Resolve relative dates such as "last month", "pichle mahine", "గత నెల" from today's date.
            - answer_language: "te" for Telugu or romanized Telugu, "hi" for Hindi or romanized Hindi, otherwise "en".
            - question: the user's question in English when intent is "question", otherwise null.
            Examples:
            User: Home loan ki documents ready cheyyi
            {"intent":"pack","task_template":"home_loan","doc_types":[],"query_en":"home loan documents","date_from":null,"date_to":null,"answer_language":"te","question":null}
            User: पिछले महीने की सैलरी स्लिप दिखाओ
            {"intent":"find","task_template":null,"doc_types":["SALARY_SLIP"],"query_en":"salary slip last month","date_from":"${lastMonth.atDay(1)}","date_to":"${lastMonth.atEndOfMonth()}","answer_language":"hi","question":null}
            User: what is my car insurance policy number
            {"intent":"question","task_template":null,"doc_types":["VEHICLE_INSURANCE"],"query_en":"car insurance policy number","date_from":null,"date_to":null,"answer_language":"en","question":"What is my car insurance policy number?"}
        """.trimIndent()
    }

    fun answerSystem(lang: Lang): String = """
        You answer questions about the user's own documents using ONLY the document texts given. Reply with one JSON object: {"answer": "...", "cited_doc_ids": [numbers]}.
        Write "answer" in ${lang.englishName}, in one or two sentences. Cite the id of every document you used.
        If the documents do not contain the answer, say so in ${lang.englishName} and cite nothing. Never invent numbers, dates or names.
    """.trimIndent()

    val docTypeSystem = """
        Classify this document from its OCR text. Reply with one JSON object: {"doc_type": "<TYPE>"}, where TYPE is one of: $DOC_TYPES.
        Use OTHER_DOCUMENT when none fits.
    """.trimIndent()

    val expirySystem = """
        Pick which candidate date is the document's expiry, valid-till, due or renewal date. Reply with one JSON object: {"index": <number>},
        or {"index": null} if none of them is an expiry date.
    """.trimIndent()
}

@Serializable
data class AnswerJson(val answer: String? = null, @SerialName("cited_doc_ids") val citedDocIds: List<Long> = emptyList())

@Serializable
data class DocTypeJson(@SerialName("doc_type") val docType: String? = null)

@Serializable
data class ExpiryJson(val index: Int? = null)

/**
 * Asks for JSON and validates it; on failure, retries once with the error message in the prompt
 * (brief §3.3). Throws after the second failure so the caller can use the rule-based fallback.
 */
suspend fun <T, R> LlmClient.askJson(
    system: String,
    user: String,
    maxTokens: Int,
    decode: (String) -> T,
    validate: (T) -> R,
): R {
    var error: String? = null
    for (attempt in 0..1) {
        val prompt = if (error == null) user else "$user\n\nYour previous reply was invalid: $error\nReply again with valid JSON only."
        val reply = complete(system, prompt, maxTokens)
        try {
            return validate(decode(reply.text))
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    throw IllegalStateException("invalid JSON twice: $error")
}
