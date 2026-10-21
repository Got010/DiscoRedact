package app.discoredact.core.selection

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import java.util.Locale

/**
 * The selection-relevant view of one message row. [manualSelected] is the user's manual toggle, if any (null when
 * the user never toggled this message by hand); it is what lets a manual toggle survive filter re-runs.
 */
data class MessageSel(
    val messageId: Long,
    val channelId: Long,
    val timestampMillis: Long,
    val content: String?,
    val hasAttachments: Boolean,
    val selected: Boolean,
    val keep: Boolean,
    val status: MessageStatus,
    val manualSelected: Boolean? = null,
)

/** The selection-relevant view of one channel row. [included] is the channel list's select/deselect. */
data class ChannelSel(
    val channelId: Long,
    val displayName: String,
    val mode: DmMode,
    val state: ChannelState,
    val included: Boolean,
) {
    /** A channel can contribute to a run only when included and not known to be gone or inaccessible. */
    val eligible: Boolean get() = included && state != ChannelState.GONE && state != ChannelState.INACCESSIBLE
}

/**
 * A message filter (spec section 6): date range, keyword, attachments-only. All set criteria must match.
 * [fromMillis] is inclusive, [untilMillis] exclusive. The keyword matches case-insensitively anywhere in the text.
 */
data class MessageFilter(
    val fromMillis: Long? = null,
    val untilMillis: Long? = null,
    val keyword: String? = null,
    val attachmentsOnly: Boolean = false,
) {
    private val needle: String? = keyword?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /** A filter with no criterion would select everything in scope; it is refused, never applied. */
    val isEmpty: Boolean get() = fromMillis == null && untilMillis == null && needle == null && !attachmentsOnly

    fun matches(timestampMillis: Long, content: String?, hasAttachments: Boolean): Boolean {
        if (fromMillis != null && timestampMillis < fromMillis) return false
        if (untilMillis != null && timestampMillis >= untilMillis) return false
        if (attachmentsOnly && !hasAttachments) return false
        if (needle != null) {
            val text = content ?: return false
            if (!text.lowercase(Locale.ROOT).contains(needle)) return false
        }
        return true
    }

    fun matches(m: MessageSel): Boolean = matches(m.timestampMillis, m.content, m.hasAttachments)
}

/**
 * The selection rules of spec section 6a, as pure functions. Precedence, highest first: keep > manual toggle >
 * filter result.
 */
object SelectionRules {

    /**
     * The `selected` value a filter run gives [m]: the manual toggle when there is one, else the filter's result.
     * Filters only set or clear `selected`; they never read or write `keep`.
     */
    fun selectedAfterFilter(m: MessageSel, filter: MessageFilter): Boolean {
        require(!filter.isEmpty) { "an empty filter is never applied" }
        return m.manualSelected ?: filter.matches(m)
    }

    /** Applies a filter to [m]: only `selected` can change. */
    fun applyFilter(m: MessageSel, filter: MessageFilter): MessageSel = m.copy(selected = selectedAfterFilter(m, filter))

    /** A tap in the message browser: flips `selected` and records it as a manual toggle. `keep` is untouched. */
    fun manualToggle(m: MessageSel): MessageSel = manualSet(m, !m.selected)

    /** Sets `selected` by hand (multi-select "mark" / "unmark"), recorded as a manual toggle. */
    fun manualSet(m: MessageSel, selected: Boolean): MessageSel = m.copy(selected = selected, manualSelected = selected)

    /** The Keep action. It never changes `selected` or the manual toggle, so un-keeping restores the old intent. */
    fun setKeep(m: MessageSel, keep: Boolean): MessageSel = m.copy(keep = keep)

    /**
     * Whether [m] would be deleted by a run frozen now. `keep` beats every filter and both modes; only PENDING
     * messages in an eligible channel are candidates.
     */
    fun wouldDelete(m: MessageSel, channel: ChannelSel): Boolean {
        if (!channel.eligible) return false
        if (m.channelId != channel.channelId) return false
        if (m.keep) return false
        if (m.status != MessageStatus.PENDING) return false
        return when (channel.mode) {
            DmMode.DELETE_SELECTED -> m.selected
            DmMode.DELETE_ALL_EXCEPT_KEPT -> true
        }
    }
}

