# STAGE13_REPORT.md — FinTrack 6-Feature Build

> Date: 2026-09-01 · Workspace: `c:\Users\Nitin\Desktop\job search`

## Summary

Implemented the Stage 13 feature build (6 features A–F) plus the DB v12
migration, with JVM tests green and instrumented tests compiling.

**JVM tests: 574, 0 failures** (baseline 545 → +29 new tests).
**Build: `:app:assembleDebug` succeeds; `:app:compileDebugAndroidTestKotlin` succeeds.**

---

## Features delivered

| # | Feature | Status | Key files |
|---|---------|--------|-----------|
| B | Transactions filters + sort | ✅ | `domain/service/TransactionFilter.kt` (new), `ui/transactions/TransactionsRoute.kt`, `TransactionFilterTest.kt` (11 tests) |
| E | Insights regression guards | ✅ | `InsightsEngineTest.kt` (+5 `regression_*` tests). Engine was already correct (uses `directionDebit`). |
| A | Payee tagging rules | ✅ | `EntitiesV12.kt` (payee_category_rules), `MigrationsV12.kt`, `FinanceDaoV10.kt`, `PayeeCategoryRuleService.kt` (PayeeIdentity/PayeeRuleResolver/PayeeEvidenceSink), `RoomPayeeEvidenceRepository.kt`, `PayeeCategoryRuleServiceTest.kt` (8 tests) |
| D | Raw LLM evidence storage | ✅ | `EntitiesV12.kt` (transaction_evidence), `llm_interpretations.rawLlmJson` (v12 ALTER), `EnrichmentOrchestrator.kt`, `LlmProcessingService.kt` persist raw JSON; never overwritten on cache-hit |
| C | LLM account type detection | ✅ | `LlmContract.kt` (PROMPT_VERSION→v3, `Interpretation.AccountType`), `LlmResponseDecoder.kt` (anti-hallucination via `knownAccountTypeHints`), `PromptBuilder.kt`, `boundsFrom()` hints, decoder tests (+4) |
| F | SMS review screen | ✅ | `LlmDao.kt` (review reads + re-run), `SmsReviewService.kt`, `SmsReviewViewModel.kt`, `ui/review/SmsReviewScreen.kt`, `Routes.SMS_REVIEW`, Settings link, MainActivity wiring |

## Schema change (v11 → v12)

- `SCHEMA_VERSION` 11 → **12**, `exportSchema=true` → `app/schemas/.../12.json` committed.
- `MIGRATION_11_12` in `MigrationsV12.kt`, registered in `Migrations.ALL`.
  Additive only:
  - `payee_category_rules` (unique `payeeIdentityHash`)
  - `transaction_evidence` (unique `transactionId`+`sourceMessageId`)
  - `llm_interpretations.rawLlmJson` nullable TEXT (ALTER)
- `MigrationTest.migrate11To12_*` instrumented test seeds v11 data, asserts new
  tables accept rows + idempotency (unique indices), and `rawLlmJson` is null
  for pre-v12 rows.

## Key design invariants honored

- **directionDebit** is the source of truth for income/expense — never `amountMinor < 0` (Features B, E).
- Raw SMS bodies stay in `raw_sms`; raw LLM JSON goes to `rawLlmJson` / `transaction_evidence` (Feature D).
- Idempotency via sha-256 identity + unique indices on all new tables (A, D).
- Manual overrides go through `TransactionWriteService` (postings/audit/idempotency) with `USER_CORRECTION` provenance (F).

## Test discipline

- JVM: `TransactionFilterTest` (11), `PayeeCategoryRuleServiceTest` (8), `LlmResponseDecoderTest` (+4), `InsightsEngineTest` (+5), prompt-version cache-key fix. **574 total, 0 failures.**
- Instrumented (compiled, device-run ready): `MigrationTest.migrate11To12_*`.

## Gotchas encountered & fixed

- Room `Map<K,V>` DAO return types are version-fragile → replaced with a
  standalone DTO (`JobStatusCountRow`).
- New abstract `LlmDao` methods required updating the two test fakes
  (`EnrichmentOrchestratorTest`, `LlmProcessingServiceTriageTest`).
- `statusColor` used `MaterialTheme` outside `@Composable` → added annotation.
- Auto-mirrored `FactCheck` icon referenced as `Icons.Filled` → `Icons.AutoMirrored.Filled`.

## Risks / follow-up

- Device-run of `connectedDebugAndroidTest` (incl. new migration test) not
  executed in this session; recommend `:app:connectedDebugAndroidTest` on the
  physical device (Samsung SM-S921E).
- Feature F manual-override path (`SmsReviewService.applyOverride`) is wired but
  the detail/override sheet UI is a follow-up; re-run + list are functional.
