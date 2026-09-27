package com.hackathon.recall.search

import com.hackathon.recall.ml.LlmClient
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/** Prompts for the jobs Qwen does (brief §3.3, §6). Every reply is JSON except [chatSystem]'s plain text. */
object Prompts {
    private val DOC_TYPES = DocType.entries.joinToString(", ") { it.name }

    fun intentSystem(today: LocalDate): String {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        return """
            You route one message sent to Recall, an offline app that holds the user's own documents (ID cards, insurance, salary slips, bills, medical, property and education papers). Reply with ONE JSON object and nothing else.
            Today's date is $today.

            Fields:
            - intent, exactly one of:
              "find": the user wants to see, get, open or share a document. ("show my PAN", "I need my car insurance papers", "resume as PDF")
              "question": the user wants a fact written inside a document. ("what is my policy number", "when does my licence expire", "how much was my last salary")
              "reminders": the user asks what is expiring, due or needs renewal, without naming one document.
              "pack": the user wants papers gathered for a task: home_loan, health_insurance_claim, vehicle_insurance_renewal or passport.
              "photos": the user wants gallery photos rather than a document: selfies, screenshots, photos of people, friends or family, food, trips, travel or places. ("show my selfies", "pics from my Goa trip", "food photos from last month")
              "chat": everything else. Greetings ("hi", "hello", "namaste"), thanks, "ok", "bye", "how are you", "who are you", "what can you do", help, jokes, feelings, complaints, general knowledge, news, weather, maths, coding, advice, a lone emoji, gibberish or an empty message.
            - task_template: one of the four task names for "pack", otherwise null.
            - doc_types: zero or more of $DOC_TYPES. Only types the message clearly means. Always [] for "chat".
            - query_en: a short English search phrase for "find" or "question". "" for "chat".
            - date_from, date_to: YYYY-MM-DD, only when the message names a period ("last month", "in 2024", "pichle mahine", "గత నెల"); resolve it from today's date. "latest", "recent", "new", "current" and "my" are NOT periods: use null.
            - answer_language: the language the user wrote in. "te" for Telugu script or romanized Telugu, "hi" for Hindi script or romanized Hindi, otherwise "en". An English sentence is "en" even if it names an Indian document.
            - question: the user's question in English for "question", otherwise null.
            - photo_category: for "photos" only, exactly one of "screenshot", "selfie", "people", "food", "places" (trips and travel are "places"); otherwise null.

            Rules:
            - A message that names or implies a document or a detail stored in one is never "chat", even if it starts with a greeting: "hi, show my aadhaar" is "find".
            - A question about the app itself ("can you share files?", "is my data safe?") is "chat".
            - If the user only greets, thanks or chats, do not guess a document.

            Examples:
            User: hi
            {"intent":"chat","task_template":null,"doc_types":[],"query_en":"","date_from":null,"date_to":null,"answer_language":"en","question":null}
            User: thanks a lot
            {"intent":"chat","task_template":null,"doc_types":[],"query_en":"","date_from":null,"date_to":null,"answer_language":"en","question":null}
            User: what can you do?
            {"intent":"chat","task_template":null,"doc_types":[],"query_en":"","date_from":null,"date_to":null,"answer_language":"en","question":null}
            User: who won yesterday's cricket match
            {"intent":"chat","task_template":null,"doc_types":[],"query_en":"","date_from":null,"date_to":null,"answer_language":"en","question":null}
            User: namaste, mera PAN card dikhao
            {"intent":"find","task_template":null,"doc_types":["PAN"],"query_en":"PAN card","date_from":null,"date_to":null,"answer_language":"hi","question":null}
            User: latest salary slip
            {"intent":"find","task_template":null,"doc_types":["SALARY_SLIP"],"query_en":"latest salary slip","date_from":null,"date_to":null,"answer_language":"en","question":null}
            User: पिछले महीने की सैलरी स्लिप दिखाओ
            {"intent":"find","task_template":null,"doc_types":["SALARY_SLIP"],"query_en":"salary slip last month","date_from":"${lastMonth.atDay(1)}","date_to":"${lastMonth.atEndOfMonth()}","answer_language":"hi","question":null}
            User: Home loan ki documents ready cheyyi
            {"intent":"pack","task_template":"home_loan","doc_types":[],"query_en":"home loan documents","date_from":null,"date_to":null,"answer_language":"te","question":null}
            User: what is my car insurance policy number
            {"intent":"question","task_template":null,"doc_types":["VEHICLE_INSURANCE"],"query_en":"car insurance policy number","date_from":null,"date_to":null,"answer_language":"en","question":"What is my car insurance policy number?"}
            User: show my trip photos
            {"intent":"photos","task_template":null,"doc_types":[],"query_en":"trip photos","date_from":null,"date_to":null,"answer_language":"en","question":null,"photo_category":"places"}
            User: anything expiring soon?
            {"intent":"reminders","task_template":null,"doc_types":[],"query_en":"expiring documents","date_from":null,"date_to":null,"answer_language":"en","question":null}
        """.trimIndent()
    }

