package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import kotlin.time.Clock

/**
 * Server-side storage for worker-bound credential profiles.
 *
 * A profile holds no secret value: `secretRef` names a row in [WorkerCredentialSecretTable] on the
 * same worker. The reference is a string rather than a foreign key so that deleting a secret does not
 * cascade into operator-authored profile metadata.
 *
 * @property workerId Worker this profile is bound to.
 * @property name Operator-facing name, unique per worker.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the secret that supplies the value.
 * @property command Executable the profile starts.
 * @property argsJson JSON-encoded argument list.
 * @property channel Delivery channel name.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property environmentJson JSON-encoded map of non-secret environment entries.
 * @property askpassVariablesJson JSON-encoded list of names receiving the askpass script path.
 * @property isEnabled Whether the profile may be attached.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
object WorkerCredentialProfileTable : LongIdTable("worker_credential_profiles") {
    val workerId = reference("worker_id", WorkersTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val secretRef = varchar("secret_ref", 255)
    val command = text("command")
    val argsJson = text("args_json").default("[]")
    val channel = varchar("channel", 32)
    val envName = varchar("env_name", 255).nullable()
    val fileName = varchar("file_name", 255).nullable()
    val askpassUsername = varchar("askpass_username", 255).nullable()
    val environmentJson = text("environment_json").default("{}")
    val askpassVariablesJson = text("askpass_variables_json").default("[]")
    val isEnabled = bool("is_enabled").default(true)
    val createdAt = long("created_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
    val updatedAt = long("updated_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
}
