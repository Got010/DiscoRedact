@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.PacingSettings
import app.discoredact.core.pacing.QuietHours

private fun presetDescription(p: PacingPreset): String {
    val c = PacingConfig.forPreset(p)
    return "${c.baseDelayMs} ms + 0-${c.jitterMaxMs} ms between deletes, ${c.sessionMaxMs / 60_000}-minute sessions, " +
        "${c.breakMinMs / 60_000}-${c.breakMaxMs / 60_000} minute breaks, ${c.dailyCap} a day"
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val contentCount by vm.contentCount.collectAsState()
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var capText by remember(settings.pacing.dailyCap) { mutableStateOf(settings.pacing.dailyCap.toString()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pacing preset", style = MaterialTheme.typography.titleMedium)
            for (p in PacingPreset.entries) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = settings.pacing.preset == p, onClick = { vm.setPreset(p) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.pacing.preset == p, onClick = { vm.setPreset(p) })
                    Column {
                        Text(if (p == PacingPreset.SLOWER) "Slower (default)" else "Gentle")
                        Text(presetDescription(p), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("A run started after a change uses the new preset; a running session keeps the one it started with.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Daily cap", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = capText,
                    onValueChange = { capText = it.filter(Char::isDigit).take(4) },
                    label = { Text("1 to ${PacingSettings.MAX_DAILY_CAP}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                val parsed = capText.toIntOrNull()
                Button(
                    onClick = { vm.setDailyCap(parsed!!.coerceIn(1, PacingSettings.MAX_DAILY_CAP)) },
                    enabled = parsed != null && parsed in 1..PacingSettings.MAX_DAILY_CAP && parsed != settings.pacing.dailyCap,
                ) { Text("Save") }
            }

            HorizontalDivider()
            Text("Quiet hours (no requests)", style = MaterialTheme.typography.titleMedium)
            val q = settings.pacing.quietHours
            HourStepper("Start", q.startMinute) { vm.setQuietHours(QuietHours(it, q.endMinute)) }
            HourStepper("End", q.endMinute) { vm.setQuietHours(QuietHours(q.startMinute, it)) }
            Text(if (q.isNone) "No quiet hours." else "No requests from ${formatMinute(q.startMinute)} to ${formatMinute(q.endMinute)}, local time.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dry run", style = MaterialTheme.typography.titleMedium)
                    Text("ON by default until one dry run has completed.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.dryRun, onCheckedChange = { vm.setDryRun(it) })
            }

            HorizontalDivider()
            Text("Local data", style = MaterialTheme.typography.titleMedium)
            Text("$contentCount messages have their text stored on this phone (for the message browser and keyword filters).", style = MaterialTheme.typography.bodySmall)
            val busy = vm.busy
            if (busy) Text("An import, filter or freeze is running; Purge and Wipe wait until it ends.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmPurge = true }, enabled = contentCount > 0 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Purge local content") }
            Button(
                onClick = { confirmWipe = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Wipe everything") }
            vm.settingsNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Purge local content?") },
            text = { Text("Deletes the stored text of every message from this phone. IDs, dates, selections and Keep stay, so runs still work; the browser then shows dates only and keyword filters match nothing. This cannot be undone except by importing again.") },
            confirmButton = { TextButton(onClick = { vm.purgeContent(); confirmPurge = false }) { Text("Purge") } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe everything?") },
            text = { Text("Deletes the database (every imported channel and message, every selection, Keep and frozen run) and all settings from this phone. Nothing on Discord changes.") },
            confirmButton = { TextButton(onClick = { vm.wipeEverything(); confirmWipe = false }) { Text("Wipe") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HourStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(Math.floorMod(minute - 60, QuietHours.MINUTES_PER_DAY)) }) { Text("-1 h") }
        Text(formatMinute(minute), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange((minute + 60) % QuietHours.MINUTES_PER_DAY) }) { Text("+1 h") }
    }
}
