package com.example.fintrack.domain

import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.model.ImportCommitResult
import com.example.fintrack.domain.model.ImportValidation
import com.example.fintrack.domain.model.MergePolicy
import com.example.fintrack.domain.service.BackupCodec
import com.example.fintrack.domain.service.BackupSink
import com.example.fintrack.domain.service.BackupService
import com.example.fintrack.domain.service.CsvInteropEngine
import com.example.fintrack.domain.service.ExportRedactionEngine
import com.example.fintrack.domain.service.LlmMinimization
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11 required realistic ambiguous / conflicting Indian financial
 * fixtures — not happy-path only:
 *
 *  1. A conflicting export: the same transaction id exists live with a
 *     different amount (Rs.250 vs Rs.2,550 — the classic OCR/SMS confusion)
 *     and the import must surface the conflict, never silently overwrite.
 *  2. A CSV with mixed sign conventions and dd/MM/yyyy dates (typical Indian
 *     bank statement) where one row is malformed — errors are surfaced,
 *     valid rows still import.
 *  3. An export row carrying a full phone number + OTP in the merchant note;
 *     redaction must strip them while keeping the VPA and amount.
 *  4. LLM minimization on a Hinglish UPI message with an unregistered VPA.
 */
class AmbiguousIndianMessageStage11Test {

    private class FakeSink : BackupSink {
        val live = mutableMapOf<BackupDataset, MutableMap<String, String>>()
        val staged = mutableMapOf<BackupDataset, MutableMap<String, String>>()

        override suspend fun exportRows(dataset: BackupDataset): List<String> =
            live[dataset]?.values?.toList() ?: emptyList()

        override suspend fun beginBatch(formatVersion: Int, schemaVersion: Int, totalRows: Int) {
            // no-op for the in-memory fake
        }

        override suspend fun stageRows(dataset: BackupDataset, rows: List<String>) {
            val m = staged.getOrPut(dataset) { mutableMapOf() }
            rows.forEach { m[stableId(it)] = it }
        }

        override suspend fun stagedDatasets(): List<BackupDataset> =
            staged.filterValues { it.isNotEmpty() }.keys.toList()

        override suspend fun stagedRowCount(dataset: BackupDataset) = staged[dataset]?.size ?: 0

        override suspend fun stagedIds(dataset: BackupDataset): List<String> =
            staged[dataset]?.keys?.toList() ?: emptyList()

        override suspend fun stagedRowById(dataset: BackupDataset, stableId: String): String? =
            staged[dataset]?.get(stableId)

        override suspend fun clearStaging() = staged.clear()

        override suspend fun commitStaged(
            policy: MergePolicy,
            replaceIds: Map<BackupDataset, Set<String>>,
        ): Pair<Map<BackupDataset, Int>, Map<BackupDataset, Int>> {
            var inserted = 0
            var replaced = 0
            for ((ds, rows) in staged) {
                for ((id, row) in rows) {
                    val target = live.getOrPut(ds) { mutableMapOf() }
                    if (target.containsKey(id)) {
                        if (id in (replaceIds[ds] ?: emptySet())) { target[id] = row; replaced++ }
                    } else { target[id] = row; inserted++ }
                }
            }
            staged.clear()
            return Pair(
                BackupDataset.entries.associateWith { inserted },
                BackupDataset.entries.associateWith { replaced },
            )
        }

        override suspend fun liveRowById(dataset: BackupDataset, stableId: String) =
            live[dataset]?.get(stableId)

        override suspend fun liveIds(dataset: BackupDataset): List<String> =
            live[dataset]?.keys?.toList() ?: emptyList()

        private fun stableId(row: String) =
            row.split(';').firstOrNull { it.startsWith("id=") }?.removePrefix("id=") ?: ""
    }

    private fun service(sink: FakeSink) = BackupService(sink, BackupCodec()) { 42L }

    /**
     * Fixture 1: same txn id, Rs.250 live vs Rs.2,550 imported (the classic
     * "2" misread). Import must show the conflict with the amount difference.
     */
    @Test
    fun `conflicting rupee amounts on same id are explicit conflicts`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["upi-001"] =
            "id=upi-001;accountId=hdfc-main;amountMinor=255000;currencyCode=INR;" +
                "kind=EXPENSE;status=POSTED;merchant=Swiggy"
        val payload = service(source).buildExport("test")

        val target = FakeSink()
        target.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["upi-001"] =
            "id=upi-001;accountId=hdfc-main;amountMinor=25000;currencyCode=INR;" +
                "kind=EXPENSE;status=POSTED;merchant=Swiggy"

        val svc = service(target)
        svc.stageValidated(payload.body)
        val preview = svc.preview()
        val conflicts = preview.realConflicts()
        assertEquals(1, conflicts.size)
        assertTrue(conflicts[0].differenceSummary.contains("amountMinor"))

