package com.github.atm1020.tuilaunch.resume

enum class AgentCliKind(val programName: String) {
    CLAUDE("claude"),
    CODEX("codex"),
    OPENCODE("opencode"),
    OMP("omp"),
    ;

    companion object {
        fun forProgramName(programName: String): AgentCliKind? = entries.firstOrNull { it.programName == programName }
    }
}
