package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotServerLocation
import com.github.atm1020.tuilaunch.copilot.CopilotServerSource
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.github.atm1020.tuilaunch.ui.copilotServerHintText
import com.github.atm1020.tuilaunch.ui.copilotStatusText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class CopilotSettingsTextTest {

    @Test
    fun `the hint names the binary that came with the GitHub Copilot plugin`() {
        val hint = copilotServerHintText(
            CopilotServerLocation.Found(
                Path.of("/plugins/github-copilot/copilot-agent/native/darwin-arm64/copilot-language-server"),
                CopilotServerSource.COPILOT_PLUGIN,
            )
        )

        assertEquals(
            "Found in the GitHub Copilot plugin: " +
                "/plugins/github-copilot/copilot-agent/native/darwin-arm64/copilot-language-server",
            hint,
        )
    }

    @Test
    fun `the hint names the binary found on PATH`() {
        val hint = copilotServerHintText(
            CopilotServerLocation.Found(
                Path.of("/usr/local/bin/copilot-language-server"),
                CopilotServerSource.SYSTEM_PATH,
            )
        )

        assertEquals("Found on PATH: /usr/local/bin/copilot-language-server", hint)
    }

    @Test
    fun `the hint names the configured binary`() {
        val hint = copilotServerHintText(
            CopilotServerLocation.Found(
                Path.of("/opt/copilot/copilot-language-server"),
                CopilotServerSource.EXPLICIT_PATH,
            )
        )

        assertEquals("Found at the configured path: /opt/copilot/copilot-language-server", hint)
    }

    @Test
    fun `the hint repeats what was tried when nothing was found`() {
        val hint = copilotServerHintText(
            CopilotServerLocation.NotFound("No GitHub Copilot language server found; tried copilot-language-server on PATH")
        )

        assertEquals(
            "Not found: No GitHub Copilot language server found; tried copilot-language-server on PATH",
            hint,
        )
    }

    @Test
    fun `a ready server reports the signed in user`() {
        assertEquals("Signed in as falleng0d", copilotStatusText(CopilotServerState.Ready("falleng0d")))
    }

    @Test
    fun `a ready server without a user name only reports being signed in`() {
        assertEquals("Signed in", copilotStatusText(CopilotServerState.Ready(null)))
    }

    @Test
    fun `a server without credentials reports the status it answered`() {
        assertEquals("Not signed in: NotSignedIn", copilotStatusText(CopilotServerState.NotSignedIn("NotSignedIn")))
        assertEquals("Not signed in: MaybeOK", copilotStatusText(CopilotServerState.NotSignedIn("MaybeOK")))
    }

    @Test
    fun `a missing binary is reported as not configured`() {
        assertEquals(
            "Not configured: nothing was found",
            copilotStatusText(CopilotServerState.NotConfigured("nothing was found")),
        )
    }

    @Test
    fun `a failure is reported with its reason`() {
        assertEquals(
            "Failed: the server exited",
            copilotStatusText(CopilotServerState.Failed("the server exited")),
        )
    }

    @Test
    fun `a server that is still starting keeps the checking text`() {
        assertEquals(CHECKING_TEXT, copilotStatusText(CopilotServerState.Starting))
        assertEquals(CHECKING_TEXT, copilotStatusText(CopilotServerState.Stopped))
    }

    private companion object {
        const val CHECKING_TEXT = "Checking…"
    }
}
