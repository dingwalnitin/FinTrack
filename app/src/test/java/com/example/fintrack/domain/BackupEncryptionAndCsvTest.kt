package com.example.fintrack.domain

import com.example.fintrack.domain.service.BackupCrypto
import com.example.fintrack.domain.service.CsvInteropEngine
import com.example.fintrack.domain.service.ExportRedactionEngine
import com.example.fintrack.domain.service.ExportRedactionGoldenFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11 P23 — encryption round-trip, CSV mapping determinism and the
 * module-160 golden redaction fixtures.
 */
class BackupEncryptionAndCsvTest {

    // ---- P23 #2: encrypted export ----

    @Test
    fun `encrypt then decrypt round-trips payload`() {
        val secret = "FTBACKUP1\nM|formatVersion=1\nE|abc"
        val enc = BackupCrypto.encrypt(secret, "correct horse".toCharArray())
        assertTrue(BackupCrypto.isEncryptedEnvelope(enc))
        val dec = BackupCrypto.decrypt(enc, "correct horse".toCharArray())
        assertEquals(secret, dec)
    }

    @Test
    fun `wrong password fails with safe message and no partial plaintext`() {
        val enc = BackupCrypto.encrypt("secret-payload", "right".toCharArray())
        try {
            BackupCrypto.decrypt(enc, "wrong".toCharArray())
            throw AssertionError("expected failure")
        } catch (e: BackupCrypto.BackupCryptoException) {
            assertTrue(e.message!!.contains("wrong password", ignoreCase = true))
            assertFalse(e.message!!.contains("secret-payload"))
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val enc = BackupCrypto.encrypt("secret-payload", "pw123456".toCharArray())
        val tampered = enc.dropLast(4) + "AAAA"
        try {
            BackupCrypto.decrypt(tampered, "pw123456".toCharArray())
            throw AssertionError("expected failure")
        } catch (e: BackupCrypto.BackupCryptoException) {
            // expected
        }
    }

    @Test
    fun `empty password is rejected at encrypt time`() {
        try {
            BackupCrypto.encrypt("x", CharArray(0))
            throw AssertionError("expected failure")
        } catch (e: BackupCrypto.BackupCryptoException) {
            // expected
        }
    }

    @Test
    fun `encryption is non-deterministic - same input different ciphertext`() {
        val a = BackupCrypto.encrypt("same", "pw123456".toCharArray())
        val b = BackupCrypto.encrypt("same", "pw123456".toCharArray())
        assertTrue(a != b)
    }

    // ---- P23 #7: golden redaction fixtures (module 160) ----

    @Test
    fun `golden redaction fixtures all pass`() {
        for (f in ExportRedactionGoldenFixtures.ALL) {
            val r = ExportRedactionEngine.redactForExport(f.input)
            assertEquals("fixture '${f.name}' output drifted", f.expectedOutput, r.text)
            assertTrue(
                "fixture '${f.name}' redaction count below expectation",
                r.redactions >= f.minRedactions,
            )
        }
    }

    @Test
    fun `required financial identifiers survive redaction`() {
        val kept = listOf(
            "XX1234",                    // masked card suffix
            "rameshkumar95@ypl",         // VPA (transaction-bearing)
            "UTR123456789",              // reference id
        )
        for (k in kept) {
            val r = ExportRedactionEngine.redactForExport("context $k end")
            assertTrue("'$k' must survive export redaction", r.text.contains(k))
        }
    }

    @Test
    fun `isExportSafe flags unmasked identifiers`() {
        assertFalse(ExportRedactionEngine.isExportSafe("call 9876543210"))
        assertFalse(ExportRedactionEngine.isExportSafe("A/c 1234567890"))
        assertTrue(ExportRedactionEngine.isExportSafe("paid using XX1234 to swiggy"))
        assertTrue(ExportRedactionEngine.isExportSafe(""))
    }

    // ---- P23 #6: CSV interop ----

    private val engine = CsvInteropEngine()

    @Test
    fun `signed-amount csv maps deterministically`() {
        val csv = "date,amount,merchant\n2026-06-12,-250.00,Swiggy\n2026-06-13,1200.50,Salary June"
        val mapping = CsvInteropEngine.CsvColumnMapping(
            dateFormat = CsvInteropEngine.DateFormat.ISO,
            signConvention = CsvInteropEngine.SignConvention.SignedAmount,
            columns = mapOf(
                CsvInteropEngine.CsvColumn.DATE to 0,
                CsvInteropEngine.CsvColumn.AMOUNT to 1,
                CsvInteropEngine.CsvColumn.MERCHANT to 2,
            ),
            hasHeaderRow = true,
            defaultCurrency = "INR",
            defaultAccountName = "HDFC",
        )
        val rows = engine.parse(csv, mapping)
        assertEquals(2, rows.size)
        val first = rows[0] as CsvInteropEngine.RowResult.Ok
        // amountMinor is always the absolute value (codebase invariant);
        // the sign is expressed through kind, never through a negative amount.
        assertEquals(25000L, first.draft.amountMinor)
        assertEquals(com.example.fintrack.domain.model.TxKind.EXPENSE, first.draft.kind)
        val second = rows[1] as CsvInteropEngine.RowResult.Ok
        assertEquals(com.example.fintrack.domain.model.TxKind.INCOME, second.draft.kind)
    }

    @Test
    fun `indian digit-grouping amounts parse`() {
        assertEquals(123456.78, engine.parseAmount("1,23,456.78")!!, 0.001)
        assertEquals(1250.0, engine.parseAmount("₹1,250")!!, 0.001)
        assertEquals(-500.0, engine.parseAmount("-500")!!, 0.001)
    }

    @Test
    fun `dd-mm-yyyy date format parses when declared`() {
        assertEquals(
            java.time.LocalDate.of(2026, 6, 12).toEpochDay(),
            engine.parseDate("12/06/2026", CsvInteropEngine.DateFormat.DD_MM_YYYY_SLASH),
        )
    }

    @Test
    fun `type-column convention distinguishes debit and credit`() {
        val csv = "date,amount,type\n12/06/2026,250.00,DEBIT\n13/06/2026,900.00,CREDIT"
        val mapping = CsvInteropEngine.CsvColumnMapping(
            dateFormat = CsvInteropEngine.DateFormat.DD_MM_YYYY_SLASH,
            signConvention = CsvInteropEngine.SignConvention.TypeColumn("type"),
            columns = mapOf(
                CsvInteropEngine.CsvColumn.DATE to 0,
                CsvInteropEngine.CsvColumn.AMOUNT to 1,
                CsvInteropEngine.CsvColumn.TYPE to 2,
            ),
            hasHeaderRow = true,
            defaultCurrency = "INR",
            defaultAccountName = "Cash",
        )
        val rows = engine.parse(csv, mapping)
        assertTrue(rows[0] is CsvInteropEngine.RowResult.Ok)
        assertEquals(
            com.example.fintrack.domain.model.TxKind.EXPENSE,
            (rows[0] as CsvInteropEngine.RowResult.Ok).draft.kind,
        )
        assertEquals(
            com.example.fintrack.domain.model.TxKind.INCOME,
            (rows[1] as CsvInteropEngine.RowResult.Ok).draft.kind,
        )
    }

    @Test
    fun `unparseable rows produce errors not fabricated transactions`() {
        val csv = "date,amount\nnot-a-date,100\n2026-06-12,abc"
        val mapping = CsvInteropEngine.CsvColumnMapping(
            dateFormat = CsvInteropEngine.DateFormat.ISO,
            signConvention = CsvInteropEngine.SignConvention.SignedAmount,
            columns = mapOf(
                CsvInteropEngine.CsvColumn.DATE to 0,
                CsvInteropEngine.CsvColumn.AMOUNT to 1,
            ),
            hasHeaderRow = true,
            defaultCurrency = "INR",
            defaultAccountName = "HDFC",
        )
        val rows = engine.parse(csv, mapping)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is CsvInteropEngine.RowResult.Error })
    }

    @Test
    fun `quoted csv cells with commas split correctly`() {
        val cells = engine.splitCsv("\"Swiggy, Instamart\",250.00,2026-06-12")
        assertEquals(listOf("Swiggy, Instamart", "250.00", "2026-06-12"), cells)
    }
}
