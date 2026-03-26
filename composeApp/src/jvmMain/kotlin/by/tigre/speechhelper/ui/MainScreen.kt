package by.tigre.speechhelper.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.domain.API_VOICES
import by.tigre.speechhelper.domain.API_VOICES_INFO
import by.tigre.speechhelper.domain.FORMATS
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.TextSegment
import by.tigre.speechhelper.domain.ValidationResult
import by.tigre.speechhelper.domain.VoiceSettings
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MainScreen(onTokenRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { MainViewModel(scope) }

    // Local TextFieldValue for cursor control (Home/End/PageUp/PageDown)
    var textFieldValue by remember { mutableStateOf(TextFieldValue(vm.text)) }
    LaunchedEffect(vm.text) {
        if (textFieldValue.text != vm.text) {
            textFieldValue = textFieldValue.copy(text = vm.text)
        }
    }

    // Auto-save text (debounced)
    LaunchedEffect(vm.currentChapterId) {
        snapshotFlow { vm.text }
            .debounce(30_000)
            .collect { vm.saveCurrentChapter() }
    }

    // Ensure voice mappings for all detected voices
    LaunchedEffect(vm.detectedVoices) {
        vm.ensureVoiceMappings(vm.detectedVoices)
    }

    // Load audio when chapter or audio path changes
    LaunchedEffect(vm.currentChapterId, vm.chapterAudioPath) {
        vm.resetPlayerState()
        val path = vm.chapterAudioPath
        if (path != null && File(path).exists()) {
            try {
                vm.chapterPlayer.open(path) {
                    vm.playerIsPlaying = false
                    vm.playerPositionMs = 0L
                }
                vm.playerDurationMs = vm.chapterPlayer.durationMs
                vm.playerReady = true
            } catch (_: Exception) {
                vm.playerReady = false
            }
        }
    }

    // Update playback position while playing
    LaunchedEffect(vm.playerIsPlaying) {
        if (vm.playerIsPlaying) {
            while (isActive && vm.chapterPlayer.isPlaying) {
                vm.playerPositionMs = vm.chapterPlayer.currentPositionMs
                delay(200)
            }
            vm.playerIsPlaying = vm.chapterPlayer.isPlaying
        }
    }

    // Sync segments when switching to segment view or split view
    LaunchedEffect(vm.viewMode, vm.currentChapterId, vm.hasMarkers) {
        if (vm.viewMode == 1 || (vm.viewMode == 0 && vm.hasMarkers)) vm.syncSegmentsFromText()
    }

    // Reset role if not available for currently selected voice (simple mode)
    val availableRoles = API_VOICES_INFO.find { it.id == vm.selectedVoice }?.roles ?: emptyList()
    LaunchedEffect(vm.selectedVoice) {
        if (vm.selectedRole.isNotBlank() && vm.selectedRole !in availableRoles) {
            vm.selectedRole = ""
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (vm.showCreateDialog) {
        CreateChapterDialog(
            onDismiss = { vm.showCreateDialog = false },
            onCreate = { vm.createChapter(it) },
        )
    }

    if (vm.showRenameDialog) {
        val currentName = vm.chapters.find { it.id == vm.currentChapterId }?.name ?: ""
        RenameChapterDialog(
            currentName = currentName,
            onDismiss = { vm.showRenameDialog = false },
            onRename = { vm.renameCurrentChapter(it) },
        )
    }

    if (vm.showDeleteDialog) {
        val currentName = vm.chapters.find { it.id == vm.currentChapterId }?.name ?: ""
        DeleteChapterDialog(
            chapterName = currentName,
            onDismiss = { vm.showDeleteDialog = false },
            onDelete = { vm.deleteCurrentChapter() },
        )
    }

    if (vm.showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { vm.showClearAllDialog = false },
            title = { Text("Очистить всё?") },
            text = { Text("Все главы, настройки голосов и кэш будут удалены. Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = { vm.clearAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showClearAllDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (vm.showSaveBookDialog) {
        SaveBookDialog(
            onDismiss = { vm.showSaveBookDialog = false },
            onSave = { vm.saveBook(it) },
        )
    }

    if (vm.showLoadBookDialog) {
        LoadBookDialog(
            onDismiss = { vm.showLoadBookDialog = false },
            onLoad = { vm.loadBook(it) },
            onDelete = { SessionStorage.deleteBook(it) },
        )
    }

    if (vm.showFolderIdDialog) {
        FolderIdDialog(
            onDismiss = { vm.showFolderIdDialog = false },
            onSave = { folderId ->
                TokenStorage.folderId = folderId
                vm.showFolderIdDialog = false
                vm.launchAutoMarkup()
            },
        )
    }

    if (vm.showResetMarkupDialog) {
        AlertDialog(
            onDismissRequest = { vm.showResetMarkupDialog = false },
            title = { Text("Сбросить разметку?") },
            text = { Text("Все голосовые теги будут удалены. Будет оставлен только исходный текст.") },
            confirmButton = {
                Button(
                    onClick = { vm.resetMarkup() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showResetMarkupDialog = false }) { Text("Отмена") }
            },
        )
    }

    // ── Main layout ───────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ChapterSelector(
                        chapters = vm.chapters,
                        currentChapterId = vm.currentChapterId,
                        onSelectChapter = { vm.switchToChapter(it) },
                        onCreateChapter = { vm.showCreateDialog = true },
                        onRenameChapter = { vm.showRenameDialog = true },
                        onDeleteChapter = { vm.showDeleteDialog = true },
                    )
                },
                actions = {
                    TextButton(onClick = { vm.saveCurrentBook() }) {
                        Text("Сохранить книгу")
                    }
                    TextButton(onClick = { vm.showLoadBookDialog = true }) {
                        Text("Загрузить книгу")
                    }
                    TextButton(onClick = { vm.importFb2() }, enabled = !vm.isLoading) {
                        Text("Импорт FB2")
                    }
                    TextButton(onClick = { vm.importEpub() }, enabled = !vm.isLoading) {
                        Text("Импорт EPUB")
                    }
                    TextButton(
                        onClick = { vm.showClearAllDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Очистить всё")
                    }
                    IconButton(onClick = onTokenRefresh) {
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
            // View mode toggle (only when text has markers)
            if (vm.hasMarkers) {
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
                        Text("Текст", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Tab(
                        selected = vm.viewMode == 1,
                        onClick = { vm.viewMode = 1 },
                    ) {
                        Text("Разбивка", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            // Main content area
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.viewMode == 0 || !vm.hasMarkers) {
                    if (vm.hasMarkers) {
                        // Split view: original text (read-only) | raw markup (editable)
                        MarkupSplitView(
                            originalText = vm.originalText.ifBlank {
                                TextParser.parse(vm.text).joinToString("\n\n") { it.text }
                            },
                            markupText = vm.text,
                            onMarkupTextChange = { newText ->
                                vm.text = newText
                            },
                            validationResult = vm.validationResult,
                            segments = vm.segments.toList(),
                            onRevalidate = { vm.revalidate() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    } else {
                        OutlinedTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                textFieldValue = newValue
                                vm.text = newValue.text
                            },
                            label = { Text("Текст для озвучивания") },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .onKeyEvent { event ->
                                    handleEditorKeys(event, textFieldValue) { newValue ->
                                        textFieldValue = newValue
                                        vm.text = newValue.text
                                    }
                                },
                            minLines = 5,
                        )
                    }
                } else {
                    SegmentsView(
                        segments = vm.segments,
                        voiceMapping = vm.voiceMapping,
                        onSegmentTextChange = { index, newText ->
                            vm.segments[index] = vm.segments[index].copy(text = newText)
                        },
                        onSplitSegment = { index, parts ->
                            val voiceName = vm.segments[index].voiceName
                            vm.segments.removeAt(index)
                            vm.segments.addAll(index, parts.map { TextSegment(voiceName = voiceName, text = it) })
                        },
                        onRemarkupSegment = { vm.remarkupSegment(it) },
                        onChangeSegmentVoice = { index, newVoiceName ->
                            vm.segments[index] = vm.segments[index].copy(voiceName = newVoiceName)
                        },
                        availableVoiceNames = vm.voiceMapping.keys.toList().sorted(),
                        isLoading = vm.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Voice mapping panel (visible when voices are configured)
                if (vm.voiceMapping.isNotEmpty()) {
                    val allVoiceNames = (vm.detectedVoices + vm.voiceMapping.keys).toList()
                        .sortedWith(compareByDescending<String> { it in vm.detectedVoices }.thenBy { it })
                    VoiceMappingPanel(
                        voiceNames = allVoiceNames,
                        activeVoiceNames = vm.detectedVoices,
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
            }

            // Simple synthesis controls (only when no markers)
            if (!vm.hasMarkers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DropdownSelector("Голос", API_VOICES, vm.selectedVoice) { vm.selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, vm.selectedFormat) { vm.selectedFormat = it }
                    if (availableRoles.isNotEmpty()) {
                        DropdownSelector(
                            "Амплуа",
                            listOf("") + availableRoles,
                            vm.selectedRole,
                            displayTransform = { it.ifBlank { "нет" } },
                        ) { vm.selectedRole = it }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Скорость: ${"%.1f".format(vm.speed)}")
                    Slider(
                        value = vm.speed.toFloat(),
                        onValueChange = { vm.speed = it.toDouble() },
                        valueRange = 0.1f..3.0f,
                        modifier = Modifier.width(200.dp),
                    )
                    Text("Тон: ${"%.0f".format(vm.pitchShift)}")
                    Slider(
                        value = vm.pitchShift.toFloat(),
                        onValueChange = { vm.pitchShift = it.toDouble() },
                        valueRange = -1000f..1000f,
                        modifier = Modifier.width(200.dp),
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
                        if (vm.viewMode == 1) vm.syncTextFromSegments()
                        if (vm.hasMarkers) {
                            vm.clearCache()
                            vm.launchMultiVoiceSynthesis()
                        } else {
                            vm.launchSimpleSynthesis()
                        }
                    },
                    enabled = vm.text.isNotBlank() && !vm.isLoading && TokenStorage.hasCredentials(),
                ) {
                    Text("Озвучить")
                }

                OutlinedButton(
                    onClick = { vm.launchAutoMarkup() },
                    enabled = vm.text.isNotBlank() && !vm.isLoading && TokenStorage.hasCredentials() && !vm.hasMarkers,
                ) {
                    Text("Авто-разметка")
                }

                if (vm.hasMarkers) {
                    OutlinedButton(
                        onClick = {
                            if (vm.viewMode == 1) vm.syncTextFromSegments()
                            vm.launchMultiVoiceSynthesis()
                        },
                        enabled = vm.text.isNotBlank() && !vm.isLoading && TokenStorage.hasCredentials(),
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
                        onClick = { vm.showResetMarkupDialog = true },
                        enabled = !vm.isLoading,
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text("Сбросить разметку")
                    }
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
            ProgressDialog(progressMessage = vm.progressMessage)
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
        Key.MoveHome -> {
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

// ── Split view: original text | raw markup ───────────────────────────────────

@Composable
private fun MarkupSplitView(
    originalText: String,
    markupText: String,
    onMarkupTextChange: (String) -> Unit,
    validationResult: ValidationResult?,
    segments: List<TextSegment>,
    onRevalidate: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Text("Исходный текст", modifier = Modifier.weight(1f), style = labelStyle)
            // Validation indicator
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
            Text(
                "Текст с разметкой",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = labelStyle,
            )
        }
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
                .collect { value ->
                    if (value == lastSyncedLeft.intValue) {
                        lastSyncedLeft.intValue = -1 // consume: this was a sync scroll
                    } else if (leftScrollState.maxValue > 0 && rightScrollState.maxValue > 0) {
                        val fraction = value.toFloat() / leftScrollState.maxValue
                        val target = (fraction * rightScrollState.maxValue).toInt()
                        lastSyncedRight.intValue = target
                        rightScrollState.scrollTo(target)
                    }
                }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { rightScrollState.value }
                .collect { value ->
                    if (value == lastSyncedRight.intValue) {
                        lastSyncedRight.intValue = -1 // consume: this was a sync scroll
                    } else if (rightScrollState.maxValue > 0 && leftScrollState.maxValue > 0) {
                        val fraction = value.toFloat() / rightScrollState.maxValue
                        val target = (fraction * leftScrollState.maxValue).toInt()
                        lastSyncedLeft.intValue = target
                        leftScrollState.scrollTo(target)
                    }
                }
        }

        // TextFieldValue for cursor stability in markup panel
        var markupFieldValue by remember { mutableStateOf(TextFieldValue(markupText)) }
        LaunchedEffect(markupText) {
            if (markupFieldValue.text != markupText) {
                markupFieldValue = markupFieldValue.copy(text = markupText)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Left: original text (read-only, selectable) with validation highlights
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize().verticalScroll(leftScrollState)) {
                    if (validationResult != null) {
                        Text(
                            text = buildValidatedOriginalAnnotatedString(validationResult, segments, bodyStyle),
                            style = bodyStyle.copy(color = onSurfaceColor),
                        )
                    } else {
                        Text(
                            text = originalText,
                            style = bodyStyle.copy(color = onSurfaceColor),
                        )
                    }
                }
            }

            VerticalDivider()

            // Right: raw markup text (editable)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize().verticalScroll(rightScrollState)) {
                    BasicTextField(
                        value = markupFieldValue,
                        onValueChange = { newValue ->
                            markupFieldValue = newValue
                            onMarkupTextChange(newValue.text)
                        },
                        textStyle = bodyStyle.copy(color = onSurfaceColor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ── Paragraph validation annotation ──────────────────────────────────────────

private fun buildValidatedOriginalAnnotatedString(
    result: ValidationResult,
    segments: List<TextSegment>,
    bodyStyle: androidx.compose.ui.text.TextStyle,
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        result.paragraphs.forEachIndexed { idx, mapping ->
            if (idx > 0) append("\n\n")
            if (mapping.isValid) {
                append(TextParser.stripMarkup(mapping.originalParagraph))
            } else if (mapping.matchedSegmentIndices.isEmpty()) {
                // Entire paragraph is missing in markup — highlight all in red
                withStyle(SpanStyle(background = Color(0xFFEF5350).copy(alpha = 0.3f))) {
                    append(TextParser.stripMarkup(mapping.originalParagraph))
                }
            } else {
                // Partial match — highlight missing words in red
                val strippedOrig = TextParser.stripMarkup(mapping.originalParagraph)
                val origWords = strippedOrig.split(Regex("\\s+")).filter { it.isNotBlank() }
                val segmentText = mapping.matchedSegmentIndices
                    .mapNotNull { segments.getOrNull(it) }
                    .joinToString(" ") { it.text }
                val markupWords = TextParser.extractCompareWords(segmentText)
                val matchedIndices = TextParser.lcsIndicesA(
                    origWords.map { it.lowercase() },
                    markupWords,
                )
                origWords.forEachIndexed { i, word ->
                    if (i > 0) append(" ")
                    if (i !in matchedIndices) {
                        withStyle(SpanStyle(background = Color(0xFFEF5350).copy(alpha = 0.3f))) {
                            append(word)
                        }
                    } else {
                        append(word)
                    }
                }
                if (mapping.extraInMarkup.isNotEmpty()) {
                    append(" ")
                    withStyle(SpanStyle(
                        background = Color(0xFFFFB300).copy(alpha = 0.3f),
                        fontSize = bodyStyle.fontSize * 0.85,
                    )) {
                        append("[+${mapping.extraInMarkup.joinToString(" ")}]")
                    }
                }
            }
        }
        // Show unmatched segments
        if (result.unmatchedSegmentIndices.isNotEmpty()) {
            val unmatchedText = result.unmatchedSegmentIndices
                .sorted()
                .mapNotNull { segments.getOrNull(it) }
                .joinToString(" ") { it.text }
            val preview = TextParser.stripMarkup(unmatchedText).take(100)
            append("\n\n")
            withStyle(SpanStyle(background = Color(0xFFFFB300).copy(alpha = 0.3f))) {
                append("[Лишнее в разметке: $preview...]")
            }
        }
    }
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
