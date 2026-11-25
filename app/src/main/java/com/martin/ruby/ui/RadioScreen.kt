package com.martin.ruby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.martin.ruby.radio.Preset
import com.martin.ruby.radio.Presets
import com.martin.ruby.radio.Radio
import com.martin.ruby.radio.RadioBrowser
import com.martin.ruby.radio.Station
import kotlinx.coroutines.launch

data class RadioActions(
    val onPlay: (Station) -> Unit = {},
    val onStop: () -> Unit = {},
    val onOpenInIHeart: (Station) -> Unit = {},
    val onOpenInOtherApp: (Station) -> Unit = {},
    val onAfterBriefing: (Boolean) -> Unit = {},
    /** Changes the saved stations (add, rename, move, make default, remove). */
    val onPresets: ((Presets) -> Presets) -> Unit = {},
    /** Searches for stations; throws if nothing could be reached. */
    val search: suspend (RadioBrowser.By, String) -> List<Station> = { _, _ -> emptyList() },
    val onBack: () -> Unit = {},
)

/** Ruby's radio: what's playing, the saved stations, finding new ones, and after-briefing play. */
@Composable
fun RadioScreen(state: Radio.State, presets: Presets, afterBriefing: Boolean, actions: RadioActions, modifier: Modifier = Modifier) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var finding by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = actions.onBack) { Text("Back") }
        Text("Radio", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // Now playing
        when {
            state.starting -> Text("Tuning in to ${state.station?.name}…", style = MaterialTheme.typography.titleSmall)
            state.playing -> Text("Playing ${state.station?.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            state.inIHeart != null -> Text("${state.inIHeart.name} is in iHeartRadio", style = MaterialTheme.typography.titleSmall)
        }
        state.problem?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
        if (state.on) OutlinedButton(onClick = actions.onStop, modifier = Modifier.testTag("radio-stop")) { Text("Stop radio") }
        // The fallbacks, for a station that didn't play here.
        val failed = state.station?.takeIf { state.problem != null && !state.on }
        if (failed != null) {
            FlowRow {
                if (failed.iHeartId != null) TextButton(onClick = { actions.onOpenInIHeart(failed) }) { Text("Open in iHeartRadio") }
                TextButton(onClick = { actions.onOpenInOtherApp(failed) }) { Text("Open in another radio app") }
            }
        }

        // My stations, with Find a station right next to the heading
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My stations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { finding = !finding }, modifier = Modifier.testTag("find-station")) {
                Text(if (finding) "Done finding" else "Find a station")
            }
        }
        if (presets.list.isEmpty()) {
            Text("No stations saved yet. Tap Find a station to add some.", style = small, color = muted)
        }
        presets.list.forEachIndexed { i, preset ->
            PresetRow(preset, isDefault = preset == presets.default, first = i == 0, last = i == presets.list.lastIndex, actions)
        }

        if (finding) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            FindStation(presets, actions)
            HorizontalDivider()
        }

        // After the briefing
        Spacer(Modifier.height(12.dp))
        val default = presets.default
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = afterBriefing, enabled = default != null, role = Role.Switch, onValueChange = actions.onAfterBriefing)
                .testTag("radio-after-briefing")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Play ${default?.label ?: "your default station"} after the morning briefing", style = MaterialTheme.typography.bodyLarge)
                Text("In the car, once she's finished (not if you stopped her).", style = small, color = muted)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = afterBriefing, onCheckedChange = null, enabled = default != null)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Talk and say \"play the radio\" (your default), \"play\" and a station's name, or \"stop\". The steering " +
                "wheel's pause, a headset or \"Hi Bixby, pause\" stop it too. It goes quieter while Ruby talks or listens, " +
                "keeps playing with the screen locked, and reconnects if the stream drops.",
            style = small, color = muted,
        )
    }
}

@Composable
private fun PresetRow(preset: Preset, isDefault: Boolean, first: Boolean, last: Boolean, actions: RadioActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val id = preset.station.id
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("preset-$id"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(preset.label + if (isDefault) " (default)" else "", style = MaterialTheme.typography.bodyLarge)
            Text(describe(preset.station), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { actions.onPlay(preset.station) }, modifier = Modifier.testTag("play-$id")) {
            Text(if (preset.station.playsInRuby) "Play" else "Open")
        }
        Column {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag("more-$id")) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                if (!first) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; actions.onPresets { it.move(id, -1) } })
                if (!last) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; actions.onPresets { it.move(id, +1) } })
                if (!isDefault) DropdownMenuItem(text = { Text("Make default") }, onClick = { menu = false; actions.onPresets { it.makeDefault(id) } })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; actions.onPresets { it.remove(id) } })
            }
        }
    }
    if (renaming) {
        var label by remember { mutableStateOf(preset.label) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename station") },
            text = {
                Column {
                    OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, modifier = Modifier.testTag("rename-field"))
                    Text("Say \"play\" and this name to play it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; actions.onPresets { it.rename(id, label) } }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FindStation(presets: Presets, actions: RadioActions) {
    var text by rememberSaveable { mutableStateOf("") }
    var by by rememberSaveable { mutableStateOf(RadioBrowser.By.NAME) }
    var results by remember { mutableStateOf<List<Station>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun go() {
        if (text.isBlank()) return
        searching = true
        problem = null
        scope.launch {
            results = try {
                actions.search(by, text)
            } catch (e: Exception) {
                problem = "Couldn't search: ${e.message ?: "no connection"}."
                null
            }
            searching = false
        }
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Find a station", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in RadioBrowser.By.entries) FilterChip(selected = by == b, onClick = { by = b }, label = { Text(b.label) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(when (by) { RadioBrowser.By.NAME -> "Station name"; RadioBrowser.By.PLACE -> "City or state"; RadioBrowser.By.GENRE -> "Genre, like jazz" }) },
                modifier = Modifier.weight(1f).testTag("search-field"),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::go, enabled = !searching && text.isNotBlank(), modifier = Modifier.testTag("search-go")) {
                Text(if (searching) "…" else "Search")
            }
        }
        Text("From the free Radio Browser directory; only your search words are sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        results?.let { list ->
            if (list.isEmpty()) Text("No stations found.", style = MaterialTheme.typography.bodySmall)
            list.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("result-${s.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(describe(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.onPlay(s) }) { Text(if (s.playsInRuby) "Play" else "Open") }
                    if (presets.has(s)) {
                        Text("Saved", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        TextButton(onClick = { actions.onPresets { it.add(s) } }, modifier = Modifier.testTag("save-${s.id}")) { Text("Save") }
                    }
                }
            }
        }
    }
}

private fun describe(s: Station): String = listOf(
    s.city,
    s.genre,
    when {
        s.playsInRuby -> "plays in Ruby"
        s.iHeartId != null -> "opens in iHeartRadio"
        else -> "opens in another radio app"
    },
).filter { it.isNotBlank() }.joinToString(" · ")
