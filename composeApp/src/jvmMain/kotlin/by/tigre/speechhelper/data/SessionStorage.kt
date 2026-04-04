package by.tigre.speechhelper.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import by.tigre.speechhelper.domain.ChapterInfo
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.VoiceSettings
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import tigre.speechhelper.db.SpeechHelperDatabase

object SessionStorage {
    private const val META_CURRENT_CHAPTER = "current_chapter_id"
    private const val META_CURRENT_BOOK = "current_book_name"
    private const val META_WINDOW_W = "window_width"
    private const val META_WINDOW_H = "window_height"

    private val dir = File(System.getProperty("user.home"), ".speechhelper").apply { mkdirs() }
    private val chaptersDir = File(dir, "chapters").apply { mkdirs() }
    private val currentChapterFile = File(dir, "current_chapter.txt")
    private val mappingFile = File(dir, "voice_mapping.txt")
    private val currentBookFile = File(dir, "current_book.txt")
    private val synthesisPrefsFile = File(dir, "synthesis_prefs.txt")
    private val windowSizeFile = File(dir, "window_size.txt")
    private val booksDir = File(dir, "books").apply { mkdirs() }

    private val dbLock = Any()

    private val holder: Holder by lazy { createHolder() }

    private class Holder(
        val database: SpeechHelperDatabase,
    )

    private fun createHolder(): Holder {
        val dbFile = File(dir, "speechhelper.db")
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite:${dbFile.absolutePath}")
        if (!dbFile.exists() || dbFile.length() == 0L) {
            SpeechHelperDatabase.Schema.create(driver).sync()
        }
        applyPragmas(driver)
        val database = SpeechHelperDatabase(driver)
        migrateLegacyFilesIfNeeded(database)
        return Holder(database)
    }

