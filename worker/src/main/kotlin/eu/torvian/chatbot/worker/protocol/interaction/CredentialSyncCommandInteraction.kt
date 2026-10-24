package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.Either
import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpRuntimeCommandProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialProfileCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialProfileDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialProfileUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialSecretCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialSecretDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCredentialSecretUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialCommandErrorResultData
import eu.torvian.chatbot.worker.credential.CredentialSyncCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Active interaction that applies one credential sync command lifecycle.
 *
 * Supported command types: `credential.profile.create`, `credential.profile.update`,
 * `credential.profile.delete`, `credential.secret.create`, `credential.secret.update` and
 * `credential.secret.delete`.
 *
 * Every command only touches the in-memory cache, so an operator change never interrupts an attached
 * target.
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property executor Cache-mutation adapter for credential commands.
 * @property emitter Emitter used for outbound lifecycle envelopes.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class CredentialSyncCommandInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    private val executor: CredentialSyncCommandExecutor,
    emitter: OutboundMessageEmitter,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider(),
) : ChannelBackedInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {

    /**
     * Executes one credential sync lifecycle.
     */
    override suspend fun start() {
        when (requestPayload.commandType) {
            WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialProfileCreateCommandData() },
                execute = { command -> executor.createProfile(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.profile.create payload"
            )

            WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialProfileUpdateCommandData() },
                execute = { command -> executor.updateProfile(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.profile.update payload"
            )

            WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialProfileDeleteCommandData() },
                execute = { command -> executor.deleteProfile(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.profile.delete payload"
            )

            WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialSecretCreateCommandData() },
                execute = { command -> executor.createSecret(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.secret.create payload"
            )

            WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialSecretUpdateCommandData() },
                execute = { command -> executor.updateSecret(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.secret.update payload"
            )

            WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE -> runOneShotCommand(
                decodeRequest = { toWorkerCredentialSecretDeleteCommandData() },
                execute = { command -> executor.deleteSecret(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode credential.secret.delete payload"
            )

            else -> emitter.emit(
                rejectedMessage(
                    reasonCode = WorkerProtocolRejectionReasons.UNSUPPORTED_COMMAND_TYPE,
                    message = "Unsupported credential command type: ${requestPayload.commandType}"
                )
            )
        }
    }

    /**
     * Emits accepted, executes, and emits the terminal result for one command.
     *
     * @param decodeRequest Decodes the request payload into typed command data.
     * @param execute Runs the command and returns either an error payload or typed success data.
     * @param mapSuccessResult Encodes typed success data as a result payload.
     * @param decodeFailureMessage Message used when the request payload cannot be decoded.
     */
    private suspend fun <TRequest, TSuccess> runOneShotCommand(
        decodeRequest: WorkerCommandRequestPayload.() -> Either<
            WorkerMcpRuntimeCommandProtocolMappingError,
            TRequest
            >,
        execute: suspend (TRequest) -> Either<WorkerCredentialCommandErrorResultData, TSuccess>,
        mapSuccessResult: (TSuccess) -> Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload>,
        decodeFailureMessage: String,
    ) {
        val commandData = requestPayload.decodeRequest().getOrElse { mappingError ->
            emitter.emit(
                rejectedMessage(
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD,
                    message = decodeFailureMessage,
                    details = mappingErrorDetails(mappingError)
                )
            )
            return
        }

        emitter.emit(
            commandAccepted(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = WorkerCommandAcceptedPayload
            )
        )

        val resultPayload = execute(commandData).fold(
            ifLeft = { errorResult -> errorResult.toWorkerCommandResultPayload() },
            ifRight = mapSuccessResult
        ).getOrElse { mappingError ->
            error("Unexpected failure encoding ${requestPayload.commandType} result payload: $mappingError")
        }

        emitter.emit(
            commandResult(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = resultPayload
            )
        )
    }

    /**
     * Builds a `command.rejected` envelope for a credential command failure.
     *
     * @param reasonCode Stable machine-readable rejection reason.
     * @param message Human-readable rejection description.
     * @param details Optional structured diagnostics.
     * @return Rejection envelope ready to send.
     */
    private fun rejectedMessage(
        reasonCode: String,
        message: String,
        details: JsonObject? = null,
    ): WorkerProtocolMessage = commandRejected(
        id = messageIdProvider.nextMessageId(),
        replyTo = envelope.id,
        interactionId = interactionId,
        payload = WorkerCommandRejectedPayload(
            commandType = requestPayload.commandType,
            reasonCode = reasonCode,
            message = message,
            details = details
        )
    )

    /**
     * Converts a protocol mapping failure into structured rejection diagnostics.
     *
     * @param mappingError Mapping failure returned by the shared protocol helpers.
     * @return Structured diagnostics suitable for `command.rejected` payload details.
     */
    private fun mappingErrorDetails(mappingError: WorkerMcpRuntimeCommandProtocolMappingError): JsonObject =
        buildJsonObject {
            when (mappingError) {
                is WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType -> {
                    put("error", "invalid_command_type")
                    put("expected", mappingError.expected)
                    put("actual", mappingError.actual)
                }

                is WorkerMcpRuntimeCommandProtocolMappingError.SerializationFailed -> {
                    put("error", "serialization_failed")
                    put("operation", mappingError.operation)
                    put("targetType", mappingError.targetType)
                    mappingError.details?.let { put("details", it) }
                }
            }
        }
}
