package by.tigre.speechhelper

import java.io.File

object SessionStorage {
    private val dir = File(System.getProperty("user.home"), ".speechhelper").apply { mkdirs() }
    private val textFile = File(dir, "session_text.txt")
    private val mappingFile = File(dir, "session_mapping.txt")

    var text: String
        get() = if (textFile.exists()) textFile.readText() else ""
        set(value) = textFile.writeText(value)

    var voiceMapping: Map<String, String>
        get() {
            if (!mappingFile.exists()) return emptyMap()
            return mappingFile.readLines().mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
        }
        set(value) {
            mappingFile.writeText(
                value.entries.joinToString("\n") { "${it.key}=${it.value}" }
            )
        }
}
