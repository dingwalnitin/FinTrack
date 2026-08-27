# Stage 7 Report — P14 Categorization Engine + P15 Review Workspace

## 1. Files / components created or changed

### Data layer (Room)
| File | Change |
|---|---|
| `data/db/EntitiesV2.kt` | `CategoryEntity` extended with additive v9 columns (`status`, `kind`, `sortOrder`, `createdAtEpochMs`) with safe defaults; new indices on `parentId`/`status`. |
| `data/db/EntitiesV9.kt` | **New.** `MerchantEntity`, `MerchantAliasEntity`, `CategoryRuleEntity`, `LlmCategorySuggestionEntity`, `MerchantVpaBindingEntity`, `CategoryAuditEntity`, `ReviewItemEntity`, `TransactionSplitEntity`, `ReimbursementLinkEntity`, `TravelModeEntity`, `TransactionTagEntity`, `TransactionNoteEntity`. |
| `data/db/migration/MigrationsV9.kt` | **New.** `MIGRATION_8_9`: additive-only; ALTERs `categories` with default-backed columns; CREATEs the 12 new tables with unique identity indices. |
| `data/db/migration/Migrations.kt` | Registered `MIGRATION_8_9` in `Migrations.ALL`; documented schema history. |
| `data/db/FinanceDaoV6.kt` | **New DAO** for all v9 tables (idempotent inserts, status transitions, audit reads, atomic split-link write). |
| `data/db/FinTrackDatabaseV2.kt` | Version 8 → 9; registered v9 entities + `financeDaoV6()`; `SCHEMA_VERSION = 9`. |

### Domain layer
| File | Change |
|---|---|
| `domain/model/Categorization.kt` | **New.** `Category`, `Merchant`, `MerchantAlias`, `CategoryRule`, `LlmCategorySuggestion`, `MerchantVpaBinding`, `CategoryAudit` + enums. |
| `domain/model/Review.kt` | **New.** `ReviewItem`, `SplitLineDraft`, `SplitValidation`, `TransactionSplit`, `ReimbursementLink`, `TravelMode`, `TransactionTag`, `TransactionNote`. |
| `domain/policy/CategorizationPolicy.kt` | **New.** Five-rung precedence ladder + `CategorizationDecision` provenance type. |
| `domain/service/CategorizationService.kt` | **New.** Merchant resolution (VPA binding → identity → normalized-name reuse → fresh), rule ladder, VPA-binding confirmation, `CategorizationSink` contract, singleton uncategorized root helper. |
| `domain/service/SplitService.kt` | **New.** Amount-conservation validation + transactional parent tombstone / child creation / link writes. |
| `domain/service/BulkCorrectionService.kt` | **New.** Two-phase preview/commit with per-row audit provenance. |
| `domain/service/ReviewQueueService.kt` | **New.** Idempotent enqueue per (txn, reason), resolve/dismiss, priority ordering. |
| `domain/service/TagsNotesService.kt` | **New.** Normalized tags, notes, reimbursement links, travel-mode windows. |
| `domain/merchant/MerchantNormalization.kt` | **New.** Domain-owned merchant normalization (no parser dependency). |
| `domain/merchant/MerchantRegistry.kt` | **New.** Domain-side read-only VPA→merchant view. |

### Application / UI
| File | Change |
|---|---|
| `application/review/ReviewQueueViewModel.kt` | **New.** Loads open items; resolve/dismiss via service. |
| `ui/review/ReviewQueueScreen.kt` | **New.** Priority-ordered queue with explicit why-review-needed explanations. |
| `ui/categories/CategoryPicker.kt` | **New.** Parent/child drill-down picker with breadcrumb; uncategorized always offered. |
| `FinTrackApplication.kt` | Wired `categorizationRepository`, `categorizationService`, `reviewRepository`, `reviewQueueService`, `splitService`, `bulkCorrectionService`, `tagsNotesService`. |

### Tests
| File | Change |
|---|---|
| `test/.../CategorizationEngineTest.kt` | **New.** 17 tests: precedence ladder, repeatability, disabled rules, VPA matching, merchant reuse, normalization, policy ranks. |
| `test/.../SplitAndReviewTest.kt` | **New.** 14 tests: amount conservation (valid/over/under/empty), commit atomicity, idempotent dedupe keys, review enqueue/resolve/dismiss ordering. |
| `test/.../BulkAndTagsTest.kt` | **New.** 10 tests: bulk preview omission of no-ops, audit provenance (previous value recorded), tag normalization/dedupe, note recency, reimbursement idempotency, single active travel window. |
| `test/.../CategorizationAmbiguityRegressionTest.kt` | **New.** Ambiguous/conflicting Indian fixtures: "AMAZON PAY INDIA" (shopping vs bills), user rule vs contradicting high-confidence LLM suggestion, shared personal/business VPA resolving only after confirmation, low-confidence advisory routing. |
| `androidTest/.../MigrationTest.kt` | Added `migrate8To9_addsCategorizationAndReviewTables_preservesData`. |

