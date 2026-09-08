package com.github.atm1020.tuilaunch.resume

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CodexSessionStrategy(private val stateDirectory: Path) : AgentSessionStrategy {
    override fun prepareLaunch(tab: TabIdentity) {
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> = hookArguments(stateFile(tab))

    override fun restoreArguments(tab: TabIdentity): List<String> {
        val file = stateFile(tab)
        val hookArguments = hookArguments(file)
        if (hookArguments.isEmpty()) return emptyList()
        val sessionId = readSessionId(file) ?: return hookArguments
        return listOf(RESUME_SUBCOMMAND, sessionId) + hookArguments
    }

    override suspend fun cleanUp(tab: TabIdentity) {
        AgentStateFiles.delete(stateFile(tab))
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun hookArguments(stateFile: Path): List<String> {
        val path = stateFile.toString()
        if (!isShellSafe(path)) return emptyList()
        return listOf(
            CONFIG_OVERRIDE,
            hookToml(SESSION_START_EVENT, path),
            CONFIG_OVERRIDE,
            hookToml(USER_PROMPT_SUBMIT_EVENT, path),
            BYPASS_HOOK_TRUST,
        )
    }

    fun readSessionId(stateFile: Path): String? =
        newestRecords(stateFile).asReversed().firstNotNullOfOrNull { resumableSessionIdIn(it) }

    private fun newestRecords(stateFile: Path): List<String> = try {
        if (!Files.isRegularFile(stateFile)) {
            emptyList()
        } else {
            Files.newByteChannel(stateFile, StandardOpenOption.READ).use { channel ->
                val start = maxOf(0L, channel.size() - TAIL_BYTES)
                channel.position(start)
                val buffer = ByteBuffer.allocate((channel.size() - start).toInt())
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                val tail = String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).split('\n')
                if (start == 0L) tail else tail.drop(1)
            }
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun resumableSessionIdIn(line: String): String? {
        val record = AgentStateFiles.jsonObjectIn(line) ?: return null
        if (record.nonBlankString(TRANSCRIPT_PATH_FIELD) == null) return null
        return record.nonBlankString(SESSION_ID_FIELD)
    }

    private fun isShellSafe(path: String): Boolean = path.none { it in SHELL_UNSAFE_CHARACTERS }

    private fun hookToml(event: String, path: String): String =
        """hooks.$event=[{hooks=[{type="command",command="{ cat; echo; } >> \"$path\"",async=true,timeout=5}]}]"""

    companion object {
        const val DIRECTORY_NAME = "codex"
        const val BYPASS_HOOK_TRUST = "--dangerously-bypass-hook-trust"
        const val CONFIG_OVERRIDE = "-c"
        const val RESUME_SUBCOMMAND = "resume"
        private const val STATE_FILE_SUFFIX = ".jsonl"
        private const val SESSION_ID_FIELD = "session_id"
        private const val TRANSCRIPT_PATH_FIELD = "transcript_path"
        private const val SESSION_START_EVENT = "SessionStart"
        private const val USER_PROMPT_SUBMIT_EVENT = "UserPromptSubmit"
        private const val SHELL_UNSAFE_CHARACTERS = "\"\\\$`\n"
        private const val TAIL_BYTES = 64L * 1024
    }
}
