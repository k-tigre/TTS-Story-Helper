package by.tigre.speechhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.data.AudioPlayer
import by.tigre.speechhelper.data.SynthesisResult
import by.tigre.speechhelper.domain.API_VOICES_INFO
import by.tigre.speechhelper.domain.ParagraphReadiness
import by.tigre.speechhelper.domain.ParagraphReadinessLabel
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Один вертикальный скролл: слева абзацы оригинала, справа карточки сегментов,
 * сопоставленных валидацией с этим абзацем.
 */
@Composable
fun ParagraphAlignedSplitView(
    originalText: String,
    onOriginalTextChange: (String) -> Unit,
    segments: List<TextSegment>,
    validationResult: ValidationResult?,
    voiceMapping: Map<String, VoiceSettings>,
    synthesizeAudio: (String, VoiceSettings) -> Flow<SynthesisResult>,
    onSegmentTextChange: (Int, String) -> Unit,
    onSplitSegment: (Int, List<String>) -> Unit,
    onMergeWithPrevious: (Int) -> Unit,
    onMergeWithNext: (Int) -> Unit,
    onRemarkupSegment: (Int) -> Unit,
    /** Индексы абзацев (1-я колонка), где батч авторазметки не сопоставился с ответом модели. */
    remarkupParagraphIndices: Set<Int> = emptySet(),
    onRemarkupParagraph: (Int) -> Unit = {},
    onChangeSegmentVoice: (Int, String?) -> Unit,
    availableVoiceNames: List<String>,
    isLoading: Boolean,
    onRevalidate: () -> Unit,
    /** Фрагменты разметки по абзацам (как в хранилище), в том же порядке, что и [originalText]. */
    markedParagraphs: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playingIndex by remember { mutableIntStateOf(-1) }
    var playError by remember { mutableStateOf<String?>(null) }
    var splitDialogIndex by remember { mutableIntStateOf(-1) }

    var paraDrafts by remember { mutableStateOf(TextParser.splitParagraphsForStorage(originalText)) }
    LaunchedEffect(originalText) {
        paraDrafts = TextParser.splitParagraphsForStorage(originalText)
    }

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

    val outlineColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelMedium
    val remarkupBg = Color(0xFFFF9800).copy(alpha = 0.14f)
    val unmatched = validationResult?.unmatchedSegmentIndices.orEmpty()

    fun segmentIndicesForParagraph(paraIndex: Int, paraCount: Int): List<Int> {
        if (validationResult != null && paraIndex < validationResult.paragraphs.size) {
            return validationResult.paragraphs[paraIndex].matchedSegmentIndices
        }
        if (validationResult == null && paraCount == 1 && segments.isNotEmpty()) {
            return segments.indices.toList()
        }
        return emptyList()
    }

    val showRows: List<Pair<Int, String>> = remember(paraDrafts) {
        if (paraDrafts.isNotEmpty()) {
            paraDrafts.mapIndexed { i, p -> i to p }
        } else if (segments.isNotEmpty()) {
            listOf(0 to "")
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, outlineColor, RoundedCornerShape(4.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Исходный текст (по абзацам)", style = labelStyle, modifier = Modifier.weight(1f))
                if (validationResult != null) {
                    val validCount = validationResult.paragraphs.count { it.isValid }
                    val totalCount = validationResult.paragraphs.size
                    val indicatorColor = if (validationResult.isFullyValid) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFF9800)
                    }
                    Text(
                        text = "$validCount/$totalCount",
                        style = labelStyle.copy(color = indicatorColor),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = onRevalidate,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Проверить", style = labelStyle)
                }
                VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 4.dp))
                Text("Сегменты разбивки", style = labelStyle, modifier = Modifier.weight(1f))
            }
            Text(
                "Ярлыки у абзаца: «Готово» — есть [voice] и проверка; «Без [voice]» — попадёт в «Недостающие» по порядку; «Ошибка» — после «Проверить».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(showRows.size, key = { showRows[it].first }) { rowIdx ->
                val (paraIndex, paraText) = showRows[rowIdx]
                val paraCount = if (paraDrafts.isNotEmpty()) paraDrafts.size else 1
                val segIdxs = segmentIndicesForParagraph(paraIndex, paraCount)
                val needsParagraphRemarkup = paraIndex in remarkupParagraphIndices

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (needsParagraphRemarkup) remarkupBg else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Абзац ${paraIndex + 1}",
                                style = labelStyle,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            val markedSlice = markedParagraphs.getOrNull(paraIndex) ?: ""
                            val paraMapping = validationResult?.paragraphs?.getOrNull(paraIndex)
                            val readiness = ParagraphReadiness.classify(
                                paraText,
                                markedSlice,
                                paraMapping,
                                needsParagraphRemarkup,
                            )
                            if (readiness != ParagraphReadinessLabel.Empty) {
                                val (badgeText, badgeColor) = when (readiness) {
                                    ParagraphReadinessLabel.RemarkupNeeded ->
                                        "Сбой пакета" to Color(0xFFE65100)
                                    ParagraphReadinessLabel.NoVoiceTags ->
                                        "Без [voice]" to Color(0xFF1565C0)
                                    ParagraphReadinessLabel.MarkedValid ->
                                        "Готово" to Color(0xFF2E7D32)
                                    ParagraphReadinessLabel.MarkedInvalid ->
                                        "Ошибка" to Color(0xFFEF6C00)
                                    ParagraphReadinessLabel.MarkedUnvalidated ->
                                        "Нет проверки" to Color(0xFF616161)
                                    ParagraphReadinessLabel.Empty ->
                                        "" to Color.Transparent
                                }
                                if (badgeText.isNotEmpty()) {
                                    Surface(
                                        color = badgeColor.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            badgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (needsParagraphRemarkup) {
                                OutlinedButton(
                                    onClick = { onRemarkupParagraph(paraIndex) },
                                    enabled = !isLoading,
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text("Переразметить абзац", style = labelStyle)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = if (paraDrafts.isNotEmpty()) paraDrafts.getOrElse(paraIndex) { "" } else paraText,
                            onValueChange = { new ->
                                if (paraDrafts.isEmpty()) {
                                    val next = if (new.isBlank()) emptyList() else listOf(new)
                                    paraDrafts = next
                                    onOriginalTextChange(TextParser.joinParagraphsForStorage(next))
                                } else {
                                    val next = paraDrafts.toMutableList()
                                    if (paraIndex < next.size) {
                                        next[paraIndex] = new
                                        paraDrafts = next
                                        onOriginalTextChange(TextParser.joinParagraphsForStorage(next))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            minLines = 3,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            label = null,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (segIdxs.isEmpty() && segments.isNotEmpty()) {
                            Text(
                                "Нет сопоставленных сегментов (нажмите «Проверить» или поправьте текст).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        for (realIndex in segIdxs) {
                            if (realIndex !in segments.indices) continue
                            val segment = segments[realIndex]
                            val settings = if (segment.voiceName != null) {
                                voiceMapping[segment.voiceName] ?: VoiceSettings()
                            } else {
                                VoiceSettings()
                            }
                            val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
                            val isPlaying = playingIndex == realIndex

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                            var voiceDropdownExpanded by remember(realIndex) { mutableStateOf(false) }
                                            Text(
                                                text = segment.voiceName ?: "без голоса",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (segment.voiceName != null) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
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
                                            Text("\u2702", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
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
                                                Text("С соседним", style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
                                            Text("\u2728", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
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
                                                Text("\u25B6", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
                    }
                }
            }

            if (unmatched.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            "Сегменты без абзаца в оригинале",
                            style = labelStyle,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        HorizontalDivider()
                        val sorted = unmatched.sorted()
                        for (realIndex in sorted) {
                            if (realIndex !in segments.indices) continue
                            val segment = segments[realIndex]
                            // Re-use minimal card: ключ совпадает с блоком выше через отдельную ячейку
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        "#${realIndex + 1} — ${segment.voiceName ?: "без голоса"}",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    OutlinedTextField(
                                        value = segment.text,
                                        onValueChange = { onSegmentTextChange(realIndex, it) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        minLines = 2,
                                    )
                                }
                            }
                        }
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
