package eg.masroof.ask

import eg.masroof.core.analytics.AnalyticsCategory
import eg.masroof.data.db.seededCategoryId

/** An account as the resolver needs it. */
data class AskAccount(
    val id: String,
    val label: String,
    val institution: String?,
)

/** Matches the words of a question to the user's own categories, accounts and merchants. Pure Kotlin. */
internal object SubjectResolver {
    /** Synonym phrase -> the keys of the default categories (`R__seed_reference.sql`) it stands for. */
    private val synonyms: Map<String, List<String>> =
        buildMap {
            fun add(
                keys: List<String>,
                vararg phrases: String,
            ) = phrases.forEach { put(ArabicText.tokens(it).joinToString(" "), keys) }
            add(
                listOf("groceries", "dining"),
                "food",
                "eating",
                "meals",
                "اكل",
                "الاكل",
                "اكلات",
                "اكل وشرب",
                "طعام",
                "akl",
                "okl",
                "food and drinks",
            )
            add(
                listOf("groceries"),
                "grocery",
                "groceries",
                "supermarket",
                "super market",
                "بقاله",
                "سوبر ماركت",
                "سوبرماركت",
                "bakala",
                "ba2ala",
            )
            add(
                listOf("dining"),
                "restaurants",
                "restaurant",
                "dining",
                "dining out",
                "eating out",
                "cafes",
                "cafe",
                "coffee",
                "مطاعم",
                "مطعم",
                "كافيهات",
                "كافيه",
                "قهوه",
                "mat3am",
                "mata3em",
            )
            add(listOf("car.fuel"), "fuel", "petrol", "gasoline", "benzin", "banzin", "بنزين", "وقود", "سولار")
            add(listOf("car"), "car", "cars", "عربيه", "سياره", "عربية", "3arabeya")
            add(listOf("transport"), "transport", "transportation", "taxi", "commute", "مواصلات", "تاكسي", "mwasalat", "mowasalat")
            add(listOf("bills"), "bills", "bill", "utilities", "فواتير", "فاتوره", "fawateer", "fawatir")
            add(listOf("bills.electricity"), "electricity", "electric", "كهربا", "كهرباء", "كهربه")
            add(listOf("bills.water"), "water", "مياه", "ميه")
            add(listOf("bills.internet"), "internet", "wifi", "انترنت", "نت")
            add(listOf("bills.mobile"), "mobile", "phone", "موبايل", "رصيد", "mobile bills")
            add(listOf("rent"), "rent", "housing", "rent and housing", "ايجار", "سكن", "igar", "egar")
            add(listOf("health"), "health", "doctor", "doctors", "pharmacy", "medicine", "صحه", "دكتور", "صيدليه", "دوا", "علاج", "se7a")
            add(listOf("education"), "education", "school", "tuition", "courses", "تعليم", "مدرسه", "دروس", "ta3leem", "ta3lim")
            add(listOf("kids"), "kids", "children", "baby", "اطفال", "عيال", "اولاد", "atfal")
            add(listOf("clothing"), "clothes", "clothing", "fashion", "ملابس", "هدوم", "malabes", "hodoom")
            add(listOf("personal-care"), "personal care", "beauty", "haircut", "عنايه شخصيه", "حلاق", "تجميل")
            add(listOf("household"), "household", "household items", "home items", "مستلزمات المنزل", "مستلزمات البيت")
            add(listOf("electronics"), "electronics", "gadgets", "الكترونيات")
            add(listOf("entertainment"), "entertainment", "fun", "movies", "cinema", "ترفيه", "سينما", "خروج", "tarfeeh")
            add(listOf("subscriptions"), "subscriptions", "subscription", "اشتراكات", "اشتراك", "eshterak", "eshtrakat")
            add(listOf("travel"), "travel", "trips", "trip", "flights", "سفر", "رحله", "safar")
            add(listOf("gifts"), "gifts", "gift", "charity", "donations", "هدايا", "هديه", "صدقه", "صدقات", "تبرع", "hadaya")
            add(listOf("fees"), "fees", "charges", "bank fees", "رسوم", "مصاريف بنكيه", "roosoom")
            add(listOf("pets"), "pets", "pet", "حيوانات", "حيوانات اليفه", "قطه", "كلب")
            add(listOf("other"), "other", "others", "misc", "miscellaneous", "اخري", "متنوع")
        }

    /** Arabic or Franco spellings of common Egyptian merchants -> the Latin spelling cards and SMS usually show. */
    private val merchantAliases: Map<String, String> =
        buildMap {
            fun add(
                latin: String,
                vararg names: String,
            ) = names.forEach { put(ArabicText.tokens(it).joinToString(" "), latin) }
            add("talabat", "طلبات", "talabat", "talabaat")
            add("amazon", "امازون", "أمازون")
            add("uber", "اوبر", "أوبر")
            add("careem", "كريم", "careem")
            add("carrefour", "كارفور", "carrefour")
            add("spinneys", "سبينيس", "spinneys")
            add("noon", "نون")
            add("jumia", "جوميا")
            add("vodafone", "فودافون")
            add("orange", "اورانج", "أورانج")
            add("etisalat", "اتصالات", "etisalat")
            add("we", "وي")
            add("mcdonalds", "ماكدونالدز", "ماك", "mcdonald")
            add("starbucks", "ستاربكس")
            add("kfc", "كنتاكي")
            add("hardees", "هارديز")
            add("elmenus", "المنيو", "elmenus")
            add("instashop", "انستاشوب")
            add("breadfast", "بريدفاست")
            add("rabbit", "رابيت")
            add("seoudi", "سعودي")
            add("metro", "مترو")
            add("hyperone", "هايبر وان", "هايبر")
            add("fawry", "فوري")
            add("instapay", "انستاباي")
            add("netflix", "نتفليكس")
            add("spotify", "سبوتيفاي")
            add("apple", "ابل")
            add("google", "جوجل")
        }

