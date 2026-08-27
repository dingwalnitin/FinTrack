package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.service.BulkCorrectionService
import com.example.fintrack.domain.service.CategorizationSink
import com.example.fintrack.domain.service.TagsNotesService
import com.example.fintrack.domain.service.TagsNotesSink
import com.example.fintrack.domain.model.Category
import com.example.fintrack.domain.model.CategoryAudit
import com.example.fintrack.domain.model.CategoryKind
import com.example.fintrack.domain.model.CategoryRule
import com.example.fintrack.domain.model.CategoryStatus
import com.example.fintrack.domain.model.LlmCategorySuggestion
import com.example.fintrack.domain.model.Merchant
import com.example.fintrack.domain.model.MerchantAlias
import com.example.fintrack.domain.model.MerchantVpaBinding
import com.example.fintrack.domain.model.ReimbursementLink
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.RuleStatus
import com.example.fintrack.domain.model.TransactionNote
import com.example.fintrack.domain.model.TransactionTag
import com.example.fintrack.domain.model.TravelMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Stage 7 P15 — bulk correction, tags/notes, reimbursement, travel mode.
 *
 * Acceptance gate: bulk/split/reimbursement/travel operations are
 * reversible/auditable; per-field/user provenance is preserved.
 */
class BulkAndTagsTest {

    private val now = Instant.ofEpochMilli(1_700_000_000_000L)

    /** In-memory categorization sink recording every audit row. */
    private class FakeCatSink : CategorizationSink {
        val audits = mutableListOf<CategoryAudit>()
        val applied = mutableListOf<Triple<String, EntityId?, EntityId?>>()
        var latestAuditForTxn: MutableMap<String, List<CategoryAudit>> = mutableMapOf()

        override suspend fun findUncategorized(): Category? = null
        override suspend fun findCategoryByNormalizedName(normalized: String): Category? = null
        override suspend fun insertCategory(category: Category, normalizedKey: String) = true
        override suspend fun archiveCategory(categoryId: EntityId) {}
        override suspend fun findMerchantByIdentity(identity: String): Merchant? = null
        override suspend fun findMerchantById(id: EntityId): Merchant? = null
        override suspend fun insertMerchant(merchant: Merchant, identity: String) = true
        override suspend fun listActiveMerchants(): List<Merchant> = emptyList()
        override suspend fun mergeMerchant(source: EntityId, target: EntityId) {}
        override suspend fun findAlias(merchantId: EntityId, aliasNormalized: String): MerchantAlias? = null
        override suspend fun insertAlias(alias: MerchantAlias, aliasIdentity: String) = true
        override suspend fun aliasesForMerchant(merchantId: EntityId): List<MerchantAlias> = emptyList()
        override suspend fun findVpaBinding(vpa: String): MerchantVpaBinding? = null
        override suspend fun insertVpaBinding(binding: MerchantVpaBinding, vpaIdentity: String) = true
        override suspend fun confirmedVpaBindings(): List<MerchantVpaBinding> = emptyList()
        override suspend fun activeRules(): List<CategoryRule> = emptyList()
        override suspend fun insertRule(rule: CategoryRule) = true
        override suspend fun findRuleById(id: String): CategoryRule? = null
        override suspend fun disableRule(id: String) {}
        override suspend fun insertLlmSuggestion(suggestion: LlmCategorySuggestion, identity: String) = true
        override suspend fun acceptLlmSuggestion(id: String, atMs: Long) {}
        override suspend fun appendCategoryAudit(
            transactionId: String,
            previousCategoryId: EntityId?,
            newCategoryId: EntityId?,
            previousMerchantId: EntityId?,
            newMerchantId: EntityId?,
            actor: String,
            sourceKind: String,
            sourceVersion: String,
            reason: String?,
            ruleId: String?,
            atEpochMs: Long,
        ) {
            val audit = CategoryAudit(
                id = "audit-${audits.size}", transactionId = transactionId,
                previousCategoryId = previousCategoryId, newCategoryId = newCategoryId,
                previousMerchantId = previousMerchantId, newMerchantId = newMerchantId,
                actor = actor, sourceKind = sourceKind, sourceVersion = sourceVersion,
                reason = reason, ruleId = ruleId, atEpochMs = atEpochMs,
            )
            audits += audit
            latestAuditForTxn[transactionId] =
                (latestAuditForTxn[transactionId] ?: emptyList()) + audit
        }
        override suspend fun applyCategorization(
            transactionId: String,
            categoryId: EntityId?,
            merchantId: EntityId?,
            sourceKind: String,
            sourceVersion: String,
            sourceReason: String?,
        ) {
            applied += Triple(transactionId, categoryId, merchantId)
        }
        override suspend fun latestAuditForTransaction(transactionId: String): CategoryAudit? =
            latestAuditForTxn[transactionId]?.lastOrNull()
    }

