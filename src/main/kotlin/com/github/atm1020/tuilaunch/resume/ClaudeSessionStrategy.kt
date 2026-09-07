package com.github.atm1020.tuilaunch.resume

import java.nio.file.Files
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

class ClaudeSessionStrategy(private val claudeHome: Path) : AgentSessionStrategy {
    override fun launchArguments(tab: TabIdentity): List<String> = listOf("--session-id", tab.tabUuid)

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> =
        if (Files.isRegularFile(transcriptFile(tab))) listOf("--resume", tab.tabUuid) else launchArguments(tab)

    fun transcriptFile(tab: TabIdentity): Path = claudeHome
        .resolve("projects")
        .resolve(ClaudeProjectPath.escape(tab.projectPath))
        .resolve("${tab.tabUuid}.jsonl")
}
