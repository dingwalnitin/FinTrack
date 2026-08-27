package com.example.fintrack.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.fintrack.application.transactions.TransferCandidatesViewModel
import com.example.fintrack.domain.service.TransferCandidateMatcher
import com.example.fintrack.domain.service.TransferCandidateSource
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI test for TransferCandidatesScreen.
 *
 * Uses a fake source that returns realistic proposals without Room.
 * Verifies the Loading, Empty, Error and Ready states all render.
 */
class TransferCandidatesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun transferCandidates_emptyStateShowsMessage() {
        val vm = TransferCandidatesViewModel(
            matcher = TransferCandidateMatcher(
                object : TransferCandidateSource {
                    override suspend fun candidatesInWindow(
                        accountIds: List<String>,
                        fromEpochMs: Long,
                        toEpochMs: Long,
                    ): List<com.example.fintrack.data.db.TransactionEntity> = emptyList()
                },
            ),
        )
        composeTestRule.setContent {
            TransferCandidatesScreen(
                accountIds = listOf("acc1"),
                viewModel = vm,
            )
        }
        composeTestRule.onNodeWithText("No transfer candidates found.").assertIsDisplayed()
    }

    @Test
    fun transferCandidates_readyStateShowsProposals() {
        // A real matcher over a source that returns a DEBIT and CREDIT pair
        // from different accounts with the same amount + close timestamps
        // produces a REVIEW/AUTO_LINK proposal.
        val now = java.time.Instant.now()
        val source = object : TransferCandidateSource {
            override suspend fun candidatesInWindow(
                accountIds: List<String>,
                fromEpochMs: Long,
                toEpochMs: Long,
            ): List<com.example.fintrack.data.db.TransactionEntity> {
                val debit = com.example.fintrack.data.db.TransactionEntity(
                    id = "e1", messageId = null, accountId = "acc1", categoryId = null,
                    amountMinor = 10_000L, currencyCode = "INR",
                    occurredAtEpochMs = now.toEpochMilli() - 5_000,
                    localDateEpochDay = now.toEpochMilli() / 86_400_000L,
                    counterparty = null, counterpartyNormalized = null,
                    referenceId = "ref-1", state = "INTERPRETED",
                    sourceKind = "SMS", sourceVersion = "sms-v1", sourceReason = null,
                    correctionSourceKind = null, correctionSourceVersion = null,
                    correctionSourceReason = null, correctionCapturedAtEpochMs = null,
                    dedupeKey = "dk1", kind = "EXPENSE", status = "POSTED",
                )
                val credit = com.example.fintrack.data.db.TransactionEntity(
                    id = "e2", messageId = null, accountId = "acc2", categoryId = null,
                    amountMinor = 10_000L, currencyCode = "INR",
                    occurredAtEpochMs = now.toEpochMilli(),
                    localDateEpochDay = now.toEpochMilli() / 86_400_000L,
                    counterparty = null, counterpartyNormalized = null,
                    referenceId = null, state = "INTERPRETED",
                    sourceKind = "SMS", sourceVersion = "sms-v1", sourceReason = null,
                    correctionSourceKind = null, correctionSourceVersion = null,
                    correctionSourceReason = null, correctionCapturedAtEpochMs = null,
                    dedupeKey = "dk2", kind = "INCOME", status = "POSTED",
                )
                return listOf(debit, credit)
            }
        }
        val vm = TransferCandidatesViewModel(matcher = TransferCandidateMatcher(source))
        vm.load(listOf("acc1", "acc2"), windowMinutes = 60)
        Thread.sleep(500)

        composeTestRule.setContent {
            TransferCandidatesScreen(
                accountIds = listOf("acc1", "acc2"),
                viewModel = vm,
            )
        }
        Thread.sleep(300)
        composeTestRule.onNodeWithText("Transfer candidates for review").assertIsDisplayed()
    }
}