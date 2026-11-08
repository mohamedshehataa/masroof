package eg.masroof.data.backup

/** One Room table in the backup and the file it is written to. */
class BackupTable(
    val name: String,
    val file: String,
)

/**
 * What the backup holds and what it never holds (ADR-0009 D5, REQ-OPS-176). A test compares both lists with the tables of the real
 * database, so a table added later fails the build until it is put on one list on purpose.
 */
object BackupTables {
    val INCLUDED: List<BackupTable> =
        listOf(
            BackupTable("expense", "data/expenses.jsonl"),
            BackupTable("category", "data/categories.jsonl"),
            BackupTable("account", "data/accounts.jsonl"),
            BackupTable("member", "data/members.jsonl"),
            BackupTable("budget", "data/budgets.jsonl"),
            BackupTable("expense_source", "data/expense_sources.jsonl"),
            BackupTable("receipt", "data/receipts.jsonl"),
            BackupTable("learned_template", "data/learned_templates.jsonl"),
            BackupTable("account_balance", "data/account_balances.jsonl"),
            BackupTable("installment_plan", "data/installment_plans.jsonl"),
            BackupTable("installment_payment", "data/installment_payments.jsonl"),
            BackupTable("subscription", "data/subscriptions.jsonl"),
            BackupTable("savings_goal", "data/savings_goals.jsonl"),
            BackupTable("goal_movement", "data/goal_movements.jsonl"),
            BackupTable("asset", "data/assets.jsonl"),
            BackupTable("asset_lot", "data/asset_lots.jsonl"),
            BackupTable("metal_price", "data/metal_prices.jsonl"),
            BackupTable("merchant_rule", "data/merchant_rules.jsonl"),
            BackupTable("debt", "data/debts.jsonl"),
            BackupTable("net_worth_snapshot", "data/net_worth_snapshots.jsonl"),
        )

    /**
     * Never leave the phone: `review_item` holds raw message text, `captured_tx` and the two outboxes are transient pipeline state
     * (the outboxes and server data are frozen server-import leftovers).
     */
    val EXCLUDED: Set<String> = setOf("review_item", "captured_tx", "outbox", "parsed_outbox")

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "data/settings.json"
    const val LOCAL_ID_KEY = "keys/local-id.key"
    const val RECEIPTS_DIR = "receipts/"
}

/** A receipt row as the backup needs it: the file is `receipts/<id>.<ext>` in the ZIP and [fileName] under the receipts directory. */
class BackupReceiptRef(
    val id: String,
    val fileName: String,
    /** The size the receipt row claims; used only to estimate progress. The manifest carries the measured size. */
    val sizeBytes: Long = 0L,
)

/** A tiny JSON writer: strings, integers, booleans, null, objects and arrays; amounts are only ever written as integers. */
internal object BackupJson {
    fun string(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val pair = c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()
            when {
                pair -> sb.append(c).append(value[++i])
                // A lone surrogate cannot be written as UTF-8 (it would become `?`): escape it so the round trip is exact.
                c.isSurrogate() -> sb.append("\\u").append("%04x".format(c.code))
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }

    fun value(v: Any?): String =
        when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Int, is Long -> v.toString()
            is String -> string(v)
            else -> error("unsupported JSON value type")
        }

    fun obj(entries: List<Pair<String, String>>): String = entries.joinToString(",", "{", "}") { "${string(it.first)}:${it.second}" }

    fun array(items: List<String>): String = items.joinToString(",", "[", "]")
}
