package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken

/**
 * Tokenizes markdown source text for editor-style syntax highlighting.
 *
 * The tokenizer is tolerant and may produce best-effort output, especially for
 * incomplete input that will be supported in streaming scenarios later.
 */
interface MarkdownTokenizer {
    /**
     * Produces a list of tokens referencing the original source string.
     *
     * @param markdown raw markdown source text.
     * @return tokens in source order, suitable for highlighting.
     */
    fun tokenize(markdown: String): List<MarkdownToken>
}

