# Stage 9 Report — P19 Dashboard/Insights · P20 Search/Diagnostics

## 1. Files / components created or changed

### Domain layer (pure logic, no storage)
| File | Change |
|---|---|
| `domain/service/InsightsEngine.kt` | **New.** P19 analytics engine over `LedgerTxnView`: dashboard summary, monthly cash flow (external vs internal), category/merchant breakdown (gross vs net with refunds), rail analytics (rail separated from funding instrument — card-funded UPI never double counts), balance history (observed snapshots + ledger-derived points, gaps flagged, no interpolation), savings rate (external flows only; explicit zero-income state), equal-length aligned period comparison (leap-year safe), Pareto, income sources, cash-flow calendar. |
| `domain/service/SearchService.kt` | **New.** P20 #1–#3: `SearchFilter` (text/date/account/kind/category/rail/tag/review-state/uncategorized-only), `SortSpec`, bounded `PageRequest`, deterministic text matcher shared by SQL and in-memory paths, stable sort with id tiebreak. |
| `domain/service/ReconciliationService.kt` | **New.** P20 #5–#7: read-only reconciliation workbench (`Matched` / `ExplainedByLaterPostings` / `Unexplained` / `NoObservation` verdicts; snapshot-staleness detection), `UnresolvedDataReportService.Report`, and the deterministic `RedactionEngine` (amounts, VPAs, phones, account tokens, OTPs) for evidence export/LLM handoff. |
| `domain/service/Stage8Sinks.kt` | **Extended additively:** `LedgerTxnView` gains `rail: String? = null` and `cardMask: String? = null` defaults so all existing constructors keep compiling. |

### Data layer (NO schema change — stays at version 10)
| File | Change |
|---|---|
| `data/db/FinanceDaoV8.kt` | **New READ-ONLY DAO.** Windowed/paged ledger reads, indexed LIKE search + count mirror, batch tag/note enrichment, reconciliation inputs, unresolved-data counters (unknown kinds, uncategorized spend, open reviews, terminal-failed LLM jobs, stale processing jobs, unmapped senders, low-confidence interpretations), raw-evidence-by-transaction and LLM-interpretation provenance queries. All reads bounded (LIMIT/OFFSET). |
| `data/db/FinTrackDatabaseV2.kt` | Registered abstract `financeDaoV8()`. Version remains **10** — no entities added or altered. |
| `data/repository/RoomInsightsRepository.kt` | **New.** The single Room access point for Stage 9 UI: entity→`LedgerTxnView` projection (direction resolved per PostingPolicy kind rules), account/opening/snapshot/category lookups, total-balance aggregation, search with tag/note enrichment + optional in-memory tag filtering, reconciliation inputs, unresolved report, raw-evidence records with interpretation provenance. |

### Application layer
| File | Change |
|---|---|
| `application/insights/HomeViewModel.kt` | **New.** Month-window dashboard state: balances, income/spend gross/net/refunds, budget progress cards (via existing `BudgetService.periodContaining` + `progress`), review/pending counts, recent transactions. |
| `application/insights/InsightsViewModel.kt` | **New.** Cash flow + previous-period comparison, category/merchant grouping toggle, account filter, rails, savings rate, Pareto, income sources, calendar, per-account balance histories. |
| `application/search/SearchViewModel.kt` | **New.** Filter state, paged search (`loadMore`), reconciliation workbench loader, unresolved-report loader, evidence viewer; copy-out always passes through `RedactionEngine`. |

