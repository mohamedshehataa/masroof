package eg.masroof

/**
 * The independent reference of AnswerGeneratedTest: a ledger of real-world money kept from the abstract operations alone. It never
 * reads a link, a reconcile result or a stored row. Its rules are the user-visible ones: a transfer between own accounts is neither
 * spending nor income and moves money once; an app-added side lives while the entry it was added for lives; a linked refund nets
 * against its purchase in the purchase's category; a broken pair or link leaves each entry as the bank reported it.
 */
internal class AnswerLedger {
    class Ev(
        val id: String,
        /** T = captured transfer out, I = income, P = purchase, A = ATM withdrawal. */
        val kind: Char,
        val sign: Int,
        var amount: Long,
        val acct: String,
        var cat: String?,
        var at: Long,
        var cur: String = "EGP",
        var alive: Boolean = true,
    )

    /** A transfer pair ('X') or a tracked ATM withdrawal ('A'): [src] is the entry the user answered, [joined] the bank's other entry. */
    class Pair(
        val src: String,
        val joined: String?,
        val kind: Char,
        val target: String,
        var insAlive: Boolean = true,
        var insWithSrc: Boolean = false,
    )

    class Refund(
        val r: String,
        val p: String,
        var delWithP: Boolean = false,
    )

    val events = LinkedHashMap<String, Ev>()
    val pairs = mutableListOf<Pair>()
    val refunds = mutableListOf<Refund>()
    val plain = mutableSetOf<String>()
    var include = false

    fun reset() {
        events.clear()
        pairs.clear()
        refunds.clear()
        plain.clear()
        include = false
        add("out", 'T', -1, 500_000, "cib", null, ago(1))
        add("in", 'I', 1, 500_000, "nbe", null, ago(0, 23))
        add("t2", 'T', -1, 70_000, "nbe", null, ago(2))
        add("i2", 'I', 1, 30_000, "cib", null, ago(3))
        add("r", 'I', 1, 45_000, "cib", null, ago(1))
        add("p1", 'P', -1, 120_000, "cib", "cat-shop", ago(5))
        add("p2", 'P', -1, 80_000, "nbe", "cat-shop", ago(4))
        add("w1", 'A', -1, 200_000, "cib", null, ago(2))
        add("e1", 'P', -1, 25_000, "nbe", "cat-elec", ago(6))
    }

    private fun add(
        id: String,
        kind: Char,
        sign: Int,
        amount: Long,
        acct: String,
        cat: String?,
        at: Long,
    ) {
        events[id] = Ev(id, kind, sign, amount, acct, cat, at)
    }

    private fun alive(id: String?) = id != null && events[id]?.alive == true

    // ---- pair and refund state

    fun insertedAlive(p: Pair) = p.joined == null && p.insAlive && alive(p.src)

    /** Both sides are live: the card is settled and the rows are locked for money edits. */
    fun bothLive(p: Pair) = alive(p.src) && (if (p.joined != null) alive(p.joined) else p.insAlive)

    fun typed(p: Pair): Boolean {
        if (!bothLive(p)) return false
        val s = events.getValue(p.src)
        val j = p.joined?.let { events.getValue(it) } ?: return true
        return s.amount == j.amount && s.cur == j.cur
    }

    fun bothLive(l: Refund) = alive(l.r) && alive(l.p)

    fun typed(l: Refund): Boolean {
        if (!bothLive(l)) return false
        val r = events.getValue(l.r)
        val p = events.getValue(l.p)
        return r.cur == p.cur && r.amount <= p.amount
    }

    fun locked(id: String) =
        pairs.any { bothLive(it) && (it.src == id || it.joined == id) } || refunds.any { bothLive(it) && (it.r == id || it.p == id) }

    fun settled(id: String) = id in plain || locked(id)

    // ---- the numbers

    private fun egp(e: Ev) = e.cur == "EGP"

    fun income(): Long =
        events.values
            .filter { it.kind == 'I' && it.alive && egp(it) }
            .filter { e -> pairs.none { typed(it) && e.sign > 0 && (it.src == e.id || it.joined == e.id) } }
            .filter { e -> refunds.none { typed(it) && it.r == e.id } }
            .sumOf { it.amount }

    fun spending(): Long {
        val purchases = events.values.filter { it.kind == 'P' && it.alive && egp(it) }.sumOf { it.amount }
        val refunded = refunds.filter { typed(it) && egp(events.getValue(it.r)) }.sumOf { events.getValue(it.r).amount }
        val cash =
            if (!include) {
                0L
            } else {
                events.values
                    .filter { it.kind == 'A' && it.alive && egp(it) }
                    .filter { e -> pairs.none { typed(it) && it.src == e.id } }
                    .sumOf { it.amount }
            }
        return purchases - refunded + cash
    }

    fun categoryTotals(): Map<String, Long> {
        val totals = HashMap<String, Long>()
        events.values.filter { it.kind == 'P' && it.alive && egp(it) }.forEach { e ->
            e.cat?.let { totals.merge(it, e.amount, Long::plus) }
        }
        refunds.filter { typed(it) && egp(events.getValue(it.r)) }.forEach { l ->
            val cat = events.getValue(l.p).cat
            if (cat != null) totals.merge(cat, -events.getValue(l.r).amount, Long::plus)
        }
        return totals
    }

    fun balance(account: String): Long {
        var b = mapOf("cib" to 2_000_000L, "nbe" to 1_000_000L, "cash" to 0L).getValue(account)
        events.values.filter { it.alive && egp(it) && it.acct == account }.forEach { b += it.sign * it.amount }
        pairs.filter { insertedAlive(it) && it.target == account }.forEach {
            val s = events.getValue(it.src)
            if (egp(s)) b += -s.sign * s.amount
        }
        return b
    }

    fun worth() = listOf("cib", "nbe", "cash").sumOf { balance(it) }

    /** Ids of the rows that must be TRANSFER_IN now: the receiving side of every typed pair. */
    fun transferInIds(): Set<String> {
        val ids = HashSet<String>()
        for (p in pairs.filter { typed(it) }) {
            val s = events.getValue(p.src)
            when {
                p.joined != null -> ids += if (s.sign > 0) p.src else p.joined
                s.sign < 0 -> ids += "${p.src}:pair"
                else -> ids += p.src
            }
        }
        return ids
    }
}
