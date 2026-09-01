package com.example.fintrack.domain.service

/**
 * Stage 13 (A) — pure payee identity + rule resolution logic, JVM-testable.
 *
 * A "payee" is identified by a normalized key derived from the UPI sender /
 * VPA / merchant / counterparty. Rules key on a stable sha-256 hash of that
 * identity so a rule is durable and idempotent.
 */
object PayeeIdentity {

    /**
     * Normalize a raw payee surface form into a stable key. Lowercases,
     * trims, collapses whitespace, strips common UPI decorations and keeps
     * the VPA (`name@bank`) intact.
     */
    fun normalize(raw: String?): String =
        raw?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    /**
     * Build a stable payee identity hash. Prefers a VPA when present
     * (`name@bank`), else the normalized name. sha-256 hex so it is safe to
     * key a unique index on.
     */
    fun identityHash(vpa: String?, name: String?): String {
        val vpaNorm = normalize(vpa)
        val nameNorm = normalize(name)
        val key = when {
            vpaNorm.contains("@") -> vpaNorm
            nameNorm.isNotEmpty() -> nameNorm
            else -> "unknown-payee"
        }
        return sha256(key)
    }

    /** SHA-256 hex digest (idempotency convention). */
    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Resolves the category to auto-apply for an inbound transaction's payee.
 * A rule, when present, is a post-categorization override: it wins over any
 * other categorization. Manual (USER_SET) rules are never auto-overwritten
 * by reprocessing.
 */
class PayeeRuleResolver(
    private val rules: Map<String, String>, // payeeIdentityHash -> categoryId
) {

    /** Resolve the category override for a payee, or null when none. */
    fun resolve(vpa: String?, name: String?): String? {
        val hash = PayeeIdentity.identityHash(vpa, name)
        return rules[hash]
    }

    fun hasRule(vpa: String?, name: String?): Boolean = resolve(vpa, name) != null
}

/**
 * Persistence contract for payee rules + transaction evidence (Stage 13 A + D).
 * Implemented by a Room repository; domain services depend on this interface,
 * never on Room.
 */
interface PayeeEvidenceSink {
    /** Upsert a per-payee category rule; idempotent by payee identity hash. */
    suspend fun upsertPayeeRule(
        payeeName: String,
        vpa: String?,
        categoryId: String,
        sourceKind: String,
        sourceVersion: String,
    ): Boolean

    suspend fun payeeRuleFor(vpa: String?, name: String?): PayeeRule?

    suspend fun allPayeeRules(): List<PayeeRule>

    /** Store the durable transaction -> evidence link. Never overwrites an existing rawLlmJson. */
    suspend fun storeTransactionEvidence(
        transactionId: String,
        sourceMessageId: String,
        rawLlmJson: String?,
    ): Boolean

    suspend fun evidenceFor(transactionId: String): List<TransactionEvidence>
}

data class PayeeRule(
    val payeeIdentityHash: String,
    val payeeName: String,
    val vpa: String?,
    val categoryId: String,
    val sourceKind: String,
)

data class TransactionEvidence(
    val transactionId: String,
    val sourceMessageId: String,
    val rawLlmJson: String?,
)
