package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.terminal.runCommandArgs
import org.junit.Assert.assertEquals
import org.junit.Test

class RunCommandArgsTest {

    @Test
    fun `a posix shell runs the command with -c`() {
        assertEquals(listOf("-c", "claude"), runCommandArgs("/bin/zsh", "claude"))
        assertEquals(listOf("-c", "claude"), runCommandArgs("C:\\Program Files\\Git\\bin\\bash.exe", "claude"))
    }

    @Test
    fun `cmd runs the command with slash c`() {
        assertEquals(listOf("/c", "claude"), runCommandArgs("C:\\Windows\\System32\\cmd.exe", "claude"))
    }

    @Test
    fun `powershell hides the arguments the IDE appends behind a comment`() {
        assertEquals(listOf("-Command", "claude", "#"), runCommandArgs("powershell.exe", "claude"))
        assertEquals(listOf("-Command", "claude", "#"), runCommandArgs("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "claude"))
    }
}
