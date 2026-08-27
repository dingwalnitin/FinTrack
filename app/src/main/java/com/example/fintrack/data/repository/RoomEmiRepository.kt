package com.example.fintrack.data.repository

import com.example.fintrack.data.db.EmiInstallmentEntity
import com.example.fintrack.data.db.EmiPlanEntity
import com.example.fintrack.data.db.EmiPreclosureEntity
import com.example.fintrack.data.db.FinanceDaoV5
import com.example.fintrack.domain.model.EmiInstallment
import com.example.fintrack.domain.model.EmiInstallmentStatus
import com.example.fintrack.domain.model.EmiPlan
import com.example.fintrack.domain.model.EmiPlanStatus
import com.example.fintrack.domain.model.EmiPreclosure
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.service.EmiInstallmentSink
import com.example.fintrack.domain.service.EmiPlanSink
import com.example.fintrack.domain.service.EmiPreclosureSink
import com.example.fintrack.domain.service.planIdentityFor
import java.time.Instant
import java.time.ZoneId

/**
 * Room-backed persistence for the P13 EMI services. Every insert goes
 * through the v8 FinanceDaoV5 which enforces unique `*Identity` and
 * `(planId, installmentNumber)` indices so a parser re-run is a no-op.
 */
class RoomEmiRepository(
    private val dao: FinanceDaoV5,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : EmiPlanSink, EmiInstallmentSink, EmiPreclosureSink {

    // ---- EmiPlanSink ----

    override suspend fun insertPlan(plan: EmiPlan) {
        val identity = plan.planIdentity.ifBlank {
            planIdentityFor(plan.emiAccountId, plan.merchantOrBiller, plan.referenceId, plan.startDate)
        }
        dao.insertEmiPlan(plan.toEntity(identity))
    }

    override suspend fun updatePlan(plan: EmiPlan) {
        dao.updateEmiPlanStatus(
            id = plan.id.value,
            status = plan.status.name,
            closedAtEpochMs = plan.closedAt?.toEpochMilli(),
        )
    }

    override suspend fun findPlan(id: EntityId): EmiPlan? =
        dao.findEmiPlanById(id.value)?.toDomain()

    // ---- EmiInstallmentSink ----

    override suspend fun insertInstallment(installment: EmiInstallment, identity: String) {
        dao.insertEmiInstallment(installment.toEntity(identity))
    }

    override suspend fun updateInstallment(installment: EmiInstallment) {
        dao.updateEmiInstallmentPaid(
            id = installment.id.value,
            status = installment.status.name,
            amountPaidMinor = installment.amountPaidMinor,
            transactionId = installment.transactionId,
        )
    }

    override suspend fun findByPlanAndNumber(planId: EntityId, installmentNumber: Int): EmiInstallment? {
        val list = dao.installmentsForPlan(planId.value)
        return list.firstOrNull { it.installmentNumber == installmentNumber }?.toDomain()
    }

    // ---- EmiPreclosureSink ----

    override suspend fun insertPreclosure(preclosure: EmiPreclosure, identity: String) {
        dao.insertEmiPreclosure(preclosure.toEntity(identity))
    }

    // ---- mappers ----

    private fun EmiPlan.toEntity(identity: String) = EmiPlanEntity(
        id = id.value,
        emiAccountId = emiAccountId.value,
        merchantOrBiller = merchantOrBiller,
        referenceId = referenceId,
        principalMinor = principalMinor,
        interestRateAnnualBps = interestRateAnnualBps,
        installmentAmountMinor = installmentAmountMinor,
        totalInstallments = totalInstallments,
        startDateEpochDay = startDate?.toEpochDay(),
        endDateEpochDay = endDate?.toEpochDay(),
        currencyCode = currencyCode,
        status = status.name,
        planIdentity = identity,
        refinancedFromPlanId = refinancedFromPlanId?.value,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
        capturedAtEpochMs = capturedAt.toEpochMilli(),
        closedAtEpochMs = closedAt?.toEpochMilli(),
    )

    private fun EmiPlanEntity.toDomain() = EmiPlan(
        id = EntityId(id),
        emiAccountId = EntityId(emiAccountId),
        merchantOrBiller = merchantOrBiller,
        referenceId = referenceId,
        principalMinor = principalMinor,
        interestRateAnnualBps = interestRateAnnualBps,
        installmentAmountMinor = installmentAmountMinor,
        totalInstallments = totalInstallments,
        startDate = startDateEpochDay?.let { java.time.LocalDate.ofEpochDay(it) },
        endDate = endDateEpochDay?.let { java.time.LocalDate.ofEpochDay(it) },
        currencyCode = currencyCode,
        status = EmiPlanStatus.valueOf(status),
        planIdentity = planIdentity,
        refinancedFromPlanId = refinancedFromPlanId?.let { EntityId(it) },
        provenance = Provenance(
            sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
            sourceVersion = sourceVersion,
            capturedAt = Instant.ofEpochMilli(capturedAtEpochMs),
        ),
        capturedAt = Instant.ofEpochMilli(capturedAtEpochMs),
        closedAt = closedAtEpochMs?.let { Instant.ofEpochMilli(it) },
    )

    private fun EmiInstallment.toEntity(identity: String) = EmiInstallmentEntity(
        id = id.value,
        planId = planId.value,
        installmentNumber = installmentNumber,
        dueDateEpochDay = dueDate.toEpochDay(),
        amountDueMinor = amountDueMinor,
        amountPaidMinor = amountPaidMinor,
        currencyCode = currencyCode,
        status = status.name,
        transactionId = transactionId,
        installmentIdentity = identity,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
    )

    private fun EmiInstallmentEntity.toDomain() = EmiInstallment(
        id = EntityId(id),
        planId = EntityId(planId),
        installmentNumber = installmentNumber,
        dueDate = java.time.LocalDate.ofEpochDay(dueDateEpochDay),
        amountDueMinor = amountDueMinor,
        amountPaidMinor = amountPaidMinor,
        currencyCode = currencyCode,
        status = EmiInstallmentStatus.valueOf(status),
        transactionId = transactionId,
        installmentIdentity = installmentIdentity,
        provenance = Provenance(
            sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
            sourceVersion = sourceVersion,
            capturedAt = Instant.ofEpochMilli(0L),
        ),
    )

    private fun EmiPreclosure.toEntity(identity: String) = EmiPreclosureEntity(
        id = id.value,
        planId = planId.value,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        principalOutstandingMinor = principalOutstandingMinor,
        feeMinor = feeMinor,
        adjustmentMinor = adjustmentMinor,
        currencyCode = currencyCode,
        kind = kind.name,
        transactionId = transactionId,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
        preclosureIdentity = identity,
        createdAtEpochMs = occurredAt.toEpochMilli(),
    )
}
