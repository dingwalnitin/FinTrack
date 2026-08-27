# P11 Implementation Prompt — Transfers, Refunds, Fees, Cash Movements, Manual Entry, Soft Delete

> Use this prompt in a follow-up session to complete Stage 5 P11 on top of the
> P09 + P10 surface already in the repo. Read `STAGE5_REPORT.md` and
> `docs/parser-authoring.md` first. Do not re-implement P09 / P10; reuse the
> `DedupeService`, `TransactionWriteService`, `TxKind` enum, `PostingPolicy`,
> and the v6 schema artefacts. Build on the existing v6 migration
> (`MIGRATION_5_6`); a v7 migration is allowed **only** for additive tables /
> columns.

## 0. GLOBAL NON-NEGOTIABLES (must read first)

Same as the P09/P10 prompt — these are inherited from the App Bible and
already enforced by `DependencyDirectionTest` and the schema migrations:

- App Bible is the source of truth; preserve its scope / non-goals.
- Do not add bank APIs / login, investment tracking, money-transfer
  execution, cloud backup/sync, push notifications, or SMS deletion.
- Kotlin/Compose/Room/Coroutines/Flow/WorkManager architecture. UI must not
  call Room directly (architectural test enforces this).
- External LLM / provider clients stay behind interfaces.
- SMS / raw source records are immutable evidence, not truth.
- Every money-changing write is transactional and idempotent.
- User corrections are first-class data and survive automated reprocessing.
- Core finance viewing, search, manual entry, budgeting, reports and export
  remain usable offline.
- Stable UUID / opaque identifiers, provenance / version metadata, lifecycle
  states, currency-aware money, Instant / local-date separation.
- Batch high-volume writes; never drive UI recomposition per source message.
- Run stage acceptance tests + relevant regressions before finishing.

## 1. P11 SUB-SCOPE — what to implement, in dependency order

P11 sits on top of P09 (dedup) and P10 (normalized transactions +
postings). The v6 schema already has the columns you need
(`kind`, `subtype`, `status`, `merchant`, `description`, `rail`, `cardMask`,
`postingGroupId`, `deletedAtEpochMs`, `deletedReason` on `transactions`;
`postingGroupId`, `memo` on `ledger_entries`).

The sub-scope has 7 numbered requirements. Implement them in the order below
— each one is required for the next.

### 1.1 Two-sided owned-account transfers (P11 #1)

Goal: a transfer is **two simultaneous balance movements** that are excluded
from income/expense metrics and have an explicit Transfer status.

**Where the v6 surface is ready**: `TxKind.TRANSFER`, `PostingPolicy`
(singlePosting generates one side; you need a sibling for two-sided
generation), and `TransferEntity` (v2) — extend it for v6.

**Required work**:

- Introduce `TransferService` in `domain/service/` with a single
  `linkTransfer(fromAccountId, toAccountId, amountMinor, currencyCode,
  occurredAt, rail, referenceId, provenance)` method.
- The service writes:
  - **two `TransactionEntity` rows** with `kind=TRANSFER` and
    `subtype=BANK_TRANSFER` / `CASH_MOVE` / etc. (whatever the rail maps to).
    One is on the source account, one on the destination. The destination
    transaction has `amountMinor` negative-as-flow-in (i.e. CREDIT semantics
    captured by `direction=CREDIT`).
  - **two `LedgerEntryEntity` rows** (one per side) sharing the same
    `postingGroupId`.
  - a **single `TransferEntity`** linking the two `fromEntryId` / `toEntryId`
    with `kind=TRANSFER` and provenance.
- All writes happen inside one Room `@Transaction` method
  (`FinanceDaoV3.recordTransfer` or a new
  `FinanceDaoV3.linkTransferAndReplacePostings`).
- The two transactions must share a `transferGroupId` (you may add a
  nullable `transferGroupId` column on `transactions` via a v7 additive
  migration) so listing / detail can show both sides.
- The candidate-matching helper `TransferCandidateMatcher` scores
  pairs of (DEBIT, CREDIT) events by (amount, account, currency, rail,
  ref, ±N minute window) and surfaces proposals via Review. It is allowed
  to use the same engine-style scoring as `DedupeEngine`; the thresholds
  belong in a new `TransferEngine` object so they are auditable.
- Income/expense queries must filter out `kind=TRANSFER`.