data class ChannelPlan(val channelId: Long, val displayName: String, val mode: DmMode, val count: Int)

/**
 * The delete set computed at final review: per-channel counts and every message ID, in run order (channel by
 * channel, newest first within a channel, as the caller streams them).
 */
class DeletePlan(val channels: List<ChannelPlan>, private val ids: LongArray) {
    val total: Int get() = ids.size
    fun messageIds(): LongArray = ids.copyOf()
    fun idAt(position: Int): Long = ids[position]

    /** The channel of the message at [position] (IDs are grouped by channel, in [channels] order). */
    fun channelAt(position: Int): Long {
        require(position in 0 until ids.size)
        var end = 0
        for (c in channels) {
            end += c.count
            if (position < end) return c.channelId
        }
        error("unreachable")
    }

    /** Two plans are the same delete set when they hold the same IDs in the same order. */
    fun sameSetAs(other: DeletePlan): Boolean = ids.contentEquals(other.ids)
}

/**
 * Builds a [DeletePlan] one channel at a time, so the messages can stream from the database in pages: either
 * [addChannel] with a sequence, or [startChannel], [offer] for each row, then [finishChannel].
 */
class DeletePlanBuilder {
    private val channels = ArrayList<ChannelPlan>()
    private val started = HashSet<Long>()
    private var ids = LongArray(1024)
    private var size = 0
    private val seen = HashSet<Long>()
    private var current: ChannelSel? = null
    private var currentCount = 0

    fun startChannel(channel: ChannelSel): DeletePlanBuilder {
        check(current == null) { "previous channel not finished" }
        require(started.add(channel.channelId)) { "channel added twice" }
        current = channel
        currentCount = 0
        return this
    }

    /** Adds [m] to the plan when the rules say the current channel's run would delete it. */
    fun offer(m: MessageSel) {
        val channel = checkNotNull(current) { "no channel started" }
        if (SelectionRules.wouldDelete(m, channel) && seen.add(m.messageId)) {
            if (size == ids.size) ids = ids.copyOf(size * 2)
            ids[size++] = m.messageId
            currentCount++
        }
    }

    fun finishChannel(): DeletePlanBuilder {
        val channel = checkNotNull(current) { "no channel started" }
        if (currentCount > 0) channels.add(ChannelPlan(channel.channelId, channel.displayName, channel.mode, currentCount))
        current = null
        return this
    }

    fun addChannel(channel: ChannelSel, messages: Sequence<MessageSel>): DeletePlanBuilder {
        startChannel(channel)
        if (channel.eligible) messages.forEach { offer(it) }
        return finishChannel()
    }

    fun build(): DeletePlan {
        check(current == null) { "channel not finished" }
        return DeletePlan(channels.toList(), ids.copyOf(size))
    }
}

/** A run's frozen delete set. It never grows: there is no way to add an ID after it is made. */
interface FrozenSet {
    val runId: Long
    val size: Int
    operator fun contains(messageId: Long): Boolean
}

class InMemoryFrozenSet(override val runId: Long, ids: LongArray) : FrozenSet {
    private val sorted: LongArray = ids.copyOf().also { it.sort() }
    override val size: Int get() = sorted.size
    override fun contains(messageId: Long): Boolean = java.util.Arrays.binarySearch(sorted, messageId) >= 0

    companion object {
        fun freeze(runId: Long, plan: DeletePlan): FrozenSet = InMemoryFrozenSet(runId, plan.messageIds())
    }
}

/** The message row as the executor re-reads it immediately before a request (spec section 8). */
data class GuardRow(val messageId: Long, val status: MessageStatus, val keep: Boolean)

/**
 * The delete guard (spec section 8, CLAUDE.md rule 5): a DELETE may be sent only if the message is in the frozen run
 * set, its status is PENDING and keep is false. Keep toggled after freezing therefore blocks the delete; selecting
 * more after freezing adds nothing.
 */
fun deleteGuard(row: GuardRow, frozen: FrozenSet): Boolean =
    row.messageId in frozen && row.status == MessageStatus.PENDING && !row.keep

fun deleteGuard(row: MessageSel, frozen: FrozenSet): Boolean =
    deleteGuard(GuardRow(row.messageId, row.status, row.keep), frozen)
