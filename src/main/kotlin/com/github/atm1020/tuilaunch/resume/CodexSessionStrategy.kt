package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser
import com.intellij.openapi.util.SystemInfo
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CodexSessionStrategy(
    private val stateDirectory: Path,
    private val theShellTakesAnEnvironmentPrefix: Boolean = !SystemInfo.isWindows,
) : AgentSessionStrategy {
    override fun prepareLaunch(tab: TabIdentity) {
        if (!theShellTakesAnEnvironmentPrefix) return
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> = hookArguments()

    override fun restoreArguments(tab: TabIdentity): List<String> {
        val hookArguments = hookArguments()
        if (hookArguments.isEmpty()) return emptyList()
        val sessionId = readSessionId(stateFile(tab)) ?: return hookArguments
        return listOf(RESUME_SUBCOMMAND, sessionId) + hookArguments
    }

    override fun launchEnvironment(tab: TabIdentity): Map<String, String> {
        if (!theShellTakesAnEnvironmentPrefix) return emptyMap()
        return linkedMapOf(STATE_FILE_VARIABLE to stateFile(tab).toAbsolutePath().toString())
    }

    override suspend fun cleanUp(tab: TabIdentity) {
        AgentStateFiles.delete(stateFile(tab))
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun hookArguments(): List<String> {
        if (!theShellTakesAnEnvironmentPrefix) return emptyList()
        return listOf(
            CONFIG_OVERRIDE,
            hookToml(SESSION_START_EVENT),
            CONFIG_OVERRIDE,
            hookToml(USER_PROMPT_SUBMIT_EVENT),
        )
    }

    fun readSessionId(stateFile: Path): String? =
        recordsIn(newestBytesOf(stateFile)).asReversed().firstNotNullOfOrNull { resumableSessionIdIn(it) }

    private fun newestBytesOf(stateFile: Path): String = try {
        if (!Files.isRegularFile(stateFile)) {
            ""
        } else {
            Files.newByteChannel(stateFile, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val start = maxOf(0L, size - TAIL_BYTES)
                channel.position(start)
                val buffer = ByteBuffer.allocate((size - start).toInt())
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
            }
        }
    } catch (_: IOException) {
        ""
    } catch (_: IllegalArgumentException) {
        ""
    }

    private fun recordsIn(tail: String): List<JsonObject> {
        val records = mutableListOf<JsonObject>()
        var carriedFromTheLineBefore = ""
        for (line in tail.lineSequence()) {
            val chunk = if (carriedFromTheLineBefore.isEmpty()) line else "$carriedFromTheLineBefore\n$line"
            val parsed = recordsAtTheStartOf(chunk)
            records += parsed.records
            carriedFromTheLineBefore = if (parsed.endedInsideARecord) chunk else ""
            if (parsed.stoppedOnTextItCannotRead) records += lastRecordOf(chunk)
        }
        return records
    }

    private fun recordsAtTheStartOf(chunk: String): ParsedRecords {
        val records = mutableListOf<JsonObject>()
        val values = JsonStreamParser(chunk)
        while (true) {
            val value = try {
                if (!values.hasNext()) return ParsedRecords(records, false, false)
                values.next()
            } catch (failure: RuntimeException) {
                val endedInsideARecord = failure.cause is EOFException
                return ParsedRecords(records, endedInsideARecord, !endedInsideARecord)
            }
            if (value.isJsonObject) records.add(value.asJsonObject)
        }
    }

    private fun lastRecordOf(chunk: String): List<JsonObject> {
        if (!chunk.contains(RECORD_START)) return emptyList()
        return recordsAtTheStartOf(RECORD_START + chunk.substringAfterLast(RECORD_START)).records
    }

    private fun resumableSessionIdIn(record: JsonObject): String? {
        if (record.nonBlankString(TRANSCRIPT_PATH_FIELD) == null) return null
        return record.nonBlankString(SESSION_ID_FIELD)
    }

    private class ParsedRecords(
        val records: List<JsonObject>,
        val endedInsideARecord: Boolean,
        val stoppedOnTextItCannotRead: Boolean,
    )

    private fun hookToml(event: String): String =
        """hooks.$event=[{hooks=[{type="command",command="$HOOK_COMMAND",async=true,timeout=5}]}]"""

    companion object {
        const val DIRECTORY_NAME = "codex"
        const val CONFIG_OVERRIDE = "-c"
        const val RESUME_SUBCOMMAND = "resume"
        const val STATE_FILE_VARIABLE = "TUILAUNCH_CODEX_STATE"
        private const val HOOK_COMMAND = "{ cat; echo; } >> \\\"\$$STATE_FILE_VARIABLE\\\""
        private const val STATE_FILE_SUFFIX = ".jsonl"
        private const val SESSION_ID_FIELD = "session_id"
        private const val TRANSCRIPT_PATH_FIELD = "transcript_path"
        private const val SESSION_START_EVENT = "SessionStart"
        private const val USER_PROMPT_SUBMIT_EVENT = "UserPromptSubmit"
        private const val RECORD_START = "{\""
        private const val TAIL_BYTES = 64L * 1024
    }
}
