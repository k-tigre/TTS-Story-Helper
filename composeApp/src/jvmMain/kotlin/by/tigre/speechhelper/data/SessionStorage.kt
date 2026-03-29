package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.ChapterInfo
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.VoiceSettings
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SessionStorage {
    private val dir = File(System.getProperty("user.home"), ".speechhelper").apply { mkdirs() }
    private val chaptersDir = File(dir, "chapters").apply { mkdirs() }
    private val currentChapterFile = File(dir, "current_chapter.txt")
    private val mappingFile = File(dir, "voice_mapping.txt")
    private val currentBookFile = File(dir, "current_book.txt")
    private val synthesisPrefsFile = File(dir, "synthesis_prefs.txt")

    init {
        migrateIfNeeded()
    }

    private fun readSynthPrefs(): MutableMap<String, String> =
        synthesisPrefsFile.takeIf { it.exists() }
            ?.readLines()
            ?.mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.take(i).trim() to line.substring(i + 1).trim()
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()

    private fun writeSynthPrefs(m: Map<String, String>) {
        synthesisPrefsFile.writeText(
            m.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" },
        )
    }

    var synthesisBackend: SynthesisBackend
        get() = when (readSynthPrefs()["backend"]?.lowercase()) {
            "local" -> SynthesisBackend.Local
            else -> SynthesisBackend.Cloud
        }
        set(value) {
            val m = readSynthPrefs()
            m["backend"] = when (value) {
                SynthesisBackend.Cloud -> "cloud"
                SynthesisBackend.Local -> "local"
            }
            writeSynthPrefs(m)
        }

    var localTtsSettings: LocalTtsSettings
        get() {
            val m = readSynthPrefs()
            val def = LocalTtsSettings()
            return LocalTtsSettings(
                baseUrl = m["local_base_url"]?.ifBlank { null } ?: def.baseUrl,
                modelId = m["local_model"]?.ifBlank { null } ?: def.modelId,
                sampleRate = m["local_sample_rate"]?.toIntOrNull() ?: def.sampleRate,
            )
        }
        set(value) {
            val m = readSynthPrefs()
            m["local_base_url"] = value.baseUrl
            m["local_model"] = value.modelId
            m["local_sample_rate"] = value.sampleRate.toString()
            writeSynthPrefs(m)
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
            val w = readChapterWorkflowState(d.name)
            ChapterInfo(d.name, name, w.markupDone, w.voiceDone, w.exported)
        }
    }

    private fun workflowFile(id: String) = File(chapterDir(id), "workflow.txt")

    private data class ChapterWorkflowState(
        val markupDone: Boolean = false,
        val voiceDone: Boolean = false,
        val exported: Boolean = false,
    )

    private fun readChapterWorkflowState(id: String): ChapterWorkflowState {
        val f = workflowFile(id)
        if (!f.exists()) return ChapterWorkflowState()
        var markupDone = false
        var voiceDone = false
        var exported = false
        for (line in f.readLines()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.take(idx).trim()
            val value = line.substring(idx + 1).trim()
            val truthy = value == "1" || value.equals("true", ignoreCase = true)
            when (key) {
                "markup_done" -> markupDone = truthy
                "voice_done" -> voiceDone = truthy
                "exported" -> exported = truthy
            }
        }
        return ChapterWorkflowState(markupDone, voiceDone, exported)
    }

    private fun writeChapterWorkflowState(id: String, state: ChapterWorkflowState) {
        workflowFile(id).writeText(
            "markup_done=${if (state.markupDone) 1 else 0}\n" +
                "voice_done=${if (state.voiceDone) 1 else 0}\n" +
                "exported=${if (state.exported) 1 else 0}\n",
        )
    }

    private fun invalidateChapterExportIfMarked(id: String) {
        val s = readChapterWorkflowState(id)
        if (s.exported) {
            writeChapterWorkflowState(id, s.copy(exported = false))
        }
    }

    fun setChapterMarkupDone(id: String, done: Boolean) {
        val s = readChapterWorkflowState(id)
        writeChapterWorkflowState(id, s.copy(markupDone = done))
    }

    fun setChapterVoiceDone(id: String, done: Boolean) {
        val s = readChapterWorkflowState(id)
        writeChapterWorkflowState(id, s.copy(voiceDone = done))
    }

    fun setChapterExported(id: String, exported: Boolean) {
        val s = readChapterWorkflowState(id)
        writeChapterWorkflowState(id, s.copy(exported = exported))
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

    var currentBookName: String
        get() = currentBookFile.takeIf { it.exists() }?.readText()?.trim() ?: ""
        set(value) {
            if (value.isNotBlank()) currentBookFile.writeText(value)
            else if (currentBookFile.exists()) currentBookFile.delete()
        }

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
        val f = File(chapterDir(id), "text.txt")
        val old = if (f.exists()) f.readText() else ""
        if (old != text) {
            invalidateChapterExportIfMarked(id)
        }
        f.writeText(text)
    }

    fun getOriginalText(id: String): String {
        val f = File(chapterDir(id), "original_text.txt")
        return if (f.exists()) f.readText() else ""
    }

    fun setOriginalText(id: String, text: String) {
        val f = File(chapterDir(id), "original_text.txt")
        val old = if (f.exists()) f.readText() else ""
        if (old != text) {
            invalidateChapterExportIfMarked(id)
        }
        f.writeText(text)
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

    fun getChapterAudioPath(id: String): String? {
        val f = File(chapterDir(id), "audio_path.txt")
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    fun setChapterAudioPath(id: String, path: String) {
        val f = File(chapterDir(id), "audio_path.txt")
        val old = if (f.exists()) f.readText().trim() else ""
        val newPath = path.trim()
        if (old != newPath) {
            invalidateChapterExportIfMarked(id)
        }
        f.writeText(path)
    }

    fun clearChapterAudioPath(id: String) {
        val f = File(chapterDir(id), "audio_path.txt")
        if (f.exists()) {
            invalidateChapterExportIfMarked(id)
            f.delete()
        }
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
            val origText = getOriginalText(chapter.id)
            if (origText.isNotBlank()) {
                sb.appendLine("##ORIGINAL_TEXT##")
                sb.appendLine(origText)
            }
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
        data class ChapterData(val name: String, val text: String, val originalText: String)
        val newChapters = mutableListOf<ChapterData>()
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
            var chapterText = ""
            var chapterOriginalText = ""
            if (i < lines.size && lines[i] == "##TEXT##") {
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i] != "##CHAPTER##" && lines[i] != "##END##" && lines[i] != "##ORIGINAL_TEXT##") {
                    textLines.add(lines[i])
                    i++
                }
                chapterText = textLines.joinToString("\n")
            }
            if (i < lines.size && lines[i] == "##ORIGINAL_TEXT##") {
                i++
                val origLines = mutableListOf<String>()
                while (i < lines.size && lines[i] != "##CHAPTER##" && lines[i] != "##END##") {
                    origLines.add(lines[i])
                    i++
                }
                chapterOriginalText = origLines.joinToString("\n")
            }
            newChapters.add(ChapterData(chapterName, chapterText, chapterOriginalText))
        }

        if (newChapters.isEmpty()) return false

        // Clear existing chapters
        clearAllData()

        // Create new chapters
        var firstId: String? = null
        for (chapter in newChapters) {
            val id = createChapter(chapter.name)
            setChapterText(id, chapter.text)
            if (chapter.originalText.isNotBlank()) {
                setOriginalText(id, chapter.originalText)
            }
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
        // Clear current book name
        if (currentBookFile.exists()) currentBookFile.delete()
        if (synthesisPrefsFile.exists()) synthesisPrefsFile.delete()
    }
}
