package com.github.atm1020.tuilaunch.resume

class AgentCommand private constructor(
    val command: String,
    val tokens: List<String>,
    val kind: AgentCliKind?,
    val wrappedByHeadroom: Boolean,
    val userSelectsASession: Boolean,
    val chainsOtherCommands: Boolean,
    val loadsATrustedExtension: Boolean,
) {
    val isManageable: Boolean
        get() = kind != null && !userSelectsASession && !chainsOtherCommands

    fun withArguments(extra: List<String>): String {
        if (extra.isEmpty()) return command
        val appended = ShellWords.join(extra)
        val separator = if (wrappedByHeadroom && !tokens.contains(PASS_THROUGH)) "$PASS_THROUGH " else ""
        val endsWithSpace = command.isEmpty() || command.last().isWhitespace()
        val gap = if (endsWithSpace) "" else " "
        return command + gap + separator + appended
    }

    companion object {
        private const val PASS_THROUGH = "--"
        private const val HEADROOM_PROGRAM = "headroom"
        private const val HEADROOM_WRAP_SUBCOMMAND = "wrap"
        private const val ENV_PROGRAM = "env"
        private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
        private val SHELL_OPERATORS = setOf(";", "&&", "||", "|", "&", ">", ">>", "<")

        private val SESSION_SELECTORS = mapOf(
            AgentCliKind.CLAUDE to listOf("--resume", "-r", "--continue", "-c", "--session-id", "--fork-session"),
            AgentCliKind.CODEX to listOf("resume", "fork", "exec", "--continue"),
            AgentCliKind.OPENCODE to listOf("--session", "-s", "--continue", "-c", "--fork"),
            AgentCliKind.OMP to listOf(
                "--resume",
                "-r",
                "--session",
                "--continue",
                "-c",
                "--fork",
                "--session-dir",
            ),
        )

        private val TRUSTED_EXTENSION_FLAGS = mapOf(AgentCliKind.OMP to listOf("--trusted-extension"))

        fun parse(command: String): AgentCommand {
            val tokens = ShellWords.split(command)
            val programIndex = programIndexIn(tokens)
            val wrappedByHeadroom = isHeadroomWrapAt(tokens, programIndex)
            val cliIndex = if (wrappedByHeadroom) programIndex + 2 else programIndex
            val kind = tokens.getOrNull(cliIndex)?.let { AgentCliKind.forProgramName(ShellWords.baseName(it)) }
            val arguments = if (kind == null) emptyList() else tokens.drop(cliIndex + 1)
            return AgentCommand(
                command = command,
                tokens = tokens,
                kind = kind,
                wrappedByHeadroom = wrappedByHeadroom,
                userSelectsASession = kind != null &&
                    anyArgumentMatches(arguments, SESSION_SELECTORS.getValue(kind)),
                chainsOtherCommands = kind != null && chainsOtherCommands(command, arguments),
                loadsATrustedExtension = kind != null &&
                    anyArgumentMatches(arguments, TRUSTED_EXTENSION_FLAGS[kind].orEmpty()),
            )
        }

        private fun chainsOtherCommands(command: String, arguments: List<String>): Boolean =
            command.contains('\n') || arguments.any { it in SHELL_OPERATORS }

        private fun programIndexIn(tokens: List<String>): Int {
            var index = 0
            while (index < tokens.size) {
                if (ASSIGNMENT.containsMatchIn(tokens[index])) {
                    index++
                    continue
                }
                if (ShellWords.baseName(tokens[index]) == ENV_PROGRAM) {
                    index++
                    continue
                }
                break
            }
            return index
        }

        private fun isHeadroomWrapAt(tokens: List<String>, index: Int): Boolean =
            ShellWords.baseName(tokens.getOrNull(index).orEmpty()) == HEADROOM_PROGRAM &&
                tokens.getOrNull(index + 1) == HEADROOM_WRAP_SUBCOMMAND

        private fun anyArgumentMatches(arguments: List<String>, flags: List<String>): Boolean =
            arguments.any { argument ->
                flags.any { flag ->
                    argument == flag || (flag.startsWith("-") && argument.startsWith("$flag="))
                }
            }
    }
}
