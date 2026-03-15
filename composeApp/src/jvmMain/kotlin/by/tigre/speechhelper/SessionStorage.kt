package by.tigre.speechhelper

import java.io.File

object SessionStorage {
    private val dir = File(System.getProperty("user.home"), ".speechhelper").apply { mkdirs() }
    private val textFile = File(dir, "session_text.txt")
    private val mappingFile = File(dir, "session_mapping.txt")

    var text: String
        get() = if (textFile.exists()) textFile.readText() else ""
        set(value) = textFile.writeText(value)

    var voiceMapping: Map<String, VoiceSettings>
        get() {
            if (!mappingFile.exists()) return emptyMap()
            return mappingFile.readLines().mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0]
                    val fields = parts[1].split("|")
                    val voice = fields.getOrElse(0) { "dasha" }
                    val role = fields.getOrElse(1) { "" }
                    val speed = fields.getOrElse(2) { "1.0" }.toDoubleOrNull() ?: 1.0
                    val pitchShift = fields.getOrElse(3) { "0.0" }.toDoubleOrNull() ?: 0.0
                    name to VoiceSettings(voice, role, speed, pitchShift)
                } else null
            }.toMap()
        }
        set(value) {
            mappingFile.writeText(
                value.entries.joinToString("\n") { (name, s) ->
                    "$name=${s.voice}|${s.role}|${s.speed}|${s.pitchShift}"
                }
            )
        }
}
