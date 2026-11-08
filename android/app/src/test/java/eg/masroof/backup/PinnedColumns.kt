package eg.masroof.backup

import eg.masroof.data.db.DB_VERSION
import java.io.File

/**
 * The columns of every included table at schema version 10. The backup dumps `SELECT *`, so a migration that adds a column (for example one
 * holding raw message text) changes what leaves the phone: this list must be updated on purpose, after checking the new column against the
 * backup rules (REQ-OPS-176).
 */
object PinnedColumns {
    val expected: Map<String, List<String>> =
        mapOf(
            "expense" to
                listOf(
                    "id",
                    "amountMinor",
                    "currency",
                    "occurredAtMillis",
                    "merchant",
                    "categoryId",
                    "accountId",
                    "notes",
                    "pending",
                    "failed",
                    "source",
                    "type",
                    "status",
                    "createdAtMillis",
                    "updatedAtMillis",
                    "deletedAtMillis",
                    "timeKnown",
                    "paymentMethod",
                    "instrumentLast4",
                    "paidByMemberId",
                    "serverId",
                    "excludedFromTotals",
                ),
            "category" to listOf("id", "nameEn", "nameAr", "sortOrder", "parentId", "color", "archived", "system", "emoji"),
            "account" to
                listOf(
                    "id",
                    "label",
                    "currency",
                    "kind",
                    "institution",
                    "last4",
                    "archived",
                    "creditLimitMinor",
                    "statementDay",
                    "dueDay",
                    "emoji",
                ),
            "learned_template" to listOf("id", "sender", "shape", "kind", "amountSlot", "last4Slot", "createdAtMillis"),
            "member" to listOf("id", "displayName", "sortOrder"),
            "receipt" to listOf("id", "expenseId", "fileName", "mimeType", "sizeBytes", "sha256", "pageCount", "createdAtMillis"),
            "expense_source" to listOf("id", "expenseId", "channel", "localSourceId", "receivedAtMillis", "senderId", "instrumentLast4"),
            "account_balance" to listOf("id", "accountId", "balanceMinor", "currency", "atMillis", "source", "createdAtMillis"),
            "budget" to
                listOf(
                    "id",
                    "categoryId",
                    "period",
                    "amountMinor",
                    "currency",
                    "rollover",
                    "alertThresholds",
                    "createdAtMillis",
                    "updatedAtMillis",
                ),
            "installment_plan" to
                listOf(
                    "id",
                    "title",
                    "provider",
                    "categoryId",
                    "accountId",
                    "currency",
                    "purchasePriceMinor",
                    "downPaymentMinor",
                    "monthlyAmountMinor",
                    "paymentCount",
                    "firstDueAtMillis",
                    "reminderDaysBefore",
                    "status",
                    "notes",
                    "createdAtMillis",
                    "completedAtMillis",
                    "payoutAtMillis",
                    "payoutAmountMinor",
                    "payoutReceivedAtMillis",
                ),
            "installment_payment" to listOf("id", "planId", "seq", "dueAtMillis", "amountMinor", "paidAtMillis", "expenseId"),
            "subscription" to
                listOf(
                    "id",
                    "kind",
                    "name",
                    "amountMinor",
                    "currency",
                    "cadence",
                    "nextRenewalAtMillis",
                    "categoryId",
                    "accountId",
                    "merchantKey",
                    "reminderDaysBefore",
                    "active",
                    "notes",
                    "createdAtMillis",
                ),
            "savings_goal" to
                listOf(
                    "id",
                    "name",
                    "emoji",
                    "targetMinor",
                    "currency",
                    "targetAtMillis",
                    "sortOrder",
                    "createdAtMillis",
                    "completedAtMillis",
                    "archivedAtMillis",
                ),
            "goal_movement" to listOf("id", "goalId", "amountMinor", "atMillis", "note"),
            "asset" to
                listOf(
                    "id",
                    "kind",
                    "name",
                    "currency",
                    "valueMinor",
                    "karat",
                    "principalMinor",
                    "annualRateBp",
                    "startAtMillis",
                    "maturityAtMillis",
                    "goalId",
                    "hiddenFromTotals",
                    "archived",
                    "createdAtMillis",
                    "updatedAtMillis",
                ),
            "asset_lot" to listOf("id", "assetId", "side", "quantityMilli", "totalMinor", "currency", "atMillis", "expenseId", "note"),
            "metal_price" to listOf("id", "metal", "karat", "pricePerGramMinor", "currency", "atMillis"),
            "merchant_rule" to listOf("id", "merchantKey", "categoryId", "createdAtMillis"),
            "debt" to
                listOf(
                    "id",
                    "name",
                    "kind",
                    "direction",
                    "amountMinor",
                    "currency",
                    "dueAtMillis",
                    "settledAtMillis",
                    "notes",
                    "createdAtMillis",
                ),
            "net_worth_snapshot" to
                listOf("id", "cairoDate", "assetsMinor", "debtsMinor", "netMinor", "currency", "breakdown", "createdAtMillis"),
        )

    /**
     * The columns the exported Room schema of [version] (the current database version by default) declares, for the included tables only.
     * A missing schema file fails: a version bump without its exported schema must not pass quietly.
     */
    fun fromSchema(
        version: Int = DB_VERSION,
        dir: File = File("schemas/eg.masroof.data.db.AppDatabase"),
    ): Map<String, List<String>> {
        val file = File(dir, "$version.json")
        check(file.isFile) { "no exported schema for database version $version: ${file.path}" }
        val text = file.readText()
        val result = LinkedHashMap<String, List<String>>()
        // Each entity is one "tableName" ... "fields": [ ... ] ... block; the fields list ends at the first "]".
        for (entity in text.split("\"tableName\": \"").drop(1)) {
            val table = entity.substringBefore('"')
            if (table !in expected.keys) continue
            val fields = entity.substringAfter("\"fields\": [").substringBefore("]")
            result[table] = Regex("\"columnName\": \"([^\"]+)\"").findAll(fields).map { it.groupValues[1] }.toList()
        }
        return result
    }
}
