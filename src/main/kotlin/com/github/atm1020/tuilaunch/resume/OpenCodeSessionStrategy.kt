package com.github.atm1020.tuilaunch.resume

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpenCodeSessionStrategy(
    private val stateDirectory: Path,
    private val bundledDirectory: Path,
    private val theShellTakesAnEnvironmentPrefix: Boolean = !SystemInfo.isWindows,
) : AgentSessionStrategy {

    override fun prepareLaunch(tab: TabIdentity) {
        if (!theShellTakesAnEnvironmentPrefix) return
        BundledIntegrationFiles.ensure(TUI_CONFIG_RESOURCE, tuiConfigFile())
        BundledIntegrationFiles.ensure(SESSION_TRACKER_RESOURCE, sessionTrackerFile())
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> = emptyList()

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> {
        if (!theShellTakesAnEnvironmentPrefix) return emptyList()
        val reported = readSessionId(stateFile(tab)) ?: return emptyList()
        return listOf(SESSION_FLAG, reported)
    }

    override fun launchEnvironment(tab: TabIdentity): Map<String, String> {
        if (!theShellTakesAnEnvironmentPrefix) return emptyMap()
        return linkedMapOf(
            TUI_CONFIG_VARIABLE to tuiConfigFile().toAbsolutePath().toString(),
            STATE_FILE_VARIABLE to stateFile(tab).toAbsolutePath().toString(),
        )
    }

    override suspend fun cleanUp(tab: TabIdentity, remembered: RememberedSession) {
        AgentStateFiles.delete(stateFile(tab))
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun tuiConfigFile(): Path = bundledDirectory.resolve(BUNDLED_SUBDIRECTORY).resolve(TUI_CONFIG_FILE_NAME)

    fun sessionTrackerFile(): Path =
        bundledDirectory.resolve(BUNDLED_SUBDIRECTORY).resolve(SESSION_TRACKER_FILE_NAME)

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
        val reported = root.asJsonObject.nonBlankString(SESSION_ID_FIELD) ?: return null
        return reported.takeIf { SESSION_ID.matches(it) }
    }

    companion object {
        const val DIRECTORY_NAME = "opencode"
        const val SESSION_FLAG = "--session"
        const val TUI_CONFIG_VARIABLE = "OPENCODE_TUI_CONFIG"
        const val STATE_FILE_VARIABLE = "TUILAUNCH_OPENCODE_STATE"
        const val BUNDLED_SUBDIRECTORY = "opencode"
        const val TUI_CONFIG_FILE_NAME = "tui.json"
        const val SESSION_TRACKER_FILE_NAME = "tuilaunch-session-tracker.js"
        const val TUI_CONFIG_RESOURCE = "/integrations/opencode/$TUI_CONFIG_FILE_NAME"
        const val SESSION_TRACKER_RESOURCE = "/integrations/opencode/$SESSION_TRACKER_FILE_NAME"
        private const val STATE_FILE_SUFFIX = ".json"
        private const val SESSION_ID_FIELD = "sessionId"
        private val SESSION_ID = Regex("^ses_[0-9A-Za-z]{26}$")
    }
}
