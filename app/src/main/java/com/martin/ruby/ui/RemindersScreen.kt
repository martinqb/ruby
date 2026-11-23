package com.martin.ruby.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.martin.ruby.BuildConfig
import com.martin.ruby.reminders.Reminder
import com.martin.ruby.reminders.ReminderNotifier
import com.martin.ruby.reminders.ReminderAlarms
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.reminders.ReminderText
import kotlinx.coroutines.launch

/** Every reminder that's waiting or has gone off, plus the last week's finished ones. */
@Composable
fun RemindersScreen(store: ReminderStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(store) { store.forScreen() }
    val reminders by flow.collectAsState(initial = emptyList())

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Reminders", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ask Ruby in the chat, e.g. \"Remind me to call Mom at 5\" or \"Remind me to buy milk " +
                "when I get to the store\". Place reminders use your saved places.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        if (ReminderAlarms.canBeExact(context)) {
            // Usually because Ruby may run in the background (Phase 2): Android lets apps it
            // doesn't battery-optimize set exact alarms without the separate permission.
            Text(
                "Time reminders go off on time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(
                "Time reminders can be a few minutes late until you allow Ruby to set exact alarms.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                )
            }) { Text("Allow exact timing") }
        }
        if (BuildConfig.DEBUG) {
            // For checking how the notification looks; it isn't saved, so its buttons do nothing.
            TextButton(onClick = {
                ReminderNotifier.show(
                    context,
                    Reminder(id = TEST_ID, text = "Test reminder", dueMillis = System.currentTimeMillis(), createdMillis = 0),
                    late = false,
                )
            }) { Text("Send a test reminder (debug)") }
        }

        Spacer(Modifier.height(16.dp))
        if (reminders.isEmpty()) {
            Text("No reminders yet.", style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(reminders, key = { it.id }) { r ->
                ListItem(
                    overlineContent = { Text(describe(r)) },
                    headlineContent = { Text(r.text) },
                    trailingContent = {
                        when (r.state) {
                            Reminder.STATE_PENDING ->
                                TextButton(onClick = { scope.launch { store.cancel(r.id) } }) { Text("Cancel") }
                            Reminder.STATE_FIRED ->
                                TextButton(onClick = { scope.launch { store.done(r.id) } }) { Text("Done") }
                            else -> Unit
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

/** The debug test reminder's id: high enough never to be a real one. */
private const val TEST_ID = 999_999L

private fun describe(r: Reminder): String {
    val whenText = r.place?.let { "When you get to $it" } ?: ReminderText.time(r.dueMillis!!)
    val state = when (r.state) {
        Reminder.STATE_FIRED -> " · went off"
        Reminder.STATE_DONE -> " · done"
        Reminder.STATE_CANCELLED -> " · cancelled"
        else -> ""
    }
    return whenText + state
}
