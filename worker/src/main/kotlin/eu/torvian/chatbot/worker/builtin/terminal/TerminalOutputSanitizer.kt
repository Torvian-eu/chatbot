package eu.torvian.chatbot.worker.builtin.terminal

/**
 * Converts raw PTY output into bounded, line-oriented text.
 *
 * State persists across [feed] calls so a control sequence split across reads is removed as one
 * unit. The sanitizer is not a terminal emulator: it discards escape and control sequences instead
 * of applying them, and it reports alternate-screen use so the tool can warn the model.
 *
 * @property tuiEscapeRatioThreshold Fraction of screen-control characters over the recent window
 *   that marks the stream as full-screen-UI driven. Decoration and line-editing sequences never
 *   count toward it.
 */
internal class TerminalOutputSanitizer(
    private val tuiEscapeRatioThreshold: Double,
) {
    /** True once an alternate-screen sequence or a high escape density has been observed. */
    var tuiDetected: Boolean = false
        private set

    /** Current position of the control-sequence state machine. */
    private var state = State.TEXT

    /** Characters consumed so far by the sequence in progress, awaiting density accounting. */
    private var pendingSequenceLength = 0

    /** Bytes collected for the CSI sequence currently being consumed. */
    private val csiBody = StringBuilder()

    /** True while an OSC/DCS string is waiting for the second byte of a string terminator. */
    private var stringEscapePending = false

    /** Length of the OSC/DCS string consumed so far, used to cap an unterminated sequence. */
    private var stringLength = 0

    /** True when a preceding `\r` makes a following `\n` redundant. */
    private var swallowNextLineFeed = false

    /** Recent per-character observations; true marks a screen-control character. */
    private val densityWindow = ArrayDeque<Boolean>()

    /** Number of `true` entries currently in [densityWindow]. */
    private var densityControlCount = 0

    /**
     * Consumes decoded characters and returns the printable, normalized text.
     *
     * @param input Decoded UTF-16 characters from the PTY reader.
     * @param length Number of valid characters in [input].
     * @return Sanitized text, with `\r\n` and lone `\r` normalized to `\n`.
     */
    fun feed(input: CharArray, length: Int): String {
        val end = length.coerceAtMost(input.size)
        val output = StringBuilder(end)
        var index = 0
        while (index < end) {
            val char = input[index]
            when (state) {
                State.TEXT -> consumeText(char, output)
                State.ESCAPE -> consumeEscape(char)
                State.CSI -> consumeCsi(char)
                State.STRING -> consumeString(char)
                // A charset-selection escape is ESC followed by an intermediate byte and one final
                // byte; only the final byte still has to be discarded.
                State.CHARSET -> {
                    pendingSequenceLength++
                    finishSequence(benign = true)
                    state = State.TEXT
                }
            }
            index++
        }
        return output.toString()
    }

    /**
     * Handles one character in printable-text state.
     *
     * @param char Character to process.
     * @param output Builder receiving printable text.
     */
    private fun consumeText(char: Char, output: StringBuilder) {
        when {
            char == ESCAPE -> {
                swallowNextLineFeed = false
                pendingSequenceLength = 1
                state = State.ESCAPE
            }
            char == '\r' -> {
                // Emit immediately and remember to swallow a directly following line feed, so the
                // result does not depend on where a chunk boundary falls inside "\r\n".
                swallowNextLineFeed = true
                output.append('\n')
                observe(isControl = false)
            }
            char == '\n' -> {
                if (swallowNextLineFeed) {
                    swallowNextLineFeed = false
                } else {
                    output.append('\n')
                }
                observe(isControl = false)
            }
            char == '\t' -> {
                swallowNextLineFeed = false
                output.append('\t')
                observe(isControl = false)
            }
            char < ' ' -> {
                // Control characters other than tab and newline carry no line-oriented meaning.
                swallowNextLineFeed = false
                observe(isControl = true)
            }
            char == DEL -> {
                swallowNextLineFeed = false
                observe(isControl = true)
            }
            else -> {
                swallowNextLineFeed = false
                output.append(char)
                observe(isControl = false)
            }
        }
    }

    /**
     * Handles the byte directly after an `ESC`.
     *
     * @param char Character following the escape byte.
     */
    private fun consumeEscape(char: Char) {
        if (char == ESCAPE) {
            // A fresh escape abandons the sequence in progress and starts the next one.
            finishSequence(benign = false)
            pendingSequenceLength = 1
            state = State.ESCAPE
            return
        }
        pendingSequenceLength++
        when (char) {
            '[' -> {
                csiBody.clear()
                state = State.CSI
            }
            ']', 'P', '^', '_' -> {
                stringLength = 0
                stringEscapePending = false
                state = State.STRING
            }
            in '('..'/' -> {
                csiBody.clear()
                state = State.CHARSET
            }
            else -> {
                // Keypad mode (`ESC =`, `ESC >`) is a line-editing switch; other two-byte escapes
                // such as save/restore cursor (`ESC 7`, `ESC 8`) address the screen.
                finishSequence(benign = char == '=' || char == '>')
                state = State.TEXT
            }
        }
    }

    /**
     * Handles one byte of a control-sequence-introducer sequence.
     *
     * @param char Character inside the sequence, including its final byte.
     */
    private fun consumeCsi(char: Char) {
        if (char == ESCAPE) {
            // A fresh escape aborts a malformed sequence instead of being swallowed by it.
            csiBody.clear()
            finishSequence(benign = false)
            pendingSequenceLength = 1
            state = State.ESCAPE
            return
        }
        pendingSequenceLength++
        if (char in '\u0040'..'\u007e') {
            val body = csiBody.toString()
            if (char == 'h' && body in ALTERNATE_SCREEN_BODIES) {
                tuiDetected = true
            }
            csiBody.clear()
            finishSequence(isBenignCsi(body, char))
            state = State.TEXT
            return
        }
        if (csiBody.length >= MAX_SEQUENCE_LENGTH) {
            // A program that never terminates a sequence must not be able to grow our state.
            csiBody.clear()
            finishSequence(benign = false)
            state = State.TEXT
            return
        }
        csiBody.append(char)
    }

    /**
     * Handles one byte of an OSC, DCS, PM or APC string.
     *
     * @param char Character inside the string.
     */
    private fun consumeString(char: Char) {
        pendingSequenceLength++
        if (stringEscapePending) {
            stringEscapePending = false
            when (char) {
                '\\' -> {
                    stringLength = 0
                    finishSequence(benign = true)
                    state = State.TEXT
                    return
                }
                ESCAPE -> {
                    stringEscapePending = true
                    return
                }
                else -> Unit
            }
        }
        when (char) {
            BEL -> {
                stringLength = 0
                finishSequence(benign = true)
                state = State.TEXT
            }
            ESCAPE -> stringEscapePending = true
            else -> {
                stringLength++
                if (stringLength >= MAX_SEQUENCE_LENGTH) {
                    stringLength = 0
                    finishSequence(benign = true)
                    state = State.TEXT
                }
            }
        }
    }

    /**
     * Accounts for the control sequence that just ended.
     *
     * Benign sequences are dropped rather than counted: bracketed paste, cursor visibility, keypad
     * mode, colour and title strings describe line editing or decoration, so counting them would
     * let an ordinary shell prompt look control-heavy enough to be a full-screen UI.
     *
     * @param benign Whether the finished sequence carries no screen-control signal.
     */
    private fun finishSequence(benign: Boolean) {
        if (!benign) repeat(pendingSequenceLength) { observe(isControl = true) }
        pendingSequenceLength = 0
    }

    /**
     * Reports whether a CSI sequence is decoration rather than screen control.
     *
     * @param body Parameter and intermediate bytes of the sequence, without `ESC [`.
     * @param final Final byte that terminated the sequence.
     * @return True for colour styling and for the mode switches a shell toggles around its prompt.
     */
    private fun isBenignCsi(body: String, final: Char): Boolean = when (final) {
        // SGR styles text in place; it never moves the cursor or rewrites the screen.
        'm' -> true
        'h', 'l' -> body in BENIGN_MODE_BODIES
        else -> false
    }

    /**
     * Records one character for the escape-density heuristic.
     *
     * The window is bounded, so the heuristic state cannot grow with output size.
     *
     * @param isControl Whether the character belonged to a screen-control sequence or was a
     *   non-printable control byte.
     */
    private fun observe(isControl: Boolean) {
        if (tuiDetected) return
        densityWindow.addLast(isControl)
        if (isControl) densityControlCount++
        if (densityWindow.size > DENSITY_WINDOW_SIZE) {
            if (densityWindow.removeFirst()) densityControlCount--
        }
        if (densityWindow.size >= DENSITY_MIN_SAMPLES &&
            densityControlCount.toDouble() / densityWindow.size > tuiEscapeRatioThreshold
        ) {
            tuiDetected = true
        }
    }

    /** States of the control-sequence state machine. */
    private enum class State {
        /** Printable text. */
        TEXT,

        /** Just consumed `ESC`. */
        ESCAPE,

        /** Inside a CSI sequence, after `ESC [`. */
        CSI,

        /** Inside an OSC, DCS, PM or APC string. */
        STRING,

        /** Inside a character-set selection escape, after `ESC (` and similar. */
        CHARSET,
    }

    private companion object {
        /** The escape byte that starts every control sequence. */
        const val ESCAPE = '\u001b'

        /** The bell byte, which also terminates some OSC strings. */
        const val BEL = '\u0007'

        /** The delete character, which carries no printable meaning. */
        const val DEL = '\u007f'

        /** Upper bound on any partially consumed control sequence. */
        const val MAX_SEQUENCE_LENGTH = 4096

        /** Number of recent characters the escape-density heuristic is evaluated over. */
        const val DENSITY_WINDOW_SIZE = 256

        /** Minimum samples before the density heuristic may fire. */
        const val DENSITY_MIN_SAMPLES = 64

        /** CSI parameter bodies that enter the alternate screen. */
        val ALTERNATE_SCREEN_BODIES = setOf("?1049", "?1047", "?47")

        /**
         * Private-mode bodies that only affect line editing or cursor display.
         *
         * `?1` application cursor keys, `?12` cursor blink, `?25` cursor visibility,
         * `?1004` focus reporting and `?2004` bracketed paste are all set up by a shell prompt.
         */
        val BENIGN_MODE_BODIES = setOf("?1", "?12", "?25", "?1004", "?2004")
    }
}
