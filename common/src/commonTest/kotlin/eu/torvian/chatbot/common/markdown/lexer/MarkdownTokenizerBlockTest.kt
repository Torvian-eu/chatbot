package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies block-level markdown tokenization heuristics in common code.
 */
class MarkdownTokenizerBlockTest {
    /**
     * Ensures ATX headings emit marker and text tokens.
     */
    @Test
    fun headingsAreTokenized() {
        assertEquals(
            listOf(MarkdownTokenKind.HeadingMarker, MarkdownTokenKind.HeadingText),
            kinds("# Heading"),
        )
        assertEquals(
            listOf(MarkdownTokenKind.HeadingMarker, MarkdownTokenKind.HeadingText),
            kinds("### Title"),
        )
    }

    /**
     * Ensures headings require marker termination by whitespace or end of line.
     */
    @Test
    fun headingWithoutWhitespaceIsPlainText() {
        assertEquals(listOf(MarkdownTokenKind.PlainText), kinds("#foo"))
    }

    /**
     * Ensures blockquote markers are detected and text is separated.
     */
    @Test
    fun blockquoteIsTokenized() {
        assertEquals(
            listOf(MarkdownTokenKind.BlockquoteMarker, MarkdownTokenKind.BlockquoteText),
            kinds("> quote"),
        )
    }

    /**
     * Ensures list markers are classified and malformed ordered lists are ignored.
     */
    @Test
    fun listMarkersAreTokenized() {
        assertEquals(
            listOf(MarkdownTokenKind.ListMarker, MarkdownTokenKind.PlainText),
            kinds("- item"),
        )
        assertEquals(
            listOf(MarkdownTokenKind.ListMarker, MarkdownTokenKind.PlainText),
            kinds("+ item"),
        )
        assertEquals(
            listOf(MarkdownTokenKind.OrderedListMarker, MarkdownTokenKind.PlainText),
            kinds("1. item"),
        )
        assertEquals(listOf(MarkdownTokenKind.PlainText), kinds("1.item"))
    }

    /**
     * Ensures horizontal rules are detected without stealing list markers.
     */
    @Test
    fun horizontalRulesAreDetected() {
        assertEquals(listOf(MarkdownTokenKind.HorizontalRule), kinds("---"))
        assertEquals(listOf(MarkdownTokenKind.HorizontalRule), kinds("***"))
        assertEquals(listOf(MarkdownTokenKind.HorizontalRule), kinds("_ _ _"))
        assertFalse(kinds("- item").contains(MarkdownTokenKind.HorizontalRule))
    }

    /**
     * Ensures fenced code blocks emit fence, language, and code body tokens.
     */
    @Test
    fun fencedCodeBlocksAreTokenized() {
        val source = "```kotlin\nval x = 1\n```"
        val tokenKinds = kinds(source)
        assertEquals(MarkdownTokenKind.CodeFence, tokenKinds.first())
        assertEquals(MarkdownTokenKind.CodeFenceLanguage, tokenKinds[1])
        assertEquals(MarkdownTokenKind.CodeFence, tokenKinds.last())
        assertTrue(tokenKinds.contains(MarkdownTokenKind.CodeBlockText))

        val languageSlice = sliceByKind(source, MarkdownTokenKind.CodeFenceLanguage)
        assertEquals("kotlin", languageSlice)
    }

    /**
     * Ensures unclosed fences keep consuming code text until end of input.
     */
    @Test
    fun unclosedFenceConsumesToEnd() {
        val source = "```\ncode\nmore"
        val tokens = tokenize(source)
        assertEquals(MarkdownTokenKind.CodeFence, tokens.first().kind)
        assertEquals(MarkdownTokenKind.CodeBlockText, tokens.last().kind)
        assertTrue(source.substring(tokens.last().start, tokens.last().endExclusive).contains("more"))
    }

    /**
     * Ensures tables are detected conservatively with delimiter guidance.
     */
    @Test
    fun tablesAreDetectedConservatively() {
        val tableSource = "| A | B |\n|---|---|\n| 1 | 2 |"
        val tableKinds = kinds(tableSource)
        assertTrue(tableKinds.contains(MarkdownTokenKind.TablePipe))
        assertTrue(tableKinds.contains(MarkdownTokenKind.TableDelimiterRow))
        assertTrue(tableKinds.contains(MarkdownTokenKind.TableHeaderText))

        val nonTableSource = "a | b"
        assertFalse(kinds(nonTableSource).contains(MarkdownTokenKind.TablePipe))
    }

