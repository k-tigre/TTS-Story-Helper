package by.tigre.speechhelper.domain

data class ChapterInfo(
    val id: String,
    val name: String,
    /** Пользователь отметил, что правка разметки завершена */
    val markupDone: Boolean = false,
    /** Пользователь отметил, что озвучка принята (есть файл) */
    val voiceDone: Boolean = false,
)


data class VoiceInfo(val id: String, val gender: String, val roles: List<String>)

data class VoiceSettings(
    val voice: String = "dasha",
    val role: String = "",
    val speed: Double = 1.0,
    val pitchShift: Double = 0.0,
)

val API_VOICES_INFO = listOf(
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

val API_VOICES = API_VOICES_INFO.map { it.id }

val FORMATS = listOf("mp3", "ogg", "wav")

enum class LlmProvider(val label: String, val defaultBaseUrl: String) {
    OpenAI("OpenAI", "https://api.openai.com/v1"),
    Ollama("Ollama", "http://localhost:11434/v1"),
    LMStudio("LM Studio", "http://localhost:1234/v1"),
    YandexCloud("Yandex Cloud", "https://ai.api.cloud.yandex.net/v1"),
}

const val MARKUP_CHUNK_LOCAL_DEFAULT = 1500
const val MARKUP_CHUNK_REMOTE_DEFAULT = 3000
const val MARKUP_CHUNK_MIN = 500
const val MARKUP_CHUNK_MAX = 32000

/** Предлагаемый размер чанка авто-разметки: локальный адрес — меньше, удалённый API — больше. */
fun defaultMarkupChunkForBaseUrl(baseUrl: String): Int {
    val u = baseUrl.lowercase()
    return if ("localhost" in u || "127.0.0.1" in u) MARKUP_CHUNK_LOCAL_DEFAULT else MARKUP_CHUNK_REMOTE_DEFAULT
}

data class LlmConfig(
    val provider: LlmProvider = LlmProvider.OpenAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val markupChunkChars: Int = defaultMarkupChunkForBaseUrl(baseUrl),
) {
    val isConfigured: Boolean get() = model.isNotBlank()
}
