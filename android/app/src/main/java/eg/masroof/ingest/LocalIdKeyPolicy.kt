package eg.masroof.ingest

/*
 * The local-id key availability policy (STORY-182, docs/design/STORY-182-local-id-key-policy.md). Pure: no Android, no Tink.
 * decide() is the oracle of the driver; lost() and record() are the bounded escalation. Nothing here is logged.
 */

enum class Keyset { LOCAL, SESSION }

enum class FailKind { CORRUPT, WEAK }

sealed interface BuildObs {
    data object NotTried : BuildObs

    data object Built : BuildObs

    /** [attempts] is the number of build attempts in THIS call (1 or 2). */
    data class Failed(
        val kind: FailKind,
        val attempts: Int,
    ) : BuildObs
}

/** BAD_TAG only under a FRESH build (a keyset that built is plain software AES-GCM: a rejection is certain). */
enum class OpenObs { NOT_TRIED, OPENED, BAD_TAG }

data class Obs(
    val localEntry: Boolean,
    val legacyEntry: Boolean,
    val localBuild: BuildObs = BuildObs.NotTried,
    val localOpen: OpenObs = OpenObs.NOT_TRIED,
    val sessionBuild: BuildObs = BuildObs.NotTried,
    val legacyOpen: OpenObs = OpenObs.NOT_TRIED,
    val recreatedThisCall: Boolean = false,
)

data class Episode(
    val since: Long,
    val fails: Int,
    val corrupt: Int,
    val starts: Int,
    val lastFailAt: Long,
    val lastToken: Long,
    val corroborated: Boolean,
)

data class Health(
    val local: Episode?,
    val session: Episode?,
)

sealed interface Action {
    data object BuildLocal : Action

    data object OpenLocal : Action

    data object BuildSession : Action

    data object OpenLegacy : Action

    data object UseLocal : Action

    data object MoveLegacy : Action

    data class Mint(
        val replaces: Boolean,
    ) : Action

    data object RecreateLocal : Action

    data object DropLegacy : Action

    data class Hold(
        val keyset: Keyset,
    ) : Action
}

internal const val CORRUPT_LIMIT = 2
internal const val MIN_GAP_MILLIS = 60_000L
internal const val HOUR_MILLIS = 60L * 60 * 1000
internal const val CORROBORATED_FAILS = 3
internal const val UNCORROBORATED_FAILS = 5
internal const val UNCORROBORATED_AGE_MILLIS = 6 * HOUR_MILLIS
internal const val UNCORROBORATED_MAX_AGE_MILLIS = 24 * HOUR_MILLIS
internal const val UNCORROBORATED_STARTS = 2
internal const val KEY_HOLD_DELAY_MILLIS = 15L * 60 * 1000
internal const val MAX_STEPS = 16
private const val MAX_CAUSES = 5
private const val EPISODE_FIELDS = 8

/** True when the evidence says [keyset] is lost for good (§3.3 of the design). */
fun lost(
    keyset: Keyset,
    e: Episode?,
    now: Long,
): Boolean {
    if (e == null) return false
    if (e.corrupt >= CORRUPT_LIMIT) return true
    val age = now - e.since
    return if (keyset == Keyset.SESSION || e.corroborated) {
        e.fails >= CORROBORATED_FAILS && age >= HOUR_MILLIS
    } else {
        e.fails >= UNCORROBORATED_FAILS &&
            ((age >= UNCORROBORATED_AGE_MILLIS && e.starts >= UNCORROBORATED_STARTS) || age >= UNCORROBORATED_MAX_AGE_MILLIS)
    }
}

