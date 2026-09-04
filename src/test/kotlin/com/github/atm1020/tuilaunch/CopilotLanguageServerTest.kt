package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotEditorInfo
import com.github.atm1020.tuilaunch.copilot.CopilotLanguageServer
import com.github.atm1020.tuilaunch.copilot.InlineCompletionTriggerKind
import com.github.atm1020.tuilaunch.copilot.JsonRpcConnection
import com.github.atm1020.tuilaunch.copilot.LspPosition
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CopilotLanguageServerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var streams: CopilotTestStreams
    private lateinit var peer: CopilotTestPeer
    private lateinit var connection: JsonRpcConnection
    private lateinit var server: CopilotLanguageServer
    private val openedDocuments = ArrayList<String>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        streams = CopilotTestStreams()
        peer = streams.peer
        connection = JsonRpcConnection(streams.clientInput, streams.clientOutput, scope)
        server = CopilotLanguageServer(
            connection = connection,
            editorInfo = CopilotEditorInfo("IntelliJ IDEA", "2026.2"),
            editorPluginInfo = CopilotEditorInfo("TUILaunch", "0.7.0"),
            openDocument = { uri -> openedDocuments += uri },
        )
    }

    @After
    fun tearDown() {
        connection.close()
        peer.close()
        scope.cancel()
    }

    @Test
    fun `initialize declares only the editor and the plugin`() {
        val answer = scope.async { server.initialize() }

        val request = peer.read()
        assertEquals("initialize", request["method"].asString)
        val params = request["params"].asJsonObject
        val options = params["initializationOptions"].asJsonObject
        assertEquals(setOf("editorInfo", "editorPluginInfo"), options.keySet())
        assertEquals("IntelliJ IDEA", options["editorInfo"].asJsonObject["name"].asString)
        assertEquals("2026.2", options["editorInfo"].asJsonObject["version"].asString)
        assertEquals("TUILaunch", options["editorPluginInfo"].asJsonObject["name"].asString)
        assertEquals("0.7.0", options["editorPluginInfo"].asJsonObject["version"].asString)
        assertFalse(params.toString().contains("copilotCapabilities"))
        assertFalse(params.toString().contains("dynamicRegistration"))

        peer.respond(request["id"].asInt, JsonParser.parseString(INITIALIZE_RESULT))
        assertEquals(
            "GitHub Copilot Language Server",
            await(answer).asJsonObject["serverInfo"].asJsonObject["name"].asString,
        )
    }

    @Test
    fun `workspace configuration is answered with one object per item`() {
        val items = JsonArray()
        listOf("github.copilot", "github-enterprise", "http", "telemetry").forEach { section ->
            items.add(JsonObject().apply { addProperty("section", section) })
        }

        peer.request(0, CopilotLanguageServer.WORKSPACE_CONFIGURATION, JsonObject().apply { add("items", items) })

        val response = peer.read()
        assertEquals(0, response["id"].asInt)
        val answered = response["result"].asJsonArray
        assertEquals(4, answered.size())
        assertTrue(answered.all { it.asJsonObject.keySet().isEmpty() })
    }

    @Test
    fun `a message request is answered with null`() {
        peer.request(1, CopilotLanguageServer.SHOW_MESSAGE_REQUEST, JsonObject())

        val response = peer.read()
        assertTrue(response["result"].isJsonNull)
    }

    @Test
    fun `a document request opens the uri and reports success`() {
        peer.request(
            2,
            CopilotLanguageServer.SHOW_DOCUMENT,
            JsonObject().apply { addProperty("uri", "https://github.com/login/device") },
        )

        val response = peer.read()
        assertTrue(response["result"].asJsonObject["success"].asBoolean)
        assertEquals(listOf("https://github.com/login/device"), openedDocuments)
    }

    @Test
    fun `checkStatus reports the signed in user`() {
        val answer = scope.async { server.checkStatus(localChecksOnly = true) }

        val request = peer.read()
        assertEquals("checkStatus", request["method"].asString)
        assertTrue(request["params"].asJsonObject["options"].asJsonObject["localChecksOnly"].asBoolean)
        peer.respond(
            request["id"].asInt,
            JsonObject().apply {
                addProperty("status", "OK")
                addProperty("user", "falleng0d")
            },
        )

        val status = await(answer)
        assertEquals("OK", status.status)
        assertEquals("falleng0d", status.user)
        assertTrue(status.isSignedIn)
    }

    @Test
    fun `checkStatus reports a missing sign in`() {
        val answer = scope.async { server.checkStatus(localChecksOnly = false) }

        val request = peer.read()
        assertFalse(request["params"].asJsonObject["options"].asJsonObject["localChecksOnly"].asBoolean)
        peer.respond(request["id"].asInt, JsonObject().apply { addProperty("status", "NotSignedIn") })

        val status = await(answer)
        assertEquals("NotSignedIn", status.status)
        assertNull(status.user)
        assertFalse(status.isSignedIn)
    }

    @Test
    fun `inlineCompletion parses the items the server returns`() {
        val answer = scope.async {
            server.inlineCompletion(
                uri = "tuilaunch://prompt-box/1.md",
                version = 3,
                position = LspPosition(8, 47),
                triggerKind = InlineCompletionTriggerKind.AUTOMATIC,
            )
        }

        val request = peer.read()
        assertEquals("textDocument/inlineCompletion", request["method"].asString)
        val params = request["params"].asJsonObject
        assertEquals("tuilaunch://prompt-box/1.md", params["textDocument"].asJsonObject["uri"].asString)
        assertEquals(3, params["textDocument"].asJsonObject["version"].asInt)
        assertEquals(8, params["position"].asJsonObject["line"].asInt)
        assertEquals(47, params["position"].asJsonObject["character"].asInt)
        assertEquals(2, params["context"].asJsonObject["triggerKind"].asInt)
        peer.respond(request["id"].asInt, JsonParser.parseString(INLINE_COMPLETION_RESULT))

        val items = await(answer)
        assertEquals(3, items.size)
        val first = items.first()
        assertEquals(
            "Write a short commit message for a change that fixes a bug in the login functionality.",
            first.insertText,
        )
        assertEquals(8, first.range.start.line)
        assertEquals(0, first.range.start.character)
        assertEquals(8, first.range.end.line)
        assertEquals(47, first.range.end.character)
        assertEquals("github.copilot.didAcceptCompletionItem", first.acceptCommand)
        assertEquals(listOf("439f6021-f7e5-4dc0-a196-253a2fba40b1"), first.acceptArguments)
    }

    @Test
    fun `an invoked completion uses trigger kind one`() {
        scope.async {
            server.inlineCompletion(
                uri = "tuilaunch://prompt-box/1.md",
                version = 1,
                position = LspPosition(0, 0),
                triggerKind = InlineCompletionTriggerKind.INVOKED,
            )
        }

        val request = peer.read()
        assertEquals(1, request["params"].asJsonObject["context"].asJsonObject["triggerKind"].asInt)
    }

    @Test
    fun `didShowCompletion repeats the accept command of the item`() {
        val items = itemsFromTranscript()

        server.didShowCompletion(items.first())

        val notification = peer.read()
        assertEquals("textDocument/didShowCompletion", notification["method"].asString)
        val item = notification["params"].asJsonObject["item"].asJsonObject
        assertEquals(
            "Write a short commit message for a change that fixes a bug in the login functionality.",
            item["insertText"].asString,
        )
        val command = item["command"].asJsonObject
        assertEquals("github.copilot.didAcceptCompletionItem", command["command"].asString)
        assertEquals(1, command["arguments"].asJsonArray.size())
    }

    @Test
    fun `executeCommand sends the command with its single argument`() {
        scope.async {
            server.executeCommand(
                CopilotLanguageServer.ACCEPT_COMPLETION_COMMAND,
                listOf("439f6021-f7e5-4dc0-a196-253a2fba40b1"),
            )
        }

        val request = peer.read()
        assertEquals("workspace/executeCommand", request["method"].asString)
        val params = request["params"].asJsonObject
        assertEquals("github.copilot.didAcceptCompletionItem", params["command"].asString)
        assertEquals(1, params["arguments"].asJsonArray.size())
        assertEquals("439f6021-f7e5-4dc0-a196-253a2fba40b1", params["arguments"].asJsonArray[0].asString)
    }

    @Test
    fun `a full text didChange carries one content change`() {
        server.didChange("tuilaunch://prompt-box/1.md", 2, "the whole document")

        val notification = peer.read()
        assertEquals("textDocument/didChange", notification["method"].asString)
        val params = notification["params"].asJsonObject
        assertEquals(2, params["textDocument"].asJsonObject["version"].asInt)
        val changes = params["contentChanges"].asJsonArray
        assertEquals(1, changes.size())
        assertEquals("the whole document", changes[0].asJsonObject["text"].asString)
        assertFalse(changes[0].asJsonObject.has("range"))
    }

    @Test
    fun `a status notification reaches the status flow`() {
        peer.notify(
            CopilotLanguageServer.DID_CHANGE_STATUS,
            JsonObject().apply {
                addProperty("busy", false)
                addProperty("kind", "Error")
                addProperty("message", "You are not signed into GitHub.")
            },
        )

        val change = runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { server.statusChanges.first() } }
        assertEquals("Error", change.kind)
        assertEquals("You are not signed into GitHub.", change.message)
        assertFalse(change.busy)
    }

    private fun itemsFromTranscript() = runBlocking {
        val answer = scope.async {
            server.inlineCompletion(
                uri = "tuilaunch://prompt-box/1.md",
                version = 1,
                position = LspPosition(8, 47),
                triggerKind = InlineCompletionTriggerKind.AUTOMATIC,
            )
        }
        val request = peer.read()
        peer.respond(request["id"].asInt, JsonParser.parseString(INLINE_COMPLETION_RESULT))
        withTimeout(AWAIT_TIMEOUT_MS) { answer.await() }
    }

    private fun <T> await(answer: Deferred<T>): T = runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { answer.await() } }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L

        const val INITIALIZE_RESULT = """
            {"capabilities":{"textDocumentSync":{"openClose":true,"change":2},
            "executeCommandProvider":{"commands":["github.copilot.didAcceptCompletionItem"]},
            "inlineCompletionProvider":{}},
            "serverInfo":{"name":"GitHub Copilot Language Server","version":"1.532.6","nodeVersion":"22.23.2"}}
        """

        const val INLINE_COMPLETION_RESULT = """
            {"items":[
            {"command":{"title":"Completion Accepted","command":"github.copilot.didAcceptCompletionItem",
            "arguments":["439f6021-f7e5-4dc0-a196-253a2fba40b1"]},
            "insertText":"Write a short commit message for a change that fixes a bug in the login functionality.",
            "range":{"start":{"line":8,"character":0},"end":{"line":8,"character":47}}},
            {"command":{"title":"Completion Accepted","command":"github.copilot.didAcceptCompletionItem",
            "arguments":["cb774ea0-ee59-4f6d-b568-64be17406740"]},
            "insertText":"Write a short commit message for a change that fixes a bug in the user authentication system.",
            "range":{"start":{"line":8,"character":0},"end":{"line":8,"character":47}}},
            {"command":{"title":"Completion Accepted","command":"github.copilot.didAcceptCompletionItem",
            "arguments":["ceb42e0d-918d-4632-bd12-8026823bc932"]},
            "insertText":"Write a short commit message for a change that fixes a bug in the user authentication process.",
            "range":{"start":{"line":8,"character":0},"end":{"line":8,"character":47}}}]}
        """
    }
}