**Acceptance**: a transfer creates two transactions, two postings, one
`TransferEntity` row, all inside one @Transaction; neither side is counted
in income/expense aggregates; both sides can be edited (P11 #3) and the
transfer link survives.

### 1.2 Refunds (P11 #2)

Goal: refunds are events that **reference** the original expense; they
never mutate it.

**Required work**:

- `TxKind.REFUND` already exists. Add `RefundLinkEntity` (a v7 additive
  table) with `(refundedEventId, refundEventId, kind FULL|PARTIAL,
  sourceKind, sourceVersion, createdAtEpochMs)` and a unique index on
  `(refundedEventId, refundEventId)`.
- A `RefundService.recordRefund(originalEventId, refundTxn, memos)`
  method that:
  - inserts the refund transaction via `TransactionWriteService.upsert`
  - inserts the `RefundLinkEntity` linking it to the original
  - if the refund is partial, records the partial amount on the link
- `RefundsForEvent` query returns the link + linked event for the detail
  screen.
- The detail screen (P10 #6) must surface refunds under a new "Linked
  refunds" section.
- Verify that original expense postings are unchanged (no compensating
  postings; the refund is a separate event).

**Acceptance**: a refund transaction is a new event with its own postings,
linked to the original via `RefundLinkEntity`; the original postings are
unchanged; `kind=REFUND` is excluded from net-expense metrics if you
choose to count it separately, but the sum of (expense + refund) equals
the net economic impact.

### 1.3 Fees and charges (P11 #2 cont.)

Goal: fees are **explicit events** with their own `kind=FEE`.

**Required work**:

- The parser may detect SMS-embedded fees ("IMPS charge Rs.5"). Add a
  rail-adapter rule (see `docs/parser-authoring.md`) that emits a
  separate `ParseCandidate` with `kind=FEE` and `subtype=IMPS_FEE` /
  `CARD_FEE` / etc.
- A fee is persisted as its own `TransactionV6` (single posting on the
  user account) with `kind=FEE`, `merchant=Bank`, `rail=<parent rail>`.
- The UI surfaces fees alongside the parent transaction in the detail
  screen via a "Linked fees" section (a v7 table
  `transaction_links(parentEventId, childEventId, role, createdAtEpochMs)`
  is allowed for both refunds and fees — share the table).

**Acceptance**: every detected fee is its own event; never collapsed into
the parent transaction's amount.

### 1.4 Cash withdrawal / deposit (P11 #2 cont.)

Goal: cash movements are **atomic events** that do not double-count the
transfer side.

**Required work**:

- `TxKind.CASH_MOVE` already exists with `subtype=CASH_OUT` / `CASH_IN`.
- For an ATM cash withdrawal:
  - one `TransactionV6` with `kind=CASH_MOVE, subtype=CASH_OUT, accountId=<bank account>, amountMinor=<withdrawn>`
  - a second `TransactionV6` with `kind=CASH_MOVE, subtype=CASH_IN, accountId=<cash account>, amountMinor=<withdrawn>`, **no double-counted** posting
  - both transactions share a `transferGroupId` and a
    `TransferEntity(kind=TRANSFER, …)` row (cash withdrawal is a transfer
    from the bank account to the cash wallet).
- For a cash deposit: the same with directions reversed.

**Acceptance**: cash movements show up in the cash wallet balance and the
bank account balance, and the sum of the two transactions equals the net
movement; they are excluded from income/expense metrics.

### 1.5 Manual transaction quick entry / edit (P11 #3)

Goal: the user can add or edit a transaction with Save / Cancel,
validation, user provenance, and offline-first behavior.

**Required work**:

- New `domain/service/ManualEntryService` with
  `createManual(txn: TransactionV6)`, `editManual(txnId, updates: …)`,
  and `deleteManual(txnId)`.
- Manual entries must have:
  - `messageId = null`
  - `provenance.sourceKind = SourceKind.MANUAL_ENTRY`
  - `provenance.sourceVersion = "manual-v1"`
  - `correctionOrigin = null` (this IS the authoritative interpretation)
- Validation rules (return `Result` on failure, do not throw):
  - `amountMinor > 0`
  - `currencyCode` is ISO-4217 (3 letters)
  - `occurredAt <= now() + 1h` (no future events except for slight clock skew)
  - `accountId` resolves to an existing ACTIVE account
  - `kind` is one of the enum values
- Edit must:
  - regenerate the posting group via `TransactionWriteService.upsert`
  - set `correctionSourceKind = USER_CORRECTION` if the user changed any
    non-display field (amount, account, currency, kind, date)
  - leave `correctionSource*` null if the user only edited display-only
    fields (description, merchant)
- Save / Cancel: keep an in-memory draft in the ViewModel; do not write to
  Room on every keystroke.
- Manual edits override model suggestions for edited fields
  (ProvenancePolicy: USER_CONFIRMED outranks MODEL_SUGGESTION).

**Acceptance**: manual entry round-trips; validation errors return
`Result.failure(...)` with a human-readable message; edits preserve the
postings invariant.

### 1.6 Transaction soft deletion / tombstones (P11 #4)

Goal: delete is reversible-looking — history and audit survive, derived
data regenerates safely, backfill never silently resurrects a deleted event.

**Where the v6 surface is ready**: `deletedAtEpochMs`, `deletedReason`,
`TxStatus.DELETED`, `PostingPolicy.isActive`, `TransactionWriteService.softDelete`.

**Required work**:

- Wire `softDelete` to the application: provide a Compose confirmation
  dialog and a ViewModel method.
- Add `restoreTransaction` that flips `status` back to `POSTED` and
  clears `deletedAtEpochMs` / `deletedReason`. Allowed only by a USER
  decision (audit event).
- All read paths (transactions list, balance computation, income/expense
  aggregations, transfer linking, dedup engine, LLM re-processing) must
  filter out `status = DELETED` events by default.
- The parser / LLM re-processing pipeline must skip messages whose only
  evidence is a soft-deleted event's primary link (or, if a new SMS
  arrives, the soft-deleted event is not resurrected; a new event is
  created instead — explicit policy).
- Audit events: every `softDelete` and `restoreTransaction` writes an
  `AuditEventEntity(action=DELETED|RESTORED, actor=USER, …)`.

**Acceptance**: deleted transactions are invisible to aggregations; their
postings and evidence links are preserved for audit; the user can restore
or permanently delete; backfill never resurrects a deleted event from a
fresh SMS.

### 1.7 Transaction timeline / detail with full provenance (P11 #5)

Goal: the existing P10 detail screen extends with:

- source SMS / evidence links (P09 surface)
- confidence / status badges
- edit history (audit events)
- relevant postings (advanced section — already in P10)
- linked transfers / refunds / fees (P11 surface)

**Required work**:

- Extend `TransactionDetailViewModel` to load:
  - `evidenceLinksForEvent` (from `FinanceDaoV3`)
  - `decisionsForEvent` (from `FinanceDaoV3`)
  - `auditEventsForEntity` (from `FinanceDaoV2` — already exists)
  - `refundLinks` / `fees` / `transfer` siblings
- Extend `TransactionDetailScreen` with three new sections:
  "Evidence (raw SMS)", "Audit history", "Linked events (transfers /
  refunds / fees)".
