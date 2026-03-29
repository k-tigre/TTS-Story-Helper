package by.tigre.speechhelper.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientProvider {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Shared HttpClient for LLM markup requests.
     * Timeout: 240 seconds (for long AI responses)
     */
    val markupClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                requestTimeout = 420_000
            }
            install(ContentNegotiation) {
                json(this@HttpClientProvider.json)
            }
        }
    }

    /**
     * Shared HttpClient for lightweight API calls (models list, health checks).
     * Timeout: 15 seconds
     */
    val defaultClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                requestTimeout = 15_000
            }
            install(ContentNegotiation) {
                json(this@HttpClientProvider.json)
            }
        }
    }

    /**
     * Shared Json instance for consistent serialization across the app.
     */
    val jsonInstance: Json get() = json
}
