package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.martin.ruby.briefing.BriefingModule
import com.martin.ruby.briefing.BriefingProfile
import com.martin.ruby.briefing.BriefingState
import com.martin.ruby.briefing.BriefingText
import com.martin.ruby.briefing.Interest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the briefing screen needs to know besides the briefing's own state. */
data class BriefingSetup(
    val hasCar: Boolean = false,
    val hasHome: Boolean = false,
    val hasCalendar: Boolean = false,
    val working: Boolean = false,
    /** Ruby is talking (the briefing or anything else). */
    val speaking: Boolean = false,
)

data class BriefingActions(
    val onEnable: (Boolean) -> Unit = {},
    val onProfile: (BriefingProfile) -> Unit = {},
    val onHearNow: () -> Unit = {},
    /** Stops her talking, and a briefing still being put together. */
    val onStopTalking: () -> Unit = {},
    val onAllowCalendar: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** The morning briefing: on or off, which segments, the interest, and the last one spoken. */
@Composable
fun BriefingScreen(state: BriefingState, setup: BriefingSetup, actions: BriefingActions, modifier: Modifier = Modifier) {
    val profile = state.profile
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TextButton(onClick = actions.onBack) { Text("Back") }
        Text("Morning briefing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "The first time your car connects each morning (4 AM to noon), Ruby greets you with a short " +
                "spoken briefing in her voice, under 2 minutes, made from the parts you choose below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow("Morning briefing", if (state.enabled) "On" else "Off", state.enabled, "briefing-switch") { on ->
            actions.onEnable(on)
            if (on && profile.has(BriefingModule.GLANCE) && !setup.hasCalendar) actions.onAllowCalendar()
        }
        if (!setup.hasCar) Text("Choose your car on the main screen first, so she knows when you get in.", style = small, color = MaterialTheme.colorScheme.error)
        if (!setup.hasHome) Text("Save a place called Home for the weather and tonight's sky.", style = small, color = MaterialTheme.colorScheme.error)
        if (profile.has(BriefingModule.GLANCE) && !setup.hasCalendar) {
            Text("Ruby reads today's events from your calendar for \"Today at a glance\".", style = small)
            OutlinedButton(onClick = actions.onAllowCalendar) { Text("Allow calendar") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Parts of the briefing", style = MaterialTheme.typography.titleMedium)
        for (module in BriefingModule.entries) {
            SwitchRow(module.label, module.explained, module in profile.modules, "module-${module.tag}") { on ->
                var next = profile.with(module, on)
                // Turning the interest on with none chosen picks the only one there is so far.
                if (module == BriefingModule.INTEREST && on && next.interest == null) next = next.copy(interest = Interest.entries.first())
                actions.onProfile(next)
                if (module == BriefingModule.GLANCE && on && state.enabled && !setup.hasCalendar) actions.onAllowCalendar()
            }
            if (module == BriefingModule.INTEREST && module in profile.modules) {
                Column(Modifier.padding(start = 16.dp)) {
                    Interest.entries.forEach { interest ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = profile.interest == interest, role = Role.RadioButton) {
                                    actions.onProfile(profile.copy(interest = interest))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = profile.interest == interest, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(interest.label, style = MaterialTheme.typography.bodyLarge)
                                Text(interest.explained, style = small, color = muted)
                            }
                        }
                    }
                    Text("More interests, like sports or news, will come later.", style = small, color = muted)
                }
            }
        }
        val words = profile.spoken.sumOf { it.words }
        Spacer(Modifier.height(4.dp))
        Text("About $words words, roughly ${BriefingText.seconds(words)} seconds.", style = small, color = muted)

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.onHearNow, enabled = !setup.working && profile.spoken.isNotEmpty()) {
                Text(if (setup.working) "Putting it together…" else "Hear it now")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = actions.onStopTalking, enabled = setup.speaking || setup.working) {
                Text("Stop talking")
            }
        }
        Text("Plays today's briefing now, any time. It doesn't count as this morning's.", style = small, color = muted)

        if (state.lastText != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            val format = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
            Text("Last briefing, ${format.format(Date(state.lastMillis))}", style = MaterialTheme.typography.titleMedium)
            state.lastNote?.let { Text(it, style = small, color = muted) }
            Spacer(Modifier.height(4.dp))
            Text(state.lastText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("last-briefing"))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("What leaves the phone", style = MaterialTheme.typography.titleSmall)
        Text(
            "Open-Meteo gets your Home place's rough location (about 1 km) for the forecast. Wikipedia gets " +
                "today's date for music history. Anthropic gets the morning's facts (weather, event titles and " +
                "times, reminders, tonight's sky, the music moments) once a day, so Claude can write the " +
                "briefing. Tonight's sky is worked out on the phone.",
            style = small,
            color = muted,
        )
    }
}

@Composable
private fun SwitchRow(label: String, explained: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(explained, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
