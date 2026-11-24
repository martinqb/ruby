package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.martin.ruby.radio.Radio
import com.martin.ruby.radio.Station
import com.martin.ruby.radio.Stations

data class RadioActions(
    val onPlay: (Station) -> Unit = {},
    val onStop: () -> Unit = {},
    val onOpenInIHeart: (Station) -> Unit = {},
    val onAfterBriefing: (Boolean) -> Unit = {},
)

/** Ruby's radio: the stations, what's playing, and starting it after the car's briefing. */
@Composable
fun RadioDialog(state: Radio.State, afterBriefing: Boolean, actions: RadioActions, onClose: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Radio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    state.starting -> Text("Tuning in to ${state.station?.name}…", style = MaterialTheme.typography.titleSmall)
                    state.playing -> Text("Playing ${state.station?.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    state.inIHeart != null -> Text("${state.inIHeart.name} is in iHeartRadio", style = MaterialTheme.typography.titleSmall)
                }
                state.problem?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
                if (state.on) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = actions.onStop, modifier = Modifier.testTag("radio-stop")) { Text("Stop radio") }
                }
                Spacer(Modifier.height(8.dp))
                for (station in Stations.ALL) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(station.name + if (station == Stations.DEFAULT) " (default)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (station.playsInRuby) "${station.city} · plays in Ruby" else "${station.city} · opens in iHeartRadio",
                                style = small, color = muted,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { actions.onPlay(station) }, modifier = Modifier.testTag("radio-play-${station.id}")) {
                            Text(if (station.playsInRuby) "Play" else "Open")
                        }
                    }
                    // The fallback, for a station that plays in Ruby but didn't work.
                    if (station.playsInRuby && state.problem != null && state.station == station) {
                        TextButton(onClick = { actions.onOpenInIHeart(station) }) { Text("Open in iHeartRadio instead") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = afterBriefing, role = Role.Switch, onValueChange = actions.onAfterBriefing)
                        .testTag("radio-after-briefing")
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Play ${Stations.DEFAULT.name} after the morning briefing", style = MaterialTheme.typography.bodyLarge)
                        Text("In the car, once she's finished (not if you stopped her).", style = small, color = muted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = afterBriefing, onCheckedChange = null)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap Talk and say \"play the radio\", \"play WEBN\" or \"stop\". The steering wheel's pause, a headset " +
                        "or \"Hi Bixby, pause\" stop it too. It goes quieter while Ruby talks or listens.",
                    style = small, color = muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
