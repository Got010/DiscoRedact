package app.discoredact.core.importer

import app.discoredact.core.importer.Fixtures.RecordingSink
import app.discoredact.core.importer.Fixtures.dm
import app.discoredact.core.importer.Fixtures.groupDm
import app.discoredact.core.importer.Fixtures.guildChannel
import app.discoredact.core.importer.Fixtures.jsonMessage
import app.discoredact.core.importer.Fixtures.source
import app.discoredact.core.importer.Fixtures.zip
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.Snowflake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageImporterTest {
    private val ts = "2021-03-04 12:34:56.789000+00:00"
    private val tsMillis = OffsetDateTime.parse("2021-03-04T12:34:56.789Z").toInstant().toEpochMilli()

    private fun run(bytes: ByteArray, sink: RecordingSink = RecordingSink()): Pair<ImportReport, RecordingSink> =
        PackageImporter().import(source(bytes), sink) to sink

    @Test
    fun jsonPackageImportsDmsAndGroupDmsWithCountsMatchingTheFiles() {
        val pkg = zip(
            "messages/index.json" to """{"111111111111111111": "Direct Message with alpha", "222222222222222222": "Group chat bravo"}""",
            "messages/c111111111111111111/channel.json" to dm("111111111111111111", "500000000000000001", "500000000000000002"),
            "messages/c111111111111111111/messages.json" to "[" + listOf(
                jsonMessage("800000000000000001", ts, "hello"),
                jsonMessage("800000000000000002", ts, "second", "https://example.invalid/a.png"),
                jsonMessage("800000000000000003", ts, ""),
            ).joinToString(",") + "]",
            "messages/c222222222222222222/channel.json" to groupDm("222222222222222222", "500000000000000001", "500000000000000003", "500000000000000004"),
            "messages/c222222222222222222/messages.json" to "[" + jsonMessage("800000000000000010", ts, "group hi") + "]",
        )
        val (report, sink) = run(pkg)
        assertEquals(2, report.channels)
        assertEquals(1, report.dmChannels)
        assertEquals(1, report.groupDmChannels)
        assertEquals(mapOf(111111111111111111L to 3, 222222222222222222L to 1), report.messagesPerChannel)
        assertEquals(4L, report.messagesInserted)
        assertEquals(0L, report.malformedRows)
        val dm = sink.channels.single { it.channelId == 111111111111111111L }
        assertEquals(ChannelType.DM, dm.type)
        assertEquals("Direct Message with alpha", dm.displayName)
        assertEquals(listOf("500000000000000001", "500000000000000002"), dm.recipientIds)
        assertEquals(ChannelType.GROUP_DM, sink.channels.single { it.channelId == 222222222222222222L }.type)
        val m2 = sink.messages.single { it.messageId == 800000000000000002L }
        assertTrue(m2.hasAttachments)
        assertEquals("second", m2.content)
        assertEquals(tsMillis, m2.timestampMillis)
        assertFalse(sink.messages.single { it.messageId == 800000000000000001L }.hasAttachments)
    }

    @Test
    fun csvPackageWithQuotedFieldsAndEmbeddedNewlines() {
        val csv = "ID,Timestamp,Contents,Attachments\r\n" +
            "800000000000000101,$ts,plain,\r\n" +
            "800000000000000102,$ts,\"comma, inside\",\r\n" +
            "800000000000000103,$ts,\"line one\nline two\",https://example.invalid/f.txt\r\n" +
            "800000000000000104,$ts,\"she said \"\"hi\"\"\",\n" +
            "\n" +
            "800000000000000105,$ts,last,"
        val pkg = zip(
            "messages/c333333333333333333/channel.json" to dm("333333333333333333"),
            "messages/c333333333333333333/messages.csv" to csv,
        )
        val (report, sink) = run(pkg)
        assertEquals(mapOf(333333333333333333L to 5), report.messagesPerChannel)
        assertEquals(0L, report.malformedRows)
        val byId = sink.messages.associateBy { it.messageId }
        assertEquals("comma, inside", byId[800000000000000102L]!!.content)
        assertEquals("line one\nline two", byId[800000000000000103L]!!.content)
        assertTrue(byId[800000000000000103L]!!.hasAttachments)
        assertEquals("she said \"hi\"", byId[800000000000000104L]!!.content)
        assertEquals("last", byId[800000000000000105L]!!.content)
        assertEquals(tsMillis, byId[800000000000000101L]!!.timestampMillis)
        assertEquals("Direct message 333333333333333333", sink.channels.single().displayName)
    }

    @Test
    fun guildFoldersAreSkippedAndTheirMessagesNeverEmitted() {
        val pkg = zip(
            "messages/c444444444444444444/channel.json" to guildChannel("444444444444444444"),
            "messages/c444444444444444444/messages.json" to "[" + jsonMessage("800000000000000201", ts, "server text") + "]",
            "messages/c444444444444444445/channel.json" to """{"id": "444444444444444445", "type": 0, "guild_id": "900000000000000001"}""",
            "messages/c444444444444444445/messages.csv" to "ID,Timestamp,Contents,Attachments\n800000000000000202,$ts,x,\n",
            "messages/c555555555555555555/channel.json" to dm("555555555555555555"),
            "messages/c555555555555555555/messages.json" to "[" + jsonMessage("800000000000000203", ts, "dm text") + "]",
        )
        val (report, sink) = run(pkg)
        assertEquals(2, report.skippedGuildChannels)
        assertEquals(listOf(555555555555555555L), sink.channels.map { it.channelId })
        assertEquals(listOf(800000000000000203L), sink.messages.map { it.messageId })
    }

    @Test
    fun nonDmTypesAndFoldersWithoutChannelJsonAreSkipped() {
        val pkg = zip(
            "messages/c600000000000000001/channel.json" to """{"id": "600000000000000001", "type": 5}""",
            "messages/c600000000000000001/messages.json" to "[" + jsonMessage("800000000000000301", ts, "a") + "]",
            "messages/c600000000000000002/messages.json" to "[" + jsonMessage("800000000000000302", ts, "b") + "]",
        )
        val (report, sink) = run(pkg)
        assertEquals(2, report.skippedOtherFolders)
        assertTrue(sink.channels.isEmpty())
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun prefixedFolderNamesFallBackToDigitsButChannelJsonIdWins() {
        val pkg = zip(
            "messages/c700000000000000001/channel.json" to """{"type": 1, "recipients": []}""",
            "messages/c700000000000000001/messages.json" to "[" + jsonMessage("800000000000000401", ts, "a") + "]",
            "messages/c700000000000000999/channel.json" to dm("700000000000000002"),
            "messages/c700000000000000999/messages.json" to "[" + jsonMessage("800000000000000402", ts, "b") + "]",
            "messages/700000000000000003/channel.json" to """{"type": "DM"}""",
            "messages/700000000000000003/messages.json" to "[" + jsonMessage("800000000000000403", ts, "c") + "]",
        )
        val (_, sink) = run(pkg)
        assertEquals(setOf(700000000000000001L, 700000000000000002L, 700000000000000003L), sink.channels.map { it.channelId }.toSet())
        assertEquals(700000000000000002L, sink.messages.single { it.messageId == 800000000000000402L }.channelId)
        assertEquals(700000000000000001L, sink.messages.single { it.messageId == 800000000000000401L }.channelId)
    }

    @Test
    fun malformedRowsAreSkippedAndCountedNeverFatal() {
        val json = "[" + listOf(
            jsonMessage("800000000000000501", ts, "good"),
            """{"Timestamp": "$ts", "Contents": "no id"}""",
            """{"ID": "not-a-number", "Timestamp": "$ts", "Contents": "bad id"}""",
            """{"ID": -5, "Timestamp": "$ts", "Contents": "negative"}""",
            "42",
            jsonMessage("800000000000000502", "garbage time", "time from id"),
        ).joinToString(",") + "]"
        val csv = "ID,Timestamp,Contents,Attachments\n" +
            "800000000000000601,$ts,ok,\n" +
            "800000000000000602,$ts,too,many,fields\n" +
            "abc,$ts,bad id,\n" +
            "800000000000000603,$ts,\"never closed"
        val pkg = zip(
            "messages/c810000000000000001/channel.json" to dm("810000000000000001"),
            "messages/c810000000000000001/messages.json" to json,
            "messages/c810000000000000002/channel.json" to dm("810000000000000002"),
            "messages/c810000000000000002/messages.csv" to csv,
        )
        val (report, sink) = run(pkg)
        assertEquals(setOf(800000000000000501L, 800000000000000502L, 800000000000000601L), sink.messages.map { it.messageId }.toSet())
        assertEquals(7L, report.malformedRows)
        assertEquals(1L, report.timestampsFromId)
        assertEquals(Snowflake.timestampMillis(800000000000000502L), sink.messages.single { it.messageId == 800000000000000502L }.timestampMillis)
        assertEquals(0, report.malformedFiles)
    }

    @Test
    fun aTruncatedJsonFileKeepsEarlierRowsAndOtherChannels() {
        val pkg = zip(
            "messages/c820000000000000001/channel.json" to dm("820000000000000001"),
            "messages/c820000000000000001/messages.json" to "[" + jsonMessage("800000000000000701", ts, "kept") + ", {\"ID\": 8000",
            "messages/c820000000000000002/channel.json" to dm("820000000000000002"),
            "messages/c820000000000000002/messages.json" to "[" + jsonMessage("800000000000000702", ts, "other") + "]",
            "messages/c820000000000000003/channel.json" to "{ not json",
            "messages/index.json" to "[1, 2",
        )
        val (report, sink) = run(pkg)
        assertEquals(setOf(800000000000000701L, 800000000000000702L), sink.messages.map { it.messageId }.toSet())
        assertEquals(3, report.malformedFiles)
    }

    @Test
    fun entriesOutsideMessagesLeaveNoTrace() {
        val canaries = listOf("CANARY-EMAIL-x@example.invalid", "CANARY-IP-203.0.113.9", "CANARY-PAYMENT", "CANARY-SERVER", "CANARY-ADS", "CANARY-TICKET", "CANARY-NESTED")
        val pkg = zip(
            "account/user.json" to """{"email": "${canaries[0]}", "ip": "${canaries[1]}", "payments": ["${canaries[2]}"]}""",
            "account/messages/c830000000000000009/channel.json" to dm("830000000000000009"),
            "account/messages/c830000000000000009/messages.json" to "[" + jsonMessage("800000000000000809", ts, canaries[6]) + "]",
            "activity/analytics/events-2021.json" to "{ this would be malformed if it were ever parsed",
            "servers/c830000000000000008/channel.json" to dm("830000000000000008"),
            "servers/c830000000000000008/messages.json" to "[" + jsonMessage("800000000000000808", ts, canaries[3]) + "]",
            "ads/traits.json" to canaries[4],
            "support_tickets/1.json" to canaries[5],
            "messages/c830000000000000001/../../account/user.json" to dm("830000000000000007"),
            "messages/c830000000000000001/nested/messages.json" to "[" + jsonMessage("800000000000000807", ts, canaries[6]) + "]",
            "Messages/c830000000000000001/Channel.json" to dm("830000000000000001"),
            "Messages/c830000000000000001/MESSAGES.JSON" to "[" + jsonMessage("800000000000000801", ts, "the only real row") + "]",
        )
        val (report, sink) = run(pkg)
        assertEquals(listOf(830000000000000001L), sink.channels.map { it.channelId })
        assertEquals(listOf(800000000000000801L), sink.messages.map { it.messageId })
        assertEquals(0, report.malformedFiles) // the unreadable activity file was never parsed
        val everything = sink.channels.toString() + sink.messages.toString() + report.toString()
        for (c in canaries) assertFalse("$c leaked", everything.contains(c))
        assertFalse(everything.contains("830000000000000009"))
        assertFalse(everything.contains("830000000000000008"))
        assertFalse(everything.contains("830000000000000007"))
    }

    @Test
    fun indexJsonNamesAndMissingIndex() {
        val pkg = zip(
            "messages/c840000000000000001/channel.json" to groupDm("840000000000000001"),
            "messages/c840000000000000001/messages.json" to "[]",
        )
        val (report, sink) = run(pkg)
        assertEquals("Group DM 840000000000000001", sink.channels.single().displayName)
        assertEquals(mapOf(840000000000000001L to 0), report.messagesPerChannel)
    }

    @Test
    fun duplicatesAcrossJsonAndCsvAreCountedOnce() {
        val pkg = zip(
            "messages/c850000000000000001/channel.json" to dm("850000000000000001"),
            "messages/c850000000000000001/messages.json" to "[" + jsonMessage("800000000000000901", ts, "a") + "]",
            "messages/c850000000000000001/messages.csv" to "ID,Timestamp,Contents,Attachments\n800000000000000901,$ts,a,\n800000000000000902,$ts,b,\n",
        )
        val (report, sink) = run(pkg)
        assertEquals(2, sink.messages.size)
        assertEquals(3L, report.messagesRead)
        assertEquals(2L, report.messagesInserted)
        assertEquals(1L, report.duplicates)
    }

    @Test
    fun aLargePackageStreamsInBoundedBatches() {
        val file = File.createTempFile("pkg", ".zip")
        try {
            val total = 150_000
            ZipOutputStream(FileOutputStream(file).buffered()).use { z ->
                z.putNextEntry(ZipEntry("messages/c860000000000000001/channel.json"))
                z.write(dm("860000000000000001").toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("messages/c860000000000000001/messages.json"))
                val w = z.bufferedWriter()
                w.write("[")
                for (i in 0 until total) {
                    if (i > 0) w.write(",")
                    w.write(jsonMessage((900000000000000000L + i).toString(), ts, "message number $i"))
                }
                w.write("]"); w.flush(); z.closeEntry()
            }
            var maxBatch = 0
            var count = 0L
            val sink = object : ImportSink {
                override fun onChannels(channels: List<ImportedChannel>) = Unit
                override fun onMessages(batch: List<ImportedMessage>): Int {
                    maxBatch = maxOf(maxBatch, batch.size); count += batch.size; return batch.size
                }
            }
            val report = PackageImporter().import({ FileInputStream(file) }, sink)
            assertEquals(total.toLong(), count)
            assertEquals(total.toLong(), report.messagesInserted)
            assertEquals(ImportLimits().batchSize, maxBatch)
        } finally {
            file.delete()
        }
    }

    @Test
    fun timestampsParse() {
        assertEquals(tsMillis, Timestamps.parseMillis(ts))
        assertEquals(tsMillis, Timestamps.parseMillis("2021-03-04T12:34:56.789+00:00"))
        assertEquals(tsMillis, Timestamps.parseMillis("2021-03-04T14:34:56.789+02:00"))
        assertEquals(tsMillis - 789, Timestamps.parseMillis("2021-03-04 12:34:56"))
        assertNull(Timestamps.parseMillis("yesterday"))
        assertNull(Timestamps.parseMillis(""))
    }

    @Test
    fun pathsAreClassifiedStrictly() {
        assertEquals(PackageEntry.Index, PackagePaths.classify("MESSAGES/Index.json"))
        assertEquals(PackageEntry.ChannelJson("c1"), PackagePaths.classify("messages\\c1\\channel.json"))
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("package/messages/c1/messages.json"))
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("messages/c1/x/messages.json"))
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("messages/../account/user.json"))
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("messages//messages.json"))
        assertEquals(123L, PackagePaths.folderDigits("c123"))
        assertNull(PackagePaths.folderDigits("abc"))
    }

    private class SinkFailure : RuntimeException("database write failed")

    /** A sink whose first batch write fails, as a full disk or a Room error would. */
    private class FailingSink : ImportSink {
        var calls = 0
        override fun onChannels(channels: List<ImportedChannel>) = Unit
        override fun onMessages(batch: List<ImportedMessage>): Int {
            calls++
            throw SinkFailure()
        }
    }

    @Test
    fun aSinkFailureInsideAJsonFileAbortsTheImportInsteadOfCountingAMalformedFile() {
        val pkg = zip(
            "messages/c870000000000000001/channel.json" to dm("870000000000000001"),
            "messages/c870000000000000001/messages.json" to "[" + listOf(
                jsonMessage("800000000000001001", ts, "a"), jsonMessage("800000000000001002", ts, "b"),
            ).joinToString(",") + "]",
        )
        val sink = FailingSink()
        try {
            PackageImporter(ImportLimits(batchSize = 1)).import(source(pkg), sink)
            throw AssertionError("the import should have failed")
        } catch (_: SinkFailure) {
        }
        assertEquals(1, sink.calls)
    }

    @Test
    fun aSinkFailureInsideACsvFileAbortsTheImport() {
        val pkg = zip(
            "messages/c870000000000000002/channel.json" to dm("870000000000000002"),
            "messages/c870000000000000002/messages.csv" to "ID,Timestamp,Contents,Attachments\n800000000000001003,$ts,a,\n800000000000001004,$ts,b,\n",
        )
        val sink = FailingSink()
        try {
            PackageImporter(ImportLimits(batchSize = 1)).import(source(pkg), sink)
            throw AssertionError("the import should have failed")
        } catch (_: SinkFailure) {
        }
        assertEquals(1, sink.calls)
    }

    @Test
    fun aByteOrderMarkBeforeMessagesJsonIsSkipped() {
        val pkg = zip(
            "messages/c880000000000000001/channel.json" to dm("880000000000000001"),
            "messages/c880000000000000001/messages.json" to "\uFEFF[" + jsonMessage("800000000000001101", ts, "bom") + "]",
        )
        val (report, sink) = run(pkg)
        assertEquals(listOf(800000000000001101L), sink.messages.map { it.messageId })
        assertEquals(0, report.malformedFiles)
    }

    @Test
    fun typeZeroWithoutAGuildKeyIsSkippedAndStrayNamesAreIgnored() {
        val pkg = zip(
            "messages/c890000000000000001/channel.json" to """{"id": "890000000000000001", "type": 0}""",
            "messages/c890000000000000001/messages.json" to "[" + jsonMessage("800000000000001201", ts, "text channel") + "]",
            "messages/c890000000000000002/channel.json" to dm("890000000000000002"),
            "messages/c890000000000000002/user.json" to "[" + jsonMessage("800000000000001202", ts, "stray file") + "]",
            "servers/messages/channel.json" to dm("890000000000000003"),
        )
        val (report, sink) = run(pkg)
        assertEquals(listOf(890000000000000002L), sink.channels.map { it.channelId })
        assertTrue(sink.messages.isEmpty())
        assertEquals(1, report.skippedOtherFolders)
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("servers/messages/channel.json"))
        assertEquals(PackageEntry.Ignored, PackagePaths.classify("messages/c1/user.json"))
    }
}
