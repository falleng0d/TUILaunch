package com.github.atm1020.tuilaunch.resume

import java.nio.file.Path

class OpenCodeSessionStrategy(
    private val stateDirectory: Path,
    private val bundledDirectory: Path,
    private val server: OpenCodeServer? = null,
) : AgentSessionStrategy {

    @Volatile
    private var serverAddress: String? = null

    override val launchWaitsForPreparation: Boolean
        get() = server != null

    override suspend fun prepareLaunchInBackground(tab: TabIdentity, command: AgentCommand) {
        serverAddress = server?.acquire(tab.tabUuid, command)
    }

    override fun prepareLaunch(tab: TabIdentity) {
        BundledIntegrationFiles.ensure(TUI_CONFIG_RESOURCE, tuiConfigFile())
        BundledIntegrationFiles.ensure(SESSION_TRACKER_RESOURCE, sessionTrackerFile())
        AgentStateFiles.createDirectoryFor(stateFile(tab))
    }

    override fun launchArguments(tab: TabIdentity): List<String> = attachArguments(tab)

    override fun restoreArguments(tab: TabIdentity): List<String> {
        val attach = attachArguments(tab)
        val reported = readSessionId(stateFile(tab)) ?: return attach
        return attach + listOf(SESSION_FLAG, reported)
    }

    private fun attachArguments(tab: TabIdentity): List<String> {
        val address = serverAddress ?: return emptyList()
        return listOf(ATTACH_SUBCOMMAND, address, DIRECTORY_FLAG, tab.projectPath)
    }

    override fun launchEnvironment(tab: TabIdentity): Map<String, String> {
        if (!BundledIntegrationFiles.areOnDisk(tuiConfigFile(), sessionTrackerFile())) return emptyMap()
        return linkedMapOf(
            TUI_CONFIG_VARIABLE to tuiConfigFile().toAbsolutePath().toString(),
            STATE_FILE_VARIABLE to stateFile(tab).toAbsolutePath().toString(),
        )
    }

    override suspend fun cleanUp(tab: TabIdentity) {
        AgentStateFiles.delete(stateFile(tab))
    }

    override fun tabClosed(tab: TabIdentity) {
        server?.release(tab.tabUuid)
    }

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun tuiConfigFile(): Path = bundledDirectory.resolve(BUNDLED_SUBDIRECTORY).resolve(TUI_CONFIG_FILE_NAME)

    fun sessionTrackerFile(): Path =
        bundledDirectory.resolve(BUNDLED_SUBDIRECTORY).resolve(SESSION_TRACKER_FILE_NAME)

    fun readSessionId(stateFile: Path): String? {
        val record = AgentStateFiles.readJsonObject(stateFile) ?: return null
        val reported = record.nonBlankString(SESSION_ID_FIELD) ?: return null
        return reported.takeIf { SESSION_ID.matches(it) }
    }

    companion object {
        const val DIRECTORY_NAME = "opencode"
        const val ATTACH_SUBCOMMAND = "attach"
        const val DIRECTORY_FLAG = "--dir"
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
