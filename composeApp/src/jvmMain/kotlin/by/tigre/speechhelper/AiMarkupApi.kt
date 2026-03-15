package by.tigre.speechhelper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    private const val CHUNK_LIMIT = 2000

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 120_000
        }
        install(ContentNegotiation) {
            json(this@AiMarkupApi.json)
        }
    }

    private const val SYSTEM_PROMPT = """
Нужно для озвучки через Yandex SpeechKit модифицировать текст, добавить акценты, разбить на голоса, используем TTS-разметка текста, паузы дополнительно если нужно указываем как <[small]>. Допустимые значения: tiny, small, medium, large, huge

Пример выделение голоса: "[voice_actor]Говорит профессор[/voice_actor], обычный голос, [голос2_нежный]Говорит леди[/голос2_нежный]" - нужно поставить начало и конец голоса - это важно! В конце голоса не забывай закрывающий тэг ставить.
голосам можно добавлять эмоциональные оттенки, при необходимости добавлять еще голоса. Расскзчик всегда один голос, он без эмоциональных отттенков.
Другие голоса добавляй только для диалогов.
Обязательно закрывай тэг голоса. Паузы оставляй внутри голоса.

Можно опускать в диалогах указание кто говорит, например "— В папу? — переспросил он." - тут можно убрать "переспросил он", так как понятно по голосу будет кто сказал, но там где больше информации - нужно озвучивать голосом "рассказчика"

Пример как обрабатывать диалоги
вот пример
не правильно, тут голосом тёмы говорится не только его реплика
[voice_actor]
— Я… я не понимаю, о чём вы, — пробормотал он, отступая на шаг.
[/voice_actor]

вот так правильный вариант будет
[voice_actor]
— Я… я не понимаю, о чём вы, — 
[/voice_actor]
[voice_рассказчик]
пробормотал он, отступая на шаг.
[/voice_рассказчик]

ВАЖНО: Нельзя менять содержание текста!Верни ТОЛЬКО размеченный текст, без пояснений.
"""

    suspend fun autoMarkup(
        text: String,
        token: String,
        folderId: String,
        systemPrompt: String = SYSTEM_PROMPT,
    ): String {
        val chunks = splitTextForAi(text)
        if (chunks.size == 1) {
            return requestMarkup(chunks[0], token, folderId, systemPrompt)
        }

        val results = mutableListOf<String>()
        for (chunk in chunks) {
            val result = requestMarkup(chunk, token, folderId, systemPrompt)
            results.add(result)
        }
        return results.joinToString("\n")
    }

    private suspend fun requestMarkup(
        text: String,
        token: String,
        folderId: String,
        systemPrompt: String,
    ): String {
        val request = ChatRequest(
            model = "gpt://$folderId/yandexgpt/latest",
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = text),
            ),
        )

        val response = client.post(ENDPOINT) {
            header(HttpHeaders.Authorization, "Api-Key $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (response.status != HttpStatusCode.OK) {
            val responseBody = response.bodyAsText()
            throw AiMarkupException("AI API error ${response.status.value}: $responseBody")
        }

        val responseText = response.bodyAsText()
        val chatResponse = json.decodeFromString<ChatResponse>(responseText)
        return chatResponse.choices.firstOrNull()?.message?.content
            ?: throw AiMarkupException("No content in AI response")
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
