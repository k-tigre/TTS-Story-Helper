package by.tigre.speechhelper.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed class MarkupResult {
    data class InProgress(val message: String) : MarkupResult()
    data class Done(val text: String) : MarkupResult()
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatResponse(
    val choices: List<Choice>,
)

@Serializable
private data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

object AiMarkupApi {
    private const val ENDPOINT = "https://ai.api.cloud.yandex.net/v1/chat/completions"
    private const val CHUNK_LIMIT = 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 240_000
        }
        install(ContentNegotiation) {
            json(this@AiMarkupApi.json)
        }
    }

    private val BASE_SYSTEM_PROMPT = """
Нужно для озвучки через Yandex SpeechKit модифицировать текст, добавить акценты, разбить на голоса, используем TTS-разметка текста, паузы дополнительно если нужно указываем как <[small]>. Допустимые значения: tiny, small, medium, large, huge

Пример выделение голоса: "[voice_actor]Говорит профессор[/voice_actor], обычный голос, [голос2_нежный]Говорит леди[/голос2_нежный]" - нужно поставить начало и конец голоса - это важно! В конце голоса не забывай закрывающий тэг ставить.
Голосам можно добавлять эмоциональные оттенки, при необходимости добавлять еще голоса, помечай новый голоса мужской или женский. 
Рассказчик всегда один голос, он без эмоциональных оттенков.
Другие голоса добавляй только для диалогов.
Обязательно закрывай тэг голоса. Паузы оставляй только между открытым тэгом голоса и закрытым тегом голоса.

ВАЖНО: Пример как обрабатывать диалоги:
— Это система подачи, — объяснил парень, видя растерянное лицо Тёмы. — Она слепая как крот. Если зазеваешься — прищемит так, что мало не покажется. Ты тот самый Серебряков?

должно получится в таком виде:
[voice_actor]
— Это система подачи, —  
[/voice_actor]
[voice_main]
объяснил парень, видя растерянное лицо Тёмы.
[/voice_main]
[voice_actor]
Она слепая как крот. Если зазеваешься — прищемит так, что мало не покажется. Ты тот самый Серебряков?
[/voice_actor]

ВАЖНО: проверь диалоги

ВАЖНО: Нельзя менять содержание текста!Верни ТОЛЬКО размеченный текст, без пояснений.
"""

    private fun buildSystemPrompt(existingVoices: Set<String>): String {
        if (existingVoices.isEmpty()) return BASE_SYSTEM_PROMPT
        val voicesList = existingVoices.joinToString(", ") { it }
        return "$BASE_SYSTEM_PROMPT\n\nВ книге уже используются следующие голоса: $voicesList. Используй эти же имена голосов для разметки, не придумывай новые без необходимости."
    }

    fun fixDialog(
        text: String,
        token: String,
        folderId: String,
    ): Flow<MarkupResult> = flow {
        val model = "gpt://$folderId/deepseek-v32/latest"
        val chunks = splitTextForAi(text)
        println("[AiFixDialog] Text split into ${chunks.size} chunk(s)")

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Исправление диалогов ${i + 1} из ${chunks.size}"))
            println("[AiFixDialog] Processing chunk ${i + 1}/${chunks.size} (${chunk.length} chars)")
            val result = sendChat(
                model = model,
                token = token,
                messages = listOf(
                    ChatMessage(role = "system", content = DIALOG_FIX_PROMPT),
                    ChatMessage(role = "user", content = chunk),
                ),
            )
            results.add(fixMalformedTags(result.replace("```", "")))
        }
        println("[AiFixDialog] All chunks processed")
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    fun autoMarkup(
        text: String,
        token: String,
        folderId: String,
        existingVoices: Set<String> = emptySet(),
    ): Flow<MarkupResult> = flow {
        val systemPrompt = buildSystemPrompt(existingVoices)
        val chunks = splitTextForAi(text)
        println("[AiMarkup] Text split into ${chunks.size} chunk(s), existingVoices=$existingVoices")

        if (chunks.size == 1) {
            emit(MarkupResult.InProgress("Авто-разметка..."))
            val result = requestMarkup(chunks[0], token, folderId, systemPrompt)
            emit(MarkupResult.Done(result))
            return@flow
        }

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Авто-разметка ${i + 1} из ${chunks.size}"))
            println("[AiMarkup] Processing chunk ${i + 1}/${chunks.size} (${chunk.length} chars)")
            val result = requestMarkup(chunk, token, folderId, systemPrompt)
            results.add(result)
        }
        println("[AiMarkup] All chunks processed")
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    private val DIALOG_FIX_PROMPT = """
Проверь размеченный текст. Убедись, что в диалогах слова автора (например "сказал он", "ответила она", "пробормотал он, отступая") вынесены в голос рассказчика, а не произносятся голосом персонажа.

Пример НЕПРАВИЛЬНОЙ разметки:
[voice_actor]
— Я не понимаю, о чём вы, — пробормотал он, отступая на шаг.
[/voice_actor]

Пример ПРАВИЛЬНОЙ разметки:
[voice_actor]
— Я не понимаю, о чём вы, —
[/voice_actor]
[voice_main]
пробормотал он, отступая на шаг.
[/voice_main]

Исправь все такие места. Верни ТОЛЬКО исправленный размеченный текст, без пояснений.
""".trimIndent()

    private suspend fun requestMarkup(
        text: String,
        token: String,
        folderId: String,
        systemPrompt: String,
    ): String {
        val model = "gpt://$folderId/deepseek-v32/latest"

        // Первый проход — основная разметка
        println("[AiMarkup] Pass 1: main markup (${text.length} chars)...")
        val firstResult = sendChat(
            model = model,
            token = token,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = text),
            ),
        )
        println("[AiMarkup] Pass 1 done (${firstResult.length} chars)")

        // TODO: Второй проход временно отключён
