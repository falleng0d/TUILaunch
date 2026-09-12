package com.github.atm1020.tuilaunch.copilot

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JsonRpcException(val code: Int, message: String) : RuntimeException(message)

class JsonRpcConnectionClosedException(message: String) : IOException(message)

class JsonRpcConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
) {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val incoming = input.buffered()
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val nextRequestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val notificationHandlers = ConcurrentHashMap<String, (JsonElement?) -> Unit>()
    private val requestHandlers = ConcurrentHashMap<String, (JsonElement?) -> JsonElement?>()
    private val closeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    init {
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    fun onNotification(method: String, handler: (JsonElement?) -> Unit) {
        notificationHandlers[method] = handler
    }

    fun onRequest(method: String, handler: (JsonElement?) -> JsonElement?) {
        requestHandlers[method] = handler
    }

    fun onClosed(listener: () -> Unit) {
        if (closed.get()) listener() else closeListeners += listener
    }

    fun notify(method: String, params: JsonElement?) {
        enqueue(notificationMessage(method, params))
    }

    suspend fun request(method: String, params: JsonElement?): JsonElement {
        val id = nextRequestId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pendingRequests[id] = response
        if (closed.get()) {
            pendingRequests.remove(id)
            throw closedException()
        }
        enqueue(requestMessage(id, method, params))
        try {
            return response.await()
        } catch (cancellation: CancellationException) {
            if (pendingRequests.remove(id) != null) {
                notify(CANCEL_REQUEST, JsonObject().apply { addProperty("id", id) })
            }
            throw cancellation
        }
    }

    /**
     * Destroy the server process before calling this. Closing a pipe that the read loop is blocked on
     * hangs on Windows, and only the process exiting releases that read.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outgoing.close()
        val failure = closedException()
        pendingRequests.keys.toList().forEach { id ->
            pendingRequests.remove(id)?.completeExceptionally(failure)
        }
        runCatching { incoming.close() }
        runCatching { output.close() }
        closeListeners.forEach { listener -> runCatching { listener() } }
        closeListeners.clear()
    }

    private fun closedException() =
        JsonRpcConnectionClosedException("The GitHub Copilot language server connection is closed")

    private fun enqueue(message: JsonObject) {
        if (closed.get()) return
        outgoing.trySend(frame(message))
    }

    private fun frame(message: JsonObject): ByteArray {
        val body = gson.toJson(message).toByteArray(StandardCharsets.UTF_8)
        val header = "$CONTENT_LENGTH: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        return header + body
    }

    private fun requestMessage(id: Int, method: String, params: JsonElement?) =
        JsonObject().apply {
            addProperty("jsonrpc", JSON_RPC_VERSION)
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }

    private fun notificationMessage(method: String, params: JsonElement?) =
        JsonObject().apply {
            addProperty("jsonrpc", JSON_RPC_VERSION)
            addProperty("method", method)
            if (params != null) add("params", params)
        }

    private fun resultMessage(id: JsonElement, result: JsonElement) =
        JsonObject().apply {
            addProperty("jsonrpc", JSON_RPC_VERSION)
            add("id", id)
            add("result", result)
        }

    private fun errorMessage(id: JsonElement, code: Int, message: String) =
        JsonObject().apply {
            addProperty("jsonrpc", JSON_RPC_VERSION)
            add("id", id)
            add(
                "error",
                JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", message)
                },
            )
        }

    private suspend fun writeLoop() {
        try {
            for (bytes in outgoing) {
                if (closed.get()) break
                output.write(bytes)
                output.flush()
            }
        } catch (failure: IOException) {
            thisLogger().debug("Writing to the GitHub Copilot language server failed", failure)
            close()
        }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val body = readFrameBody() ?: break
                val message = parseMessage(body) ?: continue
                dispatch(message)
            }
        } catch (failure: IOException) {
            thisLogger().debug("Reading from the GitHub Copilot language server failed", failure)
        } finally {
            close()
        }
    }

    private fun parseMessage(body: ByteArray): JsonObject? {
        val text = String(body, StandardCharsets.UTF_8)
        val parsed = try {
            JsonParser.parseString(text)
        } catch (failure: JsonParseException) {
            thisLogger().debug("Skipped an unparseable JSON-RPC frame", failure)
            return null
        }
        return parsed as? JsonObject
    }

    private fun readFrameBody(): ByteArray? {
        val headers = readHeaders() ?: return null
        val announced = headers[CONTENT_LENGTH.lowercase()] ?: return null
        val length = announced.toIntOrNull()
        if (length == null || length < 0 || length > MAX_CONTENT_LENGTH) {
            thisLogger().warn("The GitHub Copilot language server announced a $CONTENT_LENGTH of $announced")
            return null
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = incoming.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return body
    }

    private fun readHeaders(): Map<String, String>? {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.take(separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
    }

    private fun readHeaderLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = incoming.read()
            if (byte < 0) return null
            if (byte == '\n'.code) {
                val bytes = line.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, StandardCharsets.US_ASCII)
            }
            if (line.size() >= MAX_HEADER_LINE_BYTES) {
                thisLogger().warn("The GitHub Copilot language server sent a header line without an end")
                return null
            }
            line.write(byte)
        }
    }

    private fun dispatch(message: JsonObject) {
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = message.get("id")?.takeUnless { it.isJsonNull }
        when {
            method != null && id != null -> answerServerRequest(id, method, message.get("params"))
            method != null -> deliverNotification(method, message.get("params"))
            id != null -> completeRequest(id, message)
            else -> thisLogger().debug("Ignored a JSON-RPC message with neither a method nor an id")
        }
    }

    private fun answerServerRequest(id: JsonElement, method: String, params: JsonElement?) {
        val handler = requestHandlers[method]
        if (handler == null) {
            thisLogger().debug("Answered an unsupported GitHub Copilot request: $method")
            enqueue(errorMessage(id, METHOD_NOT_FOUND, "Method not found: $method"))
            return
        }
        scope.launch {
            val answer = try {
                resultMessage(id, handler(params) ?: JsonNull.INSTANCE)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorMessage(id, INTERNAL_ERROR, failure.message ?: failure.javaClass.name)
            }
            enqueue(answer)
        }
    }

    private fun deliverNotification(method: String, params: JsonElement?) {
        val handler = notificationHandlers[method]
        if (handler == null) {
            thisLogger().debug("Ignored a GitHub Copilot notification: $method")
            return
        }
        try {
            handler(params)
        } catch (failure: Exception) {
            thisLogger().debug("A GitHub Copilot notification handler for $method failed", failure)
        }
    }

    private fun completeRequest(id: JsonElement, message: JsonObject) {
        val requestId = id.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: return
        val response = pendingRequests.remove(requestId) ?: return
        val error = message.getAsJsonObject("error")
        if (error != null) {
            val code = error.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: INTERNAL_ERROR
            val reason = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Request failed"
            response.completeExceptionally(JsonRpcException(code, reason))
            return
        }
        response.complete(message.get("result") ?: JsonNull.INSTANCE)
    }

    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
        const val CANCEL_REQUEST = "\$/cancelRequest"

        private const val JSON_RPC_VERSION = "2.0"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val MAX_CONTENT_LENGTH = 64 * 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
    }
}
