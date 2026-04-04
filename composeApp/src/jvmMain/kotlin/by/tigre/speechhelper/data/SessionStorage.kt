package by.tigre.speechhelper.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import by.tigre.speechhelper.domain.ChapterInfo
import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.TextParser
import by.tigre.speechhelper.domain.VoiceSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import tigre.speechhelper.db.SpeechHelperDatabase

data class StoredChapterParagraph(
    val ordinal: Int,
    val originalText: String,
    val markedText: String,
)

data class BookListEntry(
    val id: String,
    val title: String,
)

/** Снимок для первичной гидратации UI вне главного (AWT) потока. */
data class InitialSessionSnapshot(
    val chapters: List<ChapterInfo>,
    val currentChapterId: String,
    val currentBookTitle: String,
    val chapterText: String,
    val originalText: String,
    val chapterAudioPath: String?,
    val voiceMapping: Map<String, VoiceSettings>,
    val synthesisBackend: SynthesisBackend,
    val localTtsSettings: LocalTtsSettings,
)

/** Один проход БД при смене главы: метаданные + текст + абзацы для валидации. */
data class ChapterContentSnapshot(
    val markedJoined: String,
    val originalJoined: String,
    val audioPath: String?,
    val originalParagraphs: List<String>,
)

/** Результат атомарного импорта: состояние для UI без лишних чтений. */
data class ImportApplyResult(
    val firstChapterId: String,
    val initialEditor: ChapterContentSnapshot,
    val chapters: List<ChapterInfo>,
    val bookTitle: String,
)

object SessionStorage {
    private val utf8 = StandardCharsets.UTF_8

    private const val DB_THREAD_NAME = "speechhelper-db"

