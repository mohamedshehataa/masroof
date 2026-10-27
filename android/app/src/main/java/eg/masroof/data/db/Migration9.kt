package eg.masroof.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 -> v9 (ADR-0009 D3 "Expand"): additive only. Nothing is dropped or rewritten; old columns keep their values. One literal
 * statement per change: SQL is never assembled from strings (semgrep CWE-89 rules).
 */
val MIGRATION_8_9: Migration =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expense ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expense ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expense ADD COLUMN deletedAtMillis INTEGER")
            db.execSQL("ALTER TABLE expense ADD COLUMN timeKnown INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE expense ADD COLUMN paymentMethod TEXT")
            db.execSQL("ALTER TABLE expense ADD COLUMN instrumentLast4 TEXT")
            db.execSQL("ALTER TABLE expense ADD COLUMN paidByMemberId TEXT")
            db.execSQL("ALTER TABLE expense ADD COLUMN serverId TEXT")
            // A row cached from the server keeps the server's id; a queued manual entry (pending = 1) has none yet.
            db.execSQL(
                """
                UPDATE expense SET createdAtMillis = occurredAtMillis, updatedAtMillis = occurredAtMillis,
                serverId = CASE WHEN pending = 0 THEN id ELSE NULL END
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_occurredAtMillis` ON `expense` (`occurredAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_categoryId` ON `expense` (`categoryId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expense_serverId` ON `expense` (`serverId`)")

            db.execSQL("ALTER TABLE category ADD COLUMN parentId TEXT")
            db.execSQL("ALTER TABLE category ADD COLUMN color TEXT")
            db.execSQL("ALTER TABLE category ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE category ADD COLUMN system INTEGER NOT NULL DEFAULT 0")

            db.execSQL("ALTER TABLE account ADD COLUMN kind TEXT")
            db.execSQL("ALTER TABLE account ADD COLUMN institution TEXT")
            db.execSQL("ALTER TABLE account ADD COLUMN last4 TEXT")
            db.execSQL("ALTER TABLE account ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `member` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `receipt` (`id` TEXT NOT NULL, `expenseId` TEXT, `fileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `pageCount` INTEGER,
                `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`expenseId`) REFERENCES `expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_expenseId` ON `receipt` (`expenseId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_sha256` ON `receipt` (`sha256`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `expense_source` (`id` TEXT NOT NULL, `expenseId` TEXT NOT NULL,
                `channel` TEXT NOT NULL, `localSourceId` TEXT NOT NULL, `receivedAtMillis` INTEGER NOT NULL,
                `senderId` TEXT, `instrumentLast4` TEXT, PRIMARY KEY(`id`),
                FOREIGN KEY(`expenseId`) REFERENCES `expense`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_source_expenseId` ON `expense_source` (`expenseId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_expense_source_localSourceId` ON `expense_source` (`localSourceId`)",
            )
        }
    }

/** Every shipped migration, in order. There is no destructive fallback for v2..v8. */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
