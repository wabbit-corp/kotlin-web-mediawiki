package one.wabbit.web.mediawiki

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WikipediaSpec {
    @Test
    fun `search parses open search response`() = runTest {
        val wiki = Wikipedia(
            testClient { request ->
                assertEquals("opensearch", request.url.parameters["action"])
                assertEquals("json", request.url.parameters["format"])
                assertEquals("2", request.url.parameters["formatversion"])
                assertEquals("Wabbit", request.url.parameters["search"])
                assertEquals("3", request.url.parameters["limit"])

                respond(
                    content = """
                        [
                          "Wabbit",
                          ["Wabbit", "Wabbit season", "Wabbit test"],
                          [],
                          [
                            "https://example.org/wiki/Wabbit",
                            "https://example.org/wiki/Wabbit_season",
                            "https://example.org/wiki/Wabbit_test"
                          ]
                        ]
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        val results = wiki.search(query = "Wabbit", limit = 3)

        assertEquals(
            listOf(
                WikipediaSearchResult("Wabbit", "https://example.org/wiki/Wabbit"),
                WikipediaSearchResult("Wabbit season", "https://example.org/wiki/Wabbit_season"),
                WikipediaSearchResult("Wabbit test", "https://example.org/wiki/Wabbit_test"),
            ),
            results,
        )
    }

    @Test
    fun `summary throws page not found for missing page id`() = runTest {
        val wiki = Wikipedia(
            testClient { request ->
                assertEquals("query", request.url.parameters["action"])
                assertEquals("DataRobot", request.url.parameters["titles"])
                assertEquals("", request.url.parameters["exintro"])
                assertEquals("", request.url.parameters["explaintext"])

                respond(
                    content = """
                        {
                          "batchcomplete": "",
                          "query": {
                            "pages": {
                              "-1": {
                                "ns": 0,
                                "title": "DataRobot",
                                "missing": ""
                              }
                            }
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        assertFailsWith<WikipediaApiError.PageNotFound> {
            wiki.summary("DataRobot")
        }
    }

    @Test
    fun `search maps http failures to typed error`() = runTest {
        val wiki = Wikipedia(
            testClient {
                respond(
                    content = "temporarily unavailable",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                )
            },
        )

        val error =
            assertFailsWith<WikipediaApiError.Http> {
                wiki.search("Wabbit")
            }

        assertEquals(503, error.status)
    }

    private fun testClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(HttpTimeout)
        }
}
