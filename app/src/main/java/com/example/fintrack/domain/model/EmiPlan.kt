package com.example.fintrack.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Stage 6 P13 — EMI plans.
 *
 * Design invariants (App Bible + P13):
 *  - An EMI is modelled as a first-class financial plan with its own
 *    lifecycle (ACTIVE -> CLOSED | PRECLOSED | REFINANCED). Historical
 *    installments are never rewritten: a preclosure is an explicit event
 *    that closes the plan and records a final settlement; a refinancing
 *    creates a NEW plan linked to the old one by [refinancedFromPlanId].
 *  - Principal, interest rate and per-installment breakdown are only
 *    stored when evidenced (SMS / bank statement / manual entry). Unknown
 *    fields remain null — never guessed. Coverage labels surface this.
 *  - A plan is matched to its installments using the durable
 *    [EmiInstallmentIdentity] = sha-256(planId | installmentNumber) so
 *    re-running the matcher is idempotent and a partial reprocess cannot
 *    duplicate an installment.
 *  - Missed/partial installments are tracked explicitly: the absence of
 *    an installment in a given month is NOT a payment; the matcher only
 *    marks an installment PAID when evidence actually links it to a
 *    posted event.
 *  - User corrections are first-class data and survive automated
 *    reprocessing (see ProvenancePolicy).
 *  - Completed (CLOSED / PRECLOSED / REFINANCED) plans remain queryable
 *    for the lifetime of the database; the UI may archive them but the
 *    schema preserves them.
 */
enum class EmiPlanStatus { ACTIVE, CLOSED, PRECLOSED, REFINANCED, PAUSED }

/**
 * One EMI plan.
 *
 * @param emiAccountId the [Account] the loan was paid FROM (the user's
 *        bank account — separate from the merchant/biller that owns the
 *        loan). When a loan is paid from a credit card the funding
 *        account is a CREDIT_CARD account, the same convention used by
 *        the P12 card-payment service.
 * @param principalMinor total principal in minor units. null when only
 *        the installment amount is evidenced.
 * @param interestRateAnnualBps annual interest rate in basis points
 *        (e.g. 14.5% = 1450). null when unknown.
 * @param installmentAmountMinor per-installment amount in minor units
 *        (typically principal+interest combined). null when unknown.
 * @param totalInstallments the contracted number of installments; null
 *        when the SMS only says "EMI for 12 months" without a count.
 */
data class EmiPlan(
    val id: EntityId,
    val emiAccountId: EntityId,               // -> Account.id (the funding account)
    val merchantOrBiller: String?,            // normalized payee; null = unknown
    val referenceId: String?,                 // loan / contract reference; null = unknown
    val principalMinor: Long?,
    val interestRateAnnualBps: Int?,
    val installmentAmountMinor: Long?,
    val totalInstallments: Int?,
    val startDate: LocalDate?,                // first installment due
    val endDate: LocalDate?,                  // contracted final installment date
    val currencyCode: String,
    val status: EmiPlanStatus,
    val planIdentity: String,                 // sha-256(emiAccountId | merchant | reference | start) for idempotency
    /**
     * Set on the NEW plan that replaced an older one. The old plan
     * transitions to status=REFINANCED; the new plan keeps
     * status=ACTIVE and records the back-reference here.
     */
    val refinancedFromPlanId: EntityId?,
    val provenance: Provenance,
    val capturedAt: Instant,
    val closedAt: Instant? = null,            // set on PRECLOSED / CLOSED / REFINANCED
) {
    init {
        require(currencyCode.length == 3)
        if (principalMinor != null) require(principalMinor >= 0)
        if (installmentAmountMinor != null) require(installmentAmountMinor >= 0)
        if (totalInstallments != null) require(totalInstallments > 0)
        if (interestRateAnnualBps != null) require(interestRateAnnualBps in 0..10_000)
        if (endDate != null && startDate != null) {
            require(!endDate.isBefore(startDate)) { "endDate must be on or after startDate" }
        }
        if (refinancedFromPlanId != null) {
            require(refinancedFromPlanId != id) { "a plan cannot refinance itself" }
            // The new plan is ACTIVE; the OLD plan (which it
            // references) carries status=REFINANCED. The link is
            // carried forward only on the new plan, never on the old
            // (its back-reference is the new plan's id).
        }
    }
}

/**
 * A single installment within a plan. Installments are created when
 * evidence is seen; missing months stay missing and are surfaced as
 * "incomplete" rather than guessed.
 */
enum class EmiInstallmentStatus { DUE, PAID, MISSED, PARTIAL, SKIPPED }

data class EmiInstallment(
    val id: EntityId,
    val planId: EntityId,                     // -> EmiPlan.id
    val installmentNumber: Int,               // 1-based
    val dueDate: LocalDate,
    val amountDueMinor: Long?,                // null when amount is unknown
    val amountPaidMinor: Long?,               // set when matched to evidence
    val currencyCode: String,
    val status: EmiInstallmentStatus,
    val transactionId: String?,               // linked posted event when PAID
    val installmentIdentity: String,          // sha-256(planId | installmentNumber)
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        require(installmentNumber >= 1) { "installmentNumber must be >= 1" }
        if (amountDueMinor != null) require(amountDueMinor >= 0)
        if (amountPaidMinor != null) {
            require(amountPaidMinor >= 0)
            require(status == EmiInstallmentStatus.PAID ||
                status == EmiInstallmentStatus.PARTIAL) {
                "amountPaidMinor is set but status is $status"
            }
        }
        if (status == EmiInstallmentStatus.PAID || status == EmiInstallmentStatus.PARTIAL) {
            require(transactionId != null) { "PAID/PARTIAL installments must link a transactionId" }
        }
    }
}

/**
 * P13 #4: preclosure event. Closes the plan and records any fees or
 * adjustments evidenced. Historical installments are preserved — the
 * matcher does NOT rewrite them.
 */
enum class EmiPreclosureKind { FORECLOSURE, SETTLEMENT, BUYOUT }

data class EmiPreclosure(
    val id: EntityId,
    val planId: EntityId,                     // -> EmiPlan.id
    val occurredAt: Instant,
    val localDate: LocalDate,
    val principalOutstandingMinor: Long?,     // evidenced outstanding at preclosure
    val feeMinor: Long?,                      // preclosure fee if evidenced
    val adjustmentMinor: Long?,               // signed: + debit to user, - credit
    val currencyCode: String,
    val kind: EmiPreclosureKind,
    val transactionId: String?,               // optional: the settlement transaction
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        if (principalOutstandingMinor != null) require(principalOutstandingMinor >= 0)
        if (feeMinor != null) require(feeMinor >= 0)
    }
}
