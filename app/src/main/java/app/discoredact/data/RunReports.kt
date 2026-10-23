package app.discoredact.data

/** Joins the Reports queries by run id. Every run in [progress] appears, newest first as given; the rest is looked up. */
fun assembleRunReports(
    progress: List<RunProgress>,
    errors: List<RunErrorGroup>,
    totals: List<RunEventTotals>,
    halts: List<RunLastHalt>,
): List<RunReport> {
    val errorsByRun = errors.groupBy { it.runId }
    val totalsByRun = totals.associateBy { it.runId }
    val haltByRun = halts.associate { it.runId to it.actionTaken }
    return progress.map { p ->
        val t = totalsByRun[p.runId]
        RunReport(
            progress = p,
            errors = errorsByRun[p.runId].orEmpty(),
            noResponse = t?.noResponse ?: 0,
            transientCount = t?.transientCount ?: 0,
            lastHalt = haltByRun[p.runId],
        )
    }
}
