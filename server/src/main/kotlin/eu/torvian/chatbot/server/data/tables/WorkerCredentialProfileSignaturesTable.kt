package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import kotlin.time.Clock

/**
 * Signature metadata for one credential profile and the device that authored it.
 *
 * The payload JSON preserves the exact bytes the app signed, so verification never depends on
 * re-serialisation. One signature per device is kept: a second write from the same device replaces its
 * row rather than accumulating history.
 *
 * @property profileId Profile the signature belongs to.
 * @property userDeviceId Device that produced the signature, and therefore the signer identity.
 * @property signature Base64-encoded signature over `payloadJson`.
 * @property timestamp Signature creation time included in the signed input, in epoch milliseconds.
 * @property nonce Replay-protection token included in the signed input.
 * @property payloadJson Exact raw JSON string that was signed.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
object WorkerCredentialProfileSignaturesTable : CompositeIdTable("worker_credential_profile_signatures") {
    val profileId = reference("profile_id", WorkerCredentialProfileTable, onDelete = ReferenceOption.CASCADE)
    val userDeviceId = reference("user_device_id", UserDevicesTable, onDelete = ReferenceOption.CASCADE)
    val signature = text("signature")
    val timestamp = long("timestamp")
    val nonce = varchar("nonce", 255)
    val payloadJson = text("payload_json")
    val createdAt = long("created_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
    val updatedAt = long("updated_at").clientDefault { Clock.System.now().toEpochMilliseconds() }

    override val primaryKey = PrimaryKey(profileId, userDeviceId)

    init {
        uniqueIndex(
            "worker_credential_profile_signatures_profile_id_user_device_id_unique",
            profileId,
            userDeviceId
        )
    }
}
