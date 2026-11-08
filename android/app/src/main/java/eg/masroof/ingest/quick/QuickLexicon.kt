package eg.masroof.ingest.quick

import java.time.DayOfWeek

/**
 * Word tables for the quick-entry parser (STORY-200). Every key is already folded (أ/إ/آ -> ا, ى -> ي, ة -> ه, lower case) so
 * a lookup is a plain map access. Nothing here reads or keeps user text.
 */
internal object QuickLexicon {
    /** A spoken or typed number word: [cls] is one of the HUNDRED / TEN / UNIT bits, [major] its value in whole currency units. */
    class NumWord(
        val cls: Int,
        val major: Long,
    )

    const val HUNDRED = 1
    const val TEN = 2
    const val UNIT = 4

    private fun words(
        cls: Int,
        vararg entries: Pair<Long, String>,
    ): Map<String, NumWord> =
        buildMap {
            for ((value, spellings) in entries) {
                for (s in spellings.split(' ')) put(s, NumWord(cls, value))
            }
        }

    val NUMBER_WORDS: Map<String, NumWord> =
        words(
            UNIT,
            1L to "واحد وحده",
            2L to "اتنين اثنين اثنان",
            3L to "تلاته ثلاثه تلات ثلاث تلت",
            4L to "اربعه اربع",
            5L to "خمسه خمس",
            6L to "سته ست",
            7L to "سبعه سبع",
            8L to "تمانيه ثمانيه تمنيه ثمان تمان",
            9L to "تسعه تسع",
        ) +
            words(
                TEN,
                10L to "عشره عشر",
                20L to "عشرين",
                30L to "تلاتين ثلاثين",
                40L to "اربعين",
                50L to "خمسين",
                60L to "ستين",
                70L to "سبعين",
                80L to "تمانين ثمانين",
                90L to "تسعين",
            ) +
            // Teens take the TEN and UNIT slots at once (no "fifteen five").
            words(
                TEN or UNIT,
                11L to "حداشر احدعشر",
                12L to "اتناشر اثناعشر",
                13L to "تلتاشر تلاتاشر",
                14L to "اربعتاشر اربعطاشر",
                15L to "خمستاشر خمسطاشر",
                16L to "ستاشر سطاشر",
                17L to "سبعتاشر سبعطاشر",
                18L to "تمنتاشر تمانتاشر ثمانتاشر",
                19L to "تسعتاشر تسعطاشر",
            ) +
            words(
                HUNDRED,
                100L to "ميه ميت مئه مايه مائه",
                200L to "ميتين مئتين مائتين",
                300L to "تلتميه تلاتميه ثلاثميه تلتمائه ثلاثمائه",
                400L to "ربعميه اربعميه اربعمائه",
                500L to "خمسميه خمسمائه",
                600L to "ستميه ستمائه",
                700L to "سبعميه سبعمائه",
                800L to "تمنميه تمانميه ثمانميه ثمانمائه",
                900L to "تسعميه تسعمائه",
            )

    /** [times]: 1 = "thousand" (x1, or the digits before it), 2 = the dual ("الفين"), 0 = the plural ("آلاف", needs a count). */
    class ScaleWord(
        val factor: Long,
        val times: Int,
    )

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L

    val SCALE_WORDS: Map<String, ScaleWord> =
        mapOf(
            "الف" to ScaleWord(THOUSAND, 1),
            "الفين" to ScaleWord(THOUSAND, 2),
            "الاف" to ScaleWord(THOUSAND, 0),
            "تلاف" to ScaleWord(THOUSAND, 0),
            "تلافات" to ScaleWord(THOUSAND, 0),
            "k" to ScaleWord(THOUSAND, 1),
            "thousand" to ScaleWord(THOUSAND, 1),
            "مليون" to ScaleWord(MILLION, 1),
            "مليونين" to ScaleWord(MILLION, 2),
            "ملايين" to ScaleWord(MILLION, 0),
            "million" to ScaleWord(MILLION, 1),
        )