    private class FakeTagsNotesSink : TagsNotesSink {
        val tags = linkedMapOf<String, TransactionTag>()
        val notes = mutableListOf<TransactionNote>()
        val reimbLinks = mutableListOf<ReimbursementLink>()
        val travelModes = linkedMapOf<String, TravelMode>()
        private var counter = 0

        override suspend fun insertTransactionTag(tag: TransactionTag): Boolean {
            if (tags.values.any { it.transactionId == tag.transactionId && it.tag == tag.tag }) return false
            tags["t-${counter++}"] = tag; return true
        }
        override suspend fun deleteTransactionTag(transactionId: String, tag: String): Boolean {
            val key = tags.entries.firstOrNull { it.value.transactionId == transactionId && it.value.tag == tag }?.key
                ?: return false
            tags.remove(key); return true
        }
        override suspend fun tagsForTransaction(transactionId: String): List<TransactionTag> =
            tags.values.filter { it.transactionId == transactionId }.sortedBy { it.tag }
        override suspend fun upsertTransactionNote(note: TransactionNote): Boolean {
            notes += note; return true
        }
        override suspend fun latestNoteForTransaction(transactionId: String): TransactionNote? =
            notes.filter { it.transactionId == transactionId }.maxByOrNull { it.updatedAtEpochMs }
        override suspend fun insertReimbursementLink(link: ReimbursementLink): Boolean {
            if (reimbLinks.any { it.expenseTransactionId == link.expenseTransactionId &&
                    it.reimbursingTransactionId == link.reimbursingTransactionId }) return false
            reimbLinks += link; return true
        }
        override suspend fun reimbursementsForExpense(expenseTransactionId: String): List<ReimbursementLink> =
            reimbLinks.filter { it.expenseTransactionId == expenseTransactionId }
        override suspend fun insertTravelMode(mode: TravelMode): Boolean {
            if (travelModes.values.any { it.accountId == mode.accountId && it.status.name == "ACTIVE" }) return false
            travelModes[mode.id] = mode; return true
        }
        override suspend fun updateTravelModeStatus(id: String, status: String, endDay: Long?): Boolean {
            val m = travelModes[id] ?: return false
            travelModes[id] = m.copy(
                status = com.example.fintrack.domain.model.TravelModeStatus.valueOf(status),
                endEpochDay = endDay,
            )
            return true
        }
        override suspend fun activeTravelModesFor(accountId: String): List<TravelMode> =
            travelModes.values.filter {
                it.accountId.value == accountId &&
                    it.status == com.example.fintrack.domain.model.TravelModeStatus.ACTIVE
            }
    }

    // ---- bulk correction ----

    @Test
    fun `preview omits rows that would not change`() = runTest {
        val sink = FakeCatSink()
        // Seed an existing audit for t1 so its current category is known.
        sink.appendCategoryAudit(
            transactionId = "t1",
            previousCategoryId = null, newCategoryId = EntityId("cat-old"),
            previousMerchantId = null, newMerchantId = null,
            actor = "SYSTEM", sourceKind = "RULE", sourceVersion = "v1",
            reason = "seed", ruleId = null, atEpochMs = 0L,
        )
        val svc = BulkCorrectionService(sink)
        val preview = svc.preview(
            transactionIds = listOf("t1", "t2"),
            newCategoryId = EntityId("cat-old"), // same as t1's current -> no-op
            newMerchantId = null,
        )
        assertEquals(listOf("t2"), preview.map { it.transactionId })
    }

