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
import androidx.compose.ui.unit.dp
import app.discoredact.core.executor.CredentialState
import app.discoredact.core.history.FetchEstimate
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.data.EventEntity
import app.discoredact.work.RunScheduler

private fun duration(ms: Long): String = when {
    ms < 60_000 -> "under a minute"
    ms < 3_600_000 -> "about ${ms / 60_000} min"
    else -> "about ${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min"
}

private fun presetName(p: PacingPreset) = when (p) {
    PacingPreset.SLOWER -> "Slower"
    PacingPreset.GENTLE -> "Gentle"
}

/** The last response: its HTTP status and Discord code only. */
private fun lastFetchResponse(events: List<EventEntity>): String? {
    val e = events.firstOrNull { it.httpStatus != null } ?: return null
    return "HTTP ${e.httpStatus}" + (e.discordCode?.let { ", code $it" } ?: "")
}

/**
 * "Fetch from Discord", the notice first (B34 history brief, section 4): what is read, that only the account's own
 * messages are kept, that closed and left DMs are not found this way, the estimate, and that the run's pacing and
 * halts apply. A screen, not a dialog; "Start fetch" begins it.
 */
@Composable
fun FetchNoticeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val runWork by vm.workState.collectAsState()
    val creds = vm.credentialState
    val config = settings.pacing.toConfig()
    Scaffold(topBar = { TopAppBar(title = { Text("Fetch from Discord") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("What is read", style = MaterialTheme.typography.titleMedium)
            Text(
                "With the stored credentials the app asks Discord, one request at a time: your account's id; the list of your " +
                    "open DMs and group DMs; then each DM's message history, 100 messages per request, newest first. " +
                    "Nothing else is read: no servers, no search, no other account.",
            )
            Text("What is kept", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only your own messages: those whose author is this account go into the same lists the data package fills. " +
                    "Everyone else's messages are read and dropped in memory; none of them is stored. A message found by both " +
                    "the package and a fetch is one row, and neither import changes your selections, Keep marks or statuses.",
            )
            Text("What is not found", style = MaterialTheme.typography.titleMedium)
            Text(
                "Closed and left DMs are not found this way. Discord lists only the DMs open in your DM list: a DM you closed " +
                    "(the X beside it) or a group DM you left is not listed, although its messages still exist. The data " +
                    "package is still the way to them; import it as well if you want them.",
                color = MaterialTheme.colorScheme.error,
            )
            Text("How long", style = MaterialTheme.typography.titleMedium)
            val dms = summaries.orEmpty().size
            val own = summaries.orEmpty().sumOf { it.messageCount.toLong() }
            Text(
                "About one request per 100 messages in each DM (everyone's messages fill the pages, not only yours), plus one " +
                    "per DM, plus two.",
            )
            if (dms == 0) {
                Text("The app knows no DMs yet; the DM list is read first, and the Fetch screen shows how many it lists.", style = MaterialTheme.typography.bodySmall)
            } else {
                val requests = FetchEstimate.requests(dms, own)
                val days = FetchEstimate.days(requests, config)
                Text(
                    "The app knows $dms DMs holding $own of your messages now, so at least $requests requests: " +
                        "${duration(FetchEstimate.durationMs(requests, dms, config))} at the ${presetName(settings.pacing.preset)} pace" +
                        (if (days > 1) ", spread over $days days by the daily cap of ${config.dailyCap}" else "") +
                        ". Other people's messages and DMs the app does not know yet add to that.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("The same pacing and halts as a run", style = MaterialTheme.typography.titleMedium)
            Text(
                "One request in flight, the preset's delay and jitter between requests, the pause between DMs, Discord's " +
                    "rate-limit headers followed, quiet hours ${formatMinute(config.quietHours.startMinute)}-${formatMinute(config.quietHours.endMinute)}, " +
                    "and the daily cap of ${config.dailyCap} requests (reads and deletes of the day together). Five invalid " +
                    "responses in ten minutes halt it, as do a rejected token, a captcha, an edge block or an unknown error. " +
                    "Pause and Stop wait for the request in flight; after a kill or reboot at most the page in flight is read again. " +
                    "A read's 403 or 404 marks that DM inaccessible or gone, as a delete's does, which keeps it out of runs; a " +
                    "later fetch that reads it opens it again. An edge block holds runs and fetches alike for an hour; Stop does not lift it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "A fetch deletes nothing and is not a run: the Dry run setting stays as it is, and the final review is still the " +
                    "last step before any delete.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Credentials: " + credentialSummary(creds), style = MaterialTheme.typography.bodySmall)
            if (creds is CredentialState.Present && creds.status.stale) {
                Text(
                    "These headers are stale. A fetch is allowed with them (reads change nothing on the account), but Discord is " +
                        "more likely to refuse an old client build; capture them again first if you can.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val blocked = when {
                vm.wiping -> "Wiping"
                runWork != RunScheduler.WorkState.NONE -> "A run is active; pause or stop it first"
                else -> null
            }
            Button(onClick = { vm.startFetch() }, enabled = blocked == null, modifier = Modifier.fillMaxWidth()) { Text("Start fetch") }
            blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            vm.fetchNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The fetch's progress, like the Run screen: DMs done of listed, your messages found, the last response, Pause, Resume, Stop. */
@Composable
fun FetchScreen(vm: AppViewModel) {
    val view by vm.fetchView.collectAsState()
    val counts by vm.fetchCounts.collectAsState()
    val allEvents by vm.fetchEvents.collectAsState()
    val work by vm.fetchWork.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Fetch from Discord") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val v = view
            if (v == null) {
                Text("No fetch yet.")
                Button(onClick = { vm.go(Route.FetchNotice) }) { Text("Fetch from Discord") }
                return@Column
            }
            val s = v.snapshot
            // This fetch's rows only: the log's rows with no run hold every earlier fetch too.
            val events = allEvents.filter { it.ts >= v.createdAt }
            val now = System.currentTimeMillis()
            val running = work == RunScheduler.WorkState.RUNNING
            val scheduled = work == RunScheduler.WorkState.SCHEDULED
            val holdUntil = s.resumeNotBefore?.takeIf { it > now }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("READS ONLY: nothing is deleted", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(
                    when (work) {
                        RunScheduler.WorkState.RUNNING -> "worker running"
                        RunScheduler.WorkState.SCHEDULED -> "worker scheduled"
                        RunScheduler.WorkState.NONE -> "worker idle"
                    },
                    MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(vm.describeFetch(v))
            if (holdUntil != null) Text("Resume possible at ${formatTime(holdUntil)}.", color = MaterialTheme.colorScheme.error)
            Text("Begun ${formatTime(v.createdAt)}" + (s.endedAt?.let { "; ended ${formatTime(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
            val c = counts
            when {
                s.accountId == null -> Text("Asking Discord which account the stored headers belong to.")
                s.listedAt == null || c == null -> Text("Reading the list of open DMs.")
                else -> {
                    LinearProgressIndicator(progress = { if (c.total == 0) 1f else c.done.toFloat() / c.total }, modifier = Modifier.fillMaxWidth())
                    Text("DMs read: ${c.done} of ${c.total} listed")
                }
            }
            Text("Your messages found: ${v.found}", fontWeight = FontWeight.SemiBold)
            lastFetchResponse(events)?.let { Text("Last response: $it", style = MaterialTheme.typography.bodySmall) }
            events.firstOrNull()?.let { Text("Last event: ${formatTime(it.ts)} ${it.actionTaken}", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running || scheduled) {
                    OutlinedButton(onClick = { vm.pauseFetch() }, modifier = Modifier.weight(1f)) { Text("Pause") }
                } else if (s.endedAt == null) {
                    Button(onClick = { vm.resumeFetch() }, enabled = holdUntil == null, modifier = Modifier.weight(1f)) { Text("Resume") }
                }
                if (s.endedAt == null) {
                    Button(
                        onClick = { vm.stopFetch() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                } else {
                    Button(onClick = { vm.go(Route.FetchNotice) }, modifier = Modifier.weight(1f)) { Text("New fetch") }
                }
            }
            vm.fetchNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Fetched messages appear in the channel list like imported ones: browse, filter, keep, review and run them as " +
                    "usual. Closed and left DMs are not in Discord's list; the data package still finds them. A new fetch reads " +
                    "every listed DM again from its newest page and adds only what is new.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { vm.home() }, modifier = Modifier.fillMaxWidth()) { Text("Back to channels") }
        }
    }
}
