package com.example.fintrack.data.repository

import com.example.fintrack.data.db.AuditLogEntryEntity
import com.example.fintrack.data.db.AppLockStateEntity
import com.example.fintrack.data.db.FinanceDaoV9
import com.example.fintrack.data.db.SettingsProfileEntity
import com.example.fintrack.domain.model.AuditRetention
import com.example.fintrack.domain.service.AuditLogSink
import com.example.fintrack.domain.service.AppLockSink
import com.example.fintrack.domain.service.SettingsProfileSink
import com.example.fintrack.domain.model.SettingsProfile
import java.util.UUID

/**
 * Stage 11 P24 — Room-backed privacy/security sinks over the v11 tables.
 */
class RoomSettingsProfileRepository(
    private val dao: FinanceDaoV9,
) : SettingsProfileSink {

    override suspend fun upsert(profile: SettingsProfile): Boolean {
        dao.upsertSettingsProfile(
            SettingsProfileEntity(
                id = profile.id,
                name = profile.name,
                version = profile.version,
                aiInterpretationEnabled = profile.aiInterpretationEnabled,
                autoCategorizationEnabled = profile.autoCategorizationEnabled,
                exportIncludeRawEvidence = profile.exportIncludeRawEvidence,
                appLockEnabled = profile.appLockEnabled,
                featureFlagsJson = MiniJson.encodeMap(
                    profile.featureFlags.mapValues { if (it.value) 1.0 else 0.0 },
                ),
                createdAtEpochMs = profile.createdAtEpochMs,
                updatedAtEpochMs = profile.updatedAtEpochMs,
            ),
        )
        return true
    }

    override suspend fun all(): List<SettingsProfile> =
        dao.allSettingsProfiles().map { it.toDomain() }

    override suspend fun findByName(name: String): SettingsProfile? =
        dao.findProfileByName(name)?.toDomain()

    override suspend fun delete(id: String) {
        dao.deleteSettingsProfile(id)
    }

    private fun SettingsProfileEntity.toDomain() = SettingsProfile(
        id = id,
        name = name,
        version = version,
        aiInterpretationEnabled = aiInterpretationEnabled,
        autoCategorizationEnabled = autoCategorizationEnabled,
        exportIncludeRawEvidence = exportIncludeRawEvidence,
        appLockEnabled = appLockEnabled,
        featureFlags = MiniJson.decodeMap(featureFlagsJson)
            .mapValues { it.value >= 0.5 },
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

class RoomAuditLogRepository(
    private val dao: FinanceDaoV9,
) : AuditLogSink {

    override suspend fun append(
        actionClass: String,
        entityId: String?,
        actor: String,
        detail: String?,
        atEpochMs: Long,
        retention: AuditRetention,
    ) {
        dao.insertAuditLogEntry(
            AuditLogEntryEntity(
                id = UUID.randomUUID().toString(),
                actionClass = actionClass,
                entityId = entityId,
                actor = actor,
                detail = detail,
                atEpochMs = atEpochMs,
                retention = retention.name,
            ),
        )
    }

    override suspend fun recent(limit: Int): List<AuditLogSink.AuditEntry> =
        dao.recentAuditEntries(limit).map {
            AuditLogSink.AuditEntry(
                id = it.id,
                actionClass = it.actionClass,
                entityId = it.entityId,
                actor = it.actor,
                detail = it.detail,
                atEpochMs = it.atEpochMs,
                retention = it.retention,
            )
        }

    override suspend fun prune(nowEpochMs: Long): Int = dao.pruneAuditLog(nowEpochMs)
}

class RoomAppLockRepository(
    private val dao: FinanceDaoV9,
) : AppLockSink {

    override suspend fun state(): AppLockSink.AppLockState? =
        dao.appLockState()?.let {
            AppLockSink.AppLockState(
                enabled = it.enabled,
                state = it.state,
                lastUnlockedAtEpochMs = it.lastUnlockedAtEpochMs,
            )
        }

    override suspend fun setEnabled(enabled: Boolean, nowEpochMs: Long) {
        val current = dao.appLockState()
        dao.upsertAppLockState(
            AppLockStateEntity(
                id = 1,
                enabled = enabled,
                lastUnlockedAtEpochMs = current?.lastUnlockedAtEpochMs ?: 0L,
                state = if (enabled) "LOCKED" else "DISABLED",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    override suspend fun markUnlocked(nowEpochMs: Long) {
        val current = dao.appLockState()
        dao.upsertAppLockState(
            AppLockStateEntity(
                id = 1,
                enabled = current?.enabled ?: true,
                lastUnlockedAtEpochMs = nowEpochMs,
                state = "UNLOCKED",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    override suspend fun markLocked(nowEpochMs: Long) {
        val current = dao.appLockState()
        dao.upsertAppLockState(
            AppLockStateEntity(
                id = 1,
                enabled = current?.enabled ?: true,
                lastUnlockedAtEpochMs = current?.lastUnlockedAtEpochMs ?: 0L,
                state = "LOCKED",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}
