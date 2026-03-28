package by.tigre.speechhelper.data

object SynthesisChunking {
    fun splitText(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)

        val chunks = mutableListOf<String>()
        val sentences = text.split(Regex("""(?<=[.!?;])\s+"""))
        val current = StringBuilder()

        for (sentence in sentences) {
            if (sentence.length > limit) {
                if (current.isNotBlank()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                val parts = sentence.split(Regex("""(?<=,)\s*"""))
                for (part in parts) {
                    if (current.length + part.length + 1 > limit && current.isNotBlank()) {
                        chunks.add(current.toString().trim())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(part)
                }
            } else if (current.length + sentence.length + 1 > limit) {
                chunks.add(current.toString().trim())
                current.clear()
                current.append(sentence)
            } else {
                if (current.isNotEmpty()) current.append(" ")
                current.append(sentence)
            }
        }

        if (current.isNotBlank()) {
            chunks.add(current.toString().trim())
        }

        return chunks.ifEmpty { listOf(text) }
    }
}
