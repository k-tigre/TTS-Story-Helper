package by.tigre.speechhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.domain.API_VOICES
import by.tigre.speechhelper.domain.API_VOICES_INFO
import by.tigre.speechhelper.domain.SILERO_V5_RU_SPEAKERS
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.VoiceSettings

@Composable
fun VoiceMappingPanel(
    synthesisBackend: SynthesisBackend,
    voiceNames: List<String>,
    activeVoiceNames: Set<String>,
    mapping: Map<String, VoiceSettings>,
    onSettingsChange: (name: String, settings: VoiceSettings) -> Unit,
    onRetryVoice: (name: String) -> Unit,
    onMergeVoice: (fromName: String, toName: String) -> Unit,
    onAddVoice: (name: String) -> Unit,
    onCleanupVoices: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val expandedVoices = remember { mutableStateMapOf<String, Boolean>() }
    var showAddVoiceDialog by remember { mutableStateOf(false) }

    if (showAddVoiceDialog) {
        var newVoiceName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddVoiceDialog = false },
            title = { Text("Добавить голос") },
            text = {
                OutlinedTextField(
                    value = newVoiceName,
                    onValueChange = { newVoiceName = it },
                    label = { Text("Имя голоса") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddVoice(newVoiceName.trim())
                        showAddVoiceDialog = false
                    },
                    enabled = newVoiceName.trim().isNotBlank() && newVoiceName.trim() !in voiceNames,
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddVoiceDialog = false }) { Text("Отмена") }
            },
        )
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Голоса", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { showAddVoiceDialog = true }) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
                if (voiceNames.any { it !in activeVoiceNames }) {
                    TextButton(
                        onClick = onCleanupVoices,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Убрать неиспользуемые", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()

            voiceNames.forEach { name ->
                val isActive = name in activeVoiceNames
                val settings = mapping[name] ?: VoiceSettings()
                val expanded = expandedVoices[name] ?: false
                val isLocal = synthesisBackend == SynthesisBackend.Local
                val voiceInfo = if (isLocal) null else API_VOICES_INFO.find { it.id == settings.voice }
                val availableRoles = voiceInfo?.roles ?: emptyList()
                val voiceOptions = if (isLocal) {
                    (SILERO_V5_RU_SPEAKERS + settings.voice).distinct()
                } else {
                    API_VOICES
                }
                val contentAlpha = if (isActive) 1f else 0.45f
                val speedSummary = "%.1f".format(settings.speed)
                val pitchSummary = "%.0f".format(settings.pitchShift)
                val genderIcon = voiceInfo?.gender ?: "?"
                val summaryRight = if (isLocal) {
                    "${settings.voice} | Silero | x$speedSummary | тон $pitchSummary"
                } else {
                    "${settings.voice}($genderIcon) | ${settings.role.ifBlank { "-" }} | x$speedSummary | ${pitchSummary}Hz"
                }

                Column {
                    // Collapsed header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedVoices[name] = !expanded }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (expanded) "\u25BC" else "\u25B6",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                        Text(
                            text = summaryRight,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        )
                    }

                    // Expanded editor
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Голос:", style = MaterialTheme.typography.bodySmall)
                                DropdownSelector(
                                    label = "",
                                    items = voiceOptions,
                                    selected = settings.voice,
                                    onSelect = { voice ->
                                        val newRoles = API_VOICES_INFO.find { it.id == voice }?.roles ?: emptyList()
                                        val newRole = if (settings.role in newRoles) settings.role else ""
                                        onSettingsChange(name, settings.copy(voice = voice, role = newRole))
                                    },
                                )
                            }

                            if (!isLocal && availableRoles.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Амплуа:", style = MaterialTheme.typography.bodySmall)
                                    DropdownSelector(
                                        label = "",
                                        items = listOf("") + availableRoles,
                                        selected = settings.role,
                                        displayTransform = { it.ifBlank { "нет" } },
                                        onSelect = { onSettingsChange(name, settings.copy(role = it)) },
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (isLocal) "Скорость (SSML): $speedSummary" else "Скорость: $speedSummary",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Slider(
                                    value = settings.speed.toFloat(),
                                    onValueChange = { onSettingsChange(name, settings.copy(speed = it.toDouble())) },
                                    valueRange = 0.1f..3.0f,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (isLocal) "Тембр (SSML): $pitchSummary" else "Тон: $pitchSummary",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Slider(
                                    value = settings.pitchShift.toFloat(),
                                    onValueChange = { onSettingsChange(name, settings.copy(pitchShift = it.toDouble())) },
                                    valueRange = -1000f..1000f,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val otherVoices = voiceNames.filter { it != name }
                                if (otherVoices.isNotEmpty()) {
                                    var mergeExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { mergeExpanded = true },
                                            enabled = !isLoading,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text("Объединить с...", style = MaterialTheme.typography.bodySmall)
                                        }
                                        DropdownMenu(
                                            expanded = mergeExpanded,
                                            onDismissRequest = { mergeExpanded = false },
                                        ) {
                                            otherVoices.forEach { target ->
                                                DropdownMenuItem(
                                                    text = { Text(target) },
                                                    onClick = {
                                                        mergeExpanded = false
                                                        onMergeVoice(name, target)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                Box(modifier = Modifier.weight(1f))

                                TextButton(
                                    onClick = { onRetryVoice(name) },
                                    enabled = !isLoading,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text("Переозвучить", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
