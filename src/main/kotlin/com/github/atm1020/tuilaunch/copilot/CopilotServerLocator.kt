package com.github.atm1020.tuilaunch.copilot

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path

enum class CopilotServerSource {
    EXPLICIT_PATH,
    COPILOT_PLUGIN,
    SYSTEM_PATH,
}

sealed class CopilotServerLocation {
    data class Found(val path: Path, val source: CopilotServerSource) : CopilotServerLocation()

    data class NotFound(val reason: String) : CopilotServerLocation()
}

data class CopilotServerPlatform(val os: String, val arch: String) {
    val executableName: String
        get() = if (os == WINDOWS) "$EXECUTABLE_BASE_NAME.exe" else EXECUTABLE_BASE_NAME

    val pluginRelativePath: String
        get() = "${CopilotServerLocator.COPILOT_AGENT_DIRECTORY}/native/$os-$arch/$executableName"

    companion object {
        const val WINDOWS = "win32"
        const val EXECUTABLE_BASE_NAME = "copilot-language-server"
    }
}

object CopilotServerLocator {
    const val COPILOT_AGENT_DIRECTORY = "copilot-agent"

    fun currentPlatform(): CopilotServerPlatform = CopilotServerPlatform(currentOs(), currentArch())

    fun locate(
        explicitPath: String,
        copilotPluginPath: Path?,
        pathLookup: (String) -> Path?,
        platform: CopilotServerPlatform = currentPlatform(),
        isExecutable: (Path) -> Boolean = ::isExecutableFile,
    ): CopilotServerLocation {
        if (explicitPath.isNotBlank()) {
            val configured = runCatching { Path.of(explicitPath) }.getOrElse { invalid ->
                return CopilotServerLocation.NotFound(
                    "The configured GitHub Copilot language server path $explicitPath " +
                        "is not a valid path: ${invalid.message ?: invalid.javaClass.simpleName}"
                )
            }
            return if (isExecutable(configured)) {
                CopilotServerLocation.Found(configured, CopilotServerSource.EXPLICIT_PATH)
            } else {
                CopilotServerLocation.NotFound(
                    "The configured GitHub Copilot language server path $explicitPath " +
                        "does not exist or is not executable"
                )
            }
        }

        val attempts = mutableListOf<String>()

        val bundledWithPlugin = copilotPluginPath?.resolve(platform.pluginRelativePath)
        if (bundledWithPlugin == null) {
            attempts += "a GitHub Copilot plugin that ships a language server, which is not installed"
        } else if (isExecutable(bundledWithPlugin)) {
            return CopilotServerLocation.Found(bundledWithPlugin, CopilotServerSource.COPILOT_PLUGIN)
        } else {
            attempts += "the GitHub Copilot plugin binary at $bundledWithPlugin"
        }

        val onPath = pathLookup(platform.executableName)
        if (onPath != null && isExecutable(onPath)) {
            return CopilotServerLocation.Found(onPath, CopilotServerSource.SYSTEM_PATH)
        }
        attempts += "${platform.executableName} on PATH"

        return CopilotServerLocation.NotFound(
            "No GitHub Copilot language server found; tried ${attempts.joinToString(" and ")}"
        )
    }

    private fun isExecutableFile(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

    private fun currentOs(): String = when {
        SystemInfo.isMac -> "darwin"
        SystemInfo.isWindows -> CopilotServerPlatform.WINDOWS
        else -> "linux"
    }

    private fun currentArch(): String = if (CpuArch.isArm64()) "arm64" else "x64"
}
