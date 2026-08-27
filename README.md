# FinTrack — Increment 1: Foundation & Scope Guardrails

Layered Android finance tracker foundation. **This increment establishes architecture only; no financial features yet.**

## Architecture
`ui -> application/use cases -> domain -> data`, with boundaries in `parser/`, `llm/`, `importexport/`.

## Canonical vocabulary (App Bible)
- **message** = raw evidence (immutable)
- **transaction / financial event** = normalized interpretation
- **ledger entry** = posting
- **account** = balance-bearing container
- **transfer/settlement** = explicit relationship (`TransferLink`)

## Non-goals (enforced by `domain/NonGoals.kt` + architecture tests)
No bank login/API, investments, transfer execution, cloud sync, push notifications, SMS deletion, remote config.

## Data
Room schema **v1** (`messages`, `transactions`). Raw evidence and interpretations stored separately. User corrections carry their own provenance (`correctionSource*`) and survive reprocessing.

## Tests
- `MoneyTest`, `EntityIdTest`, `LocalDateDerivationTest` — domain primitives
- `TransactionMappingTest` — correction survival + lifecycle round-trip
- `DependencyDirectionTest` — dependency direction + non-goals registry enforcement

Run: `./gradlew test`
