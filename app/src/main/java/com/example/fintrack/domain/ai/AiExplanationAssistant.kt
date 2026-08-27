package com.example.fintrack.domain.ai

import com.example.fintrack.domain.service.LedgerTxnView

/**
 * Stage 10 / P22 — transaction explanation assistant with citation model
 * (module 171) and explanation guardrails (module 174).
 *
 * The assistant explains a transaction ONLY from supplied evidence and
 * provenance. It distinguishes EVIDENCE (facts recorded in the ledger /
 * raw SMS metadata) from INFERENCE (derived statements), never invents
 * unsupported facts, and refuses unsupported requests instead of
 * hallucinating.
 *
 * Every claim carries a [Citation] pointing at a transaction id, aggregate
 * key or evidence source that actually exists in the supplied context.
 */
class AiExplanationAssistant {

    sealed interface Citation {
        data class Transaction(val transactionId: String) : Citation
        data class Evidence(val rawSmsId: String) : Citation
        data class Aggregate(val dimension: AiQueryPlan.Dimension?, val key: String?) : Citation
        data object Provenance : Citation
    }

    enum class ClaimKind { EVIDENCE, INFERENCE }

    data class ExplanationClaim(
        val text: String,
        val kind: ClaimKind,
        val citation: Citation,
    )

    data class Explanation(
        val claims: List<ExplanationClaim>,
        /** Explicit unknowns the evidence cannot answer. Never guessed away. */
        val unknowns: List<String>,
        val refused: Boolean,
        val refusalReason: String?,
    )

    /**
     * Build an explanation for one transaction from its ledger view plus
     * optional evidence ids. Deterministic; no model in the loop.
     *
     * @param request user's question — checked against [AiSafetyPolicy] first
     */
    fun explain(
        request: String,
        txn: LedgerTxnView,
        evidenceSmsIds: List<String> = emptyList(),
        hasInterpretationProvenance: Boolean = false,
        linkedRefundIds: List<String> = emptyList(),
        linkedFeeIds: List<String> = emptyList(),
    ): Explanation {
        val safety = AiSafetyPolicy.evaluate(request)
        if (safety.verdict == AiSafetyPolicy.Verdict.REFUSE) {
            return Explanation(
                claims = emptyList(),
                unknowns = emptyList(),
                refused = true,
                refusalReason = safety.message,
            )
        }

        val claims = mutableListOf<ExplanationClaim>()
        val unknowns = mutableListOf<String>()

        // ---- evidence-backed facts ----
        claims += ExplanationClaim(
            text = "Recorded as ${txn.kind}${txn.subtype?.let { " ($it)" } ?: ""} of ${txn.amountMinor} minor on ${txn.accountId}.",
            kind = ClaimKind.EVIDENCE,
            citation = Citation.Transaction(txn.id),
        )
        if (evidenceSmsIds.isNotEmpty()) {
            claims += ExplanationClaim(
                text = "Supported by ${evidenceSmsIds.size} raw SMS evidence record(s).",
                kind = ClaimKind.EVIDENCE,
                citation = Citation.Evidence(evidenceSmsIds.first()),
            )
        } else {
            unknowns += "No raw SMS evidence is linked to this event (manual entry or import)."
        }
        if (hasInterpretationProvenance) {
            claims += ExplanationClaim(
                text = "An AI interpretation contributed to this record; it is advisory and the stored values are authoritative.",
                kind = ClaimKind.INFERENCE,
                citation = Citation.Provenance,
            )
        } else {
            unknowns += "No AI interpretation provenance exists for this event."
        }

        // ---- derived (inference) statements ----
        if (linkedRefundIds.isNotEmpty()) {
            claims += ExplanationClaim(
                text = "${linkedRefundIds.size} refund(s) are linked to this expense.",
                kind = ClaimKind.INFERENCE,
                citation = Citation.Transaction(linkedRefundIds.first()),
            )
        }
        if (linkedFeeIds.isNotEmpty()) {
            claims += ExplanationClaim(
                text = "${linkedFeeIds.size} fee event(s) are linked to this transaction.",
                kind = ClaimKind.INFERENCE,
                citation = Citation.Transaction(linkedFeeIds.first()),
            )
        }
        if (txn.categoryId.isNullOrBlank()) {
            unknowns += "This transaction is uncategorized; no category claim can be made."
        }
        if (txn.rail == null) {
            unknowns += "Payment rail is unknown for this event."
        }

        return Explanation(
            claims = claims,
            unknowns = unknowns,
            refused = false,
            refusalReason = null,
        )
    }

    /**
     * Validate a model-proposed narrative against the retrieved facts
     * (module 174 guardrail). Every cited id must exist; every number in the
     * narrative must appear in the allowed set. Unsupported content causes
     * rejection, not silent passage.
     */
    fun validateNarrative(
        narrative: String,
        knownTransactionIds: Set<String>,
        knownEvidenceIds: Set<String> = emptySet(),
        allowedAmountsMinor: Set<Long> = emptySet(),
    ): Result<Unit> {
        // Cited transaction ids must exist.
        Regex("txn:([A-Za-z0-9-]+)").findAll(narrative).forEach { m ->
            val id = m.groupValues[1]
            if (id !in knownTransactionIds) {
                return Result.failure(
                    IllegalArgumentException("cited transaction '$id' does not exist"),
                )
            }
        }
        Regex("sms:([A-Za-z0-9-]+)").findAll(narrative).forEach { m ->
            val id = m.groupValues[1]
            if (knownEvidenceIds.isNotEmpty() && id !in knownEvidenceIds) {
                return Result.failure(
                    IllegalArgumentException("cited evidence '$id' does not exist"),
                )
            }
        }
        // Amounts must be grounded.
        Regex("(?:Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").findAll(narrative).forEach { m ->
            val minor = m.groupValues[1].replace(",", "").toDoubleOrNull()
                ?.let { Math.round(it * 100) } ?: -1L
            if (allowedAmountsMinor.isNotEmpty() && minor !in allowedAmountsMinor) {
                return Result.failure(
                    IllegalArgumentException("amount ${m.groupValues[1]} not present in retrieved facts"),
                )
            }
        }
        // Advice-shaped sentences are refused outright.
        val adviceCheck = AiSafetyPolicy.evaluate(narrative)
        if (adviceCheck.verdict == AiSafetyPolicy.Verdict.REFUSE &&
            adviceCheck.rule == AiSafetyPolicy.Rule.FINANCIAL_ADVICE
        ) {
            return Result.failure(IllegalArgumentException(adviceCheck.message ?: "financial advice refused"))
        }
        return Result.success(Unit)
    }
}
