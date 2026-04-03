package one.wabbit.web.mediawiki

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.retryingHttpCall
import one.wabbit.web.common.safeBodyPrefix
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WikipediaSearchResult(val title: String, val url: String)

@Serializable
data class WikipediaPageSummary(val title: String, val pageId: Int, val extract: String)

sealed class WikipediaApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : WikipediaApiError(message)

    class PageNotFound(title: String) : WikipediaApiError("Wiki page not found: $title")

    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        cause: Throwable? = null,
    ) : WikipediaApiError(
        buildString {
            append("HTTP ")
            append(status)
            append(" from ")
            append(url)
            if (!bodySample.isNullOrBlank()) {
                append(", body sample: ")
                append(bodySample.take(256))
            }
        },
        cause,
    )

    class Network(
        val url: String,
        cause: Throwable,
    ) : WikipediaApiError(
        "Network failure talking to $url: ${cause::class.simpleName}: ${cause.message}",
        cause,
    )

    class Parse(
        val url: String,
        val bodySample: String,
        cause: Throwable,
    ) : WikipediaApiError(
        "Failed to parse Wikipedia response from $url: ${cause::class.simpleName}: ${cause.message}; body sample: ${bodySample.take(256)}",
        cause,
    )
}

interface WikipediaApi {
    data class Config(
        val apiUrl: String = "https://en.wikipedia.org/w/api.php",
        val etiquette: Etiquette = Etiquette("one.wabbit.web.mediawiki/1.0"),
        val timeouts: Timeouts = Timeouts(
            request = 30.seconds,
            connect = 30.seconds,
            socket = 30.seconds,
        ),
    ) {
        init {
            require(apiUrl.isNotBlank()) { "apiUrl must not be blank" }
        }
    }

    suspend fun search(query: String, limit: Int = 5): List<WikipediaSearchResult>

    suspend fun summary(title: String): WikipediaPageSummary
}

class KtorWikipediaApi(
    val httpClient: HttpClient,
    val config: WikipediaApi.Config = WikipediaApi.Config(),
) : WikipediaApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    override suspend fun search(query: String, limit: Int): List<WikipediaSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            throw WikipediaApiError.InvalidInput("query must not be blank")
        }
        if (limit <= 0) {
            throw WikipediaApiError.InvalidInput("limit must be positive")
        }

        val body =
            getBody {
                parameter("action", "opensearch")
                parameter("format", "json")
                parameter("formatversion", "2")
                parameter("search", normalizedQuery)
                parameter("limit", limit)
            }

        val array =
            try {
                json.decodeFromString<JsonArray>(body)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw WikipediaApiError.Parse(config.apiUrl, body.take(2048), t)
            }

        return try {
            val titles = array[1].jsonArray
            val urls = array[3].jsonArray
            titles.zip(urls).map {
                WikipediaSearchResult(
                    title = it.first.jsonPrimitive.content,
                    url = it.second.jsonPrimitive.content,
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw WikipediaApiError.Parse(config.apiUrl, body.take(2048), t)
        }
    }

    override suspend fun summary(title: String): WikipediaPageSummary {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) {
            throw WikipediaApiError.InvalidInput("title must not be blank")
        }

        val body =
            getBody {
                parameter("action", "query")
                parameter("format", "json")
                parameter("prop", "extracts")
                parameter("titles", normalizedTitle)
                parameter("exintro", "")
                parameter("explaintext", "")
            }

        val jsonBody =
            try {
                json.decodeFromString<JsonObject>(body)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw WikipediaApiError.Parse(config.apiUrl, body.take(2048), t)
            }

        val page =
            try {
                val pages = jsonBody["query"]!!.jsonObject["pages"]!!.jsonObject
                val pageId = pages.keys.first().toInt()
                if (pageId == -1) {
                    throw WikipediaApiError.PageNotFound(normalizedTitle)
                }
                pages[pageId.toString()]!!.jsonObject
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is WikipediaApiError.PageNotFound) throw t
                throw WikipediaApiError.Parse(config.apiUrl, body.take(2048), t)
            }

        return WikipediaPageSummary(
            title = page["title"]!!.jsonPrimitive.content,
            pageId = page["pageid"]!!.jsonPrimitive.int,
            extract = page["extract"]!!.jsonPrimitive.content.trim(),
        )
    }

    private suspend fun getBody(configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String {
        val response = try {
            retryingHttpCall {
                httpClient.get(config.apiUrl) {
                    expectSuccess = true
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Application.Json)
                    configure()
                }
            }
        } catch (t: Throwable) {
            throw t.toWikipediaError(config.apiUrl)
        }

        return response.readBody(config.apiUrl)
    }

    private suspend fun HttpResponse.readBody(url: String): String =
        try {
            bodyAsText()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw WikipediaApiError.Network(url, t)
        }
}

typealias Wikipedia = KtorWikipediaApi

private suspend fun Throwable.toWikipediaError(url: String): WikipediaApiError {
    if (this is CancellationException) throw this
    return if (this is ResponseException) {
        val sample = runCatching { response.safeBodyPrefix(2048) }.getOrNull()
        WikipediaApiError.Http(url, response.status.value, sample, this)
    } else {
        WikipediaApiError.Network(url, this)
    }
}
