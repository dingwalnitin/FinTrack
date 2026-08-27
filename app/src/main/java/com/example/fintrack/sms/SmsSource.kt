package com.example.fintrack.sms

import kotlinx.coroutines.flow.Flow

/**
 * Read-only source of raw SMS evidence. Hides the platform (ContentResolver vs
 * in-memory test fakes) so the backfill worker is fully unit-testable.
 *
 * Implementations:
 *  - ContentResolverSmsSource reads from Telephony.Sms when READ_SMS is granted.
 *  - FakeSmsSource is for tests.
 */
interface SmsSource {

    /**
     * Paged read. Page size is implementation-defined; callers may request up
     * to [limit] rows newer than [afterProviderId] (exclusive). When
     * [afterProviderId] is null, the call returns the most recent rows first.
     */
    suspend fun readPage(afterProviderId: Long?, limit: Int): List<RawSms>

    /** Cold flow over pages in newest-first order; emits pages until exhausted. */
    fun pages(afterProviderId: Long?, pageSize: Int): Flow<List<RawSms>>

    /** True when the platform reports permission is granted for the source. */
    fun hasPermission(): Boolean
}