    /** The comparable form of a name: folded, article-stripped and with known Arabic merchant spellings mapped to Latin. */
    fun canon(token: String): String = merchantAliases[token] ?: token

    fun canonTokens(text: String): List<String> {
        val tokens = ArabicText.tokens(text)
        // A two-word alias ("hyper one") is looked up before single words.
        val joined = tokens.joinToString(" ")
        merchantAliases[joined]?.let { return listOf(it) }
        return tokens.map(::canon)
    }

    /** Whether the merchant label on an entry is the merchant named by [key] (every word of the key is a word of the label). */
    fun merchantMatches(
        merchant: String?,
        keyTokens: List<String>,
    ): Boolean {
        if (merchant.isNullOrBlank() || keyTokens.isEmpty()) return false
        val words = canonTokens(merchant).toSet()
        return keyTokens.all { it in words }
    }

    /** The default categories a subject stands for, or null. Sub-categories roll up into their parent. */
    private fun categoryFromSynonym(
        tokens: List<String>,
        categories: List<AnalyticsCategory>,
    ): AskSubject.Category? {
        val phrase = tokens.joinToString(" ")
        val keys = synonyms[phrase] ?: return null
        val byId = categories.filter { !it.archived }.associateBy { it.id }
        val picked = keys.mapNotNull { byId[seededCategoryId(it)] }
        if (picked.isEmpty()) return null
        return categorySubject(picked, categories)
    }

    private fun categorySubject(
        picked: List<AnalyticsCategory>,
        categories: List<AnalyticsCategory>,
    ): AskSubject.Category {
        val ids = LinkedHashSet<String>()
        for (c in picked) {
            ids.add(c.id)
            if (c.parentId == null) categories.filter { it.parentId == c.id }.forEach { ids.add(it.id) }
        }
        return AskSubject.Category(ids, picked.joinToString(" + ") { it.nameEn }, picked.joinToString(" + ") { it.nameAr })
    }

    private fun nameTokens(c: AnalyticsCategory): List<Set<String>> = listOf(c.nameEn, c.nameAr).map { ArabicText.tokens(it).toSet() }

    private fun categoryFromName(
        tokens: List<String>,
        categories: List<AnalyticsCategory>,
    ): AskSubject.Category? {
        val live = categories.filter { !it.archived }
        val exact = live.filter { c -> nameTokens(c).any { it == tokens.toSet() } }
        val loose = live.filter { c -> nameTokens(c).any { it.containsAll(tokens) } }
        val hit = (exact.ifEmpty { loose }).sortedBy { if (it.parentId == null) 0 else 1 }.firstOrNull() ?: return null
        return categorySubject(listOf(hit), categories)
    }

    private fun accountFrom(
        tokens: List<String>,
        accounts: List<AskAccount>,
    ): AskSubject.Account? {
        val hits =
            accounts.filter { a ->
                listOfNotNull(a.label, a.institution).any { name -> canonTokens(name).containsAll(tokens) }
            }
        if (hits.isEmpty()) return null
        return AskSubject.Account(hits.map { it.id }.toSet(), hits.first().label)
    }

    /**
     * Matches [text] to, in this order, a default-category synonym, a category name, an account and a merchant seen in
     * [merchants]. Null when nothing matches.
     */
    fun resolve(
        text: String,
        categories: List<AnalyticsCategory>,
        accounts: List<AskAccount>,
        merchants: List<String>,
    ): AskSubject? {
        val tokens = ArabicText.tokens(text)
        if (tokens.isEmpty()) return null
        categoryFromSynonym(tokens, categories)?.let { return it }
        categoryFromName(tokens, categories)?.let { return it }
        accountFrom(tokens, accounts)?.let { return it }
        val key = canonTokens(text)
        val hits = merchants.filter { merchantMatches(it, key) }
        if (hits.isEmpty()) return null
        return AskSubject.Merchant(key.joinToString(" "), hits.minOf { it.trim() })
    }

    /**
     * "groceries and fuel": when [text] as a whole is nothing, each word is resolved on its own. Two or more subjects, or an
     * empty list when any word matches nothing (a word is never silently dropped).
     */
    fun resolveParts(
        text: String,
        categories: List<AnalyticsCategory>,
        accounts: List<AskAccount>,
        merchants: List<String>,
    ): List<AskSubject> {
        val tokens = ArabicText.tokens(text)
        if (tokens.size < 2) return emptyList()
        val parts = tokens.map { resolve(it, categories, accounts, merchants) }
        return if (parts.all { it != null }) parts.filterNotNull().distinct() else emptyList()
    }

    /** The [limit] top-level categories whose names are closest to [text], for "did you mean". */
    fun closest(
        text: String,
        categories: List<AnalyticsCategory>,
        limit: Int,
    ): List<AnalyticsCategory> {
        val wanted = ArabicText.tokens(text)
        if (wanted.isEmpty()) return emptyList()
        return categories
            .filter { !it.archived && it.parentId == null }
            .sortedWith(compareBy({ distance(wanted, it) }, { it.nameEn }))
            .take(limit)
    }

    private fun distance(
        wanted: List<String>,
        c: AnalyticsCategory,
    ): Int {
        val names = nameTokens(c).flatten()
        return wanted.minOf { w ->
            names.minOfOrNull { n -> if (n.startsWith(w) || w.startsWith(n)) 0 else levenshtein(w, n) }
                ?: Int.MAX_VALUE
        }
    }

    internal fun levenshtein(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
