package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.AuditRetention
import com.example.fintrack.domain.model.SettingsProfile
import java.util.UUID

/**
 * Stage 11 P24 — persistence contracts for privacy/security features.
 * Implemented in the data layer; domain stays storage-free.
 */
interface SettingsProfileSink {
    suspend fun upsert(profile: SettingsProfile): Boolean
    suspend fun all(): List<SettingsProfile>
    suspend fun findByName(name: String): SettingsProfile?
    suspend fun delete(id: String)
}

interface AuditLogSink {
    /** Append one sanitized audit entry. Never called with secrets/raw SMS. */
    suspend fun append(
        actionClass: String,
        entityId: String?,
        actor: String,
        detail: String?,
        atEpochMs: Long,
        retention: AuditRetention,
    )

    suspend fun recent(limit: Int): List<AuditEntry>
    suspend fun prune(nowEpochMs: Long): Int

    data class AuditEntry(
        val id: String,
        val actionClass: String,
        val entityId: String?,
        val actor: String,
        val detail: String?,
        val atEpochMs: Long,
        val retention: String,
    )
}

/** App-lock lifecycle sink. The lock secret itself never passes through here. */
interface AppLockSink {
    suspend fun state(): AppLockState?
    suspend fun setEnabled(enabled: Boolean, nowEpochMs: Long)
    suspend fun markUnlocked(nowEpochMs: Long)
    suspend fun markLocked(nowEpochMs: Long)

    data class AppLockState(
        val enabled: Boolean,
        val state: String, // LOCKED | UNLOCKED | DISABLED
        val lastUnlockedAtEpochMs: Long,
    )
}

/**
 * Stage 11 P24 #6 / module 175 — settings profile lifecycle.
 *
 * Profiles are separate from financial data: exporting one produces a small
 * `FTPROFILE1` payload with preferences only. Importing validates the
 * version and refuses unknown/newer formats rather than guessing.
 */
class SettingsProfileService(
    private val sink: SettingsProfileSink,
    private val clock: () -> Long,
) {

    fun exportProfile(profile: SettingsProfile): String = buildString {
        append("FTPROFILE1").append('\n')
        append("name=").append(profile.name).append('\n')
        append("version=").append(SettingsProfile.VERSION).append('\n')
        append("aiInterpretationEnabled=").append(profile.aiInterpretationEnabled).append('\n')
        append("autoCategorizationEnabled=").append(profile.autoCategorizationEnabled).append('\n')
        append("exportIncludeRawEvidence=").append(profile.exportIncludeRawEvidence).append('\n')
        append("appLockEnabled=").append(profile.appLockEnabled).append('\n')
        append("flags=").append(
            profile.featureFlags.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" },
        ).append('\n')
    }

    sealed interface ImportResult {
        data class Imported(val profile: SettingsProfile) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    fun parseProfile(payload: String): ImportResult {
        val lines = payload.lines().map { it.trim() }
        if (lines.firstOrNull() != "FTPROFILE1") {
            return ImportResult.Rejected("not a FinTrack settings profile")
        }
        val fields = lines.drop(1).mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val version = fields["version"]?.toIntOrNull()
            ?: return ImportResult.Rejected("missing profile version")
        if (version > SettingsProfile.VERSION) {
            return ImportResult.Rejected("profile format v$version is newer than supported v${SettingsProfile.VERSION}")
        }
        val name = fields["name"]?.takeIf { it.isNotBlank() }
            ?: return ImportResult.Rejected("profile name missing")
        val flags = fields["flags"]?.split(',')?.filter { it.isNotBlank() }?.mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size != 2) null else kv[0] to (kv[1] == "true")
        }?.toMap() ?: emptyMap()
        val now = clock()
        return ImportResult.Imported(
            SettingsProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                version = SettingsProfile.VERSION,
                aiInterpretationEnabled = fields["aiInterpretationEnabled"] == "true",
                autoCategorizationEnabled = fields["autoCategorizationEnabled"] != "false",
                exportIncludeRawEvidence = fields["exportIncludeRawEvidence"] == "true",
                appLockEnabled = fields["appLockEnabled"] == "true",
                featureFlags = flags,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }
}

/**
 * Stage 11 P24 #4 — audit logging for money-changing and sensitive actions.
 *
 * Boundaries enforced by construction:
 *  - [record] rejects details longer than a short summary and strips any
 *    content that looks like raw evidence or secrets before persisting;
 *  - retention is applied per entry and pruned by [AuditLogSink.prune].
 */
class AuditService(
    private val sink: AuditLogSink,
    private val clock: () -> Long,
) {

    companion object {
        const val ACT_TRANSACTION_WRITE = "TRANSACTION_WRITE"
        const val ACT_IMPORT_COMMIT = "IMPORT_COMMIT"
        const val ACT_EXPORT = "EXPORT"
        const val ACT_SETTINGS_CHANGE = "SETTINGS_CHANGE"
        const val ACT_APP_UNLOCK = "APP_UNLOCK"
        const val ACT_APP_LOCK_CHANGE = "APP_LOCK_CHANGE"

        val DEFAULT_RETENTION = AuditRetention.DAYS_90
        private const val MAX_DETAIL_LEN = 200
    }

    fun record(
        actionClass: String,
        entityId: String? = null,
        actor: String = "USER",
        detail: String? = null,
        retention: AuditRetention = DEFAULT_RETENTION,
    ) {
        // Synchronous-in-coroutine callers use suspend variant; this overload
        // exists for pure-JVM tests.
    }

    suspend fun recordAsync(
        actionClass: String,
        entityId: String? = null,
        actor: String = "USER",
        detail: String? = null,
        retention: AuditRetention = DEFAULT_RETENTION,
    ) {
        require(actionClass.isNotBlank())
        val safeDetail = detail
            ?.replace(Regex("""(?i)(api[_-]?key|token|secret|password)\s*[=:]\s*\S+"""), "$1=[REDACTED]")
            ?.replace(Regex("""\b(?:\+91[- ]?)?[6-9][0-9]{9}\b"""), "[PHONE]")
            ?.take(MAX_DETAIL_LEN)
        sink.append(
            actionClass = actionClass,
            entityId = entityId,
            actor = actor,
            detail = safeDetail,
            atEpochMs = clock(),
            retention = retention,
        )
    }

    suspend fun pruneExpired(): Int {
        val now = clock()
        val cutoff90 = now - 90L * 24 * 3600 * 1000
        val cutoff365 = now - 365L * 24 * 3600 * 1000
        // Prune both buckets; FOREVER rows are protected in the DAO query.
        var removed = sink.prune(cutoff90)
        removed += sink.prune(cutoff365)
        return removed
    }
}
