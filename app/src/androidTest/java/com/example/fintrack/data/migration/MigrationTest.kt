package com.example.fintrack.data.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.migration.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests using Room's exported schemas (app/schemas).
 * Validates v1 -> v2 forward migration with data preservation and no loss.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FinTrackDatabaseV2::class.java,
    )

    private fun createAndPopulateV1() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """INSERT INTO messages (id, body, receivedAtEpochMs, sourceHash, sourceKind, sourceVersion, capturedAtEpochMs)
                   VALUES ('m1', 'SMS body', 1000, 'sha256-fake', 'SMS', 'sms-v1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO transactions (id, messageId, amountMinor, currencyCode, occurredAtEpochMs,
                   counterparty, state, sourceKind, sourceVersion, capturedAtEpochMs,
                   correctionSourceKind, correctionSourceVersion, correctionCapturedAtEpochMs)
                   VALUES ('t1', 'm1', 2500, 'INR', 1700000000000, 'Coffee', 'INTERPRETED',
                   'SMS', 'sms-v1', 1000, NULL, NULL, NULL)"""
            )
        }
    }

    @Test
    fun migrate1To2_preservesData_andAddsBlueprint() {
        createAndPopulateV1()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2).use { db ->
            // Existing row survived.
            db.query("SELECT id, amountMinor, currencyCode FROM transactions WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("t1", c.getString(0))
                assertEquals(2500L, c.getLong(1))
                assertEquals("INR", c.getString(2))
            }
            // Derived local-date backfill is deterministic.
            db.query("SELECT localDateEpochDay FROM transactions WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1700000000000L / 86400000L, c.getLong(0))
            }
            // New blueprint tables exist and accept rows.
            db.execSQL(
                """INSERT INTO accounts (id, name, normalizedName, currencyCode, accountType, createdAtEpochMs, lifecycle)
                   VALUES ('a1', 'HDFC', 'hdfc', 'INR', 'BANK', 0, 'ACTIVE')"""
            )
            db.query("SELECT COUNT(*) FROM accounts").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            // Unique dedupe index enforced.
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO transactions (id, messageId, accountId, categoryId, amountMinor, currencyCode,
                       occurredAtEpochMs, localDateEpochDay, counterparty, counterpartyNormalized, referenceId,
                       state, sourceKind, sourceVersion, sourceReason,
                       correctionSourceKind, correctionSourceVersion, correctionSourceReason, correctionCapturedAtEpochMs,
                       dedupeKey) VALUES
                       ('t2', NULL, '', NULL, 100, 'INR', 0, 0, NULL, NULL, NULL, 'RAW',
                        'SMS', 'v1', NULL, NULL, NULL, NULL, NULL, 'dup')"""
                )
                db.execSQL(
                    """INSERT INTO transactions (id, messageId, accountId, categoryId, amountMinor, currencyCode,
                       occurredAtEpochMs, localDateEpochDay, counterparty, counterpartyNormalized, referenceId,
                       state, sourceKind, sourceVersion, sourceReason,
                       correctionSourceKind, correctionSourceVersion, correctionSourceReason, correctionCapturedAtEpochMs,
                       dedupeKey) VALUES
                       ('t3', NULL, '', NULL, 100, 'INR', 0, 0, NULL, NULL, NULL, 'RAW',
                        'SMS', 'v1', NULL, NULL, NULL, NULL, NULL, 'dup')"""
                )
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("duplicate dedupeKey must be rejected", threw)
        }
    }

    @Test
    fun migrate3To4_addsSmsEvidenceTables_preservingData() {
        // Create a v3 database and populate it.
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """INSERT INTO accounts (id, name, normalizedName, currencyCode, accountType,
                   createdAtEpochMs, lifecycle, nickname, last4, institutionName)
                   VALUES ('a1', 'HDFC', 'hdfc', 'INR', 'BANK', 0, 'ACTIVE', NULL, NULL, NULL)"""
            )
            db.execSQL(
                """INSERT INTO messages (id, body, sender, receivedAtEpochMs, sourceHash,
                   sourceKind, sourceVersion, capturedAtEpochMs)
                   VALUES ('m1', 'legacy body', 'HDFC', 1000, 'hash1', 'SMS', 'sms-v1', 1000)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4).use { db ->
            // Existing rows survived.
            db.query("SELECT id, body FROM messages WHERE id = 'm1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("m1", c.getString(0))
                assertEquals("legacy body", c.getString(1))
            }
            db.query("SELECT id FROM accounts WHERE id = 'a1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("a1", c.getString(0))
            }
            // New SMS tables exist and accept rows.
            db.execSQL(
                """INSERT INTO raw_sms (id, providerId, sender, receivedAtEpochMs, body,
                   contentHash, sourceKind, sourceVersion, capturedAtEpochMs)
                   VALUES ('r1', 1, 'HDFC', 1000, 'INR 100 debited', 'h1', 'BACKFILL', 'sms-v1', 1000)"""
            )
            // Unique providerId index enforced.
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO raw_sms (id, providerId, sender, receivedAtEpochMs, body,
                       contentHash, sourceKind, sourceVersion, capturedAtEpochMs)
                       VALUES ('r2', 1, 'HDFC', 1000, 'INR 100 debited', 'h2', 'BACKFILL', 'sms-v1', 1000)"""
                )
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("duplicate providerId must be rejected", threw)

            // Cursor singleton accepts a row.
            db.execSQL(
                """INSERT INTO sms_backfill_cursor (id, lastProviderId, startedAtEpochMs,
                   lastUpdatedAtEpochMs, status, totalSeen, totalPersisted, totalDuplicate)
                   VALUES (1, 1, 1000, 1000, 'RUNNING', 1, 1, 0)"""
            )
            db.query("SELECT totalPersisted FROM sms_backfill_cursor WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0))
            }
        }
    }

    @Test
    fun migrate4To5_addsLlmEnrichmentTables_preservingData() {
        // Start with a v4 database seeded with prior-stage data.
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """INSERT INTO raw_sms (id, providerId, sender, receivedAtEpochMs, body,
                   contentHash, sourceKind, sourceVersion, capturedAtEpochMs)
                   VALUES ('r1', 7, 'HDFC', 1000, 'INR 250.00 debited', 'h7',
                   'BACKFILL', 'sms-v1', 1000)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5).use { db ->
            // Existing raw_sms survived unchanged.
            db.query("SELECT id, body FROM raw_sms WHERE id = 'r1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("r1", c.getString(0))
                assertEquals("INR 250.00 debited", c.getString(1))
            }
            // New LLM tables accept advisory-only rows.
            db.execSQL(
                """INSERT INTO llm_jobs (id, jobIdentity, sourceMessageId, senderHash, priority,
                   status, attempts, maxAttempts, nextRetryAtEpochMs,
                   promptVersion, schemaVersion, providerId,
                   createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('j1', 'idem-1', 'r1', 'hash-r1', 0,
                   'PENDING', 0, 4, 1000,
                   'enrich-prompt-v1', 'enrich-schema-v1', 'fake',
                   1000, 1000)"""
            )
            // Unique jobIdentity enforced.
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO llm_jobs (id, jobIdentity, sourceMessageId, senderHash, priority,
                       status, attempts, maxAttempts, nextRetryAtEpochMs,
                       promptVersion, schemaVersion, providerId,
                       createdAtEpochMs, updatedAtEpochMs)
                       VALUES ('j2', 'idem-1', 'r1', 'hash-r1', 0,
                       'PENDING', 0, 4, 1000,
                       'enrich-prompt-v1', 'enrich-schema-v1', 'fake',
                       1000, 1000)"""
                )
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("duplicate jobIdentity must be rejected", threw)

            // Interpretation + cache tables accept validated rows.
            db.execSQL(
                """INSERT INTO llm_interpretations (id, sourceMessageId, responseHash,
                   promptVersion, schemaVersion, providerId, modelId,
                   amountMinor, currencyCode, direction,
                   evidenceExplanationsJson, latencyMs, tokensPrompt, tokensCompletion,
                   fromCache, createdAtEpochMs)
                   VALUES ('i1', 'r1', 'rh-1', 'enrich-prompt-v1', 'enrich-schema-v1',
                   'fake', 'm1', 25000, 'INR', 'DEBIT', '{}', 100, 50, 20, 0, 1000)"""
            )
            db.execSQL(
                """INSERT INTO llm_response_cache (id, cacheKey, validatedResponseJson,
                   promptVersion, schemaVersion, providerId, modelId, createdAtEpochMs)
                   VALUES ('c1', 'ck-1', '{}', 'enrich-prompt-v1', 'enrich-schema-v1',
                   'fake', 'm1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO llm_usage_counters (id, bucketDayUtc, requests, cacheHits,
                   tokensPrompt, tokensCompletion, validationFailures, retries,
                   updatedAtEpochMs)
                   VALUES ('u1', 0, 1, 0, 50, 20, 0, 0, 1000)"""
            )
            // Single-row aggregate metric.
            db.execSQL(
                """INSERT INTO llm_metrics (id, metricName, value, updatedAtEpochMs)
                   VALUES ('queue_depth', 'queue_depth', 3, 1000)"""
            )
            db.query("SELECT value FROM llm_metrics WHERE metricName = 'queue_depth'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(3L, c.getLong(0))
            }
        }
    }

    /**
     * v8 -> v9 (Stage 7): additive tables for P14 categorization/merchants/
     * rules and P15 review/splits/tags. Existing v8 data must survive and
     * the new tables must accept rows with idempotency enforced.
     */
    @Test
    fun migrate8To9_addsCategorizationAndReviewTables_preservesData() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            // Seed a legacy category row (v2 shape, no v9 columns).
            db.execSQL(
                """INSERT INTO categories (id, name, normalizedName, parentId)
                   VALUES ('cat-old', 'Groceries', 'groceries', NULL)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 9, true, com.example.fintrack.data.db.migration.MIGRATION_8_9).use { db ->
            // Existing row survived with safe defaults on the new columns.
            db.query(
                "SELECT id, status, kind FROM categories WHERE id = 'cat-old'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("cat-old", c.getString(0))
                assertEquals("ACTIVE", c.getString(1))
                assertEquals("TAXONOMY", c.getString(2))
            }

            // New P14 tables accept rows.
            db.execSQL(
                """INSERT INTO merchants (id, displayName, normalizedName, accountId,
                   status, merchantIdentity, sourceKind, sourceVersion,
                   createdAtEpochMs, mergedIntoMerchantId)
                   VALUES ('m1', 'Swiggy', 'swiggy', NULL, 'ACTIVE', 'ident-1',
                   'SMS', 'merchants-v1', 1000, NULL)"""
            )
            // Unique merchantIdentity enforced.
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO merchants (id, displayName, normalizedName, accountId,
                       status, merchantIdentity, sourceKind, sourceVersion,
                       createdAtEpochMs, mergedIntoMerchantId)
                       VALUES ('m2', 'Swiggy2', 'swiggy2', NULL, 'ACTIVE', 'ident-1',
                       'SMS', 'merchants-v1', 1000, NULL)"""
                )
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("duplicate merchantIdentity must be rejected", threw)

            // New P15 tables accept rows.
            db.execSQL(
                """INSERT INTO review_items (id, transactionId, reason, priority, status,
                   createdAtEpochMs, resolvedAtEpochMs, explanation, sourceKind, sourceVersion)
                   VALUES ('r1', 't1', 'AMBIGUOUS', 5, 'OPEN', 1000, NULL,
                   'Two conflicting SMS.', 'parser', 'v1')"""
            )
            db.query("SELECT COUNT(*) FROM review_items WHERE status = 'OPEN'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }

    /**
     * v9 -> v10 (Stage 8): additive tables for budgets, recurring patterns,
     * cash reconciliations and ATM links. Existing v9 data must survive.
     */
    @Test
    fun migrate9To10_addsBudgetRecurringCashTables_preservesData() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                """INSERT INTO categories (id, name, normalizedName, parentId,
                   status, kind, sortOrder, createdAtEpochMs)
                   VALUES ('cat-old', 'Groceries', 'groceries', NULL,
                   'ACTIVE', 'TAXONOMY', 0, 0)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 10, true, com.example.fintrack.data.db.migration.MIGRATION_9_10).use { db ->
            // Existing v9 row survived untouched.
            db.query("SELECT id FROM categories WHERE id = 'cat-old'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("cat-old", c.getString(0))
            }

            // P16: budget rows accept inserts; scope identity is unique.
            db.execSQL(
                """INSERT INTO budgets (id, name, scopeKind, categoryId, accountId,
                   periodType, startDayOfMonth, targetAmountMinor, currencyCode,
                   rolloverEnabled, rolloverCapMinor, exclusionsJson, scopeIdentity,
                   status, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('b1', 'Food', 'CATEGORY', 'cat-old', NULL, 'MONTHLY', 1,
                   10000, 'INR', 1, NULL, '', 'scope-1', 'ACTIVE', 'USER',
                   'budget-v1', 1000)"""
            )
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO budgets (id, name, scopeKind, categoryId, accountId,
                       periodType, startDayOfMonth, targetAmountMinor, currencyCode,
                       rolloverEnabled, rolloverCapMinor, exclusionsJson, scopeIdentity,
                       status, sourceKind, sourceVersion, createdAtEpochMs)
                       VALUES ('b2', 'Food2', 'CATEGORY', 'cat-old', NULL, 'MONTHLY', 1,
                       10000, 'INR', 1, NULL, '', 'scope-1', 'ACTIVE', 'USER',
                       'budget-v1', 1000)"""
                )
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("duplicate scopeIdentity must be rejected", threw)

            // P16: one boundary row per (budget, period).
            db.execSQL(
                """INSERT INTO budget_periods (id, budgetId, periodStartEpochDay,
                   periodEndEpochDay, rolloverInMinor, boundaryAction, computedAtEpochMs)
                   VALUES ('bp1', 'b1', 20600, 20630, 500, 'ROLLOVER_APPLIED', 1000)"""
            )

            // P17: recurring pattern + observation.
            db.execSQL(
                """INSERT INTO recurring_patterns (id, patternIdentity, accountId,
                   counterpartyNormalized, merchant, categoryId, periodicity,
                   intervalDays, canonicalAmountMinor, minObservedAmountMinor,
                   maxObservedAmountMinor, currencyCode, confidence,
                   firstSeenEpochMs, lastSeenEpochMs, nextExpectedEpochMs, status,
                   isSubscription, decidedBy, sourceKind, sourceVersion,
                   createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('rp1', 'pid-1', 'a1', 'netflix', 'Netflix', NULL, 'MONTHLY',
                   30, 49900, 49900, 49900, 'INR', 0.8, 1000, 2000, 3000,
                   'DETECTED', 1, 'SYSTEM', 'SYSTEM', 'recurring-v1', 1000, 1000)"""
            )
            db.execSQL(
                """INSERT INTO recurring_observations (id, patternId, transactionId,
                   amountMinor, occurredAtEpochMs, observationIdentity, createdAtEpochMs)
                   VALUES ('ro1', 'rp1', 't1', 49900, 1000, 'oid-1', 1000)"""
            )

            // P18: cash reconciliation + ATM link.
            db.execSQL(
                """INSERT INTO cash_reconciliations (id, accountId, countedMinor,
                   ledgerDerivedMinor, differenceMinor, outcome,
                   adjustmentTransactionId, reason, reconciliationIdentity,
                   sourceKind, sourceVersion, atEpochMs)
                   VALUES ('cr1', 'cash1', 5000, 5100, -100, 'UNDER', NULL,
                   'rounding', 'rid-1', 'MANUAL_ENTRY', 'cash-reconcile-v1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO atm_cash_links (id, withdrawalTransactionId,
                   cashAccountId, amountMinor, currencyCode,
                   withdrawalOccurredAtEpochMs, matchedBy, candidateCount, ambiguous,
                   confirmedByUser, linkIdentity, sourceKind, sourceVersion,
                   createdAtEpochMs)
                   VALUES ('al1', 'w1', 'cash1', 20000, 'INR', 900, 'AMOUNT_DATE_ACCOUNT',
                   1, 0, 0, 'lid-1', 'SYSTEM', 'atm-link-v1', 1000)"""
            )
            db.query("SELECT COUNT(*) FROM atm_cash_links WHERE cashAccountId = 'cash1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }

    /**
     * v5 -> v6 (Stage 5, P09 + P10): additive tables for dedup artifacts
     * (evidence_links, dedupe_clusters, dedupe_cluster_members,
     * dedupe_decisions) and additive columns on transactions (kind,
     * subtype, status, merchant, description, rail, cardMask,
     * postingGroupId, deletedAtEpochMs, deletedReason). Existing v5 data
     * must survive.
     */
    @Test
    fun migrate5To6_addsDedupAndNormalizedTransactionTables_preservesData() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """INSERT INTO raw_sms (id, providerId, sender, receivedAtEpochMs, body,
                   contentHash, sourceKind, sourceVersion, capturedAtEpochMs)
                   VALUES ('r1', 10, 'HDFC', 1000, 'INR 250 debited', 'h10',
                   'BACKFILL', 'sms-v1', 1000)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, com.example.fintrack.data.db.migration.MIGRATION_5_6).use { db ->
            // Existing raw_sms survived unchanged.
            db.query("SELECT id, body FROM raw_sms WHERE id = 'r1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("r1", c.getString(0))
                assertEquals("INR 250 debited", c.getString(1))
            }

            // P09: evidence_links accept rows; unique (eventId, rawSmsId) enforced.
            db.execSQL(
                """INSERT INTO evidence_links (id, eventId, rawSmsId, linkIdentity,
                   linkKind, sourceKind, sourceVersion, sourceReason, createdAtEpochMs)
                   VALUES ('el1', 'e1', 'r1', 'li-1', 'RAW_PRIMARY', 'PROMOTED',
                   'parser-v1', NULL, 1000)"""
            )
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO evidence_links (id, eventId, rawSmsId, linkIdentity,
                       linkKind, sourceKind, sourceVersion, sourceReason, createdAtEpochMs)
                       VALUES ('el2', 'e1', 'r1', 'li-2', 'RAW_PRIMARY', 'PROMOTED',
                       'parser-v1', NULL, 1000)"""
                    )
            } catch (e: Exception) { threw = true }
            assertTrue("duplicate (eventId, rawSmsId) must be rejected", threw)

            // P09: dedupe_clusters + members accept rows.
            db.execSQL(
                """INSERT INTO dedupe_clusters (id, clusterIdentity, status, topScore,
                   verdict, reasonsJson, canonicalEventId, createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('dc1', 'ci-1', 'PROPOSED', 0.85, 'AUTO_MERGE', '{}', NULL, 1000, 1000)"""
            )
            db.execSQL(
                """INSERT INTO dedupe_cluster_members (id, clusterId, eventId, score,
                   signalBreakdownJson, createdAtEpochMs)
                   VALUES ('dcm1', 'dc1', 'e1', 0.85, '{"ref":1.0}', 1000)"""
            )

            // P09: dedupe_decisions accept rows.
            db.execSQL(
                """INSERT INTO dedupe_decisions (id, decisionEventId, clusterId, decisionKind,
                   actor, sourceKind, sourceVersion, reason, appliedAtEpochMs)
                   VALUES ('dd1', 'e1', 'dc1', 'MERGE', 'SYSTEM', 'AUTO', 'auto-v1', NULL, 1000)"""
            )

            // P10: new transactions columns accept values.
            db.execSQL(
                """INSERT INTO transactions (id, amountMinor, currencyCode, occurredAtEpochMs,
                   localDateEpochDay, accountId, state, sourceKind, sourceVersion, dedupeKey,
                   kind, subtype, status, merchant, description, rail, cardMask,
                   postingGroupId, deletedAtEpochMs, deletedReason)
                   VALUES ('t-v6', 25000, 'INR', 1700000000000, 19678, 'a1', 'INTERPRETED',
                   'SMS', 'sms-v1', 'dk-v6-1',
                   'EXPENSE', 'UPI', 'POSTED', 'Swiggy', 'lunch', 'UPI', '1234',
                   'pg-1', NULL, NULL)"""
            )
            db.query("SELECT kind, merchant FROM transactions WHERE id = 't-v6'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("EXPENSE", c.getString(0))
                assertEquals("Swiggy", c.getString(1))
            }
        }
    }

    /**
     * v6 -> v7 (Stage 5, P11): transferGroupId column on transactions,
     * refund_links table and transaction_links table. Existing v6 data
     * must survive.
     */
    @Test
    fun migrate6To7_addsTransferRefundLinkTables_preservesData() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            // Seed a v6 transaction with the v6 columns (no transferGroupId).
            db.execSQL(
                """INSERT INTO transactions (id, amountMinor, currencyCode, occurredAtEpochMs,
                   localDateEpochDay, accountId, state, sourceKind, sourceVersion, dedupeKey,
                   kind, status)
                   VALUES ('t1', 25000, 'INR', 1700000000000, 19678, 'a1', 'INTERPRETED',
                   'SMS', 'sms-v1', 'dk1', 'EXPENSE', 'POSTED')"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, com.example.fintrack.data.db.migration.MIGRATION_6_7).use { db ->
            // Existing v6 row survived; transferGroupId is null.
            db.query("SELECT id, transferGroupId FROM transactions WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("t1", c.getString(0))
                assertTrue("transferGroupId must be null for legacy rows", c.isNull(1))
            }

            // New transferGroupId column accepts values.
            db.execSQL(
                """INSERT INTO transactions (id, amountMinor, currencyCode, occurredAtEpochMs,
                   localDateEpochDay, accountId, state, sourceKind, sourceVersion, dedupeKey,
                   kind, status, transferGroupId)
                   VALUES ('t2', 10000, 'INR', 1700000000000, 19678, 'a1', 'INTERPRETED',
                   'SMS', 'sms-v1', 'dk2', 'TRANSFER', 'POSTED', 'tg-1')"""
            )
            db.query("SELECT transferGroupId FROM transactions WHERE id = 't2'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("tg-1", c.getString(0))
            }

            // refund_links accept rows; unique (refundedEventId, refundEventId) enforced.
            db.execSQL(
                """INSERT INTO refund_links (id, refundedEventId, refundEventId, kind,
                   amountMinor, currencyCode, sourceKind, sourceVersion, sourceReason,
                   refundIdentity, createdAtEpochMs)
                   VALUES ('rl1', 'e1', 'e2', 'FULL', 25000, 'INR', 'SYSTEM', 'parser-v1',
                   NULL, 'ri-1', 1000)"""
            )
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO refund_links (id, refundedEventId, refundEventId, kind,
                       amountMinor, currencyCode, sourceKind, sourceVersion, sourceReason,
                       refundIdentity, createdAtEpochMs)
                       VALUES ('rl2', 'e1', 'e2', 'FULL', 25000, 'INR', 'SYSTEM', 'parser-v1',
                       NULL, 'ri-2', 1000)"""
                    )
            } catch (e: Exception) { threw = true }
            assertTrue("duplicate (refundedEventId, refundEventId) must be rejected", threw)

            // transaction_links accept rows.
            db.execSQL(
                """INSERT INTO transaction_links (id, parentEventId, childEventId, role,
                   sourceKind, sourceVersion, sourceReason, linkIdentity, createdAtEpochMs)
                   VALUES ('tl1', 'e1', 'e2', 'FEE', 'SYSTEM', 'parser-v1', NULL, 'tli-1', 1000)"""
            )
            db.query("SELECT COUNT(*) FROM transaction_links WHERE role = 'FEE'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }

    /**
     * v7 -> v8 (Stage 6, P12 + P13): additive tables for credit cards,
     * card statements, card payments, reward events, adjustments,
     * EMI plans, installments and preclosures. Existing v7 data must survive.
     */
    @Test
    fun migrate7To8_addsCreditCardAndEmiTables_preservesData() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                """INSERT INTO transactions (id, amountMinor, currencyCode, occurredAtEpochMs,
                   localDateEpochDay, accountId, state, sourceKind, sourceVersion, dedupeKey,
                   kind, status)
                   VALUES ('t1', 25000, 'INR', 1700000000000, 19678, 'a1', 'INTERPRETED',
                   'SMS', 'sms-v1', 'dk1', 'EXPENSE', 'POSTED')"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, com.example.fintrack.data.db.migration.MIGRATION_7_8).use { db ->
            // Existing v7 row survived.
            db.query("SELECT id FROM transactions WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("t1", c.getString(0))
            }

            // P12: credit_cards accept rows; unique accountId enforced.
            db.execSQL(
                """INSERT INTO credit_cards (id, accountId, nickname, cardIdentity, issuer,
                   cardMask, currencyCode, lifecycle, createdAtEpochMs, creditLimitMinor,
                   statementDayOfMonth, statementCycleDays, dueDayOfMonth,
                   dueDaysAfterStatement, rewardPointsBalance)
                   VALUES ('cc1', 'a1', 'HDFC Visa', 'ci-1', 'HDFC', '4411', 'INR', 'ACTIVE',
                   1000, 5000000, 5, 30, 25, NULL, 1200)"""
            )
            var threw = false
            try {
                db.execSQL(
                    """INSERT INTO credit_cards (id, accountId, nickname, cardIdentity, issuer,
                       cardMask, currencyCode, lifecycle, createdAtEpochMs)
                       VALUES ('cc2', 'a1', 'HDFC Visa2', 'ci-2', 'HDFC', '4412', 'INR', 'ACTIVE', 1000)"""
                    )
            } catch (e: Exception) { threw = true }
            assertTrue("duplicate accountId on credit_cards must be rejected", threw)

            // P12: card_statements accept rows.
            db.execSQL(
                """INSERT INTO card_statements (id, cardId, accountId, periodStartEpochDay,
                   periodEndEpochDay, dueDateEpochDay, totalDueMinor, minDueMinor,
                   currencyCode, status, statementIdentity, capturedAtEpochMs,
                   sourceKind, sourceVersion)
                   VALUES ('cs1', 'cc1', 'a1', 20600, 20630, 20655, 250000, 25000,
                   'INR', 'OPEN', 'si-1', 1000, 'SMS', 'card-v1')"""
            )
            db.execSQL(
                """INSERT INTO card_statement_lines (id, statementId, cardId, lineIdentity,
                   occurredAtEpochMs, localDateEpochDay, amountMinor, currencyCode,
                   direction, status, merchant, sourceKind, sourceVersion)
                   VALUES ('csl1', 'cs1', 'cc1', 'li-1', 1700000000000, 19678,
                   25000, 'INR', 'DEBIT', 'POSTED', 'Swiggy order', 'SMS', 'card-v1')"""
            )

            // P12: card_payments, reward_events, adjustments.
            db.execSQL(
                """INSERT INTO card_payments (id, statementId, paidAtEpochMs, amountMinor,
                   currencyCode, paymentIdentity, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('cp1', 'cs1', 1700000000000, 250000, 'INR', 'pi-1', 'USER', 'payment-v1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO reward_events (id, cardId, earnedAtEpochMs, points,
                   description, rewardIdentity, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('re1', 'cc1', 1700000000000, 100, 'Spend reward', 'ri-1', 'SMS', 'reward-v1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO card_statement_adjustments (id, statementId, description,
                   amountMinor, currencyCode, adjustmentIdentity, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('adj1', 'cs1', 'Waived late fee', -50000, 'INR', 'ai-1', 'SMS', 'adj-v1', 1000)"""
            )

            // P13: EMI plan + installment + preclosure.
            db.execSQL(
                """INSERT INTO emi_plans (id, transactionId, cardId, principalMinor,
                   currencyCode, tenureMonths, interestRateAnnual, monthlyEmiMinor,
                   totalInterestMinor, processingFeeMinor, emiPlanIdentity, status,
                   sourceKind, sourceVersion, createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('ep1', 't1', 'cc1', 1200000, 'INR', 12, 14.0, 107500,
                   90000, 0, 'epi-1', 'ACTIVE', 'SMS', 'emi-v1', 1000, 1000)"""
            )
            db.execSQL(
                """INSERT INTO emi_installments (id, planId, installmentNumber,
                   dueDateEpochDay, amountMinor, currencyCode, status, paidAtEpochMs,
                   installmentIdentity, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('ei1', 'ep1', 1, 20655, 107500, 'INR', 'PAID', 1700000000000,
                   'eii-1', 'SMS', 'emi-v1', 1000)"""
            )
            db.execSQL(
                """INSERT INTO emi_preclosures (id, planId, closedAtEpochMs,
                   outstandingPrincipalMinor, waiverMinor, amountPaidMinor, currencyCode,
                   sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('epc1', 'ep1', 1700000000000, 600000, 5000, 595000, 'INR',
                   'USER', 'preclosure-v1', 1000)"""
            )
            db.query("SELECT COUNT(*) FROM emi_plans WHERE status = 'ACTIVE'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }

    /**
     * v10 -> v11 (Stage 11, P23 + P24): additive tables for import staging,
     * settings profiles, audit log and app lock state. Existing v10 data
     * must survive.
     */
    @Test
    fun migrate10To11_addsImportStagingSettingsAuditAppLockTables_preservesData() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """INSERT INTO budgets (id, name, scopeKind, categoryId, accountId,
                   periodType, startDayOfMonth, targetAmountMinor, currencyCode,
                   rolloverEnabled, rolloverCapMinor, exclusionsJson, scopeIdentity,
                   status, sourceKind, sourceVersion, createdAtEpochMs)
                   VALUES ('b1', 'Food', 'CATEGORY', NULL, NULL, 'MONTHLY', 1,
                   10000, 'INR', 1, NULL, '', 'scope-1', 'ACTIVE', 'USER',
                   'budget-v1', 1000)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 11, true, com.example.fintrack.data.db.migration.MIGRATION_10_11).use { db ->
            // Existing v10 row survived.
            db.query("SELECT id FROM budgets WHERE id = 'b1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("b1", c.getString(0))
            }

            // P23: import staging tables accept rows.
            db.execSQL(
                """INSERT INTO import_batches (id, createdAtEpochMs, status,
                   formatVersion, schemaVersion, totalStagedRows)
                   VALUES ('ib1', 1000, 'STAGED', 1, 11, 3)"""
            )
            db.execSQL(
                """INSERT INTO import_staging_rows (id, batchId, dataset, stableId,
                   canonicalRow, stagedAtEpochMs)
                   VALUES ('isr1', 'ib1', 'ACCOUNTS', 'a1', 'id=a1;name=HDFC', 1000)"""
            )
            db.query("SELECT COUNT(*) FROM import_staging_rows WHERE batchId = 'ib1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }

            // P24 / module 175: settings_profiles.
            db.execSQL(
                """INSERT INTO settings_profiles (id, name, version,
                   aiInterpretationEnabled, autoCategorizationEnabled,
                   exportIncludeRawEvidence, appLockEnabled, featureFlagsJson,
                   createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('sp1', 'Default', 1, 0, 1, 0, 0, '{}', 1000, 1000)"""
            )
            db.query("SELECT name FROM settings_profiles WHERE id = 'sp1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Default", c.getString(0))
            }

            // P24 #4: audit_log.
            db.execSQL(
                """INSERT INTO audit_log (id, actionClass, entityId, actor, detail,
                   atEpochMs, retention)
                   VALUES ('al1', 'EXPORT', NULL, 'USER', 'exported backup', 1000, 'DAYS_90')"""
            )
            db.query("SELECT COUNT(*) FROM audit_log WHERE actionClass = 'EXPORT'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }

            // P24 #5: app_lock_state singleton.
            db.execSQL(
                """INSERT INTO app_lock_state (id, enabled, lastUnlockedAtEpochMs,
                   state, updatedAtEpochMs)
                   VALUES (1, 1, 1000, 'UNLOCKED', 1000)"""
            )
            db.query("SELECT state FROM app_lock_state WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("UNLOCKED", c.getString(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
