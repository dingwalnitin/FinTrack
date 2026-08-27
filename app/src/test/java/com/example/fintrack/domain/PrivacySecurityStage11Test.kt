package com.example.fintrack.domain

import com.example.fintrack.domain.model.AuditRetention
import com.example.fintrack.domain.model.SettingsProfile
import com.example.fintrack.domain.service.AppLockService
import com.example.fintrack.domain.service.AppLockSink
import com.example.fintrack.domain.service.AuditLogSink
import com.example.fintrack.domain.service.AuditService
import com.example.fintrack.domain.service.InMemorySecretVault
import com.example.fintrack.domain.service.LlmMinimization
import com.example.fintrack.domain.service.LlmMinimizationFixtures
import com.example.fintrack.domain.model.PrivacyModel
import com.example.fintrack.domain.service.SecretVault
import com.example.fintrack.domain.service.SettingsProfileService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11 P24 — privacy model, LLM minimization, audit boundaries,
 * app-lock lifecycle and settings-profile portability.
 */
class PrivacySecurityStage11Test {

    // ---- P24 #1: privacy posture ----

    @Test
    fun `privacy model forbids ads analytics cloud backup and data sale`() {
        assertTrue(PrivacyModel.NO_ADS)
        assertTrue(PrivacyModel.NO_ANALYTICS_SDK)
        assertTrue(PrivacyModel.NO_CLOUD_BACKUP)
        assertTrue(PrivacyModel.NO_DATA_SALE)
    }

    @Test
    fun `every network egress path is registered and llm default is off`() {
        assertEquals(1, PrivacyModel.NETWORK_EGRESS_PATHS.size)
        assertTrue(PrivacyModel.NETWORK_EGRESS_PATHS[0].contains("default OFF"))
    }

    // ---- P24 #3: LLM minimization golden fixtures ----

    @Test
    fun `llm minimization fixtures - no phone otp or full vpa leaves the device`() {
        for (f in LlmMinimizationFixtures.ALL) {
            val p = LlmMinimization.minimize(
                rawEvidenceText = f.rawText,
                amountMinor = 25000L,
                currencyCode = "INR",
                directionHint = "DEBIT",
                rail = "UPI",
                occurredAtEpochMs = 1_780_000_000_000L,
            )
            val fragment = p.toPromptFragment()
            for (forbidden in f.mustNotContain) {
                assertFalse(
                    "fixture '${f.name}': '$forbidden' must not appear in payload",
                    fragment.contains(forbidden),
                )
                assertFalse(
                    "fixture '${f.name}': '$forbidden' must not survive in any field",
                    p.maskedVpa?.contains(forbidden) == true ||
                        p.maskedAccountSuffix?.contains(forbidden) == true,
                )
            }
        }
    }

    @Test
    fun `minimized payload carries only whitelisted fields`() {
        val p = LlmMinimization.minimize(
            rawEvidenceText = "Rs.500 debited to merchant@okaxis, ref UTR99, call 9812345678",
            amountMinor = 50000L,
            currencyCode = "INR",
            directionHint = "DEBIT",
            rail = "UPI",
            occurredAtEpochMs = 1L,
        )
        val fragment = p.toPromptFragment()
        // No free text, no reference ids, no phones.
        assertFalse(fragment.contains("UTR"))
        assertFalse(fragment.contains("9812345678"))
        assertFalse(fragment.contains("merchant@okaxis")) // only masked form allowed
        assertTrue(p.maskedAccountSuffix == null || p.maskedAccountSuffix.length == 4)
    }

    @Test
    fun `masked vpa keeps transaction-bearing shape`() {
        val p = LlmMinimization.minimize(
            rawEvidenceText = "paid rameshkumar95@ypl",
            amountMinor = 100L, currencyCode = "INR", directionHint = null,
            rail = null, occurredAtEpochMs = null,
        )
        assertEquals("r*******5@ypl", p.maskedVpa)
    }

