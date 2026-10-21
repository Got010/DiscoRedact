@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.discoredact.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import app.discoredact.data.ChannelSummary
import app.discoredact.data.MessageRow

/** Whether the browser marks a row "will delete": the same rule as :core's SelectionRules.wouldDelete. */
private fun willDelete(r: MessageRow, c: ChannelSummary?): Boolean {
    if (c == null) return false
    return app.discoredact.core.selection.SelectionRules.wouldDelete(r.toSel(), c.toSel())
}

@Composable
fun BrowserScreen(vm: AppViewModel, state: BrowserState) {
    val summary by remember(state.channelId) { vm.observeSummary(state.channelId) }.collectAsState(initial = null)
    val count by remember(state.channelId, state.query) { vm.observeMessageCount(state.channelId, state.query) }.collectAsState(initial = 0)
    LaunchedEffect(state) { state.pager.refresh() }
    val multi = state.multi.isNotEmpty()

    Scaffold(
        topBar = {
            if (multi) {
                TopAppBar(
                    title = { Text("${state.multi.size} selected") },
                    navigationIcon = { IconButton(onClick = { state.multi.clear() }) { Icon(Icons.Filled.Close, contentDescription = "End multi-select") } },
                )
            } else {
                TopAppBar(
                    title = { Text(summary?.displayName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { BackButton(vm) },
                    actions = { TextButton(onClick = { vm.go(Route.Filters(state.channelId)) }) { Text("Filters") } },
                )
            }
        },
        bottomBar = {
            if (multi) {
                val ids = state.multi.keys.toList()
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { vm.setKeep(ids, true); state.multi.clear() }) { Text("Keep") }
                        TextButton(onClick = { vm.setKeep(ids, false); state.multi.clear() }) { Text("Un-keep") }
                        TextButton(onClick = { vm.setSelected(ids, true); state.multi.clear() }) { Text("Mark delete") }
                        TextButton(onClick = { vm.setSelected(ids, false); state.multi.clear() }) { Text("Unmark") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val s = summary
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in DmMode.entries) {
                        FilterChip(
                            selected = s?.mode == m,
                            onClick = { vm.setMode(state.channelId, m) },
                            label = { Text(modeLabel(m)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge("will delete ${s?.willDelete ?: 0}", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    Badge("kept ${s?.kept ?: 0}", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    if (s != null && !s.included) Badge("channel excluded", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s?.mode == DmMode.DELETE_ALL_EXCEPT_KEPT) {
                    Text(
                        "Every message without Keep will be deleted. Taps and filters change the selection but do not matter in this mode; use Keep (star) to spare a message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Tap a message to mark or unmark it for deletion. Long-press to select several.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state.search(it) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search this DM") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("$count messages", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            val pager = state.pager
            LazyColumn(Modifier.weight(1f)) {
                items(count) { index ->
                    val row = pager.rowAt(index)
                    if (row == null) {
                        Spacer(Modifier.fillMaxWidth().height(64.dp))
                    } else {
                        MessageItem(
                            row = row,
                            deletes = willDelete(row, s),
                            inMulti = state.multi.containsKey(row.messageId),
                            onTap = { if (multi) state.flip(row.messageId) else vm.toggle(row) },
                            onLongPress = { state.multi[row.messageId] = true },
                            onKeep = { vm.setKeep(listOf(row.messageId), !row.keep) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MessageItem(row: MessageRow, deletes: Boolean, inMulti: Boolean, onTap: () -> Unit, onLongPress: () -> Unit, onKeep: () -> Unit) {
    val bg = when {
        inMulti -> MaterialTheme.colorScheme.primaryContainer
        deletes -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onTap, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTime(row.timestamp), style = MaterialTheme.typography.labelMedium)
                if (row.hasAttachments) Icon(AttachmentIcon, contentDescription = "Has attachments", modifier = Modifier.size(16.dp))
                if (deletes) Badge("will delete", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
                if (row.keep) Badge("kept", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                if (row.status != MessageStatus.PENDING) Badge(row.status.name.lowercase(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val text = row.content
            Text(
                when {
                    text == null -> "(text purged)"
                    text.isEmpty() -> "(no text)"
                    else -> text
                },
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onKeep) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (row.keep) "Un-keep" else "Keep",
                tint = if (row.keep) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
