# Parser Authoring Guide

This is the single framework for turning raw SMS into deterministic,
evidence-bounded parse candidates. All parser work happens here — no more
scattered stubs.

## Pipeline

```
raw SMS (immutable)
  └─> DeterministicSmsClassifier   (FINANCIAL / NON_FINANCIAL / BORDERLINE)
        └─> TextNormalizer         (derived normalized text; raw never mutated)
              └─> RailParser adapters (UPI, IMPS/NEFT/RTGS, cards, ATM)
                    └─> ParseCandidate (all unknown fields stay null)
```

Classification is **separate** from extraction. Only `FINANCIAL` messages are
parsed deterministically. `BORDERLINE` messages may later be escalated to an
optional LLM classifier (behind an interface); they are **never** parsed by
rules alone.

## Adding a bank or rail

1. Add golden fixtures to `parser/fixture/FixtureCorpus.kt` (real-shaped SMS
   for the new bank, including at least one malformed case).
2. If existing adapters fail deterministically on the fixtures, extend the
   relevant adapter in `parser/rail/RailParsers.kt` with a new rule.
3. Every rule must:
   - return `null` when it cannot match (never guess),
   - emit a stable `ruleId` in its `FieldProvenance`,
   - keep confidence ≤ 0.98 for regex matches (only exact structural matches
     may claim higher).
4. Run the fixture tests (`ParserFixtureTest`); precision/recall per signal id
   must not regress.

## Field rules

- **Amounts**: integer minor units only (`MoneyPolicy`), parsed via
  `TextNormalizer.parseAmountToken`. Tokens that don't parse cleanly → null.
- **VPA normalization**: lowercase, no spaces, exactly one `@`, alphanumeric
  handle. Malformed VPAs → null (see `malformed-vpa-two-at` fixture).
- **Card masks**: exactly 4 trailing digits, else null.
- **Bank references**: UTR/RRN/ref-no patterns; uppercase-normalized.
- **Dates**: explicit dates only; dd/mm/yyyy (Indian convention) and ISO.
  Missing time defaults to noon via `DateTimePolicy` (DST-safe).
- **Credit kind**: salary/interest/cashback/refund/P2P/transfer-in/merchant —
  only when a keyword or confirmed merchant VPA says so; else UNKNOWN.

## Ownership

A masked card token or account suffix is a *hint*, never authoritative
ownership. Mapping evidence to an owned account requires the user-confirmed
sender mapping (`sender_account_mappings.confirmedByUser = 1`).

## Merchant learning

When the user confirms a UPI VPA belongs to a merchant, register it in
`MerchantRegistry`. Later credits from that VPA classify as
`MERCHANT_CREDIT`; unregistered VPAs stay `P2P_RECEIVE`/UNKNOWN.

## Provenance

Every extracted field carries `FieldProvenance(ruleId, fixtureVersion,
confidence)`. Downstream consumers must be able to answer "which rule produced
this value?" from the candidate alone.
