package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.HttpOpenCodeApi
import com.github.atm1020.tuilaunch.resume.OpenCodeApi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections

private const val LOOPBACK = "127.0.0.1"
private const val SESSION_ID = "ses_7d1e4a9c"
private const val TAB_UUID = "8b2c7d40-3e19-4c58-9f6a-1d0e5b7c2a93"
private const val PROJECT_DIRECTORY = "/Users/falleng0d/My Projects/TUILaunch"

private data class Reply(val status: Int, val body: String)

private data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val body: String,
)

class OpenCodeApiTest {

    private var server: HttpServer? = null
    private val recorded = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @After
    fun stopTheServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun theServerIsHealthyOnlyWhenItSaysSo() {
        val api = serve("/global/health" to Reply(200, """{"healthy":true,"version":"1.18.23"}"""))

        assertTrue(runBlocking { api.health() })
        assertEquals("GET", requests().single().method)
    }

    @Test
    fun aStartingServerIsNotHealthyYet() {
        val api = serve("/global/health" to Reply(200, """{"healthy":false}"""))

        assertFalse(runBlocking { api.health() })
    }

    @Test
    fun anErrorAnswerIsNotHealthy() {
        val api = serve("/global/health" to Reply(503, "not ready"))

        assertFalse(runBlocking { api.health() })
    }

    @Test
    fun aPortWithNoServerBehindItFails() {
        val api = HttpOpenCodeApi(aPortNothingListensOn())

        assertThrows(IOException::class.java) { runBlocking { api.health() } }
    }

    @Test
    fun creatingASessionSendsTheDirectoryAndTheTabItBelongsTo() {
        val api = serve("/session" to Reply(200, """{"id":"$SESSION_ID","title":"New session"}"""))

        assertEquals(SESSION_ID, runBlocking { api.createSession(PROJECT_DIRECTORY, TAB_UUID) })

        val request = requests().single()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        assertEquals(PROJECT_DIRECTORY, decodedDirectoryOf(request))
        assertEquals("""{"metadata":{"tuilaunchTab":"$TAB_UUID"}}""", request.body)
    }

    @Test
    fun aSessionWithoutAnIdFails() {
        val api = serve("/session" to Reply(200, """{"title":"New session"}"""))

        assertThrows(IOException::class.java) { runBlocking { api.createSession(PROJECT_DIRECTORY, TAB_UUID) } }
    }

    @Test
    fun aRefusedCreationFails() {
        val api = serve("/session" to Reply(500, "boom"))

        assertThrows(IOException::class.java) { runBlocking { api.createSession(PROJECT_DIRECTORY, TAB_UUID) } }
    }

    @Test
    fun selectingASessionTellsTheTuiWhichOneToShow() {
        val api = serve("/tui/select-session" to Reply(200, "true"))

        runBlocking { api.selectSession(SESSION_ID) }

        val request = requests().single()
        assertEquals("POST", request.method)
        assertEquals("/tui/select-session", request.path)
        assertNull(request.query)
        assertEquals("""{"sessionID":"$SESSION_ID"}""", request.body)
    }

    @Test
    fun anEmptySessionHasNoMessages() {
        val api = serve("/session/$SESSION_ID/message" to Reply(200, "[]"))

        assertEquals(0, runBlocking { api.messageCount(SESSION_ID) })
        assertEquals("GET", requests().single().method)
    }

    @Test
    fun aSessionWithATurnInItHasMessages() {
        val api = serve(
            "/session/$SESSION_ID/message" to Reply(200, """[{"id":"msg_1"},{"id":"msg_2"}]"""),
        )

        assertEquals(2, runBlocking { api.messageCount(SESSION_ID) })
    }

    @Test
    fun anAnswerThatIsNoMessageListFails() {
        val api = serve("/session/$SESSION_ID/message" to Reply(200, """{"error":"gone"}"""))

        assertThrows(IOException::class.java) { runBlocking { api.messageCount(SESSION_ID) } }
    }

    @Test
    fun deletingASessionAsksTheServerToDropIt() {
        val api = serve("/session/$SESSION_ID" to Reply(200, "true"))

        runBlocking { api.deleteSession(SESSION_ID) }

        val request = requests().single()
        assertEquals("DELETE", request.method)
        assertEquals("/session/$SESSION_ID", request.path)
    }

    @Test
    fun aRefusedDeletionFails() {
        val api = serve("/session/$SESSION_ID" to Reply(404, "not found"))

        assertThrows(IOException::class.java) { runBlocking { api.deleteSession(SESSION_ID) } }
    }

    private fun serve(vararg routes: Pair<String, Reply>): OpenCodeApi {
        val replies = routes.toMap()
        val started = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0)
        started.createContext("/") { exchange -> answer(exchange, replies) }
        started.start()
        server = started
        return HttpOpenCodeApi(started.address.port)
    }

    private fun answer(exchange: HttpExchange, replies: Map<String, Reply>) {
        try {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            recorded.add(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.query,
                    body = body,
                )
            )
            val reply = replies[exchange.requestURI.path] ?: Reply(404, "no route")
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } finally {
            exchange.close()
        }
    }

    private fun requests(): List<RecordedRequest> = synchronized(recorded) { recorded.toList() }

    private fun decodedDirectoryOf(request: RecordedRequest): String {
        val query = requireNotNull(request.query) { "The request carried no query string" }
        val value = query.substringAfter("directory=")
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun aPortNothingListensOn(): Int = ServerSocket(0).use { it.localPort }
}
