package eg.masroof

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.NeverDeleteCallback
import eg.masroof.data.db.NeverDeleteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeverDeleteDatabaseTest {
    private class Recording(
        version: Int,
    ) : SupportSQLiteOpenHelper.Callback(version) {
        val calls = mutableListOf<String>()
        var corruptions = 0

        override fun onCreate(db: SupportSQLiteDatabase) {
            calls += "create"
        }

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            calls += "upgrade"
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            calls += "open"
        }

        override fun onCorruption(db: SupportSQLiteDatabase) {
            corruptions++
        }
    }

    private fun helper(
        context: Context,
        name: String,
        callback: SupportSQLiteOpenHelper.Callback,
    ) = NeverDeleteOpenHelperFactory.create(
        SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(name)
            .callback(callback)
            .build(),
    )

    @Test
    fun `REQ-AND-130 a corruption report never deletes masroof db and the other callbacks still reach Room`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recording = Recording(1)
        val helper = helper(context, "never-delete.db", recording)
        val db = helper.writableDatabase
        assertEquals(listOf("create", "open"), recording.calls)
        val file = File(db.path!!)
        assertTrue(file.exists())

        // What the framework does when SQLite reports corruption: it hands the database to the callback, whose default deletes it.
        NeverDeleteCallback(recording).onCorruption(db)
        assertTrue("the database file must survive a corruption report", file.exists())
        assertEquals("the guard does not forward the report to a callback that could delete", 0, recording.corruptions)
        helper.close()
        assertTrue(file.exists())
    }

    @Test
    fun `REQ-AND-130 the stock callback would delete the file which is why the guard exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plain =
            object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            }
        val helper =
            androidx.sqlite.db.framework
                .FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name("stock.db")
                        .callback(plain)
                        .build(),
                )
        val db = helper.writableDatabase
        val file = File(db.path!!)
        plain.onCorruption(db)
        assertTrue("control: the default onCorruption removes the file", !file.exists())
        helper.close()
    }

    @Test
    fun `REQ-AND-130 the production database builder installs the guard`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "guarded-build.db"
        val db =
            eg.masroof.data.db.DatabaseStartup
                .build(context, name)
        try {
            val file = File(db.openHelper.writableDatabase.path!!)
            assertTrue("the open helper is the guarded one", db.openHelper is eg.masroof.data.db.NeverDeleteOpenHelper)
            assertTrue(file.exists())
        } finally {
            db.close()
        }
    }
}
