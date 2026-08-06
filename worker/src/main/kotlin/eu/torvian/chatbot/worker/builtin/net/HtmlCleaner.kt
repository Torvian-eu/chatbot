package eu.torvian.chatbot.worker.builtin.net

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Cleans raw HTML down to a "bare minimum" document for the web-fetch tools' `cleanHtml` option.
 *
 * The implementation is safelist-based, delegating to JSoup's [Cleaner] + [Safelist]: the input is
 * parsed as HTML and run through a configured safelist so the output can contain only the core tags,
 * core attributes, and visible text the requirement allows. Non-core tags (`script`, `style`, `head`
 * content, `iframe`, forms, metadata, ...) and non-core attributes (`style`, `class`, `id`,
 * `on*` event handlers, ...) are removed automatically.
 *
 * The cleaner is thread-safe once configured and may be reused and shared across calls, so a single
 * instance can be held by callers rather than re-created per request.
 *
 * This type is deliberately pure and transport-agnostic: it operates on in-memory HTML strings and
 * never performs I/O, keeping web transport concerns entirely in [WebFetchService].
 */
class HtmlCleaner {

    /** The safelist shared by all [clean] calls; choose which tags/attributes are kept below. */
    private val safelist: Safelist = buildSafelist()

    /** A reusable [Cleaner] built on [safelist]; safe to share across threads once constructed. */
    private val cleaner: Cleaner = Cleaner(safelist)

    /**
     * Cleans [html] to its bare-minimum form when [clean] is true and the input is HTML; otherwise
     * returns the input unchanged.
     *
     * When [clean] is false (or [html] is blank) the original input is returned verbatim, so callers
     * can pass the fetched bytes straight through without branching on the flag themselves. When
     * [clean] is true, the (possibly malformed) HTML fragment is parsed in a JSoup-HTML5-tolerant
     * way and re-serialized body-only; this never throws on page content.
     *
     * @param html The raw HTML to potentially clean.
     * @param clean Whether cleaning should be applied. When false, [html] is returned unchanged.
     * @return The cleaned body-only HTML when cleaning is applied, otherwise [html].
     */
    fun clean(html: String, clean: Boolean): String {
        if (!clean || html.isBlank()) return html
        val dirty: Document = Jsoup.parse(html)
        val cleaned: Document = cleaner.clean(dirty)
        return cleaned.body().html()
    }

    /**
     * Builds the [Safelist] encoding the "core tags + core attributes" policy.
     *
     * It starts from the canned `basic` safelist (which already keeps common body tags such as
     * `a`, `b`, `blockquote`, `br`, `cite`, `code`, `dd`, `dl`, `dt`, `em`, `i`, `li`, `ol`, `p`,
     * `pre`, `q`, `small`, `span`, `strike`, `strong`, `sub`, `sup`, `u`, `ul`) and the structural
     * `img`, `h1`-`h6`, and table tags the requirement calls for. Content-type cleanup (dropping
     * `script`/`style`/`head`) is built into JSoup's safelist body handling.
     *
     * @return A configured, ready-to-use [Safelist].
     */
    private fun buildSafelist(): Safelist = Safelist.basic()
        .addTags("h1", "h2", "h3", "h4", "h5", "h6", "img", "table", "thead", "tbody", "tfoot", "tr", "th", "td")
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title")
        .addAttributes(":all", "title", "aria-label")
        // Safelist.basic() enforces rel="nofollow" on every anchor; drop it so links stay as-is and
        // the output is not bloated with an identical attribute on every <a>, saving LLM tokens.
        .removeEnforcedAttribute("a", "rel")
}
