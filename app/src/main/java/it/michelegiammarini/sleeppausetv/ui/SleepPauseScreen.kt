package it.michelegiammarini.sleeppausetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.TvBrand
import it.michelegiammarini.sleeppausetv.data.db.SleepSessionEntity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepPauseScreen(viewModel: MainViewModel, ensurePermissions: (() -> Unit) -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("SleepPause TV") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonitorCard(
                running = monitor.running,
                db = monitor.currentDb,
                snoringScore = monitor.snoringScore,
                breathingScore = monitor.breathingScore,
                speechScore = monitor.speechScore,
                musicScore = monitor.musicScore,
                interferenceScore = monitor.interferenceScore,
                topLabel = monitor.topLabel,
                topScore = monitor.topScore,
                sleepEvents = monitor.sleepEventCount,
                movements = monitor.movementCount,
                pauses = monitor.pauseCount,
                lastEvidence = monitor.lastSleepEvidence,
                status = monitor.lastMessage,
                onStart = { ensurePermissions(viewModel::startMonitoring) },
                onStop = viewModel::stopMonitoring,
            )

            message?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (it.isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) { Text(it.text, Modifier.padding(12.dp)) }
            }

            TvConnectionCard(settings, viewModel)
            DetectionCard(settings, viewModel)

            SectionCard("Recent sessions") {
                if (history.isEmpty()) Text("No saved sessions")
                history.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    SessionRow(item)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetectionCard(settings: AppSettings, viewModel: MainViewModel) {
    SectionCard("Sleep detection and automation") {
        LabelSwitch(
            "Pause the TV after a confirmed sleep event",
            settings.automaticPause,
            viewModel::setAutoPause,
        )

        Text("Snoring", style = MaterialTheme.typography.titleSmall)
        PercentageSlider(
            label = "Minimum YAMNet snoring score",
            value = settings.snoringConfidence,
            onValueChange = viewModel::setSnoringConfidence,
        )
        IntegerSlider(
            label = "Consecutive snoring detections",
            value = settings.snoringConsecutiveDetections,
            range = 1..12,
            onValueChange = viewModel::setSnoringConsecutive,
        )

        HorizontalDivider()
        Text("Breathing", style = MaterialTheme.typography.titleSmall)
        PercentageSlider(
            label = "Minimum YAMNet breathing score",
            value = settings.breathingConfidence,
            onValueChange = viewModel::setBreathingConfidence,
        )
        IntegerSlider(
            label = "Consecutive breathing detections",
            value = settings.breathingConsecutiveDetections,
            range = 1..12,
            onValueChange = viewModel::setBreathingConsecutive,
        )

        HorizontalDivider()
        Text("Minimum sound level: ${settings.sensitivityDb.roundToInt()} dBFS")
        Slider(
            value = settings.sensitivityDb,
            onValueChange = viewModel::setSensitivity,
            valueRange = -60f..-25f,
        )
        Text("Sensitivity versus other sounds: ${(settings.otherSoundSensitivity * 100).roundToInt()}%")
        Slider(
            value = settings.otherSoundSensitivity,
            onValueChange = viewModel::setOtherSoundSensitivity,
            valueRange = 0f..1f,
        )
        Text(
            "Higher values allow breathing or snoring to remain valid when speech, music, TV audio or household scores are stronger. At 100%, the configured sleep percentage is not raised by this filter.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        LabelSwitch("Track device movement", settings.monitorMovement, viewModel::setMonitorMovement)
        if (settings.monitorMovement) {
            IntegerSlider(
                label = "Required time without movement (minutes)",
                value = settings.noMovementMinutes,
                range = 1..60,
                onValueChange = viewModel::setNoMovementMinutes,
            )
            Text(
                "The TV is paused only when a sleep event is confirmed and no device movement has been detected for this period.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Movement gating is off. A confirmed sleep event may pause the TV immediately, subject to the cooldown.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        IntegerSlider(
            label = "Minimum time between automatic pauses (minutes)",
            value = settings.pauseCooldownMinutes,
            range = 1..60,
            onValueChange = viewModel::setCooldown,
        )
        Text(
            "A sleep event is created when either signal reaches its own threshold for the configured number of consecutive YAMNet windows. If both are present, one combined event is counted. Detection runs locally; raw audio is never saved or transmitted. This is not a medical device.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PercentageSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Text("$label: ${(value * 100).roundToInt()}%")
    Slider(value = value, onValueChange = onValueChange, valueRange = 0.10f..0.95f)
}

@Composable
private fun IntegerSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Text("$label: $value")
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}

@Composable
private fun TvConnectionCard(settings: AppSettings, viewModel: MainViewModel) {
    SectionCard("TV connection") {
        Text("TV platform", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandChip(TvBrand.SAMSUNG, settings, viewModel)
            BrandChip(TvBrand.LG_WEBOS, settings, viewModel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandChip(TvBrand.ROKU, settings, viewModel)
            BrandChip(TvBrand.HOME_ASSISTANT, settings, viewModel)
        }

        if (settings.tvBrand == TvBrand.HOME_ASSISTANT) {
            OutlinedTextField(
                value = settings.homeAssistantUrl,
                onValueChange = viewModel::setHomeAssistantUrl,
                label = { Text("Home Assistant URL") },
                placeholder = { Text("https://home.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.homeAssistantToken,
                onValueChange = viewModel::setHomeAssistantToken,
                label = { Text("Long-lived access token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.homeAssistantEntity,
                onValueChange = viewModel::setHomeAssistantEntity,
                label = { Text("Media player entity") },
                placeholder = { Text("media_player.living_room_tv") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Use this option for Android/Google TV, Fire TV, Sony, Philips, TCL, Hisense, Panasonic and other TVs already integrated with Home Assistant.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            OutlinedTextField(
                value = settings.tvIp,
                onValueChange = viewModel::setTvIp,
                label = { Text("TV IP address or hostname") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.tvBrand == TvBrand.SAMSUNG || settings.tvBrand == TvBrand.LG_WEBOS) {
                Text(
                    if (settings.tvToken.isBlank()) {
                        "Pairing key: not acquired"
                    } else {
                        "Pairing key: saved locally ••••${settings.tvToken.takeLast(4)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                when (settings.tvBrand) {
                    TvBrand.SAMSUNG -> "The first connection prompts for approval on the Samsung TV."
                    TvBrand.LG_WEBOS -> "The first connection prompts for approval on the LG webOS TV."
                    TvBrand.ROKU -> "Enable control by mobile apps in Roku settings if required. Roku uses a Play/Pause toggle."
                    TvBrand.HOME_ASSISTANT -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::connectTv) { Text("Connect / pair") }
            OutlinedButton(onClick = viewModel::pauseTv) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Text(" Test pause")
            }
        }
        if (settings.tvBrand == TvBrand.SAMSUNG || settings.tvBrand == TvBrand.LG_WEBOS) {
            OutlinedButton(onClick = viewModel::clearToken, enabled = settings.tvToken.isNotBlank()) {
                Text("Forget pairing key")
            }
        }
    }
}

@Composable
private fun BrandChip(brand: TvBrand, settings: AppSettings, viewModel: MainViewModel) {
    FilterChip(
        selected = settings.tvBrand == brand,
        onClick = { viewModel.setTvBrand(brand) },
        label = { Text(brand.displayName) },
    )
}

@Composable
private fun MonitorCard(
    running: Boolean,
    db: Float,
    snoringScore: Float,
    breathingScore: Float,
    speechScore: Float,
    musicScore: Float,
    interferenceScore: Float,
    topLabel: String,
    topScore: Float,
    sleepEvents: Int,
    movements: Int,
    pauses: Int,
    lastEvidence: String,
    status: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, contentDescription = null)
                Text(
                    if (running) " Monitoring" else " Monitoring stopped",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(status)
            if (running) {
                Text("${db.roundToInt()} dBFS • $topLabel ${(topScore * 100).roundToInt()}%")
                Text(
                    "Snoring ${(snoringScore * 100).roundToInt()}% • Breathing ${(breathingScore * 100).roundToInt()}%",
                )
                Text(
                    "Other sounds: speech ${(speechScore * 100).roundToInt()}% • music ${(musicScore * 100).roundToInt()}% • interference ${(interferenceScore * 100).roundToInt()}%",
                )
                Text("Sleep events $sleepEvents • Movements $movements • TV pauses $pauses")
                if (sleepEvents > 0) Text("Last sleep evidence: $lastEvidence")
                Button(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(" Stop and save")
                }
            } else {
                Button(onClick = onStart) { Text("Start session") }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabelSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SessionRow(item: SleepSessionEntity) {
    val start = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.startedAt))
    val duration = item.endedAt?.let { TimeUnit.MILLISECONDS.toMinutes(it - item.startedAt) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(start, style = MaterialTheme.typography.titleSmall)
        Text(
            "${duration?.let { "$it min" } ?: "in progress"} • ${item.sleepEventCount} sleep events • ${item.movementCount} movements • ${item.automaticPauses} pauses",
        )
        if (item.endedAt != null) {
            Text(
                "Average noise ${item.averageNoiseDb.roundToInt()} dBFS",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
