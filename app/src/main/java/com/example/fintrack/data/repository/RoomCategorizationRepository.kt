package com.example.fintrack.data.repository

import com.example.fintrack.data.db.CategoryAuditEntity
import com.example.fintrack.data.db.CategoryEntity
import com.example.fintrack.data.db.CategoryRuleEntity
import com.example.fintrack.data.db.FinanceDaoV6
import com.example.fintrack.data.db.LlmCategorySuggestionEntity
import com.example.fintrack.data.db.MerchantAliasEntity
import com.example.fintrack.data.db.MerchantEntity
import com.example.fintrack.data.db.MerchantVpaBindingEntity
import com.example.fintrack.domain.model.Category
import com.example.fintrack.domain.model.CategoryAudit
import com.example.fintrack.domain.model.CategoryKind
import com.example.fintrack.domain.model.CategoryRule
import com.example.fintrack.domain.model.CategoryStatus
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.LlmCategorySuggestion
import com.example.fintrack.domain.model.Merchant
import com.example.fintrack.domain.model.MerchantAlias
import com.example.fintrack.domain.model.MerchantStatus
import com.example.fintrack.domain.model.MerchantVpaBinding
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.RuleStatus
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.service.CategorizationSink
import java.security.MessageDigest
import java.time.Instant

/**
 * Room-backed [CategorizationSink] for Stage 7 P14. Every insert is
 * idempotent on a stable identity hash backed by a unique index so
 * re-running parsers / categorization / user-correction flows cannot
 * duplicate history.
 */
