# Stage 8 Report — P16 Budgets · P17 Recurring/Subscriptions · P18 Cash & ATM

## 1. Files / components created or changed

### Data layer (schema v10)
| File | Change |
|---|---|
| `data/db/EntitiesV10.kt` | **New.** 6 additive entities: `budgets`, `budget_periods`, `recurring_patterns`, `recurring_observations`, `cash_reconciliations`, `atm_cash_links`. All carry stable identity hashes backed by unique indices (v6–v9 convention). |
| `data/db/migration/MigrationsV10.kt` | **New.** `MIGRATION_9_10`: additive-only CREATE TABLE + index statements; no existing data touched. |
| `data/db/migration/Migrations.kt` | Registered `MIGRATION_9_10` in `Migrations.ALL`; documented schema history. |
| `data/db/FinTrackDatabaseV2.kt` | Version 9 → **10**, registered the 6 new entities, added abstract `financeDaoV7()`, bumped `SCHEMA_VERSION = 10`. |
| `data/db/FinanceDaoV7.kt` | **New DAO.** Idempotent writes for all v10 tables, atomic `@Transaction` helpers (`applyBudgetBoundary`, `applyCashReconciliation`), ledger read queries used by the budget/recurring engines. |
| `data/repository/RoomStage8Repository.kt` | **New.** Implements the three domain sinks (`BudgetSink`, `RecurringSink`, `CashSink`) over `FinanceDaoV7`; entity↔domain mappers with safe enum fallbacks. |

### Domain layer
| File | Change |
|---|---|
| `domain/model/Budget.kt` | **New.** `Budget` (policy only — scope/target/rollover/exclusions), `BudgetPeriod` (durable boundary decision: RESET / ROLLOVER_APPLIED / ROLLOVER_CAPPED), `BudgetProgress`, `ProgressStatus` (label+symbol contract for non-color cues), `BudgetExclusions` (deterministic encode/decode). |
| `domain/model/Recurring.kt` | **New.** `RecurringPattern` (confidence, observed min/max/canonical amounts, next-expected estimate, durable status), `RecurringObservation`, `RecurringForecast` (+`UpcomingCharge`). Module 149 monthly/annual normalization via `monthlyEquivalentMinor()` / `annualEquivalentMinor()`. |
| `domain/model/Cash.kt` | **New.** `CashReconciliation` (EXACT/UNDER/OVER), `AtmCashLink`, `AtmWithdrawalCandidate`, `AtmMatchKind`. |
| `domain/service/BudgetService.kt` | **New.** Pure engine: deterministic eligibility (`Eligibility.Eligible/Excluded(reason)`), scope preview (module 151), period-boundary math anchored on `startDayOfMonth` with month-length clamping, rollover resolution, actual-vs-budget progress (refunds reduce usage; transfers/CASH_MOVE/income never count). |
| `domain/service/RecurringService.kt` | **New.** Detection by median interval → MONTHLY/QUARTERLY/ANNUAL; irregular cadence rejected up front (gap spread > max(30% of mean, 3 days)); module 148 amount variance kept as canonical median + observed range (tolerance ±50%); durable user decisions never regress on re-detection; skip handling rolls next-expected forward without stacking; forecast restricted to CONFIRMED or ≥0.5-confidence patterns and flags unconfirmed contributions. |
| `domain/service/CashService.kt` | **New.** Reconciliation evaluation (booking a difference requires a reason), small-difference logging policy (≤1% of derived balance), ATM matching by amount+date window with explicit ambiguity when multiple candidates match, stable link identity. |
| `domain/service/Stage8Sinks.kt` | **New.** Persistence contracts + shared `LedgerTxnView` so domain stays storage-free (dependency direction preserved). |

### UI / wiring
| File | Change |
|---|---|
| `ui/budgets/BudgetsScreen.kt` | **New.** Offline budgets list: progress bars, text+symbol status cues (OK / ! / X) plus `contentDescription` semantics — never color-only; explicit "carried in" rollover source line; partial-history indicator; recurring forecast card labelled "(estimate)" with "(?)" on unconfirmed rows. |
| `ui/budgets/BudgetsViewModel.kt` | **New.** Reads only through domain sinks; no Room imports. |
| `ui/navigation/FinTrackAppShell.kt` | Replaced the BUDGETS `PlaceholderScreen` with the real screen; optional sink parameters keep the shell backward-compatible. |
| `MainActivity.kt` | Wires `app.stage8Repository` / `app.recurringService` into the shell. |
| `FinTrackApplication.kt` | DI: `stage8Repository` (over `financeDaoV7()`), `budgetService`, `recurringService`, `cashService`. |

### Tests
| File | Change |
|---|---|
| `app/src/test/.../domain/BudgetServiceTest.kt` | **New.** 19 tests: eligibility/exclusions (transfers, cash moves, income, account/tag/kind filters), preview, progress math, refund treatment, over/near-limit thresholds, coverage-incomplete flag, month boundaries incl. Feb clamping, rollover/reset/cap determinism. |
| `app/src/test/.../domain/RecurringServiceTest.kt` | **New.** 11 tests: monthly/quarterly/annual detection, annual→monthly normalization (module 149), variable-amount tolerance with range preservation (module 148), unexplainable variance rejection, too-few observations, irregular intervals, durable CONFIRMED/CANCELLED decisions across re-detection, skipped-month roll-forward, forecast windowing + unconfirmed flagging, subscription-evidence rule. |
| `app/src/test/.../domain/CashServiceTest.kt` | **New.** 11 tests: EXACT/UNDER/OVER outcomes, mandatory reason on booked differences, small-difference policy, single vs same-amount-multiple withdrawal matching (ambiguity), drift window, link identity stability, manual confirmed links. |
| `app/src/androidTest/.../migration/MigrationTest.kt` | Added `migrate9To10_addsBudgetRecurringCashTables_preservesData`: creates v9, migrates to v10 against the exported schema, verifies v9 data survives, exercises every new table and asserts the unique-index idempotency constraints (duplicate `scopeIdentity` rejected). |