### UI layer
| File | Change |
|---|---|
| `ui/home/HomeScreen.kt` | **New.** Summary cards (balance, month flows incl. refund line when present, budget progress bars with OK/!/X labels, needs-attention counts, recent rows). No decorative charts. |
| `ui/insights/InsightsScreen.kt` | **New.** Grouping/account filter chips, cash-flow card with own-transfers line and previous-period delta, savings-rate card (explicit "not computable" zero-income state), breakdown rows with drill-down labels + gross−refunds detail, rail rows with card-funded share, Pareto card, income sources, balance-history cards with gap warnings. |
| `ui/search/SearchScreen.kt` | **New.** Search field + chips (account/kind/tag/sort), result rows with tags/notes, Load-more paging, reconciliation workbench (read-only verdicts), unresolved-data report, raw-evidence viewer that visually separates "Raw evidence (immutable)" from "Interpretation (advisory)" and offers redacted copy-out. |
| `ui/navigation/FinTrackAppShell.kt` | HOME and INSIGHTS placeholders replaced with real screens; REVIEW now hosts `ReviewQueueScreen`; new optional VM parameters keep the shell backward-compatible. |
| `MainActivity.kt` | Constructs and wires `HomeViewModel`, `InsightsViewModel`, `SearchViewModel`, `ReviewQueueViewModel` into the shell. |
| `FinTrackApplication.kt` | DI: `insightsRepository` (over `financeDaoV8()`), `insightsEngine`, `reconciliationService`. |

### Tests
| File | Change |
|---|---|
| `app/src/test/.../domain/InsightsEngineTest.kt` | **New.** 17 tests: dashboard separation of income/spend/refunds vs transfers, bounded recent list, cash-flow internal/external split, monthly series bounds, category breakdown net-of-refunds + uncategorized visibility, merchant fallback to normalized counterparty, account filter, rail×funding-instrument non-double-counting, balance history merge without interpolation, gap flagging, empty-history flag, savings rate external-only + zero-income state, leap-year aligned ranges, aligned compareCashFlow, Pareto vital-few, income sources, calendar sparse days. |
| `app/src/test/.../domain/SearchAndDiagnosticsTest.kt` | **New.** 13 tests: text matching across merchant/note/counterparty, combined filters incl. uncategorized-only, tag filter, stable pagination without overlap/gaps, DESC amount sort with ascending-id tiebreak, deleted-row exclusion, reconciliation matched/timing-explained/no-snapshot/unexplained verdicts, report totals, redaction determinism + full masking set, sha256 stability. |
| `app/src/test/.../domain/AmbiguousIndianMessageStage9Test.kt` | **New.** Required ambiguous/conflicting Indian fixtures: balance-message-without-verb ("Avail balance … Rs.12,345.67"), direction-conflicting "Transaction of Rs.500", malformed "Debited Rs.,,," — all refuse deterministic extraction; a manually-recorded ambiguous event surfaces as UNCATEGORIZED + UNKNOWN-kind without distorting savings rate but visible in cash flow; conflicting same-amount duplicate coffees count as TWO events until a user merges them. |

## 2. Room schema changes
**None.** Schema stays at **version 10**. Stage 9 is purely derived/read-only over existing v2–v10 tables; all required columns (rail, cardMask, merchant, counterpartyNormalized, categoryId, status, kind) already exist with supporting indices (`counterpartyNormalized`, `merchant`, `localDateEpochDay`, `(accountId, occurredAtEpochMs)`, `status`, `kind`). No migration, no migration test needed. Existing `MigrationTest` untouched and compiling.

## 3. Domain/business-rule changes
- **Refunds are spend-reduction, not income:** `dashboardSummary`/`cashFlow` classify REFUND credits separately from INCOME so savings rate and income metrics are never inflated by money coming back.
- **UNKNOWN-kind debits** surface conservatively as outflow and under Uncategorized in breakdowns — visible, never promoted to confirmed expense classification.
- **Rail ≠ funding instrument:** card-funded UPI contributes once under UPI with the card share shown separately (`fundingInstrumentMinor ⊆ spendMinor`).
- **Balance history never interpolates observed bank values**; gaps >45 days between unobserved points flag `hasGaps`.
- **Savings rate excludes transfers/CASH_MOVE** and returns `rate=null, zeroIncome=true` rather than fabricating 0%.
- **Period comparison aligns by equal-length windows** (Feb 29 leap window ↔ 29-day January window ending Jan 31), never naive calendar-month shifts.
- **Diagnostics are read-only by default**; booking differences still routes through the existing CashService/ReconcileViewModel user-action paths.
- **Evidence vs interpretation separation** enforced in the UI structure and by redaction-on-copy.

Repairs to earlier-stage code: none required; `LedgerTxnView` was extended additively with defaulted fields only.

