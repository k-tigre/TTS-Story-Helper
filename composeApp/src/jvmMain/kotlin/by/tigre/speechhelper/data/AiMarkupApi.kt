package by.tigre.speechhelper.data

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    private const val CHUNK_LIMIT = 2000

    private val json = HttpClientProvider.jsonInstance
    private val client = HttpClientProvider.markupClient

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
            emit(MarkupResult.InProgress("Fixing dialogs ${i + 1} of ${chunks.size}"))
            println("[AiFixDialog] Processing chunk ${i + 1}/${chunks.size} (${chunk.length} chars)")
            val result = sendChat(
                model = model,
                token = token,
                messages = listOf(
                    ChatMessage(role = "system", content = MarkupSystemPrompts.dialogFixPrompt),
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
        val systemPrompt = MarkupSystemPrompts.autoMarkupPrompt(existingVoices)
        val chunks = splitTextForAi(text)
        println("[AiMarkup] Text split into ${chunks.size} chunk(s), existingVoices=$existingVoices")

        if (chunks.size == 1) {
            emit(MarkupResult.InProgress("Auto markup..."))
            val result = requestMarkup(chunks[0], token, folderId, systemPrompt)
            emit(MarkupResult.Done(result))
            return@flow
        }

        val results = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            emit(MarkupResult.InProgress("Auto markup ${i + 1} of ${chunks.size}"))
            println("[AiMarkup] Processing chunk ${i + 1}/${chunks.size} (${chunk.length} chars)")
            val result = requestMarkup(chunk, token, folderId, systemPrompt)
            results.add(result)
        }
        println("[AiMarkup] All chunks processed")
        emit(MarkupResult.Done(results.joinToString("\n")))
    }

    private suspend fun requestMarkup(
        text: String,
        token: String,
        folderId: String,
        systemPrompt: String,
    ): String {
        val model = "gpt://$folderId/deepseek-v32/latest"

        // First pass — main markup
        println("[AiMarkup] Pass 1: main markup (${text.length} chars)...")
        val startTime = System.currentTimeMillis()
        val firstResult = sendChat(
            model = model,
            token = token,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = text),
            ),
        )
        val elapsed = System.currentTimeMillis() - startTime
        println("[AiMarkup] Pass 1 done (${firstResult.length} chars, ${elapsed}ms)")

        // TODO: Second pass temporarily disabled
//        // Second pass — dialog fix
//        println("[AiMarkup] Pass 2: fixing dialogs...")
//        val finalResult = sendChat(
//            model = model,
//            token = token,
//            messages = listOf(
//                ChatMessage(role = "system", content = systemPrompt),
//                ChatMessage(role = "user", content = text),
//                ChatMessage(role = "assistant", content = firstResult),
//                ChatMessage(role = "user", content = MarkupSystemPrompts.dialogFixPrompt),
//            ),
//        )
//        println("[AiMarkup] Pass 2 done (${finalResult.length} chars)")
//        return finalResult.replace("```", "")

        return fixMalformedTags(firstResult.replace("```", ""))
    }

    /**
     * AI иногда возвращает теги в формате <voice_name> вместо [voice_name].
     * Заменяем угловые скобки на квадратные для тегов голосов.
     */
    internal fun fixMalformedTags(text: String): String {
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

        val totalChars = messages.sumOf { it.content.length }
        println("[AiMarkup] -> POST $ENDPOINT (model=$model, messages=${messages.size}, totalChars=$totalChars)")

        val startTime = System.currentTimeMillis()
        val response = client.post(ENDPOINT) {
            header(HttpHeaders.Authorization, "Api-Key $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val elapsed = System.currentTimeMillis() - startTime

        println("[AiMarkup] <- HTTP ${response.status.value} (${elapsed}ms)")

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
