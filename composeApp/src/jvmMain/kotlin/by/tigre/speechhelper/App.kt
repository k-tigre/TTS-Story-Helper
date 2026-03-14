package by.tigre.speechhelper

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

private val API_VOICES_INFO = listOf(
    VoiceInfo("dasha", "Ж", listOf("neutral", "good", "friendly")),
    VoiceInfo("julia", "Ж", listOf("neutral", "strict")),
    VoiceInfo("lera", "Ж", listOf("neutral", "friendly")),
    VoiceInfo("masha", "Ж", listOf("good", "strict", "friendly")),
    VoiceInfo("alexander", "М", listOf("neutral", "good")),
    VoiceInfo("kirill", "М", listOf("neutral", "strict", "good")),
    VoiceInfo("anton", "М", listOf("neutral", "good")),
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

    var isLoading by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    val voiceMapping = remember { mutableStateMapOf<String, String>() }

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
    val voiceRoles: Map<String, Set<String>> = remember(text) {
        if (hasMarkers) TextParser.extractVoiceRoles(text) else emptyMap()
    }

    // Ensure all detected voices have a mapping
    LaunchedEffect(detectedVoices) {
        for (name in detectedVoices) {
            if (name !in voiceMapping) {
                voiceMapping[name] = API_VOICES[0]
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
                    val apiVoice = voiceMapping[segment.voiceName] ?: API_VOICES[0]
                    val partFile = File(cacheDir, "part_%03d.mp3".format(index))

                    // Skip already cached unless retrying this voice
                    if (partFile.exists() && (retryVoice == null || segment.voiceName != retryVoice)) {
                        progressMessage = "Часть ${index + 1} из ${segments.size} — кэш"
                        continue
                    }

                    progressMessage = "Обработка ${index + 1} из ${segments.size} (${segment.voiceName} -> $apiVoice)..."
                    try {
                        val bytes = SpeechKitApi.synthesize(
                            text = segment.text,
                            voice = apiVoice,
                            role = segment.role,
                            speed = segment.speed,
                            format = "mp3",
                            token = TokenStorage.iamToken,
                        )
                        withContext(Dispatchers.IO) { partFile.writeBytes(bytes) }
                    } catch (e: Exception) {
                        errors.add("#${index + 1} ${segment.voiceName}: ${e.message}")
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
                    role = null,
                    speed = speed,
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
                        voiceRoles = voiceRoles,
                        mapping = voiceMapping,
                        onMappingChange = { name, apiVoice ->
                            voiceMapping[name] = apiVoice
                            saveVoiceMapping()
                        },
                        onRetryVoice = { name ->
                            launchMultiVoiceSynthesis(retryVoice = name)
                        },
                        isLoading = isLoading,
                        modifier = Modifier.width(280.dp).fillMaxHeight(),
                    )
                }
            }

            // Controls row
            if (!hasMarkers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DropdownSelector("Голос", API_VOICES, selectedVoice) { selectedVoice = it }
                    DropdownSelector("Формат", FORMATS, selectedFormat) { selectedFormat = it }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Скорость: ${"%.1f".format(speed)}")
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { speed = it.toDouble() },
                        valueRange = 0.1f..3.0f,
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
    voiceRoles: Map<String, Set<String>>,
    mapping: Map<String, String>,
    onMappingChange: (name: String, apiVoice: String) -> Unit,
    onRetryVoice: (name: String) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Голоса", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()

            voiceNames.forEach { name ->
                val textRoles = voiceRoles[name]
                val selectedApiVoice = mapping[name] ?: API_VOICES[0]
                val apiVoiceInfo = API_VOICES_INFO.find { it.id == selectedApiVoice }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                            if (!textRoles.isNullOrEmpty()) {
                                Text(
                                    text = "роли: ${textRoles.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DropdownSelector(
                            label = "",
                            items = API_VOICES,
                            selected = selectedApiVoice,
                            onSelect = { onMappingChange(name, it) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (apiVoiceInfo != null) {
                            Text(
                                text = "${apiVoiceInfo.gender}, ${apiVoiceInfo.roles.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = { onRetryVoice(name) },
                            enabled = !isLoading,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Переозвучить", style = MaterialTheme.typography.bodySmall)
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
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (label.isNotBlank()) "$label: $selected" else selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
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
