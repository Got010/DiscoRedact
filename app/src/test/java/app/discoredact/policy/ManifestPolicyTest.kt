package app.discoredact.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest's privacy facts (CLAUDE.md rules 2 and 9). The release gate checks the built APK as well. */
class ManifestPolicyTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test
    fun exactlyTheExecutorsPermissionsAreRequested() {
        val m = parse("src/main/AndroidManifest.xml")
        val requested = (m.children("uses-permission") + m.children("uses-permission-sdk-23"))
            .filter { it.getAttributeNS(tools, "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
        assertEquals(
            listOf(
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requested,
        )
        assertTrue(m.children("permission").all { it.getAttributeNS(tools, "node") == "remove" })
    }

    @Test
    fun backupIsOffAndNothingTransfers() {
        val app = parse("src/main/AndroidManifest.xml").children("application").single()
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = rules.children(section).single()
            assertTrue(s.children("include").isEmpty())
            assertEquals(domains, s.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet())
        }
    }

    @Test
    fun theOnlyServiceOfOurOwnIsWorkManagersForegroundServiceGivenTheDataSyncType() {
        val app = parse("src/main/AndroidManifest.xml").children("application").single()
        for (tag in listOf("receiver", "provider")) assertTrue(tag, app.children(tag).isEmpty())
        val service = app.children("service").single()
        assertEquals("androidx.work.impl.foreground.SystemForegroundService", service.getAttributeNS(android, "name"))
        assertEquals("dataSync", service.getAttributeNS(android, "foregroundServiceType"))
        assertEquals("merge", service.getAttributeNS(tools, "node"))
        assertEquals(1, app.children("activity").size)
    }
}