    fun answerSystem(lang: Lang): String = """
        You answer questions about the user's own documents using ONLY the document texts given. Reply with one JSON object: {"answer": "...", "cited_doc_ids": [numbers]}.
        Write "answer" in ${lang.englishName}, as ONE short sentence that states the fact asked for (for example "Your policy number is 12345, valid till 3 March 2027."). Do not write document ids, brackets or OCR noise in "answer"; list the ids you used only in "cited_doc_ids".
        If the documents do not contain the answer, say so in ${lang.englishName} and cite nothing. Never invent numbers, dates or names.
    """.trimIndent()

    /**
     * Conversational replies for [IntentKind.CHAT]. Plain text, not JSON, so it can stream into the
     * UI. The model is shown no documents, so it is told never to claim or describe one.
     */
    fun chatSystem(lang: Lang, documentCount: Int): String = """
        You are Recall, a private assistant that runs fully offline on the user's phone. You help them find their own documents (ID cards, insurance, salary slips, bills, medical and property papers), answer questions from them, share them as PDF with Aadhaar numbers masked, gather papers for tasks like a home loan, and warn before documents expire. The user has $documentCount documents saved.
        Reply in ${lang.englishName}, in at most two short, warm sentences. Plain text only: no JSON, no lists, no markdown, no emojis.
        - Greeting or small talk: greet back and suggest one thing to ask, such as "Show my PAN card" or "What expires this month?".
        - Thanks, ok or bye: reply briefly and politely.
        - "Who are you" or "what can you do": say what you do in one sentence and give one example to try.
        - Anything unrelated to their documents (news, general knowledge, maths, coding, advice, opinions): say kindly that you can only help with the documents saved on this phone, and suggest something you can do.
        - Never say a document exists, and never state a number, date or name from one: you have not been shown any documents.
    """.trimIndent()

    val docTypeSystem = """
        Classify one document from its OCR text. Reply with one JSON object: {"doc_type": "<TYPE>"} and nothing else.
        TYPE is one of: ${DocType.entries.joinToString("; ") { "${it.name} (${it.labelEn})" }}.
        Rules:
        - Pick a type only when the text clearly shows that kind of document: its title, issuing authority, or the fields it always has.
        - Screenshots of apps, chats, shopping or food orders, advertisements, posters, notes and anything not in the list: OTHER_DOCUMENT.
        - When unsure between two types, or unsure at all: OTHER_DOCUMENT.
        Types that are often confused:
        - LOAN_SANCTION_EMI: a bank or NBFC sanction or approval letter, loan agreement, or EMI or repayment schedule for a loan account. NOT a loan app screenshot, an "instant loan approved" message or advert, or a receipt for one EMI payment.
        - PAYMENT_SCREENSHOT: a UPI or wallet payment confirmation (paid to, UTR, transaction ID).
        - RECEIPT_INVOICE: a bill or tax invoice from a shop, restaurant or seller.
        - BANK_STATEMENT: a bank's list of account transactions with balances.
        - SALARY_SLIP: an employer's payslip with earnings and deductions.
        - TICKET: a travel or event ticket with a PNR or booking reference.
    """.trimIndent()

    /** Bump when [docTypeSystem] changes, so documents Qwen verified with the old wording are re-checked. */
    const val DOC_TYPE_PROMPT_VERSION = 2

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