    @Test
    fun `commit applies every change and appends one audit row each`() = runTest {
        val sink = FakeCatSink()
        val svc = BulkCorrectionService(sink)
        val changes = listOf(
            BulkCorrectionService.ProposedChange("t1", null, EntityId("cat-a"), null, null),
            BulkCorrectionService.ProposedChange("t2", null, EntityId("cat-a"), null, null),
        )
        val result = svc.commit(changes, reason = "reclassify groceries")
        assertTrue(result.allSucceeded)
        assertEquals(2, result.applied)
        assertEquals(2, sink.audits.size)
        assertEquals(2, sink.applied.size)
        assertTrue(sink.audits.all { it.actor == "USER" })
        assertTrue(sink.audits.all { it.sourceKind == "USER_CORRECTION" })
        assertTrue(sink.audits.all { it.reason == "reclassify groceries" })
    }

    @Test
    fun `commit records the previous value for reversibility`() = runTest {
        val sink = FakeCatSink()
        val svc = BulkCorrectionService(sink)
        val changes = listOf(
            BulkCorrectionService.ProposedChange(
                "t1", currentCategoryId = EntityId("cat-old"),
                newCategoryId = EntityId("cat-new"),
                currentMerchantId = null, newMerchantId = null,
            ),
        )
        svc.commit(changes, reason = null)
        val audit = sink.audits.single()
        assertEquals(EntityId("cat-old"), audit.previousCategoryId)
        assertEquals(EntityId("cat-new"), audit.newCategoryId)
    }

    // ---- tags ----

    @Test
    fun `tags are normalized to lowercase and deduped`() = runTest {
        val sink = FakeTagsNotesSink()
        val svc = TagsNotesService(sink, clock = { now })
        assertTrue(svc.addTag("t1", "Travel"))
        assertFalse(svc.addTag("t1", "travel")) // duplicate after normalization
        assertEquals(listOf("travel"), svc.tagsFor("t1").map { it.tag })
    }

    @Test
    fun `blank tags are rejected`() = runTest {
        val svc = TagsNotesService(FakeTagsNotesSink(), clock = { now })
        assertFalse(svc.addTag("t1", "   "))
    }

    @Test
    fun `removeTag deletes only the matching tag`() = runTest {
        val sink = FakeTagsNotesSink()
        val svc = TagsNotesService(sink, clock = { now })
        svc.addTag("t1", "a")
        svc.addTag("t1", "b")
        assertTrue(svc.removeTag("t1", "A"))
        assertEquals(listOf("b"), svc.tagsFor("t1").map { it.tag })
    }

    // ---- notes ----

    @Test
    fun `latest note wins`() = runTest {
        val svc = TagsNotesService(FakeTagsNotesSink(), clock = { now })
        svc.setNote("t1", "first")
        svc.setNote("t1", "second")
        assertEquals("second", svc.latestNote("t1")!!.note)
    }

    // ---- reimbursement ----

    @Test
    fun `reimbursement link is idempotent on the pair`() = runTest {
        val svc = TagsNotesService(FakeTagsNotesSink(), clock = { now })
        assertTrue(svc.linkReimbursement("expense-1", "credit-1"))
        assertFalse(svc.linkReimbursement("expense-1", "credit-1"))
        assertEquals(1, svc.reimbursementsFor("expense-1").size)
    }

    // ---- travel mode ----

    @Test
    fun `only one active travel window per account`() = runTest {
        val svc = TagsNotesService(FakeTagsNotesSink(), clock = { now })
        val acc = EntityId("acc-1")
        assertTrue(svc.startTravelMode(acc, "Bali trip", "IDR", 20_000))
        assertFalse(svc.startTravelMode(acc, "Second trip", "EUR", 20_100))
        assertEquals(1, svc.activeTravelModesFor(acc.value).size)
    }

    @Test
    fun `ending a travel window allows a new one`() = runTest {
        val sink = FakeTagsNotesSink()
        val svc = TagsNotesService(sink, clock = { now })
        val acc = EntityId("acc-1")
        svc.startTravelMode(acc, "Trip A", "IDR", 20_000)
        val id = sink.travelModes.keys.first()
        assertTrue(svc.endTravelMode(id, 20_100))
        assertTrue(svc.startTravelMode(acc, "Trip B", "EUR", 20_200))
        assertEquals(1, svc.activeTravelModesFor(acc.value).size)
    }
}
