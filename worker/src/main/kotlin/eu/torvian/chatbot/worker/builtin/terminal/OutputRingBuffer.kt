package eu.torvian.chatbot.worker.builtin.terminal

/**
 * Bounded text buffer with absolute character offsets.
 *
 * The buffer keeps the most recent [capacityBytes] UTF-8 bytes. Readers address content by an
 * absolute offset, so a reader that falls behind the retained window receives an explicit dropped
 * count instead of silently missing data.
 *
 * @param capacityBytes Maximum UTF-8 bytes retained; sanitized output beyond it evicts the oldest
 *   characters.
 */
internal class OutputRingBuffer(private val capacityBytes: Int) {

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    // A UTF-8 character never occupies fewer bytes than the UTF-16 characters that encode it, so a
    // char array of capacityBytes is always large enough for the retained text.
    private val chars = CharArray(capacityBytes)
    private val weights = IntArray(capacityBytes)
    private var head = 0
    private var size = 0
    private var byteCount = 0

    /** Absolute offset one past the last character ever appended. */
    var totalProduced: Long = 0
        private set

    /** Absolute offset of the first character still retained. */
    var oldestRetained: Long = 0
        private set

    /** UTF-8 bytes of all text before [oldestRetained], used for byte-accurate drop notices. */
    var oldestRetainedBytes: Long = 0
        private set

    /** UTF-8 bytes currently retained. */
    val retainedBytes: Int
        get() = byteCount

