package com.example.fintrack.domain

import com.example.fintrack.domain.model.Transaction

/**
 * Non-goals registry (App Bible). Any feature touching these must be rejected
 * in review and guarded by architecture tests / lint review.
 */
object NonGoals {
    val FORBIDDEN_CAPABILITIES = listOf(
        "bank login or bank API access",
        "investment tracking",
        "money transfer execution",
        "cloud backup/sync",
        "push notifications",
        "SMS deletion",
    )

    /** Remote arbitrary configuration is forbidden; config is local + safe defaults. */
    const val REMOTE_CONFIG_ALLOWED = false
}

/**
 * Repository contract: emits cached local data first, then refreshes.
 * UI never touches Room directly.
 */
interface FinanceRepository {
    fun observeTransactions(): kotlinx.coroutines.flow.Flow<List<Transaction>>
}
