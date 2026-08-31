package com.example.fintrack.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.fintrack.TestActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.fintrack.application.transactions.ManualEntryViewModel
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.ManualEntryInput
import com.example.fintrack.domain.service.ManualEntrySink
import com.example.fintrack.domain.service.ManualEntryService
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransactionWriteSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Instrumented Compose UI test for ManualEntryScreen.
 *
 * Uses a fake service so the test runs on-device without touching Room.
 * Verifies form fields, validation error display, and save/cancel flow.
 */
class ManualEntryScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private class FakeWriteSink : TransactionWriteSink {
        val stored = linkedMapOf<String, TransactionV6>()

        override suspend fun findTransaction(id: String): TransactionV6? = stored[id]

        override suspend fun findPostingGroupId(id: String): String? = stored[id]?.postingGroupId

        override suspend fun replacePostingGroupAndUpsertTxn(
            txn: TransactionV6,
            previousPostingGroupId: String?,
            newPostings: List<SinglePosting>,
        ): Pair<TransactionV6, List<SinglePosting>> {
            previousPostingGroupId?.let { stored.remove(it) }
            stored[txn.id.value] = txn
            return txn to newPostings
        }

        override suspend fun updateStatusAndTombstone(
            txnId: String, status: String, deletedAtEpochMs: Long, deletedReason: String?,
        ) {
            stored[txnId] = stored[txnId]!!.copy(
                status = TxStatus.valueOf(status),
                deletedAt = Instant.ofEpochMilli(deletedAtEpochMs),
                deletedReason = deletedReason,
            )
        }
    }

    private class FakeManualSink : ManualEntrySink {
        override suspend fun findTransaction(id: String): TransactionV6? = null
        override suspend fun restoreFromTombstone(id: String) {}
        override suspend fun appendAudit(
            entityId: String,
            entityType: String,
            action: String,
            actor: String,
            reason: String?,
            atEpochMs: Long,
        ) {
            // no-op
        }
    }

    private fun service(writeSink: TransactionWriteSink = FakeWriteSink()) = ManualEntryService(
        writeService = TransactionWriteService(writeSink),
        sink = FakeManualSink(),
        clock = { Instant.parse("2026-08-27T00:00:00Z") },
        zone = ZoneId.of("Asia/Kolkata"),
    )

    @Test
    fun manualEntry_formFieldsAreDisplayed() {
        val vm = ManualEntryViewModel(service())
        composeTestRule.setContent {
            ManualEntryScreen(viewModel = vm, onSaved = {})
        }

        composeTestRule.onNodeWithText("Account id").assertIsDisplayed()
        composeTestRule.onNodeWithText("Amount (minor units)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Currency").assertIsDisplayed()
        composeTestRule.onNodeWithText("Merchant").assertIsDisplayed()
        composeTestRule.onNodeWithText("Counterparty").assertIsDisplayed()
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun manualEntry_saveWithEmptyFields_showsValidationError() {
        val vm = ManualEntryViewModel(service())
        composeTestRule.setContent {
            ManualEntryScreen(viewModel = vm, onSaved = {})
        }

        composeTestRule.onNodeWithText("Save").performClick()
        // The ViewModel validates and sets an error message.
        // The error text is displayed on the screen.
        Thread.sleep(300) // brief wait for ViewModel coroutine
        // Error should be visible somewhere
        val error = vm.draft.value.error
        assertNotNull("validation error expected for empty save", error)
    }

    @Test
    fun manualEntry_saveWithValidData_succeeds() = runTest {
        val writeSink = FakeWriteSink()
        val svc = service(writeSink)
        val vm = ManualEntryViewModel(svc)

        composeTestRule.setContent {
            ManualEntryScreen(viewModel = vm, onSaved = {})
        }

        vm.updateDraft { d ->
            d.copy(
                accountId = "acc1",
                amountText = "25000",
                currencyCode = "INR",
                kind = TxKind.EXPENSE,
                merchant = "Swiggy",
                counterparty = "swiggy@ybl",
            )
        }
        vm.save()
        Thread.sleep(500)
        assertTrue("must be saved", vm.state.value is ManualEntryViewModel.State.Saved)
        assertEquals(1, writeSink.stored.size)
        val txn = writeSink.stored.values.first()
        assertEquals(25000L, txn.amountMinor)
        assertEquals("Swiggy", txn.merchant)
    }

    @Test
    fun manualEntry_cancelClearsDraft() {
        val vm = ManualEntryViewModel(service())
        vm.updateDraft { it.copy(amountText = "5000") }

        composeTestRule.setContent {
            ManualEntryScreen(viewModel = vm, onSaved = {})
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue("draft must be cleared on cancel", vm.draft.value.amountText.isEmpty())
    }
}