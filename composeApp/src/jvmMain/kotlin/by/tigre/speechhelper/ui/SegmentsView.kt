package by.tigre.speechhelper.ui

import by.tigre.speechhelper.ui.vm.SegmentViewVoiceFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
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

private fun segmentVoiceFilterLabel(f: SegmentViewVoiceFilter): String = when (f) {
    SegmentViewVoiceFilter.All -> "Все голоса"
    is SegmentViewVoiceFilter.Only -> f.voiceName
    SegmentViewVoiceFilter.Unvoiced -> "Без голоса"
}

@Composable
fun SegmentsView(
    segments: List<TextSegment>,
    voiceMapping: Map<String, VoiceSettings>,
    voiceListFilter: SegmentViewVoiceFilter,
    onVoiceListFilterChange: (SegmentViewVoiceFilter) -> Unit,
    synthesizeAudio: (String, VoiceSettings) -> Flow<SynthesisResult>,
    onSegmentTextChange: (index: Int, newText: String) -> Unit,
    onRemarkupSegment: (index: Int) -> Unit,
    onSplitSegment: (index: Int, parts: List<String>) -> Unit,
    onMergeWithPrevious: (index: Int) -> Unit,
    onMergeWithNext: (index: Int) -> Unit,
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

    // Не оборачивать в remember(segments): при mutableStateList тот же instance после
    // removeAt/add — ссылка не меняется, и кэш displayRows остаётся старым.
    val displayRows = segments.mapIndexed { idx, seg -> idx to seg }.filter { (_, seg) ->
        when (voiceListFilter) {
            SegmentViewVoiceFilter.All -> true
            is SegmentViewVoiceFilter.Only -> seg.voiceName == voiceListFilter.voiceName
            SegmentViewVoiceFilter.Unvoiced -> seg.voiceName == null
        }
    }

    val namedVoicesInSegments = segments.mapNotNull { it.voiceName }.distinct().sorted()
    val hasUnvoicedSegments = segments.any { it.voiceName == null }

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Показать:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var filterMenuExpanded by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { filterMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(segmentVoiceFilterLabel(voiceListFilter), style = MaterialTheme.typography.bodyMedium)
                }
                DropdownMenu(
                    expanded = filterMenuExpanded,
                    onDismissRequest = { filterMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Все голоса") },
                        onClick = {
                            onVoiceListFilterChange(SegmentViewVoiceFilter.All)
                            filterMenuExpanded = false
                        },
                    )
                    namedVoicesInSegments.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = name,
                                    fontWeight = if (voiceListFilter is SegmentViewVoiceFilter.Only &&
                                        voiceListFilter.voiceName == name
                                    ) {
                                        FontWeight.Bold
                                    } else {
                                        null
                                    },
                                )
                            },
                            onClick = {
                                onVoiceListFilterChange(SegmentViewVoiceFilter.Only(name))
                                filterMenuExpanded = false
                            },
                        )
                    }
                    if (hasUnvoicedSegments) {
                        DropdownMenuItem(
                            text = { Text("Без голоса") },
                            onClick = {
                                onVoiceListFilterChange(SegmentViewVoiceFilter.Unvoiced)
                                filterMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (displayRows.isEmpty() && segments.isNotEmpty()) {
            Text(
                text = "Нет сегментов для выбранного фильтра.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(displayRows, key = { it.first }) { (realIndex, segment) ->
                val settings = if (segment.voiceName != null) {
                    voiceMapping[segment.voiceName] ?: VoiceSettings()
                } else {
                    VoiceSettings()
                }
                val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
                val isPlaying = playingIndex == realIndex

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${realIndex + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }

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
                                            onChangeSegmentVoice(realIndex, null)
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
                                                onChangeSegmentVoice(realIndex, name)
                                                voiceDropdownExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "\u2192 ${settings.voice} (${voiceInfo?.gender ?: "?"})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )

                            IconButton(
                                onClick = { splitDialogIndex = realIndex },
                                enabled = !isLoading && playingIndex == -1,
                            ) {
                                Text(
                                    "\u2702",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }

                            var mergeNeighborExpanded by remember(realIndex) { mutableStateOf(false) }
                            val canMergePrev = realIndex > 0
                            val canMergeNext = realIndex < segments.lastIndex
                            Box {
                                TextButton(
                                    onClick = { mergeNeighborExpanded = true },
                                    enabled = !isLoading && playingIndex == -1 && (canMergePrev || canMergeNext),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        "С соседним",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                                DropdownMenu(
                                    expanded = mergeNeighborExpanded,
                                    onDismissRequest = { mergeNeighborExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("С предыдущим") },
                                        onClick = {
                                            mergeNeighborExpanded = false
                                            onMergeWithPrevious(realIndex)
                                        },
                                        enabled = canMergePrev,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Со следующим") },
                                        onClick = {
                                            mergeNeighborExpanded = false
                                            onMergeWithNext(realIndex)
                                        },
                                        enabled = canMergeNext,
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onRemarkupSegment(realIndex) },
                                enabled = !isLoading && playingIndex == -1,
                            ) {
                                Text(
                                    "\u2728",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }

                            if (isPlaying) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                IconButton(
                                    onClick = {
                                        playError = null
                                        playingIndex = realIndex
                                        scope.launch {
                                            try {
                                                synthesizeAudio(segment.text, settings).collectLatest { result ->
                                                    when (result) {
                                                        is SynthesisResult.InProgress -> {}
                                                        is SynthesisResult.Done -> AudioPlayer.play(result.bytes)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                playError = "#${realIndex + 1}: ${e.message}"
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
                            onValueChange = { onSegmentTextChange(realIndex, it) },
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
}
