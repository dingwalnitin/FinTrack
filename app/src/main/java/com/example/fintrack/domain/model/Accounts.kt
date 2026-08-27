package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Account domain model — authoritative balance container.
 *
 * Identity rules (App Bible):
 *  - id is a stable UUID, never derived from user content.
 *  - last4 + institution are *identity hints* only; ownership is user-confirmed.
 *  - Multiple accounts may share the same last4 at the same bank; uniqueness
 *    comes from the UUID + nickname, never from masked digits.
 */
enum class AccountType { BANK, CREDIT_CARD, CASH, OTHER_LIABILITY }

enum class AccountLifecycle { ACTIVE, ARCHIVED }

data class Account(
    val id: EntityId,
    val nickname: String,
    val type: AccountType,
    val currencyCode: String,           // ISO-4217; INR/USD supported
    val last4: String?,                 // masked digits; may be unknown
    val institutionName: String?,       // bank / issuer identity (normalized)
    val lifecycle: AccountLifecycle,
    val createdAt: Instant,
) {
    init {
        require(currencyCode.length == 3)
        if (last4 != null) {
            require(last4.length == 4 && last4.all { it.isDigit() }) {
                "last4 must be exactly 4 digits or null"
            }
        }
    }

    /** Display identity that stays distinguishable even with duplicate last4. */
    fun displayName(): String =
        listOfNotNull(institutionName, nickname).joinToString(" · ") +
            (last4?.let { " (••••$it)" } ?: "")
}

/**
 * Opening balance: the seed of every derived balance. Traceable by definition.
 */
data class OpeningBalance(
    val accountId: EntityId,
    val amount: Money,
    val asOf: Instant,
)

/**
 * Timestamped balance snapshot from an explicit source. Immutable once written.
 * kind tells where it came from so reconciliation can explain differences.
 */
enum class BalanceSnapshotKind { MANUAL_ACTUAL, STATEMENT, SMS_EVIDENCE }

data class BalanceSnapshot(
    val id: EntityId,
    val accountId: EntityId,
    val amount: Money,
    val kind: BalanceSnapshotKind,
    val messageId: EntityId?,           // evidence link when derived from SMS
    val capturedAt: Instant,
    val provenance: Provenance,
)

/**
 * Sender/account mapping. Many sender IDs may map to one account; one sender
 * may also map to several accounts of the same bank (duplicate last4 case).
 * Mapping is a proposal until confirmedByUser = true.
 */
data class SenderAccountMapping(
    val senderId: String,
    val accountId: EntityId,
    val confirmedByUser: Boolean,
    val provenance: Provenance,
)

/**
 * Bank/institution alias learned locally. Only user-confirmed aliases are used
 * for automatic normalization; unconfirmed ones stay proposals.
 */
data class InstitutionAlias(
    val aliasRaw: String,
    val canonicalInstitution: String,
    val confirmedByUser: Boolean,
)

/** Multi-currency storage contract: original amount + original currency. */
data class OriginalAmount(
    val amountMinor: Long,
    val currencyCode: String,
)
