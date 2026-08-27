package com.example.fintrack.domain.service

/**
 * Stage 11 P24 #2 / #5 — secret storage and app-lock lifecycle.
 *
 * The lock secret NEVER lives in Room or in exports. [SecretVault] is the
 * seam: on Android it is backed by Android Keystore (a key that never
 * leaves secure hardware); tests use an in-memory fake.
 */
interface SecretVault {
    /** Store a new lock secret, replacing any previous one. */
    suspend fun storeLockSecret(secret: ByteArray)

    /** Verify a candidate against the stored secret. Constant-time compare. */
    suspend fun verifyLockSecret(candidate: ByteArray): Boolean

    /** Remove the stored secret (disabling app lock). */
    suspend fun clearLockSecret()

    val hasLockSecret: Boolean
}

/** Pure-JVM in-memory implementation for tests and non-secure contexts. */
class InMemorySecretVault : SecretVault {
    private var stored: ByteArray? = null

    override val hasLockSecret: Boolean get() = stored != null

    override suspend fun storeLockSecret(secret: ByteArray) {
        require(secret.isNotEmpty()) { "lock secret must not be empty" }
        stored = RedactionEngine.sha256(secret.toString(Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
    }

    override suspend fun verifyLockSecret(candidate: ByteArray): Boolean {
        val expected = stored ?: return false
        val candidateHash = RedactionEngine.sha256(candidate.toString(Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
        return constantTimeEquals(expected, candidateHash)
    }

    override suspend fun clearLockSecret() {
        stored = null
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Stage 11 P24 #5 — app-lock state machine.
 *
 * Lifecycle:
 *  - enable → stores a secret via [SecretVault], marks state LOCKED;
 *  - unlock → verifies the candidate; success records lastUnlockedAt;
 *  - grace period: within [gracePeriodMs] of a successful unlock the app
 *    may resume without re-prompting (normal offline use stays unfragile);
 *  - disable requires a successful verification first.
 */
class AppLockService(
    private val vault: SecretVault,
    private val sink: AppLockSink,
    private val clock: () -> Long,
    private val gracePeriodMs: Long = 30_000L,
) {

    sealed interface UnlockResult {
        data object Unlocked : UnlockResult
        data class Denied(val reason: String) : UnlockResult
    }

    suspend fun currentState(): AppLockSink.AppLockState? = sink.state()

    suspend fun enable(secret: CharArray): Result<Unit> {
        if (secret.isEmpty()) return Result.failure(IllegalArgumentException("PIN must not be empty"))
        if (secret.size < 4) return Result.failure(IllegalArgumentException("PIN must be at least 4 characters"))
        vault.storeLockSecret(secret.concatToString().toByteArray(Charsets.UTF_8))
        sink.setEnabled(true, clock())
        sink.markLocked(clock())
        return Result.success(Unit)
    }

    suspend fun unlock(candidate: CharArray): UnlockResult {
        val state = sink.state()
        if (state == null || !state.enabled) return UnlockResult.Denied("app lock is not enabled")
        return if (vault.verifyLockSecret(candidate.concatToString().toByteArray(Charsets.UTF_8))) {
            sink.markUnlocked(clock())
            UnlockResult.Unlocked
        } else {
            UnlockResult.Denied("incorrect PIN")
        }
    }

    /**
     * Whether the UI may skip the lock screen right now (grace window).
     * Normal offline usage inside the grace window is never interrupted.
     */
    suspend fun shouldShowLock(): Boolean {
        val state = sink.state() ?: return false
        if (!state.enabled) return false
        val sinceUnlock = clock() - state.lastUnlockedAtEpochMs
        return state.state != "UNLOCKED" || sinceUnlock > gracePeriodMs
    }

    suspend fun disable(candidate: CharArray): Result<Unit> {
        when (val r = unlock(candidate)) {
            is UnlockResult.Unlocked -> {
                vault.clearLockSecret()
                sink.setEnabled(false, clock())
                return Result.success(Unit)
            }
            is UnlockResult.Denied -> return Result.failure(IllegalStateException(r.reason))
        }
    }
}
