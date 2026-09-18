package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable

/**
 * Machine-readable cause explaining why an assistant message is not complete.
 *
 * The cause travels on [ChatMessage.AssistantMessage] (and therefore on session details and on every
 * chat event that carries an assistant message) so clients can render a localized label for the
 * interruption case instead of parsing server-authored text.
 *
 * Semantics:
 * - `null` (the property is absent) means *no terminal cause is known*: the message is either complete
 *   or still in flight (a streaming placeholder), or was abandoned by a crash. It is never a synonym
 *   for "failed".
 * - [INTERRUPTED_BY_USER] is set only when the user stopped the generation; the message carries no
 *   error code and no reason text, because the client localizes the label from the cause alone.
 * - [FAILED] is set for every generation failure, including output truncation and a provider stream
 *   that ended without a completion signal; it is always accompanied by an error code and a bounded,
 *   provider-internal-free reason text.
 *
 * Values are persisted and sent by enum name (`INTERRUPTED_BY_USER`, `FAILED`); the storage column is
 * nullable so no sentinel such as `NONE` has to exist here.
 */
@Serializable
enum class AssistantMessageIncompleteCause {
    /**
     * The user stopped the generation before it completed (stop control, with or without a socket
     * teardown). The partial content received so far is persisted with the message; no error code or
     * reason text is recorded because the client derives a localized label from this cause.
     */
    INTERRUPTED_BY_USER,

    /**
     * The generation failed: a provider/network/configuration error, an unexpected server error, a
     * provider stream that ended without a terminal chunk, or a response that exceeded the output
     * character limit. The message always carries an error code and a bounded, user-facing reason.
     */
    FAILED
}
