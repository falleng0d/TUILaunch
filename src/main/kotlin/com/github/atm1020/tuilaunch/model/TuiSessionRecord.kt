package com.github.atm1020.tuilaunch.model

data class TuiSessionRecord(
    var appName: String = "",
    var title: String = "",
    var selected: Boolean = false,
    var tabUuid: String? = null,
    var agentSessionId: String? = null,
    var agentCliKind: String? = null,
)
