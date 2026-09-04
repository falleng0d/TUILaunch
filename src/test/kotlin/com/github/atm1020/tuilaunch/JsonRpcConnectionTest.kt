package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.JsonRpcConnection
import com.github.atm1020.tuilaunch.copilot.JsonRpcConnectionClosedException
import com.github.atm1020.tuilaunch.copilot.JsonRpcException
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class JsonRpcConnectionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var streams: CopilotTestStreams
    private lateinit var peer: CopilotTestPeer
    private lateinit var connection: JsonRpcConnection
    private val uncaughtFailures = CopyOnWriteArrayList<Throwable>()

    @Before
    fun setUp() {
        val recordFailures = CoroutineExceptionHandler { _, failure -> uncaughtFailures += failure }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + recordFailures)
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

    @Test
    fun `a negative content length is a protocol error that closes the connection`() {
        val answer = scope.async { connection.request("checkStatus", null) }
        peer.read()

        peer.sendRaw("Content-Length: -1\r\n\r\n")

        assertTrue(failure(answer) is JsonRpcConnectionClosedException)
        assertTrue(connection.isClosed)
        assertEquals(emptyList<Throwable>(), settledUncaughtFailures())
    }

    @Test
    fun `a content length nobody could allocate closes the connection instead`() {
        val answer = scope.async { connection.request("checkStatus", null) }
        peer.read()

        peer.sendRaw("Content-Length: 2147483647\r\n\r\n")

        assertTrue(failure(answer) is JsonRpcConnectionClosedException)
        assertTrue(connection.isClosed)
        assertEquals(emptyList<Throwable>(), settledUncaughtFailures())
    }

    @Test
    fun `a header line that never ends closes the connection`() {
        val answer = scope.async { connection.request("checkStatus", null) }
        peer.read()

        peer.sendRaw("X-Endless: " + "a".repeat(ENDLESS_HEADER_BYTES))

        assertTrue(failure(answer) is JsonRpcConnectionClosedException)
        assertTrue(connection.isClosed)
        assertEquals(emptyList<Throwable>(), settledUncaughtFailures())
    }

    @Test
    fun `a close listener learns that the connection is gone`() {
        val closures = ArrayList<String>()
        connection.onClosed { closures += "read loop" }

        peer.sendRaw("Content-Length: -1\r\n\r\n")

        awaitTrue { connection.isClosed }
        assertEquals(listOf("read loop"), closures)

        connection.onClosed { closures += "after the close" }

        assertEquals(listOf("read loop", "after the close"), closures)
    }

    private fun <T> await(answer: Deferred<T>): T = runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { answer.await() } }

    private fun failure(answer: Deferred<*>): Throwable? = runBlocking {
        withTimeout(AWAIT_TIMEOUT_MS) { runCatching { answer.await() }.exceptionOrNull() }
    }

    private fun awaitTrue(condition: () -> Boolean) = runBlocking {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun settledUncaughtFailures(): List<Throwable> {
        runBlocking { delay(SETTLE_MS) }
        return uncaughtFailures.toList()
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 200L
        const val ENDLESS_HEADER_BYTES = 16 * 1024
    }
}
