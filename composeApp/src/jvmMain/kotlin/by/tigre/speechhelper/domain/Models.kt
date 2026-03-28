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
