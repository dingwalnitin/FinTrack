package com.example.fintrack.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * ContentResolver-backed SmsSource. Reads from Telephony.Sms in newest-first
 * order using the provider id as a stable cursor. The user SMS database is
 * read-only here — we never write to or delete from it.
 *
 * Permission state is checked at construction (cached) and re-checked per
 * page read so revocation is observed non-destructively.
 */
class ContentResolverSmsSource(
    private val context: Context,
) : SmsSource {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

    override suspend fun readPage(afterProviderId: Long?, limit: Int): List<RawSms> =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "limit must be positive" }
            if (!hasPermission()) return@withContext emptyList()

            // No cursor yet (first page of a fresh backfill) must omit the
            // WHERE clause entirely — _ID is always positive, so a sentinel
            // like "_ID < -1" would match nothing and silently short-circuit
            // the whole backfill as "complete" without reading any history.
            val selection = if (afterProviderId != null) SELECT_AFTER else null
            val selectionArgs = if (afterProviderId != null) arrayOf(afterProviderId.toString()) else null

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                "${Telephony.Sms._ID} DESC LIMIT $limit",
            ) ?: return@withContext emptyList()

            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                buildList {
                    while (c.moveToNext()) {
                        add(
                            RawSms(
                                providerId = c.getLong(idIdx),
                                sender = if (c.isNull(addrIdx)) null else c.getString(addrIdx),
                                timestampEpochMs = c.getLong(dateIdx),
                                body = c.getString(bodyIdx) ?: "",
                            )
                        )
                    }
                }
            }
        }

    override fun pages(afterProviderId: Long?, pageSize: Int): Flow<List<RawSms>> = flow {
        var cursor = afterProviderId
        while (true) {
            val page = readPage(cursor, pageSize)
            if (page.isEmpty()) break
            emit(page)
            cursor = page.last().providerId
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        // _ID < cursor means "older than the cursor"; we walk newest first, so
        // we ask for rows strictly older than the current cursor.
        const val SELECT_AFTER = "${Telephony.Sms._ID} < ?"
    }
}