    /** Scale words that may open a number on their own ("ألف جنيه"); "k" and the plurals need a count before them. */
    val SCALE_STARTERS = setOf("الف", "الفين", "مليون", "مليونين", "thousand", "million")

    val HALF = setOf("نص", "نصف")
    val QUARTER = setOf("ربع")

    private fun split(words: String): List<String> = words.split(' ').filter { it.isNotEmpty() }

    /** Currency words, symbols and ISO codes -> ISO-4217. "pound" is the Egyptian pound; sterling needs "gbp" or the symbol. */
    val CURRENCIES: Map<String, String> =
        buildMap {
            for (w in split("pound pounds le egp ج جنيه جنيها جنيهات")) put(w, "EGP")
            for (w in split("dollar dollars usd \$ دولار دولارات")) put(w, "USD")
            for (w in split("euro euros eur € يورو")) put(w, "EUR")
            for (w in split("riyal riyals sar ريال ريالات")) put(w, "SAR")
            for (w in split("dirham dirhams aed درهم دراهم")) put(w, "AED")
            for (w in split("gbp £ sterling")) put(w, "GBP")
            put("qar", "QAR")
        }

    val INCOME_WORDS: Set<String> =
        setOf(
            "received",
            "receive",
            "salary",
            "income",
            "earned",
            "bonus",
            "payday",
            "deposit",
            "قبضت",
            "قبض",
            "مرتب",
            "راتب",
            "استلمت",
            "ايراد",
            "ايرادات",
            "بونص",
            "مكافاه",
            "حافز",
            "ارباح",
        )

    val EXPENSE_VERBS: Set<String> = setOf("paid", "pay", "spent", "spend", "bought", "buy", "دفعت", "دفع", "صرفت", "اشتريت", "حولت")

    /** Verbs, fillers and prepositions: never a merchant, never in the note. */
    val STOP_WORDS: Set<String> =
        setOf(
            "spent",
            "spend",
            "paid",
            "pay",
            "bought",
            "buy",
            "got",
            "for",
            "on",
            "in",
            "at",
            "from",
            "the",
            "a",
            "an",
            "to",
            "of",
            "and",
            "with",
            "i",
            "my",
            "was",
            "is",
            "received",
            "receive",
            "last",
            "me",
            "دفعت",
            "دفع",
            "صرفت",
            "صرف",
            "اشتريت",
            "جبت",
            "في",
            "عند",
            "من",
            "علي",
            "على",
            "ب",
            "ل",
            "و",
            "ده",
            "دي",
            "عشان",
            "قبضت",
            "استلمت",
            "ال",
            "اول",
        )

    /** The word before a merchant name: "at talabat", "في كارفور". */
    val MERCHANT_PREPOSITIONS: Set<String> = setOf("at", "from", "في", "عند")

    private fun kw(
        category: String,
        words: String,
    ): List<Pair<String, String>> = split(words).map { it to category }

