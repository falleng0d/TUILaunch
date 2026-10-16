package com.github.atm1020.tuilaunch.resume

import java.nio.file.Path

class OpenCodeSessionStrategy(
    private val stateDirectory: Path,
) : AgentSessionStrategy {

    override fun launchArguments(tab: TabIdentity): List<String> = listOf(SESSION_FLAG, sessionIdOf(tab))

    override fun restoreArguments(tab: TabIdentity): List<String> = listOf(SESSION_FLAG, sessionToRestore(tab))

    override suspend fun cleanUp(tab: TabIdentity) {
        AgentStateFiles.delete(stateFile(tab))
    }

    fun sessionToRestore(tab: TabIdentity): String = readSessionId(stateFile(tab)) ?: sessionIdOf(tab)

    fun sessionIdOf(tab: TabIdentity): String =
        SESSION_ID_PREFIX + tab.tabUuid.filter { it.isLetterOrDigit() }.lowercase()

    fun stateFile(tab: TabIdentity): Path =
        stateDirectory.resolve(DIRECTORY_NAME).resolve("${tab.tabUuid}$STATE_FILE_SUFFIX")

    fun readSessionId(stateFile: Path): String? {
        val record = AgentStateFiles.readJsonObject(stateFile) ?: return null
        val reported = record.nonBlankString(SESSION_ID_FIELD) ?: return null
        return reported.takeIf { SESSION_ID.matches(it) }
    }

    companion object {
        const val DIRECTORY_NAME = "opencode"
        const val SESSION_FLAG = "--session"
        const val SESSION_ID_PREFIX = "ses_"
        private const val STATE_FILE_SUFFIX = ".json"
        private const val SESSION_ID_FIELD = "sessionId"
        private val SESSION_ID = Regex("^ses_[0-9A-Za-z]{26}$")
    }
}
