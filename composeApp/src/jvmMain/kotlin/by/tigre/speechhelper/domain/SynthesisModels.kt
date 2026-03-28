package by.tigre.speechhelper.domain

enum class SynthesisBackend {
    Cloud,
    Local,
}

data class LocalTtsSettings(
    val baseUrl: String = "http://127.0.0.1:8765",
    val modelId: String = "v5_ru",
    val sampleRate: Int = 48000,
)

/** Silero v5_ru speakers (see https://github.com/snakers4/silero-models) */
val SILERO_V5_RU_SPEAKERS = listOf("aidar", "baya", "kseniya", "eugene", "xenia")

/**
 * Имена голосов Yandex SpeechKit → ближайший спикер Silero v5_ru (пол/характер на глаз).
 * Если в [voiceId] уже id Silero — возвращается он же (с каноническим регистром).
 */
fun yandexVoiceIdToSileroSpeaker(voiceId: String): String {
    val t = voiceId.trim()
    SILERO_V5_RU_SPEAKERS.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
    return YANDEX_VOICE_TO_SILERO_SPEAKER[t.lowercase()] ?: "baya"
}

/** Ключи — lowercase id голосов Yandex из [API_VOICES_INFO]. */
private val YANDEX_VOICE_TO_SILERO_SPEAKER = mapOf(
    "alena" to "baya",
    "filipp" to "aidar",
    "ermil" to "eugene",
    "jane" to "xenia",
    "omazh" to "xenia",
    "zahar" to "aidar",
    "dasha" to "baya",
    "julia" to "kseniya",
    "lera" to "baya",
    "masha" to "xenia",
    "marina" to "xenia",
    "alexander" to "aidar",
    "kirill" to "eugene",
    "anton" to "aidar",
    "madi_ru" to "aidar",
    "saule_ru" to "kseniya",
    "zamira_ru" to "baya",
    "zhanar_ru" to "baya",
    "yulduz_ru" to "xenia",
)

val LOCAL_TTS_SAMPLE_RATES = listOf(8000, 24000, 48000)