        // KEEP_LIVE keeps Rs.250 (user's confirmed value wins by default).
        val kept = svc.commit(MergePolicy.KEEP_LIVE)
        assertTrue(kept is ImportCommitResult.Committed)
        assertTrue(
            target.live[BackupDataset.TRANSACTIONS]!!["upi-001"]!!.contains("amountMinor=25000"),
        )
    }

    /** Fixture 1b: REPLACE is possible but ONLY via explicit user choice. */
    @Test
    fun `replace policy applies only after explicit choice`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["upi-001"] =
            "id=upi-001;amountMinor=255000;currencyCode=INR;kind=EXPENSE;status=POSTED"
        val payload = service(source).buildExport("test")

        val target = FakeSink()
        target.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["upi-001"] =
            "id=upi-001;amountMinor=25000;currencyCode=INR;kind=EXPENSE;status=POSTED"
        val svc = service(target)
        svc.stageValidated(payload.body)
        // REPLACE applies only with an explicit user selection of the row.
        svc.commit(MergePolicy.REPLACE_WITH_IMPORTED, setOf("upi-001"))
        assertTrue(
            target.live[BackupDataset.TRANSACTIONS]!!["upi-001"]!!.contains("amountMinor=255000"),
        )
    }

    /**
     * Fixture 2: Indian bank-statement CSV (dd/MM/yyyy, Dr/Cr column, one
     * malformed row). Valid rows import; the bad row surfaces as an error.
     */
    @Test
    fun `indian statement csv with dr-cr and malformed row handled honestly`() {
        val csv = buildString {
            append("Date,Amount,Type,Narration\n")
            append("01/07/2026,450.00,DR,\"SWIGGY INSTAMART GURGAON\"\n")   // valid debit
            append("05/07/2026,25000.00,CR,SALARY JUNE ACME\n")             // valid credit
            append("07/07/2026,,DR,UPI/rupees/unknown\n")                   // missing amount
            append("32/13/2026,100.00,DR,BROKEN DATE\n")                    // impossible date
        }
        val engine = CsvInteropEngine()
        val mapping = CsvInteropEngine.CsvColumnMapping(
            dateFormat = CsvInteropEngine.DateFormat.DD_MM_YYYY_SLASH,
            signConvention = CsvInteropEngine.SignConvention.TypeColumn("Type"),
            columns = mapOf(
                CsvInteropEngine.CsvColumn.DATE to 0,
                CsvInteropEngine.CsvColumn.AMOUNT to 1,
                CsvInteropEngine.CsvColumn.TYPE to 2,
                CsvInteropEngine.CsvColumn.MERCHANT to 3,
            ),
            hasHeaderRow = true,
            defaultCurrency = "INR",
            defaultAccountName = "HDFC ••••4821",
        )
        val results = engine.parse(csv, mapping)
        assertEquals(4, results.size)
        val okRows = results.filterIsInstance<CsvInteropEngine.RowResult.Ok>()
        assertEquals(2, okRows.size)
        assertEquals(45000L, okRows[0].draft.amountMinor)
        assertEquals(com.example.fintrack.domain.model.TxKind.EXPENSE, okRows[0].draft.kind)
        assertEquals(com.example.fintrack.domain.model.TxKind.INCOME, okRows[1].draft.kind)
        // Malformed rows report their reason instead of becoming fake txns.
        val errors = results.filterIsInstance<CsvInteropEngine.RowResult.Error>()
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.reason.contains("amount") })
        assertTrue(errors.any { it.reason.contains("date", ignoreCase = true) })
    }

    /**
     * Fixture 3: exported free-text carries a phone number and OTP from a
     * real Indian bank SMS pattern; redaction strips them, keeps VPA+amount.
     */
    @Test
    fun `export redaction on realistic bank sms text`() {
        val raw = "Rs.2,550 debited to swiggy@axisbank ref 482911223344, " +
            "info call 9876543210, OTP 553129"
        val r = ExportRedactionEngine.redactForExport(raw)
        assertFalse(r.text.contains("OTP 553129"))
        assertFalse(r.text.contains("553129"))
        assertFalse(r.text.contains("9876543210"))
        // Transaction-bearing facts survive.
        assertTrue(r.text.contains("swiggy@axisbank"))
        assertTrue(r.text.contains("Rs.2,550"))
        assertTrue(r.text.contains("482911223344"))
    }

    /**
     * Fixture 4: Hinglish UPI message with an unregistered personal VPA —
     * minimization keeps only the masked VPA shape; nothing else leaks.
     */
    @Test
    fun `hinglish upi message minimization keeps masked vpa only`() {
        val p = LlmMinimization.minimize(
            rawEvidenceText = "papa ko bheja 9876543210 pe ₹500, rameshkumar95@ypl se aaya tha",
            amountMinor = 50000L,
            currencyCode = "INR",
            directionHint = "CREDIT",
            rail = "UPI",
            occurredAtEpochMs = 1L,
        )
        val fragment = p.toPromptFragment()
        assertFalse(fragment.contains("9876543210"))
        assertFalse(fragment.contains("rameshkumar95@ypl"))
        assertEquals("r*******5@ypl", p.maskedVpa)
        assertTrue(fragment.contains("amount=50000"))
    }

    /** Fixture 5: tampered conflicting backup fails validation outright. */
    @Test
    fun `tampered conflicting export never reaches staging`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["x1"] =
            "id=x1;amountMinor=100;currencyCode=INR"
        val body = service(source).buildExport("test").body
        val tampered = body.replace("amountMinor=100", "amountMinor=999999")

        when (val v = service(FakeSink()).validate(tampered)) {
            is ImportValidation.Invalid -> assertTrue(v.reasons.isNotEmpty())
            is ImportValidation.Valid -> throw AssertionError("tamper must be caught")
        }
    }
}
