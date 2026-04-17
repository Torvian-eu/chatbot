package eu.torvian.chatbot.common.models.api.worker.protocol.builders

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandMessagePayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import kotlinx.serialization.json.JsonObject

/**
 * Builds a `session.hello` envelope to start worker-side session negotiation.
 *
 * @param id New transport-level message identifier.
 * @param interactionId Interaction identifier to group handshake messages.
 * @param payload Structured payload describing worker identity and capabilities.
 * @return Worker protocol envelope ready to send.
 */
fun sessionHello(
    id: String,
    interactionId: String,
    payload: WorkerSessionHelloPayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.SESSION_HELLO,
    replyTo = null,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerSessionHelloPayload")
)

/**
 * Builds a `session.welcome` envelope for an existing hello interaction.
 *
 * @param id New transport-level message identifier.
 * @param replyTo Message identifier that this envelope acknowledges.
 * @param interactionId Interaction identifier shared with the originating hello message.
 * @param payload Structured payload describing the negotiated session properties.
 * @return Worker protocol envelope ready to send.
 */
fun sessionWelcome(
    id: String,
    replyTo: String?,
    interactionId: String,
    payload: WorkerSessionWelcomePayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.SESSION_WELCOME,
    replyTo = replyTo,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerSessionWelcomePayload")
)

/**
 * Builds a `command.accepted` envelope for a known interaction identifier.
 *
 * @param id New transport-level message identifier.
 * @param replyTo Message identifier that this envelope acknowledges.
 * @param interactionId Interaction identifier to group messages belonging to the same logical interaction.
 * @param payload Structured payload describing the accepted command.
 * @return Worker protocol envelope ready to send.
 */
fun commandAccepted(
    id: String,
    replyTo: String?,
    interactionId: String,
    payload: WorkerCommandAcceptedPayload = WorkerCommandAcceptedPayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.COMMAND_ACCEPTED,
    replyTo = replyTo,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerCommandAcceptedPayload")
)

/**
 * Builds a `command.rejected` envelope for a command that cannot be processed.
 *
 * @param id New transport-level message identifier.
 * @param replyTo Message identifier that this envelope acknowledges.
 * @param interactionId Interaction identifier to group messages belonging to the same logical interaction.
 * @param payload Structured payload describing why the command was rejected.
 * @return Worker protocol envelope ready to send.
 */
fun commandRejected(
    id: String,
    replyTo: String?,
    interactionId: String,
    payload: WorkerCommandRejectedPayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.COMMAND_REJECTED,
    replyTo = replyTo,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerCommandRejectedPayload")
)

/**
 * Builds a `command.result` envelope for a completed command.
 *
 * @param id New transport-level message identifier.
 * @param replyTo Message identifier that this envelope acknowledges.
 * @param interactionId Interaction identifier to group messages belonging to the same logical interaction.
 * @param payload Structured payload describing the final command result.
 * @return Worker protocol envelope ready to send.
 */
fun commandResult(
    id: String,
    replyTo: String?,
    interactionId: String,
    payload: WorkerCommandResultPayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.COMMAND_RESULT,
    replyTo = replyTo,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerCommandResultPayload")
)

/**
 * Builds a `command.message` envelope for follow-up session messaging.
 *
 * @param id New transport-level message identifier.
 * @param replyTo Message identifier that this envelope acknowledges.
 * @param interactionId Interaction identifier to group messages belonging to the same logical interaction.
 * @param payload Structured payload describing the follow-up command message.
 * @return Worker protocol envelope ready to send.
 */
fun commandMessage(
    id: String,
    replyTo: String?,
    interactionId: String,
    payload: WorkerCommandMessagePayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.COMMAND_MESSAGE,
    replyTo = replyTo,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerCommandMessagePayload")
)

/**
 * Builds a `command.request` envelope to initiate a new command.
 *
 * @param id New transport-level message identifier.
 * @param interactionId Interaction identifier to group messages belonging to the same logical interaction.
 * @param payload Structured payload describing the command request.
 * @return Worker protocol envelope ready to send.
 */
fun commandRequest(
    id: String,
    interactionId: String,
    payload: WorkerCommandRequestPayload
): WorkerProtocolMessage = WorkerProtocolMessage(
    id = id,
    type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
    replyTo = null,
    interactionId = interactionId,
    payload = payload.toJsonObjectOrError("WorkerCommandRequestPayload")
)

/**
 * Encodes a payload DTO to a JSON object and fails fast if local protocol code becomes inconsistent.
 *
 * @receiver Payload DTO that should always be serializable.
 * @param targetType Type name used in the fail-fast error message.
 * @return Encoded payload JSON object.
 */
private inline fun <reified T> T.toJsonObjectOrError(targetType: String): JsonObject {
    return encodeProtocolPayload(this, targetType).getOrElse { error("Failed to encode $targetType: $it") }
}
