package by.tigre.speechhelper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class VoiceInfo(val id: String, val gender: String, val roles: List<String>)

data class VoiceSettings(
    val voice: String = "dasha",
    val role: String = "",
    val speed: Double = 1.0,
    val pitchShift: Double = 0.0,
)

private val API_VOICES_INFO = listOf(
    // Russian (ru-RU)
    VoiceInfo("alena", "Ж", listOf("neutral", "good")),
    VoiceInfo("filipp", "М", emptyList()),
    VoiceInfo("ermil", "М", listOf("neutral", "good")),
    VoiceInfo("jane", "Ж", listOf("neutral", "good", "evil")),
    VoiceInfo("omazh", "Ж", listOf("neutral", "evil")),
    VoiceInfo("zahar", "М", listOf("neutral", "good")),
    VoiceInfo("dasha", "Ж", listOf("neutral", "good", "friendly")),
    VoiceInfo("julia", "Ж", listOf("neutral", "strict")),
    VoiceInfo("lera", "Ж", listOf("neutral", "friendly")),
    VoiceInfo("masha", "Ж", listOf("good", "strict", "friendly")),
    VoiceInfo("marina", "Ж", listOf("neutral", "whisper", "friendly")),
    VoiceInfo("alexander", "М", listOf("neutral", "good")),
    VoiceInfo("kirill", "М", listOf("neutral", "strict", "good")),
    VoiceInfo("anton", "М", listOf("neutral", "good")),
    VoiceInfo("madi_ru", "М", emptyList()),
    VoiceInfo("saule_ru", "Ж", listOf("neutral", "strict", "whisper")),
    VoiceInfo("zamira_ru", "Ж", listOf("neutral", "strict", "friendly")),
    VoiceInfo("zhanar_ru", "Ж", listOf("neutral", "strict", "friendly")),
    VoiceInfo("yulduz_ru", "Ж", listOf("neutral", "strict", "friendly", "whisper")),
)

private val API_VOICES = API_VOICES_INFO.map { it.id }

private val FORMATS = listOf("mp3", "ogg", "wav")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var showTokenDialog by remember { mutableStateOf(!TokenStorage.hasCredentials()) }

    MaterialTheme {
        if (showTokenDialog) {
            TokenDialog(
                onDismiss = {
                    if (TokenStorage.hasCredentials()) showTokenDialog = false
                },
                onSave = { token ->
                    TokenStorage.iamToken = token
                    showTokenDialog = false
                }
            )
        }

        MainScreen(onTokenRefresh = { showTokenDialog = true })
    }
}

