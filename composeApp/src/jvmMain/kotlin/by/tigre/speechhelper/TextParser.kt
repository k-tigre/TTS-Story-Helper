package by.tigre.speechhelper

data class TextSegment(
    val voiceName: String,
    val role: String?,
    val speed: Double,
    val text: String,
)

object TextParser {

    private val MARKER_REGEX = Regex(
        """<!--\s*voice:\s*([^,]+?)(?:\s*,\s*(?:role|emotion):\s*([^,]+?))?(?:\s*,\s*speed:\s*([^-]+?))?\s*-->"""
    )

    fun parse(input: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        val lines = input.lines()

        var currentVoice: String? = null
        var currentRole: String? = null
        var currentSpeed = 1.0
        val currentText = StringBuilder()

        for (line in lines) {
            val match = MARKER_REGEX.find(line.trim())
            if (match != null) {
                if (currentVoice != null) {
                    val text = currentText.toString().trim()
                    if (text.isNotBlank()) {
                        segments.add(TextSegment(currentVoice, currentRole, currentSpeed, stripSsmlTags(text)))
                    }
                    currentText.clear()
                }
                currentVoice = match.groupValues[1].trim()
                currentRole = match.groupValues[2].trim().ifBlank { null }
                currentSpeed = match.groupValues[3].trim().toDoubleOrNull() ?: 1.0
            } else {
                if (currentVoice != null) {
                    currentText.appendLine(line)
                }
            }
        }

        if (currentVoice != null) {
            val text = currentText.toString().trim()
            if (text.isNotBlank()) {
                segments.add(TextSegment(currentVoice, currentRole, currentSpeed, stripSsmlTags(text)))
            }
        }

        return segments
    }

    fun extractVoiceNames(input: String): Set<String> {
        return MARKER_REGEX.findAll(input).map { it.groupValues[1].trim() }.toSet()
    }

    fun extractVoiceRoles(input: String): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        for (match in MARKER_REGEX.findAll(input)) {
            val voice = match.groupValues[1].trim()
            val role = match.groupValues[2].trim()
            if (role.isNotBlank()) {
                result.getOrPut(voice) { mutableSetOf() }.add(role)
            }
        }
        return result
    }

    fun hasVoiceMarkers(input: String): Boolean {
        return MARKER_REGEX.containsMatchIn(input)
    }

    private fun stripSsmlTags(text: String): String {
        return text
            .replace(Regex("""<break\s+[^>]*/>"""), " ")
            .replace(Regex("""</?speak>"""), "")
            .replace(Regex("""<prosody[^>]*>"""), "")
            .replace(Regex("""</prosody>"""), "")
            .replace(Regex("""<emphasis[^>]*>"""), "")
            .replace(Regex("""</emphasis>"""), "")
            .replace(Regex("""<phoneme[^>]*>"""), "")
            .replace(Regex("""</phoneme>"""), "")
            .replace(Regex("""</?s>"""), "")
            .replace(Regex("""</?p>"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
