package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v3 account-authority blueprint. Accounts become authoritative balance
 * containers with identity hints (last4/institution), nickname, lifecycle,
 * opening balances, immutable balance snapshots, sender mappings and learned
 * institution aliases.
 */

@Entity(
    tableName = "account_opening_balances",
    indices = [Index(value = ["accountId"], unique = true)],
)
data class AccountOpeningBalanceEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val asOfEpochMs: Long,
)

@Entity(
    tableName = "balance_snapshots",
    indices = [
        Index("accountId", "capturedAtEpochMs"),
        Index(value = ["snapshotIdentity"], unique = true), // idempotency
    ],
)
data class BalanceSnapshotEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val kind: String,                       // MANUAL_ACTUAL | STATEMENT | SMS_EVIDENCE
    val messageId: String?,                 // evidence link; never raw body content
    val capturedAtEpochMs: Long,
    val sourceKind: String,
    val sourceVersion: String,
    val snapshotIdentity: String,           // hash(account, amount, kind, capturedAt)
)

@Entity(
    tableName = "sender_account_mappings",
    indices = [
        Index(value = ["senderId", "accountId"], unique = true),
        Index("accountId"),
    ],
)
data class SenderAccountMappingEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val accountId: String,
    val confirmedByUser: Boolean,           // ownership is user-confirmed only
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "institution_aliases",
    indices = [Index(value = ["aliasNormalized"], unique = true)],
)
data class InstitutionAliasEntity(
    @PrimaryKey val id: String,
    val aliasRaw: String,
    val aliasNormalized: String,
    val canonicalInstitution: String,
    val confirmedByUser: Boolean,
)
