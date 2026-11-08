package eg.masroof.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10 (STORY-201, ADR-0009 D3 "Expand"): additive only, for every EPIC-18 feature at once. New columns on existing tables
 * are nullable or carry a default, so every existing row stays valid and unchanged; the new tables start empty. Nothing is
 * dropped or rewritten. One literal statement per change: SQL is never assembled from strings (semgrep CWE-89 rules). The SQL
 * mirrors Room's exported `10.json` exactly, which `MigrationTestHelper` validates.
 */
val MIGRATION_9_10: Migration =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE category ADD COLUMN emoji TEXT")
            db.execSQL("ALTER TABLE expense ADD COLUMN excludedFromTotals INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE account ADD COLUMN creditLimitMinor INTEGER")
            db.execSQL("ALTER TABLE account ADD COLUMN statementDay INTEGER")
            db.execSQL("ALTER TABLE account ADD COLUMN dueDay INTEGER")
            db.execSQL("ALTER TABLE account ADD COLUMN emoji TEXT")

            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `account_balance` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL,
                    `balanceMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `atMillis` INTEGER NOT NULL, `source`
                    TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`)
                    REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                ),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_account_balance_accountId` ON `account_balance` (`accountId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `budget` (`id` TEXT NOT NULL, `categoryId` TEXT, `period` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `rollover` INTEGER NOT NULL DEFAULT 0,
                    `alertThresholds` TEXT NOT NULL DEFAULT '50,80,100', `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES
                    `category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                    """,
                ),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_budget_categoryId` ON `budget` (`categoryId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `installment_plan` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `provider`
                    TEXT NOT NULL, `categoryId` TEXT, `accountId` TEXT, `currency` TEXT NOT NULL, `purchasePriceMinor`
                    INTEGER, `downPaymentMinor` INTEGER NOT NULL, `monthlyAmountMinor` INTEGER NOT NULL, `paymentCount`
                    INTEGER NOT NULL, `firstDueAtMillis` INTEGER NOT NULL, `reminderDaysBefore` INTEGER NOT NULL,
                    `status` TEXT NOT NULL, `notes` TEXT, `createdAtMillis` INTEGER NOT NULL, `completedAtMillis`
                    INTEGER, `payoutAtMillis` INTEGER, `payoutAmountMinor` INTEGER, `payoutReceivedAtMillis` INTEGER,
                    PRIMARY KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `installment_payment` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `seq`
                    INTEGER NOT NULL, `dueAtMillis` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `paidAtMillis`
                    INTEGER, `expenseId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES
                    `installment_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`expenseId`) REFERENCES
                    `expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                    """,
                ),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_payment_planId_seq` ON `installment_payment` (`planId`, `seq`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_payment_expenseId` ON `installment_payment` (`expenseId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `subscription` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT
                    NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `cadence` TEXT NOT NULL,
                    `nextRenewalAtMillis` INTEGER NOT NULL, `categoryId` TEXT, `accountId` TEXT, `merchantKey` TEXT,
                    `reminderDaysBefore` INTEGER NOT NULL, `active` INTEGER NOT NULL DEFAULT 1, `notes` TEXT,
                    `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `savings_goal` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT,
                    `targetMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetAtMillis` INTEGER, `sortOrder`
                    INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, `completedAtMillis` INTEGER,
                    `archivedAtMillis` INTEGER, PRIMARY KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `goal_movement` (`id` TEXT NOT NULL, `goalId` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL, `atMillis` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`id`), FOREIGN
                    KEY(`goalId`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                ),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_movement_goalId` ON `goal_movement` (`goalId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `asset` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL,
                    `currency` TEXT NOT NULL, `valueMinor` INTEGER, `karat` INTEGER, `principalMinor` INTEGER,
                    `annualRateBp` INTEGER, `startAtMillis` INTEGER, `maturityAtMillis` INTEGER, `goalId` TEXT,
                    `hiddenFromTotals` INTEGER NOT NULL DEFAULT 0, `archived` INTEGER NOT NULL DEFAULT 0,
                    `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN
                    KEY(`goalId`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                    """,
                ),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asset_goalId` ON `asset` (`goalId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `asset_lot` (`id` TEXT NOT NULL, `assetId` TEXT NOT NULL, `side` TEXT NOT
                    NULL, `quantityMilli` INTEGER NOT NULL, `totalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL,
                    `atMillis` INTEGER NOT NULL, `expenseId` TEXT, `note` TEXT, PRIMARY KEY(`id`), FOREIGN
                    KEY(`assetId`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN
                    KEY(`expenseId`) REFERENCES `expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                    """,
                ),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asset_lot_assetId` ON `asset_lot` (`assetId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_asset_lot_expenseId` ON `asset_lot` (`expenseId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `metal_price` (`id` TEXT NOT NULL, `metal` TEXT NOT NULL, `karat` INTEGER
                    NOT NULL, `pricePerGramMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `atMillis` INTEGER NOT
                    NULL, PRIMARY KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `merchant_rule` (`id` TEXT NOT NULL, `merchantKey` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN
                    KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                ),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_rule_merchantKey` ON `merchant_rule` (`merchantKey`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_merchant_rule_categoryId` ON `merchant_rule` (`categoryId`)",
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `debt` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL,
                    `direction` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `dueAtMillis`
                    INTEGER, `settledAtMillis` INTEGER, `notes` TEXT, `createdAtMillis` INTEGER NOT NULL, PRIMARY
                    KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                oneLine(
                    """
                    CREATE TABLE IF NOT EXISTS `net_worth_snapshot` (`id` TEXT NOT NULL, `cairoDate` TEXT NOT NULL,
                    `assetsMinor` INTEGER NOT NULL, `debtsMinor` INTEGER NOT NULL, `netMinor` INTEGER NOT NULL,
                    `currency` TEXT NOT NULL, `breakdown` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, PRIMARY
                    KEY(`id`))
                    """,
                ),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_net_worth_snapshot_cairoDate` ON `net_worth_snapshot` (`cairoDate`)",
            )
        }
    }

/** A statement written over several lines is joined with single spaces, so it stays byte-identical to Room's `createSql`. */
private fun oneLine(sql: String): String = sql.trimIndent().replace("\n", " ")