    /** Один поток обслуживает SQLite (один JDBC-коннект). Читать/писать через [withDb] или `withContext(databaseDispatcher)`. */
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, DB_THREAD_NAME).apply { isDaemon = true }
    }

    val databaseDispatcher: CoroutineDispatcher = dbExecutor.asCoroutineDispatcher()

    /**
     * Открывает БД и выполняет миграции на фоновом потоке SQLite (не на AWT).
     * Вызывать из [main] до [androidx.compose.ui.window.application].
     */
    fun warmUp() {
        withDb { }
    }

    suspend fun loadInitialSnapshot(): InitialSessionSnapshot = withContext(databaseDispatcher) {
        val cid = ensureCurrentChapter()
        InitialSessionSnapshot(
            chapters = listChapters(),
            currentChapterId = cid,
            currentBookTitle = currentBookTitle(),
            chapterText = getChapterText(cid),
            originalText = getOriginalText(cid),
            chapterAudioPath = getChapterAudioPath(cid),
            voiceMapping = voiceMapping,
            synthesisBackend = synthesisBackend,
            localTtsSettings = localTtsSettings,
        )
    }

    suspend fun listBooksSuspend(): List<BookListEntry> = withContext(databaseDispatcher) {
        listBooks()
    }

    private const val META_CURRENT_CHAPTER = "current_chapter_id"
    private const val META_CURRENT_BOOK_ID = "current_book_id"
    /** Старый ключ: отображаемое имя; после миграции не используется. */
    private const val META_CURRENT_BOOK_LEGACY = "current_book_name"
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

    private class Holder(
        val database: SpeechHelperDatabase,
    )

    @Volatile
    private var holder: Holder? = null

    private fun holderOrCreate(): Holder {
        holder?.let { return it }
        synchronized(this) {
            holder?.let { return it }
            val h = createHolder()
            holder = h
            return h
        }
    }

    private fun createHolder(): Holder {
        val dbFile = File(dir, "speechhelper.db")
        val pathForUrl = dbFile.absoluteFile.normalize().invariantSeparatorsPath
        val jdbcUrl = "jdbc:sqlite:$pathForUrl"
        // Не передавать сюда `encoding` и т.п.: SQLite JDBC разбирает Properties как SQLiteConfig и
        // значения вроде UTF-8 ломают prepare при открытии соединения.
        val driver = JdbcSqliteDriver(url = jdbcUrl)
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        if (isNew) {
            SpeechHelperDatabase.Schema.create(driver).sync()
        } else {
            maybeMigrateBlobChapterToParagraphRows(driver)
        }
        applyPragmas(driver)
        migrateBookScopeIfNeeded(driver)
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

    private fun isDbWorkerThread(): Boolean = Thread.currentThread().name == DB_THREAD_NAME

    private fun <T> runOnDbThread(body: () -> T): T {
        if (isDbWorkerThread()) return body()
        return dbExecutor.submit(body).get()
    }

    private inline fun <T> withDb(crossinline block: SpeechHelperDatabase.() -> T): T =
        runOnDbThread {
            holderOrCreate().database.block()
        }

    private fun SpeechHelperDatabase.joinedMarked(chapterId: String): String {
        val rows = chapterQueries.selectParagraphsForChapter(chapterId).executeAsList()
        return TextParser.joinParagraphsForStorage(rows.map { it.marked_text })
    }

    private fun SpeechHelperDatabase.joinedOriginal(chapterId: String): String {
        val rows = chapterQueries.selectParagraphsForChapter(chapterId).executeAsList()
        return TextParser.joinParagraphsForStorage(rows.map { it.original_text })
    }

    private fun SpeechHelperDatabase.paragraphPairs(chapterId: String): List<Pair<String, String>> =
        chapterQueries.selectParagraphsForChapter(chapterId).executeAsList().map { it.original_text to it.marked_text }

    private fun alignedParagraphPairs(originalJoined: String, markedJoined: String): List<Pair<String, String>> {
        val o = TextParser.splitParagraphsForStorage(originalJoined)
        val m = TextParser.splitParagraphsForStorage(markedJoined)
        if (o.isEmpty() && m.isEmpty()) return emptyList()
        val n = maxOf(o.size, m.size)
        return List(n) { i -> o.getOrElse(i) { "" } to m.getOrElse(i) { "" } }
    }

    private fun SpeechHelperDatabase.replaceChapterParagraphs(chapterId: String, pairs: List<Pair<String, String>>) {
        chapterQueries.deleteParagraphsForChapter(chapterId).sync()
        for ((i, p) in pairs.withIndex()) {
            chapterQueries.insertParagraph(chapterId, i.toLong(), p.first, p.second).sync()
        }
    }

    private fun SpeechHelperDatabase.snapshotChapterContent(chapterId: String): ChapterContentSnapshot {
        val rows = chapterQueries.selectParagraphsForChapter(chapterId).executeAsList()
            .sortedBy { it.ordinal }
        val origParas = rows.map { it.original_text }
        val markedJoined = TextParser.joinParagraphsForStorage(rows.map { it.marked_text })
        val originalJoined = TextParser.joinParagraphsForStorage(origParas)
        val audioPath = chapterQueries.selectById(chapterId).executeAsOneOrNull()?.audio_path?.trim()?.ifBlank { null }
        return ChapterContentSnapshot(markedJoined, originalJoined, audioPath, origParas)
    }

    /** Сохраняет размеченный текст только если отличается от БД (без лишних INSERT paragraph). */
    private fun SpeechHelperDatabase.persistMarkedChapterTextIfChanged(chapterId: String, text: String) {
        if (chapterId.isBlank()) return
        val row = chapterQueries.selectById(chapterId).executeAsOneOrNull() ?: return
        val rows = chapterQueries.selectParagraphsForChapter(chapterId).executeAsList()
            .sortedBy { it.ordinal }
        val oldMarked = TextParser.joinParagraphsForStorage(rows.map { it.marked_text })
        if (oldMarked == text) return
        if (row.exported != 0L) {
            chapterQueries.invalidateExportIfExported(chapterId).sync()
        }
        val newMarked = TextParser.splitParagraphsForStorage(text)
        val o = rows.map { it.original_text }
        if (newMarked.isEmpty() && o.isEmpty()) {
            chapterQueries.deleteParagraphsForChapter(chapterId).sync()
            return
        }
        val n = maxOf(o.size, newMarked.size)
        val pairs = List(n) { i -> o.getOrElse(i) { "" } to newMarked.getOrElse(i) { "" } }
        replaceChapterParagraphs(chapterId, pairs)
        bookQueries.touchUpdatedAt(System.currentTimeMillis(), row.book_id).sync()
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

    private fun newBookId(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) + "_b"

    private fun readLegacyWorkflow(f: File): Triple<Long, Long, Long> {
        if (!f.exists()) return Triple(0L, 0L, 0L)
        var markupDone = 0L
        var voiceDone = 0L
        var exported = 0L
        for (line in f.readLines(utf8)) {
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

    private fun parseVoiceLinesToDb(database: SpeechHelperDatabase, bookId: String, lines: Iterable<String>) {
        for (line in lines) {
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val name = parts[0]
            val fields = parts[1].split("|")
            val voice = fields.getOrElse(0) { "dasha" }
            val role = fields.getOrElse(1) { "" }
            val speed = fields.getOrElse(2) { "1.0" }.toDoubleOrNull() ?: 1.0
            val pitchShift = fields.getOrElse(3) { "0.0" }.toDoubleOrNull() ?: 0.0
            database.voiceMappingQueries.insertRow(bookId, name, voice, role, speed, pitchShift).sync()
        }
    }

    // ── SQLite v1 (chapter.text / chapter.original_text blobs) → paragraph rows ─────────────

    private data class LegacyChapterBlob(
        val id: String,
        val name: String,
        val markedBlob: String,
        val originalBlob: String,
        val audioPath: String?,
        val markupDone: Long,
        val voiceDone: Long,
        val exported: Long,
    )

    private data class VoiceRow(
        val speaker: String,
        val voice: String,
        val role: String,
        val speed: Double,
        val pitch: Double,
    )

    private data class SavedBookBlobRow(
        val name: String,
        val content: String,
        val updatedAt: Long,
    )

    private fun tableExists(driver: SqlDriver, tableName: String): Boolean {
        val qr = driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            { cursor ->
                QueryResult.Value(cursor.next().value)
            },
            1,
        ) { bindString(0, tableName) }
        return (qr as QueryResult.Value<Boolean>).value
    }

    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
        val qr = driver.executeQuery(
            null,
            "SELECT 1 FROM pragma_table_info(?) WHERE name = ? LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            2,
        ) {
            bindString(0, table)
            bindString(1, column)
        }
        return (qr as QueryResult.Value<Boolean>).value
    }

    private fun firstBookIdFromDriver(driver: SqlDriver): String? {
        if (!tableExists(driver, "book")) return null
        val qr = driver.executeQuery(
            null,
            "SELECT id FROM book LIMIT 1",
            { cursor ->
                val ok = cursor.next().value
                QueryResult.Value(if (ok) cursor.getString(0) else null)
            },
            0,
            null,
        )
        return (qr as QueryResult.Value<String?>).value
    }

    private fun loadSavedBookBlobRows(driver: SqlDriver): List<SavedBookBlobRow> {
        if (!tableExists(driver, "saved_book")) return emptyList()
        val qr = driver.executeQuery(
            null,
            "SELECT name, content, updated_at FROM saved_book",
            { cursor ->
                val list = mutableListOf<SavedBookBlobRow>()
                while (cursor.next().value) {
                    list.add(
                        SavedBookBlobRow(
                            name = cursor.getString(0)!!,
                            content = cursor.getString(1)!!,
                            updatedAt = cursor.getLong(2)!!,
                        ),
                    )
                }
                QueryResult.Value(list)
            },
            0,
            null,
        )
        return (qr as QueryResult.Value<List<SavedBookBlobRow>>).value
    }

    private fun chapterTableHasTextColumn(driver: SqlDriver): Boolean {
        val qr = driver.executeQuery(
            null,
            "SELECT 1 FROM pragma_table_info('chapter') WHERE name='text' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
            null,
        )
        return (qr as QueryResult.Value<Boolean>).value
    }

    private fun dropAllSpeechHelperTables(driver: SqlDriver) {
        driver.execute(null, "DROP TABLE IF EXISTS paragraph", 0, null).sync()
        driver.execute(null, "DROP TABLE IF EXISTS saved_book", 0, null).sync()
        driver.execute(null, "DROP TABLE IF EXISTS voice_mapping", 0, null).sync()
        driver.execute(null, "DROP TABLE IF EXISTS chapter", 0, null).sync()
        driver.execute(null, "DROP TABLE IF EXISTS book", 0, null).sync()
        driver.execute(null, "DROP TABLE IF EXISTS app_meta", 0, null).sync()
    }

    private fun loadLegacyChapterBlobs(driver: SqlDriver): List<LegacyChapterBlob> {
        val qr = driver.executeQuery(
            null,
            """
            SELECT id, name, text, original_text, audio_path, markup_done, voice_done, exported
            FROM chapter
            """.trimIndent(),
            { cursor ->
                val list = mutableListOf<LegacyChapterBlob>()
                while (cursor.next().value) {
                    list.add(
                        LegacyChapterBlob(
                            id = cursor.getString(0)!!,
                            name = cursor.getString(1)!!,
                            markedBlob = cursor.getString(2)!!,
                            originalBlob = cursor.getString(3)!!,
                            audioPath = cursor.getString(4),
                            markupDone = cursor.getLong(5)!!,
                            voiceDone = cursor.getLong(6)!!,
                            exported = cursor.getLong(7)!!,
                        ),
                    )
                }
                QueryResult.Value(list)
            },
            0,
            null,
        )
        return (qr as QueryResult.Value<List<LegacyChapterBlob>>).value
    }

    private fun loadAllMeta(driver: SqlDriver): List<Pair<String, String>> {
        val qr = driver.executeQuery(
            null,
            "SELECT key, value FROM app_meta",
            { cursor ->
                val list = mutableListOf<Pair<String, String>>()
                while (cursor.next().value) {
                    list.add(cursor.getString(0)!! to cursor.getString(1)!!)
                }
                QueryResult.Value(list)
            },
            0,
            null,
        )
        return (qr as QueryResult.Value<List<Pair<String, String>>>).value
    }

    private fun loadAllVoices(driver: SqlDriver): List<VoiceRow> {
        val qr = driver.executeQuery(
            null,
            "SELECT speaker_name, voice, role, speed, pitch_shift FROM voice_mapping",
            { cursor ->
                val list = mutableListOf<VoiceRow>()
                while (cursor.next().value) {
                    list.add(
                        VoiceRow(
                            speaker = cursor.getString(0)!!,
                            voice = cursor.getString(1)!!,
                            role = cursor.getString(2)!!,
                            speed = cursor.getDouble(3)!!,
                            pitch = cursor.getDouble(4)!!,
                        ),
                    )
                }
                QueryResult.Value(list)
            },
            0,
            null,
        )
        return (qr as QueryResult.Value<List<VoiceRow>>).value
    }

    private fun  maybeMigrateBlobChapterToParagraphRows(driver: SqlDriver) {
        if (!tableExists(driver, "chapter")) {
            SpeechHelperDatabase.Schema.create(driver).sync()
            return
        }
        if (!chapterTableHasTextColumn(driver)) {
            if (!tableExists(driver, "paragraph")) {
                SpeechHelperDatabase.Schema.create(driver).sync()
            }
            return
        }
        val legacy = loadLegacyChapterBlobs(driver)
        val meta = loadAllMeta(driver)
        val voices = loadAllVoices(driver)
        dropAllSpeechHelperTables(driver)
        SpeechHelperDatabase.Schema.create(driver).sync()
        val db = SpeechHelperDatabase(driver)
        val defaultBookId = newBookId()
        db.bookQueries.insertBook(defaultBookId, "Без названия", System.currentTimeMillis()).sync()
        for ((k, v) in meta) {
            if (k == META_CURRENT_BOOK_LEGACY) continue
            db.appMetaQueries.upsert(k, v).sync()
        }
        for (row in voices) {
            db.voiceMappingQueries.insertRow(defaultBookId, row.speaker, row.voice, row.role, row.speed, row.pitch).sync()
        }
        for (ch in legacy) {
            db.chapterQueries.insertChapter(
                ch.id,
                defaultBookId,
                ch.name,
                ch.audioPath,
                ch.markupDone,
                ch.voiceDone,
                ch.exported,
            ).sync()
            val pairs = alignedParagraphPairs(ch.originalBlob, ch.markedBlob)
            for ((i, p) in pairs.withIndex()) {
                db.chapterQueries.insertParagraph(ch.id, i.toLong(), p.first, p.second).sync()
            }
        }
        val oldName = meta.firstOrNull { it.first == META_CURRENT_BOOK_LEGACY }?.second?.trim().orEmpty()
        if (oldName.isNotBlank()) {
            db.bookQueries.updateTitleAndTime(oldName, System.currentTimeMillis(), defaultBookId).sync()
        }
        db.appMetaQueries.upsert(META_CURRENT_BOOK_ID, defaultBookId).sync()
    }

    private fun migrateBookScopeIfNeeded(driver: SqlDriver) {
        if (!tableExists(driver, "book")) {
            driver.execute(
                null,
                """
                CREATE TABLE book (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
                null,
            ).sync()
        }

        if (!columnExists(driver, "chapter", "book_id")) {
            val defaultBookId = newBookId()
            val now = System.currentTimeMillis()
            driver.execute(
                null,
                "INSERT INTO book(id, title, updated_at) VALUES(?,?,?)",
                3,
            ) {
                bindString(0, defaultBookId)
                bindString(1, "Без названия")
                bindLong(2, now)
            }.sync()
            driver.execute(null, "ALTER TABLE chapter ADD COLUMN book_id TEXT", 0, null).sync()
            driver.execute(
                null,
                "UPDATE chapter SET book_id = ? WHERE book_id IS NULL",
                1,
            ) { bindString(0, defaultBookId) }.sync()
        }

        if (tableExists(driver, "voice_mapping") && !columnExists(driver, "voice_mapping", "book_id")) {
            val bookId = firstBookIdFromDriver(driver) ?: return
            driver.execute(
                null,
                """
                CREATE TABLE voice_mapping_new (
                    book_id TEXT NOT NULL,
                    speaker_name TEXT NOT NULL,
                    voice TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT '',
                    speed REAL NOT NULL DEFAULT 1.0,
                    pitch_shift REAL NOT NULL DEFAULT 0.0,
                    PRIMARY KEY (book_id, speaker_name)
                )
                """.trimIndent(),
                0,
                null,
            ).sync()
            driver.execute(
                null,
                """
                INSERT INTO voice_mapping_new(book_id, speaker_name, voice, role, speed, pitch_shift)
                SELECT ?, speaker_name, voice, role, speed, pitch_shift FROM voice_mapping
                """.trimIndent(),
                1,
            ) { bindString(0, bookId) }.sync()
            driver.execute(null, "DROP TABLE voice_mapping", 0, null).sync()
            driver.execute(null, "ALTER TABLE voice_mapping_new RENAME TO voice_mapping", 0, null).sync()
        }

        if (tableExists(driver, "saved_book")) {
            val db = SpeechHelperDatabase(driver)
            for (row in loadSavedBookBlobRows(driver)) {
                if (!row.content.startsWith("##BOOK_FORMAT_V1##")) continue
                val bookId = newBookId()
                db.bookQueries.insertBook(bookId, row.name, row.updatedAt).sync()
                applyLoadedBookFormatLinesForBook(
                    db,
                    bookId,
                    row.content.lines(),
                    setCurrentChapterMeta = false,
                )
            }
            driver.execute(null, "DROP TABLE IF EXISTS saved_book", 0, null).sync()
        }

        val db = SpeechHelperDatabase(driver)
        migrateCurrentBookMetaKey(db)
        ensureMinimumBooks(db)
    }

    private fun migrateCurrentBookMetaKey(database: SpeechHelperDatabase) {
        if (database.appMetaQueries.getValue(META_CURRENT_BOOK_ID).executeAsOneOrNull() != null) {
            database.appMetaQueries.deleteKey(META_CURRENT_BOOK_LEGACY).sync()
            return
        }
        val oldTitle = database.appMetaQueries.getValue(META_CURRENT_BOOK_LEGACY).executeAsOneOrNull()?.trim().orEmpty()
        val books = database.bookQueries.selectAllOrdered().executeAsList()
        val resolved = when {
            oldTitle.isNotBlank() -> books.firstOrNull { it.title == oldTitle }?.id ?: books.firstOrNull()?.id
            else -> books.firstOrNull()?.id
        }
        if (resolved != null) {
            database.appMetaQueries.upsert(META_CURRENT_BOOK_ID, resolved).sync()
        }
        database.appMetaQueries.deleteKey(META_CURRENT_BOOK_LEGACY).sync()
    }

    private fun ensureMinimumBooks(database: SpeechHelperDatabase) {
        if (database.bookQueries.bookCount().executeAsOne() > 0L) return
        val bookId = newBookId()
        val chapterId = newChapterId()
        val now = System.currentTimeMillis()
        database.bookQueries.insertBook(bookId, "Без названия", now).sync()
        database.chapterQueries.insertChapter(chapterId, bookId, "Глава 1", null, 0L, 0L, 0L).sync()
        database.appMetaQueries.upsert(META_CURRENT_BOOK_ID, bookId).sync()
        database.appMetaQueries.upsert(META_CURRENT_CHAPTER, chapterId).sync()
    }

    private fun ensureDefaultBookRow(database: SpeechHelperDatabase): String {
        val first = database.bookQueries.selectAllOrdered().executeAsList().firstOrNull()
        if (first != null) return first.id
        val bookId = newBookId()
        database.bookQueries.insertBook(bookId, "Без названия", System.currentTimeMillis()).sync()
        return bookId
    }

    private fun finalizeBookContext(database: SpeechHelperDatabase) {
        val books = database.bookQueries.selectAllOrdered().executeAsList()
        if (books.isEmpty()) {
            ensureMinimumBooks(database)
            return
        }
        var bookId = database.appMetaQueries.getValue(META_CURRENT_BOOK_ID).executeAsOneOrNull()
        if (bookId == null || books.none { it.id == bookId }) {
            bookId = books.first().id
            database.appMetaQueries.upsert(META_CURRENT_BOOK_ID, bookId).sync()
        }
        val cid = database.appMetaQueries.getValue(META_CURRENT_CHAPTER).executeAsOneOrNull()
        val row = cid?.let { database.chapterQueries.selectById(it).executeAsOneOrNull() }
        if (row == null || row.book_id != bookId) {
            val firstCh = database.chapterQueries.firstIdForBook(bookId).executeAsOneOrNull()
            if (firstCh != null) {
                database.appMetaQueries.upsert(META_CURRENT_CHAPTER, firstCh).sync()
            }
        }
    }

    private fun migrateAncientSessionFiles(database: SpeechHelperDatabase, bookId: String) {
        val sessionText = File(dir, "session_text.txt")
        val sessionMapping = File(dir, "session_mapping.txt")
        if (!sessionText.exists() && !sessionMapping.exists()) return

        val id = newChapterId()
        val text = sessionText.takeIf { it.exists() }?.readText(utf8).orEmpty()
        databaseInsertChapterWithParagraphs(
            database,
            bookId,
            id,
            "Глава 1",
            "",
            text,
            null,
            0L,
            0L,
            0L,
        )
        if (sessionMapping.exists()) {
            parseVoiceLinesToDb(database, bookId, sessionMapping.readLines(utf8))
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

    private fun databaseInsertChapterWithParagraphs(
        database: SpeechHelperDatabase,
        bookId: String,
        id: String,
        name: String,
        originalJoined: String,
        markedJoined: String,
        audioPath: String?,
        markupDone: Long,
        voiceDone: Long,
        exported: Long,
    ) {
        database.chapterQueries.insertChapter(id, bookId, name, audioPath, markupDone, voiceDone, exported).sync()
        val pairs = alignedParagraphPairs(originalJoined, markedJoined)
        for ((i, p) in pairs.withIndex()) {
            database.chapterQueries.insertParagraph(id, i.toLong(), p.first, p.second).sync()
        }
    }

    private fun migrateChapterDirsFromDisk(database: SpeechHelperDatabase, bookId: String) {
        val dirs = chaptersDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) return
        for (d in dirs) {
            val id = d.name
            val name = File(d, "meta.txt").takeIf { it.exists() }?.readText(utf8)?.trim()?.ifBlank { null } ?: id
            val text = File(d, "text.txt").takeIf { it.exists() }?.readText(utf8).orEmpty()
            val orig = File(d, "original_text.txt").takeIf { it.exists() }?.readText(utf8).orEmpty()
            val audio = File(d, "audio_path.txt").takeIf { it.exists() }?.readText(utf8)?.trim()?.ifBlank { null }
            val (m, v, e) = readLegacyWorkflow(File(d, "workflow.txt"))
            databaseInsertChapterWithParagraphs(database, bookId, id, name, orig, text, audio, m, v, e)
            d.deleteRecursively()
        }
    }

    private fun importSynthPrefsFileIfExists(database: SpeechHelperDatabase) {
        if (!synthesisPrefsFile.exists()) return
        for (line in synthesisPrefsFile.readLines(utf8)) {
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
        val defaultBookId = ensureDefaultBookRow(database)
        val emptyDb = database.chapterQueries.chapterCount().executeAsOne() == 0L
        if (emptyDb) {
            if (dirs.isNotEmpty()) {
                migrateChapterDirsFromDisk(database, defaultBookId)
            } else {
                migrateAncientSessionFiles(database, defaultBookId)
            }
        } else if (dirs.isNotEmpty()) {
            dirs.forEach { it.deleteRecursively() }
        }

        if (mappingFile.exists()) {
            parseVoiceLinesToDb(database, defaultBookId, mappingFile.readLines(utf8))
            mappingFile.delete()
        }

        currentChapterFile.takeIf { it.exists() }?.let { f ->
            val v = f.readText(utf8).trim()
            if (v.isNotBlank()) database.appMetaQueries.upsert(META_CURRENT_CHAPTER, v).sync()
            f.delete()
        }

        currentBookFile.takeIf { it.exists() }?.let { f ->
            val v = f.readText(utf8).trim()
            if (v.isNotBlank()) {
                database.bookQueries.updateTitleAndTime(v, System.currentTimeMillis(), defaultBookId).sync()
            }
            f.delete()
        }

        importSynthPrefsFileIfExists(database)

        windowSizeFile.takeIf { it.exists() }?.let { f ->
            val lines = f.readLines(utf8)
            lines.getOrNull(0)?.toIntOrNull()?.let { w ->
                database.appMetaQueries.upsert(META_WINDOW_W, w.toString()).sync()
            }
            lines.getOrNull(1)?.toIntOrNull()?.let { h ->
                database.appMetaQueries.upsert(META_WINDOW_H, h.toString()).sync()
            }
            f.delete()
        }

        migrateLegacyBookTxtFilesFromDisk(database)
        finalizeBookContext(database)
    }

    private fun migrateLegacyBookTxtFilesFromDisk(database: SpeechHelperDatabase) {
        val files = booksDir.listFiles()?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }.orEmpty()
        for (f in files) {
            val content = try {
                f.readText(utf8)
            } catch (_: Exception) {
                continue
            }
            if (!content.startsWith("##BOOK_FORMAT_V1##")) continue
            val bookId = newBookId()
            database.bookQueries.insertBook(bookId, f.nameWithoutExtension, f.lastModified()).sync()
            applyLoadedBookFormatLinesForBook(
                database,
                bookId,
                content.lines(),
                setCurrentChapterMeta = false,
            )
            f.delete()
        }
    }

    private data class ParsedBookV1(
        val voices: Map<String, VoiceSettings>,
        val chapters: List<Triple<String, String, String>>,
    )

    private fun parseBookFormatV1Lines(lines: List<String>): ParsedBookV1? {
        if (lines.firstOrNull()?.trim() != "##BOOK_FORMAT_V1##") return null
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

        if (newChapters.isEmpty()) return null
        return ParsedBookV1(
            newMapping,
            newChapters.map { Triple(it.name, it.text, it.originalText) },
        )
    }

    private fun applyLoadedBookFormatLinesForBook(
        database: SpeechHelperDatabase,
        bookId: String,
        lines: List<String>,
        setCurrentChapterMeta: Boolean,
    ): Boolean {
        val parsed = parseBookFormatV1Lines(lines) ?: return false

        database.chapterQueries.deleteChaptersForBook(bookId).sync()
        database.voiceMappingQueries.deleteAllForBook(bookId).sync()

        var firstId: String? = null
        for ((chapterName, chapterText, chapterOriginalText) in parsed.chapters) {
            val id = newChapterId()
            database.chapterQueries.insertChapter(id, bookId, chapterName, null, 0L, 0L, 0L).sync()
            val pairs = alignedParagraphPairs(chapterOriginalText, chapterText)
            for ((ord, p) in pairs.withIndex()) {
                database.chapterQueries.insertParagraph(id, ord.toLong(), p.first, p.second).sync()
            }
            if (firstId == null) firstId = id
        }

        for ((name, s) in parsed.voices) {
            database.voiceMappingQueries.insertRow(bookId, name, s.voice, s.role, s.speed, s.pitchShift).sync()
        }

        if (setCurrentChapterMeta && firstId != null) {
            database.appMetaQueries.upsert(META_CURRENT_CHAPTER, firstId).sync()
        }
        return true
    }

    fun requireCurrentBookId(): String = withDb {
        appMetaQueries.getValue(META_CURRENT_BOOK_ID).executeAsOneOrNull()?.takeIf { bid ->
            bookQueries.selectTitle(bid).executeAsOneOrNull() != null
        }?.let { return@withDb it }

        val firstRow = bookQueries.selectAllOrdered().executeAsList().firstOrNull()
        if (firstRow != null) {
            appMetaQueries.upsert(META_CURRENT_BOOK_ID, firstRow.id).sync()
            return@withDb firstRow.id
        }
        val bid = newBookId()
        bookQueries.insertBook(bid, "Без названия", System.currentTimeMillis()).sync()
        appMetaQueries.upsert(META_CURRENT_BOOK_ID, bid).sync()
        bid
    }

    var currentBookId: String?
        get() = metaGet(META_CURRENT_BOOK_ID)?.ifBlank { null }
        set(value) {
            if (value != null) metaSet(META_CURRENT_BOOK_ID, value) else metaDelete(META_CURRENT_BOOK_ID)
        }

    /** Заголовок текущей книги (из строки book, не из метаданных-флага). */
    fun currentBookTitle(): String = withDb {
        val id = requireCurrentBookId()
        bookQueries.selectTitle(id).executeAsOneOrNull().orEmpty()
    }

    fun setCurrentBookTitle(title: String) {
        val id = requireCurrentBookId()
        val t = title.trim()
        if (t.isEmpty()) return
        withDb {
            bookQueries.updateTitleAndTime(t, System.currentTimeMillis(), id).sync()
        }
    }

    fun switchToBook(bookId: String): Boolean = withDb {
        if (bookQueries.selectTitle(bookId).executeAsOneOrNull() == null) return@withDb false
        appMetaQueries.upsert(META_CURRENT_BOOK_ID, bookId).sync()
        val first = chapterQueries.firstIdForBook(bookId).executeAsOneOrNull()
        if (first != null) {
            appMetaQueries.upsert(META_CURRENT_CHAPTER, first).sync()
        } else {
            val cid = newChapterId()
            chapterQueries.insertChapter(cid, bookId, "Глава 1", null, 0L, 0L, 0L).sync()
            appMetaQueries.upsert(META_CURRENT_CHAPTER, cid).sync()
        }
        true
    }

    fun listBooks(): List<BookListEntry> = withDb {
        bookQueries.selectAllOrdered().executeAsList().map { BookListEntry(it.id, it.title) }
    }

    /** Только обновляет название текущей книги и время (контент уже в строках chapter или paragraph). */
    fun saveBookTitle(title: String) {
        setCurrentBookTitle(title)
    }

    fun importBookFromV1TextOrNull(raw: String): Boolean {
        val lines = raw.lines()
        if (parseBookFormatV1Lines(lines) == null) return false
        val bookId = newBookId()
        withDb {
            bookQueries.insertBook(bookId, "Импорт", System.currentTimeMillis()).sync()
            applyLoadedBookFormatLinesForBook(this, bookId, lines, setCurrentChapterMeta = true)
            appMetaQueries.upsert(META_CURRENT_BOOK_ID, bookId).sync()
        }
        return true
    }

    fun loadBook(bookId: String): Boolean {
        if (!switchToBook(bookId)) return false
        return true
    }

    fun deleteBook(bookId: String) {
        val cur = currentBookId
        withDb {
            bookQueries.deleteById(bookId).sync()
        }
        File(booksDir, "$bookId.txt").delete()
        if (cur == bookId) {
            val next = withDb { bookQueries.selectAllOrdered().executeAsList().firstOrNull() }
            if (next != null) {
                switchToBook(next.id)
            } else {
                withDb {
                    val bid = newBookId()
                    val cid = newChapterId()
                    bookQueries.insertBook(bid, "Без названия", System.currentTimeMillis()).sync()
                    chapterQueries.insertChapter(cid, bid, "Глава 1", null, 0L, 0L, 0L).sync()
                    appMetaQueries.upsert(META_CURRENT_BOOK_ID, bid).sync()
                    appMetaQueries.upsert(META_CURRENT_CHAPTER, cid).sync()
                }
            }
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

    fun listChapters(): List<ChapterInfo> {
        val bid = requireCurrentBookId()
        return withDb {
            chapterQueries.selectAllForList(bid).executeAsList().map { row ->
                ChapterInfo(
                    id = row.id,
                    name = row.name,
                    markupDone = row.markup_done != 0L,
                    voiceDone = row.voice_done != 0L,
                    exported = row.exported != 0L,
                )
            }
        }
    }

    fun listChapterParagraphs(chapterId: String): List<StoredChapterParagraph> = withDb {
        chapterQueries.selectParagraphsForChapter(chapterId).executeAsList().map { r ->
            StoredChapterParagraph(r.ordinal.toInt(), r.original_text, r.marked_text)
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
        val bid = requireCurrentBookId()
        withDb {
            chapterQueries.insertChapter(id, bid, name, null, 0L, 0L, 0L).sync()
            bookQueries.touchUpdatedAt(System.currentTimeMillis(), bid).sync()
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
            val bid = chapterQueries.selectById(id).executeAsOneOrNull()?.book_id ?: return@withDb
            bookQueries.touchUpdatedAt(System.currentTimeMillis(), bid).sync()
        }
    }

    var currentChapterId: String?
        get() = metaGet(META_CURRENT_CHAPTER)?.ifBlank { null }
        set(value) {
            if (value != null) metaSet(META_CURRENT_CHAPTER, value) else metaDelete(META_CURRENT_CHAPTER)
        }

    @Deprecated("Используйте currentBookTitle() или currentBookId", ReplaceWith("currentBookTitle()"))
    var currentBookName: String
        get() = currentBookTitle()
        set(bookValue) {
            if (bookValue.isNotBlank()) setCurrentBookTitle(bookValue)
        }

    fun ensureCurrentChapter(): String {
        val bookId = requireCurrentBookId()
        val cur = currentChapterId
        if (cur != null) {
            val row = withDb { chapterQueries.selectById(cur).executeAsOneOrNull() }
            if (row != null && row.book_id == bookId) return cur
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
        withDb { joinedMarked(id) }

    fun setChapterText(id: String, text: String) {
        withDb {
            persistMarkedChapterTextIfChanged(id, text)
        }
    }

    /**
     * Атомарно: сохранить размеченный текст у [fromChapterId] (если изменился), выставить текущую главу,
     * вернуть контент [toChapterId] одним проходом (меньше round-trip к потоку БД).
     */
    fun persistSwitchChapter(fromChapterId: String, editorMarkedText: String, toChapterId: String): ChapterContentSnapshot =
        withDb {
            persistMarkedChapterTextIfChanged(fromChapterId, editorMarkedText)
            appMetaQueries.upsert(META_CURRENT_CHAPTER, toChapterId).sync()
            snapshotChapterContent(toChapterId)
        }

    /**
     * Добавляет новую книгу из импорта и переключает текущую на неё. Уже существующие книги и настройки
     * приложения в [app_meta] не трогаем. Абзацы готовые — см. [ParsedBook.preparedForStorage].
     */
    fun applyImportedBookPrepared(bookTitle: String, prepared: List<PreparedImportChapter>): ImportApplyResult {
        return withDb {
            val title = bookTitle.trim().ifBlank { "Без названия" }
            val bid = newBookId()
            val now = System.currentTimeMillis()
            bookQueries.insertBook(bid, title, now).sync()
            appMetaQueries.upsert(META_CURRENT_BOOK_ID, bid).sync()

            var firstId: String? = null
            for (pch in prepared) {
                val id = newChapterId()
                if (firstId == null) firstId = id
                chapterQueries.insertChapter(id, bid, pch.name, null, 0L, 0L, 0L).sync()
                for ((i, marked) in pch.markedParagraphs.withIndex()) {
                    chapterQueries.insertParagraph(id, i.toLong(), "", marked).sync()
                }
            }

            val fid = firstId ?: run {
                val id = newChapterId()
                chapterQueries.insertChapter(id, bid, "Глава 1", null, 0L, 0L, 0L).sync()
                id
            }
            appMetaQueries.upsert(META_CURRENT_CHAPTER, fid).sync()

            val chapterInfos = chapterQueries.selectAllForList(bid).executeAsList().map { row ->
                ChapterInfo(
                    id = row.id,
                    name = row.name,
                    markupDone = row.markup_done != 0L,
                    voiceDone = row.voice_done != 0L,
                    exported = row.exported != 0L,
                )
            }
            ImportApplyResult(
                firstChapterId = fid,
                initialEditor = snapshotChapterContent(fid),
                chapters = chapterInfos,
                bookTitle = title,
            )
        }
    }

    fun getOriginalText(id: String): String =
        withDb { joinedOriginal(id) }

    fun setOriginalText(id: String, text: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull() ?: return@withDb
            val oldOrig = joinedOriginal(id)
            if (oldOrig == text) return@withDb
            if (row.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            val newOrig = TextParser.splitParagraphsForStorage(text)
            val oldPairs = paragraphPairs(id)
            val m = oldPairs.map { it.second }
            if (newOrig.isEmpty() && m.isEmpty()) {
                chapterQueries.deleteParagraphsForChapter(id).sync()
                return@withDb
            }
            val n = maxOf(newOrig.size, m.size)
            val pairs = List(n) { i -> newOrig.getOrElse(i) { "" } to m.getOrElse(i) { "" } }
            replaceChapterParagraphs(id, pairs)
            bookQueries.touchUpdatedAt(System.currentTimeMillis(), row.book_id).sync()
        }
    }

    var voiceMapping: Map<String, VoiceSettings>
        get() {
            val bid = requireCurrentBookId()
            return withDb {
                voiceMappingQueries.selectAllForBook(bid).executeAsList().associate { row ->
                    row.speaker_name to VoiceSettings(row.voice, row.role, row.speed, row.pitch_shift)
                }
            }
        }
        set(value) {
            val bid = requireCurrentBookId()
            withDb {
                voiceMappingQueries.deleteAllForBook(bid).sync()
                for ((name, s) in value) {
                    voiceMappingQueries.insertRow(bid, name, s.voice, s.role, s.speed, s.pitchShift).sync()
                }
                bookQueries.touchUpdatedAt(System.currentTimeMillis(), bid).sync()
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
            row?.book_id?.let { bookQueries.touchUpdatedAt(System.currentTimeMillis(), it).sync() }
        }
    }

    fun clearChapterAudioPath(id: String) {
        withDb {
            val row = chapterQueries.selectById(id).executeAsOneOrNull()
            if (row?.audio_path != null && row.exported != 0L) {
                chapterQueries.invalidateExportIfExported(id).sync()
            }
            chapterQueries.clearAudioPath(id).sync()
            row?.book_id?.let { bookQueries.touchUpdatedAt(System.currentTimeMillis(), it).sync() }
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

    fun clearAllData() {
        withDb {
            chapterQueries.deleteAll().sync()
            voiceMappingQueries.deleteAll().sync()
            bookQueries.deleteAll().sync()
            appMetaQueries.deleteAll().sync()
        }
        val cacheRoot = File(System.getProperty("user.home"), "SpeechHelper/cache")
        if (cacheRoot.exists()) cacheRoot.deleteRecursively()
        withDb {
            val bid = newBookId()
            val cid = newChapterId()
            bookQueries.insertBook(bid, "Без названия", System.currentTimeMillis()).sync()
            chapterQueries.insertChapter(cid, bid, "Глава 1", null, 0L, 0L, 0L).sync()
            appMetaQueries.upsert(META_CURRENT_BOOK_ID, bid).sync()
            appMetaQueries.upsert(META_CURRENT_CHAPTER, cid).sync()
        }
    }
}