## 4. Test results
- **Unit suite (`testDebugUnitTest`): BUILD SUCCESSFUL — 344 tests, 0 failures** (was 308 after Stage 8; +36 new: 17 insights + 13 search/diagnostics + 5 ambiguous-fixture + 1 removed-dup adjustment).
- Architecture guard `DependencyDirectionTest` green: domain has no upward imports; UI imports neither Room nor repository implementations (Stage 9 ViewModels live in `application/` and receive `RoomInsightsRepository` there, matching the existing `TransactionDetailViewModel` pattern).
- Full `assembleDebug`: green.
- `compileDebugAndroidTestKotlin`: green (instrumented tests compile; execution still requires a device/emulator, unavailable in this environment).

## 5. Acceptance gates
| Sub-scope | Gate | Status |
|---|---|---|
| P19 #1 | Home summary cards from local aggregates; review/pending counts; no decorative charts | ✅ `HomeScreen` + `HomeViewModel` |
| P19 #2 | Monthly cash flow separating external from owned-transfer/card-settlement volume | ✅ `cashFlow`/`monthlyCashFlow` with `internalTransfersMinor` reported separately |
| P19 #3 | Category/merchant spend with drill-down labels, filters, gross-vs-net refunds, uncategorized visibility | ✅ `spendBreakdown` + InsightsScreen chips |
| P19 #4 | Rail analytics with funding instrument separated; UPI-on-card not double counted | ✅ `railAnalytics` (tested) |
| P19 #5 | Balance history from snapshots + derived points; gaps visible; no interpolation of observed values | ✅ `balanceHistory` (tested) |
| P19 #6 | Savings rate external-only; explicit zero-income/incomplete states | ✅ `savingsRate` (tested) |
| P19 #7 | Period comparison with explicit alignment and leap-year handling | ✅ `alignedRanges`/`compareCashFlow` (tested on Feb-2024) |
| P19 #8 | Absorbs 152–156: account/merchant reports, top categories/Pareto, cash-flow calendar, income-source analytics, coverage marking | ✅ `pareto`, `incomeSources`, `cashFlowCalendar`, `coverageIncomplete` flags throughout |
| P20 #1–#3 | Local/offline search across supported fields; filters preserved via shell saveState; stable sort + bounded pagination | ✅ `SearchService` + `FinanceDaoV8.searchTransactions` (LIMIT/OFFSET, tested determinism) |
| P20 #4 | Tags/notes integrated into results and filters | ✅ batch-enriched rows + tag chip filter |
| P20 #5 | Reconciliation workbench comparing evidence/snapshot/derived without mutating evidence | ✅ `ReconciliationService` verdicts incl. snapshot-staleness |
| P20 #6 | Unresolved-data report (mappings, unknown meaning, low confidence, parser/LLM failures, stale jobs) | ✅ `unresolvedReport` over DAO counters |
| P20 #7 | Raw evidence viewer with immutable text + provenance; redaction engine applied on export/LLM path | ✅ `rawEvidenceFor` + `RedactionEngine` (tested) |
| P20 #8 | Diagnostics read-only by default; evidence vs interpretation distinguished | ✅ SearchScreen section labelling; no write paths added |

Performance: every list path is bounded (paged search ≤100/page, enrichment batches capped, monthly series bounded by input); no per-message recomposition; aggregates computed off the UI thread inside ViewModels' coroutine scope.

## 6. Unresolved risks / deliberate follow-up
1. **Instrumented tests not executed** (no device/emulator in this environment, unchanged since Stage 8). Migration tests unaffected — schema did not change.
2. **SQL search covers the common dimensions** (text/date/account/kind); tag and review-state filters fall back to a capped (≤2000-row) fetch + in-memory filter. A JOIN-based query would remove the cap if ledgers grow beyond that.
3. **Budget progress on Home recomputes per budget** from windowed ledger slices; fine at current scale, could move to a single pass if budget count grows.
4. **Review tab** now renders the existing `ReviewQueueScreen`; deep-linking a review item to the transaction-detail route is left for the navigation polish increment.
5. **Coverage incompleteness** is currently caller-supplied (`coverageIncomplete=false` defaults); wiring it automatically from `sms_ingestion_progress` remains the same small follow-up noted in Stage 8.