    // ---- P24 #4: audit log boundaries ----

    private class FakeAuditSink : AuditLogSink {
        val entries = mutableListOf<AuditLogSink.AuditEntry>()
        var pruned = 0

        override suspend fun append(
            actionClass: String, entityId: String?, actor: String,
            detail: String?, atEpochMs: Long, retention: AuditRetention,
        ) {
            entries += AuditLogSink.AuditEntry(
                id = entries.size.toString(), actionClass = actionClass,
                entityId = entityId, actor = actor, detail = detail,
                atEpochMs = atEpochMs, retention = retention.name,
            )
        }

        override suspend fun recent(limit: Int): List<AuditLogSink.AuditEntry> =
            entries.takeLast(limit)

        override suspend fun prune(nowEpochMs: Long): Int {
            val removed = entries.count { it.atEpochMs < nowEpochMs && it.retention != "FOREVER" }
            entries.removeAll { it.atEpochMs < nowEpochMs && it.retention != "FOREVER" }
            pruned += removed
            return removed
        }
    }

    @Test
    fun `audit service redacts secrets and phones from details`() = runTest {
        val sink = FakeAuditSink()
        val svc = AuditService(sink) { 1000L }
        svc.recordAsync(
            AuditService.ACT_TRANSACTION_WRITE,
            entityId = "txn-1",
            detail = "api_key=sk-supersecret phone 9876543210 amount changed",
        )
        val stored = sink.entries.single().detail!!
        assertFalse(stored.contains("sk-supersecret"))
        assertFalse(stored.contains("9876543210"))
        assertTrue(stored.contains("[REDACTED]"))
        assertTrue(stored.contains("[PHONE]"))
    }

    @Test
    fun `audit retention prunes expired rows but protects forever rows`() = runTest {
        val sink = FakeAuditSink()
        var now = 0L
        val svc = AuditService(sink) { now }
        svc.recordAsync(AuditService.ACT_EXPORT, detail = "old", retention = AuditRetention.DAYS_90)
        now = 91L * 24 * 3600 * 1000 + 1
        svc.recordAsync(AuditService.ACT_SETTINGS_CHANGE, detail = "keep", retention = AuditRetention.FOREVER)
        val removed = svc.pruneExpired()
        assertEquals(1, removed)
        assertEquals(1, sink.entries.size)
        assertEquals("keep", sink.entries.single().detail)
    }

    @Test
    fun `audit detail length is bounded`() = runTest {
        val sink = FakeAuditSink()
        val svc = AuditService(sink) { 0L }
        svc.recordAsync(AuditService.ACT_IMPORT_COMMIT, detail = "x".repeat(10_000))
        assertTrue(sink.entries.single().detail!!.length <= 200)
    }

    // ---- P24 #5: app lock lifecycle ----

    @Test
    fun `enable unlock disable lifecycle works offline`() = runTest {
        val vault = InMemorySecretVault()
        val states = mutableListOf<AppLockSink.AppLockState>()
        val sink = object : AppLockSink {
            var s: AppLockSink.AppLockState? = null
            override suspend fun state() = s
            override suspend fun setEnabled(enabled: Boolean, nowEpochMs: Long) {
                s = AppLockSink.AppLockState(enabled, if (enabled) "LOCKED" else "DISABLED", s?.lastUnlockedAtEpochMs ?: 0)
                states += s!!
            }
            override suspend fun markUnlocked(nowEpochMs: Long) {
                s = AppLockSink.AppLockState(true, "UNLOCKED", nowEpochMs); states += s!!
            }
            override suspend fun markLocked(nowEpochMs: Long) {
                s = AppLockSink.AppLockState(true, "LOCKED", s?.lastUnlockedAtEpochMs ?: 0); states += s!!
            }
        }
        var now = 1000L
        val svc = AppLockService(vault, sink, { now })

        svc.enable("1234".toCharArray())
        assertTrue(svc.shouldShowLock())
        assertEquals(AppLockService.UnlockResult.Denied("incorrect PIN"), svc.unlock("9999".toCharArray()))
        assertEquals(AppLockService.UnlockResult.Unlocked, svc.unlock("1234".toCharArray()))

        // Grace window: normal offline use not interrupted right after unlock.
        now += 5_000
        assertFalse(svc.shouldShowLock())
        now += 60_000
        assertTrue(svc.shouldShowLock())

        assertTrue(svc.disable("1234".toCharArray()).isSuccess)
        assertFalse(svc.shouldShowLock())
    }

