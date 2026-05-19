package eu.torvian.chatbot.common.markdown.model

/**
 * Classification for markdown source tokens used in editor-style syntax highlighting.
 */
enum class MarkdownTokenKind {
    /**
     * Plain, unstyled markdown source text.
     */
    PlainText,
    /**
     * Marker that opens a heading (e.g., '#', '##').
     */
    HeadingMarker,
    /**
     * Heading content text after the marker.
     */
    HeadingText,
    /**
     * Marker for an unordered list item (e.g., '-', '*').
     */
    ListMarker,
    /**
     * Marker for an ordered list item (e.g., '1.').
     */
    OrderedListMarker,
    /**
     * Marker for a blockquote line (e.g., '>').
     */
    BlockquoteMarker,
    /**
     * Content text inside a blockquote line.
     */
    BlockquoteText,
    /**
     * Delimiter for emphasis (e.g., '*', '_').
     */
    EmphasisDelimiter,
    /**
     * Text content inside emphasis.
     */
    EmphasisText,
    /**
     * Delimiter for strong emphasis (e.g., '**', '__').
     */
    StrongDelimiter,
    /**
     * Text content inside strong emphasis.
     */
    StrongText,
    /**
     * Delimiter for strikethrough (e.g., '~~').
     */
    StrikeDelimiter,
    /**
     * Text content inside strikethrough.
     */
    StrikeText,
    /**
     * Delimiter for inline code (e.g., '`').
     */
    InlineCodeDelimiter,
    /**
     * Text content inside inline code.
     */
    InlineCodeText,
    /**
     * Fence delimiter for fenced code blocks (e.g., '```').
     */
    CodeFence,
    /**
     * Language info string after a code fence.
     */
    CodeFenceLanguage,
    /**
     * Text content inside a fenced code block.
     */
    CodeBlockText,
    /**
     * Delimiter that opens or closes link text (e.g., '[' or ']').
     */
    LinkTextDelimiter,
    /**
     * Text content of a link label.
     */
    LinkText,
    /**
     * Delimiter that opens or closes a link URL (e.g., '(' or ')').
     */
    LinkUrlDelimiter,
    /**
     * URL content of a link target.
     */
    LinkUrl,
    /**
     * Marker that starts an image (e.g., '!').
     */
    ImageMarker,
    /**
     * Table cell separator (e.g., '|').
     */
    TablePipe,
    /**
     * Text content within a table header cell.
     */
    TableHeaderText,
    /**
     * Text content within a table body cell.
     */
    TableCellText,
    /**
     * Delimiter row in a table header separator line (e.g., '---').
     */
    TableDelimiterRow,
    /**
     * Horizontal rule marker line (e.g., '---', '***').
     */
    HorizontalRule,
    /**
     * Escaped sequence (e.g., '\\*').
     */
    EscapeSequence,
}