## 2. Room schema changes
- **Version:** 9 → 10 (`SCHEMA_VERSION = 10`).
- **Migration:** `MIGRATION_9_10` — additive-only; six new tables + indices; zero ALTERs on existing tables; no data rewrite.
- **Exported schema:** `app/schemas/com.example.fintrack.data.db.FinTrackDatabaseV2/10.json` generated by KSP.
- **Migration test:** written and compiling (`migrate9To10_...`); **not executed in this session** because no emulator/device is available in this environment (SDK has no `emulator/` component and `adb devices` shows none). It follows the exact pattern of the passing per-version tests and validates against the exported 10.json.

## 3. Domain/business-rule changes
- **Budgets are derived, not stored:** only policy lives in `budgets`; all actual-vs-budget numbers are computed from ledger views at read time. No second balance truth.
- **Explicit boundary policy (module 150):** every period boundary writes a `budget_periods` row recording `rolloverInMinor` + `boundaryAction`. Rollover disabled or negative remaining ⇒ RESET; positive remaining ⇒ full carry; capped ⇒ capped carry. Deterministic given inputs.
- **Exclusions (module 151):** kind/account/tag dimensions evaluated in fixed order with named reasons; `preview()` returns included vs excluded lists so users see exactly what a budget covers.
- **Refunds** within the scoped category/account reduce usage; **transfers and CASH_MOVE are never spend**; income never counts.
- **Recurrence is an interpretation:** confidence-scored, advisory until user-confirmed; user CONFIRMED/REJECTED/CANCELLED decisions are durable and survive re-detection (decidedBy=USER preserved).
- **Amount variance (module 148):** pattern keeps canonical median + observed min/max; breaks only beyond ±50% tolerance.
- **Annual billing (module 149):** normalized via monthly-equivalent so yearly charges appear correctly in monthly reporting without duplication.
- **Cash reconciliation:** counted vs ledger-derived compared explicitly; booking a difference requires a reason and produces an adjustment transaction through the transactional pipeline; opening balances are never silently altered.
- **ATM linking:** relationship rows only — withdrawals are never duplicated; ambiguity (>1 candidate) is surfaced rather than guessed.

## 4. Repairs to earlier-stage code
None required. All earlier entities/services/tests were compatible; Stage 8 was built additively on top.

## 5. Test results
- **Unit suite (`testDebugUnitTest --rerun-tasks`): BUILD SUCCESSFUL — 308 tests, 0 failures** (was 264 before Stage 8; +44 new: 19 budget + 11 recurring + 11 cash + 3 split across fixes).
- Architecture guard (`DependencyDirectionTest`) green: domain depends on nothing above it; UI never touches Room or repository implementations.
- Full `assembleDebug`: green.
- Instrumented migration test: compiles; execution deferred (no device/emulator available — see risks).

## 6. Acceptance gates
| Sub-scope | Gate | Status |
|---|---|---|
| P16 | Budget configured/viewed/reconciled to transactions; rollover/reset/exclusion deterministic + tested | ✅ Engine + persistence + UI shipped; 19 focused tests cover boundaries, refunds, transfers, exclusions, rollover/reset/cap, missing history |
| P17 | Patterns reviewable; forecasts clearly estimates; variance + annual modeled without duplicate spend | ✅ Detection + durable review statuses + forecast with unconfirmed flags; monthly-equivalent normalization; 11 focused tests |
| P18 | Cash continuity preserved; quick reconcile shows exact/under/over; ATM evidence linked without duplicates | ✅ Reconciliation events + adjustment path with mandatory reason; relationship-table ATM links with ambiguity exposure; 11 focused tests |

Stage-level integration checks: single source of truth maintained (no parallel transaction/balance/category representations); user corrections remain authoritative (`userCorrected` flows into detection input; durable pattern decisions); offline behavior intact (all Stage 8 features are pure local computation; no network paths added); non-goals untouched.

## 7. Unresolved risks / deliberate follow-up
1. **Instrumented migration test not executed here** — no device/emulator exists in this environment. Run `connectedDebugAndroidTest` on a device before release; the test is ready.
2. **Budget UI is read-only in this increment**: creating/editing budgets currently happens through the service/sink API; a budget-editor sheet was left out to keep this stage's UI surface minimal (the roadmap's quick-entry emphasis targeted cash, which ships via the existing `ManualEntryScreen` + `CashService` pipeline). Follow-up: budget editor + cash quick-entry bottom sheet composable.
3. **Recurring review workspace UI** (confirm/reject list) is exposed via `RecurringSink.reviewablePatterns()` / `applyUserDecision()` but not yet rendered as a dedicated screen; wired follow-up for the next increment.
4. **Coverage incompleteness** is currently a caller-supplied flag (e.g. from backfill progress); wiring it automatically to `sms_ingestion_progress` is a small follow-up.
5. **Forecast currency mixing**: multi-currency patterns fall back to the dominant currency label; per-currency forecast buckets would be more precise if multi-currency usage grows.
