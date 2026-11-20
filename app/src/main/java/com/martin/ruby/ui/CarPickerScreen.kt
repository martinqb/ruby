package com.martin.ruby.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.martin.ruby.data.Car
import com.martin.ruby.ui.theme.RubyTheme

/** Lists the phone's paired Bluetooth devices so the user can tap the one that is their car. */
@Composable
fun CarPickerScreen(
    devices: List<Car>,
    selectedAddress: String?,
    onPick: (Car) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Which one is your car?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty()) {
            Text(
                "No paired devices found. Pair your phone with your car in Android's " +
                    "Bluetooth settings first, then come back here.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn {
                items(devices, key = { it.address }) { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(device.address) },
                        trailingContent = {
                            if (device.address == selectedAddress) Text("Your car")
                        },
                        modifier = Modifier.clickable { onPick(device) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CarPickerScreenPreview() {
    RubyTheme {
        CarPickerScreen(
            devices = listOf(
                Car("00:11:22:33:44:55", "Corolla"),
                Car("66:77:88:99:AA:BB", "Earbuds"),
            ),
            selectedAddress = "00:11:22:33:44:55",
            onPick = {},
            onBack = {},
        )
    }
}
