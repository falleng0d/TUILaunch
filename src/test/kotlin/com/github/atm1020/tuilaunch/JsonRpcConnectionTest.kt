package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.JsonRpcConnection
import com.github.atm1020.tuilaunch.copilot.JsonRpcConnectionClosedException
import com.github.atm1020.tuilaunch.copilot.JsonRpcException
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class JsonRpcConnectionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var streams: CopilotTestStreams
    private lateinit var peer: CopilotTestPeer
    private lateinit var connection: JsonRpcConnection

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        streams = CopilotTestStreams()
        peer = streams.peer
        connection = JsonRpcConnection(streams.clientInput, streams.clientOutput, scope)
    }

    @After
    fun tearDown() {
        connection.close()
        peer.close()
        scope.cancel()
    }

    @Test
    fun `a frame carries the byte length of a multi byte payload`() {
        val text = "héllo — 🌍 τέλος"
        val answer = scope.async { connection.request("echo", JsonObject().apply { addProperty("text", text) }) }

        val request = peer.read()
        assertEquals("echo", request["method"].asString)
        assertEquals(text, request["params"].asJsonObject["text"].asString)
        assertEquals(request.toString().toByteArray(StandardCharsets.UTF_8).size, peer.lastContentLength)
        assertTrue(peer.lastContentLength > request.toString().length)

        peer.respond(request["id"].asInt, JsonObject().apply { addProperty("text", "réponse — 🌍") })

        assertEquals("réponse — 🌍", await(answer).asJsonObject["text"].asString)
    }

    @Test
    fun `responses are routed to the request that carries their id`() {
        val first = scope.async { connection.request("first", null) }
        val second = scope.async { connection.request("second", null) }

        val firstRequest = peer.read()
        val secondRequest = peer.read()
        assertEquals("first", firstRequest["method"].asString)
        assertEquals("second", secondRequest["method"].asString)

        peer.respond(secondRequest["id"].asInt, JsonObject().apply { addProperty("who", "second") })
        peer.respond(firstRequest["id"].asInt, JsonObject().apply { addProperty("who", "first") })

        assertEquals("first", await(first).asJsonObject["who"].asString)
        assertEquals("second", await(second).asJsonObject["who"].asString)
    }

    @Test
    fun `an error response fails the request with its code`() {
        val answer = scope.async { connection.request("textDocument/inlineCompletion", null) }

        val request = peer.read()
        peer.respondError(request["id"].asInt, -32802, "Request was superseded by a new request")

        val failure = failure(answer)
        assertTrue(failure is JsonRpcException)
        assertEquals(-32802, (failure as JsonRpcException).code)
        assertEquals("Request was superseded by a new request", failure.message)
    }

    @Test
    fun `cancelling a request sends a cancel notification for its id`() {
        val answer = scope.async { connection.request("textDocument/inlineCompletion", null) }
        val request = peer.read()

        answer.cancel()

        val cancellation = peer.read()
        assertEquals("\$/cancelRequest", cancellation["method"].asString)
        assertEquals(request["id"].asInt, cancellation["params"].asJsonObject["id"].asInt)
    }

    @Test
    fun `an unsupported server request is answered with method not found`() {
        peer.request(7, "client/registerCapability", JsonObject())

        val response = peer.read()
        assertEquals(7, response["id"].asInt)
        assertEquals(-32601, response["error"].asJsonObject["code"].asInt)
        assertTrue(response["error"].asJsonObject["message"].asString.contains("client/registerCapability"))
    }

    @Test
    fun `an unhandled notification produces no answer`() {
        peer.notify("featureFlagsNotification", JsonObject())
        peer.request(8, "unhandled/method", JsonObject())

        val response = peer.read()
        assertEquals(8, response["id"].asInt)
        assertNull(response["result"])
    }

    @Test
    fun `a handled notification reaches its listener`() {
        val delivered = ArrayList<String>()
        connection.onNotification("didChangeStatus") { params ->
            delivered += params?.asJsonObject?.get("kind")?.asString.orEmpty()
        }

        peer.notify("didChangeStatus", JsonObject().apply { addProperty("kind", "Normal") })
        peer.request(9, "unhandled/method", JsonObject())
        peer.read()

        assertEquals(listOf("Normal"), delivered)
    }

    @Test
    fun `a registered request handler answers the server`() {
        connection.onRequest("workspace/configuration") { JsonObject().apply { addProperty("answered", true) } }

        peer.request(11, "workspace/configuration", JsonObject())

        val response = peer.read()
        assertEquals(11, response["id"].asInt)
        assertTrue(response["result"].asJsonObject["answered"].asBoolean)
    }

    @Test
    fun `the end of the stream fails every pending request`() {
        val answer = scope.async { connection.request("checkStatus", null) }
        peer.read()

        peer.close()

        assertTrue(failure(answer) is JsonRpcConnectionClosedException)
        assertTrue(connection.isClosed)
    }

    private fun <T> await(answer: Deferred<T>): T = runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { answer.await() } }

    private fun failure(answer: Deferred<*>): Throwable? = runBlocking {
        withTimeout(AWAIT_TIMEOUT_MS) { runCatching { answer.await() }.exceptionOrNull() }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
