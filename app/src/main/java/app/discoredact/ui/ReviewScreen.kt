@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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

@Composable
fun ReviewScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val review = vm.review
    Scaffold(topBar = { TopAppBar(title = { Text("Final review") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        val plan = review.plan
        if (plan == null || review.computing) {
            Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text("Computing the delete set...", modifier = Modifier.padding(16.dp))
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Dry run", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (settings.dryRun) "ON: a run sends nothing and only reports what it would delete."
                                    else "OFF: a run would delete for real.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = settings.dryRun, onCheckedChange = { vm.setDryRun(it) })
                        }
                        review.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("Total: ${plan.total} messages in ${plan.channels.size} DMs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        for (c in plan.channels) {
                            Row {
                                Text(c.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.count}  (${modeLabel(c.mode)})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "Kept messages, excluded DMs and messages not pending are never in this set. Confirming freezes exactly this list into a run; selecting more later needs a new review.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider()
                        Text("Every message in the delete set", style = MaterialTheme.typography.titleSmall)
                    }
                }
                val pager = review.pager
                items(plan.total) { i ->
                    val row = pager?.rowAt(i)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (row == null) {
                            Spacer(Modifier.height(40.dp))
                        } else {
                            Text("${i + 1}. ${formatTime(row.timestamp)}", style = MaterialTheme.typography.labelSmall)
                            Text(row.content?.ifEmpty { "(no text)" } ?: "(text purged)", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                if (plan.total == 0) {
                    Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Nothing to delete") }
                } else {
                    HoldToConfirmButton(
                        text = if (review.freezing) "Freezing..." else "Hold to freeze ${plan.total} messages",
                        enabled = !review.freezing,
                        onConfirmed = { vm.confirmFreeze() },
                    )
                }
            }
        }
    }
}

@Composable
fun FrozenScreen(vm: AppViewModel, r: Route.Frozen) {
    Scaffold(topBar = { TopAppBar(title = { Text("Run frozen") }) }) { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Run #${r.runId}: ${r.total} messages in ${r.channels} DMs are frozen into this run.", style = MaterialTheme.typography.titleMedium)
            Text(
                "The executor is not built yet (milestone 2). Nothing was sent and nothing will be deleted. " +
                    "This app has no network code in this version.",
                color = MaterialTheme.colorScheme.error,
            )
            Text("Marking a message Keep now still protects it when the executor exists: it re-checks keep before every delete.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.home() }) { Text("Back to channels") }
        }
    }
}