/** The next step. First matching row wins (design §3.2); total over [Obs]. */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // a flat table, one row per condition
fun decide(
    o: Obs,
    h: Health,
    now: Long,
): Action {
    val lb = o.localBuild
    if (lb == BuildObs.NotTried) return Action.BuildLocal
    if (lb is BuildObs.Failed) {
        return when {
            lb.attempts == 1 -> Action.BuildLocal
            lost(Keyset.LOCAL, h.local, now) && !o.recreatedThisCall -> Action.RecreateLocal
            else -> Action.Hold(Keyset.LOCAL)
        }
    }
    if (o.localEntry && o.localOpen == OpenObs.NOT_TRIED) return Action.OpenLocal
    if (o.localEntry && o.localOpen == OpenObs.OPENED) return Action.UseLocal
    // local is gone: no entry, or a bad tag under a fresh build
    if (!o.legacyEntry) return Action.Mint(replaces = o.localEntry && o.localOpen == OpenObs.BAD_TAG)
    val sb = o.sessionBuild
    if (sb == BuildObs.NotTried) return Action.BuildSession
    if (sb is BuildObs.Failed) {
        return when {
            sb.attempts == 1 -> Action.BuildSession
            lost(Keyset.SESSION, h.session, now) -> Action.DropLegacy
            else -> Action.Hold(Keyset.SESSION)
        }
    }
    return when (o.legacyOpen) {
        OpenObs.NOT_TRIED -> Action.OpenLegacy
        OpenObs.OPENED -> Action.MoveLegacy
        OpenObs.BAD_TAG -> Action.DropLegacy
    }
}

/** Counts one failure into the episode (§3.4). Rate limited to one counted failure per minute; a clock moved back keeps the counts. */
fun record(
    e: Episode?,
    kind: FailKind,
    corroborates: Boolean,
    now: Long,
    token: Long,
): Episode {
    val base = e ?: Episode(since = now, fails = 0, corrupt = 0, starts = 0, lastFailAt = 0, lastToken = 0, corroborated = false)
    val counts = base.fails == 0 || now - base.lastFailAt >= MIN_GAP_MILLIS || now < base.lastFailAt
    return base.copy(
        since = minOf(base.since, now),
        fails = if (counts) base.fails + 1 else base.fails,
        lastFailAt = if (counts) now else base.lastFailAt,
        corrupt = if (kind == FailKind.CORRUPT) base.corrupt + 1 else base.corrupt,
        starts = if (token != base.lastToken) base.starts + 1 else base.starts,
        lastToken = token,
        corroborated = base.corroborated || corroborates,
    )
}

/** CORRUPT: the stored keyset string does not parse (depends only on its content). Everything else is WEAK. */
fun classify(e: Throwable): FailKind =
    if (generateSequence(e) { it.cause }.take(MAX_CAUSES).any { isParseFailure(it) }) FailKind.CORRUPT else FailKind.WEAK

private fun isParseFailure(t: Throwable): Boolean =
    t is java.io.CharConversionException || t.javaClass.name.contains("InvalidProtocolBuffer")

fun encodeEpisode(e: Episode): String =
    "v1|${e.since}|${e.fails}|${e.corrupt}|${e.starts}|${e.lastFailAt}|${e.lastToken}|${if (e.corroborated) 1 else 0}"

/** Strict: anything that is not exactly the v1 record parses as no episode (a damaged record can only delay escalation). */
fun decodeEpisode(s: String?): Episode? {
    val p = s?.split('|') ?: return null
    if (p.size != EPISODE_FIELDS || p[0] != "v1") return null
    val since = p[1].toLongOrNull()
    val fails = p[2].toIntOrNull()
    val corrupt = p[3].toIntOrNull()
    val starts = p[4].toIntOrNull()
    val lastFail = p[5].toLongOrNull()
    val token = p[6].toLongOrNull()
    val c = p[7]
    val ok =
        since != null &&
            fails != null &&
            corrupt != null &&
            starts != null &&
            lastFail != null &&
            token != null &&
            fails >= 0 &&
            corrupt >= 0 &&
            starts >= 0 &&
            (c == "0" || c == "1")
    return if (ok) Episode(since, fails, corrupt, starts, lastFail, token, c == "1") else null
}
