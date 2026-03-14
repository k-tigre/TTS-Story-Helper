package by.tigre.speechhelper

data class TextSegment(
    val voiceName: String,
    val emotion: String?,
    val speed: Float,
    val text: String,
)

object TextParser {

    private val MARKER_REGEX = Regex(
        """<!--\s*voice:\s*([^,]+?)(?:\s*,\s*emotion:\s*([^,]+?))?(?:\s*,\s*speed:\s*([^-]+?))?\s*-->"""
    )

    fun parse(input: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        val lines = input.lines()

        var currentVoice: String? = null
        var currentEmotion: String? = null
        var currentSpeed = 1.0f
        val currentText = StringBuilder()

        for (line in lines) {
            val match = MARKER_REGEX.find(line.trim())
            if (match != null) {
                if (currentVoice != null) {
                    val text = currentText.toString().trim()
                    if (text.isNotBlank()) {
                        segments.add(TextSegment(currentVoice, currentEmotion, currentSpeed, text))
                    }
                    currentText.clear()
                }
                currentVoice = match.groupValues[1].trim()
                currentEmotion = match.groupValues[2].trim().ifBlank { null }
                currentSpeed = match.groupValues[3].trim().toFloatOrNull() ?: 1.0f
            } else {
                if (currentVoice != null) {
                    currentText.appendLine(line)
                }
            }
        }

        if (currentVoice != null) {
            val text = currentText.toString().trim()
            if (text.isNotBlank()) {
                segments.add(TextSegment(currentVoice, currentEmotion, currentSpeed, text))
            }
        }

        return segments
    }

    fun extractVoiceNames(input: String): Set<String> {
        return MARKER_REGEX.findAll(input).map { it.groupValues[1].trim() }.toSet()
    }

    fun hasVoiceMarkers(input: String): Boolean {
        return MARKER_REGEX.containsMatchIn(input)
    }
}
