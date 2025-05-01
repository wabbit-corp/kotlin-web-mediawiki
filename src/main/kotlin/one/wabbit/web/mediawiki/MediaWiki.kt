package one.wabbit.web.mediawiki

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder

class Wikipedia(val httpClient: HttpClient, val apiUrl: String = "https://en.wikipedia.org/w/api.php") {
    @Serializable data class SearchResult(val title: String, val url: String)

    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        val response = httpClient.get(apiUrl) {
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

    @Serializable data class PageSummary(
        val title: String,
        val pageId: Int,
        val extract: String)

    class PageNotFound(title: String) : Exception("Wiki page not found: $title")

    suspend fun summary(title: String): PageSummary {
        val response = httpClient.get(apiUrl) {
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
                page["extract"]!!.jsonPrimitive.content.trim()
            )
        }
    }
}