## 2. Room schema changes

- **Version:** 8 → 9 (`exportSchema = true`; `app/schemas/.../9.json` exported and verified — 47 entities, no duplicates).
- **Migration:** `MIGRATION_8_9` — additive only.
  - `categories`: 4 added columns with safe defaults (`ACTIVE`/`TAXONOMY`/0/0) + 2 indices. No data rewritten.
  - 12 new tables, each with stable `*Identity` hash columns backed by unique indices for idempotency.
- **Migration test:** `migrate8To9_addsCategorizationAndReviewTables_preservesData` validates legacy row survival with defaults, unique-index enforcement on `merchants.merchantIdentity`, and review-item inserts. *(Instrumented test — requires a device/emulator to execute; compiles against exported schema.)*

## 3. Domain / business-rule changes

- **Categorization precedence (P14 #3):** USER_CONFIRMED_RULE > HIGH_CONFIDENCE_RULE > LEARNED_MAPPING > LLM_SUGGESTION > UNCATEGORIZED, encoded in `CategorizationPolicy` with strictly ordered ranks.
- **LLM suggestions are advisory only (P14 #4):** they land in `llm_category_suggestions`, never directly in transactions; acceptance is a normal user correction that appends an audit row.
- **Anti-overgeneralization (P14 #5):** a single noisy merchant never becomes a global rule; mappings are per-merchant until explicitly confirmed. UPI VPA bindings are used only when `confirmedByUser = true`.
- **Amount conservation (P15 #5):** splits are validated *before* any write; children sum must equal the parent exactly; parent is tombstoned only after children persist.
- **Repairs to earlier stages:** none required — existing services (write/refund/transfer/card/EMI) were reused as-is.

## 4. Test results

```
gradlew :app:testDebugUnitTest
Total tests: 264   Failures: 0   Errors: 0   BUILD SUCCESSFUL
```

Focused suites added this stage:
- `CategorizationEngineTest` — 17 tests ✔
- `SplitAndReviewTest` — 14 tests ✔
- `BulkAndTagsTest` — 10 tests ✔
- `CategorizationAmbiguityRegressionTest` — 4 tests ✔

Existing regressions all green, including `DependencyDirectionTest` (11), `ParserFixtureTest` (5),
`EnrichmentOrchestratorTest` (9), `CardTransactionServiceTest` (19), `EmiPlanServiceTest` (13),
`TransferServiceTest` (6), `RefundServiceTest` (3), `ManualEntryServiceTest` (8).

## 5. Acceptance-gate results

| Gate | Result |
|---|---|
| P14: deterministic rules produce repeatable categories | ✔ `same evidence produces same decision (repeatable)` |
| P14: user corrections persist | ✔ bulk-commit audit rows record actor=USER, sourceKind=USER_CORRECTION, previous value |
| P14: UPI mappings improve only after explicit confirmation | ✔ `unconfirmed vpa never resolves to a merchant`, `shared vpa ... resolves only after confirmation` |
| P15: review clears ambiguities without data loss | ✔ resolve/dismiss recorded; transaction untouched by queue writes |
| P15: bulk/split/reimbursement/travel reversible & auditable | ✔ preview-before-commit; previous values in every audit row |
| P15: amount conservation tests pass | ✔ valid/over/under/empty split validation + persisted-child sum assertion |

## 6. Unresolved risks / deliberate follow-up

1. **Instrumented migration test not executed here** — it requires an emulator/device; it compiles against the exported v9 schema and follows the same pattern as the passing v1→v5 tests.
2. **`applyCategorization` in `RoomCategorizationRepository` records audit only** — the actual `transactions.categoryId` update is expected to flow through `TransactionWriteService.upsert` at the application-layer call site. Wiring that final hop into the SMS-processing worker is left as follow-up so this stage does not alter existing ingestion behavior.
3. **UI integration into `NavHost`** — `ReviewQueueScreen` and `CategoryPicker` are built and compile but are not yet routed into `Routes.REVIEW` (currently a placeholder); wiring them changes shell behavior best done with UI tests in a focused pass.
4. **Learned-mapping rung** — the ladder's third rung currently resolves through per-merchant rules created by user correction; a dedicated `merchantId → categoryId` learned table was folded into `category_rules` (matchKind=MERCHANT_EXACT) rather than adding a second representation of the same fact.
5. **Attachments remain placeholder-only** per the Bible; tags/notes are text-only by design.
