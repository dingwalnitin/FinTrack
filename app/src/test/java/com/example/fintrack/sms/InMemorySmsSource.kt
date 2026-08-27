package com.example.fintrack.sms

/**
 * In-memory test fake for [SmsSource]. Returns a fixed list of rows in
 * newest-first order (highest providerId first). When [revoked] is true, the
 * source reports no permission and returns no rows — matching the
 * ContentResolverSmsSource behavior under revocation.
 */
class InMemorySmsSource(
    private val rows: List<RawSms>,
    private var revoked: Boolean = false,
) : SmsSource {

    private val sortedNewestFirst = rows.sortedByDescending { it.providerId }

    override suspend fun readPage(afterProviderId: Long?, limit: Int): List<RawSms> {
        if (revoked || !hasPermission()) return emptyList()
        val tail = if (afterProviderId == null) sortedNewestFirst
        else sortedNewestFirst.filter { it.providerId < afterProviderId }
        return tail.take(limit)
    }

    override fun pages(afterProviderId: Long?, pageSize: Int): kotlinx.coroutines.flow.Flow<List<RawSms>> =
        kotlinx.coroutines.flow.flow {
            var cursor = afterProviderId
            while (true) {
                val page = readPage(cursor, pageSize)
                if (page.isEmpty()) break
                emit(page)
                cursor = page.last().providerId
            }
        }

    override fun hasPermission(): Boolean = !revoked

    fun setRevoked(revoked: Boolean) { this.revoked = revoked }
}
