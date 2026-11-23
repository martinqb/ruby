package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martin.ruby.BuildConfig
import com.martin.ruby.memory.DaySummary
import com.martin.ruby.memory.Summaries
import com.martin.ruby.memory.SummarySettings
import com.martin.ruby.memory.SummaryStore
import com.martin.ruby.memory.SummaryText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Day summaries (Phase 6 step 4): when they're made, how the last run went, and the summaries. */
@Composable
fun SummariesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SummaryStore(context) }
    val settings = remember { SummarySettings(context) }
    val summariesFlow = remember(store) { store.all() }
    val summaries by summariesFlow.collectAsState(initial = emptyList())
    val status by settings.status.collectAsState(initial = SummarySettings.Status())
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Day summaries", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Each night, Ruby asks Claude to summarize days of chat older than ${Summaries.AGE_DAYS} days, " +
                    "up to ${Summaries.MAX_DAYS_PER_RUN} days a night. Only that day's messages are sent, with your " +
                    "API key. The messages themselves stay in memory. When Ruby recalls an old day, she can send " +
                    "its summary instead of many messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Summarize nightly (charging, on Wi-Fi)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = status.nightlyOn,
                    onCheckedChange = { on ->
                        scope.launch {
                            settings.setNightlyOn(on)
                            if (on) Summaries.schedule(context) else Summaries.cancel(context)
                        }
                    },
                )
            }
            status.lastRunMillis?.let { last ->
                Text(
                    "Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))}: " +
                        (status.lastResult ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                Summaries.runNow(context)
                message = "Summarizing in the background. \"Last run\" above updates when it's done."
            }) { Text("Summarize now") }
            if (BuildConfig.DEBUG) {
                // For trying summaries without waiting a week; sends those days to Claude.
                TextButton(onClick = {
                    Summaries.runNow(context, includeRecent = true)
                    message = "Summarizing every day, today too, in the background. Today's summary " +
                        "is deleted when the next message is saved, so it can't go out of date."
                }) { Text("Summarize all days, including today (debug)") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                if (summaries.isEmpty()) "No summaries yet." else "${summaries.size} days summarized, newest first.",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(summaries, key = { it.id }) { summary ->
            SummaryItem(summary, onDelete = { scope.launch { store.delete(summary.day) } })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SummaryItem(summary: DaySummary, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${SummaryText.label(summary.day)} · ${summary.messageCount} messages",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            // It's made again on the next run, from the day's messages as they are then.
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        Text(summary.text, style = MaterialTheme.typography.bodyMedium)
    }
}
