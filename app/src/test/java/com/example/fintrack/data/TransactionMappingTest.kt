package com.example.fintrack.data

import com.example.fintrack.data.repository.toDomain
import com.example.fintrack.domain.model.LifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapping tests: corrections (user provenance) must survive the entity->domain
 * round trip, and lifecycle states must round-trip losslessly.
 */
class TransactionMappingTest {

    private fun entity(
        correctionKind: String? = null,
        state: String = LifecycleState.CONFIRMED.name,
    ) = com.example.fintrack.data.db.TransactionEntity(
        id = "t1",
        messageId = "m1",
        accountId = "acc1",
        categoryId = null,
        amountMinor = 1234,
        currencyCode = "USD",
        occurredAtEpochMs = 0L,
        localDateEpochDay = 0L,
        counterparty = "Coffee Shop",
        counterpartyNormalized = "coffee shop",
        referenceId = null,
        state = state,
        sourceKind = "SMS",
        sourceVersion = "sms-v1",
        sourceReason = null,
        correctionSourceKind = correctionKind,
        correctionSourceVersion = if (correctionKind != null) "user-v1" else null,
        correctionSourceReason = null,
        correctionCapturedAtEpochMs = if (correctionKind != null) 42L else null,
        dedupeKey = "dk1",
    )

    @Test
    fun `correction provenance survives mapping`() {
        val txn = entity(correctionKind = "USER_CORRECTION").toDomain()
        assertNotNull(txn.correctionOrigin)
        assertEquals("user-v1", txn.correctionOrigin?.sourceVersion)
    }

    @Test
    fun `no correction maps to null origin`() {
        assertNull(entity().toDomain().correctionOrigin)
    }

    @Test
    fun `lifecycle state round-trips`() {
        for (state in LifecycleState.entries) {
            assertEquals(state, entity(state = state.name).toDomain().state)
        }
    }
}
