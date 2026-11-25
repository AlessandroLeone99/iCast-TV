package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
fun UpdateSettings(viewModel: MainViewModel) {
    val updater = viewModel.updater
    val state by updater.state.collectAsState()
    val auto by viewModel.pref(Prefs.AUTO_CHECK_UPDATES).collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Versione installata: ${updater.installedVersion}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Controlla all'avvio", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { viewModel.set(Prefs.AUTO_CHECK_UPDATES, it) },
                modifier = Modifier.dpadFocus())
        }
        val release = state.release
        if (release != null) Text("Disponibile: ${release.version}")
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.checking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.progress?.let {
            LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Download: $it%")
        }
        when {
            state.checking || state.progress != null -> TextButton(onClick = updater::cancel, modifier = Modifier.dpadFocus()) { Text("Annulla") }
            state.ready -> Button(onClick = { updater.install(context) }, modifier = Modifier.dpadFocus()) { Text("Installa aggiornamento") }
            release != null -> Button(onClick = updater::download, modifier = Modifier.dpadFocus()) { Text("Scarica aggiornamento") }
        }
        if (!state.checking && state.progress == null) {
            OutlinedButton(onClick = { updater.check() }, modifier = Modifier.dpadFocus()) { Text("Controlla aggiornamenti") }
        }
    }
}
