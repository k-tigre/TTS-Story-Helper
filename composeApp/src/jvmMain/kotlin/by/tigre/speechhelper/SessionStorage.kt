package by.tigre.speechhelper

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ChapterInfo(val id: String, val name: String)

object SessionStorage {
    private val dir = File(System.getProperty("user.home"), ".speechhelper").apply { mkdirs() }
    private val chaptersDir = File(dir, "chapters").apply { mkdirs() }
    private val currentChapterFile = File(dir, "current_chapter.txt")
    private val mappingFile = File(dir, "voice_mapping.txt")

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        val oldTextFile = File(dir, "session_text.txt")
        val oldMappingFile = File(dir, "session_mapping.txt")
        if (oldTextFile.exists() || oldMappingFile.exists()) {
            val id = createChapter("Глава 1")
            if (oldTextFile.exists()) {
                setChapterText(id, oldTextFile.readText())
                oldTextFile.delete()
            }
            if (oldMappingFile.exists()) {
                // Migrate old per-session mapping to global mapping
                mappingFile.writeText(oldMappingFile.readText())
                oldMappingFile.delete()
            }
            currentChapterId = id

            // Migrate old cache
            val oldCacheDir = File(System.getProperty("user.home"), "SpeechHelper/cache")
            if (oldCacheDir.exists() && oldCacheDir.listFiles()?.any { it.isFile } == true) {
                val newCacheDir = getChapterCacheDir(id)
                oldCacheDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    file.renameTo(File(newCacheDir, file.name))
                }
            }
        }
    }

    private fun chapterDir(id: String): File = File(chaptersDir, id).apply { mkdirs() }

    fun listChapters(): List<ChapterInfo> {
        val dirs = chaptersDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        return dirs.map { d ->
            val name = File(d, "meta.txt").takeIf { it.exists() }?.readText()?.trim() ?: d.name
            ChapterInfo(d.name, name)
        }
    }

    fun createChapter(name: String): String {
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val d = chapterDir(id)
        File(d, "meta.txt").writeText(name)
        File(d, "text.txt").writeText("")
        return id
    }

    fun deleteChapter(id: String) {
        File(chaptersDir, id).deleteRecursively()
        clearChapterCache(id)
        if (currentChapterId == id) {
            val remaining = listChapters()
            currentChapterId = remaining.firstOrNull()?.id
        }
    }

    fun renameChapter(id: String, name: String) {
        val d = chapterDir(id)
        File(d, "meta.txt").writeText(name)
    }

    var currentChapterId: String?
        get() = currentChapterFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
        set(value) = if (value != null) currentChapterFile.writeText(value) else currentChapterFile.delete().let {}

    fun ensureCurrentChapter(): String {
        var id = currentChapterId
        if (id == null || !File(chaptersDir, id).exists()) {
            val chapters = listChapters()
            id = if (chapters.isNotEmpty()) {
                chapters.first().id
            } else {
                createChapter("Глава 1")
            }
            currentChapterId = id
        }
        return id
    }

    fun getChapterText(id: String): String {
        val f = File(chapterDir(id), "text.txt")
        return if (f.exists()) f.readText() else ""
    }

    fun setChapterText(id: String, text: String) {
        File(chapterDir(id), "text.txt").writeText(text)
    }

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

    fun getChapterCacheDir(id: String): File {
        return File(System.getProperty("user.home"), "SpeechHelper/cache/$id").apply { mkdirs() }
    }

    fun clearChapterCache(id: String) {
        val cacheDir = File(System.getProperty("user.home"), "SpeechHelper/cache/$id")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    var windowWidth: Int
        get() = windowSizeFile.takeIf { it.exists() }?.readLines()?.getOrNull(0)?.toIntOrNull() ?: 800
        set(value) = windowSizeFile.writeText("$value\n$windowHeight")

    var windowHeight: Int
        get() = windowSizeFile.takeIf { it.exists() }?.readLines()?.getOrNull(1)?.toIntOrNull() ?: 600
        set(value) = windowSizeFile.writeText("$windowWidth\n$value")

    private val windowSizeFile = File(dir, "window_size.txt")

    fun saveWindowSize(width: Int, height: Int) {
        windowSizeFile.writeText("$width\n$height")
    }

    // --- Book save/load ---

    private val booksDir = File(dir, "books").apply { mkdirs() }

    fun listBooks(): List<String> {
        return booksDir.listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun saveBook(bookName: String) {
        val sb = StringBuilder()
        sb.appendLine("##BOOK_FORMAT_V1##")
        sb.appendLine("##VOICE_MAPPING##")
        val mapping = voiceMapping
        for ((name, s) in mapping) {
            sb.appendLine("$name=${s.voice}|${s.role}|${s.speed}|${s.pitchShift}")
        }
        val chapters = listChapters()
        for (chapter in chapters) {
            sb.appendLine("##CHAPTER##")
            sb.appendLine(chapter.name)
            sb.appendLine("##TEXT##")
            sb.appendLine(getChapterText(chapter.id))
        }
        sb.appendLine("##END##")

        val safeFileName = bookName.replace(Regex("[^\\w\\s\\-()\\[\\]а-яА-ЯёЁ]"), "_").trim()
        File(booksDir, "$safeFileName.txt").writeText(sb.toString())
    }

    fun loadBook(bookName: String): Boolean {
        val file = File(booksDir, "$bookName.txt")
        if (!file.exists()) return false

        val lines = file.readLines()
        if (lines.firstOrNull() != "##BOOK_FORMAT_V1##") return false

        // Parse voice mapping
        val newMapping = mutableMapOf<String, VoiceSettings>()
        val newChapters = mutableListOf<Pair<String, String>>() // name to text
        var i = 1
        // Parse voice mapping section
        if (i < lines.size && lines[i] == "##VOICE_MAPPING##") {
            i++
            while (i < lines.size && lines[i] != "##CHAPTER##" && lines[i] != "##END##") {
                val parts = lines[i].split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0]
                    val fields = parts[1].split("|")
                    val voice = fields.getOrElse(0) { "dasha" }
                    val role = fields.getOrElse(1) { "" }
                    val speed = fields.getOrElse(2) { "1.0" }.toDoubleOrNull() ?: 1.0
                    val pitchShift = fields.getOrElse(3) { "0.0" }.toDoubleOrNull() ?: 0.0
                    newMapping[name] = VoiceSettings(voice, role, speed, pitchShift)
                }
                i++
            }
        }

        // Parse chapters
        while (i < lines.size && lines[i] == "##CHAPTER##") {
            i++
            val chapterName = if (i < lines.size) lines[i] else "Без названия"
            i++
            if (i < lines.size && lines[i] == "##TEXT##") {
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i] != "##CHAPTER##" && lines[i] != "##END##") {
                    textLines.add(lines[i])
                    i++
                }
                newChapters.add(chapterName to textLines.joinToString("\n"))
            }
        }

        if (newChapters.isEmpty()) return false

        // Clear existing chapters
        clearAllData()

        // Create new chapters
        var firstId: String? = null
        for ((name, text) in newChapters) {
            val id = createChapter(name)
            setChapterText(id, text)
            if (firstId == null) firstId = id
        }

        // Set voice mapping
        voiceMapping = newMapping

        // Set current chapter
        if (firstId != null) {
            currentChapterId = firstId
        }

        return true
    }

    fun deleteBook(bookName: String) {
        File(booksDir, "$bookName.txt").delete()
    }

    fun clearAllData() {
        // Delete all chapters
        chaptersDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        // Clear cache
        val cacheRoot = File(System.getProperty("user.home"), "SpeechHelper/cache")
        if (cacheRoot.exists()) cacheRoot.deleteRecursively()
        // Clear voice mapping
        if (mappingFile.exists()) mappingFile.delete()
        // Clear current chapter
        if (currentChapterFile.exists()) currentChapterFile.delete()
    }
}
