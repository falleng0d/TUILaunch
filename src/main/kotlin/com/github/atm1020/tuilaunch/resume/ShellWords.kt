package com.github.atm1020.tuilaunch.resume

object ShellWords {
    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val BACKSLASH = '\\'
    private const val DOUBLE_QUOTE_ESCAPABLES = "\"\\$`"
    private val SAFE_CHARACTERS = Regex("^[A-Za-z0-9_./:=@,-]+$")

    data class Token(val start: Int, val text: String)

    fun split(command: String): List<String> = tokenize(command).map { it.text }

    fun tokenize(command: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val token = StringBuilder()
        var tokenStarted = false
        var tokenStart = 0
        var index = 0
        while (index < command.length) {
            val character = command[index]
            if (!tokenStarted && !character.isWhitespace()) tokenStart = index
            when {
                character == BACKSLASH && index + 1 < command.length -> {
                    token.append(command[index + 1])
                    tokenStarted = true
                    index += 2
                }

                character == SINGLE_QUOTE -> {
                    index++
                    while (index < command.length && command[index] != SINGLE_QUOTE) {
                        token.append(command[index])
                        index++
                    }
                    if (index < command.length) index++
                    tokenStarted = true
                }

                character == DOUBLE_QUOTE -> {
                    index++
                    while (index < command.length && command[index] != DOUBLE_QUOTE) {
                        val escapesTheNextCharacter = command[index] == BACKSLASH &&
                            index + 1 < command.length &&
                            command[index + 1] in DOUBLE_QUOTE_ESCAPABLES
                        if (escapesTheNextCharacter) {
                            token.append(command[index + 1])
                            index += 2
                        } else {
                            token.append(command[index])
                            index++
                        }
                    }
                    if (index < command.length) index++
                    tokenStarted = true
                }

                character.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens.add(Token(tokenStart, token.toString()))
                        token.setLength(0)
                        tokenStarted = false
                    }
                    index++
                }

                else -> {
                    token.append(character)
                    tokenStarted = true
                    index++
                }
            }
        }
        if (tokenStarted) tokens.add(Token(tokenStart, token.toString()))
        return tokens
    }

    fun quote(value: String): String {
        if (SAFE_CHARACTERS.matches(value)) return value
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    fun join(values: List<String>): String = values.joinToString(" ") { quote(it) }

    fun baseName(program: String): String = program.substringAfterLast('/')
}