    /**
     * Ensures emphasis, strong, and strike inline markers are tokenized.
     */
    @Test
    fun emphasisStrongAndStrikeAreTokenized() {
        val source = "*a* _a_ **a** __a__ ~~a~~"
        val tokenKinds = kinds(source)
        assertTrue(tokenKinds.contains(MarkdownTokenKind.EmphasisDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.EmphasisText))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.StrongDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.StrongText))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.StrikeDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.StrikeText))
    }

    /**
     * Ensures inline code spans are tokenized and partial spans are conservative.
     */
    @Test
    fun inlineCodeIsTokenized() {
        val tokenKinds = kinds("`code`")
        assertTrue(tokenKinds.contains(MarkdownTokenKind.InlineCodeDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.InlineCodeText))
        assertFalse(kinds("`cod").contains(MarkdownTokenKind.InlineCodeDelimiter))
    }

    /**
     * Ensures link syntax emits text and URL tokens.
     */
    @Test
    fun linksAreTokenized() {
        val source = "[label](https://example.com)"
        val tokenKinds = kinds(source)
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkTextDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkText))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkUrlDelimiter))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkUrl))

        val partialSource = "[label](https://exa"
        val partialKinds = kinds(partialSource)
        assertTrue(partialKinds.contains(MarkdownTokenKind.LinkText))
        assertTrue(partialKinds.contains(MarkdownTokenKind.LinkUrl))
    }

    /**
     * Ensures image syntax emits the image marker and link-style tokens.
     */
    @Test
    fun imagesAreTokenized() {
        val tokenKinds = kinds("![alt](url)")
        assertTrue(tokenKinds.contains(MarkdownTokenKind.ImageMarker))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkText))
        assertTrue(tokenKinds.contains(MarkdownTokenKind.LinkUrl))
    }

    /**
     * Ensures escaped delimiters are not parsed as emphasis.
     */
    @Test
    fun escapesAreTokenized() {
        val source = "\\*literal asterisk\\*"
        val tokenKinds = kinds(source)
        assertTrue(tokenKinds.contains(MarkdownTokenKind.EscapeSequence))
        assertFalse(tokenKinds.contains(MarkdownTokenKind.EmphasisDelimiter))
    }

    /**
     * Ensures inline parsing occurs within block-level text regions.
     */
    @Test
    fun inlineParsingAppliesInsideBlocks() {
        val headingKinds = kinds("# *hi*")
        assertTrue(headingKinds.contains(MarkdownTokenKind.HeadingMarker))
        assertTrue(headingKinds.contains(MarkdownTokenKind.EmphasisDelimiter))

        val quoteKinds = kinds("> [a](b)")
        assertTrue(quoteKinds.contains(MarkdownTokenKind.BlockquoteMarker))
        assertTrue(quoteKinds.contains(MarkdownTokenKind.LinkUrl))

        val listKinds = kinds("- `code`")
        assertTrue(listKinds.contains(MarkdownTokenKind.ListMarker))
        assertTrue(listKinds.contains(MarkdownTokenKind.InlineCodeDelimiter))
    }

    /**
     * Ensures inline parsing does not run inside fenced code blocks.
     */
    @Test
    fun inlineParsingIsSkippedInCodeBlocks() {
        val source = "```\n*not*\n```"
        val tokenKinds = kinds(source)
        assertTrue(tokenKinds.contains(MarkdownTokenKind.CodeBlockText))
        assertFalse(tokenKinds.contains(MarkdownTokenKind.EmphasisDelimiter))
    }

    /**
     * Tokenizes the source string using the default block-level tokenizer.
     */
    private fun tokenize(source: String): List<MarkdownToken> = DefaultMarkdownTokenizer.tokenize(source)

    /**
     * Returns the ordered token kinds for the provided source.
     */
    private fun kinds(source: String): List<MarkdownTokenKind> = tokenize(source).map { it.kind }

    /**
     * Extracts the first substring matching the requested token kind.
     */
    private fun sliceByKind(source: String, kind: MarkdownTokenKind): String {
        val token = tokenize(source).first { it.kind == kind }
        return source.substring(token.start, token.endExclusive)
    }
}
