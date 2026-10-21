package eu.torvian.chatbot.worker.builtin.terminal

/**
 * Adds terminal-specific variables to the environment produced by the child-environment provider.
 *
 * The provider remains the authority over account identity and inherited credentials; this
 * decorator only adds variables that describe the PTY and reduce decorative output. It never reads
 * or copies the worker's own environment.
 *
 * @param base Complete child environment built by the provider; it is not mutated.
 * @return A new environment map containing [base] plus the terminal variables.
 */
internal fun terminalEnvironment(base: Map<String, String>): Map<String, String> {
    val environment = LinkedHashMap(base)
    environment[TERM_VARIABLE] = TERM_VALUE
    environment[NO_COLOR_VARIABLE] = ENABLED_VALUE
    environment[CLICOLOR_VARIABLE] = DISABLED_VALUE
    environment[PAGER_VARIABLE] = CAT_PAGER
    environment[GIT_PAGER_VARIABLE] = CAT_PAGER
    environment[SYSTEMD_PAGER_VARIABLE] = CAT_PAGER
    environment[DEBIAN_FRONTEND_VARIABLE] = NONINTERACTIVE_VALUE

    // A shell configuration the deployment deliberately set wins over these interactive defaults.
    environment.putIfAbsent(PS1_VARIABLE, DEFAULT_PS1)
    environment.putIfAbsent(PROMPT_COMMAND_VARIABLE, "")
    return environment
}

/** Terminal type advertised to the child. */
private const val TERM_VARIABLE = "TERM"

/** Terminal type advertised to the child; the session is a real 256-colour PTY. */
private const val TERM_VALUE = "xterm-256color"

/** Variable asking programs to suppress colour output. */
private const val NO_COLOR_VARIABLE = "NO_COLOR"

/** Variable asking programs to suppress colour output. */
private const val CLICOLOR_VARIABLE = "CLICOLOR"

/** Value that enables a boolean environment decoration. */
private const val ENABLED_VALUE = "1"

/** Value that disables a boolean environment decoration. */
private const val DISABLED_VALUE = "0"

/** Pager variable used by generic programs. */
private const val PAGER_VARIABLE = "PAGER"

/** Pager variable used by git. */
private const val GIT_PAGER_VARIABLE = "GIT_PAGER"

/** Pager variable used by systemd tools. */
private const val SYSTEMD_PAGER_VARIABLE = "SYSTEMD_PAGER"

/** Pager command that keeps output linear instead of entering a full-screen pager. */
private const val CAT_PAGER = "cat"

/** Variable that stops package tools from opening interactive prompts. */
private const val DEBIAN_FRONTEND_VARIABLE = "DEBIAN_FRONTEND"

/** Value that keeps Debian package tools non-interactive. */
private const val NONINTERACTIVE_VALUE = "noninteractive"

/** Shell prompt variable. */
private const val PS1_VARIABLE = "PS1"

/** Shell prompt value in the `user@host:cwd$ ` shape. */
private const val DEFAULT_PS1 = "\\u@\\h:\\w\\$ "

/** Shell hook variable; empty stops a preconfigured prompt hook from escaping a full-screen UI. */
private const val PROMPT_COMMAND_VARIABLE = "PROMPT_COMMAND"
