package com.github.atm1020.tuilaunch.resume

enum class AgentCliKind(val programName: String) {
    CLAUDE("claude"),
    CODEX("codex"),
    OPENCODE("opencode"),
    OMP("omp"),
    ;

    companion object {
        /**
         * A `<cli>-agent` wrapper forwards whatever follows to the CLI it is named after, so it takes the
         * same session arguments. Anything else named after a CLI is left alone, since it may not.
         */
        private const val WRAPPER_SUFFIX = "-agent"

        fun forProgramName(programName: String): AgentCliKind? {
            val name = programName.removeSuffix(WRAPPER_SUFFIX)
            return entries.firstOrNull { it.programName == name }
        }
    }
}
