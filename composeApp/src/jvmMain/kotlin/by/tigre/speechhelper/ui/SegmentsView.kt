package by.tigre.speechhelper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.data.AudioPlayer
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.domain.API_VOICES_INFO
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun SegmentsView(
    segments: List<TextSegment>,
    voiceMapping: Map<String, VoiceSettings>,
    synthesizeAudio: (String, VoiceSettings) -> Flow<SynthesisResult>,
    onSegmentTextChange: (index: Int, newText: String) -> Unit,
    onRemarkupSegment: (index: Int) -> Unit,
    onSplitSegment: (index: Int, parts: List<String>) -> Unit,
    onChangeSegmentVoice: (index: Int, newVoiceName: String?) -> Unit,
    availableVoiceNames: List<String>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playingIndex by remember { mutableStateOf(-1) }
    var playError by remember { mutableStateOf<String?>(null) }
    var splitDialogIndex by remember { mutableStateOf(-1) }

    if (splitDialogIndex >= 0 && splitDialogIndex < segments.size) {
        val seg = segments[splitDialogIndex]
        SplitSegmentDialog(
            segmentText = seg.text,
            voiceName = seg.voiceName,
            onDismiss = { splitDialogIndex = -1 },
            onSplit = { parts ->
                onSplitSegment(splitDialogIndex, parts)
                splitDialogIndex = -1
            },
        )
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            val settings = if (segment.voiceName != null) {
                voiceMapping[segment.voiceName] ?: VoiceSettings()
            } else {
                VoiceSettings()
            }
            val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
            val isPlaying = playingIndex == index

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Segment number badge
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        // Voice label (clickable to change)
                        Box {
                            var voiceDropdownExpanded by remember { mutableStateOf(false) }
                            Text(
                                text = segment.voiceName ?: "без голоса",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (segment.voiceName != null)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { voiceDropdownExpanded = true },
                            )
                            DropdownMenu(
                                expanded = voiceDropdownExpanded,
                                onDismissRequest = { voiceDropdownExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text("без голоса", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    onClick = {
                                        onChangeSegmentVoice(index, null)
                                        voiceDropdownExpanded = false
                                    },
                                )
                                availableVoiceNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = name,
                                                fontWeight = if (name == segment.voiceName) FontWeight.Bold else null,
                                            )
                                        },
                                        onClick = {
                                            onChangeSegmentVoice(index, name)
                                            voiceDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Mapped voice info
                        Text(
                            text = "\u2192 ${settings.voice} (${voiceInfo?.gender ?: "?"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )

                        // Split button
                        IconButton(
                            onClick = { splitDialogIndex = index },
                            enabled = !isLoading && playingIndex == -1,
                        ) {
                            Text(
                                "\u2702",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }

                        // Remarkup button
                        IconButton(
                            onClick = { onRemarkupSegment(index) },
                            enabled = !isLoading && playingIndex == -1,
                        ) {
                            Text(
                                "\u2728",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        // Play button
                        if (isPlaying) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(
                                onClick = {
                                    playError = null
                                    playingIndex = index
                                    scope.launch {
                                        try {
                                            synthesizeAudio(segment.text, settings).collectLatest { result ->
                                                when (result) {
                                                    is SynthesisResult.InProgress -> {}
                                                    is SynthesisResult.Done -> AudioPlayer.play(result.bytes)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            playError = "#${index + 1}: ${e.message}"
                                        } finally {
                                            playingIndex = -1
                                        }
                                    }
                                },
                                enabled = !isLoading && playingIndex == -1,
                            ) {
                                Text(
                                    "\u25B6",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = segment.text,
                        onValueChange = { onSegmentTextChange(index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (playError != null) {
            item {
                Text(
                    text = playError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
