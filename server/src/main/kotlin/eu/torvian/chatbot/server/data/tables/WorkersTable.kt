package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Worker identity table.
 *
 * Stores ownership, certificate identity, and scopes for service principals.
 *
 * @property ownerUserId FK to the owning user.
 * @property workerUid Public UID generated during worker setup.
 * @property displayName Human-readable worker label.
 * @property certificatePem Persisted PEM certificate.
 * @property certificateFingerprint Unique SHA-256 certificate fingerprint.
 * @property allowedScopesJson JSON-encoded list of worker scopes.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property lastSeenAt Last successful authentication timestamp.
 * @property toolNamePrefix Optional prefix applied to the public names of the worker's built-in tools.
 *   Must match `^[a-zA-Z0-9_-]+$` (letters, digits, underscores, dashes); enforced by the server before
 *   persistence. A `null` value means "no prefix".
 */
object WorkersTable : LongIdTable("workers") {
    val ownerUserId = reference("owner_user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val workerUid = varchar("worker_uid", 64).uniqueIndex()
    val displayName = varchar("display_name", 255)
    val certificatePem = text("certificate_pem")
    val certificateFingerprint = varchar("certificate_fingerprint", 255).uniqueIndex()
    val allowedScopesJson = text("allowed_scopes_json")
    val createdAt = long("created_at")
    val lastSeenAt = long("last_seen_at").nullable()
    val toolNamePrefix = varchar("tool_name_prefix", 255).nullable()
}