- Each section must be honest about unknowns: a missing audit event is
  empty, not a fabricated "no history".

**Acceptance**: every detail view shows its evidence, history, and
linked events; unknown fields stay unknown.

### 1.8 Absorb module 142 — explicit transaction statuses (P11 #6)

The P10 surface already includes `TxStatus { PENDING, POSTED,
REVIEW_REQUIRED, FAILED, DELETED }` and the legacy `state` column. Make
sure the parser / LLM orchestrator writes the right status on each
transition:

- new event from parser: `PENDING` → `POSTED` once the posting group is
  written, or `REVIEW_REQUIRED` if signals say so, or `FAILED` on
  terminal error.
- USER-corrected event: stays `POSTED` (correction is not a status
  change).
- Soft-deleted: `DELETED`.
- LLM re-processing never resurrects a `DELETED` event.

### 1.9 Protect against contradicting SMS (P11 #7)

Goal: a later SMS that contradicts an earlier interpretation stores new
evidence and reconciles rather than mutating immutable evidence.

**Required work**:

- When a new raw_sms arrives for an event whose messageId matches and
  whose content materially differs, do not update the existing
  `TransactionEntity`; instead:
  - insert a new `EvidenceLinkEntity` (RAW_SECONDARY) — multiple SMS may
    support one event.
  - if the contradiction is significant (e.g. different amount, different
    ref id), set `status = REVIEW_REQUIRED` and write an `AuditEventEntity`
    explaining "new SMS contradicts prior interpretation".
- The P10 detail screen must surface the new evidence and the review
  cue.

**Acceptance**: the original transaction is never mutated by a new SMS;
new evidence is added; the user is notified via REVIEW_REQUIRED when
the contradiction is significant.

## 2. Files to create / change (checklist)

This is a non-exhaustive list — extend as needed:

### New

- `domain/service/TransferService.kt`
- `domain/service/TransferCandidateMatcher.kt`
- `domain/service/RefundService.kt`
- `domain/service/ManualEntryService.kt`
- `domain/policy/TransferEngine.kt` (scoring + thresholds)
- `data/db/EntitiesV7.kt` — `RefundLinkEntity`, optional
  `TransactionLinkEntity` (parent/child)
- `data/db/FinanceDaoV4.kt` — new queries
- `data/repository/RoomTransferRepository.kt`,
  `RoomRefundRepository.kt`, `RoomManualEntryRepository.kt`
- `application/transactions/ManualEntryViewModel.kt`,
  `application/transactions/TransferCandidatesViewModel.kt`
- `ui/transactions/ManualEntryScreen.kt`,
  `ui/transactions/TransferCandidatesScreen.kt`
