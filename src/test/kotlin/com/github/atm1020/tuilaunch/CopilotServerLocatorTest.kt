package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotServerLocation
import com.github.atm1020.tuilaunch.copilot.CopilotServerLocator
import com.github.atm1020.tuilaunch.copilot.CopilotServerPlatform
import com.github.atm1020.tuilaunch.copilot.CopilotServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class CopilotServerLocatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val linuxOnX64 = CopilotServerPlatform("linux", "x64")

    @Test
    fun `a configured path wins over everything else`() {
        val configured = executable("configured", "copilot-language-server")
        val onPath = executable("path", "copilot-language-server")

        val location = CopilotServerLocator.locate(
            explicitPath = configured.toString(),
            copilotPluginPath = pluginWithBinary(linuxOnX64),
            pathLookup = { onPath },
            platform = linuxOnX64,
        )

        assertEquals(CopilotServerLocation.Found(configured, CopilotServerSource.EXPLICIT_PATH), location)
    }

    @Test
    fun `a configured path that does not exist is reported`() {
        val missing = temporaryFolder.root.toPath().resolve("nowhere/copilot-language-server")

        val location = CopilotServerLocator.locate(
            explicitPath = missing.toString(),
            copilotPluginPath = pluginWithBinary(linuxOnX64),
            pathLookup = { executable("path", "copilot-language-server") },
            platform = linuxOnX64,
        )

        val reason = (location as CopilotServerLocation.NotFound).reason
        assertTrue(reason.contains(missing.toString()))
        assertTrue(reason.contains("not executable"))
    }

    @Test
    fun `a configured path that is not a path at all is reported`() {
        val location = CopilotServerLocator.locate(
            explicitPath = "/opt/copilot/copilot\u0000language-server",
            copilotPluginPath = pluginWithBinary(linuxOnX64),
            pathLookup = { executable("path", "copilot-language-server") },
            platform = linuxOnX64,
        )

        val reason = (location as CopilotServerLocation.NotFound).reason
        assertTrue(reason.contains("is not a valid path"))
    }

    @Test
    fun `the copilot plugin binary is used when no path is configured`() {
        val pluginPath = pluginWithBinary(linuxOnX64)

        val location = CopilotServerLocator.locate(
            explicitPath = "",
            copilotPluginPath = pluginPath,
            pathLookup = { null },
            platform = linuxOnX64,
        )

        assertEquals(
            CopilotServerLocation.Found(
                pluginPath.resolve("copilot-agent/native/linux-x64/copilot-language-server"),
                CopilotServerSource.COPILOT_PLUGIN,
            ),
            location,
        )
    }

    @Test
    fun `the copilot plugin layout is found for the running os and architecture`() {
        val platform = CopilotServerLocator.currentPlatform()
        val pluginPath = pluginWithBinary(platform)

        val location = CopilotServerLocator.locate(
            explicitPath = "",
            copilotPluginPath = pluginPath,
            pathLookup = { null },
        )

        assertEquals(
            CopilotServerLocation.Found(
                pluginPath.resolve(platform.pluginRelativePath),
                CopilotServerSource.COPILOT_PLUGIN,
            ),
            location,
        )
    }

    @Test
    fun `the executable on PATH is the last resort`() {
        val onPath = executable("path", "copilot-language-server")
        val requestedNames = ArrayList<String>()

        val location = CopilotServerLocator.locate(
            explicitPath = "",
            copilotPluginPath = temporaryFolder.newFolder("copilot-plugin-without-binary").toPath(),
            pathLookup = { name ->
                requestedNames += name
                onPath
            },
            platform = linuxOnX64,
        )

        assertEquals(CopilotServerLocation.Found(onPath, CopilotServerSource.SYSTEM_PATH), location)
        assertEquals(listOf("copilot-language-server"), requestedNames)
    }

    @Test
    fun `nothing found lists what was tried`() {
        val pluginPath = temporaryFolder.newFolder("copilot-plugin-empty").toPath()

        val location = CopilotServerLocator.locate(
            explicitPath = "",
            copilotPluginPath = pluginPath,
            pathLookup = { null },
            platform = linuxOnX64,
        )

        val reason = (location as CopilotServerLocation.NotFound).reason
        assertTrue(reason.contains(pluginPath.resolve("copilot-agent/native/linux-x64/copilot-language-server").toString()))
        assertTrue(reason.contains("copilot-language-server on PATH"))
    }

    @Test
    fun `a missing copilot plugin is named in the reason`() {
        val location = CopilotServerLocator.locate(
            explicitPath = "",
            copilotPluginPath = null,
            pathLookup = { null },
            platform = linuxOnX64,
        )

        val reason = (location as CopilotServerLocation.NotFound).reason
        assertTrue(reason.contains("a GitHub Copilot plugin that ships a language server, which is not installed"))
        assertTrue(reason.contains("copilot-language-server on PATH"))
    }

    @Test
    fun `the windows layout looks for an exe`() {
        val windows = CopilotServerPlatform("win32", "x64")

        assertEquals("copilot-language-server.exe", windows.executableName)
        assertEquals("copilot-agent/native/win32-x64/copilot-language-server.exe", windows.pluginRelativePath)
    }

    private fun pluginWithBinary(platform: CopilotServerPlatform): Path {
        val pluginPath = temporaryFolder.newFolder("copilot-plugin-${platform.os}-${platform.arch}").toPath()
        val binary = pluginPath.resolve(platform.pluginRelativePath)
        binary.parent.toFile().mkdirs()
        binary.toFile().writeText("binary")
        binary.toFile().setExecutable(true)
        return pluginPath
    }

    private fun executable(directory: String, name: String): Path {
        val file = temporaryFolder.newFolder(directory).toPath().resolve(name)
        file.toFile().writeText("binary")
        file.toFile().setExecutable(true)
        return file
    }
}