    private fun applyPragmas(driver: SqlDriver) {
        driver.execute(null, "PRAGMA journal_mode=WAL", 0, null).sync()
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0, null).sync()
        driver.execute(null, "PRAGMA foreign_keys=ON", 0, null).sync()
    }

    private fun <T> QueryResult<T>.sync(): T = (this as QueryResult.Value<T>).value

    private fun db() = holder.database

    private inline fun <T> withDb(crossinline block: SpeechHelperDatabase.() -> T): T = synchronized(dbLock) {
        db().block()
    }

    private fun metaGet(key: String): String? =
        withDb { appMetaQueries.getValue(key).executeAsOneOrNull() }

    private fun metaSet(key: String, value: String) {
        withDb { appMetaQueries.upsert(key, value).sync() }
    }

    private fun metaDelete(key: String) {
        withDb { appMetaQueries.deleteKey(key).sync() }
    }

    private fun readSynthPrefs(): MutableMap<String, String> {
        val keys = listOf("backend", "local_base_url", "local_model", "local_sample_rate")
        val m = mutableMapOf<String, String>()
        withDb {
            for (k in keys) {
                appMetaQueries.getValue(k).executeAsOneOrNull()?.let { v -> m[k] = v }
            }
        }
        return m
    }

    private fun writeSynthPrefs(m: Map<String, String>) {
        withDb {
            for ((k, v) in m.entries.sortedBy { it.key }) {
                appMetaQueries.upsert(k, v).sync()
            }
        }
    }

    private fun newChapterId(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

    private fun readLegacyWorkflow(f: File): Triple<Long, Long, Long> {
        if (!f.exists()) return Triple(0L, 0L, 0L)
        var markupDone = 0L
        var voiceDone = 0L
        var exported = 0L
        for (line in f.readLines()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.take(idx).trim()
            val value = line.substring(idx + 1).trim()
            val truthy = value == "1" || value.equals("true", ignoreCase = true)
            when (key) {
                "markup_done" -> markupDone = if (truthy) 1L else 0L
                "voice_done" -> voiceDone = if (truthy) 1L else 0L
                "exported" -> exported = if (truthy) 1L else 0L
            }
        }
        return Triple(markupDone, voiceDone, exported)
    }

    private fun parseVoiceLinesToDb(database: SpeechHelperDatabase, lines: Iterable<String>) {
        for (line in lines) {
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val name = parts[0]
            val fields = parts[1].split("|")
            val voice = fields.getOrElse(0) { "dasha" }
            val role = fields.getOrElse(1) { "" }
            val speed = fields.getOrElse(2) { "1.0" }.toDoubleOrNull() ?: 1.0
            val pitchShift = fields.getOrElse(3) { "0.0" }.toDoubleOrNull() ?: 0.0
            database.voiceMappingQueries.insertRow(name, voice, role, speed, pitchShift).sync()
        }
    }

    private fun migrateAncientSessionFiles(database: SpeechHelperDatabase) {
        val sessionText = File(dir, "session_text.txt")
        val sessionMapping = File(dir, "session_mapping.txt")
        if (!sessionText.exists() && !sessionMapping.exists()) return

        val id = newChapterId()
        val text = sessionText.takeIf { it.exists() }?.readText().orEmpty()
        database.chapterQueries.insertFull(id, "Глава 1", text, "", null, 0L, 0L, 0L).sync()
        if (sessionMapping.exists()) {
            parseVoiceLinesToDb(database, sessionMapping.readLines())
            sessionMapping.delete()
        }
        if (sessionText.exists()) sessionText.delete()
        database.appMetaQueries.upsert(META_CURRENT_CHAPTER, id).sync()

        val oldCacheDir = File(System.getProperty("user.home"), "SpeechHelper/cache")
        if (oldCacheDir.exists() && oldCacheDir.listFiles()?.any { it.isFile } == true) {
            val newCacheDir = getChapterCacheDir(id)
            oldCacheDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                file.renameTo(File(newCacheDir, file.name))
            }
        }
    }

    private fun migrateChapterDirsFromDisk(database: SpeechHelperDatabase) {
        val dirs = chaptersDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) return
        for (d in dirs) {
            val id = d.name
            val name = File(d, "meta.txt").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } ?: id
            val text = File(d, "text.txt").takeIf { it.exists() }?.readText().orEmpty()
            val orig = File(d, "original_text.txt").takeIf { it.exists() }?.readText().orEmpty()
            val audio = File(d, "audio_path.txt").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
            val (m, v, e) = readLegacyWorkflow(File(d, "workflow.txt"))
            database.chapterQueries.insertFull(id, name, text, orig, audio, m, v, e).sync()
            d.deleteRecursively()
        }
    }

    private fun importSynthPrefsFileIfExists(database: SpeechHelperDatabase) {
        if (!synthesisPrefsFile.exists()) return
        for (line in synthesisPrefsFile.readLines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.take(i).trim()
            val v = line.substring(i + 1).trim()
            database.appMetaQueries.upsert(k, v).sync()
        }
        synthesisPrefsFile.delete()
    }

    private fun migrateLegacyFilesIfNeeded(database: SpeechHelperDatabase) {
        val dirs = chaptersDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        val emptyDb = database.chapterQueries.chapterCount().executeAsOne() == 0L
        if (emptyDb) {
            if (dirs.isNotEmpty()) {
                migrateChapterDirsFromDisk(database)
            } else {
                migrateAncientSessionFiles(database)
            }
        } else if (dirs.isNotEmpty()) {
            dirs.forEach { it.deleteRecursively() }
        }

        if (mappingFile.exists()) {
            parseVoiceLinesToDb(database, mappingFile.readLines())
            mappingFile.delete()
        }

        currentChapterFile.takeIf { it.exists() }?.let { f ->
            val v = f.readText().trim()
            if (v.isNotBlank()) database.appMetaQueries.upsert(META_CURRENT_CHAPTER, v).sync()
            f.delete()
        }

        currentBookFile.takeIf { it.exists() }?.let { f ->
            val v = f.readText().trim()
            if (v.isNotBlank()) database.appMetaQueries.upsert(META_CURRENT_BOOK, v).sync()
            f.delete()
        }

        importSynthPrefsFileIfExists(database)

        windowSizeFile.takeIf { it.exists() }?.let { f ->
            val lines = f.readLines()
            lines.getOrNull(0)?.toIntOrNull()?.let { w ->
                database.appMetaQueries.upsert(META_WINDOW_W, w.toString()).sync()
            }
            lines.getOrNull(1)?.toIntOrNull()?.let { h ->
                database.appMetaQueries.upsert(META_WINDOW_H, h.toString()).sync()
            }
            f.delete()
        }
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

    fun listChapters(): List<ChapterInfo> = withDb {
        chapterQueries.selectAllForList().executeAsList().map { row ->
            ChapterInfo(
                id = row.id,
                name = row.name,
                markupDone = row.markup_done != 0L,
                voiceDone = row.voice_done != 0L,
                exported = row.exported != 0L,
            )
        }
    }

    fun setChapterMarkupDone(id: String, done: Boolean) {
        withDb {
            chapterQueries.updateMarkupDone(if (done) 1L else 0L, id).sync()
        }
    }

    fun setChapterVoiceDone(id: String, done: Boolean) {
        withDb {
            chapterQueries.updateVoiceDone(if (done) 1L else 0L, id).sync()
        }
    }

    fun setChapterExported(id: String, exported: Boolean) {
        withDb {
            chapterQueries.updateExported(if (exported) 1L else 0L, id).sync()
        }
    }

    fun createChapter(name: String): String {
        val id = newChapterId()
        withDb {
            chapterQueries.insertFull(id, name, "", "", null, 0L, 0L, 0L).sync()
        }
        return id
    }

    fun deleteChapter(id: String) {
        withDb {
            chapterQueries.deleteById(id).sync()
        }
        clearChapterCache(id)
        if (currentChapterId == id) {
            val remaining = listChapters()
            currentChapterId = remaining.firstOrNull()?.id
        }
    }

    fun renameChapter(id: String, name: String) {
        withDb {
            chapterQueries.updateName(name, id).sync()
        }
    }

    var currentChapterId: String?
        get() = metaGet(META_CURRENT_CHAPTER)?.ifBlank { null }
        set(value) {
            if (value != null) metaSet(META_CURRENT_CHAPTER, value) else metaDelete(META_CURRENT_CHAPTER)
        }

    var currentBookName: String
        get() = metaGet(META_CURRENT_BOOK)?.trim().orEmpty()
        set(bookValue) {
            if (bookValue.isNotBlank()) metaSet(META_CURRENT_BOOK, bookValue) else metaDelete(META_CURRENT_BOOK)
        }

    fun ensureCurrentChapter(): String {
        val cur = currentChapterId
        if (cur != null && withDb { chapterQueries.selectById(cur).executeAsOneOrNull() } != null) {
            return cur
        }
        val chapters = listChapters()
        val id = if (chapters.isNotEmpty()) {
            chapters.first().id
        } else {
            createChapter("Глава 1")
        }
        currentChapterId = id
        return id
    }

    fun getChapterText(id: String): String =
        withDb { chapterQueries.selectById(id).executeAsOneOrNull()?.text }.orEmpty()

    fun setChapterText(id: String, text: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull()
            if (row != null && row.text != text && row.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            chapterQueries.updateText(text, id).sync()
        }
    }

    fun getOriginalText(id: String): String =
        withDb { chapterQueries.selectById(id).executeAsOneOrNull()?.original_text }.orEmpty()

    fun setOriginalText(id: String, text: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull()
            if (row != null && row.original_text != text && row.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            chapterQueries.updateOriginalText(text, id).sync()
        }
    }

    var voiceMapping: Map<String, VoiceSettings>
        get() = withDb {
            voiceMappingQueries.selectAll().executeAsList().associate { row ->
                row.speaker_name to VoiceSettings(row.voice, row.role, row.speed, row.pitch_shift)
            }
        }
        set(value) {
            withDb {
                voiceMappingQueries.deleteAll().sync()
                for ((name, s) in value) {
                    voiceMappingQueries.insertRow(name, s.voice, s.role, s.speed, s.pitchShift).sync()
                }
            }
        }

    fun getChapterAudioPath(id: String): String? =
        withDb {
            chapterQueries.selectById(id).executeAsOneOrNull()?.audio_path?.trim()?.ifBlank { null }
        }

    fun setChapterAudioPath(id: String, path: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull()
            val newPath = path.trim()
            val old = row?.audio_path?.trim().orEmpty()
            if (old != newPath && row?.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            chapterQueries.updateAudioPath(newPath.ifBlank { null }, id).sync()
        }
    }

    fun clearChapterAudioPath(id: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull()
            if (row?.audio_path != null && row.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            chapterQueries.clearAudioPath(id).sync()
        }
    }

    fun getChapterCacheDir(id: String): File =
        File(System.getProperty("user.home"), "SpeechHelper/cache/$id").apply { mkdirs() }

    fun clearChapterCache(id: String) {
        val cacheDir = File(System.getProperty("user.home"), "SpeechHelper/cache/$id")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    var windowWidth: Int
        get() = metaGet(META_WINDOW_W)?.toIntOrNull() ?: 800
        set(value) {
            metaSet(META_WINDOW_W, value.toString())
        }

    var windowHeight: Int
        get() = metaGet(META_WINDOW_H)?.toIntOrNull() ?: 600
        set(value) {
            metaSet(META_WINDOW_H, value.toString())
        }

    fun saveWindowSize(width: Int, height: Int) {
        metaSet(META_WINDOW_W, width.toString())
        metaSet(META_WINDOW_H, height.toString())
    }

    fun listBooks(): List<String> =
        booksDir.listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            .orEmpty()

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

        val newMapping = mutableMapOf<String, VoiceSettings>()
        data class ChapterData(val name: String, val text: String, val originalText: String)
        val newChapters = mutableListOf<ChapterData>()
        var i = 1
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

        clearAllData()

        var firstId: String? = null
        for (chapter in newChapters) {
            val id = createChapter(chapter.name)
            setChapterText(id, chapter.text)
            if (chapter.originalText.isNotBlank()) {
                setOriginalText(id, chapter.originalText)
            }
            if (firstId == null) firstId = id
        }

        voiceMapping = newMapping

        if (firstId != null) {
            currentChapterId = firstId
        }

        return true
    }

    fun deleteBook(bookName: String) {
        File(booksDir, "$bookName.txt").delete()
    }

    fun clearAllData() {
        withDb {
            chapterQueries.deleteAll().sync()
            voiceMappingQueries.deleteAll().sync()
            appMetaQueries.deleteAll().sync()
        }
        val cacheRoot = File(System.getProperty("user.home"), "SpeechHelper/cache")
        if (cacheRoot.exists()) cacheRoot.deleteRecursively()
    }
}
