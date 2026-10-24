package eu.torvian.chatbot.worker.credential

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * One decrypted credential value.
 *
 * The value is held as characters so it can be wiped: [close] overwrites the buffer, and callers must
 * invoke it once the channel material has copied what it needs. A JVM `String` built from the value
 * for an environment map cannot be wiped; that copy is minimized and dropped after the spawn, so the
 * `env` channel is the one to avoid when the deployment has a choice.
 *
 * @property chars Backing buffer of the value, wiped on [close].
 */
internal class SecretValue(private val chars: CharArray) : AutoCloseable {
    /** Number of characters in the value. */
    val length: Int
        get() = chars.size

    /**
     * @return A copy of the value as characters, for callers that must hand out their own buffer.
     */
    fun copyChars(): CharArray = chars.copyOf()

    /**
     * @return A copy of the value's UTF-8 bytes, for callers that write it to a file.
     */
    fun copyBytes(): ByteArray {
        // Encoded straight from the character buffer: a String intermediate would outlive every wipe
        // this class performs and would only be collectable, never erased.
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    /** Wipes the buffer; idempotent, so a double close is harmless. */
    override fun close() {
        chars.fill('\u0000')
    }
}
