package com.martin.ruby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.martin.ruby.data.Place
import com.martin.ruby.ui.theme.RubyTheme

/**
 * Lists saved places and saves new ones. To save a place, the user stands there and
 * Ruby takes one GPS fix.
 */
@Composable
fun PlacesScreen(
    places: List<Place>,
    currentPlace: String?,
    saving: Boolean,
    message: String?,
    onSave: (name: String) -> Unit,
    onDelete: (Place) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Saved places", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ruby shows a place's name when you're within 100 m of it. Places are kept on " +
                "your phone only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text("Save where you are now", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home", "Work", "Gym").forEach { suggestion ->
                AssistChip(onClick = { name = suggestion }, label = { Text(suggestion) })
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSave(name.trim()) }, enabled = !saving && name.isNotBlank()) {
            Text(if (saving) "Finding your location…" else "Save this spot")
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))
        if (places.isEmpty()) {
            Text("No places saved yet.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn {
                items(places, key = { it.name }) { place ->
                    ListItem(
                        headlineContent = { Text(place.name) },
                        supportingContent = {
                            Text(
                                (if (place.name == currentPlace) "You're here · " else "") +
                                    "%.5f, %.5f".format(place.latitude, place.longitude)
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { onDelete(place) }) { Text("Delete") }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlacesScreenPreview() {
    RubyTheme {
        PlacesScreen(
            places = listOf(Place("Home", 51.50070, -0.12460), Place("Gym", 51.50330, -0.11960)),
            currentPlace = "Home",
            saving = false,
            message = "Saved Home (accurate to about 8 m).",
            onSave = {},
            onDelete = {},
            onBack = {},
        )
    }
}