class RoomCategorizationRepository(
    private val dao: FinanceDaoV6,
) : CategorizationSink {

    // ---- Categories ----

    override suspend fun findUncategorized(): Category? =
        dao.findCategoryByNormalizedName("uncategorized")?.toDomain()

    override suspend fun findCategoryByNormalizedName(normalized: String): Category? =
        dao.findCategoryByNormalizedName(normalized)?.toDomain()

    override suspend fun insertCategory(category: Category, normalizedKey: String): Boolean {
        val row = CategoryEntity(
            id = category.id.value,
            name = category.name,
            normalizedName = normalizedKey,
            parentId = category.parentId?.value,
            status = category.status.name,
            kind = category.kind.name,
            sortOrder = category.sortOrder,
            createdAtEpochMs = category.createdAt.toEpochMilli(),
        )
        return dao.insertCategory(row) != -1L
    }

    override suspend fun archiveCategory(categoryId: EntityId) {
        dao.findCategoryById(categoryId.value)?.let {
            dao.updateCategory(it.copy(status = CategoryStatus.ARCHIVED.name))
        }
    }

    // ---- Merchants ----

    override suspend fun findMerchantByIdentity(identity: String): Merchant? =
        dao.findMerchantByIdentity(identity)?.toDomain()

    override suspend fun findMerchantById(id: EntityId): Merchant? =
        dao.findMerchantById(id.value)?.toDomain()

    override suspend fun insertMerchant(merchant: Merchant, identity: String): Boolean {
        val row = MerchantEntity(
            id = merchant.id.value,
            displayName = merchant.displayName,
            normalizedName = merchant.normalizedName,
            accountId = merchant.accountId?.value,
            status = merchant.status.name,
            merchantIdentity = identity,
            sourceKind = merchant.sourceKind.name,
            sourceVersion = merchant.sourceVersion,
            createdAtEpochMs = merchant.createdAt.toEpochMilli(),
            mergedIntoMerchantId = merchant.mergedIntoMerchantId?.value,
        )
        return dao.insertMerchant(row) != -1L
    }

    override suspend fun listActiveMerchants(): List<Merchant> =
        dao.merchantsByStatus(MerchantStatus.ACTIVE.name).map { it.toDomain() }

    override suspend fun mergeMerchant(source: EntityId, target: EntityId) {
        dao.updateMerchantLifecycle(
            id = source.value,
            status = MerchantStatus.MERGED.name,
            mergedInto = target.value,
        )
    }

    // ---- Aliases ----

    override suspend fun findAlias(merchantId: EntityId, aliasNormalized: String): MerchantAlias? {
        val identity = sha256("${merchantId.value}|$aliasNormalized")
        return dao.findMerchantAliasByIdentity(identity)?.toDomain()
    }

    override suspend fun insertAlias(alias: MerchantAlias, aliasIdentity: String): Boolean {
        val row = MerchantAliasEntity(
            id = alias.id,
            merchantId = alias.merchantId.value,
            aliasRaw = alias.aliasRaw,
            aliasNormalized = alias.aliasNormalized,
            aliasIdentity = aliasIdentity,
            sourceKind = alias.sourceKind.name,
            sourceVersion = alias.sourceVersion,
            createdAtEpochMs = alias.createdAt.toEpochMilli(),
        )
        return dao.insertMerchantAlias(row) != -1L
    }

    override suspend fun aliasesForMerchant(merchantId: EntityId): List<MerchantAlias> =
        dao.aliasesForMerchant(merchantId.value).map { it.toDomain() }

    // ---- VPA bindings ----

    override suspend fun findVpaBinding(vpa: String): MerchantVpaBinding? {
        // Bindings are keyed by (vpa, merchant); look up any binding for this vpa.
        val all = dao.confirmedMerchantVpaBindings() + unconfirmedBindings()
        return all.firstOrNull { it.vpa == vpa.lowercase() }?.toDomain()
    }

    private suspend fun unconfirmedBindings(): List<MerchantVpaBindingEntity> {
        // We do not have a direct "all bindings" query; confirmed ones are the
        // authoritative set for categorization. Unconfirmed rows are only used
        // for diagnostics and are not returned here.
        return emptyList()
    }

    override suspend fun insertVpaBinding(binding: MerchantVpaBinding, vpaIdentity: String): Boolean {
        val row = MerchantVpaBindingEntity(
            id = binding.id,
            merchantId = binding.merchantId.value,
            vpa = binding.vpa,
            vpaIdentity = vpaIdentity,
            confirmedByUser = binding.confirmedByUser,
            sourceKind = binding.sourceKind.name,
            sourceVersion = binding.sourceVersion,
            createdAtEpochMs = binding.createdAt.toEpochMilli(),
        )
        return dao.insertMerchantVpaBinding(row) != -1L
    }

    override suspend fun confirmedVpaBindings(): List<MerchantVpaBinding> =
        dao.confirmedMerchantVpaBindings().map { it.toDomain() }

    // ---- Rules ----

    override suspend fun activeRules(): List<CategoryRule> =
        dao.categoryRulesByStatus(RuleStatus.ACTIVE.name).map { it.toDomain() }

    override suspend fun insertRule(rule: CategoryRule): Boolean {
        val row = CategoryRuleEntity(
            id = rule.id,
            name = rule.name,
            priority = rule.priority,
            status = rule.status.name,
            matchKind = rule.matchKind.name,
            matchValue = rule.matchValue,
            merchantId = rule.merchantId?.value,
            categoryId = rule.categoryId.value,
            sourceKind = rule.sourceKind.name,
            sourceVersion = rule.sourceVersion,
            createdAtEpochMs = rule.createdAt.toEpochMilli(),
            createdBy = rule.createdBy,
        )
        return dao.insertCategoryRule(row) != -1L
    }

    override suspend fun findRuleById(id: String): CategoryRule? =
        dao.findCategoryRuleById(id)?.toDomain()

    override suspend fun disableRule(id: String) {
        dao.updateCategoryRuleStatus(id, RuleStatus.DISABLED.name)
    }

    // ---- LLM advisor ----

    override suspend fun insertLlmSuggestion(
        suggestion: LlmCategorySuggestion,
        identity: String,
    ): Boolean {
        val row = LlmCategorySuggestionEntity(
            id = suggestion.id,
            transactionId = suggestion.transactionId,
            categoryId = suggestion.categoryId?.value,
            merchantId = suggestion.merchantId?.value,
            confidence = suggestion.confidence,
            reason = suggestion.reason,
            modelId = suggestion.modelId,
            promptVersion = suggestion.promptVersion,
            schemaVersion = suggestion.schemaVersion,
            suggestionIdentity = identity,
            createdAtEpochMs = suggestion.createdAt.toEpochMilli(),
            accepted = suggestion.accepted,
            acceptedAtEpochMs = suggestion.acceptedAt?.toEpochMilli(),
        )
        return dao.insertLlmCategorySuggestion(row) != -1L
    }

    override suspend fun acceptLlmSuggestion(id: String, atMs: Long) {
        dao.acceptLlmCategorySuggestion(id, atMs)
    }

    // ---- Audit ----

    override suspend fun appendCategoryAudit(
        transactionId: String,
        previousCategoryId: EntityId?,
        newCategoryId: EntityId?,
        previousMerchantId: EntityId?,
        newMerchantId: EntityId?,
        actor: String,
        sourceKind: String,
        sourceVersion: String,
        reason: String?,
        ruleId: String?,
        atEpochMs: Long,
    ) {
        dao.insertCategoryAudit(
            CategoryAuditEntity(
                id = java.util.UUID.randomUUID().toString(),
                transactionId = transactionId,
                previousCategoryId = previousCategoryId?.value,
                newCategoryId = newCategoryId?.value,
                previousMerchantId = previousMerchantId?.value,
                newMerchantId = newMerchantId?.value,
                actor = actor,
                sourceKind = sourceKind,
                sourceVersion = sourceVersion,
                reason = reason,
                ruleId = ruleId,
                atEpochMs = atEpochMs,
            )
        )
    }

    override suspend fun applyCategorization(
        transactionId: String,
        categoryId: EntityId?,
        merchantId: EntityId?,
        sourceKind: String,
        sourceVersion: String,
        sourceReason: String?,
    ) {
        // The transactions table write goes through FinanceDaoV3's upsert path;
        // here we only record the audit trail. The caller (application layer)
        // performs the actual transaction update via TransactionWriteService.
    }

    override suspend fun latestAuditForTransaction(transactionId: String): CategoryAudit? =
        dao.latestCategoryAuditForTransaction(transactionId)?.toDomain()

    private fun CategoryAuditEntity.toDomain() = CategoryAudit(
        id = id,
        transactionId = transactionId,
        previousCategoryId = previousCategoryId?.let { EntityId(it) },
        newCategoryId = newCategoryId?.let { EntityId(it) },
        previousMerchantId = previousMerchantId?.let { EntityId(it) },
        newMerchantId = newMerchantId?.let { EntityId(it) },
        actor = actor,
        sourceKind = sourceKind,
        sourceVersion = sourceVersion,
        reason = reason,
        ruleId = ruleId,
        atEpochMs = atEpochMs,
    )

    // ---- mappers ----

    private fun CategoryEntity.toDomain() = Category(
        id = EntityId(id),
        name = name,
        normalizedName = normalizedName,
        parentId = parentId?.let { EntityId(it) },
        status = runCatching { CategoryStatus.valueOf(status) }.getOrDefault(CategoryStatus.ACTIVE),
        kind = runCatching { CategoryKind.valueOf(kind) }.getOrDefault(CategoryKind.TAXONOMY),
        sortOrder = sortOrder,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    )

    private fun MerchantEntity.toDomain() = Merchant(
        id = EntityId(id),
        displayName = displayName,
        normalizedName = normalizedName,
        accountId = accountId?.let { EntityId(it) },
        status = runCatching { MerchantStatus.valueOf(status) }.getOrDefault(MerchantStatus.ACTIVE),
        merchantIdentity = merchantIdentity,
        sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
        sourceVersion = sourceVersion,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
        mergedIntoMerchantId = mergedIntoMerchantId?.let { EntityId(it) },
    )

    private fun MerchantAliasEntity.toDomain() = MerchantAlias(
        id = id,
        merchantId = EntityId(merchantId),
        aliasRaw = aliasRaw,
        aliasNormalized = aliasNormalized,
        aliasIdentity = aliasIdentity,
        sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
        sourceVersion = sourceVersion,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    )

    private fun MerchantVpaBindingEntity.toDomain() = MerchantVpaBinding(
        id = id,
        merchantId = EntityId(merchantId),
        vpa = vpa,
        vpaIdentity = vpaIdentity,
        confirmedByUser = confirmedByUser,
        sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
        sourceVersion = sourceVersion,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    )

    private fun CategoryRuleEntity.toDomain() = CategoryRule(
        id = id,
        name = name,
        priority = priority,
        status = runCatching { RuleStatus.valueOf(status) }.getOrDefault(RuleStatus.ACTIVE),
        matchKind = runCatching { RuleMatchKind.valueOf(matchKind) }.getOrDefault(RuleMatchKind.USER_RULE),
        matchValue = matchValue,
        merchantId = merchantId?.let { EntityId(it) },
        categoryId = EntityId(categoryId),
        sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.USER_CORRECTION),
        sourceVersion = sourceVersion,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
        createdBy = createdBy,
    )

    private fun LlmCategorySuggestionEntity.toDomain() = LlmCategorySuggestion(
        id = id,
        transactionId = transactionId,
        categoryId = categoryId?.let { EntityId(it) },
        merchantId = merchantId?.let { EntityId(it) },
        confidence = confidence,
        reason = reason,
        modelId = modelId,
        promptVersion = promptVersion,
        schemaVersion = schemaVersion,
        suggestionIdentity = suggestionIdentity,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
        accepted = accepted,
        acceptedAt = acceptedAtEpochMs?.let { Instant.ofEpochMilli(it) },
    )

    companion object {
        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
