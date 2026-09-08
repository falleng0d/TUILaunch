package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class TabIdentity(
    val tabUuid: String,
    val projectPath: String,
    val projectHash: String,
)

interface AgentSessionStrategy {
    fun prepareLaunch(tab: TabIdentity) {
    }

    fun launchArguments(tab: TabIdentity): List<String>

    fun restoreArguments(tab: TabIdentity): List<String>

    fun launchEnvironment(tab: TabIdentity): Map<String, String> = emptyMap()

    fun restoreEnvironment(tab: TabIdentity): Map<String, String> = launchEnvironment(tab)

    suspend fun cleanUp(tab: TabIdentity) {
    }
}

internal fun JsonObject.nonBlankString(field: String): String? {
    val value = get(field) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString.takeIf { it.isNotBlank() }
}

object AgentStateFiles {
    private val STATE_FILE_NAME = Regex("""^([^.]+)\.(json|jsonl)(\..+)?$""")

    fun createDirectoryFor(stateFile: Path) {
        val parent = stateFile.parent ?: return
        try {
            Files.createDirectories(parent)
        } catch (_: IOException) {
        }
    }

    fun readJsonObject(stateFile: Path): JsonObject? {
        val text = try {
            if (!Files.isRegularFile(stateFile)) return null
            Files.readString(stateFile)
        } catch (_: IOException) {
            return null
        }
        return jsonObjectIn(text)
    }

    fun jsonObjectIn(text: String): JsonObject? {
        if (text.isBlank()) return null
        val parsed = try {
            JsonParser.parseString(text)
        } catch (_: RuntimeException) {
            return null
        }
        return if (parsed.isJsonObject) parsed.asJsonObject else null
    }

    suspend fun delete(stateFile: Path) {
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(stateFile)
            } catch (_: IOException) {
            }
        }
    }

    suspend fun deleteStateFilesExcept(directory: Path, tabUuids: Set<String>) {
        withContext(Dispatchers.IO) {
            orphanedStateFiles(directory, tabUuids).forEach { file ->
                try {
                    Files.deleteIfExists(file)
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun orphanedStateFiles(directory: Path, tabUuids: Set<String>): List<Path> = try {
        Files.newDirectoryStream(directory).use { entries ->
            entries.filter { belongsToATabThatIsGone(it, tabUuids) }
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun belongsToATabThatIsGone(file: Path, tabUuids: Set<String>): Boolean {
        val name = STATE_FILE_NAME.matchEntire(file.fileName.toString()) ?: return false
        return name.groupValues[1] !in tabUuids
    }
}

data class AgentSessionEnvironment(
    val homeDirectory: Path,
    val stateDirectory: Path,
    val bundledDirectory: Path,
    val claudeConfigDir: String? = null,
    val piCodingAgentDir: String? = null,
    val theShellIsPosix: Boolean = !SystemInfo.isWindows,
) {
    val claudeHome: Path
        get() = overriddenDirectory(claudeConfigDir) ?: homeDirectory.resolve(".claude")

    val ompRoot: Path
        get() = overriddenDirectory(piCodingAgentDir) ?: homeDirectory.resolve(".omp").resolve("agent")

    private fun overriddenDirectory(value: String?): Path? =
        value?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    companion object {
        const val CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR"
        const val PI_CODING_AGENT_DIR = "PI_CODING_AGENT_DIR"

        fun fromSystem(stateDirectory: Path, bundledDirectory: Path): AgentSessionEnvironment =
            AgentSessionEnvironment(
                homeDirectory = Path.of(System.getProperty("user.home")),
                stateDirectory = stateDirectory,
                bundledDirectory = bundledDirectory,
                claudeConfigDir = System.getenv(CLAUDE_CONFIG_DIR),
                piCodingAgentDir = System.getenv(PI_CODING_AGENT_DIR),
            )
    }
}

object AgentSessionStrategies {
    fun forKind(
        kind: AgentCliKind,
        environment: AgentSessionEnvironment,
        hookAllowed: Boolean = true,
    ): AgentSessionStrategy = when (kind) {
        AgentCliKind.CLAUDE -> ClaudeSessionStrategy(
            claudeHome = environment.claudeHome,
            stateDirectory = environment.stateDirectory,
            hookAllowed = hookAllowed && environment.theShellIsPosix,
        )

        AgentCliKind.CODEX -> CodexSessionStrategy(environment.stateDirectory)

        AgentCliKind.OPENCODE -> OpenCodeSessionStrategy(
            stateDirectory = environment.stateDirectory,
            bundledDirectory = environment.bundledDirectory,
            theShellTakesAnEnvironmentPrefix = environment.theShellIsPosix,
        )

        AgentCliKind.OMP -> OmpSessionStrategy(
            root = environment.ompRoot,
            bundledDirectory = environment.bundledDirectory,
            hookAllowed = hookAllowed,
        )
    }
}
