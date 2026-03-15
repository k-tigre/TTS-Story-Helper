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
}