@Composable
private fun TokenDialog(
    onDismiss: () -> Unit,
    onSave: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf(TokenStorage.iamToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(token) },
                enabled = token.isNotBlank(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            if (TokenStorage.hasCredentials()) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onTokenRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(SessionStorage.text) }
    var selectedVoice by remember { mutableStateOf(API_VOICES[0]) }
    var selectedFormat by remember { mutableStateOf(FORMATS[0]) }
    var speed by remember { mutableStateOf(1.0) }
    var pitchShift by remember { mutableStateOf(0.0) }
    var selectedRole by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    val voiceMapping = remember { mutableStateMapOf<String, VoiceSettings>() }

    // Load saved mapping on first composition
    LaunchedEffect(Unit) {
        val saved = SessionStorage.voiceMapping
        voiceMapping.putAll(saved)
    }

    // Save text on change
    LaunchedEffect(text) {
        SessionStorage.text = text
    }

    val hasMarkers = TextParser.hasVoiceMarkers(text)
    val detectedVoices: Set<String> = remember(text) {
        if (hasMarkers) TextParser.extractVoiceNames(text) else emptySet()
    }

    // Ensure all detected voices have a mapping
    LaunchedEffect(detectedVoices) {
        for (name in detectedVoices) {
            if (name !in voiceMapping) {
                voiceMapping[name] = VoiceSettings()
            }
        }
        // Remove stale mappings
        val stale = voiceMapping.keys - detectedVoices
        for (key in stale) {
            voiceMapping.remove(key)
        }
    }

    fun saveVoiceMapping() {
        SessionStorage.voiceMapping = voiceMapping.toMap()
    }

    fun launchMultiVoiceSynthesis(retryVoice: String? = null) {
        val segments = TextParser.parse(text)
        if (segments.isEmpty()) {
            statusMessage = "Ошибка: не найдены сегменты текста"
            return
        }
        saveVoiceMapping()
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = ""
            try {
                val cacheDir = withContext(Dispatchers.IO) {
                    File(System.getProperty("user.home"), "SpeechHelper/cache").apply { mkdirs() }
                }
                val errors = mutableListOf<String>()

                for ((index, segment) in segments.withIndex()) {
                    val settings = if (segment.voiceName != null) {
                        voiceMapping[segment.voiceName] ?: VoiceSettings()
                    } else {
                        VoiceSettings()
                    }
                    val partFile = File(cacheDir, "part_%03d.mp3".format(index))

                    // Skip already cached unless retrying this voice
                    if (partFile.exists() && (retryVoice == null || segment.voiceName != retryVoice)) {
                        progressMessage = "Часть ${index + 1} из ${segments.size} — кэш"
                        continue
                    }

                    progressMessage = "Обработка ${index + 1} из ${segments.size} (${segment.voiceName ?: "по умолчанию"} -> ${settings.voice})..."
                    try {
                        val bytes = SpeechKitApi.synthesize(
                            text = segment.text,
                            voice = settings.voice,
                            role = settings.role.ifBlank { null },
                            speed = settings.speed,
                            pitchShift = settings.pitchShift,
                            format = "mp3",
                            token = TokenStorage.iamToken,
                        )
                        withContext(Dispatchers.IO) { partFile.writeBytes(bytes) }
                    } catch (e: Exception) {
                        errors.add("#${index + 1} ${segment.voiceName ?: "по умолчанию"}: ${e.message}")
                        // Delete failed part so it will be retried next time
                        partFile.delete()
                    }
                }

                // Assemble all available parts in order
                progressMessage = "Склейка аудио..."
                val allParts = (0 until segments.size).mapNotNull { i ->
                    val f = File(cacheDir, "part_%03d.mp3".format(i))
                    if (f.exists()) f.readBytes() else null
                }

                if (allParts.isEmpty()) {
                    statusMessage = "Ошибка: ни один сегмент не озвучен"
                } else {
                    val combined = allParts.reduce { acc, bytes -> acc + bytes }
                    val filePath = saveAudioFile(combined, "mp3")
                    val successCount = allParts.size
                    val totalCount = segments.size
                    statusMessage = if (errors.isEmpty()) {
                        "Сохранено ($totalCount сегментов): $filePath"
                    } else {
                        "Сохранено ($successCount/$totalCount): $filePath\nОшибки:\n${errors.joinToString("\n")}"
                    }
                }
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    fun clearCache() {
        val cacheDir = File(System.getProperty("user.home"), "SpeechHelper/cache")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
        statusMessage = "Кэш очищен"
    }

    fun launchSimpleSynthesis() {
        scope.launch {
            isLoading = true
            statusMessage = ""
            progressMessage = "Синтез речи..."
            try {
                val audioBytes = SpeechKitApi.synthesize(
                    text = text,
                    voice = selectedVoice,
                    role = selectedRole.ifBlank { null },
                    speed = speed,
                    pitchShift = pitchShift,
                    format = selectedFormat,
                    token = TokenStorage.iamToken,
                )
                val filePath = saveAudioFile(audioBytes, selectedFormat)
                statusMessage = "Сохранено: $filePath"
            } catch (e: Exception) {
                statusMessage = "Ошибка: ${e.message}"
            } finally {
                isLoading = false
                progressMessage = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpeechHelper") },
                actions = {
                    TextButton(onClick = onTokenRefresh) {
                        Text("Обновить токен")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Main area: text + voice panel side by side
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст для озвучивания") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    minLines = 5,
                )

                if (hasMarkers && detectedVoices.isNotEmpty()) {
                    VoiceMappingPanel(
                        voiceNames = detectedVoices.toList(),
                        mapping = voiceMapping,
                        onSettingsChange = { name, settings ->
                            voiceMapping[name] = settings
                            saveVoiceMapping()
                        },
                        onRetryVoice = { name ->
                            launchMultiVoiceSynthesis(retryVoice = name)
                        },
                        isLoading = isLoading,
                        modifier = Modifier.width(350.dp).fillMaxHeight(),
                    )
                }
            }

            // Controls row
            if (!hasMarkers) {
                val currentVoiceInfo = API_VOICES_INFO.find { it.id == selectedVoice }
                val availableRoles = currentVoiceInfo?.roles ?: emptyList()

                // Reset role if not available for this voice
                LaunchedEffect(selectedVoice) {
                    if (selectedRole.isNotBlank() && selectedRole !in availableRoles) {
                        selectedRole = ""
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DropdownSelector("Голос", API_VOICES, selectedVoice) { selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, selectedFormat) { selectedFormat = it }
                    if (availableRoles.isNotEmpty()) {
                        DropdownSelector(
                            "Амплуа",
                            listOf("") + availableRoles,
                            selectedRole,
                            displayTransform = { it.ifBlank { "нет" } }
                        ) { selectedRole = it }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Скорость: ${"%.1f".format(speed)}")
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { speed = it.toDouble() },
                        valueRange = 0.1f..3.0f,
                        modifier = Modifier.width(200.dp),
                    )

                    Text("Тон: ${"%.0f".format(pitchShift)}")
                    Slider(
                        value = pitchShift.toFloat(),
                        onValueChange = { pitchShift = it.toDouble() },
                        valueRange = -1000f..1000f,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }

            // Action row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (hasMarkers) {
                            clearCache()
                            launchMultiVoiceSynthesis()
                        } else {
                            launchSimpleSynthesis()
                        }
                    },
                    enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                ) {
                    Text("Озвучить")
                }

                if (hasMarkers) {
                    OutlinedButton(
                        onClick = { launchMultiVoiceSynthesis() },
                        enabled = text.isNotBlank() && !isLoading && TokenStorage.hasCredentials(),
                    ) {
                        Text("Повторить ошибки")
                    }

                    TextButton(
                        onClick = { clearCache() },
                        enabled = !isLoading,
                    ) {
                        Text("Очистить кэш")
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    if (progressMessage.isNotBlank()) {
                        Text(progressMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    color = if (statusMessage.startsWith("Ошибка"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun VoiceMappingPanel(
    voiceNames: List<String>,
    mapping: Map<String, VoiceSettings>,
    onSettingsChange: (name: String, settings: VoiceSettings) -> Unit,
    onRetryVoice: (name: String) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val expandedVoices = remember { mutableStateMapOf<String, Boolean>() }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Голоса", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()

            voiceNames.forEach { name ->
                val settings = mapping[name] ?: VoiceSettings()
                val expanded = expandedVoices[name] ?: false
                val voiceInfo = API_VOICES_INFO.find { it.id == settings.voice }
                val availableRoles = voiceInfo?.roles ?: emptyList()

                // Compact summary (always visible, clickable to toggle)
                val roleSummary = settings.role.ifBlank { "-" }
                val speedSummary = "%.1f".format(settings.speed)
                val pitchSummary = "%.0f".format(settings.pitchShift)
                val genderIcon = voiceInfo?.gender ?: "?"

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
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${settings.voice}($genderIcon) | $roleSummary | x$speedSummary | ${pitchSummary}Hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    items = API_VOICES,
                                    selected = settings.voice,
                                    onSelect = { voice ->
                                        val newRoles = API_VOICES_INFO.find { it.id == voice }?.roles ?: emptyList()
                                        val newRole = if (settings.role in newRoles) settings.role else ""
                                        onSettingsChange(name, settings.copy(voice = voice, role = newRole))
                                    }
                                )
                            }

                            if (availableRoles.isNotEmpty()) {
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
                                        onSelect = { onSettingsChange(name, settings.copy(role = it)) }
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Скорость: $speedSummary", style = MaterialTheme.typography.bodySmall)
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
                                Text("Тон: $pitchSummary", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = settings.pitchShift.toFloat(),
                                    onValueChange = { onSettingsChange(name, settings.copy(pitchShift = it.toDouble())) },
                                    valueRange = -1000f..1000f,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
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

@Composable
private fun DropdownSelector(
    label: String,
    items: List<String>,
    selected: String,
    displayTransform: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            val display = displayTransform(selected)
            Text(if (label.isNotBlank()) "$label: $display" else display)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(displayTransform(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

private suspend fun saveAudioFile(bytes: ByteArray, format: String): String {
    return withContext(Dispatchers.IO) {
        val ext = when (format) {
            "oggopus" -> "ogg"
            else -> format
        }
        val dir = File(System.getProperty("user.home"), "SpeechHelper")
        dir.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(dir, "speech_${timestamp}.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }
}
