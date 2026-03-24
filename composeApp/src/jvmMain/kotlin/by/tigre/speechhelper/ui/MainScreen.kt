package by.tigre.speechhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import by.tigre.speechhelper.TokenStorage
import by.tigre.speechhelper.data.SessionStorage
import by.tigre.speechhelper.domain.API_VOICES
import by.tigre.speechhelper.domain.API_VOICES_INFO
import by.tigre.speechhelper.domain.FORMATS
import by.tigre.speechhelper.domain.TextSegment
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

    // Sync segments when switching to segment view
    LaunchedEffect(vm.viewMode, vm.currentChapterId) {
        if (vm.viewMode == 1) vm.syncSegmentsFromText()
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
                    OutlinedTextField(
                        value = vm.text,
                        onValueChange = { vm.text = it },
                        label = { Text("Текст для озвучивания") },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        minLines = 5,
                    )
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