- `app/schemas/com.example.fintrack.data.db.FinTrackDatabaseV2/7.json`
  (auto-generated by KSP)

### Changed

- `data/db/FinTrackDatabaseV2.kt` — add v7 entities; version 6 → 7
- `data/db/migration/MigrationsV7.kt` + register in `Migrations.ALL`
- `data/db/EntitiesV2.kt` — if you add `transferGroupId` on
  `TransactionEntity`
- `domain/repository/FinanceRepositoryV2.kt` — add transfer / refund /
  manual-entry methods
- `data/repository/RoomFinanceRepositoryV2.kt` — implement them
- `ui/transactions/TransactionDetailScreen.kt` — extend with evidence,
  audit, linked-events sections
- `ui/navigation/Routes.kt` — add `MANUAL_ENTRY`, `TRANSFER_REVIEW`,
  `TRANSACTION_DETAIL` is already present
- `FinTrackApplication.kt` — wire new services

## 3. Tests required

Unit tests (mirroring `DedupeEngineTest` / `PostingPolicyTest` /
`TransactionWriteServiceTest`):

- `TransferServiceTest` — two-sided transfer inside one @Transaction;
  posting invariant; transfer link survives edits.
- `TransferCandidateMatcherTest` — scoring + thresholds; unambiguous
  matches auto-link, ambiguous ones go to Review.
- `RefundServiceTest` — refund event created; original event unchanged;
  link stored.
- `ManualEntryServiceTest` — validation rejects bad input; USER_CORRECTION
  outranks model suggestions for edited fields; offline-first
  (no network).
- `TransactionWriteServiceTest` (extend) — `restoreTransaction` clears
  tombstone; aggregations exclude DELETED.
- `TransactionLinkEntityTest` — parent/child links are unique per pair;
  cascade rules documented.
- `ContradictionHandlingTest` — new SMS adds evidence; if significant,
  status flips to REVIEW_REQUIRED; never resurrects a DELETED event.

Integration / instrumentation:

- `FinanceDaoV3IntegrationTest` (extend) — verify the new v7 tables and
  queries on an in-memory v7 database.
- `MigrationTest` (extend) — add a 6 → 7 fixture mirroring the existing
  1→2 and 3→4 fixtures. Use the exported `7.json`.

UI / Compose:

- `ManualEntryScreenTest` — Save / Cancel flow, validation messages.
- `TransferCandidatesScreenTest` — Review queue shows ambiguous matches.

Regression — the existing tests must remain green:

- `DedupeEngineTest`, `PostingPolicyTest`, `TransactionWriteServiceTest`,
  `FinanceDaoV2IntegrationTest`, `RoomSmsRepositoryTest`,
  `TransactionMappingTestV2`, `SmsIngestionPolicyTest`, `MoneyTest`,
  `AccountAuthorityTest`, `ParserFixtureTest`, `EnrichmentOrchestratorTest`,
  `LlmResponseDecoderTest`, `PoliciesTest`, `DependencyDirectionTest`.

## 4. Acceptance gate

Manual, transfer, refund, fee, cash, and deletion scenarios remain correct
through reprocessing; user edits survive enrichment; transaction detail
explains source and status. Build green; focused / integration / UI tests
pass; no secrets in logs / exports; offline behavior intact.

## 5. Final report

Write a `P11_REPORT.md` in the repo root following the same structure as
`STAGE5_REPORT.md` (Files / Schema / Domain / Tests / Acceptance / Risks).
Append to or supersede `STAGE5_REPORT.md` as appropriate.

## 6. Verification commands

```pwsh
cd "c:\Users\Nitin\Desktop\job search"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

Both must succeed; all P09 + P10 + P11 unit tests must pass; no
regressions in the existing suite.

## 7. Notes on style and architecture

- Keep UI free of `data.db` imports. ViewModels live in `application/`.
- Domain services accept `Sink` interfaces; data layer provides the Room
  implementation. The pattern used in P09/P10 (`DedupeSink`,
  `TransactionWriteSink`) is the template.
- New domain enums go in `domain/model/`; new policies in
  `domain/policy/`; new services in `domain/service/`.
- Money is always in `Long` minor units. Use `MoneyPolicy.toMajor` /
  `toMinorUnits` for display / parsing boundaries.
- `Instant` for absolute time, `LocalDate` (epoch day) for grouping.
  Never store wall-clock strings.
- Idempotency keys: `dedupeKey` for transactions, `linkIdentity` for
  evidence links, `clusterIdentity` for dedup clusters, `transferGroupId`
  for transfer siblings, `refundIdempotencyKey` (you can derive
  sha-256 of `(refundedEventId, refundEventId, kind, amountMinor)`) for
  refund links.
- The `DependencyDirectionTest` will fail if you cross layer boundaries.
  Run it locally before declaring done.
