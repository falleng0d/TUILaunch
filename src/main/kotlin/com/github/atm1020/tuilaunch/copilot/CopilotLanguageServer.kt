package com.github.atm1020.tuilaunch.copilot

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class LspPosition(val line: Int, val character: Int)

data class LspRange(val start: LspPosition, val end: LspPosition)

data class CopilotEditorInfo(val name: String, val version: String)

data class CopilotStatus(val status: String, val user: String?) {
    val isSignedIn: Boolean
        get() = SIGNED_IN_STATUSES.any { it.equals(status, ignoreCase = true) }

    companion object {
        private val SIGNED_IN_STATUSES = setOf("OK", "AlreadySignedIn")
    }
}

data class CopilotStatusChange(val kind: String, val message: String?, val busy: Boolean)

data class InlineCompletionItem(
    val insertText: String,
    val range: LspRange,
    val acceptCommand: String?,
    val acceptArguments: List<String>,
)

enum class InlineCompletionTriggerKind(val value: Int) {
    INVOKED(1),
    AUTOMATIC(2),
}

class CopilotLanguageServer(
    private val connection: JsonRpcConnection,
    private val editorInfo: CopilotEditorInfo = ideEditorInfo(),
    private val editorPluginInfo: CopilotEditorInfo = pluginEditorInfo(),
    private val openDocument: (String) -> Unit = { BrowserUtil.browse(it) },
) : CopilotCompletionServer {
    private val statusFlow = MutableSharedFlow<CopilotStatusChange>(
        replay = 1,
        extraBufferCapacity = STATUS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val statusChanges: Flow<CopilotStatusChange> = statusFlow.asSharedFlow()

    init {
        connection.onRequest(WORKSPACE_CONFIGURATION) { params -> configurationFor(params) }
        connection.onRequest(SHOW_MESSAGE_REQUEST) { JsonNull.INSTANCE }
        connection.onRequest(SHOW_DOCUMENT) { params -> showDocument(params) }
        connection.onNotification(DID_CHANGE_STATUS) { params -> statusFlow.tryEmit(statusChange(params)) }
    }

    suspend fun initialize(): JsonElement = connection.request("initialize", initializeParams())

    fun initialized() = connection.notify("initialized", JsonObject())

    suspend fun checkStatus(localChecksOnly: Boolean): CopilotStatus {
        val params = JsonObject().apply {
            add("options", JsonObject().apply { addProperty("localChecksOnly", localChecksOnly) })
        }
        val result = connection.request("checkStatus", params) as? JsonObject
        val status = result?.string("status") ?: UNKNOWN_STATUS
        return CopilotStatus(status, result?.string("user"))
    }

    override fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        val document = JsonObject().apply {
            addProperty("uri", uri)
            addProperty("languageId", languageId)
            addProperty("version", version)
            addProperty("text", text)
        }
        connection.notify("textDocument/didOpen", JsonObject().apply { add("textDocument", document) })
    }

    override fun didChange(uri: String, version: Int, text: String) {
        val params = JsonObject().apply {
            add(
                "textDocument",
                JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("version", version)
                },
            )
            add("contentChanges", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
        }
        connection.notify("textDocument/didChange", params)
    }

    override fun didClose(uri: String) {
        val params = JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
        }
        connection.notify("textDocument/didClose", params)
    }

    override suspend fun inlineCompletion(
        uri: String,
        version: Int,
        position: LspPosition,
        triggerKind: InlineCompletionTriggerKind,
    ): List<InlineCompletionItem> {
        val params = JsonObject().apply {
            add(
                "textDocument",
                JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("version", version)
                },
            )
            add("position", position.toJson())
            add("context", JsonObject().apply { addProperty("triggerKind", triggerKind.value) })
            add(
                "formattingOptions",
                JsonObject().apply {
                    addProperty("tabSize", DEFAULT_TAB_SIZE)
                    addProperty("insertSpaces", true)
                },
            )
        }
        return parseItems(connection.request("textDocument/inlineCompletion", params))
    }

    override fun didShowCompletion(item: InlineCompletionItem) {
        connection.notify(
            "textDocument/didShowCompletion",
            JsonObject().apply { add("item", item.toJson()) },
        )
    }

    override suspend fun executeCommand(command: String, arguments: List<String>): JsonElement {
        val params = JsonObject().apply {
            addProperty("command", command)
            add("arguments", JsonArray().apply { arguments.forEach { add(it) } })
        }
        return connection.request("workspace/executeCommand", params)
    }

    suspend fun shutdown(): JsonElement = connection.request("shutdown", JsonObject())

    fun exit() = connection.notify("exit", JsonObject())

    private fun initializeParams() = JsonObject().apply {
        addProperty("processId", ProcessHandle.current().pid().toInt())
        add("clientInfo", editorPluginInfo.toJson())
        add(
            "capabilities",
            JsonObject().apply {
                add("workspace", JsonObject().apply { addProperty("configuration", true) })
                add("textDocument", JsonObject().apply { add("inlineCompletion", JsonObject()) })
                add(
                    "window",
                    JsonObject().apply {
                        add("showDocument", JsonObject().apply { addProperty("support", true) })
                    },
                )
            },
        )
        add(
            "initializationOptions",
            JsonObject().apply {
                add("editorInfo", editorInfo.toJson())
                add("editorPluginInfo", editorPluginInfo.toJson())
            },
        )
    }

    private fun configurationFor(params: JsonElement?): JsonElement {
        val items = (params as? JsonObject)?.getAsJsonArray("items")
        val answer = JsonArray()
        repeat(items?.size() ?: 1) { answer.add(JsonObject()) }
        return answer
    }

    private fun showDocument(params: JsonElement?): JsonElement {
        (params as? JsonObject)?.string("uri")?.let(openDocument)
        return JsonObject().apply { addProperty("success", true) }
    }

    private fun statusChange(params: JsonElement?): CopilotStatusChange {
        val status = params as? JsonObject
        return CopilotStatusChange(
            kind = status?.string("kind") ?: UNKNOWN_STATUS,
            message = status?.string("message"),
            busy = status?.get("busy")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }

    private fun parseItems(result: JsonElement): List<InlineCompletionItem> {
        val items = (result as? JsonObject)?.getAsJsonArray("items") ?: return emptyList()
        return items.mapNotNull { parseItem(it as? JsonObject ?: return@mapNotNull null) }
    }

    private fun parseItem(item: JsonObject): InlineCompletionItem? {
        val insertText = item.string("insertText") ?: return null
        val range = parseRange(item.getAsJsonObject("range")) ?: return null
        val command = item.getAsJsonObject("command")
        val arguments = command?.getAsJsonArray("arguments")
            ?.mapNotNull { argument -> argument.takeIf { it.isJsonPrimitive }?.asString }
            ?: emptyList()
        return InlineCompletionItem(insertText, range, command?.string("command"), arguments)
    }

    private fun parseRange(range: JsonObject?): LspRange? {
        val start = parsePosition(range?.getAsJsonObject("start")) ?: return null
        val end = parsePosition(range?.getAsJsonObject("end")) ?: return null
        return LspRange(start, end)
    }

    private fun parsePosition(position: JsonObject?): LspPosition? {
        val line = position?.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val character = position.get("character")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        return LspPosition(line, character)
    }

    companion object {
        const val WORKSPACE_CONFIGURATION = "workspace/configuration"
        const val SHOW_MESSAGE_REQUEST = "window/showMessageRequest"
        const val SHOW_DOCUMENT = "window/showDocument"
        const val DID_CHANGE_STATUS = "didChangeStatus"
        const val ACCEPT_COMPLETION_COMMAND = "github.copilot.didAcceptCompletionItem"

        private const val UNKNOWN_STATUS = "Unknown"
        private const val DEFAULT_TAB_SIZE = 4
        private const val STATUS_BUFFER = 32
        private const val UNKNOWN_VERSION = "0.0.0"
        private const val PLUGIN_NAME = "TUILaunch"

        fun ideEditorInfo(): CopilotEditorInfo {
            val applicationInfo = ApplicationInfo.getInstance()
            return CopilotEditorInfo(applicationInfo.versionName, applicationInfo.fullVersion)
        }

        fun pluginEditorInfo(): CopilotEditorInfo {
            val ownClassLoader = CopilotLanguageServer::class.java.classLoader as? PluginAwareClassLoader
            return CopilotEditorInfo(PLUGIN_NAME, ownClassLoader?.pluginDescriptor?.version ?: UNKNOWN_VERSION)
        }
    }
}

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

private fun CopilotEditorInfo.toJson() = JsonObject().apply {
    addProperty("name", name)
    addProperty("version", version)
}

private fun LspPosition.toJson() = JsonObject().apply {
    addProperty("line", line)
    addProperty("character", character)
}

private fun LspRange.toJson() = JsonObject().apply {
    add("start", start.toJson())
    add("end", end.toJson())
}

private fun InlineCompletionItem.toJson() = JsonObject().apply {
    addProperty("insertText", insertText)
    add("range", range.toJson())
    if (acceptCommand != null) {
        add(
            "command",
            JsonObject().apply {
                addProperty("title", "Completion Accepted")
                addProperty("command", acceptCommand)
                add("arguments", JsonArray().apply { acceptArguments.forEach { add(it) } })
            },
        )
    }
}
