package one.wabbit.web.mediawiki

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Wikipedia(
    val httpClient: HttpClient,
    val apiUrl: String = "https://en.wikipedia.org/w/api.php",
) {
    @Serializable data class SearchResult(val title: String, val url: String)

    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        val response =
            httpClient.get(apiUrl) {
                header("Accept", "application/json")
                parameter("action", "opensearch")
                parameter("format", "json")
                parameter("formatversion", "2")
                parameter("search", URLEncoder.encode(query, "UTF-8"))
                parameter("limit", limit)
            }
        val arr = Json.decodeFromString<JsonArray>(response.bodyAsText())
        return arr[1].jsonArray.zip(arr[3].jsonArray).map {
            SearchResult(it.first.jsonPrimitive.content, it.second.jsonPrimitive.content)
        }
    }

    @Serializable data class PageSummary(val title: String, val pageId: Int, val extract: String)

    class PageNotFound(title: String) : Exception("Wiki page not found: $title")

    suspend fun summary(title: String): PageSummary {
        val response =
            httpClient.get(apiUrl) {
                header("Accept", "application/json")
                parameter("action", "query")
                parameter("format", "json")
                parameter("prop", "extracts")
                parameter("titles", title)
                parameter("exintro", "")
                parameter("explaintext", "")
            }
        val body = response.bodyAsText()
        // {"batchcomplete":"","query":{"pages":{"-1":{"ns":0,"title":"DataRobot","missing":""}}}}

        val json = Json.decodeFromString<JsonObject>(body)
        val pages = json["query"]!!.jsonObject["pages"]!!.jsonObject

        val pageId = pages.keys.first().toInt()
        if (pageId == -1) {
            throw PageNotFound(title)
        } else {
            val page = pages[pageId.toString()]!!.jsonObject
            return PageSummary(
                page["title"]!!.jsonPrimitive.content,
                page["pageid"]!!.jsonPrimitive.int,
                page["extract"]!!.jsonPrimitive.content.trim(),
            )
        }
    }
}
