package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.SendMessageRequest
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.core.error.agent.SendMessageRequestBuildError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Default implementation of [SendMessageRequestBuilder].
 *
 * Parses the tool-call input JSON for `chat_session_id`, `message`, and the optional `mode`
 * parameter (see [OperatorToolCatalog]), then validates through [SessionOwnershipDao.getOwner] that
 * the target session exists and is owned by the calling user before assembling the
 * [SendMessageRequest]. The persisted [ToolCall.id] is used as the correlation key echoed back in
 * the operator's `ToolExecutionResult`.
 *
 * All argument parsing happens before any I/O: malformed input fails with
 * [SendMessageRequestBuildError.InvalidInput] and never queries the ownership DAO, so a bad tool
 * call cannot leak whether a session id exists. The ownership check itself covers both existence
 * and user-scoping with one DAO call (the DAO is self-transactional; a session without an owner row
 * is inaccessible by definition).
 *
 * @property sessionOwnershipDao User-scoped session lookup used to validate the target session.
 * @property json JSON codec used to decode the tool-call arguments and mode wire values.
 */
class DefaultSendMessageRequestBuilder(
    private val sessionOwnershipDao: SessionOwnershipDao,
    private val json: Json
) : SendMessageRequestBuilder {

    override suspend fun build(
        userId: Long,
        toolCall: ToolCall
    ): Either<SendMessageRequestBuildError, SendMessageRequest> =
        buildInternal(userId, toolCall)

    /**
     * Parses the send-message arguments and validates the target session.
     *
     * Parsing precedes the ownership lookup: the target session is only ever queried for input that
     * is structurally valid (integral id, non-blank message, known mode), so malformed input can
     * never leak whether a session id exists. The ownership result is a logical
     * [SendMessageRequestBuildError.SessionNotFound] /
     * [SendMessageRequestBuildError.SessionNotAccessible] before any relay.
     *
     * @param userId Ownership scope for the target session.
     * @param toolCall Persisted call to parse.
     * @return Validated send payload or a logical build failure.
     */
    private suspend fun buildInternal(
        userId: Long,
        toolCall: ToolCall
    ): Either<SendMessageRequestBuildError, SendMessageRequest> = either {
            val arguments = parseArguments(toolCall.input).bind()

            // Tool arguments are untrusted JSON; safe casts keep strings/doubles/objects/arrays in
            // the typed error path. Only a JSON NUMBER with an integral Long value is accepted — a
            // JSON string "7" is deliberately rejected as malformed tool input.
            val chatSessionId = arguments[OperatorToolCatalog.SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY]
                ?.let { it as? JsonPrimitive }
                ?.takeUnless { it.isString }
                ?.contentOrNull
                ?.toLongOrNull()
                ?: raise(
                    SendMessageRequestBuildError.InvalidInput(
                        "Missing or invalid '${OperatorToolCatalog.SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY}' in send_message arguments"
                    )
                )

            val message = arguments[OperatorToolCatalog.SEND_MESSAGE_MESSAGE_PROPERTY]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: raise(
                    SendMessageRequestBuildError.InvalidInput(
                        "Missing or blank '${OperatorToolCatalog.SEND_MESSAGE_MESSAGE_PROPERTY}' in send_message arguments"
                    )
                )

            // Optional mode: absent → wait-for-response (default). A present value must be a JSON
            // string equal to one of the two serialized wire values; strings/numbers/objects/arrays/
            // explicit null are malformed tool input. The enum's @SerialName values are the schema
            // values too, so decoding through the serializer keeps both aligned.
            val mode = when (val element = arguments[OperatorToolCatalog.SEND_MESSAGE_MODE_PROPERTY]) {
                null -> OperatorToolMode.WAIT_FOR_RESPONSE
                is JsonPrimitive -> element.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw -> runCatching { json.decodeFromString(OperatorToolMode.serializer(), "\"$raw\"") }.getOrNull() }
                    ?: raise(
                        SendMessageRequestBuildError.InvalidInput(
                            "'${OperatorToolCatalog.SEND_MESSAGE_MODE_PROPERTY}' must be one of 'wait_for_response', 'fire_and_forget' in send_message arguments"
                        )
                    )

                else -> raise(
                    SendMessageRequestBuildError.InvalidInput(
                        "'${OperatorToolCatalog.SEND_MESSAGE_MODE_PROPERTY}' must be one of 'wait_for_response', 'fire_and_forget' in send_message arguments"
                    )
                )
            }

            // Validate existence + same-user ownership before assembly. One DAO call covers both:
            // a session without an owner row is inaccessible by definition.
            val owner = withError({ error: GetOwnerError ->
                when (error) {
                    is GetOwnerError.ResourceNotFound ->
                        SendMessageRequestBuildError.SessionNotFound(chatSessionId)
                }
            }) {
                sessionOwnershipDao.getOwner(chatSessionId).bind()
            }
            ensure(owner == userId) {
                SendMessageRequestBuildError.SessionNotAccessible(chatSessionId)
            }

            SendMessageRequest(
                chatSessionId = chatSessionId,
                message = message,
                mode = mode,
                toolCallId = toolCall.id
            )
        }

    /**
     * Decodes the tool-call input JSON into a [JsonObject].
     *
     * A missing or non-object input is a caller error; a malformed input is reported as
     * [SendMessageRequestBuildError.InvalidInput] so the LLM sees a readable tool error rather than
     * a crash.
     *
     * @param input Raw arguments string from the persisted tool call.
     * @return Either the parsed arguments object or an [SendMessageRequestBuildError.InvalidInput].
     */
    private fun parseArguments(input: String?): Either<SendMessageRequestBuildError, JsonObject> = either {
        ensure(!input.isNullOrBlank()) { SendMessageRequestBuildError.InvalidInput("send_message arguments are empty") }
        val element = runCatching { json.parseToJsonElement(input) }.getOrElse { error ->
            raise(SendMessageRequestBuildError.InvalidInput("Failed to parse send_message arguments: ${error.message}"))
        }
        ensure(element is JsonObject) {
            SendMessageRequestBuildError.InvalidInput("send_message arguments must be a JSON object")
        }
        element.jsonObject
    }
}