//        // Второй проход — исправление диалогов
//        println("[AiMarkup] Проход 2: исправление диалогов...")
//        val finalResult = sendChat(
//            model = model,
//            token = token,
//            messages = listOf(
//                ChatMessage(role = "system", content = systemPrompt),
//                ChatMessage(role = "user", content = text),
//                ChatMessage(role = "assistant", content = firstResult),
//                ChatMessage(role = "user", content = DIALOG_FIX_PROMPT),
//            ),
//        )
//        println("[AiMarkup] Проход 2 завершён (${finalResult.length} символов)")
//        return finalResult.replace("```", "")

        return fixMalformedTags(firstResult.replace("```", ""))
    }

    /**
     * AI иногда возвращает теги в формате <voice_name> вместо [voice_name].
     * Заменяем угловые скобки на квадратные для тегов голосов.
     */
    private fun fixMalformedTags(text: String): String {
        return text
            .replace(Regex("""</([\wа-яА-ЯёЁ_]+)>""")) { "[/${it.groupValues[1]}]" }
            .replace(Regex("""<([\wа-яА-ЯёЁ_]+)>""")) { match ->
                val name = match.groupValues[1]
                // Не трогаем паузы вида <[small]> и HTML-подобные теги
                if (name in setOf("tiny", "small", "medium", "large", "huge")) {
                    match.value
                } else {
                    "[$name]"
                }
            }
    }

    private suspend fun sendChat(
        model: String,
        token: String,
        messages: List<ChatMessage>,
    ): String {
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = 0.5,
        )

        println("[AiMarkup] -> POST $ENDPOINT (model=$model, messages=${messages.size}, totalChars=${messages.sumOf { it.content.length }})")

        val response = client.post(ENDPOINT) {
            header(HttpHeaders.Authorization, "Api-Key $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        println("[AiMarkup] <- HTTP ${response.status.value}")

        if (response.status != HttpStatusCode.OK) {
            val responseBody = response.bodyAsText()
            println("[AiMarkup] ERROR: $responseBody")
            throw AiMarkupException("AI API error ${response.status.value}: $responseBody")
        }

        val responseText = response.bodyAsText()
        val chatResponse = json.decodeFromString<ChatResponse>(responseText)
        val content = chatResponse.choices.firstOrNull()?.message?.content
            ?: throw AiMarkupException("No content in AI response")
        println("[AiMarkup] <- Received ${content.length} chars")
        return content
    }

    internal fun splitTextForAi(text: String): List<String> {
        if (text.length <= CHUNK_LIMIT) return listOf(text)

        val chunks = mutableListOf<String>()
        val paragraphs = text.split(Regex("""\n\s*\n"""))
        val current = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.length > CHUNK_LIMIT) {
                if (current.isNotBlank()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                val sentences = paragraph.split(Regex("""(?<=[.!?])\s+"""))
                for (sentence in sentences) {
                    if (current.length + sentence.length + 1 > CHUNK_LIMIT && current.isNotBlank()) {
                        chunks.add(current.toString().trim())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(sentence)
                }
            } else if (current.length + paragraph.length + 2 > CHUNK_LIMIT) {
                chunks.add(current.toString().trim())
                current.clear()
                current.append(paragraph)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
        }

        if (current.isNotBlank()) {
            chunks.add(current.toString().trim())
        }

        return chunks.ifEmpty { listOf(text) }
    }
}

class AiMarkupException(message: String) : Exception(message)