    /** Category keyword -> reference key of the seeded category (`car.fuel`, `groceries` ...). Merchants map here too. */
    val CATEGORY_KEYWORDS: Map<String, String> =
        (
            kw("car.fuel", "fuel gas benzine benzin petrol gasoline diesel بنزين بنزن بانزين سولار وقود") +
                kw("car.parking", "parking ركنه انتظار") +
                kw("car.maintenance", "mechanic maintenance garage ميكانيكي صيانه جراج") +
                kw("car.insurance", "insurance تامين") +
                kw("car", "car عربيه سياره") +
                kw("transport", "uber careem taxi metro bus microbus indriver swvl transport transportation") +
                kw("transport", "مواصلات اوبر كاريم تاكسي مترو ميكروباص اتوبيس") +
                kw(
                    "dining",
                    "talabat elmenus restaurant cafe coffee coffees lunch dinner breakfast pizza burger starbucks mcdonald kfc food",
                ) +
                kw("dining", "طلبات المنيوس مطعم كافيه قهوه غدا عشا فطار ستاربكس اكل") +
                kw(
                    "groceries",
                    "groceries grocery carrefour spinneys supermarket hypermarket hyper seoudi خضار بقاله كارفور سبينس سوبرماركت هايبر",
                ) +
                kw(
                    "subscriptions",
                    "netflix shahid spotify anghami subscription subscriptions youtube نتفلكس شاهد سبوتيفاي انغامي اشتراك",
                ) +
                kw("rent", "rent housing ايجار سكن") +
                kw("bills.electricity", "electricity electric كهرباء") +
                kw("bills.water", "water مياه") +
                kw("bills.gas", "غاز") +
                kw("bills.internet", "internet wifi انترنت") +
                kw("bills.mobile", "mobile vodafone orange etisalat recharge فودافون اورنج اتصالات رصيد موبايل") +
                kw("bills", "bill bills فاتوره فواتير") +
                kw("health", "pharmacy doctor medicine hospital clinic صيدليه دكتور دوا دواء مستشفي كشف تحاليل") +
                kw("education", "school tuition course university مدرسه كورس دروس جامعه") +
                kw("kids", "kids toys toy diapers اطفال حفاضات لعبه") +
                kw("clothing", "clothes clothing shoes ملابس هدوم جزمه حذاء") +
                kw("personal-care", "haircut barber salon حلاق كوافير صالون") +
                kw("household", "household cleaning furniture ikea تنظيف مستلزمات") +
                kw("electronics", "electronics laptop charger الكترونيات") +
                kw("entertainment", "cinema movie movies game games gaming سينما فيلم") +
                kw("travel", "flight hotel trip tickets طيران فندق رحله سفر") +
                kw("gifts", "gift gifts charity donation zakat هديه صدقه تبرع زكاه") +
                kw("fees", "fee fees commission رسوم عموله") +
                kw("pets", "pet pets vet dog بيطري كلب")
        ).toMap()

    /** Names that are a merchant on their own. */
    val MERCHANTS: Set<String> =
        setOf(
            "talabat",
            "elmenus",
            "uber",
            "careem",
            "carrefour",
            "spinneys",
            "netflix",
            "shahid",
            "spotify",
            "anghami",
            "starbucks",
            "mcdonald",
            "kfc",
            "ikea",
            "noon",
            "amazon",
            "jumia",
            "vodafone",
            "orange",
            "etisalat",
            "seoudi",
            "swvl",
            "indriver",
            "كارفور",
            "طلبات",
            "المنيوس",
            "اوبر",
            "كاريم",
            "سبينس",
            "نتفلكس",
            "ستاربكس",
            "فودافون",
            "اورنج",
            "اتصالات",
            "امازون",
            "سبوتيفاي",
            "شاهد",
        )

    val MONTHS: Map<String, Int> =
        buildMap {
            val en =
                listOf(
                    "jan january",
                    "feb february",
                    "mar march",
                    "apr april",
                    "may",
                    "jun june",
                    "jul july",
                    "aug august",
                    "sep sept september",
                    "oct october",
                    "nov november",
                    "dec december",
                )
            val ar = listOf("يناير", "فبراير", "مارس", "ابريل", "مايو", "يونيو", "يوليو", "اغسطس", "سبتمبر", "اكتوبر", "نوفمبر", "ديسمبر")
            en.forEachIndexed { i, names -> split(names).forEach { put(it, i + 1) } }
            ar.forEachIndexed { i, name -> put(name, i + 1) }
        }

    val YESTERDAY: Set<String> = setOf("yesterday", "امبارح", "امس", "البارحه", "embare7", "imbare7", "embarah")
    val TODAY: Set<String> = setOf("today", "اليوم", "النهارده", "انهارده", "النهارد")

    val WEEKDAYS: Map<String, DayOfWeek> =
        mapOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
        )
}
