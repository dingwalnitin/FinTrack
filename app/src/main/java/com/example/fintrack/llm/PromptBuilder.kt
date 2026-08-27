package com.example.fintrack.llm

/**
 * P07: prompt construction. Builds a compact, evidence-labeled prompt from
 * normalized SMS plus minimal metadata. Every input is explicitly labeled as
 * evidence, not truth. Nearby same-sender messages are included only within
 * the configured time window and only for duplicate/missing-detail resolution.
 */
object PromptBuilder {

    const val NEARBY_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    const val MAX_NEARBY = 3

    fun build(request: ParseRequest): String = buildString {
        appendLine("You are a strict JSON-extraction engine for a personal finance tracker.")
        appendLine("You receive ONE bank SMS message as evidence. Extract ONLY the fields the")
        appendLine("message explicitly states. Output ONLY a single JSON object — no prose,")
        appendLine("no markdown fences, no comments — that validates against the schema below.")
        appendLine()
        appendLine("RULES:")
        appendLine("1. All inputs below are EVIDENCE, not truth. Extract only what the text supports.")
        appendLine("2. Never invent identifiers, amounts, dates or names absent from the evidence.")
        appendLine("3. Unknown fields must be omitted or null — never guessed.")
        appendLine("4. amountMinor is the amount in minor units (paise for INR):")
        appendLine("   e.g. \"Rs.250\" -> 25000, \"₹1,250.50\" -> 125050, \"INR 10\" -> 1000.")
        appendLine("5. direction: DEBIT when money left the account (debited/spent/withdrawn),")
        appendLine("   CREDIT when money arrived (credited/deposited/refund).")
        appendLine("6. occurredAtEpochMs: the transaction time in epoch milliseconds if the SMS")
        appendLine("   states a date/time; otherwise omit it.")
        appendLine("7. Every extracted value must appear in the message text verbatim or be an")
        appendLine("   exact numeric/date conversion of it.")
        appendLine("8. Respond with ONLY a JSON object matching schema ${request.schemaVersion}:")
        appendLine()
        appendLine("SCHEMA ${request.schemaVersion}:")
        appendLine("{")
        appendLine("  \"amountMinor\": int,                // REQUIRED if an amount is present")
        appendLine("  \"currencyCode\": \"INR\",             // REQUIRED, 3-letter ISO")
        appendLine("  \"direction\": \"DEBIT\" | \"CREDIT\",  // REQUIRED")
        appendLine("  \"accountToken\": string|null,       // masked suffix e.g. \"XX1234\" if present")
        appendLine("  \"rail\": string|null,               // UPI | IMPS | NEFT | RTGS | CARD_POS | CARD_ONLINE | ATM | ACH | UNKNOWN")
        appendLine("  \"counterpartyRaw\": string|null,    // payee/merchant name as written")
        appendLine("  \"counterpartyNormalized\": string|null, // normalized name")
        appendLine("  \"categorySuggestion\": string|null, // e.g. \"Groceries\", \"Utilities\"")
        appendLine("  \"transferTargetToken\": string|null,")
        appendLine("  \"recurring\": bool|null,            // true only if the SMS indicates recurrence")
        appendLine("  \"emiDetail\": string|null,")
        appendLine("  \"occurredAtEpochMs\": long|null,")
        appendLine("  \"confidence\": { \"fieldName\": { \"value\": 0.0..1.0, \"explanation\": \"why\" } },")
        appendLine("  \"overallConfidence\": 0.0..1.0|null")
        appendLine("}")
        appendLine()
        appendLine("EXAMPLE (do not copy values, follow the shape):")
        appendLine("{\"amountMinor\": 25000, \"currencyCode\": \"INR\", \"direction\": \"DEBIT\",")
        appendLine(" \"accountToken\": \"XX1234\", \"rail\": \"UPI\", \"counterpartyRaw\": \"BigBazaar\",")
        appendLine(" \"counterpartyNormalized\": \"Big Bazaar\", \"categorySuggestion\": \"Groceries\",")
        appendLine(" \"confidence\": {\"amount\": {\"value\": 0.99, \"explanation\": \"Rs.250 stated verbatim\"}}}")
        appendLine()
        appendLine("EVIDENCE (primary message id=${request.sourceMessageId}, receivedAt=${request.receivedAtEpochMs}):")
        appendLine(request.bodyText)
        val nearby = request.nearbyEvidence.take(MAX_NEARBY)
        if (nearby.isNotEmpty()) {
            appendLine()
            appendLine("EVIDENCE (nearby same-sender context, for duplicate/missing-detail resolution only):")
            nearby.forEach { n ->
                appendLine("- [id=${n.sourceMessageId} at=${n.receivedAtEpochMs}] ${n.bodyText}")
            }
        }
    }

    /** Deterministic semantic-input hash key for caching (P08). */
    fun cacheKey(request: ParseRequest, providerId: String, modelId: String): String =
        listOf(
            request.bodyText.trim(),
            request.senderHash,
            request.receivedAtEpochMs,
            request.nearbyEvidence.joinToString("|") { "${it.sourceMessageId}:${it.bodyText.trim()}" },
            request.promptVersion,
            request.schemaVersion,
            providerId,
            modelId,
        ).joinToString("\u0001").sha256()

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
