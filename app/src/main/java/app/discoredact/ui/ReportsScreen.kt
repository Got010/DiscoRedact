@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.discoredact.core.executor.Executor
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.report.LeftoversCsv
import app.discoredact.data.RoomRunStore
import app.discoredact.data.RunErrorGroup
import app.discoredact.data.RunReport
import java.time.LocalDate

private fun statusLabel(s: MessageStatus) = when (s) {
    MessageStatus.PENDING -> "Pending"
    MessageStatus.DELETED -> "Deleted"
    MessageStatus.ALREADY_GONE -> "Already gone"
    MessageStatus.SKIPPED_SYSTEM -> "System messages skipped"
    MessageStatus.INACCESSIBLE -> "Inaccessible"
    MessageStatus.FAILED -> "Failed"
}

private fun errorLine(g: RunErrorGroup) =
    "HTTP ${g.httpStatus}" + (g.discordCode?.let { ", code $it" } ?: "") + ": ${g.count}"

/** Reports (spec section 6): totals by status, every run with its counts and errors, and the leftovers export. */
@Composable
fun ReportsScreen(vm: AppViewModel) {
    val counts by vm.statusCounts.collectAsState()
    val kept by vm.keptCount.collectAsState()
    val leftovers by vm.leftoverCount.collectAsState()
    val reports by vm.reportRuns.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) vm.exportLeftovers(uri)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Reports") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Totals by status", style = MaterialTheme.typography.titleMedium)
                    val byStatus = counts?.associate { it.status to it.count }
                    if (byStatus == null) {
                        Text("Reading...")
                    } else {
                        for (s in MessageStatus.entries) CountLine(statusLabel(s), byStatus[s] ?: 0)
                        CountLine("Total imported", byStatus.values.sum())
                        HorizontalDivider()
                        CountLine("Kept (never deleted; counted in the statuses above too)", kept)
                        CountLine("Leftovers (see below)", leftovers)
                    }
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Leftovers export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Leftovers are message IDs the app could not delete: IDs in spaces the alt can no longer reach (left group DMs, " +
                            "deleted channels), and IDs Discord refused. A message you marked Keep is never listed. Discord has reportedly deleted messages from a supplied ID list " +
                            "sent to privacy@discord.com. That is unverified, so treat it as best effort.",
                    )
                    Text(
                        "The file holds channel IDs and message IDs only: no text, no times, no account data. Nothing is sent anywhere by this app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { picker.launch(LeftoversCsv.suggestedName(LocalDate.now())) },
                        enabled = !vm.exporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export leftovers ($leftovers) as CSV") }
                    vm.reportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Runs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Newest first. Tap a run for its channels. Errors come from the event log, which keeps only the newest ${RoomRunStore.KEEP_EVENTS} events.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (reports == null) Text("Reading...") else if (reports.orEmpty().isEmpty()) Text("No run has been frozen yet.")
                }
            }
            items(reports.orEmpty(), key = { it.progress.runId }) { r ->
                val id = r.progress.runId
                RunReportCard(vm, r, id in expanded) { expanded = if (id in expanded) expanded - id else expanded + id }
            }
        }
    }
}

@Composable
private fun CountLine(label: String, n: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(n.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RunReportCard(vm: AppViewModel, r: RunReport, open: Boolean, onToggle: () -> Unit) {
    val p = r.progress
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Run #${p.runId}: ${if (p.isDryRun) "dry run" else "real run"}" + if (p.dryRun == null) " (mode not recorded)" else "",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(Executor.describe(p.stopReason, p.endedAt, p.startedAt))
        Text(
            "Started ${p.startedAt?.let { formatTime(it) } ?: "not started"}; ended ${p.endedAt?.let { formatTime(it) } ?: "not ended"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Frozen set ${p.total} in ${p.channels} DMs: deleted ${p.deleted}, already gone ${p.alreadyGone}, system ${p.skippedSystem}, " +
                "failed ${p.failed}, pending ${p.pending}, blocked ${p.blocked}" +
                (if (p.isDryRun) ", would delete ${p.dryWouldDelete}" else "") +
                ". Deleted counter ${p.deletedCount}.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (open) {
            val errorsText = buildList {
                r.errors.forEach { add(errorLine(it)) }
                if (r.noResponse > 0) add("No response: ${r.noResponse}")
                if (r.transientCount > 0) add("Retried server or network errors: ${r.transientCount}")
            }
            Text("Errors", style = MaterialTheme.typography.labelLarge)
            if (errorsText.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodySmall)
            for (line in errorsText) Text(line, style = MaterialTheme.typography.bodySmall)
            r.lastHalt?.let { Text("Last halt: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Text("Per channel", style = MaterialTheme.typography.labelLarge)
            val lines by remember(p.runId) { vm.observeRunChannelLines(p.runId) }.collectAsState(initial = emptyList())
            for (l in lines) {
                Row(Modifier.fillMaxWidth()) {
                    Text(l.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        (if (p.isDryRun) "would delete ${l.dryWouldDelete}" else "deleted ${l.deleted}") + " of ${l.total}" +
                            (if (l.state == ChannelState.INACCESSIBLE || l.state == ChannelState.GONE) " (${l.state.name.lowercase()})" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}
