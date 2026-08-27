package com.example.fintrack.domain

import com.example.fintrack.domain.model.Account
import com.example.fintrack.domain.model.AccountLifecycle
import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.OpeningBalance
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.service.AccountIdentityResolver
import com.example.fintrack.domain.service.BalanceCalculator
import com.example.fintrack.domain.service.InstitutionAliasRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Focused tests for the account-authority increment: balances, reconciliation,
 * identity resolution with duplicate last4, and alias normalization.
 */
class AccountAuthorityTest {

    private fun account(
        nickname: String, last4: String?, institution: String?,
        lifecycle: AccountLifecycle = AccountLifecycle.ACTIVE,
    ) = Account(
        id = EntityId.generate(), nickname = nickname, type = AccountType.BANK,
        currencyCode = "INR", last4 = last4, institutionName = institution,
        lifecycle = lifecycle, createdAt = Instant.now(),
    )

    // ---- BalanceCalculator ----

    @Test
    fun `derived balance is opening plus credits minus debits`() {
        val opening = OpeningBalance(EntityId.generate(), Money(10_000, "INR"), Instant.now())
        val derived = BalanceCalculator.derivedBalance(
            opening,
            listOf(
                BalanceCalculator.Posting(PostingDirection.CREDIT, 2_500),
                BalanceCalculator.Posting(PostingDirection.DEBIT, 1_000),
            ),
        )
        assertEquals(11_500L, derived)
    }

    @Test
    fun `derived balance without opening starts at zero`() {
        val derived = BalanceCalculator.derivedBalance(null, listOf(BalanceCalculator.Posting(PostingDirection.CREDIT, 500)))
        assertEquals(500L, derived)
    }

    @Test
    fun `reconciliation shows explicit difference and never auto-adjusts`() {
        val rec = BalanceCalculator.reconcile(actualMinor = 9_000, derivedMinor = 8_750, asOfActual = Instant.now())
        assertFalse(rec.reconciled)
        assertEquals(250L, rec.differenceMinor)
    }

    @Test
    fun `reconciliation passes when actual equals derived`() {
        val rec = BalanceCalculator.reconcile(1_234L, 1_234L, Instant.now())
        assertTrue(rec.reconciled)
        assertEquals(0L, rec.differenceMinor)
    }

    // ---- Account identity ----

    @Test
    fun `two same-bank accounts with same last4 are both proposed as ambiguous`() {
        val a = account("Salary", "1234", "hdfc bank")
        val b = account("Savings", "1234", "hdfc bank")
        val proposal = AccountIdentityResolver.propose(listOf(a, b), "hdfc bank", "1234")
        assertTrue(proposal.ambiguous)
        assertEquals(setOf(a.id.value, b.id.value), proposal.candidateAccountIds.toSet())
    }

    @Test
    fun `unique last4 resolves to single candidate`() {
        val a = account("Salary", "1234", "hdfc bank")
        val b = account("Savings", "5678", "hdfc bank")
        val proposal = AccountIdentityResolver.propose(listOf(a, b), "hdfc bank", "5678")
        assertFalse(proposal.ambiguous)
        assertEquals(listOf(b.id.value), proposal.candidateAccountIds)
    }

    @Test
    fun `unknown suffix never fabricates identity`() {
        val a = account("Salary", null, "hdfc bank")
        val proposal = AccountIdentityResolver.propose(listOf(a), "hdfc bank", null)
        assertTrue(proposal.candidateAccountIds.isEmpty())
        assertEquals("UNKNOWN_SUFFIX", proposal.reason)
    }

    @Test
    fun `archived accounts are excluded from proposals but history remains queryable via model`() {
        val archived = account("Old", "9999", "icici", AccountLifecycle.ARCHIVED)
        val proposal = AccountIdentityResolver.propose(listOf(archived), "icici", "9999")
        assertTrue(proposal.candidateAccountIds.isEmpty())
        assertEquals(AccountLifecycle.ARCHIVED, archived.lifecycle) // still present for history
    }

    @Test
    fun `no match reports NO_MATCH instead of guessing`() {
        val proposal = AccountIdentityResolver.propose(emptyList(), "sbi", "1111")
        assertEquals("NO_MATCH", proposal.reason)
    }

    @Test
    fun `account rejects malformed last4`() {
        try {
            account("Bad", "12a4", "hdfc")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---- Alias normalization ----

    @Test
    fun `confirmed aliases normalize case and whitespace`() {
        val registry = InstitutionAliasRegistry(listOf("HDFC  Bank" to "hdfc"))
        assertEquals("hdfc", registry.canonicalize("hdfc bank"))
        assertNull(registry.canonicalize("unknown co-op"))
        assertNull(registry.canonicalize(null))
    }
}