    @Test
    fun `short pin rejected at enable time`() = runTest {
        val svc = AppLockService(InMemorySecretVault(), object : AppLockSink {
            override suspend fun state() = null
            override suspend fun setEnabled(enabled: Boolean, nowEpochMs: Long) {}
            override suspend fun markUnlocked(nowEpochMs: Long) {}
            override suspend fun markLocked(nowEpochMs: Long) {}
        }, { 0L })
        assertTrue(svc.enable("12".toCharArray()).isFailure)
    }

    @Test
    fun `vault verify fails closed when nothing stored`() = runTest {
        val vault: SecretVault = InMemorySecretVault()
        assertFalse(vault.hasLockSecret)
        assertFalse(vault.verifyLockSecret("1234".toByteArray()))
    }

    // ---- P24 #6 / module 175: settings profiles ----

    @Test
    fun `settings profile export-import round trip`() {
        val svc = SettingsProfileService(object : com.example.fintrack.domain.service.SettingsProfileSink {
            override suspend fun upsert(profile: SettingsProfile) = true
            override suspend fun all() = emptyList<SettingsProfile>()
            override suspend fun findByName(name: String) = null
            override suspend fun delete(id: String) {}
        }) { 0L }
        val profile = SettingsProfile(
            id = "p1", name = "travel", version = SettingsProfile.VERSION,
            aiInterpretationEnabled = true, autoCategorizationEnabled = false,
            exportIncludeRawEvidence = false, appLockEnabled = true,
            featureFlags = mapOf("insights_v2" to true),
            createdAtEpochMs = 0, updatedAtEpochMs = 0,
        )
        val payload = svc.exportProfile(profile)
        when (val r = svc.parseProfile(payload)) {
            is SettingsProfileService.ImportResult.Imported -> {
                assertEquals("travel", r.profile.name)
                assertTrue(r.profile.aiInterpretationEnabled)
                assertFalse(r.profile.autoCategorizationEnabled)
                assertTrue(r.profile.appLockEnabled)
                assertEquals(true, r.profile.featureFlags["insights_v2"])
            }
            is SettingsProfileService.ImportResult.Rejected ->
                throw AssertionError("expected import success: ${r.reason}")
        }
    }

    @Test
    fun `newer profile version is rejected not guessed`() {
        val svc = SettingsProfileService(object : com.example.fintrack.domain.service.SettingsProfileSink {
            override suspend fun upsert(profile: SettingsProfile) = true
            override suspend fun all() = emptyList<SettingsProfile>()
            override suspend fun findByName(name: String) = null
            override suspend fun delete(id: String) {}
        }) { 0L }
        val r = svc.parseProfile("FTPROFILE1\nname=x\nversion=999\n")
        assertTrue(r is SettingsProfileService.ImportResult.Rejected)
    }

    @Test
    fun `non-profile payload is rejected`() {
        val svc = SettingsProfileService(object : com.example.fintrack.domain.service.SettingsProfileSink {
            override suspend fun upsert(profile: SettingsProfile) = true
            override suspend fun all() = emptyList<SettingsProfile>()
            override suspend fun findByName(name: String) = null
            override suspend fun delete(id: String) {}
        }) { 0L }
        assertTrue(svc.parseProfile("hello world") is SettingsProfileService.ImportResult.Rejected)
    }
}
