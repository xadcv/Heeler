package dev.bybee.heeler.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bybee.heeler.AppContainer
import dev.bybee.heeler.data.HostRecord
import dev.bybee.heeler.herdr.HerdrSessionName
import kotlinx.coroutines.launch
import java.util.UUID

/** Top-level navigation as plain state: three screens do not justify a navigation library. */
private sealed interface Screen {
    data object Hosts : Screen
    data class EditHost(val host: HostRecord?) : Screen
    data object DeviceKey : Screen
    data class Console(val host: HostRecord) : Screen
}

@Composable
fun HeelerApp(container: AppContainer) {
    var screen: Screen by remember { mutableStateOf(Screen.Hosts) }
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    if (screen != Screen.Hosts) BackHandler { screen = Screen.Hosts }

    when (val current = screen) {
        Screen.Hosts -> HostListScreen(
            hosts = hosts,
            onAdd = { screen = Screen.EditHost(null) },
            onEdit = { screen = Screen.EditHost(it) },
            onOpen = { screen = Screen.Console(it) },
            onDeviceKey = { screen = Screen.DeviceKey },
        )
        is Screen.EditHost -> HostEditorScreen(
            initial = current.host,
            onSave = { host -> scope.launch { container.hosts.save(host) }; screen = Screen.Hosts },
            onDelete = current.host?.let { host -> { scope.launch { container.hosts.delete(host.id) }; screen = Screen.Hosts } },
            onCancel = { screen = Screen.Hosts },
        )
        Screen.DeviceKey -> DeviceKeyScreen(container, onBack = { screen = Screen.Hosts })
        is Screen.Console -> ConsoleScreen(container, current.host, onBack = { screen = Screen.Hosts })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostListScreen(
    hosts: List<HostRecord>,
    onAdd: () -> Unit,
    onEdit: (HostRecord) -> Unit,
    onOpen: (HostRecord) -> Unit,
    onDeviceKey: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Hosts") }, actions = { TextButton(onClick = onDeviceKey) { Text("Device Key") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (hosts.isEmpty()) {
                Text(
                    "No Hosts yet. Add the machine where herdr runs, then install this device's key there.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(hosts, key = { it.id }) { host ->
                    ListItem(
                        headlineContent = { Text(host.displayName) },
                        supportingContent = { Text("${host.username}@${host.hostname}:${host.port}" + (host.session?.let { "  ·  session $it" } ?: "")) },
                        trailingContent = { TextButton(onClick = { onEdit(host) }) { Text("Edit") } },
                        modifier = Modifier.clickable { onOpen(host) },
                    )
                    HorizontalDivider()
                }
            }
            Button(onClick = onAdd, Modifier.padding(16.dp).fillMaxWidth()) { Text("Add Host") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostEditorScreen(
    initial: HostRecord?,
    onSave: (HostRecord) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var hostname by rememberSaveable { mutableStateOf(initial?.hostname ?: "") }
    var port by rememberSaveable { mutableStateOf(initial?.port?.toString() ?: "22") }
    var username by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var session by rememberSaveable { mutableStateOf(initial?.session ?: "") }
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = hostname.isNotBlank() && username.isNotBlank() && portValue != null &&
        (session.isBlank() || HerdrSessionName.isValid(session))

    Scaffold(topBar = { TopAppBar(title = { Text(if (initial == null) "Add Host" else "Edit Host") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hostname, { hostname = it }, label = { Text("Hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, isError = portValue == null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("SSH user") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                session, { session = it }, label = { Text("herdr session (blank = default)") }, singleLine = true,
                isError = session.isNotBlank() && !HerdrSessionName.isValid(session),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            HostRecord(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                hostname = hostname.trim(),
                                port = portValue ?: 22,
                                username = username.trim(),
                                session = session.trim().ifBlank { null },
                            ),
                        )
                    },
                ) { Text("Save") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceKeyScreen(container: AppContainer, onBack: () -> Unit) {
    val line = remember { container.deviceKey.authorizedKeysLine() }
    val fingerprint = remember { container.deviceKey.fingerprint() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    DeviceKeyContent(line, fingerprint, onBack) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Heeler Device Key", line))) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceKeyContent(line: String, fingerprint: String, onBack: () -> Unit, onCopy: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Device Key") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "This Ed25519 key was generated on this device and its seed is sealed by the Android Keystore. " +
                    "Append the public line to ~/.ssh/authorized_keys on each Host.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card { SelectionContainer { Text(line, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
            Text("Fingerprint $fingerprint", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCopy) { Text("Copy public key") }
        }
    }
}
