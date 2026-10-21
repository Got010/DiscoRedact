@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.discoredact.core.executor.Eta
import app.discoredact.core.executor.Executor
import app.discoredact.data.EventEntity
import app.discoredact.data.RunProgress
import app.discoredact.work.RunScheduler

private fun minutes(ms: Long): String = when {
    ms < 60_000 -> "under a minute"
    ms < 3_600_000 -> "about ${ms / 60_000} min"
    else -> "about ${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min"
}

/** Deletions (or dry-run verdicts) per minute over the last ten minutes of events. */
private fun ratePerMinute(events: List<EventEntity>, now: Long): Double {
    val window = 10 * 60_000L
    val n = events.count { it.ts >= now - window && (it.actionTaken == "DELETED" || it.actionTaken == "DRY_WOULD_DELETE") }
    return n / 10.0
}

private fun lastResponse(events: List<EventEntity>): String? {
    val e = events.firstOrNull { it.httpStatus != null } ?: return null
    return buildString {
        append("HTTP ").append(e.httpStatus)
        e.discordCode?.let { append(", code ").append(it) }
        append(": ").append(e.actionTaken)
        e.remaining?.let { append("  (remaining ").append(it) }
        e.resetAfter?.let { append(", reset after ").append(it).append(" s") }
        if (e.remaining != null) append(")")
    }
}

@Composable
fun RunScreen(vm: AppViewModel) {
    val progress by vm.runProgress.collectAsState()
    val events by vm.runEvents.collectAsState()
    val nextChannel by vm.runNextChannel.collectAsState()
    val lines by vm.runChannelLines.collectAsState()
    val work by vm.workState.collectAsState()
    val settings by vm.settings.collectAsState()
    val deletedToday by vm.deletedToday.collectAsState()
    val p = progress
    Scaffold(topBar = { TopAppBar(title = { Text(if (p == null) "Run" else "Run #${p.runId}") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        if (p == null) {
            Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("No run yet. Freeze a delete set at the final review first.")
                Button(onClick = { vm.home() }) { Text("Back to channels") }
            }
            return@Scaffold
        }
        val now = System.currentTimeMillis()
        val running = work == RunScheduler.WorkState.RUNNING
        val scheduled = work == RunScheduler.WorkState.SCHEDULED
        val resumable = Executor.isResumable(p.stopReason, p.endedAt, p.startedAt) && !running
        val holdUntil = p.resumeNotBefore?.takeIf { it > now }
        val config = settings.pacing.toConfig()
        val eta = Eta.estimate(p.pending, config, if (p.isDryRun) 0 else deletedToday)
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (p.isDryRun) "DRY RUN: nothing is sent" else "REAL RUN: deletes for real",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = if (p.isDryRun) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Badge(
                            when (work) {
                                RunScheduler.WorkState.RUNNING -> "worker running"
                                RunScheduler.WorkState.SCHEDULED -> "worker scheduled"
                                RunScheduler.WorkState.NONE -> "worker idle"
                            },
                            MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Executor.describe(p.stopReason, p.endedAt, p.startedAt))
                    if (holdUntil != null) Text("Resume possible at ${formatTime(holdUntil)}.", color = MaterialTheme.colorScheme.error)
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else (if (p.isDryRun) p.dryWouldDelete else p.settled).toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (p.isDryRun) {
                        Text("${p.dryWouldDelete} of ${p.total} checked: would delete ${p.dryWouldDelete}; kept or unreachable ${p.blocked}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${p.settled} of ${p.total} settled: deleted ${p.deleted}, already gone ${p.alreadyGone}, system ${p.skippedSystem}, failed ${p.failed}", style = MaterialTheme.typography.bodySmall)
                        Text("Pending ${p.pending}; kept or unreachable ${p.blocked}", style = MaterialTheme.typography.bodySmall)
                    }
                    nextChannel?.let { Text("Next channel: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val rate = ratePerMinute(events, now)
                    if (running) Text("Rate: ${"%.1f".format(rate)} per minute (last 10 min)")
                    if (p.endedAt == null && p.pending > 0 && !p.isDryRun) {
                        Text(
                            buildString {
                                append("Estimate: ${eta.today} more today, ${minutes(eta.todayMs)} of running time")
                                if (eta.later > 0) append("; ${eta.later} more over ${eta.laterDays} further day(s) at the daily cap of ${config.dailyCap}")
                                append(". Quiet hours ${formatMinute(config.quietHours.startMinute)}-${formatMinute(config.quietHours.endMinute)} add to that.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    lastResponse(events)?.let { Text("Last response: $it", style = MaterialTheme.typography.bodySmall) }
                    events.firstOrNull()?.let { Text("Last event: ${formatTime(it.ts)} ${it.actionTaken}", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.startedAt == null && p.endedAt == null && !running && !scheduled) {
                            Button(onClick = { vm.startRun(p.runId) }, modifier = Modifier.weight(1f)) { Text(if (settings.dryRun) "Start dry run" else "Start run") }
                        } else if (running || scheduled) {
                            OutlinedButton(onClick = { vm.pauseRun(p.runId) }, modifier = Modifier.weight(1f)) { Text("Pause") }
                        } else if (resumable) {
                            Button(onClick = { vm.resumeRun(p.runId) }, enabled = holdUntil == null && p.pending > 0, modifier = Modifier.weight(1f)) { Text("Resume") }
                        }
                        if (p.endedAt == null) {
                            Button(
                                onClick = { vm.stopRun(p.runId) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                modifier = Modifier.weight(1f),
                            ) { Text("Stop") }
                        }
                    }
                    if (p.endedAt == null && p.pending == 0 && p.blocked > 0) {
                        Text("Nothing is pending and reachable; the rest is kept or in channels that are gone or inaccessible. Stop ends the run.", style = MaterialTheme.typography.bodySmall)
                    }
                    vm.runNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Marking a message Keep while the run goes protects it: the guard re-reads every row right before its request. " +
                            "Pause and Stop wait for the request in flight; a kill or reboot resumes from the per-message status with no repeat.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    Text("Per channel", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(lines, key = { it.channelId }) { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(l.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (p.isDryRun) "${l.dryWouldDelete}/${l.total}" else "${l.deleted}/${l.total}" + if (l.state.name == "PENDING" || l.state.name == "ACTIVE" || l.state.name == "DONE") "" else " (${l.state.name.lowercase()})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { vm.home() }, modifier = Modifier.fillMaxWidth()) { Text("Back to channels") }
                }
            }
        }
    }
}
