package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class CodexSessionStrategy(private val stateDirectory: Path) : AgentSessionStrategy {
    override fun launchArguments(tab: TabIdentity): List<String> = hookArguments(stateFile(tab))

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> {
        val file = stateFile(tab)
        val hookArguments = hookArguments(file)
        if (hookArguments.isEmpty()) return emptyList()
        val sessionId = readSessionId(file) ?: return hookArguments
        return listOf(RESUME_SUBCOMMAND, sessionId) + hookArguments
    }

    override suspend fun cleanUp(tab: TabIdentity, remembered: RememberedSession) {
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(stateFile(tab))
            } catch (_: IOException) {
            }
        }
    }

    suspend fun deleteStateFilesExcept(tabUuids: Set<String>) {
        withContext(Dispatchers.IO) {
            orphanedStateFiles(tabUuids).forEach { file ->
                try {
                    Files.deleteIfExists(file)
                } catch (_: IOException) {
                }
            }
        }
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun canManage(tab: TabIdentity): Boolean = isShellSafe(stateFile(tab).toString())

    fun hookArguments(stateFile: Path): List<String> {
        val path = stateFile.toString()
        if (!isShellSafe(path)) return emptyList()
        if (!createParentDirectory(stateFile)) return emptyList()
        return listOf(BYPASS_HOOK_TRUST, CONFIG_OVERRIDE, sessionStartHookToml(path))
    }

    fun readSessionId(stateFile: Path): String? {
        val text = try {
            if (!Files.isRegularFile(stateFile)) return null
            Files.readString(stateFile)
        } catch (_: IOException) {
            return null
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (_: RuntimeException) {
            return null
        }
        if (!root.isJsonObject) return null
        val sessionId = root.asJsonObject.get(SESSION_ID_FIELD) ?: return null
        if (!sessionId.isJsonPrimitive || !sessionId.asJsonPrimitive.isString) return null
        return sessionId.asString.takeIf { it.isNotBlank() }
    }

    private fun orphanedStateFiles(tabUuids: Set<String>): List<Path> = try {
        Files.newDirectoryStream(stateDirectory.resolve(DIRECTORY_NAME), "*$STATE_FILE_SUFFIX").use { entries ->
            entries.filter { it.fileName.toString().removeSuffix(STATE_FILE_SUFFIX) !in tabUuids }
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun createParentDirectory(stateFile: Path): Boolean {
        val parent = stateFile.parent ?: return false
        return try {
            Files.createDirectories(parent)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun isShellSafe(path: String): Boolean = path.none { it in SHELL_UNSAFE_CHARACTERS }

    private fun sessionStartHookToml(path: String): String =
        """hooks.SessionStart=[{hooks=[{type="command",command="cat > \"$path\"",async=true,timeout=5}]}]"""

    companion object {
        const val DIRECTORY_NAME = "codex"
        const val BYPASS_HOOK_TRUST = "--dangerously-bypass-hook-trust"
        const val CONFIG_OVERRIDE = "-c"
        const val RESUME_SUBCOMMAND = "resume"
        private const val STATE_FILE_SUFFIX = ".json"
        private const val SESSION_ID_FIELD = "session_id"
        private const val SHELL_UNSAFE_CHARACTERS = "\"\\\$`\n"
    }
}
