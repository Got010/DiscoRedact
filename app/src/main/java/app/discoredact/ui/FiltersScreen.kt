@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun FiltersScreen(vm: AppViewModel, state: FilterState) {
    var picking by remember { mutableStateOf<String?>(null) }
    fun changed() = vm.schedulePreview(state)

    Scaffold(topBar = { TopAppBar(title = { Text("Filters") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.channelId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.allDms, onClick = { state.allDms = false; changed() }, label = { Text("This DM") })
                    FilterChip(selected = state.allDms, onClick = { state.allDms = true; changed() }, label = { Text("All DMs") })
                }
            } else {
                Text("Scope: all DMs", style = MaterialTheme.typography.titleSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = "from" }) { Text("From: ${state.from ?: "any"}") }
                OutlinedButton(onClick = { picking = "until" }) { Text("Until: ${state.until ?: "any"}") }
            }
            if (state.from != null || state.until != null) {
                TextButton(onClick = { state.from = null; state.until = null; changed() }) { Text("Clear dates") }
            }
            OutlinedTextField(
                value = state.keyword,
                onValueChange = { state.keyword = it; changed() },
                label = { Text("Keyword (any case)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only messages with attachments", modifier = Modifier.weight(1f))
                Switch(checked = state.attachmentsOnly, onCheckedChange = { state.attachmentsOnly = it; changed() })
            }

            if (state.counting || state.applying) LinearProgressIndicator(Modifier.fillMaxWidth())
            val p = state.preview
            if (p != null) {
                Text(
                    listOf(
                        "Messages in scope: ${p.inScope}",
                        "Match this filter: ${p.matches} (of which kept: ${p.matchesKept})",
                        "Selected now: ${p.selectedNow}",
                        "Selected after applying: ${p.selectedAfterApply}",
                        "With a manual choice (a filter never changes these): ${p.manualOverrides}",
                    ).joinToString("\n"),
                )
            }
            Text(
                "Applying sets the selection of every message in scope to the filter's result, except messages you marked or " +
                    "unmarked by hand. Keep is never changed, and a kept message is never deleted.",
                style = MaterialTheme.typography.bodySmall,
            )
            val empty = state.filter().isEmpty
            Button(onClick = { vm.applyFilter(state) }, enabled = !empty && !state.applying, modifier = Modifier.fillMaxWidth()) {
                Text(if (empty) "Set a date, keyword or attachments first" else "Apply filter")
            }
            OutlinedButton(onClick = { vm.clearFilterSelection(state) }, enabled = !state.applying, modifier = Modifier.fillMaxWidth()) {
                Text("Clear filter selections in scope")
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    picking?.let { which ->
        val initial = (if (which == "from") state.from else state.until)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    if (which == "from") state.from = date else state.until = date
                    picking = null
                    changed()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

