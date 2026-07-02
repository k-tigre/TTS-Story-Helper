package by.tigre.speechhelper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.ui.vm.RootViewModel
import by.tigre.speechhelper.domain.AutoMarkupMode
import by.tigre.speechhelper.domain.LOCAL_TTS_SAMPLE_RATES
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.ParagraphMapping
import by.tigre.speechhelper.domain.ParagraphReadiness
import by.tigre.speechhelper.domain.ParagraphReadinessLabel
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    val root = remember(scope) { RootViewModel(scope) }
    val vm = root.main
    val dialogs = root.dialogs
    val player = root.player

    // Auto-save text (debounced)
    LaunchedEffect(vm.currentChapterId) {
        snapshotFlow { vm.text }
            .debounce(30_000)
            .collect { vm.saveCurrentChapter() }
    }

    // Ensure voice mappings for all detected voices
    LaunchedEffect(vm.detectedVoices, vm.isLoading) {
        if (vm.isLoading) return@LaunchedEffect
        vm.ensureVoiceMappings(vm.detectedVoices)
    }

    // Load audio when chapter or audio path changes
    LaunchedEffect(vm.currentChapterId, vm.chapterAudioPath) {
        player.bindChapterAudioFile(vm.chapterAudioPath)
    }

    // Update playback position while playing
    LaunchedEffect(player.playerIsPlaying) {
        if (player.playerIsPlaying) {
            player.tickPositionWhilePlaying()
        }
    }

    // Синхронизация сегментов при смене режима (смена главы уже делает revalidate в редакторе)
    LaunchedEffect(vm.viewMode, vm.isLoading) {
        if (vm.isLoading) return@LaunchedEffect
        vm.syncSegmentsFromText()
    }

    AppGlobalOverlays(root)

    // ── Main layout ───────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChapterSelector(
                            chapters = vm.chapters,
                            currentChapterId = vm.currentChapterId,
                            onSelectChapter = { vm.switchToChapter(it) },
                            onCreateChapter = { dialogs.showCreateDialog = true },
                            onRenameChapter = { dialogs.showRenameDialog = true },
                            onDeleteChapter = { dialogs.showDeleteDialog = true },
                        )
                        TextButton(onClick = { dialogs.showChaptersWorkflowDialog = true }) {
                            Text("Пакетная обработка…")
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.requestAudiobookExport() },
                        enabled = !vm.isLoading,
                    ) {
                        Text("Экспорт аудиокниги")
                    }
                    TextButton(onClick = { vm.saveCurrentBook() }) {
                        Text("Сохранить книгу")
                    }
                    TextButton(onClick = { dialogs.showLoadBookDialog = true }) {
                        Text("Загрузить книгу")
                    }
                    TextButton(onClick = { vm.importFb2() }, enabled = !vm.isLoading) {
                        Text("Импорт FB2")
                    }
                    TextButton(onClick = { vm.importEpub() }, enabled = !vm.isLoading) {
                        Text("Импорт EPUB")
                    }
                    TextButton(
                        onClick = { dialogs.showClearAllDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Очистить всё")
                    }
                    TextButton(onClick = { dialogs.showHelpDialog = true }) {
                        Text("Помощь")
                    }
                    IconButton(onClick = { dialogs.showTokenDialog = true }) {
                        Text("\u2699", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryTabRow(
                selectedTabIndex = vm.viewMode,
                modifier = Modifier.width(300.dp),
            ) {
                Tab(
                    selected = vm.viewMode == 0,
                    onClick = {
                        if (vm.viewMode == 1) vm.syncTextFromSegments()
                        vm.viewMode = 0
                    },
                ) {
                    Text("Разметка", modifier = Modifier.padding(vertical = 8.dp))
                }
                Tab(
                    selected = vm.viewMode == 1,
                    onClick = { vm.viewMode = 1 },
                ) {
                    Text("Разбивка", modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            // Main content area
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val detectedVoicesForUi = if (vm.isLoading) emptySet() else vm.detectedVoices
                if (vm.isLoading) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Подождите…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val splitOriginal = remember(vm.originalText, vm.text) {
                        vm.originalText.ifBlank {
                            TextParser.joinSegmentTextsAsPlainOriginal(TextParser.parse(vm.text))
                        }
                    }
                    val markedParagraphs = remember(vm.text) { TextParser.splitParagraphsForStorage(vm.text) }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (vm.viewMode) {
                            0 -> MarkupSplitView(
                                originalText = splitOriginal,
                                markupText = vm.text,
                                markedParagraphs = markedParagraphs,
                                onMarkupTextChange = { newText -> vm.applyMarkupTextChange(newText) },
                                onOriginalTextChange = { newOriginal ->
                                    vm.updateOriginalText(newOriginal)
                                },
                                validationResult = vm.validationResult,
                                segments = vm.segments,
                                remarkupParagraphIndices = vm.remarkupNeededParagraphIndices,
                                onRevalidate = { vm.revalidate() },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            else -> ParagraphAlignedSplitView(
                                originalText = splitOriginal,
                                onOriginalTextChange = { vm.updateOriginalText(it) },
                                segments = vm.segments,
                                validationResult = vm.validationResult,
                                voiceMapping = vm.voiceMapping,
                                synthesizeAudio = { t, s -> vm.synthesizeAudio(t, s, "wav") },
                                onSegmentTextChange = { index, newText ->
                                    vm.segments[index] = vm.segments[index].copy(text = newText)
                                },
                                onSplitSegment = { index, parts ->
                                    val voiceName = vm.segments[index].voiceName
                                    vm.segments.removeAt(index)
                                    vm.segments.addAll(index, parts.map { TextSegment(voiceName = voiceName, text = it) })
                                },
                                onMergeWithPrevious = { vm.mergeSegmentWithPrevious(it) },
                                onMergeWithNext = { vm.mergeSegmentWithNext(it) },
                                onRemarkupSegment = { vm.remarkupSegment(it) },
                                remarkupParagraphIndices = vm.remarkupNeededParagraphIndices,
                                onRemarkupParagraph = { vm.remarkupChapterParagraph(it) },
                                onChangeSegmentVoice = { index, newVoiceName ->
                                    vm.segments[index] = vm.segments[index].copy(voiceName = newVoiceName)
                                },
                                availableVoiceNames = vm.voiceMapping.keys.toList().sorted(),
                                isLoading = vm.isLoading,
                                onRevalidate = { vm.revalidate() },
                                markedParagraphs = markedParagraphs,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }

                // Voice mapping panel (always visible)
                val allVoiceNames = (detectedVoicesForUi + vm.voiceMapping.keys).toList()
                    .sortedWith(compareByDescending<String> { it in detectedVoicesForUi }.thenBy { it })
                val activeVoiceNames = if (vm.hasMarkers) detectedVoicesForUi else setOf("voice_main")
                VoiceMappingPanel(
                    synthesisBackend = vm.synthesisBackend,
                    voiceNames = allVoiceNames,
                    activeVoiceNames = activeVoiceNames,
                    mapping = vm.voiceMapping,
                    onSettingsChange = { name, settings ->
                        vm.voiceMapping[name] = settings
                        vm.saveVoiceMapping()
                    },
                    onRetryVoice = { vm.launchMultiVoiceSynthesis(retryVoice = it) },
                    onMergeVoice = { from, to -> vm.mergeVoice(from, to) },
                    onAddVoice = { name ->
                        vm.voiceMapping[name] = VoiceSettings()
                        vm.saveVoiceMapping()
                    },
                    onCleanupVoices = { vm.removeUnusedVoices() },
                    isLoading = vm.isLoading,
                    modifier = Modifier.width(350.dp).fillMaxHeight(),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Источник синтеза", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = vm.synthesisBackend == SynthesisBackend.Cloud,
                        onClick = { vm.onSynthesisBackendChange(SynthesisBackend.Cloud) },
                        label = { Text("Yandex SpeechKit") },
                    )
                    FilterChip(
                        selected = vm.synthesisBackend == SynthesisBackend.Local,
                        onClick = { vm.onSynthesisBackendChange(SynthesisBackend.Local) },
                        label = { Text("Локально (Silero)") },
                    )
                }
                if (vm.synthesisBackend == SynthesisBackend.Local) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = vm.localTtsSettings.baseUrl,
                            onValueChange = {
                                vm.onLocalTtsSettingsChange(vm.localTtsSettings.copy(baseUrl = it))
                            },
                            label = { Text("URL сервера") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.checkLocalTtsConnection() }) {
                            Text("Проверить")
                        }
                    }
                    OutlinedTextField(
                        value = vm.localTtsSettings.modelId,
                        onValueChange = {
                            vm.onLocalTtsSettingsChange(vm.localTtsSettings.copy(modelId = it))
                        },
                        label = { Text("ID модели Silero (например v5_ru)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Частота:", style = MaterialTheme.typography.bodySmall)
                        DropdownSelector(
                            label = "",
                            items = LOCAL_TTS_SAMPLE_RATES.map { it.toString() },
                            selected = vm.localTtsSettings.sampleRate.toString(),
                            onSelect = { rate ->
                                rate.toIntOrNull()?.let {
                                    vm.onLocalTtsSettingsChange(vm.localTtsSettings.copy(sampleRate = it))
                                }
                            },
                        )
                    }
                    Text(
                        "Формат: WAV. Скорость/тембр — через SSML prosody (ступенчато, не как в облаке). Сервер: local-tts-server/run.ps1 или run.sh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (vm.synthesisBackend == SynthesisBackend.Cloud && !TokenStorage.hasCredentials()) {
                            dialogs.showTokenDialog = true
                            return@Button
                        }
                        if (vm.viewMode == 1) vm.syncTextFromSegments()
                        if (vm.hasMarkers) {
                            vm.clearCache()
                            vm.launchMultiVoiceSynthesis()
                        } else {
                            vm.launchSimpleSynthesis()
                        }
                    },
                    enabled = vm.text.isNotBlank() && !vm.isLoading,
                ) {
                    Text("Озвучить")
                }

                OutlinedButton(
                    onClick = {
                        if (!TokenStorage.hasCredentials()) {
                            dialogs.showTokenDialog = true
                            return@OutlinedButton
                        }
                        if (vm.hasMarkers) {
                            dialogs.autoMarkupModeDialogChapterIds = null
                            dialogs.showAutoMarkupModeDialog = true
                        } else {
                            vm.launchAutoMarkup(AutoMarkupMode.FillMissing)
                        }
                    },
                    enabled = vm.text.isNotBlank() && !vm.isLoading,
                ) {
                    Text("Авто-разметка")
                }

                val currentChapterInfo = vm.chapters.find { it.id == vm.currentChapterId }
                if (currentChapterInfo != null) {
                    FilterChip(
                        selected = currentChapterInfo.markupDone,
                        onClick = { vm.toggleCurrentChapterMarkupDone() },
                        label = { Text("Разметка готова") },
                    )
                    FilterChip(
                        selected = currentChapterInfo.voiceDone,
                        onClick = { vm.toggleCurrentChapterVoiceDone() },
                        enabled = vm.audioFileExistsForChapter(vm.currentChapterId),
                        label = { Text("Озвучка готова") },
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (vm.synthesisBackend == SynthesisBackend.Cloud && !TokenStorage.hasCredentials()) {
                            dialogs.showTokenDialog = true
                            return@OutlinedButton
                        }
                        if (vm.viewMode == 1) vm.syncTextFromSegments()
                        vm.launchMultiVoiceSynthesis()
                    },
                    enabled = vm.text.isNotBlank() && !vm.isLoading && vm.hasMarkers,
                ) {
                    Text("Повторить ошибки")
                }

                TextButton(
                    onClick = { vm.clearCache() },
                    enabled = !vm.isLoading,
                ) {
                    Text("Очистить кэш")
                }

                OutlinedButton(
                    onClick = { dialogs.showResetMarkupDialog = true },
                    enabled = !vm.isLoading,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Text("Сбросить разметку")
                }
            }

            // Audio player bar
            ChapterPlayerBar(
                isReady = vm.playerReady,
                isPlaying = vm.playerIsPlaying,
                positionMs = vm.playerPositionMs,
                durationMs = vm.playerDurationMs,
                onPlayPause = { vm.togglePlayerPlayPause() },
                onSeek = { vm.seekPlayer(it) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Status message
            if (vm.statusMessage.isNotBlank()) {
                Text(
                    text = vm.statusMessage,
                    color = if (vm.statusMessage.startsWith("Ошибка"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (vm.isLoading) {
            ProgressDialog(
                progressMessage = vm.progressMessage,
                onCancel = if (vm.progressCancellable) {
                    { vm.cancelMarkupProgress() }
                } else {
                    null
                },
            )
        }
    }
}

// ── Editor key handling ───────────────────────────────────────────────────────

private fun moveByLines(text: String, cursor: Int, lines: Int): Int {
    if (lines == 0) return cursor
    return if (lines < 0) {
        var pos = cursor
        var remaining = -lines
        while (remaining > 0) {
            val prev = text.lastIndexOf('\n', pos - 1)
            if (prev < 0) return 0
            pos = prev
            remaining--
        }
        pos
    } else {
        var pos = cursor
        var remaining = lines
        while (remaining > 0) {
            val next = text.indexOf('\n', pos)
            if (next < 0) return text.length
            pos = next + 1
            remaining--
        }
        pos.coerceAtMost(text.length)
    }
}

private fun handleEditorKeys(
    event: androidx.compose.ui.input.key.KeyEvent,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val text = value.text
    val cursor = value.selection.start
    return when (event.key) {
        Key.MoveHome, Key.Home -> {
            val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
            onChange(value.copy(selection = TextRange(lineStart)))
            true
        }
        Key.MoveEnd -> {
            val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
            onChange(value.copy(selection = TextRange(lineEnd)))
            true
        }
        Key.PageUp -> {
            val newCursor = moveByLines(text, cursor, -20)
            onChange(value.copy(selection = TextRange(newCursor)))
            true
        }
        Key.PageDown -> {
            val newCursor = moveByLines(text, cursor, 20)
            onChange(value.copy(selection = TextRange(newCursor)))
            true
        }
        else -> false
    }
}

// Nested verticalScroll + BasicTextField: default bring-into-view on focus/click resets scroll on
// Desktop and, via proportional sync, the other pane (see JetBrains/compose-multiplatform#4014).
private val IgnoreBringIntoViewForScrollParent: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private val LocalMarkupScrollViewportHeightPx = compositionLocalOf { 0 }

/** Keeps the text cursor in view when selection moves; does not use bring-into-view (see #4014 workaround). */
@Composable
private fun VerticalScrollRevealCursorEffect(
    scrollState: ScrollState,
    selection: TextRange,
    textLayoutResult: TextLayoutResult?,
    fieldCoordinates: LayoutCoordinates?,
    viewportHeightPx: Int,
) {
    val layoutState = rememberUpdatedState(textLayoutResult)
    val fieldState = rememberUpdatedState(fieldCoordinates)
    val density = LocalDensity.current
    val marginPx = remember(density) { with(density) { 8.dp.toPx() } }
    LaunchedEffect(selection.start, selection.end, viewportHeightPx, marginPx) {
        val layout = layoutState.value ?: return@LaunchedEffect
        val field = fieldState.value ?: return@LaunchedEffect
        val scrollContent = field.parentLayoutCoordinates ?: return@LaunchedEffect
        if (!field.isAttached || !scrollContent.isAttached) return@LaunchedEffect
        if (viewportHeightPx <= 0) return@LaunchedEffect

        val textLen = layout.layoutInput.text.length
        val anchor = selection.start.coerceIn(0, textLen)
        val cursorRect = layout.getCursorRect(anchor)
        val fieldTopInContent = scrollContent.localPositionOf(field, Offset.Zero)
        val cursorTop = fieldTopInContent.y + cursorRect.top
        val cursorBottom = cursorTop + cursorRect.height

        val vh = viewportHeightPx.toFloat()
        val y = scrollState.value.toFloat()
        val target = when {
            cursorTop < y + marginPx -> (cursorTop - marginPx).coerceAtLeast(0f)
            cursorBottom > y + vh - marginPx -> (cursorBottom - vh + marginPx).coerceAtLeast(0f)
            else -> return@LaunchedEffect
        }
        val maxScroll = scrollState.maxValue.coerceAtLeast(0)
        scrollState.scrollTo(target.roundToInt().coerceIn(0, maxScroll))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkupSyncedScrollColumn(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides IgnoreBringIntoViewForScrollParent) {
        Box(
            modifier = modifier
                .onSizeChanged { viewportHeightPx = it.height }
                .verticalScroll(scrollState),
        ) {
            CompositionLocalProvider(LocalMarkupScrollViewportHeightPx provides viewportHeightPx) {
                content()
            }
        }
    }
}

// ── Split view: original text | raw markup ───────────────────────────────────

private val remarkupParagraphHighlightColor = Color(0xFFFF9800).copy(alpha = 0.2f)

private val paragraphNoVoiceBg = Color(0xFF2196F3).copy(alpha = 0.12f)
private val paragraphReadyBg = Color(0xFF4CAF50).copy(alpha = 0.10f)
private val paragraphInvalidParaBg = Color(0xFFFF9800).copy(alpha = 0.14f)
private val paragraphUnvalidatedBg = Color(0xFF9E9E9E).copy(alpha = 0.10f)

private fun applyParagraphReadinessHighlights(
    base: AnnotatedString,
    plainOriginal: String,
    markedParagraphs: List<String>,
    validationResult: ValidationResult?,
    remarkupIndices: Set<Int>,
): AnnotatedString {
    val origParas = TextParser.splitParagraphsForStorage(plainOriginal)
    val ranges = paragraphStorageNonBlankRangesForHighlight(plainOriginal)
    if (origParas.isEmpty() || ranges.isEmpty() || origParas.size != ranges.size) return base

    val b = AnnotatedString.Builder(base)
    for (i in origParas.indices) {
        val r = ranges[i]
        if (r.isEmpty() || i in remarkupIndices) continue
        val kind = ParagraphReadiness.classify(
            origParas[i],
            markedParagraphs.getOrElse(i) { "" },
            validationResult?.paragraphs?.getOrNull(i),
            remarkupNeeded = false,
        )
        val color = when (kind) {
            ParagraphReadinessLabel.NoVoiceTags -> paragraphNoVoiceBg
            ParagraphReadinessLabel.DialogUnsplit -> paragraphNoVoiceBg
            ParagraphReadinessLabel.MarkedValid -> paragraphReadyBg
            ParagraphReadinessLabel.MarkedInvalid -> paragraphInvalidParaBg
            ParagraphReadinessLabel.MarkedUnvalidated -> paragraphUnvalidatedBg
            else -> null
        }
        if (color != null) {
            b.addStyle(SpanStyle(background = color), r.first, r.last + 1)
        }
    }
    return b.toAnnotatedString()
}

private fun annotateOriginalForMarkupView(
    plainOriginal: String,
    validationResult: ValidationResult?,
    remarkupParagraphIndices: Set<Int>,
    markedParagraphs: List<String>,
    focused: Boolean,
): AnnotatedString {
    if (focused) return AnnotatedString(plainOriginal)
    val validated = buildValidatedOriginalAnnotatedExact(plainOriginal, validationResult)
    val withReadiness = applyParagraphReadinessHighlights(
        validated,
        plainOriginal,
        markedParagraphs,
        validationResult,
        remarkupParagraphIndices,
    )
    return applyRemarkupParagraphHighlights(
        withReadiness,
        plainOriginal,
        remarkupParagraphIndices,
    )
}

/** Диапазоны символов непустых абзацев в тексте хранения (`\\n\\n`), в том же порядке, что [TextParser.splitParagraphsForStorage]. */
private fun paragraphStorageNonBlankRangesForHighlight(full: String): List<IntRange> {
    if (full.isBlank()) return emptyList()
    val delim = Regex("\n\\s*\n")
    val ranges = mutableListOf<IntRange>()
    var searchStart = 0
    var match = delim.find(full, searchStart)
    while (match != null) {
        val chunk = full.substring(searchStart, match.range.first)
        if (chunk.trim().isNotBlank()) {
            ranges.add(searchStart until match.range.first)
        }
        searchStart = match.range.last + 1
        match = delim.find(full, searchStart)
    }
    val tail = full.substring(searchStart)
    if (tail.trim().isNotBlank()) {
        ranges.add(searchStart until full.length)
    }
    return ranges
}

private fun applyRemarkupParagraphHighlights(
    base: AnnotatedString,
    plain: String,
    indices: Set<Int>,
    color: Color = remarkupParagraphHighlightColor,
): AnnotatedString {
    if (indices.isEmpty()) return base
    val ranges = paragraphStorageNonBlankRangesForHighlight(plain)
    if (ranges.isEmpty()) return base
    val b = AnnotatedString.Builder(base)
    for (idx in indices) {
        val r = ranges.getOrNull(idx) ?: continue
        if (r.isEmpty()) continue
        b.addStyle(SpanStyle(background = color), r.first, r.last + 1)
    }
    return b.toAnnotatedString()
}

@Composable
private fun MarkupSplitView(
    originalText: String,
    markupText: String,
    markedParagraphs: List<String>,
    onMarkupTextChange: (String) -> Unit,
    onOriginalTextChange: (String) -> Unit,
    validationResult: ValidationResult?,
    segments: List<TextSegment>,
    remarkupParagraphIndices: Set<Int> = emptySet(),
    onRevalidate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditingOriginal by remember { mutableStateOf(false) }
    var markupHasFocus by remember { mutableStateOf(false) }
    var originalFieldHasFocus by remember { mutableStateOf(false) }
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium
    val bodyStyle = MaterialTheme.typography.bodyMedium

    Column(
        modifier = modifier
            .border(1.dp, outlineColor, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
    ) {
        // Column headers with validation indicator
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Исходный текст", style = labelStyle)
            OutlinedButton(
                onClick = { isEditingOriginal = !isEditingOriginal },
                modifier = Modifier.height(28.dp).padding(start = 8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    if (isEditingOriginal) "Готово" else "Редактировать",
                    style = labelStyle,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Validation indicator (учитывает сбой пакета и незавершённую разметку)
            val validationSummary = ParagraphReadiness.summarizeChapterValidation(
                validationResult = validationResult,
                remarkupIndices = remarkupParagraphIndices,
                originalJoined = originalText,
                markedParagraphs = markedParagraphs,
            )
            if (validationSummary != null) {
                val indicatorColor = if (validationSummary.isAllReady) {
                    Color(0xFF4CAF50)
                } else {
                    Color(0xFFFF9800)
                }
                Text(
                    text = "${validationSummary.readyCount}/${validationSummary.totalCount}",
                    style = labelStyle.copy(color = indicatorColor),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else if (validationResult != null) {
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
            Text(
                "Текст с разметкой",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = labelStyle,
            )
        }
        HorizontalDivider()
        if (remarkupParagraphIndices.isNotEmpty()) {
            val nums = remarkupParagraphIndices.sorted().joinToString(", ") { "${it + 1}" }
            Text(
                text = "Ответ модели для пакета не совпал с абзацем(ами): $nums — фон подсвечен. Переразметка: режим «по абзацам», кнопка у абзаца.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            HorizontalDivider()
        }
        Text(
            "Абзацы в «Исходный текст»: зелёный фон — разметка валидна; синий — нет тегов [voice] " +
                "(режим «Недостающие» берёт такие абзацы по порядку в тексте, с меньшего номера); оранжевый фон — ошибка «Проверить» или сбой авторазметки пакета.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        HorizontalDivider()

        // Content: two scrollable columns side by side
        val leftScrollState = rememberScrollState()
        val rightScrollState = rememberScrollState()
        // Synchronized scrolling: proportional mapping
        // Track last programmatically-set scroll value to distinguish user vs sync scrolls
        val lastSyncedLeft = remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        val lastSyncedRight = remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        LaunchedEffect(Unit) {
            snapshotFlow { leftScrollState.value }
                .distinctUntilChanged()
                .collect { value ->
                    if (value == lastSyncedLeft.intValue) {
                        lastSyncedLeft.intValue = -1 // consume: this was a sync scroll
                    } else {
                        val lMax = leftScrollState.maxValue
                        val rMax = rightScrollState.maxValue
                        if (lMax <= 0 || rMax <= 0) return@collect
                        val fraction = value.toFloat() / lMax
                        val target = (fraction * rMax).roundToInt().coerceIn(0, rMax)
                        if (kotlin.math.abs(target - rightScrollState.value) > 1) {
                            lastSyncedRight.intValue = target
                            rightScrollState.scrollTo(target)
                        }
                    }
                }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { rightScrollState.value }
                .distinctUntilChanged()
                .collect { value ->
                    if (value == lastSyncedRight.intValue) {
                        lastSyncedRight.intValue = -1 // consume: this was a sync scroll
                    } else {
                        val lMax = leftScrollState.maxValue
                        val rMax = rightScrollState.maxValue
                        if (lMax <= 0 || rMax <= 0) return@collect
                        val fraction = value.toFloat() / rMax
                        val target = (fraction * lMax).roundToInt().coerceIn(0, lMax)
                        if (kotlin.math.abs(target - leftScrollState.value) > 1) {
                            lastSyncedLeft.intValue = target
                            leftScrollState.scrollTo(target)
                        }
                    }
                }
        }

        // Markup field: annotated string must equal raw markup exactly (same as original pane).
        var markupFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    applyRemarkupParagraphHighlights(
                        buildValidatedMarkupAnnotatedExact(markupText, validationResult, onSurfaceColor),
                        markupText,
                        remarkupParagraphIndices,
                    ),
                ),
            )
        }
        LaunchedEffect(markupText, validationResult, onSurfaceColor, markupHasFocus, remarkupParagraphIndices) {
            val ann = if (markupHasFocus) {
                AnnotatedString(markupText)
            } else {
                applyRemarkupParagraphHighlights(
                    buildValidatedMarkupAnnotatedExact(markupText, validationResult, onSurfaceColor),
                    markupText,
                    remarkupParagraphIndices,
                )
            }
            if (markupFieldValue.text != markupText) {
                markupFieldValue = TextFieldValue(ann, TextRange(markupText.length))
            } else {
                val sel = markupFieldValue.selection
                val len = markupText.length
                markupFieldValue = TextFieldValue(
                    annotatedString = ann,
                    selection = TextRange(sel.min.coerceIn(0, len), sel.max.coerceIn(0, len)),
                    composition = markupFieldValue.composition,
                )
            }
        }

        // Single field: annotated text must match plain text byte-for-byte (no overlay mismatch).
        var originalFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    annotateOriginalForMarkupView(
                        originalText,
                        validationResult,
                        remarkupParagraphIndices,
                        markedParagraphs,
                        focused = false,
                    ),
                ),
            )
        }
        LaunchedEffect(originalText, validationResult, originalFieldHasFocus, remarkupParagraphIndices, markedParagraphs) {
            val ann = annotateOriginalForMarkupView(
                originalText,
                validationResult,
                remarkupParagraphIndices,
                markedParagraphs,
                originalFieldHasFocus,
            )
            if (originalFieldValue.text != originalText) {
                originalFieldValue = TextFieldValue(ann, TextRange(originalText.length))
            } else {
                val sel = originalFieldValue.selection
                val len = originalText.length
                originalFieldValue = TextFieldValue(
                    annotatedString = ann,
                    selection = TextRange(sel.min.coerceIn(0, len), sel.max.coerceIn(0, len)),
                    composition = originalFieldValue.composition,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Left: original text with optional editing
            if (isEditingOriginal) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp),
                ) {
                    MarkupSyncedScrollColumn(
                        scrollState = leftScrollState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        var originalLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                        var originalFieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val originalViewportPx = LocalMarkupScrollViewportHeightPx.current
                        VerticalScrollRevealCursorEffect(
                            scrollState = leftScrollState,
                            selection = originalFieldValue.selection,
                            textLayoutResult = originalLayoutResult,
                            fieldCoordinates = originalFieldCoords,
                            viewportHeightPx = originalViewportPx,
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            BasicTextField(
                                value = originalFieldValue,
                                onValueChange = { newValue ->
                                    val textChanged = newValue.text != originalFieldValue.text
                                    val plain = newValue.text
                                    val ann = annotateOriginalForMarkupView(
                                        plain,
                                        validationResult,
                                        remarkupParagraphIndices,
                                        markedParagraphs,
                                        originalFieldHasFocus,
                                    )
                                    originalFieldValue = TextFieldValue(
                                        annotatedString = ann,
                                        selection = newValue.selection,
                                        composition = newValue.composition,
                                    )
                                    if (textChanged) onOriginalTextChange(plain)
                                },
                                onTextLayout = { originalLayoutResult = it },
                                textStyle = bodyStyle.copy(color = onSurfaceColor),
                                cursorBrush = SolidColor(onSurfaceColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { originalFieldHasFocus = it.isFocused }
                                    .onGloballyPositioned { originalFieldCoords = it }
                                    .onPreviewKeyEvent { event ->
                                        handleEditorKeys(event, originalFieldValue) { newValue ->
                                            val changed = newValue.text != originalFieldValue.text
                                            val ann = annotateOriginalForMarkupView(
                                                newValue.text,
                                                validationResult,
                                                remarkupParagraphIndices,
                                                markedParagraphs,
                                                originalFieldHasFocus,
                                            )
                                            originalFieldValue = TextFieldValue(
                                                annotatedString = ann,
                                                selection = newValue.selection,
                                                composition = newValue.composition,
                                            )
                                            if (changed) onOriginalTextChange(newValue.text)
                                        }
                                    },
                            )
                            UnmatchedOriginalFooter(
                                validationResult = validationResult,
                                segments = segments,
                                labelStyle = labelStyle,
                            )
                        }
                    }
                }
            } else {
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp),
                ) {
                    MarkupSyncedScrollColumn(
                        scrollState = leftScrollState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = annotateOriginalForMarkupView(
                                    originalText,
                                    validationResult,
                                    remarkupParagraphIndices,
                                    markedParagraphs,
                                    focused = false,
                                ),
                                style = bodyStyle.copy(color = onSurfaceColor),
                            )
                            UnmatchedOriginalFooter(
                                validationResult = validationResult,
                                segments = segments,
                                labelStyle = labelStyle,
                            )
                        }
                    }
                }
            }

            VerticalDivider()

            // Right: markup with validation highlights in-field (no overlay).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
            ) {
                MarkupSyncedScrollColumn(
                    scrollState = rightScrollState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    var markupLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                    var markupFieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    val markupViewportPx = LocalMarkupScrollViewportHeightPx.current
                    VerticalScrollRevealCursorEffect(
                        scrollState = rightScrollState,
                        selection = markupFieldValue.selection,
                        textLayoutResult = markupLayoutResult,
                        fieldCoordinates = markupFieldCoords,
                        viewportHeightPx = markupViewportPx,
                    )
                    BasicTextField(
                        value = markupFieldValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != markupFieldValue.text
                            val plain = newValue.text
                            val ann = if (markupHasFocus) {
                                AnnotatedString(plain)
                            } else {
                                buildValidatedMarkupAnnotatedExact(plain, validationResult, onSurfaceColor)
                            }
                            markupFieldValue = TextFieldValue(
                                annotatedString = ann,
                                selection = newValue.selection,
                                composition = newValue.composition,
                            )
                            if (textChanged) onMarkupTextChange(plain)
                        },
                        onTextLayout = { markupLayoutResult = it },
                        textStyle = bodyStyle.copy(color = onSurfaceColor),
                        cursorBrush = SolidColor(onSurfaceColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { markupHasFocus = it.isFocused }
                            .onGloballyPositioned { markupFieldCoords = it }
                            .onPreviewKeyEvent { event ->
                                handleEditorKeys(event, markupFieldValue) { newValue ->
                                    val changed = newValue.text != markupFieldValue.text
                                    val ann = if (markupHasFocus) {
                                        AnnotatedString(newValue.text)
                                    } else {
                                        applyRemarkupParagraphHighlights(
                                            buildValidatedMarkupAnnotatedExact(
                                                newValue.text,
                                                validationResult,
                                                onSurfaceColor,
                                            ),
                                            newValue.text,
                                            remarkupParagraphIndices,
                                        )
                                    }
                                    markupFieldValue = TextFieldValue(
                                        annotatedString = ann,
                                        selection = newValue.selection,
                                        composition = newValue.composition,
                                    )
                                    if (changed) onMarkupTextChange(newValue.text)
                                }
                            },
                    )
                }
            }
        }
    }
}

// ── Paragraph validation annotation ──────────────────────────────────────────

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInnerWithMarkupHighlights(
    inner: String,
    extraSigIndices: Set<Int>,
    highlight: Color,
) {
    val pauseRe = Regex("""<\[[^\]]*\]>""")
    var sigIdx = 0
    var i = 0
    while (i < inner.length) {
        if (inner[i].isWhitespace()) {
            append(inner[i])
            i++
            continue
        }
        val pauseMatch = pauseRe.find(inner, i)
        if (pauseMatch != null && pauseMatch.range.first == i) {
            append(pauseMatch.value)
            i = pauseMatch.range.last + 1
            continue
        }
        val start = i
        while (i < inner.length && !inner[i].isWhitespace()) {
            val pm = pauseRe.find(inner, i)
            if (pm != null && pm.range.first == i) break
            i++
        }
        val word = inner.substring(start, i)
        if (word.isEmpty()) continue
        val n = TextParser.normalizeCompareToken(word)
        val isExtra = n.isNotEmpty() && sigIdx in extraSigIndices
        if (n.isNotEmpty()) sigIdx++
        if (isExtra) {
            withStyle(SpanStyle(background = highlight)) { append(word) }
        } else {
            append(word)
        }
    }
}

/** Plain [AnnotatedString.text] equals [fullMarkup] exactly; walks raw string like [TextParser.parse]. */
private fun buildValidatedMarkupAnnotatedExact(
    fullMarkup: String,
    result: ValidationResult?,
    onSurfaceColor: Color,
): AnnotatedString {
    if (result == null) {
        return AnnotatedString(fullMarkup)
    }
    val segments = TextParser.parse(fullMarkup)
    val extraBySeg = mutableMapOf<Int, MutableSet<Int>>()
    for (p in result.paragraphs) {
        for ((seg, set) in p.extraMarkupSignificantWordIndicesBySegment) {
            extraBySeg.getOrPut(seg) { mutableSetOf() }.addAll(set)
        }
    }
    val unmatched = result.unmatchedSegmentIndices
    val extraHighlight = Color(0xFFFFB300).copy(alpha = 0.35f)
    val tagColor = onSurfaceColor.copy(alpha = 0.55f)
    return buildAnnotatedString {
        var segIdx = 0
        var last = 0
        for (match in TextParser.TAG_REGEX.findAll(fullMarkup)) {
            val beforeRaw = fullMarkup.substring(last, match.range.first)
            if (beforeRaw.trim().isNotBlank()) {
                appendMarkupSegmentContent(
                    this,
                    beforeRaw,
                    segIdx,
                    segments,
                    unmatched,
                    extraBySeg,
                    extraHighlight,
                )
                segIdx++
            } else {
                append(beforeRaw)
            }

            val innerRange = match.groups[2]!!.range
            val openPart = fullMarkup.substring(match.range.first, innerRange.first)
            val innerRaw = fullMarkup.substring(innerRange)
            val closePart = fullMarkup.substring(innerRange.last + 1, match.range.last + 1)

            if (innerRaw.trim().isNotBlank()) {
                withStyle(SpanStyle(color = tagColor)) { append(openPart) }
                appendMarkupSegmentContent(
                    this,
                    innerRaw,
                    segIdx,
                    segments,
                    unmatched,
                    extraBySeg,
                    extraHighlight,
                )
                withStyle(SpanStyle(color = tagColor)) { append(closePart) }
                segIdx++
            } else {
                append(openPart)
                append(innerRaw)
                append(closePart)
            }

            last = match.range.last + 1
        }
        val tailRaw = fullMarkup.substring(last)
        if (tailRaw.trim().isNotBlank()) {
            appendMarkupSegmentContent(
                this,
                tailRaw,
                segIdx,
                segments,
                unmatched,
                extraBySeg,
                extraHighlight,
            )
        } else {
            append(tailRaw)
        }
    }
}

private fun appendMarkupSegmentContent(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    raw: String,
    segIdx: Int,
    segments: List<TextSegment>,
    unmatched: Set<Int>,
    extraBySeg: Map<Int, Set<Int>>,
    extraHighlight: Color,
) {
    when {
        segIdx in unmatched -> {
            val nSig = TextParser.extractCompareWords(segments[segIdx].text).size
            val allExtra = if (nSig > 0) (0 until nSig).toSet() else emptySet()
            builder.appendInnerWithMarkupHighlights(raw, allExtra, extraHighlight)
        }
        else -> {
            builder.appendInnerWithMarkupHighlights(
                raw,
                extraBySeg[segIdx].orEmpty(),
                extraHighlight,
            )
        }
    }
}

private val originalParagraphDelimiter = Regex("\n\\s*\n")

/** Annotated string whose plain [AnnotatedString.text] equals [fullOriginal] exactly (cursor-safe editing). */
private fun buildValidatedOriginalAnnotatedExact(
    fullOriginal: String,
    result: ValidationResult?,
): AnnotatedString {
    if (result == null || result.paragraphs.isEmpty()) {
        return AnnotatedString(fullOriginal)
    }
    val mappings = result.paragraphs
    val redBg = Color(0xFFEF5350).copy(alpha = 0.3f)
    return buildAnnotatedString {
        var mi = 0
        var searchStart = 0
        var match = originalParagraphDelimiter.find(fullOriginal, searchStart)
        while (match != null) {
            val paraRaw = fullOriginal.substring(searchStart, match.range.first)
            appendOriginalParagraphChunk(this, paraRaw, mappings.getOrNull(mi), redBg)
            if (paraRaw.trim().isNotBlank()) mi++
            append(match.value)
            searchStart = match.range.last + 1
            match = originalParagraphDelimiter.find(fullOriginal, searchStart)
        }
        val tail = fullOriginal.substring(searchStart)
        appendOriginalParagraphChunk(this, tail, mappings.getOrNull(mi), redBg)
    }
}

private fun appendOriginalParagraphChunk(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    paraRaw: String,
    mapping: ParagraphMapping?,
    redBg: Color,
) {
    if (mapping == null) {
        builder.append(paraRaw)
        return
    }
    if (paraRaw.trim().isBlank()) {
        builder.append(paraRaw)
        return
    }
    val leadEnd = paraRaw.indexOfFirst { !it.isWhitespace() }
    val trailStart = paraRaw.indexOfLast { !it.isWhitespace() } + 1
    if (leadEnd < 0) {
        builder.append(paraRaw)
        return
    }
    val lead = paraRaw.substring(0, leadEnd)
    val trimmed = paraRaw.substring(leadEnd, trailStart)
    val trail = paraRaw.substring(trailStart)
    when {
        mapping.isValid -> builder.append(paraRaw)
        mapping.matchedSegmentIndices.isEmpty() -> {
            builder.apply {
                append(lead)
                withStyle(SpanStyle(background = redBg)) { append(trimmed) }
                append(trail)
            }
        }
        else -> {
            val ranges = TextParser.mapCompareTokenRangesInRawParagraph(trimmed)
            val tokens = TextParser.splitCompareWhitespace(TextParser.stripMarkup(trimmed))
            val missing = mapping.missingOriginalWordIndices
            if (ranges == null || ranges.size != tokens.size) {
                builder.append(paraRaw)
            } else {
                builder.apply {
                    append(lead)
                    var pos = 0
                    ranges.forEachIndexed { di, r ->
                        if (pos < r.first) append(trimmed.substring(pos, r.first))
                        val chunk = trimmed.substring(r.first, r.last + 1)
                        if (di in missing) {
                            withStyle(SpanStyle(background = redBg)) { append(chunk) }
                        } else {
                            append(chunk)
                        }
                        pos = r.last + 1
                    }
                    if (pos < trimmed.length) append(trimmed.substring(pos))
                    append(trail)
                }
            }
        }
    }
}

@Composable
private fun UnmatchedOriginalFooter(
    validationResult: ValidationResult?,
    segments: List<TextSegment>,
    labelStyle: androidx.compose.ui.text.TextStyle,
) {
    val unmatched = validationResult?.unmatchedSegmentIndices.orEmpty()
    if (unmatched.isEmpty()) return
    val unmatchedText = unmatched
        .sorted()
        .mapNotNull { segments.getOrNull(it) }
        .joinToString(" ") { it.text }
    val preview = TextParser.stripMarkup(unmatchedText).take(100)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(background = Color(0xFFFFB300).copy(alpha = 0.3f))) {
                append("[Лишнее в разметке: $preview...]")
            }
        },
        style = labelStyle.copy(color = onSurface),
        modifier = Modifier.padding(top = 8.dp),
    )
}

// ── Diff annotation utilities ─────────────────────────────────────────────────

private fun buildDiffAnnotatedString(original: String, modified: String): androidx.compose.ui.text.AnnotatedString {
    if (original.isBlank()) return androidx.compose.ui.text.AnnotatedString(original)
    val origWords = original.trim().split(Regex("\\s+"))
    val modWords = modified.trim().split(Regex("\\s+"))
    val matched = lcsOrigIndices(origWords, modWords)
    return buildAnnotatedString {
        origWords.forEachIndexed { i, word ->
            if (i !in matched) {
                withStyle(SpanStyle(background = Color(0xFFFFB300).copy(alpha = 0.35f))) {
                    append(word)
                }
            } else {
                append(word)
            }
            if (i < origWords.size - 1) append(" ")
        }
    }
}

private fun lcsOrigIndices(a: List<String>, b: List<String>): Set<Int> {
    val m = a.size; val n = b.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) dp[i - 1][j - 1] + 1
                       else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }
    val matched = mutableSetOf<Int>()
    var i = m; var j = n
    while (i > 0 && j > 0) {
        when {
            a[i - 1].equals(b[j - 1], ignoreCase = true) -> { matched.add(i - 1); i--; j-- }
            dp[i - 1][j] >= dp[i][j - 1] -> i--
            else -> j--
        }
    }
    return matched
}
