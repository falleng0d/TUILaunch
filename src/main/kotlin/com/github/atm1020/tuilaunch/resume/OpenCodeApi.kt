package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

interface OpenCodeApi {
    suspend fun health(): Boolean

    suspend fun createSession(directory: String, tabUuid: String): String

    suspend fun selectSession(id: String)

    suspend fun messageCount(id: String): Int

    suspend fun deleteSession(id: String)
}

class HttpOpenCodeApi(
    private val port: Int,
    private val hostname: String = OpenCodeSessionStrategy.LOOPBACK_HOSTNAME,
    private val client: HttpClient = sharedClient,
) : OpenCodeApi {

    override suspend fun health(): Boolean {
        val response = send(requestTo(HEALTH_PATH).GET().build())
        if (response.statusCode() != OK_STATUS) return false
        val healthy = jsonObjectOf(response.body())?.get(HEALTHY_FIELD) ?: return false
        return healthy.isJsonPrimitive && healthy.asBoolean
    }

    override suspend fun createSession(directory: String, tabUuid: String): String {
        val encodedDirectory = URLEncoder.encode(directory, StandardCharsets.UTF_8)
        val path = "$SESSION_PATH?$DIRECTORY_PARAMETER=$encodedDirectory"
        val response = sendExpectingSuccess(post(path, newSessionBody(tabUuid)))
        val id = stringField(jsonObjectOf(response.body())?.get(ID_FIELD))
        return id ?: throw IOException("OpenCode on port $port answered a session without an id")
    }

    override suspend fun selectSession(id: String) {
        val body = JsonObject().apply { addProperty(SESSION_ID_FIELD, id) }.toString()
        sendExpectingSuccess(post(SELECT_SESSION_PATH, body))
    }

    override suspend fun messageCount(id: String): Int {
        val response = sendExpectingSuccess(requestTo("$SESSION_PATH/$id$MESSAGE_PATH").GET().build())
        val messages = parseJson(response.body())
        if (messages == null || !messages.isJsonArray) {
            throw IOException("OpenCode on port $port answered no message list for session $id")
        }
        return messages.asJsonArray.size()
    }

    override suspend fun deleteSession(id: String) {
        sendExpectingSuccess(requestTo("$SESSION_PATH/$id").DELETE().build())
    }

    private fun newSessionBody(tabUuid: String): String {
        val metadata = JsonObject().apply { addProperty(TAB_FIELD, tabUuid) }
        return JsonObject().apply { add(METADATA_FIELD, metadata) }.toString()
    }

    private fun requestTo(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create("$HTTP_SCHEME$hostname:$port$path"))
        .timeout(REQUEST_TIMEOUT)
        .header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)

    private fun post(path: String, body: String): HttpRequest = requestTo(path)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()

    private suspend fun sendExpectingSuccess(request: HttpRequest): HttpResponse<String> {
        val response = send(request)
        if (response.statusCode() !in SUCCESS_STATUS) {
            throw IOException(
                "OpenCode on port $port answered ${response.statusCode()} " +
                    "to ${request.method()} ${request.uri().path}"
            )
        }
        return response
    }

    private suspend fun send(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun jsonObjectOf(body: String): JsonObject? = parseJson(body)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun parseJson(body: String): JsonElement? = try {
        JsonParser.parseString(body)
    } catch (_: RuntimeException) {
        null
    }

    private fun stringField(field: JsonElement?): String? {
        if (field == null || !field.isJsonPrimitive || !field.asJsonPrimitive.isString) return null
        return field.asString.takeIf { it.isNotBlank() }
    }

    companion object {
        const val HEALTH_PATH = "/global/health"
        const val SESSION_PATH = "/session"
        const val MESSAGE_PATH = "/message"
        const val SELECT_SESSION_PATH = "/tui/select-session"
        const val DIRECTORY_PARAMETER = "directory"
        const val TAB_FIELD = "tuilaunchTab"

        private const val OK_STATUS = 200
        private const val HTTP_SCHEME = "http://"
        private const val HEALTHY_FIELD = "healthy"
        private const val ID_FIELD = "id"
        private const val METADATA_FIELD = "metadata"
        private const val SESSION_ID_FIELD = "sessionID"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val JSON_CONTENT_TYPE = "application/json"
        private val SUCCESS_STATUS = 200..299
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        private val sharedClient: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        }
    }
}
