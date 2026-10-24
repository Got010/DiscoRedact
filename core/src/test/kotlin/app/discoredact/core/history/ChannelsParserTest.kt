package app.discoredact.core.history

import app.discoredact.core.history.Pages.dm
import app.discoredact.core.history.Pages.group
import app.discoredact.core.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The open DM list: types 1 and 3 only, never a guild channel, names derived as the package import does. */
class ChannelsParserTest {
    @Test fun dmsAndGroupDmsOnly() {
        val body = "[" + listOf(
            dm(11, 501L to "anna"),
            group(12, "Trip", 501, 502),
            group(13, null, 503, 504),
            """{"id":"14","type":0,"guild_id":"7","name":"general"}""",
            """{"id":"15","type":1,"guild_id":"7","recipients":[]}""",
            """{"id":"16","type":2}""",
            """{"id":"17","type":"1"}""",
            """{"type":1}""",
            "7",
            dm(11, 501L to "anna"),
            """{"id":"18","type":1,"recipients":[]}""",
            """{"id":"19","type":3,"name":"  ","recipients":[]}""",
            // Types other than 1 and 3 with no guild_id: a guild text, voice, category, announcement or directory.
            """{"id":"20","type":0,"recipients":[{"id":"5"}]}""",
            """{"id":"21","type":2,"recipients":[]}""",
            """{"id":"22","type":4}""",
            """{"id":"23","type":5,"name":"news"}""",
            """{"id":"24","type":13}""",
        ).joinToString(",") + "]"
        val list = ChannelsParser.dms(body)!!
        // A type sent as the text "1" reads as 1, as the package import reads it.
        assertEquals(listOf(11L, 12L, 13L, 17L, 18L, 19L), list.channels.map { it.channelId })
        assertEquals(
            listOf(ChannelType.DM, ChannelType.GROUP_DM, ChannelType.GROUP_DM, ChannelType.DM, ChannelType.DM, ChannelType.GROUP_DM),
            list.channels.map { it.type },
        )
        assertEquals(8, list.skippedOther)
        assertEquals(2, list.malformed)
        assertEquals("anna", list.channels[0].displayName)
        assertEquals(listOf("501"), list.channels[0].recipientIds)
        assertEquals("Trip", list.channels[1].displayName)
        assertEquals("u503, u504", list.channels[2].displayName)
        assertEquals(listOf("503", "504"), list.channels[2].recipientIds)
        assertEquals("Direct message 18", list.channels[4].displayName)
        assertEquals("Group DM 19", list.channels[5].displayName)
    }

    @Test fun theGlobalNameWinsOverTheUsername() {
        val list = ChannelsParser.dms("""[{"id":"11","type":1,"recipients":[{"id":"5","username":"anna_x","global_name":"Anna"}]}]""")!!
        assertEquals("Anna", list.channels.single().displayName)
    }

    @Test fun aBodyThatIsNotAListIsNull() {
        assertNull(ChannelsParser.dms("""{"message":"x"}"""))
        assertNull(ChannelsParser.dms("<html></html>"))
        assertNull(ChannelsParser.dms(""))
        assertEquals(0, ChannelsParser.dms("[]")!!.channels.size)
    }

    @Test fun itsTextIsCountsOnly() {
        val list = ChannelsParser.dms("[${dm(11, 501L to "SECRETNAME")}]")!!
        assertFalse(list.toString().contains("SECRETNAME"))
    }
}
