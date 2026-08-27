# Stage 10 Report — P21 AI Query Language/Context Retrieval · P22 AI Categorization/Explanation Assistant

## 1. Files / components created or changed

### Domain layer (pure logic, no storage) — `domain/ai/`
| File | Change |
|---|---|
| `AiQueryPlan.kt` | **New.** Module 170 absorbed: typed, validated intermediate plan (`Intent`, `Metric`, `Dimension`, `Filters`, `Sort`). Constructor-enforced invariants: limit bounded 1..500, `fromDay ≤ toDay`, AGGREGATE requires ≥1 metric, distinct groupBy dimensions. No SQL, no Room — the only thing an LLM/NL parser may produce. |
| `NaturalDateParser.kt` | **New.** Module 172 absorbed: relative phrases (today/yesterday/weeks/months/quarters/years/past-N-days/months/all-time/explicit ISO) → explicit epoch-day ranges using app ZoneId + caller-supplied "today". Refuses unknown phrases rather than guessing. |
| `AiQueryParser.kt` | **New.** Deterministic NL → validated-plan parser: intent detection, date extraction, merchant/category/account/rail filters via pluggable `AliasResolver` (deterministic aliasing first), amount thresholds (`over Rs.X` / `under Rs.Y`), `top N` limits, sort phrases. Also decodes LLM-proposed plan JSON through a strict whitelist — unsupported fields, unknown enums/metrics/dimensions and unresolvable ids are hard failures. Same input ⇒ same `planIdentity` (sha-256 of canonical form). |
| `AiQueryEngine.kt` | **New.** Deterministic executor over `LedgerTxnView` snapshots: filtering, stable sort with id tiebreak, bounded pagination with `hasMore`, aggregation by category/merchant/account/rail/day/month with gross/refunded/net split. Mirrors InsightsEngine classification exactly (transfers/CASH_MOVE never spend; refunds net against spend; uncategorized surfaces as null key). |
| `Coverage.kt` | **New.** Coverage indicators computed from the ledger snapshot itself: ingestion-incomplete flag, first/last observed day, UNKNOWN-kind count, uncategorized share, window-extends-before-history flag. Incomplete SMS history yields qualified summaries, never false certainty. |
| `AiSummaryGenerator.kt` | **New.** P21 #4: financial summaries built ONLY from retrieved structured facts. Every claim carries a citation to an existing aggregate key, transaction id or coverage fact. `isQualified` + `qualifications()` expose explicit caveats when data is partial. |
| `AiSafetyPolicy.kt` | **New.** Module 85 absorbed as central policy: refuses money movement, bank login/credentials, investment advice, live-bank-state claims, secret/SMS exposure, personal financial advice, out-of-scope requests. Deterministic; every refusal carries a user-facing explanation. |
| `AiExplanationAssistant.kt` | **New.** Modules 171 + 174 absorbed: transaction explanations from evidence/provenance only, EVIDENCE vs INFERENCE claim kinds, per-claim citations to existing ids, explicit `unknowns` list (no fabricated provenance/rails/categories), plus `validateNarrative()` guardrail that rejects narratives citing nonexistent transactions/evidence or containing amounts not present in retrieved facts. |
| `CategoryAliasResolver.kt` | **New.** Module 173 absorbed: deterministic category aliasing ladder (exact normalized name → confirmed alias → unique contains-match → Ambiguous candidates → Unresolved). AI-proposed mappings become ADVISORY rules only (`createdBy=SYSTEM`, `LLM_INTERPRETATION` source, priority after all user/heuristic rules, confidence floor 0.6) so they can never outrank user corrections in the existing `CategorizationPolicy` ladder. |

### Data layer (NO schema change — stays at version 10)
| File | Change |
|---|---|
| `data/repository/RoomAiQueryRepository.kt` | **New.** The single Room access point for the AI query feature. Executes validated plans over bounded read-only DAO queries (`FinanceDaoV8`) using the same entity→`LedgerTxnView` projection as Stage 9, then persists query METADATA (plan identity, filters summary, coverage summary, refused flag) into the existing v2 `audit_events` table. Raw sensitive prompts are never persisted. |

### Application layer
| File | Change |
|---|---|
| `application/ai/AiQueryViewModel.kt` | **New.** Pipeline state machine: safety gate → deterministic parse → optional range confirmation dialog → execution over retrieved facts → grounded cited summary. Failures are isolated (error state only); local finance functionality is untouched. |

