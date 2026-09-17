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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonitorCard(
                running = monitor.running,
                db = monitor.currentDb,
                confidence = monitor.lastConfidence,
                snores = monitor.snoreCount,
                movements = monitor.movementCount,
                pauses = monitor.pauseCount,
                status = monitor.lastMessage,
                onStart = { ensurePermissions(viewModel::startMonitoring) },
                onStop = viewModel::stopMonitoring
            )

            message?.let {
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (it.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                )) { Text(it.text, Modifier.padding(12.dp)) }
            }

            SectionCard("Samsung TV") {
                OutlinedTextField(
                    value = settings.tvIp,
                    onValueChange = viewModel::setTvIp,
                    label = { Text("Indirizzo IP / hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (settings.tvToken.isBlank()) "Token: non ancora acquisito" else "Token: salvato in modo persistente ••••${settings.tvToken.takeLast(4)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::connectTv) { Text("Connetti / abbina") }
                    OutlinedButton(onClick = viewModel::pauseTv) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Text(" KEY_PAUSE")
                    }
                }
                OutlinedButton(onClick = viewModel::clearToken, enabled = settings.tvToken.isNotBlank()) {
                    Text("Dimentica token")
                }
            }

            SectionCard("Rilevamento e automazione") {
                LabelSwitch("Pausa automatica al russamento", settings.automaticPause, viewModel::setAutoPause)
                LabelSwitch("Monitora i movimenti", settings.monitorMovement, viewModel::setMonitorMovement)
                Text("Soglia volume: ${settings.sensitivityDb.roundToInt()} dBFS")
                Slider(
                    value = settings.sensitivityDb,
                    onValueChange = viewModel::setSensitivity,
                    valueRange = -60f..-25f
                )
                Text("Confidenza minima: ${(settings.minConfidence * 100).roundToInt()}%")
                Slider(
                    value = settings.minConfidence,
                    onValueChange = viewModel::setConfidence,
                    valueRange = 0.45f..0.90f
                )
                Text("Intervallo minimo tra pause: ${settings.pauseCooldownMinutes} min")
                Slider(
                    value = settings.pauseCooldownMinutes.toFloat(),
                    onValueChange = { viewModel.setCooldown(it.roundToInt()) },
                    valueRange = 1f..30f,
                    steps = 28
                )
                Text(
                    "L'audio è elaborato sul dispositivo e non viene registrato. Il rilevamento è orientativo e non costituisce un dispositivo medico.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("Ultime sessioni") {
                if (history.isEmpty()) Text("Nessuna sessione salvata")
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
private fun MonitorCard(
    running: Boolean,
    db: Float,
    confidence: Float,
    snores: Int,
    movements: Int,
    pauses: Int,
    status: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, contentDescription = null)
                Text(if (running) " Monitoraggio in corso" else " Monitoraggio fermo", style = MaterialTheme.typography.titleMedium)
            }
            Text(status)
            if (running) {
                Text("${db.roundToInt()} dBFS • confidenza ${(confidence * 100).roundToInt()}%")
                Text("Russamenti $snores  •  Movimenti $movements  •  Pause TV $pauses")
                Button(onClick = onStop) { Icon(Icons.Default.Stop, null); Text(" Termina e salva") }
            } else {
                Button(onClick = onStart) { Text("Avvia sessione") }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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
        Text("${duration?.let { "$it min" } ?: "in corso"} • ${item.snoreCount} russamenti • ${item.movementCount} movimenti • ${item.automaticPauses} pause")
        if (item.endedAt != null) Text("Rumore medio ${item.averageNoiseDb.roundToInt()} dBFS", style = MaterialTheme.typography.bodySmall)
    }
}
