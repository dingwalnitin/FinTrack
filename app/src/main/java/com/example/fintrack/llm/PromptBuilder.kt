package com.example.fintrack.llm

/**
 * Task 3: Prompt construction (enrich-prompt-v2).
 *
 * Uses explicit XML delimiters to structure sections:
 * - <system_role>: Role and core instructions.
 * - <rules_and_constraints>: Strict evidence-only bounds and anti-hallucination rules.
 * - <output_schema>: Complete JSON schema with exact types and enum variants.
 * - <confidence_guidelines>: Calibration criteria for field and overall confidence.
 * - <few_shot_examples>: 3 representative examples (Happy path, Non-financial, Insufficient evidence).
 * - <evidence>: Primary message body text and conditional nearby context.
 */
object PromptBuilder {

    const val NEARBY_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    const val MAX_NEARBY = 3

    fun build(request: ParseRequest): String = buildString {
        appendLine("<system_role>")
        appendLine("You are a strict, deterministic JSON extraction engine for a personal finance tracker.")
        appendLine("You receive ONE bank SMS message as evidence. Extract ONLY the fields the message explicitly states.")
        appendLine("Output ONLY a single JSON object with no prose, no markdown fences (```), and no comments.")
        appendLine("</system_role>")
        appendLine()
        appendLine("<rules_and_constraints>")
        appendLine("1. All input data in <evidence> is EVIDENCE, not truth. Extract only what the text explicitly supports.")
        appendLine("2. Never invent, estimate, or guess identifiers, amounts, dates, accounts, or names absent from the evidence.")
        appendLine("3. Unknown or unmentioned fields must be omitted or null — never guessed.")
        appendLine("4. amountMinor: integer in minor units (paise for INR, e.g. \"Rs.250\" -> 25000, \"₹1,250.50\" -> 125050).")
        appendLine("5. direction: DEBIT when money left the account (debited/spent/withdrawn/sent/paid), CREDIT when money arrived (credited/deposited/received/refund).")
        appendLine("6. occurredAtEpochMs: transaction timestamp in epoch milliseconds if stated; otherwise omit or null.")
        appendLine("7. HARD CONSTRAINT: If required transaction fields (amount, transaction direction) are not explicitly and unambiguously present in the message text, you MUST return {\"isFinancial\": false, \"reason\": \"insufficient_evidence\"}. Never guess.")
        appendLine("8. If the message is NOT a completed financial transaction (OTP/2FA verification codes, login alerts, promotional/marketing offers, spam, delivery/service updates, balance enquiries without transfer, or future reminders), set \"isFinancial\": false, give a short \"reason\", and omit all financial fields.")
        appendLine("</rules_and_constraints>")
        appendLine()
        appendLine("<output_schema version=\"${request.schemaVersion}\">")
        appendLine("{")
        appendLine("  \"isFinancial\": boolean,             // REQUIRED: false if not a completed financial transaction or if evidence is insufficient")
        appendLine("  \"reason\": string | null,           // Brief explanation when isFinancial is false (e.g. \"OTP verification\", \"insufficient_evidence\")")
        appendLine("  \"amountMinor\": integer,            // REQUIRED if isFinancial is true (in paise, > 0)")
        appendLine("  \"currencyCode\": \"INR\",             // REQUIRED if isFinancial is true (3-letter ISO code)")
        appendLine("  \"direction\": \"DEBIT\" | \"CREDIT\",  // REQUIRED if isFinancial is true")
        appendLine("  \"accountToken\": string | null,     // Masked suffix e.g. \"XX1234\" if stated")
        appendLine("  \"rail\": \"UPI\" | \"IMPS\" | \"NEFT\" | \"RTGS\" | \"CARD_POS\" | \"CARD_ONLINE\" | \"ATM\" | \"ACH\" | \"UNKNOWN\" | null,")
        appendLine("  \"counterpartyRaw\": string | null,  // Payee/merchant name as written in message")
        appendLine("  \"counterpartyNormalized\": string | null, // Cleaned/normalized payee/merchant name")
        appendLine("  \"categorySuggestion\": string | null, // e.g. \"Groceries\", \"Utilities\", \"Dining\"")
        appendLine("  \"transferTargetToken\": string | null,")
        appendLine("  \"recurring\": boolean | null,       // true only if explicitly stated as recurring/mandate/autopay")
        appendLine("  \"emiDetail\": string | null,")
        appendLine("  \"occurredAtEpochMs\": integer | null,")
        appendLine("  \"confidence\": { \"<field>\": { \"value\": 0.0..1.0, \"explanation\": \"string\" } },")
        appendLine("  \"overallConfidence\": 0.0..1.0 | null")
        appendLine("}")
        appendLine("</output_schema>")
        appendLine()
        appendLine("<confidence_guidelines>")
        appendLine("- High (0.90 - 1.0): Field is explicitly, verbatim stated in the message text.")
        appendLine("- Medium (0.60 - 0.89): Inferred from unambiguous context or standard rail keywords without guessing.")
        appendLine("- Low (< 0.60): Ambiguous or incomplete context.")
        appendLine("</confidence_guidelines>")
        appendLine()
        appendLine("<few_shot_examples>")
        appendLine("Example 1 (Valid financial transaction - happy path):")
        appendLine("{\"isFinancial\": true, \"amountMinor\": 25000, \"currencyCode\": \"INR\", \"direction\": \"DEBIT\", \"accountToken\": \"XX1234\", \"rail\": \"UPI\", \"counterpartyRaw\": \"BigBazaar\", \"counterpartyNormalized\": \"Big Bazaar\", \"categorySuggestion\": \"Groceries\", \"confidence\": {\"amount\": {\"value\": 0.99, \"explanation\": \"Rs.250 stated verbatim\"}, \"direction\": {\"value\": 0.99, \"explanation\": \"debited stated verbatim\"}}, \"overallConfidence\": 0.98}")
        appendLine()
        appendLine("Example 2 (Non-financial message - OTP / Promo / Alert):")
        appendLine("{\"isFinancial\": false, \"reason\": \"OTP verification code\"}")
        appendLine()
        appendLine("Example 3 (Insufficient evidence / Ambiguous message):")
        appendLine("{\"isFinancial\": false, \"reason\": \"insufficient_evidence: missing amount and account\"}")
        appendLine("</few_shot_examples>")
        appendLine()
        appendLine("<evidence id=\"${request.sourceMessageId}\" receivedAt=\"${request.receivedAtEpochMs}\">")
        appendLine(request.bodyText)
        val nearby = request.nearbyEvidence.take(MAX_NEARBY)
        if (nearby.isNotEmpty()) {
            appendLine()
            appendLine("<nearby_context>")
            nearby.forEach { n ->
                appendLine("- [id=${n.sourceMessageId} at=${n.receivedAtEpochMs}] ${n.bodyText}")
            }
            appendLine("</nearby_context>")
        }
        appendLine("</evidence>")
    }

    /** Deterministic semantic-input hash key for caching. Accounts for promptVersion and schemaVersion. */
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
