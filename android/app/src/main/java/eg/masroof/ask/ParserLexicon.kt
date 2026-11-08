package eg.masroof.ask

/**
 * The words and phrases [QuestionParser] looks for, in English, Egyptian Arabic and Franco (Arabic in Latin letters).
 * Everything is folded with [ArabicText] when the object loads, so spellings here may keep hamza, ta marbuta and so on.
 * Words are separated by spaces or new lines; phrases by `|`.
 */
internal object ParserLexicon {
    private fun words(text: String): Set<String> = ArabicText.tokens(text).toSet()

    private fun phrases(text: String): List<String> =
        text.split('|').map { ArabicText.tokens(it).joinToString(" ") }.filter { it.isNotEmpty() }

    /** "roast me" is a request; "roast chicken" is not, so English needs a pronoun (or the word alone). */
    val ROAST_PHRASES: List<String> = phrases("roast me|roast us|roast my|roast our|roast it")

    /** Arabic stems that appear inside one word ("اتريق", "هزقني"). */
    val ROAST_STEMS: List<String> = listOf("تريق", "هزق").map(ArabicText::normalize)

    val PAYDAY_WORDS: Set<String> = words("payday القبض قبض el2abd 2abd e2abd el2obd 2obd")
    val PAYDAY_PHRASES: List<String> =
        phrases(
            """pay day|salary day|till salary|until salary|before salary|لحد المرتب|حد المرتب|لغايه المرتب|لحد الراتب|حد الراتب|
            قبل المرتب|whats left|what is left|how much is left|how much left|money left|money remaining|how much do i have left|
            فاضل كام|فاضلي كام|باقي كام|باقيلي كام|متبقي|fadel kam|fadl kam|fadel ma3aya|fadlly kam""",
        )

    val COMPARE_WORDS: Set<String> =
        words("vs versus compare compared comparison مقارنه قارن قارنلي قارنها moqarna mo2arna mqarna")
    val COMPARE_PHRASES: List<String> = phrases("compared to|compared with|difference between|الفرق بين")

    /** "more than last month": a comparison only when a "last ..." period is also named. */
    val COMPARE_NEEDS_PERIOD: List<String> = phrases("more than|less than|اكتر من|اقل من")

    val TOP_MERCHANT_PHRASES: List<String> =
        phrases(
            """top places|top merchants|top shops|top stores|top spots|top vendors|most visited|favorite places|favourite places|
            where do i spend the most|where do i shop|where do i spend most|اكتر اماكن|اكتر مكان|اكتر محلات|اكتر محل|اكثر اماكن|
            اكثر مكان|aktar amaken|akter amaken|akter makan|aktar makan|aktar ma7alat|akter ma7alat""",
        )

    val FREQUENCY_PHRASES: List<String> =
        phrases(
            """how often|how many times|how many visits|how many orders|number of times|number of visits|كام مره|كام مرات|كم مره|
            عدد مرات|عدد زيارات|kam mara|kam marra|kam mra|kam mrah|kam marah""",
        )

    val WHY_WORDS: Set<String> = words("why ليه leh laih lih لماذا")
    val HIGH_WORDS: Set<String> =
        words(
            """high higher much expensive up increase increased غالي عالي عاليه كتير مرتفع زاد زادت زياده 3aly 3aaly ghaly kteer ktir
            gamed""",
        )

    val BIGGEST_WORDS: Set<String> = words("biggest largest highest اكبر اغلي akbar a3la a3lah")
    val BIGGEST_PHRASES: List<String> = phrases("most expensive|اعلي مصروف|top expense|top purchase")
    val CATEGORY_WORDS: Set<String> = words("category categories فئه فئات تصنيف تصنيفات kategory")

    val BREAKDOWN_STRONG: List<String> =
        phrases(
            """where did my money go|where does my money go|where did the money go|where is my money going|where my money went|
            where did it all go|where does it all go|breakdown|by category|by categories|فلوسي راحت فين|الفلوس راحت فين|فلوسي فين|
            راحت فين|بتروح فين|اتصرفت فين|صرفت فين|فين الفلوس|تقسيم المصاريف|توزيع المصاريف|floosy rahet feen|floosy rahet fen|
            fluosy rahet feen|fluosy rahet fen|rahet feen|rahet fen|feen floosy|feen el floos|feen floos|floosi feen|floosy feen""",
        )

    /** "what did I spend on": a breakdown only when no subject follows. */
    val BREAKDOWN_WEAK: List<String> =
        phrases(
            """what did i spend on|what do i spend on|what am i spending on|on what|علي ايه|3ala eh|3la eh|3ala eih|3la eih|
            basraf 3ala eh|sarft 3ala eh""",
        )

    val SPEND_WORDS: Set<String> =
        words(
            """spend spent spending spendings expense expenses expenditure cost costs paid pay much total outgoings صرفت صرفنا صرف
            بصرف مصروف مصروفات مصاريف مصروفي مصاريفي دفعت دفعنا انفقت اجمالي مجموع كام كم sarft sarfna sarraft srft masrof masrouf
            masroof masrofat masaref kam basraf bsarraf""",
        )
    val SPEND_PHRASES: List<String> = phrases("قد ايه")

    val QUESTION_WORDS: Set<String> =
        words(
            """what whats who when where which how can will is are do does ما ماذا مين امتي ازاي هل ايه ايش eh eih ay ezay meen emta
            fen""",
        )

    private val FILLER: Set<String> =
        words(
            """a an the i my me we our us of on in at for to from did do does done is are was were be it its this that these those
            and or with by about show tell give get list please can could would you your yours money egp le pound pounds since during
            than more less so there has have had been over under into per any all every each some also just only very really often
            times time visits visit order orders ordered go went going goes buy bought shop shopping eat ate top places place
            merchants merchant shops stores expensive left remaining until till payday day roast breakdown week month year days
            last past current previous ago now lately recently got many may too might should
            على علي في من الى الي عن ب مع و قد انا انت انتا بقي بقى ده دا دي دول لو سمحت ممكن عايز عاوز عايزه عاوزه اعرف قولي قوللي
            وريني هات فلوس فلوسي فلوسنا جنيه جنيها بتاع بتاعي بتاعت اللي باللي فات اخر شهر اسبوع سنه يوم ايام النهارده مره مرات
            اكتر اقل اماكن مكان محلات طلبت رحت روحت بروح بطلب اشتريت بشتري اشتري اروح اطلب راحت فين اوي جدا تاني فاضل باقي لحد حد
            لغايه
            3ala 3la fe fi f men el al ya w ana 3and da di dah deh dih dy fel fil lel lil l bta3 bta3t sarf floos floosy fluosy
            flosy fluos feen rahet raht awy awi gedan mara marra mrat talabt rohet roht fadel fadl el2abd 2abd bel bil""",
        )

    /** Words that are never part of a subject: filler plus every intent word above. */
    val STOP: Set<String> =
        FILLER + QUESTION_WORDS + COMPARE_WORDS + WHY_WORDS + HIGH_WORDS + BIGGEST_WORDS + CATEGORY_WORDS + SPEND_WORDS
}