### UI layer
| File | Change |
|---|---|
| `ui/ai/AiQueryScreen.kt` | **New.** Compose surface: query input, refusals shown verbatim, interpreted-range label, aggregate result rows, summary claims each prefixed with their citation tag (`[agg:key]` / `[txn:id]` / `[coverage]`), qualification warnings rendered distinctly. Confirmation `AlertDialog` before ambiguous ranges execute. |

### Wiring
| File | Change |
|---|---|
| `FinTrackApplication.kt` | Added `aiQueryRepository` (over existing `financeDaoV8()` + `financeDaoV4()`) and `aiQueryParser` with a category alias resolver. |
| `MainActivity.kt` | Constructs `AiQueryViewModel` and passes it into the shell. |
| `ui/navigation/Routes.kt` | Added `AI_QUERY = "ai-query"` route. |
| `ui/navigation/FinTrackAppShell.kt` | Added optional `aiQueryViewModel` parameter (backward-compatible) hosting `AiQueryScreen`. |

## 2. Room schema changes
**None.** Schema stays at **version 10**. The AI query audit reuses the existing v2 `audit_events` table (`entityType="AI_QUERY"`, `action=EXECUTED|REFUSED`, filters+coverage summary in `detailReason`) — no new tables, no migration, no migration test needed. Existing `MigrationTest` untouched.

## 3. Domain/business-rule changes
- **Structured retrieval, not free-form model access:** the model can only ever produce a typed plan that is whitelist-decoded; arbitrary SQL or direct Room access is structurally impossible.
- **Deterministic-first aliasing:** category/account/merchant language resolves through exact → alias → unique-match before anything advisory; ambiguity returns candidate lists, not guesses.
- **AI categorization stays subordinate:** advisory rules carry `LLM_INTERPRETATION` provenance and sort after every user/heuristic rule, preserving the P14 precedence ladder and the global rule that user corrections are authoritative.
- **Coverage honesty:** summaries lead with what the data covers; partial SMS history, pre-history windows, UNKNOWN kinds and >50% uncategorized share all force qualified language.
- **Citation integrity:** narrative validation rejects any claim citing a nonexistent transaction/evidence id or asserting an amount absent from retrieved facts.
- **Refusals are answers:** safety-policy hits return explicit refusal text instead of best-effort output.

Repairs to earlier-stage code: none required; all Stage 5–9 services were reused as-is.

## 4. Test results
```
gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL — 389 tests, 0 failures   (was 344 after Stage 9; +45 new)
```

Focused suites added this stage:
- `AiQueryPlanTest` (17 tests) — module 172 date parsing incl. quarter bounds and refusal of unknown phrases; NL→plan conversion for spend-by-category/date/account; plan-identity determinism; merchant/amount/top-N extraction; LLM plan-JSON decoding incl. rejection of unknown metrics, unsupported fields (e.g. injected `"sql"`), out-of-bounds limits; deterministic execution; refunds netting inside aggregates; transfers excluded from spend buckets; limit/hasMore pagination; deleted-row exclusion; amount-sort with id tiebreak.
- `AiAssistantSafetyTest` (19 tests) — all seven module-85 refusal classes; citation-only-existing-ids; unknowns-stay-unknown (no fabricated evidence/provenance/rail/category); refusal produces zero claims; narrative guardrail rejects hallucinated txn ids and unsupported amounts while passing grounded text; aliasing exact/ambiguous/unresolved; advisory-rule confidence floor; hallucinated-merchant fixture (summary cites only real aggregate keys, never invents "Zomato"); unsupported-balance question yields qualified summary; conflicting same-amount events keep separate citations.
- `AmbiguousIndianMessageStage10Test` (7 tests) — required realistic ambiguous/conflicting Indian fixtures: Hinglish query phrasing ("pichle mahine kitna kharcha hua") handled honestly; conflicting correction-SMS amounts (Rs.250 vs Rs.2,550 same VPA) both counted, neither hidden; unregistered personal VPA (`rameshkumar95@ypl`) stays uncategorized with 100% uncategorized-share flagged; partial-history month query forces the "earliest recorded transaction" caveat; orphan ATM withdrawal excluded from spend metrics but still listable; Hindi money-movement request cannot reach any write path; EMI debit without a linked plan lists without inventing EMI facts.

