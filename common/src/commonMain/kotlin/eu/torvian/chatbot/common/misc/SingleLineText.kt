package eu.torvian.chatbot.common.misc

/**
 * Returns a copy of [text] in which every line-break sequence (CRLF, CR, or LF) is replaced by a
 * single space, so the result is guaranteed to be free of newline characters.
 *
 * This is used for single-line inputs such as chat session names: pasting multi-line content must
 * not persist newline characters that are invisible in a single-line text field but leak into
 * tooltips and other display surfaces.
 *
 * @param text The input text, possibly containing line breaks.
 * @return The input with all line-break sequences replaced by a single space.
 */
fun normalizeSingleLine(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ')
