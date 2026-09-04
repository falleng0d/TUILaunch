package com.github.atm1020.tuilaunch.copilot

sealed class CopilotServerState {
    data object Stopped : CopilotServerState()

    data class NotConfigured(val reason: String) : CopilotServerState()

    data object Starting : CopilotServerState()

    data class Ready(val user: String?) : CopilotServerState()

    data object NotSignedIn : CopilotServerState()

    data class Failed(val reason: String) : CopilotServerState()
}
