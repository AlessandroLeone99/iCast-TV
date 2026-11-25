package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import io.github.jqssun.airplay.renderer.SubtitleTrack

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SubtitleOverlay(cues: List<Cue>, scale: Float, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SubtitleView(context).apply {
                setApplyEmbeddedFontSizes(false)
                setBottomPaddingFraction(0.09f)
                isFocusable = false
                isClickable = false
            }
        },
        update = { view ->
            view.setFractionalTextSize(0.0533f * scale)
            view.setCues(cues)
        }
    )
}

@Composable
fun SubtitleSelector(
    tracks: List<SubtitleTrack>,
    scale: Float,
    onSelect: (SubtitleTrack?, Boolean) -> Unit,
    onScale: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sottotitoli (CC)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tracks.isEmpty()) {
                    Text("Nessuna traccia sottotitoli ricevuta dal video.")
                }
                Column(
                    Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                ) {
                    TextButton(
                        onClick = { onSelect(null, true) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).focusRing()
                    ) { Text("Disattivati") }
                    TextButton(
                        onClick = { onSelect(null, false) },
                        modifier = Modifier.fillMaxWidth().focusRing()
                    ) { Text("Automatici (italiano preferito)") }
                    tracks.forEach { track ->
                        TextButton(
                            onClick = { onSelect(track, false) },
                            enabled = track.supported,
                            modifier = Modifier.fillMaxWidth().focusRing()
                        ) {
                            Text(track.label + if (track.selected) " ✓" else if (!track.supported) " (non supportato)" else "")
                        }
                    }
                }
                Text("Dimensione testo")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onScale(0.75f) }, modifier = Modifier.focusRing()) {
                        Text(if (scale == 0.75f) "Piccolo ✓" else "Piccolo")
                    }
                    TextButton(onClick = { onScale(1f) }, modifier = Modifier.focusRing()) {
                        Text(if (scale == 1f) "Normale ✓" else "Normale")
                    }
                    TextButton(onClick = { onScale(1.5f) }, modifier = Modifier.focusRing()) {
                        Text(if (scale == 1.5f) "Grande ✓" else "Grande")
                    }
                }
                LaunchedEffect(Unit) {
                    focus.requestFocusUntilLanded(attempts = 10)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing()) { Text("Chiudi") }
        }
    )
}