    /**
     * Appends sanitized text, evicting the oldest characters until the capacity fits.
     *
     * @param text Printable text to retain; an empty string leaves every offset unchanged.
     */
    fun append(text: String) {
        if (text.isEmpty()) return
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val isSurrogatePair = Character.isHighSurrogate(char) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            if (isSurrogatePair) {
                appendUnit(
                    first = char,
                    second = text[index + 1],
                    firstWeight = 4,
                    unitBytes = 4,
                )
                index += 2
            } else {
                val width = utf8Width(char)
                appendUnit(
                    first = char,
                    second = null,
                    firstWeight = width,
                    unitBytes = width,
                )
                index++
            }
        }
    }

    /**
     * Reads a prefix from [offset] that fits both [maxChars] and [maxBytes].
     *
     * The returned prefix never splits a surrogate pair and never includes a character whose UTF-8
     * width would exceed the byte budget. The cursor advances only by the returned characters.
     *
     * @param offset Absolute character offset to read from.
     * @param maxChars Maximum characters to return.
     * @param maxBytes Maximum UTF-8 bytes to return.
     * @return The returned text, the next cursor position, how many characters were dropped before
     *   the returned text, and whether more produced text remains after it.
     */
    fun read(offset: Long, maxChars: Int, maxBytes: Int): OutputChunk {
        val clampedOffset = offset.coerceIn(0L, totalProduced)
        val start = maxOf(clampedOffset, oldestRetained)
        val droppedChars = start - clampedOffset
        if (maxChars <= 0 || maxBytes <= 0) {
            return OutputChunk(
                text = "",
                nextOffset = start,
                droppedChars = droppedChars,
                hasMore = start < totalProduced,
            )
        }
        val text = StringBuilder(minOf(maxChars, size))
        var retainedIndex = (start - oldestRetained).toInt()
        var charsUsed = 0
        var bytesUsed = 0
        while (retainedIndex < size) {
            val char = charAt(retainedIndex)
            val isSurrogatePair = Character.isHighSurrogate(char) &&
                retainedIndex + 1 < size &&
                Character.isLowSurrogate(charAt(retainedIndex + 1))
            val unitChars = if (isSurrogatePair) 2 else 1
            val unitBytes = if (isSurrogatePair) 4 else utf8Width(char)
            if (charsUsed + unitChars > maxChars || bytesUsed + unitBytes > maxBytes) break
            repeat(unitChars) { text.append(charAt(retainedIndex + it)) }
            charsUsed += unitChars
            bytesUsed += unitBytes
            retainedIndex += unitChars
        }
        val nextOffset = start + charsUsed
        return OutputChunk(
            text = text.toString(),
            nextOffset = nextOffset,
            droppedChars = droppedChars,
            hasMore = nextOffset < totalProduced,
        )
    }

    /** Clears retained text and resets every offset to zero. */
    fun clear() {
        head = 0
        size = 0
        byteCount = 0
        totalProduced = 0
        oldestRetained = 0
        oldestRetainedBytes = 0
    }

    /**
     * Appends one code-point unit, evicting the oldest code points until it fits.
     *
     * @param first First UTF-16 character of the unit.
     * @param second Second UTF-16 character when the unit is a surrogate pair, otherwise null.
     * @param firstWeight Bytes counted against [byteCount] for [first]; a surrogate pair is
     *   accounted entirely on its high surrogate.
     * @param unitBytes Effective UTF-8 width of the whole unit.
     */
    private fun appendUnit(
        first: Char,
        second: Char?,
        firstWeight: Int,
        unitBytes: Int,
    ) {
        val unitChars = if (second == null) 1 else 2
        if (unitChars > chars.size || unitBytes > capacityBytes) {
            // A code point larger than the whole budget can never be retained, but it still
            // advances the produced offset so readers see it as dropped output.
            totalProduced += unitChars
            return
        }
        while (size > 0 && (size + unitChars > chars.size || byteCount + unitBytes > capacityBytes)) {
            evictOldestCodePoint()
        }
        writeChar(first, firstWeight)
        // The low surrogate carries no separate byte weight: eviction removes the pair together and
        // subtracts firstWeight plus this zero.
        if (second != null) writeChar(second, 0)
        byteCount += unitBytes
        totalProduced += unitChars
    }

    /**
     * Removes the oldest code point, keeping a surrogate pair together.
     */
    private fun evictOldestCodePoint() {
        val first = charAt(0)
        if (Character.isHighSurrogate(first) && size >= 2 && Character.isLowSurrogate(charAt(1))) {
            val evicted = weightAt(0) + weightAt(1)
            byteCount -= evicted
            oldestRetainedBytes += evicted
            head = (head + 2) % chars.size
            size -= 2
            oldestRetained += 2
        } else {
            val evicted = weightAt(0)
            byteCount -= evicted
            oldestRetainedBytes += evicted
            head = (head + 1) % chars.size
            size -= 1
            oldestRetained += 1
        }
    }

    /**
     * Writes one character and its byte weight at the tail of the ring.
     *
     * @param char Character to store.
     * @param weight Bytes attributed to the character.
     */
    private fun writeChar(char: Char, weight: Int) {
        val position = (head + size) % chars.size
        chars[position] = char
        weights[position] = weight
        size++
    }

    /**
     * @param index Retained character index, zero-based from the oldest character.
     * @return The character at [index].
     */
    private fun charAt(index: Int): Char = chars[(head + index) % chars.size]

    /**
     * @param index Retained character index, zero-based from the oldest character.
     * @return The byte weight stored for the character at [index].
     */
    private fun weightAt(index: Int): Int = weights[(head + index) % chars.size]

    private companion object {
        /**
         * @param char Character to measure.
         * @return UTF-8 byte width of [char], treating an unpaired surrogate as three bytes.
         */
        fun utf8Width(char: Char): Int = when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            else -> 3
        }
    }
}

/**
 * One incremental read from an [OutputRingBuffer].
 *
 * @property text Returned characters; never cuts a surrogate pair.
 * @property nextOffset Absolute offset the reader resumes from next time.
 * @property droppedChars Number of characters that fell out of the retained window before
 *   [text]; zero when the reader was still inside the window.
 * @property hasMore Whether produced text remains after [nextOffset].
 */
internal data class OutputChunk(
    val text: String,
    val nextOffset: Long,
    val droppedChars: Long,
    val hasMore: Boolean,
)
