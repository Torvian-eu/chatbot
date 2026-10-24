package eu.torvian.chatbot.worker.credential

import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Worker-side authorization of one relayed credential profile.
 *
 * A profile fixes the command a session will run as another account, so it must carry a device
 * signature the worker can verify against its own trust store.
 */
interface CredentialProfileValidator {
    /**
     * @param profile Profile relayed by the server.
     * @param signedRequest Detached signature the app produced over the persisted profile payload.
     * @param workerId Worker id this worker's owner pinned at registration, or null when no pin is
     *   configured, in which case the row's own worker id is not checked.
     * @return Structured authorization outcome.
     */
    suspend fun validate(
        profile: CredentialProfileDto,
        signedRequest: SignedRequest?,
        workerId: Long?,
    ): CredentialProfileValidationResult
}

/**
 * Structured outcome of worker-side credential-profile authorization.
 */
sealed interface CredentialProfileValidationResult {
    /** The worker may cache and later run the profile. */
    data object Authorized : CredentialProfileValidationResult

    /**
     * Base contract for rejections that are logged and, for notifications, returned to the server.
     */
    sealed interface Rejected : CredentialProfileValidationResult {
        /** Stable machine-readable rejection code. */
        val code: String

        /** Human-readable rejection summary; never contains a secret. */
        val message: String

        /** Optional additional diagnostics suitable for logs. */
        val details: String?
    }

    /** The relayed profile carried no detached signature. */
    data object MissingSignedRequest : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_MISSING_SIGNED_REQUEST"
        override val message: String = "Missing detached signed request for credential profile"
        override val details: String? = null
    }

    /**
     * The detached signature could not be verified.
     *
     * @property details Additional verification diagnostics, when available.
     */
    data class InvalidSignature(override val details: String? = null) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_INVALID_SIGNATURE"
        override val message: String = "Detached credential profile signature verification failed"
    }

    /**
     * The signature references a signer the worker does not trust.
     *
     * @property signerId Identifier of the unknown signer.
     */
    data class UnknownSigner(val signerId: String) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_UNKNOWN_SIGNER"
        override val message: String = "Credential profile was signed by an unknown device"
        override val details: String? = null
    }

    /**
     * The signed payload could not be decoded as a profile request.
     *
     * @property details Diagnostic describing the failure.
     */
    data class MalformedSignedPayload(override val details: String? = null) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_MALFORMED_SIGNED_PAYLOAD"
        override val message: String = "Signed credential profile payload could not be decoded"
    }

    /**
     * The relayed profile disagrees with the payload the user signed.
     *
     * @property fields Request-derived fields whose values differ.
     */
    data class DtoMismatch(val fields: List<String>) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_DTO_MISMATCH"
        override val message: String = "Relayed credential profile does not match its signed payload"
        override val details: String = fields.joinToString(",")
    }

    /**
     * The relayed row is bound to a different worker than the response says.
     *
     * This catches a server-side join or pagination mistake. It cannot detect a hostile server: a
     * server that can mislabel the field can mislabel the rows, and decryptability is the only
     * cryptographic gate on a secret.
     *
     * @property expected Worker id from the bootstrap response.
     * @property actual Worker id on the relayed row.
     */
    data class ForeignWorker(val expected: Long, val actual: Long) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_FOREIGN_WORKER"
        override val message: String = "Relayed credential row is bound to a different worker"
        override val details: String = "expected=$expected actual=$actual"
    }

    /**
     * The profile violates the shared credential rules.
     *
     * @property violations Human-readable messages that never echo a value.
     */
    data class ValidationFailed(val violations: List<String>) : Rejected {
        override val code: String = "CREDENTIAL_PROFILE_VALIDATION_FAILED"
        override val message: String = "Credential profile failed worker-side validation"
        override val details: String = violations.joinToString("; ")
    }
}
