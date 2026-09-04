package com.github.atm1020.tuilaunch

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class BlockingBytePipe(private val readTimeoutMs: Long = 0L) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = java.lang.Object()
    private val bytes = ArrayDeque<Byte>()
    private var writerClosed = false
    private var readerClosed = false

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(monitor) {
                if (readerClosed) throw IOException("The pipe reader is closed")
                for (index in off until off + len) bytes.addLast(b[index])
                monitor.notifyAll()
            }
        }

        override fun close() {
            synchronized(monitor) {
                writerClosed = true
                monitor.notifyAll()
            }
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(monitor) {
                val deadline = if (readTimeoutMs > 0) System.currentTimeMillis() + readTimeoutMs else 0L
                while (bytes.isEmpty() && !writerClosed && !readerClosed) {
                    if (deadline == 0L) {
                        monitor.wait()
                        continue
                    }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) throw IOException("The pipe produced no bytes within $readTimeoutMs ms")
                    monitor.wait(remaining)
                }
                if (bytes.isEmpty()) return -1
                var written = 0
                while (written < len && bytes.isNotEmpty()) {
                    b[off + written] = bytes.removeFirst()
                    written++
                }
                return written
            }
        }

        override fun close() {
            synchronized(monitor) {
                readerClosed = true
                monitor.notifyAll()
            }
        }
    }
}

class CopilotTestStreams(peerReadTimeoutMs: Long = 10_000L) {
    private val toClient = BlockingBytePipe()
    private val toServer = BlockingBytePipe(peerReadTimeoutMs)

    val clientInput: InputStream get() = toClient.input
    val clientOutput: OutputStream get() = toServer.output

    val peer: CopilotTestPeer = CopilotTestPeer(toServer.input, toClient.output)
}

class CopilotTestPeer(private val input: InputStream, private val output: OutputStream) {
    private val buffered = input.buffered()

    var lastContentLength: Int = 0
        private set

    fun read(): JsonObject {
        val length = readContentLength()
        lastContentLength = length
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = buffered.read(body, read, length - read)
            if (count < 0) throw IOException("The peer stream ended after $read of $length bytes")
            read += count
        }
        return JsonParser.parseString(String(body, StandardCharsets.UTF_8)).asJsonObject
    }

    fun send(message: JsonObject) {
        val body = message.toString().toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    fun respond(id: Int, result: JsonElement) = send(
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("result", result)
        }
    )

    fun respondError(id: Int, code: Int, message: String) = send(
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add(
                "error",
                JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", message)
                },
            )
        }
    )

    fun request(id: Int, method: String, params: JsonElement) = send(
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
    )

    fun notify(method: String, params: JsonElement) = send(
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
    )

    fun respondNull(id: Int) = respond(id, JsonNull.INSTANCE)

    fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }

    private fun readContentLength(): Int {
        var length = -1
        while (true) {
            val line = readLine() ?: throw IOException("The peer stream ended before a complete header")
            if (line.isEmpty()) {
                if (length < 0) throw IOException("The peer received a frame without a Content-Length header")
                return length
            }
            val separator = line.indexOf(':')
            if (separator > 0 && line.take(separator).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(separator + 1).trim().toInt()
            }
        }
    }

    private fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = buffered.read()
            if (byte < 0) return null
            if (byte == '\n'.code) {
                val raw = line.toByteArray()
                val end = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, end, StandardCharsets.US_ASCII)
            }
            line.write(byte)
        }
    }
}
