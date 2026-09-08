package com.github.atm1020.tuilaunch.resume

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.math.abs

object ClaudeProjectPath {
    private const val MAX_ESCAPED_LENGTH = 200
    private const val ABSOLUTE_MIN_INT = 2147483648L

    fun escape(path: String): String {
        val escaped = buildString(path.length) {
            for (character in path) {
                append(if (character.isAlphanumericAscii()) character else '-')
            }
        }
        if (escaped.length <= MAX_ESCAPED_LENGTH) return escaped
        return escaped.take(MAX_ESCAPED_LENGTH) + "-" + base36Hash(path)
    }

    private fun base36Hash(path: String): String {
        var hash = 0
        for (character in path) {
            hash = (hash shl 5) - hash + character.code
        }
        val magnitude = if (hash == Int.MIN_VALUE) ABSOLUTE_MIN_INT else abs(hash).toLong()
        return magnitude.toString(36)
    }

    private fun Char.isAlphanumericAscii(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

class ClaudeSessionStrategy(
    private val claudeHome: Path,
    private val stateDirectory: Path,
    private val hookAllowed: Boolean = true,
) : AgentSessionStrategy {
    override fun prepareLaunch(tab: TabIdentity) {
        if (!hookAllowed) return
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> =
        settingsArguments(tab) + listOf(SESSION_ID_FLAG, tab.tabUuid)

    override fun restoreArguments(tab: TabIdentity): List<String> {
        if (!hookAllowed) return theSessionOfTheTabItself(tab)
        val reported = readSessionId(stateFile(tab))
        if (reported != null) return settingsArguments(tab) + listOf(RESUME_FLAG, reported)
        return settingsArguments(tab) + theSessionOfTheTabItself(tab)
    }

    override suspend fun cleanUp(tab: TabIdentity) {
        if (!hookAllowed) return
        AgentStateFiles.delete(stateFile(tab))
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun readSessionId(stateFile: Path): String? {
        val record = AgentStateFiles.readJsonObject(stateFile) ?: return null
        if (!theTranscriptIsStillThere(record)) return null
        return record.nonBlankString(SESSION_ID_FIELD)
    }

    fun transcriptFile(tab: TabIdentity): Path = claudeHome
        .resolve("projects")
        .resolve(ClaudeProjectPath.escape(tab.projectPath))
        .resolve("${tab.tabUuid}.jsonl")

    private fun settingsArguments(tab: TabIdentity): List<String> {
        if (!hookAllowed) return emptyList()
        return listOf(SETTINGS_FLAG, settingsJsonFor(stateFile(tab)))
    }

    private fun theSessionOfTheTabItself(tab: TabIdentity): List<String> =
        if (Files.isRegularFile(transcriptFile(tab))) {
            listOf(RESUME_FLAG, tab.tabUuid)
        } else {
            listOf(SESSION_ID_FLAG, tab.tabUuid)
        }

    private fun theTranscriptIsStillThere(record: JsonObject): Boolean {
        val transcriptPath = record.nonBlankString(TRANSCRIPT_PATH_FIELD) ?: return true
        return try {
            Files.isRegularFile(Path.of(transcriptPath))
        } catch (_: InvalidPathException) {
            false
        }
    }

    private fun settingsJsonFor(stateFile: Path): String {
        val handler = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", POSIX_SHELL)
            add(
                "args",
                JsonArray().apply {
                    add(SHELL_COMMAND_FLAG)
                    add(WRITE_THE_HOOK_INPUT)
                    add(stateFile.toString())
                },
            )
            addProperty("timeout", HOOK_TIMEOUT_SECONDS)
        }
        val group = JsonObject().apply { add("hooks", JsonArray().apply { add(handler) }) }
        val events = JsonObject().apply { add(SESSION_START_EVENT, JsonArray().apply { add(group) }) }
        return MINIFIED_JSON.toJson(JsonObject().apply { add("hooks", events) })
    }

    companion object {
        const val DIRECTORY_NAME = "claude"
        const val SETTINGS_FLAG = "--settings"
        const val SESSION_ID_FLAG = "--session-id"
        const val RESUME_FLAG = "--resume"
        private const val STATE_FILE_SUFFIX = ".json"
        private const val SESSION_ID_FIELD = "session_id"
        private const val TRANSCRIPT_PATH_FIELD = "transcript_path"
        private const val SESSION_START_EVENT = "SessionStart"
        private const val POSIX_SHELL = "/bin/sh"
        private const val SHELL_COMMAND_FLAG = "-c"
        private const val STATE_FILE_ARGUMENT = "\$0"
        private const val HOOK_TIMEOUT_SECONDS = 5
        private const val WRITE_THE_HOOK_INPUT =
            "cat > \"$STATE_FILE_ARGUMENT.tmp\" && mv \"$STATE_FILE_ARGUMENT.tmp\" \"$STATE_FILE_ARGUMENT\""
        private val MINIFIED_JSON: Gson = GsonBuilder().disableHtmlEscaping().create()
    }
}
