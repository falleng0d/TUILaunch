package com.github.atm1020.tuilaunch.model

internal const val ACTION_ID_PREFIX = "TUILauncher."

data class TuiAppConfig(
    var name: String = "",
    var command: String = "",
    var options: String = "",
    var description: String = "",
    var windowWidth: Int? = null,
    var windowHeight: Int? = null,
    var shortcutKeyCode: Int? = null,
)

