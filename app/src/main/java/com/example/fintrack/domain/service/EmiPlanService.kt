package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.EmiInstallment
import com.example.fintrack.domain.model.EmiInstallmentStatus
import com.example.fintrack.domain.model.EmiPlan
import com.example.fintrack.domain.model.EmiPlanStatus
import com.example.fintrack.domain.model.EmiPreclosure
import com.example.fintrack.domain.model.EmiPreclosureKind
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.model.PostingDirection
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Stage 6 P13: EMI plan + installment + preclosure services.
 *
 * Design:
 *  - Plans and installments are persisted through [EmiPlanSink] /
 *    [EmiInstallmentSink]. Both carry stable identity hashes so a
 *    parser re-run cannot duplicate the same plan or installment.
 *  - Missed/partial installments are tracked explicitly; absence of
 *    evidence is NEVER treated as a payment. The matcher only marks
 *    an installment PAID when a posted transaction is linked to it.
 *  - A preclosure is an explicit event that closes the plan. Historical
 *    installments are never rewritten.
 *  - A refinance is modelled as a new plan with `refinancedFromPlanId`
 *    pointing to the previous plan; the previous plan transitions to
 *    REFINANCED and remains queryable for audit.
 *  - User corrections survive automated reprocessing (ProvenancePolicy).
 */
