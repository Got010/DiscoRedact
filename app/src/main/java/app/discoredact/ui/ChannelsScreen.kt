@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.DmMode
import app.discoredact.data.ChannelSummary

fun modeLabel(mode: DmMode) = when (mode) {
    DmMode.DELETE_SELECTED -> "Delete selected"
    DmMode.DELETE_ALL_EXCEPT_KEPT -> "Delete all except kept"
}

@Composable
fun BackButton(vm: AppViewModel) {
    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

@Composable
fun DryRunBanner(on: Boolean) {
    Badge(
        text = if (on) "Dry run: ON" else "Dry run: OFF",
        container = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        content = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
fun ChannelsScreen(vm: AppViewModel) {
    val summaries by vm.summaries.collectAsState()
    val settings by vm.settings.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DiscoRedact") },
                actions = {
                    TextButton(onClick = { vm.go(Route.Import) }) { Text("Import") }
                    IconButton(onClick = { vm.go(Route.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { pad ->
        val list = summaries
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (settings.importUnfinished && vm.importState !is ImportState.Running) {
                Badge(
                    text = "Last import did not finish; import the package again",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { vm.go(Route.Import) },
                )
            }
            ChannelsBody(vm, list, settings.dryRun)
        }
    }
}

@Composable
private fun ChannelsBody(vm: AppViewModel, list: List<ChannelSummary>?, dryRun: Boolean) {
    Box(Modifier.fillMaxSize()) {
        when {
            list == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }
            list.isEmpty() -> Column(Modifier.padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("No data package imported yet.", style = MaterialTheme.typography.titleMedium)
                Text("Pick the ZIP of your Discord data package. Only its messages folder is read; account, activity, servers and everything else are ignored.")
                Button(onClick = { vm.go(Route.Import) }) { Text("Import data package") }
            }
            else -> Column(Modifier.fillMaxSize()) {
                val total = list.sumOf { it.willDelete }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${list.size} DMs, $total will delete", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    DryRunBanner(dryRun)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.setAllIncluded(true) }) { Text("Include all") }
                    TextButton(onClick = { vm.setAllIncluded(false) }) { Text("Exclude all") }
                    TextButton(onClick = { vm.go(Route.Filters(null)) }) { Text("Filters (all DMs)") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.channelId }) { c -> ChannelRow(vm, c) }
                }
                HorizontalDivider()
                Button(onClick = { vm.go(Route.Review) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Final review") }
            }
        }
    }
}

@Composable
private fun ChannelRow(vm: AppViewModel, c: ChannelSummary) {
    Row(
        Modifier.fillMaxWidth().clickable { vm.go(Route.Browser(c.channelId)) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = c.included, onCheckedChange = { vm.setIncluded(c.channelId, it) })
        Column(Modifier.weight(1f)) {
            Text(c.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (c.type == ChannelType.DM) "DM" else "Group DM"} · ${c.messageCount} messages · ${c.state.name.lowercase()} · ${modeLabel(c.mode)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!c.included) Badge("excluded", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                Badge("will delete ${c.willDelete}", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                Badge("kept ${c.kept}", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
    HorizontalDivider()
}

@Composable
fun ImportScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.import(uri) }
    Scaffold(topBar = { TopAppBar(title = { Text("Import") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Pick the ZIP of your Discord data package (Settings > Data & Privacy > Request data in Discord).")
            Text(
                "Only entries under messages/ are read. account/, activity/, servers/, ads/ and support tickets are never opened. " +
                    "Server channels are skipped. Re-importing adds new messages and keeps your selections.",
                style = MaterialTheme.typography.bodySmall,
            )
            when (val s = vm.importState) {
                ImportState.Idle -> Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                    Text("Pick data package")
                }
                is ImportState.Running -> {
                    CircularProgressIndicator()
                    val p = s.progress
                    Text(if (p == null) "Reading channels..." else "Read ${p.messagesRead} messages (${p.channelsDone} of ${p.channelsTotal} DMs)")
                }
                is ImportState.Done -> {
                    val r = s.report
                    Text("Import finished", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(
                            "DMs and group DMs: ${r.channels} (${r.dmChannels} DM, ${r.groupDmChannels} group)",
                            "Messages read: ${r.messagesRead}",
                            "New messages: ${r.messagesInserted}",
                            "Already in the app: ${r.duplicates}",
                            "Unreadable rows skipped: ${r.malformedRows}",
                            "Files that stopped part-way: ${r.malformedFiles}",
                            "Server channels skipped: ${r.skippedGuildChannels}",
                            "Other folders skipped: ${r.skippedOtherFolders}",
                            "Times taken from message IDs: ${r.timestampsFromId}",
                        ).joinToString("\n"),
                    )
                    Button(onClick = { vm.resetImport(); vm.home() }) { Text("Go to channels") }
                    OutlinedButton(onClick = { vm.resetImport() }) { Text("Import another") }
                }
                is ImportState.Failed -> {
                    Text("Import failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Text("Messages imported before the failure stay; importing again skips them.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.resetImport() }) { Text("Try again") }
                }
            }
        }
    }
}
