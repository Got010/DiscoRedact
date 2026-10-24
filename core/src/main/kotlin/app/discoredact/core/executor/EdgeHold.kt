package app.discoredact.core.executor

/**
 * The edge-block hold (CLAUDE.md rule 8, spec section 9: manual resume only, not before an hour). An edge block is the
 * edge's verdict on the client or the address, not on one operation, so the hold is app-wide: after a run or a fetch
 * halts on one, neither a run nor a fetch may start or resume until an hour after the newest such halt, and Stop or a
 * new fetch does not clear it. Its source is the shared event log: both halts write an action starting [ACTION].
 */
object EdgeHold {
    const val HOLD_MS: Long = 60 * 60 * 1000L

    /** The event text both sessions write when they halt on an edge block ("HALT edge", "HALT edge 503"). */
    val ACTION: String = "HALT ${HaltReason.EDGE_BLOCK.key}"

    fun isEdgeHalt(actionTaken: String): Boolean = actionTaken == ACTION || actionTaken.startsWith("$ACTION ")

    /** When the hold ends, from the newest edge halt's time; null when there was none. */
    fun until(lastEdgeHaltAt: Long?): Long? = lastEdgeHaltAt?.let { it + HOLD_MS }
}
