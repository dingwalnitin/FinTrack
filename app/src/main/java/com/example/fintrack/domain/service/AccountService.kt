package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.Account
import com.example.fintrack.domain.model.BalanceSnapshot
import com.example.fintrack.domain.model.OpeningBalance
import com.example.fintrack.domain.model.PostingDirection
import java.time.Instant

/**
 * Derived (running) balance computation. Pure domain logic — no storage.
 *
 * Balance = opening balance + Σ credits − Σ debits, all in the account's
 * currency, computed with integer minor units only.
 */
object BalanceCalculator {

    data class Posting(val direction: PostingDirection, val amountMinor: Long)

    fun derivedBalance(
        opening: OpeningBalance?,
        postings: List<Posting>,
    ): Long {
        var balance = opening?.amount?.minorUnits ?: 0L
        for (p in postings) {
            balance = when (p.direction) {
                PostingDirection.CREDIT -> Math.addExact(balance, p.amountMinor)
                PostingDirection.DEBIT -> Math.subtractExact(balance, p.amountMinor)
            }
        }
        return balance
    }

    /**
     * Reconciliation: compare an actual (snapshot/manual) balance against the
     * ledger-derived balance. The difference is explicit — never auto-adjusted.
     */
    data class Reconciliation(
        val actualMinor: Long,
        val derivedMinor: Long,
        val differenceMinor: Long,
        val reconciled: Boolean,
        val asOfActual: Instant,
    )

    fun reconcile(
        actualMinor: Long,
        derivedMinor: Long,
        asOfActual: Instant,
    ): Reconciliation {
        val diff = Math.subtractExact(actualMinor, derivedMinor)
        return Reconciliation(
            actualMinor = actualMinor,
            derivedMinor = derivedMinor,
            differenceMinor = diff,
            reconciled = diff == 0L,
            asOfActual = asOfActual,
        )
    }
}

/**
 * Account identity resolution. SMS/parser/LLM may *propose* identity from
 * masked digits + institution; ownership is always user-confirmed.
 * Never fabricates identity when suffix is unknown or ambiguous.
 */
object AccountIdentityResolver {

    data class Proposal(
        val candidateAccountIds: List<String>,
        val ambiguous: Boolean,
        val reason: String?,
    )

    /**
     * @param last4 masked digits from evidence, or null if unknown.
     * @return proposals restricted to ACTIVE accounts of matching institution.
     *         Ambiguous when multiple candidates share the same last4.
     */
    fun propose(
        candidates: List<Account>,
        institutionNormalized: String?,
        last4: String?,
    ): Proposal {
        require(last4 == null || (last4.length == 4 && last4.all { it.isDigit() }))
        var filtered = candidates.filter { it.lifecycle == com.example.fintrack.domain.model.AccountLifecycle.ACTIVE }
        if (!institutionNormalized.isNullOrBlank()) {
            filtered = filtered.filter { it.institutionName == institutionNormalized }
        }
        if (!last4.isNullOrBlank()) {
            filtered = filtered.filter { it.last4 == last4 }
        } else {
            // Unknown suffix: cannot narrow to a single account without guessing.
            return Proposal(emptyList(), ambiguous = false, reason = "UNKNOWN_SUFFIX")
        }
        return when {
            filtered.isEmpty() -> Proposal(emptyList(), ambiguous = false, reason = "NO_MATCH")
            filtered.size > 1 -> Proposal(filtered.map { it.id.value }, ambiguous = true, reason = "DUPLICATE_LAST4")
            else -> Proposal(listOf(filtered.single().id.value), ambiguous = false, reason = null)
        }
    }
}

/**
 * Local bank/institution alias normalization. Only user-confirmed aliases are
 * applied automatically; unconfirmed ones are returned as proposals only.
 */
class InstitutionAliasRegistry(confirmed: List<Pair<String, String>>) {

    private val map: Map<String, String> =
        confirmed.associate { (raw, canonical) -> normalize(raw) to canonical }

    /** Returns canonical institution name, or null when unknown (kept unknown). */
    fun canonicalize(rawInstitution: String?): String? {
        if (rawInstitution.isNullOrBlank()) return null
        return map[normalize(rawInstitution)]
    }

    companion object {
        fun normalize(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
