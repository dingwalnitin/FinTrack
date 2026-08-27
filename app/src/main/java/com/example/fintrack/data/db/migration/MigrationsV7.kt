package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 -> v7 (Stage 5, P11 — transfers / refunds / fees / cash / manual entry).
 *
 * Additive only — no existing data is mutated.
 *
 *  - transactions.transferGroupId: shared UUID between the two sides of a
 *    two-sided transfer so the detail / list screens can show both legs of
 *    a transfer as a single logical event.
 *  - refund_links: durable link from a refund event to the original expense
 *    it refunds. Refund is a separate financial event; the original is
 *    preserved untouched. Idempotency on (refundedEventId, refundEventId)
 *    and on the stable refundIdentity hash.
 *  - transaction_links: generic parent/child link used for fees (parent
 *    charge + separate fee event). The same table is also used to surface
 *    "linked refunds" in the detail screen.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- transfers: shared identity column on transactions ----
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `transferGroupId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_transferGroupId` ON `transactions` (`transferGroupId`)")

        // ---- refunds ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `refund_links` (
               `id` TEXT NOT NULL,
               `refundedEventId` TEXT NOT NULL,
               `refundEventId` TEXT NOT NULL,
               `kind` TEXT NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `sourceReason` TEXT,
               `refundIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_refund_links_refundedEventId_refundEventId` ON `refund_links` (`refundedEventId`, `refundEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_links_refundedEventId` ON `refund_links` (`refundedEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_links_refundEventId` ON `refund_links` (`refundEventId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_refund_links_refundIdentity` ON `refund_links` (`refundIdentity`)")

        // ---- transaction links (fees, parent/child) ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_links` (
               `id` TEXT NOT NULL,
               `parentEventId` TEXT NOT NULL,
               `childEventId` TEXT NOT NULL,
               `role` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `sourceReason` TEXT,
               `linkIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_links_parent_child_role` ON `transaction_links` (`parentEventId`, `childEventId`, `role`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_links_parentEventId` ON `transaction_links` (`parentEventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_links_childEventId` ON `transaction_links` (`childEventId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_links_linkIdentity` ON `transaction_links` (`linkIdentity`)")
    }
}
