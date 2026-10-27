package eg.masroof.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Stable id of the one default member; identical on every install. */
const val ME_MEMBER_ID = "member-me"

/** Stored name of the default member. The UI shows "أنا" for this row when Arabic is selected. */
const val ME_MEMBER_NAME = "Me"

/** Stable id of a default category: derived from the reference key of `R__seed_reference.sql`, identical on every install. */
fun seededCategoryId(referenceKey: String): String = "cat-$referenceKey"

private class SeedCategory(
    val key: String,
    val parentKey: String?,
    val nameEn: String,
    val nameAr: String,
    val color: String,
    val sortOrder: Int,
)

// Copied from backend/src/main/resources/db/migration/R__seed_reference.sql (names, colours, sort order).
private val TOP_LEVEL =
    listOf(
        SeedCategory("groceries", null, "Groceries", "بقالة", "#16A34A", 10),
        SeedCategory("dining", null, "Dining out", "مطاعم", "#F97316", 20),
        SeedCategory("car", null, "Car", "السيارة", "#F59E0B", 30),
        SeedCategory("transport", null, "Transport", "مواصلات", "#0EA5E9", 40),
        SeedCategory("bills", null, "Bills", "فواتير", "#6366F1", 50),
        SeedCategory("rent", null, "Rent & housing", "إيجار وسكن", "#8B5CF6", 60),
        SeedCategory("health", null, "Health", "صحة", "#EF4444", 70),
        SeedCategory("education", null, "Education", "تعليم", "#14B8A6", 80),
        SeedCategory("kids", null, "Kids", "أطفال", "#EC4899", 90),
        SeedCategory("clothing", null, "Clothing", "ملابس", "#A855F7", 100),
        SeedCategory("personal-care", null, "Personal care", "عناية شخصية", "#F472B6", 110),
        SeedCategory("household", null, "Household items", "مستلزمات المنزل", "#84CC16", 120),
        SeedCategory("electronics", null, "Electronics", "إلكترونيات", "#64748B", 130),
        SeedCategory("entertainment", null, "Entertainment", "ترفيه", "#D946EF", 140),
        SeedCategory("subscriptions", null, "Subscriptions", "اشتراكات", "#06B6D4", 150),
        SeedCategory("travel", null, "Travel", "سفر", "#0284C7", 160),
        SeedCategory("gifts", null, "Gifts & charity", "هدايا وصدقات", "#E11D48", 170),
        SeedCategory("fees", null, "Fees & charges", "رسوم ومصاريف بنكية", "#78716C", 180),
        SeedCategory("pets", null, "Pets", "حيوانات أليفة", "#CA8A04", 190),
        SeedCategory("other", null, "Other", "أخرى", "#9CA3AF", 900),
    )

// A subcategory takes its parent's colour, as the SQL does.
private fun sub(
    key: String,
    parent: String,
    nameEn: String,
    nameAr: String,
    sortOrder: Int,
) = SeedCategory(key, parent, nameEn, nameAr, TOP_LEVEL.first { it.key == parent }.color, sortOrder)

private val SUBCATEGORIES =
    listOf(
        sub("car.fuel", "car", "Fuel", "بنزين", 1),
        sub("car.maintenance", "car", "Maintenance", "صيانة", 2),
        sub("car.parking", "car", "Parking & tolls", "انتظار وبوابات", 3),
        sub("car.insurance", "car", "Insurance", "تأمين", 4),
        sub("bills.electricity", "bills", "Electricity", "كهرباء", 1),
        sub("bills.water", "bills", "Water", "مياه", 2),
        sub("bills.gas", "bills", "Gas", "غاز", 3),
        sub("bills.internet", "bills", "Internet", "إنترنت", 4),
        sub("bills.mobile", "bills", "Mobile", "موبايل", 5),
    )

/**
 * Seeds what a phone without a server needs (ADR-0009 D3): the default categories when the `category` table is empty, and the
 * member "Me" when the `member` table is empty. Idempotent: running it again changes nothing. It never reads or logs row contents.
 */
object DefaultSeed {
    fun seed(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            if (isEmpty(db, "SELECT COUNT(*) FROM category")) {
                for (c in TOP_LEVEL + SUBCATEGORIES) insertCategory(db, c)
            }
            if (isEmpty(db, "SELECT COUNT(*) FROM member")) {
                db.execSQL(
                    "INSERT OR IGNORE INTO member (id, displayName, sortOrder) VALUES (?, ?, 0)",
                    arrayOf<Any>(ME_MEMBER_ID, ME_MEMBER_NAME),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isEmpty(
        db: SupportSQLiteDatabase,
        countSql: String,
    ): Boolean =
        db.query(countSql).use {
            it.moveToFirst()
            it.getLong(0) == 0L
        }

    private fun insertCategory(
        db: SupportSQLiteDatabase,
        c: SeedCategory,
    ) {
        db.execSQL(
            "INSERT OR IGNORE INTO category (id, nameEn, nameAr, sortOrder, parentId, color, archived, system) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0, 1)",
            arrayOf<Any?>(
                seededCategoryId(c.key),
                c.nameEn,
                c.nameAr,
                c.sortOrder,
                c.parentKey?.let(::seededCategoryId),
                c.color,
            ),
        )
    }
}
