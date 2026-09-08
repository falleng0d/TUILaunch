package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class CodexSessionStrategy(private val stateDirectory: Path) : AgentSessionStrategy {
    override fun prepareLaunch(tab: TabIdentity) {
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> = hookArguments(stateFile(tab))

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> {
        val file = stateFile(tab)
        val hookArguments = hookArguments(file)
        if (hookArguments.isEmpty()) return emptyList()
        val sessionId = readSessionId(file) ?: return hookArguments
        return listOf(RESUME_SUBCOMMAND, sessionId) + hookArguments
    }

    override suspend fun cleanUp(tab: TabIdentity, remembered: RememberedSession) {
        AgentStateFiles.delete(stateFile(tab))
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun canManage(tab: TabIdentity): Boolean = isShellSafe(stateFile(tab).toString())

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

    fun readSessionId(stateFile: Path): String? {
        val lines = try {
            if (!Files.isRegularFile(stateFile)) return null
            Files.readAllLines(stateFile)
        } catch (_: IOException) {
            return null
        }
        return lines.asReversed().firstNotNullOfOrNull { resumableSessionIdIn(it) }
    }

    private fun resumableSessionIdIn(line: String): String? {
        if (line.isBlank()) return null
        val root = try {
            JsonParser.parseString(line)
        } catch (_: RuntimeException) {
            return null
        }
        if (!root.isJsonObject) return null
        val record = root.asJsonObject
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
    }
}