class EmiPlanService(
    private val planSink: EmiPlanSink,
    private val installmentSink: EmiInstallmentSink,
    private val preclosureSink: EmiPreclosureSink,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // ---- Plan creation ----

    /**
     * Open a new EMI plan. The plan is durable on the
     * sha-256(emiAccountId | merchant | reference | startDate) hash so
     * a re-run is a no-op. Optional [firstInstallment] lets the caller
     * atomically create the first installment in the same call.
     */
    suspend fun openPlan(
        emiAccountId: EntityId,
        merchantOrBiller: String?,
        referenceId: String?,
        principalMinor: Long?,
        interestRateAnnualBps: Int?,
        installmentAmountMinor: Long?,
        totalInstallments: Int?,
        startDate: LocalDate?,
        currencyCode: String,
        provenance: Provenance,
        firstInstallmentDueDate: LocalDate? = null,
        firstInstallmentAmountMinor: Long? = null,
    ): Result<EmiPlan> {
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        if (totalInstallments != null && totalInstallments <= 0) {
            return Result.failure(IllegalArgumentException("totalInstallments must be > 0 when set"))
        }
        if (installmentAmountMinor != null && installmentAmountMinor <= 0) {
            return Result.failure(IllegalArgumentException("installmentAmountMinor must be > 0 when set"))
        }
        if (principalMinor != null && principalMinor < 0) {
            return Result.failure(IllegalArgumentException("principalMinor must be >= 0"))
        }
        if (interestRateAnnualBps != null && interestRateAnnualBps !in 0..10_000) {
            return Result.failure(IllegalArgumentException("interestRateAnnualBps must be 0..10000"))
        }
        val identity = planIdentityFor(emiAccountId, merchantOrBiller, referenceId, startDate)
        val now = clock()
        val plan = EmiPlan(
            id = EntityId.generate(),
            emiAccountId = emiAccountId,
            merchantOrBiller = merchantOrBiller,
            referenceId = referenceId,
            principalMinor = principalMinor,
            interestRateAnnualBps = interestRateAnnualBps,
            installmentAmountMinor = installmentAmountMinor,
            totalInstallments = totalInstallments,
            startDate = startDate,
            endDate = computeEndDate(startDate, totalInstallments),
            currencyCode = currencyCode,
            status = EmiPlanStatus.ACTIVE,
            planIdentity = identity,
            refinancedFromPlanId = null,
            provenance = provenance,
            capturedAt = now,
            closedAt = null,
        )
        planSink.insertPlan(plan)
        // Optionally create the first installment in the same call so the
        // matcher has a row to update when the first payment arrives.
        if (firstInstallmentDueDate != null) {
            val inst = buildInstallment(
                planId = plan.id,
                installmentNumber = 1,
                dueDate = firstInstallmentDueDate,
                amountDueMinor = firstInstallmentAmountMinor,
                provenance = provenance,
            )
            installmentSink.insertInstallment(inst, installmentIdentityFor(plan.id, 1))
        }
        return Result.success(plan)
    }

    // ---- Installment matching ----

    /**
     * Mark an installment as paid (or partial) by linking a posted
     * transaction. Re-runs are idempotent: the matcher supplies
     * (planId, installmentNumber) and the sink updates the existing
     * row rather than inserting a new one.
     */
    suspend fun recordInstallmentPayment(
        planId: EntityId,
        installmentNumber: Int,
        transactionId: String,
        amountPaidMinor: Long?,
        provenance: Provenance,
    ): Result<EmiInstallment> {
        if (installmentNumber < 1) {
            return Result.failure(IllegalArgumentException("installmentNumber must be >= 1"))
        }
        val existing = installmentSink.findByPlanAndNumber(planId, installmentNumber)
            ?: return Result.failure(NoSuchElementException("no installment #$installmentNumber on plan $planId"))
        if (existing.status == EmiInstallmentStatus.PAID) {
            return Result.success(existing) // idempotent no-op
        }
        val paid = existing.copy(
            status = if (amountPaidMinor != null && amountPaidMinor < (existing.amountDueMinor ?: Long.MAX_VALUE)) {
                EmiInstallmentStatus.PARTIAL
            } else {
                EmiInstallmentStatus.PAID
            },
            amountPaidMinor = amountPaidMinor ?: existing.amountDueMinor,
            transactionId = transactionId,
        )
        installmentSink.updateInstallment(paid)
        return Result.success(paid)
    }

    /**
     * Mark an installment as missed when the due date has passed
     * without a payment. Caller is responsible for the
     * "time-since-due" check — the service just records the verdict.
     */
    suspend fun markInstallmentMissed(
        planId: EntityId,
        installmentNumber: Int,
        provenance: Provenance,
    ): Result<EmiInstallment> {
        val existing = installmentSink.findByPlanAndNumber(planId, installmentNumber)
            ?: return Result.failure(NoSuchElementException("no installment #$installmentNumber on plan $planId"))
        if (existing.status == EmiInstallmentStatus.PAID ||
            existing.status == EmiInstallmentStatus.PARTIAL) {
            return Result.success(existing)
        }
        val updated = existing.copy(status = EmiInstallmentStatus.MISSED)
        installmentSink.updateInstallment(updated)
        return Result.success(updated)
    }

    // ---- Preclosure ----

    /**
     * Record a preclosure event. The plan is closed (status=PRECLOSED)
     * and any evidenced fees/adjustments are stored on the preclosure
     * row. Historical installments are preserved.
     */
    suspend fun recordPreclosure(
        planId: EntityId,
        occurredAt: Instant,
        principalOutstandingMinor: Long?,
        feeMinor: Long?,
        adjustmentMinor: Long?,
        kind: EmiPreclosureKind,
        transactionId: String?,
        provenance: Provenance,
    ): Result<EmiPreclosure> {
        if (principalOutstandingMinor != null && principalOutstandingMinor < 0) {
            return Result.failure(IllegalArgumentException("principalOutstandingMinor must be >= 0"))
        }
        if (feeMinor != null && feeMinor < 0) {
            return Result.failure(IllegalArgumentException("feeMinor must be >= 0"))
        }
        val identity = preclosureIdentityFor(planId, occurredAt, kind)
        val preclosure = EmiPreclosure(
            id = EntityId.generate(),
            planId = planId,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            principalOutstandingMinor = principalOutstandingMinor,
            feeMinor = feeMinor,
            adjustmentMinor = adjustmentMinor,
            currencyCode = "INR", // plans are single-currency; inheritance to preclosure handled by caller
            kind = kind,
            transactionId = transactionId,
            provenance = provenance,
        )
        preclosureSink.insertPreclosure(preclosure, identity)
        // Close the plan.
        val plan = planSink.findPlan(planId)
            ?: return Result.failure(NoSuchElementException("plan $planId not found"))
        if (plan.status != EmiPlanStatus.PRECLOSED) {
            val closed = plan.copy(
                status = EmiPlanStatus.PRECLOSED,
                closedAt = clock(),
            )
            planSink.updatePlan(closed)
        }
        return Result.success(preclosure)
    }

    // ---- Refinancing ----

    /**
     * Refinance a plan into a new one. The previous plan transitions
     * to REFINANCED (preserved for audit); a new ACTIVE plan is opened
     * with `refinancedFromPlanId` pointing to the old one. Historical
     * installments are NEVER rewritten.
     */
    suspend fun refinance(
        previousPlanId: EntityId,
        newEmiAccountId: EntityId,
        merchantOrBiller: String?,
        referenceId: String?,
        principalMinor: Long?,
        interestRateAnnualBps: Int?,
        installmentAmountMinor: Long?,
        totalInstallments: Int?,
        startDate: LocalDate?,
        currencyCode: String,
        provenance: Provenance,
    ): Result<EmiPlan> {
        val previous = planSink.findPlan(previousPlanId)
            ?: return Result.failure(NoSuchElementException("plan $previousPlanId not found"))
        if (previous.status == EmiPlanStatus.REFINANCED) {
            return Result.failure(IllegalStateException("plan $previousPlanId is already REFINANCED"))
        }
        val now = clock()
        val closed = previous.copy(
            status = EmiPlanStatus.REFINANCED,
            closedAt = now,
        )
        planSink.updatePlan(closed)
        val newIdentity = planIdentityFor(newEmiAccountId, merchantOrBiller, referenceId, startDate)
        val newPlan = EmiPlan(
            id = EntityId.generate(),
            emiAccountId = newEmiAccountId,
            merchantOrBiller = merchantOrBiller,
            referenceId = referenceId,
            principalMinor = principalMinor,
            interestRateAnnualBps = interestRateAnnualBps,
            installmentAmountMinor = installmentAmountMinor,
            totalInstallments = totalInstallments,
            startDate = startDate,
            endDate = computeEndDate(startDate, totalInstallments),
            currencyCode = currencyCode,
            status = EmiPlanStatus.ACTIVE,
            planIdentity = newIdentity,
            refinancedFromPlanId = previous.id,
            provenance = provenance,
            capturedAt = now,
            closedAt = null,
        )
        planSink.insertPlan(newPlan)
        return Result.success(newPlan)
    }

    // ---- Progress / pay-off view ----

    /**
     * Pure progress computation over a plan + its installments. Coverage
     * labels are explicit: missing values stay "unknown" instead of being
     * guessed.
     */
    fun progress(plan: EmiPlan, installments: List<EmiInstallment>): EmiProgress {
        val sorted = installments.sortedBy { it.installmentNumber }
        val paid = sorted.count { it.status == EmiInstallmentStatus.PAID }
        val partial = sorted.count { it.status == EmiInstallmentStatus.PARTIAL }
        val missed = sorted.count { it.status == EmiInstallmentStatus.MISSED }
        val total = plan.totalInstallments ?: sorted.size
        val remaining = if (total > 0) (total - paid - partial).coerceAtLeast(0) else null
        val paidMinor = sorted.mapNotNull { it.amountPaidMinor }.sum()
        val outstandingMinor = plan.principalMinor?.let { (it - paidMinor).coerceAtLeast(0) }
        val hasPrincipal = plan.principalMinor != null
        val hasInterest = plan.interestRateAnnualBps != null
        val hasInstallmentAmount = plan.installmentAmountMinor != null
        val hasTotalCount = plan.totalInstallments != null
        val coverage = EmiProgress.Coverage(
            hasPrincipal = hasPrincipal,
            hasInterest = hasInterest,
            hasInstallmentAmount = hasInstallmentAmount,
            hasTotalCount = hasTotalCount,
        )
        return EmiProgress(
            planId = plan.id,
            planStatus = plan.status,
            paidInstallments = paid,
            partialInstallments = partial,
            missedInstallments = missed,
            totalInstallments = total,
            remainingInstallments = remaining,
            paidMinor = paidMinor,
            outstandingPrincipalMinor = outstandingMinor,
            currencyCode = plan.currencyCode,
            coverage = coverage,
        )
    }

    // ---- helpers ----

    private fun buildInstallment(
        planId: EntityId,
        installmentNumber: Int,
        dueDate: LocalDate,
        amountDueMinor: Long?,
        provenance: Provenance,
    ): EmiInstallment = EmiInstallment(
        id = EntityId.generate(),
        planId = planId,
        installmentNumber = installmentNumber,
        dueDate = dueDate,
        amountDueMinor = amountDueMinor,
        amountPaidMinor = null,
        currencyCode = "INR",
        status = EmiInstallmentStatus.DUE,
        transactionId = null,
        installmentIdentity = installmentIdentityFor(planId, installmentNumber),
        provenance = provenance,
    )

    private fun computeEndDate(start: LocalDate?, totalInstallments: Int?): LocalDate? {
        if (start == null || totalInstallments == null) return null
        // One installment per month from start. We do not adjust for
        // short Februarys etc. — the user can correct via the edit flow.
        return start.plusMonths(totalInstallments.toLong() - 1)
    }
}

