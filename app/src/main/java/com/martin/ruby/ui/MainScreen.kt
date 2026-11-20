package com.martin.ruby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.martin.ruby.data.Car
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionReading
import com.martin.ruby.ui.theme.RubyTheme

/** The home screen: shows Driving or Not driving, plus buttons to set things up. */
@Composable
fun MainScreen(
    car: Car?,
    driving: Boolean,
    hasBluetoothPermission: Boolean,
    onRequestPermission: () -> Unit,
    onChooseCar: () -> Unit,
    backgroundSensing: Boolean,
    onBackgroundSensingChange: (Boolean) -> Unit,
    batteryOptimized: Boolean,
    onAllowBackground: () -> Unit,
    motion: MotionReading?,
    hasActivityPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (driving) "Driving" else "Not driving",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = if (driving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (car == null) "No car chosen yet" else "Car: ${car.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MotionInfo(motion)
        Spacer(Modifier.height(32.dp))

        if (!hasBluetoothPermission) {
            Text(
                text = "Ruby needs Bluetooth access to notice when your phone connects to your car.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermission) { Text("Allow Bluetooth access") }
        } else {
            OutlinedButton(onClick = onChooseCar) {
                Text(if (car == null) "Choose your car" else "Change car")
            }
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sense in the background", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps working when Ruby is closed or the phone restarts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = backgroundSensing, onCheckedChange = onBackgroundSensingChange)
            }

            if (backgroundSensing && !hasActivityPermission) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Allow Physical activity so Ruby can count steps and tell when you're " +
                        "walking. It stays on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestPermission) { Text("Allow Physical activity") }
            }

            if (backgroundSensing && batteryOptimized) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Android may stop Ruby to save battery. Let Ruby run in the background " +
                        "so it doesn't miss the car connecting.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAllowBackground) { Text("Allow background use") }
            }
        }
    }
}

/** The motion result, plus the numbers behind it so the thresholds can be tuned. */
@Composable
private fun MotionInfo(reading: MotionReading?) {
    Text(
        text = "Motion: ${reading?.motion?.label ?: "not sensing"}",
        style = MaterialTheme.typography.titleMedium,
    )
    val details = when {
        reading == null -> "Turn on background sensing to start."
        reading.paused -> "Sensors paused until the phone moves (saves battery)."
        else -> buildString {
            append("Variation %.2f m/s²".format(reading.variation))
            append(" · ")
            append(reading.stepsPerSecond?.let { "%.1f steps/s".format(it) } ?: "no step counter")
        }
    }
    Text(
        text = details,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    RubyTheme {
        MainScreen(
            car = Car("00:11:22:33:44:55", "Corolla"),
            driving = true,
            hasBluetoothPermission = true,
            onRequestPermission = {},
            onChooseCar = {},
            backgroundSensing = true,
            onBackgroundSensingChange = {},
            batteryOptimized = true,
            onAllowBackground = {},
            motion = MotionReading(Motion.IN_VEHICLE, variation = 0.62f, stepsPerSecond = 0f),
            hasActivityPermission = true,
        )
    }
}
