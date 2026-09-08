package com.github.atm1020.tuilaunch.resume

class AgentCommand private constructor(
    val command: String,
    val tokens: List<String>,
    val kind: AgentCliKind?,
    val wrappedByHeadroom: Boolean,
    val userSelectsASession: Boolean,
    val chainsOtherCommands: Boolean,
    val bringsItsOwnHookFlag: Boolean,
    val setsASessionVariableItself: Boolean,
    private val programStart: Int,
) {
    val isManageable: Boolean
        get() = kind != null && !userSelectsASession && !chainsOtherCommands && !setsASessionVariableItself

    fun withEnvironment(assignments: Map<String, String>): AgentCommand {
        if (assignments.isEmpty()) return this
        val prefix = assignments.entries.joinToString(" ") { (name, value) ->
            "$name=${ShellWords.quote(value)}"
        } + " "
        val decorated = command.substring(0, programStart) + prefix + command.substring(programStart)
        return AgentCommand(
            command = decorated,
            tokens = ShellWords.split(decorated),
            kind = kind,
            wrappedByHeadroom = wrappedByHeadroom,
            userSelectsASession = userSelectsASession,
            chainsOtherCommands = chainsOtherCommands,
            bringsItsOwnHookFlag = bringsItsOwnHookFlag,
            setsASessionVariableItself = setsASessionVariableItself,
            programStart = programStart + prefix.length,
        )
    }

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
        private const val TRUSTED_EXTENSION_FLAG = "--trusted-extension"
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

        private val HOOK_FLAGS_THE_USER_MAY_OWN = mapOf(
            AgentCliKind.CLAUDE to listOf(ClaudeSessionStrategy.SETTINGS_FLAG),
            AgentCliKind.OMP to listOf(TRUSTED_EXTENSION_FLAG),
        )

        private val SESSION_VARIABLES = mapOf(
            AgentCliKind.OPENCODE to listOf(
                OpenCodeSessionStrategy.TUI_CONFIG_VARIABLE,
                OpenCodeSessionStrategy.STATE_FILE_VARIABLE,
            ),
        )

        fun parse(command: String): AgentCommand {
            val tokens = ShellWords.tokenize(command)
            val texts = tokens.map { it.text }
            val programIndex = programIndexIn(texts)
            val wrappedByHeadroom = isHeadroomWrapAt(texts, programIndex)
            val cliIndex = if (wrappedByHeadroom) programIndex + 2 else programIndex
            val kind = texts.getOrNull(cliIndex)?.let { AgentCliKind.forProgramName(ShellWords.baseName(it)) }
            val arguments = if (kind == null) emptyList() else texts.drop(cliIndex + 1)
            return AgentCommand(
                command = command,
                tokens = texts,
                kind = kind,
                wrappedByHeadroom = wrappedByHeadroom,
                userSelectsASession = kind != null &&
                    anyArgumentMatches(arguments, SESSION_SELECTORS.getValue(kind)),
                chainsOtherCommands = kind != null && chainsOtherCommands(command, arguments),
                bringsItsOwnHookFlag = kind != null &&
                    anyArgumentMatches(arguments, HOOK_FLAGS_THE_USER_MAY_OWN[kind].orEmpty()),
                setsASessionVariableItself = kind != null &&
                    anyAssignmentMatches(texts.take(programIndex), SESSION_VARIABLES[kind].orEmpty()),
                programStart = tokens.getOrNull(programIndex)?.start ?: command.length,
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

        private fun anyAssignmentMatches(prefix: List<String>, names: List<String>): Boolean =
            prefix.any { token -> names.any { token.startsWith("$it=") } }
    }
}