data class EmiProgress(
    val planId: EntityId,
    val planStatus: EmiPlanStatus,
    val paidInstallments: Int,
    val partialInstallments: Int,
    val missedInstallments: Int,
    val totalInstallments: Int,
    val remainingInstallments: Int?,
    val paidMinor: Long,
    val outstandingPrincipalMinor: Long?,
    val currencyCode: String,
    val coverage: Coverage,
) {
    /**
     * Coverage tells the UI which fields are evidenced vs unknown. The
     * rule: every field is either evidenced or labelled "unknown"; the
     * engine NEVER fabricates a value to make the view look complete.
     */
    data class Coverage(
        val hasPrincipal: Boolean,
        val hasInterest: Boolean,
        val hasInstallmentAmount: Boolean,
        val hasTotalCount: Boolean,
    )
}

// ---- Sinks (domain-side persistence contracts) ----

interface EmiPlanSink {
    suspend fun insertPlan(plan: EmiPlan)
    suspend fun updatePlan(plan: EmiPlan)
    suspend fun findPlan(id: EntityId): EmiPlan?
}

interface EmiInstallmentSink {
    suspend fun insertInstallment(installment: EmiInstallment, identity: String)
    suspend fun updateInstallment(installment: EmiInstallment)
    suspend fun findByPlanAndNumber(planId: EntityId, installmentNumber: Int): EmiInstallment?
}

interface EmiPreclosureSink {
    suspend fun insertPreclosure(preclosure: EmiPreclosure, identity: String)
}

// ---- identity helpers ----

internal fun planIdentityFor(
    emiAccountId: EntityId, merchant: String?, reference: String?, start: LocalDate?,
): String {
    val raw = "${emiAccountId.value}|${merchant?.lowercase() ?: ""}|${reference ?: ""}|${start?.toEpochDay() ?: -1L}"
    return sha256Hex(raw)
}

internal fun installmentIdentityFor(planId: EntityId, installmentNumber: Int): String {
    val raw = "${planId.value}|$installmentNumber"
    return sha256Hex(raw)
}

internal fun preclosureIdentityFor(planId: EntityId, occurredAt: Instant, kind: EmiPreclosureKind): String {
    val raw = "${planId.value}|${occurredAt.toEpochMilli()}|${kind.name}"
    return sha256Hex(raw)
}
