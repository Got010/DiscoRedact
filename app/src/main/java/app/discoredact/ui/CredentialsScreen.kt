@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.discoredact.core.executor.CredentialState

/** The status line of the Credentials screen, the Settings screen and the frozen screen. Never a value. */
fun credentialSummary(s: CredentialState?): String = s?.describe(::formatTime) ?: "Checking the stored credentials."

/**
 * Spec section 7: sign in on the phone (B34 Form A, the sign-in screen), or import by "Copy as cURL" from a desktop
 * browser logged into the alt, or the five fields by hand;
 * the status with its age and the 14-day staleness warning; wipe. Values are never shown back.
 */
@Composable
fun CredentialsScreen(vm: AppViewModel) {
    var curl by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var auth by remember { mutableStateOf("") }
    var ua by remember { mutableStateOf("") }
    var props by remember { mutableStateOf("") }
    var locale by remember { mutableStateOf("en-US") }
    var tz by remember { mutableStateOf(java.util.TimeZone.getDefault().id) }
    var confirmWipe by remember { mutableStateOf(false) }
    val status = vm.credentialState
    val work by vm.workState.collectAsState()
    val fetchWork by vm.fetchWork.collectAsState()
    val signInBlocked = vm.signInBlockedReason(work, fetchWork)
    Scaffold(topBar = { TopAppBar(title = { Text("Credentials") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            val warn = status is CredentialState.Unreadable || (status is CredentialState.Present && status.status.stale)
            Text(credentialSummary(status), color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (status is CredentialState.Present && status.status.stale) {
                Text(
                    "Fetch from Discord is allowed with stale headers (reads change nothing on the account), but Discord is more " +
                        "likely to refuse an old client build; capture again first if you can.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Stored encrypted under a key in the phone's Keystore; never logged, never backed up, never shown here. " +
                    "A real run sends these five headers exactly as captured, nothing else.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.credentialNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            HorizontalDivider()
            Text("Sign in on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Opens Discord's own sign-in page in the app. Discord's page handles the password; the app copies only the five " +
                    "headers from the page's own requests to Discord's API, then deletes everything the page stored.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { vm.go(Route.SignIn) },
                enabled = signInBlocked == null && !vm.credentialsBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in here") }
            signInBlocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            Text("Or paste from a desktop browser", style = MaterialTheme.typography.titleMedium)
            Text(
                "On a desktop browser logged into the alt: open the developer tools, Network panel, pick any request to Discord's API, " +
                    "right-click it, Copy > Copy as cURL, and paste it here. The paste is read once for its headers and not kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = curl,
                onValueChange = { curl = it },
                label = { Text("cURL text") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { vm.importCurl(curl); curl = "" },
                enabled = curl.isNotBlank() && !vm.credentialsBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import headers") }

            HorizontalDivider()
            TextButton(onClick = { manual = !manual }) { Text(if (manual) "Hide the manual form" else "Or enter the five fields by hand") }
            if (manual) {
                OutlinedTextField(auth, { auth = it }, label = { Text("Authorization (the token)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ua, { ua = it }, label = { Text("User-Agent") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(props, { props = it }, label = { Text("X-Super-Properties") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(locale, { locale = it }, label = { Text("X-Discord-Locale") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tz, { tz = it }, label = { Text("X-Discord-Timezone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.importManual(auth, ua, props, locale, tz); auth = ""; props = "" },
                    enabled = listOf(auth, ua, props, locale, tz).all { it.isNotBlank() } && !vm.credentialsBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Store these") }
            }

            HorizontalDivider()
            Text("Wipe", style = MaterialTheme.typography.titleMedium)
            Text("Removes the stored headers and their key from this phone. Log out of the browser session as well: that invalidates the token wherever it was copied.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { confirmWipe = true },
                enabled = status?.wipeable == true && !vm.credentialsBusy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Wipe credentials") }
        }
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe credentials?") },
            text = { Text("The stored headers and their key are deleted from this phone. A real run can start again only after a new capture.") },
            confirmButton = { TextButton(onClick = { vm.wipeCredentials(); confirmWipe = false }) { Text("Wipe") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}
