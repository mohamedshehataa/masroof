package eg.masroof.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest change of STORY-183 is exactly what the foreground backup task needs and nothing else (REQ-OPS-177). */
class BackupManifestTest {
    private val manifest =
        DocumentBuilderFactory
            .newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    private val android = "http://schemas.android.com/apk/res/android"

    private fun names(tag: String) =
        (0 until manifest.getElementsByTagName(tag).length)
            .map { manifest.getElementsByTagName(tag).item(it) as org.w3c.dom.Element }

    @Test
    fun `REQ-OPS-177 the manifest declares the two foreground permissions and the dataSync type for WorkManager's own service`() {
        val permissions = names("uses-permission").map { it.getAttributeNS(android, "name").ifEmpty { it.getAttribute("android:name") } }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        val service = names("service").first { it.getAttribute("android:name") == "androidx.work.impl.foreground.SystemForegroundService" }
        assertEquals("dataSync", service.getAttribute("android:foregroundServiceType"))
        assertFalse("the foreground service is not exported", service.getAttribute("android:exported") == "true")
    }

    @Test
    fun `REQ-OPS-177 the merged manifest of the debug build keeps the dataSync service not exported and adds no other permission use`() {
        val merged =
            File("build/intermediates/merged_manifests/debug").walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
        assertTrue("the merged manifest is produced before the unit tests run", merged != null)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(merged)
        val service =
            (0 until doc.getElementsByTagName("service").length)
                .map { doc.getElementsByTagName("service").item(it) as org.w3c.dom.Element }
                .first { it.getAttribute("android:name") == "androidx.work.impl.foreground.SystemForegroundService" }
        assertEquals("dataSync", service.getAttribute("android:foregroundServiceType"))
        assertEquals("false", service.getAttribute("android:exported"))
        val permissions =
            (0 until doc.getElementsByTagName("uses-permission").length)
                .map { (doc.getElementsByTagName("uses-permission").item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertFalse(
            "no camera, location, contacts or storage permission came in through a library",
            permissions.any {
                it.contains("CAMERA") ||
                    it.contains("LOCATION") ||
                    it.contains("CONTACTS") ||
                    it.contains("STORAGE")
            },
        )
    }

    @Test
    fun `REQ-OPS-177 no new exported component and no new permission beyond the two foreground ones`() {
        val exported =
            (names("activity") + names("service") + names("receiver") + names("provider"))
                .filter { it.getAttribute("android:exported") == "true" }
                .map { it.getAttribute("android:name") }
        assertEquals(
            setOf(".MainActivity", ".ingest.SmsReceiver", ".ingest.MasroofNotificationListener", ".ui.receipts.ShareReceiptActivity"),
            exported.toSet(),
        )
        val permissions = names("uses-permission").map { it.getAttribute("android:name") }.toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECEIVE_SMS",
                // STORY-186 "Read past SMS": asked only on Start, after an explanation
                "android.permission.READ_SMS",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECORD_AUDIO",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            ),
            permissions,
        )
        assertEquals(
            "false",
            manifest
                .getElementsByTagName("application")
                .item(0)
                .attributes
                .getNamedItem("android:allowBackup")
                .nodeValue,
        )
    }
}
