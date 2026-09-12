package com.github.atm1020.tuilaunch.resume

import com.intellij.openapi.util.SystemInfo

/**
 * Codex hands a hook command to a shell of its own choosing rather than running it directly:
 * `pwsh -NoProfile -Command <command>` on Windows and a POSIX shell everywhere else.
 */
enum class HookShell {
    POSIX {
        override fun appendStdinTo(variable: String): String = "{ cat; echo; } >> \\\"\$$variable\\\""
    },

    POWERSHELL {
        override fun appendStdinTo(variable: String): String =
            "[IO.File]::AppendAllText(\$env:$variable, [Console]::In.ReadToEnd() + [Environment]::NewLine)"
    },
    ;

    /** Appends everything the hook receives on standard input, plus a line break, to the file named by [variable]. */
    abstract fun appendStdinTo(variable: String): String

    companion object {
        fun current(): HookShell = if (SystemInfo.isWindows) POWERSHELL else POSIX
    }
}