Regression suite: all 344 prior tests remain green, including `DependencyDirectionTest` (domain has no upward imports; UI imports neither Room nor repository implementations), `InsightsEngineTest`, `SearchAndDiagnosticsTest`, `CategorizationEngineTest`, and the full Stage 5–8 suites.

Instrumented tests compile but were not executed (no device/emulator in this environment — unchanged since Stage 8). No schema change occurred, so migration tests are unaffected.

## 5. Acceptance-gate results
| Sub-scope | Gate | Status |
|---|---|---|
| P21 #1 | NL parsing into safe internal representation; no arbitrary SQL or direct model access to Room | ✅ `AiQueryParser` + whitelist `decodePlanJson`; architecture test keeps UI/data boundaries intact |
| P21 #2 | Context retrieval selects minimum relevant structured facts | ✅ Plans fetch only the filtered ledger window via read-only DAO; account/category labels resolved on demand |
| P21 #3 | Historical listing tool with bounded pagination, explicit filters, deterministic ordering | ✅ `AiQueryEngine.execute` — LIMIT semantics via plan.limit, id-tiebreak sort tested for determinism |
| P21 #4 | Summaries from retrieved facts only, with coverage indicators | ✅ `AiSummaryGenerator` + `Coverage`; qualified-summary tests pass |
| P21 #5 | Typed validated intermediate plan (module 170) | ✅ `AiQueryPlan` constructor invariants + JSON decode tests |
| P21 #6 | NL date parsing with app ZoneId; interpreted range shown before risky execution | ✅ `NaturalDateParser` + `NeedsConfirmation` outcome + confirmation dialog in UI |
| P21 #7 | Persist query metadata without raw sensitive prompts; AI failure isolated | ✅ Audit rows in `audit_events` carry plan/filters/coverage summaries only; ViewModel isolates errors |
| P22 #1 | Categorization assistant proposes against canonical taxonomy; user confirmation authoritative | ✅ Advisory rules subordinate in the existing ladder; acceptance flows through normal user-correction path |
| P22 #2 | Explanation assistant from evidence/provenance without inventing facts | ✅ `AiExplanationAssistant.explain` with explicit unknowns |
| P22 #3 | Citation model — every material claim points to real ids (module 171) | ✅ Per-claim citations; `validateNarrative` rejects nonexistent citations |
| P22 #4 | NL category mapping deterministic-first, AI only for unresolved ambiguity (module 173) | ✅ `CategoryAliasResolver` ladder + advisory-rule floor |
| P22 #5 | Explanation guardrails: uncertainty, evidence vs inference, refuse unsupported (module 174) | ✅ ClaimKind split, unknowns list, refusal path tested |
| P22 #6 | Central safety/refusal policy (module 85) | ✅ `AiSafetyPolicy` covering all seven rule classes |
| P22 #7 | Adversarial fixtures | ✅ Hallucinated merchants, unsupported balances, conflicting evidence, unknown categories, out-of-scope requests — all covered in `AiAssistantSafetyTest` + `AmbiguousIndianMessageStage10Test` |

Stage-level integration checks: single source of truth maintained (AI reads the same `transactions`/`ledger_entries` projections as Insights/Search; no parallel representations); user corrections remain authoritative (advisory rules rank below USER rules by construction); offline behavior intact (the entire pipeline is local and deterministic — no network paths added); non-goals untouched (money movement, bank login, investments, live bank state all refused).

## 6. Unresolved risks / deliberate follow-up
1. **Optional LLM narrative rewrite is deliberately deferred.** The deterministic draft summary satisfies this stage's grounding/citation gates; wiring an actual provider rewrite through the existing `LlmProvider` interface (with `validateNarrative` as the post-filter) is a natural next increment and needs no structural change.
2. **Hinglish/Hindi query phrasing** currently falls back to `Unparsed` (honest refusal) rather than being understood. Extending the phrase tables is additive and test-covered.
3. **Merchant/account aliasing in the parser** uses text matching today; the application-level resolver currently resolves categories only. Wiring account-name→id resolution from `accounts` into the shell's `AliasResolver` is a small follow-up.
4. **Instrumented tests not executed** (no device/emulator in this environment, unchanged since Stage 8). No schema change occurred, so migration tests are unaffected.
5. **Audit rows are append-only per run** — repeated identical queries add rows rather than deduplicating (the unique-index idempotency used elsewhere was intentionally not applied here so query history is preserved).
