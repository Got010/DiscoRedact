package app.discoredact.core.selection

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SelectionRulesTest {
    private val day = 86_400_000L

    private fun msg(id: Long, ts: Long = id * day, content: String? = "text $id", att: Boolean = false, keep: Boolean = false, channel: Long = 1) =
        MessageSel(id, channel, ts, content, att, selected = false, keep = keep, status = MessageStatus.PENDING)

    private fun chan(mode: DmMode, id: Long = 1, state: ChannelState = ChannelState.PENDING, included: Boolean = true) =
        ChannelSel(id, "dm $id", mode, state, included)

    private val filters = listOf(
        MessageFilter(keyword = "text"),
        MessageFilter(fromMillis = 0),
        MessageFilter(untilMillis = Long.MAX_VALUE),
        MessageFilter(attachmentsOnly = true),
        MessageFilter(fromMillis = 0, untilMillis = Long.MAX_VALUE, keyword = "TEXT", attachmentsOnly = false),
    )

    @Test
    fun keepBeatsEveryFilterAndBothModes() {
        for (mode in DmMode.entries) for (f in filters) {
            var m = msg(1, att = true, keep = true)
            m = SelectionRules.applyFilter(m, f)
            assertTrue("filter $f should select", m.selected)
            assertFalse(SelectionRules.wouldDelete(m, chan(mode)))
            m = SelectionRules.manualSet(m, true)
            assertFalse(SelectionRules.wouldDelete(m, chan(mode)))
            assertTrue(m.keep)
        }
    }

    @Test
    fun filtersNeverTouchKeep() {
        for (keep in listOf(true, false)) for (f in filters) {
            val before = msg(1, keep = keep)
            assertEquals(keep, SelectionRules.applyFilter(before, f).keep)
            assertEquals(keep, SelectionRules.applyFilter(before.copy(selected = true), MessageFilter(keyword = "zzz")).keep)
        }
    }

    @Test
    fun filterSetsAndClearsSelectedOnly() {
        val m = msg(1, content = "hello there").copy(selected = true)
        assertFalse(SelectionRules.applyFilter(m, MessageFilter(keyword = "absent")).selected)
        assertTrue(SelectionRules.applyFilter(m.copy(selected = false), MessageFilter(keyword = "HELLO")).selected)
        assertEquals(m.copy(selected = false), SelectionRules.applyFilter(m, MessageFilter(keyword = "absent")))
    }

    @Test
    fun manualTogglesOverrideAndSurviveFilterReRuns() {
        val f = MessageFilter(keyword = "match")
        var a = msg(1, content = "a match")
        var b = msg(2, content = "no")
        a = SelectionRules.applyFilter(a, f)
        b = SelectionRules.applyFilter(b, f)
        assertTrue(a.selected); assertFalse(b.selected)
        a = SelectionRules.manualToggle(a) // user un-selects a filter hit
        b = SelectionRules.manualToggle(b) // user selects a filter miss
        repeat(3) {
            a = SelectionRules.applyFilter(a, f)
            b = SelectionRules.applyFilter(b, f)
        }
        assertFalse(a.selected); assertTrue(b.selected)
        // A different filter does not undo them either.
        a = SelectionRules.applyFilter(a, MessageFilter(fromMillis = 0))
        b = SelectionRules.applyFilter(b, MessageFilter(keyword = "nothing"))
        assertFalse(a.selected); assertTrue(b.selected)
    }

    @Test
    fun keepDoesNotChangeSelectionIntent() {
        var m = SelectionRules.manualSet(msg(1), true)
        m = SelectionRules.setKeep(m, true)
        assertTrue(m.selected); assertEquals(true, m.manualSelected)
        assertFalse(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_SELECTED)))
        m = SelectionRules.setKeep(m, false)
        assertTrue(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_SELECTED)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyFilterIsRefused() {
        SelectionRules.applyFilter(msg(1), MessageFilter(keyword = "   "))
    }

    @Test
    fun modes() {
        val sel = msg(1).copy(selected = true)
        val unsel = msg(2)
        assertTrue(SelectionRules.wouldDelete(sel, chan(DmMode.DELETE_SELECTED)))
        assertFalse(SelectionRules.wouldDelete(unsel, chan(DmMode.DELETE_SELECTED)))
        assertTrue(SelectionRules.wouldDelete(sel, chan(DmMode.DELETE_ALL_EXCEPT_KEPT)))
        assertTrue(SelectionRules.wouldDelete(unsel, chan(DmMode.DELETE_ALL_EXCEPT_KEPT)))
        assertFalse(SelectionRules.wouldDelete(unsel.copy(keep = true), chan(DmMode.DELETE_ALL_EXCEPT_KEPT)))
    }

    @Test
    fun onlyPendingMessagesInEligibleChannelsAreCandidates() {
        val m = msg(1).copy(selected = true)
        for (s in MessageStatus.entries) {
            assertEquals(s == MessageStatus.PENDING, SelectionRules.wouldDelete(m.copy(status = s), chan(DmMode.DELETE_ALL_EXCEPT_KEPT)))
        }
        assertFalse(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_ALL_EXCEPT_KEPT, included = false)))
        assertFalse(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_ALL_EXCEPT_KEPT, state = ChannelState.GONE)))
        assertFalse(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_ALL_EXCEPT_KEPT, state = ChannelState.INACCESSIBLE)))
        assertTrue(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_ALL_EXCEPT_KEPT, state = ChannelState.DONE)))
        assertFalse(SelectionRules.wouldDelete(m, chan(DmMode.DELETE_ALL_EXCEPT_KEPT, id = 2)))
    }

    @Test
    fun keptMessageIsNeverInAPlanUnderAnyCombination() {
        val rnd = Random(7)
        repeat(5_000) {
            val m = MessageSel(
                messageId = rnd.nextLong(1, 1000), channelId = 1, timestampMillis = rnd.nextLong(0, 10 * day),
                content = listOf(null, "", "Alpha", "beta ALPHA").random(rnd), hasAttachments = rnd.nextBoolean(),
                selected = rnd.nextBoolean(), keep = true, status = MessageStatus.entries.random(rnd),
                manualSelected = listOf(null, true, false).random(rnd),
            )
            var cur = m
            repeat(rnd.nextInt(0, 4)) {
                cur = when (rnd.nextInt(3)) {
                    0 -> SelectionRules.applyFilter(cur, filters.random(rnd))
                    1 -> SelectionRules.manualToggle(cur)
                    else -> SelectionRules.manualSet(cur, rnd.nextBoolean())
                }
            }
            val c = chan(DmMode.entries.random(rnd), state = ChannelState.entries.random(rnd), included = rnd.nextBoolean())
            assertFalse(SelectionRules.wouldDelete(cur, c))
            val plan = DeletePlanBuilder().addChannel(c, sequenceOf(cur)).build()
            assertEquals(0, plan.total)
        }
    }

    @Test
    fun planCountsPerChannelAndKeepsOrder() {
        val c1 = chan(DmMode.DELETE_SELECTED, id = 1)
        val c2 = chan(DmMode.DELETE_ALL_EXCEPT_KEPT, id = 2)
        val c3 = chan(DmMode.DELETE_ALL_EXCEPT_KEPT, id = 3, included = false)
        val plan = DeletePlanBuilder()
            .addChannel(c1, sequenceOf(msg(13).copy(selected = true), msg(12), msg(11).copy(selected = true, keep = true), msg(10).copy(selected = true)))
            .addChannel(c2, sequenceOf(msg(22, channel = 2), msg(21, channel = 2, keep = true), msg(20, channel = 2).copy(status = MessageStatus.DELETED), msg(19, channel = 2)))
            .addChannel(c3, sequenceOf(msg(30, channel = 3)))
            .build()
        assertEquals(listOf(13L, 10L, 22L, 19L), plan.messageIds().toList())
        assertEquals(listOf(1L to 2, 2L to 2), plan.channels.map { it.channelId to it.count })
        assertEquals(4, plan.total)
    }

    @Test
    fun theFrozenSetDoesNotGrowAfterFreezing() {
        val c = chan(DmMode.DELETE_SELECTED)
        val msgs = (1L..5L).map { msg(it) }.toMutableList()
        msgs[0] = SelectionRules.manualSet(msgs[0], true)
        msgs[1] = SelectionRules.manualSet(msgs[1], true)
        val plan = DeletePlanBuilder().addChannel(c, msgs.asSequence()).build()
        val frozen = InMemoryFrozenSet.freeze(runId = 1, plan = plan)
        assertEquals(2, frozen.size)
        // The user selects more after freezing.
        msgs[2] = SelectionRules.manualSet(msgs[2], true)
        msgs[3] = SelectionRules.applyFilter(msgs[3], MessageFilter(keyword = "text"))
        val newPlan = DeletePlanBuilder().addChannel(c, msgs.asSequence()).build()
        assertEquals(4, newPlan.total)
        assertFalse(newPlan.sameSetAs(plan))
        assertEquals(2, frozen.size)
        assertFalse(deleteGuard(msgs[2], frozen))
        assertFalse(deleteGuard(msgs[3], frozen))
        assertTrue(deleteGuard(msgs[0], frozen))
        // Mutating the plan's exported array cannot reach the frozen set.
        plan.messageIds()[0] = 3L
        assertFalse(3L in frozen)
    }

    @Test
    fun keepToggledAfterFreezingBlocksTheDelete() {
        val c = chan(DmMode.DELETE_ALL_EXCEPT_KEPT)
        val m = msg(1)
        val frozen = InMemoryFrozenSet.freeze(1, DeletePlanBuilder().addChannel(c, sequenceOf(m)).build())
        assertTrue(deleteGuard(GuardRow(1, MessageStatus.PENDING, keep = false), frozen))
        assertFalse(deleteGuard(GuardRow(1, MessageStatus.PENDING, keep = true), frozen))
        for (s in MessageStatus.entries - MessageStatus.PENDING) assertFalse(deleteGuard(GuardRow(1, s, keep = false), frozen))
        assertFalse(deleteGuard(GuardRow(2, MessageStatus.PENDING, keep = false), frozen))
    }

    @Test
    fun filterMatching() {
        val f = MessageFilter(fromMillis = 100, untilMillis = 200)
        assertFalse(f.matches(99, "x", false))
        assertTrue(f.matches(100, "x", false))
        assertTrue(f.matches(199, "x", false))
        assertFalse(f.matches(200, "x", false))
        val k = MessageFilter(keyword = " Élan ")
        assertTrue(k.matches(0, "un ÉLAN vital", false))
        assertTrue(k.matches(0, "élan", false))
        assertFalse(k.matches(0, null, false))
        assertFalse(k.matches(0, "elan", false))
        val a = MessageFilter(attachmentsOnly = true, keyword = "pic")
        assertTrue(a.matches(0, "pic", true))
        assertFalse(a.matches(0, "pic", false))
        assertTrue(MessageFilter().isEmpty)
        assertTrue(MessageFilter(keyword = " ").isEmpty)
        assertFalse(MessageFilter(attachmentsOnly = true).isEmpty)
    }

    @Test
    fun incrementalBuilderMatchesSequenceBuilderAndMapsPositionsToChannels() {
        val c1 = chan(DmMode.DELETE_ALL_EXCEPT_KEPT, id = 1)
        val c2 = chan(DmMode.DELETE_SELECTED, id = 2)
        val m1 = listOf(msg(3), msg(2, keep = true), msg(1))
        val m2 = listOf(msg(9, channel = 2).copy(selected = true), msg(8, channel = 2))
        val a = DeletePlanBuilder().addChannel(c1, m1.asSequence()).addChannel(c2, m2.asSequence()).build()
        val b = DeletePlanBuilder()
        b.startChannel(c1); m1.forEach { b.offer(it) }; b.finishChannel()
        b.startChannel(c2); m2.forEach { b.offer(it) }; b.finishChannel()
        val plan = b.build()
        assertTrue(plan.sameSetAs(a))
        assertEquals(listOf(3L, 1L, 9L), plan.messageIds().toList())
        assertEquals(listOf(1L, 1L, 2L), (0 until plan.total).map { plan.channelAt(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aChannelCannotBeAddedTwice() {
        val c = chan(DmMode.DELETE_SELECTED)
        DeletePlanBuilder().addChannel(c, emptySequence()).addChannel(c, emptySequence())
    }
}
