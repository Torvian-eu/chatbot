package db.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Statement

/**
 * Normalizes the former `agent_roles.instructions_json` JSON column into shareable instruction rows.
 *
 * Creates the `instructions`, `agent_role_instructions` and `instruction_owners` tables, backfills
 * one instruction row per stored JSON entry (never de-duplicated, so identical entries within one
 * role stay distinct rows) with link rows whose `sequence` is the entry's list position, and finally
 * drops `instructions_json`. The JSON is parsed leniently at the `JsonElement` level: unparseable
 * column values degrade to zero backfilled rows for that role, and entries missing fields fall back
 * to empty strings so one malformed entry can never fail the whole migration. A `spawnable_agents`
 * row stores `message = NULL` because its text is generated per role at read time.
 *
 * Implemented as a Java migration so the backfill needs no SQLite JSON1 or window functions. The
 * migration runs on migration connections where foreign key enforcement is off, so the DDL and the
 * column drop are safe inside Flyway's migration transaction.
 *
 * A linked instruction row cannot be deleted: the link foreign key on `instruction_id` is `RESTRICT`,
 * so the row disappears only after every link is gone. This is correct for both deletion paths — the
 * instruction-delete surface refuses a linked row outright, and the role-deletion sweep deletes a
 * candidate row only after the deleted role's link rows cascaded away and nothing else links it.
 * SQLite cannot alter a foreign key, so an already-migrated database keeps whatever policy its
 * `agent_role_instructions` table was created with; the service-level refusal still enforces the rule
 * there.
 */
class V32__ShareableAgentRoleInstructions : BaseJavaMigration() {

    companion object {
        /** Creates the instruction rows: one per instruction, with nullable generated-message marker. */
        private const val CREATE_INSTRUCTIONS_SQL = """
            CREATE TABLE instructions (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                type       VARCHAR(64)  NOT NULL,
                name       VARCHAR(255) NOT NULL,
                message    TEXT,
                custom     TEXT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
        """

        /**
         * Creates the ordered role↔instruction links. The composite primary key enforces link
         * uniqueness at the storage level; the role foreign key cascades so deleting a role removes
         * only its link rows, while the instruction foreign key restricts so a still-linked
         * instruction row cannot be deleted.
         */
        private const val CREATE_ROLE_INSTRUCTIONS_SQL = """
            CREATE TABLE agent_role_instructions (
                agent_role_id  BIGINT NOT NULL,
                instruction_id BIGINT NOT NULL,
                sequence       INTEGER NOT NULL,
                PRIMARY KEY (agent_role_id, instruction_id),
                FOREIGN KEY (agent_role_id)  REFERENCES agent_roles (id) ON DELETE CASCADE,
                FOREIGN KEY (instruction_id) REFERENCES instructions (id) ON DELETE RESTRICT
            )
        """

        /** Reverse-lookup index powering shared-row resolution (which roles link a given row). */
        private const val CREATE_ROLE_INSTRUCTIONS_INSTRUCTION_IDX_SQL =
            "CREATE INDEX agent_role_instructions_instruction_idx ON agent_role_instructions (instruction_id)"

        /** Creates the single-owner side table mirroring `model_preset_owners`. */
        private const val CREATE_INSTRUCTION_OWNERS_SQL = """
            CREATE TABLE instruction_owners (
                instruction_id BIGINT NOT NULL,
                user_id        BIGINT NOT NULL,
                PRIMARY KEY (instruction_id),
                FOREIGN KEY (instruction_id) REFERENCES instructions (id) ON DELETE CASCADE,
                FOREIGN KEY (user_id)        REFERENCES users (id)        ON DELETE CASCADE
            )
        """

        /** Removes the denormalized JSON column the new tables replace. */
        private const val DROP_INSTRUCTIONS_JSON_SQL = "ALTER TABLE agent_roles DROP COLUMN instructions_json"

        /** Parses stored JSON without any DTO coupling, so future wire changes cannot alter backfill. */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The stored type key of the generated spawn allow-list marker. Hardcoded so the migration's
         * backfill keeps matching historical rows even if the application constants ever change.
         */
        private const val SPAWNABLE_AGENTS_TYPE = "spawnable_agents"
    }

    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use { statement ->
            statement.execute(CREATE_INSTRUCTIONS_SQL)
            statement.execute(CREATE_ROLE_INSTRUCTIONS_SQL)
            statement.execute(CREATE_ROLE_INSTRUCTIONS_INSTRUCTION_IDX_SQL)
            statement.execute(CREATE_INSTRUCTION_OWNERS_SQL)
        }
        backfill(connection)
        connection.createStatement().use { statement ->
            statement.execute(DROP_INSTRUCTIONS_JSON_SQL)
        }
    }

    /**
     * Copies every stored JSON entry into an instruction row plus its ordered link.
     *
     * @param connection The migration connection.
     */
    private fun backfill(connection: java.sql.Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, instructions_json FROM agent_roles").use { rows ->
                while (rows.next()) {
                    val roleId = rows.getLong(1)
                    val instructionsJson = rows.getString(2)
                    val ownerId = loadOwner(connection, roleId)
                    parseEntries(instructionsJson).forEachIndexed { sequence, entry ->
                        val instructionId = insertInstruction(connection, entry)
                        insertLink(connection, roleId, instructionId, sequence)
                        if (ownerId != null) {
                            insertOwner(connection, instructionId, ownerId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Parses a stored instruction array leniently.
     *
     * A parse failure or a non-array value degrades to zero entries (mirroring the role-read decode
     * fallback) instead of aborting the migration. Non-object array elements are kept as empty
     * objects so every stored entry still yields exactly one row at its original list position.
     *
     * @param instructionsJson The raw JSON column value.
     * @return The entry objects in stored order.
     */
    private fun parseEntries(instructionsJson: String?): List<JsonObject> {
        val parsed = runCatching { json.parseToJsonElement(instructionsJson ?: "") }.getOrNull() as? JsonArray
            ?: return emptyList()
        return parsed.map { it as? JsonObject ?: JsonObject(emptyMap()) }
    }

    /**
     * Resolves the owner of the source role, if the ownership row exists.
     *
     * A role without an ownership row keeps its owner-less inconsistency: its instruction rows are
     * still backfilled (no content is lost) but get no owner row.
     *
     * @param connection The migration connection.
     * @param roleId The source role id.
     * @return The owner's user id, or null when the role has no ownership row.
     */
    private fun loadOwner(connection: java.sql.Connection, roleId: Long): Long? =
        connection.prepareStatement("SELECT user_id FROM agent_role_owners WHERE role_id = ?").use { statement ->
            statement.setLong(1, roleId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    /**
     * Inserts one instruction row from a stored JSON entry and returns its generated id.
     *
     * Field extraction is lenient: missing `type`/`name`/`message` fall back to empty strings (the
     * read-time mapper then drops such rows with a warning), `message` is forced to NULL for
     * `spawnable_agents`, and `custom` is stored as raw JSON text only when it is an object.
     *
     * @param connection The migration connection.
     * @param entry The raw JSON entry.
     * @return The generated instruction id.
     */
    private fun insertInstruction(connection: java.sql.Connection, entry: JsonObject): Long {
        val type = entry.stringOrEmpty("type")
        val name = entry.stringOrEmpty("name")
        val message = if (type == SPAWNABLE_AGENTS_TYPE) null else entry.stringOrEmpty("message")
        val custom = (entry["custom"] as? JsonObject)?.toString()
        connection.prepareStatement(
            "INSERT INTO instructions (type, name, message, custom, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, type)
            statement.setString(2, name)
            statement.setString(3, message)
            statement.setString(4, custom)
            // The stored rows carry no historical timestamps; the migration time is the best
            // available approximation and both columns are NOT NULL.
            statement.setLong(5, System.currentTimeMillis())
            statement.setLong(6, System.currentTimeMillis())
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Generated key missing for backfilled instruction of a stored entry" }
                return keys.getLong(1)
            }
        }
    }

    /**
     * Inserts the ordered role↔instruction link for a backfilled row.
     *
     * @param connection The migration connection.
     * @param roleId The source role id.
     * @param instructionId The backfilled instruction id.
     * @param sequence The entry's list position (zero-based).
     */
    private fun insertLink(connection: java.sql.Connection, roleId: Long, instructionId: Long, sequence: Int) {
        connection.prepareStatement(
            "INSERT INTO agent_role_instructions (agent_role_id, instruction_id, sequence) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setLong(1, roleId)
            statement.setLong(2, instructionId)
            statement.setInt(3, sequence)
            statement.executeUpdate()
        }
    }

    /**
     * Copies the source role's ownership onto a backfilled instruction row.
     *
     * @param connection The migration connection.
     * @param instructionId The backfilled instruction id.
     * @param userId The owner of the source role.
     */
    private fun insertOwner(connection: java.sql.Connection, instructionId: Long, userId: Long) {
        connection.prepareStatement(
            "INSERT INTO instruction_owners (instruction_id, user_id) VALUES (?, ?)"
        ).use { statement ->
            statement.setLong(1, instructionId)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    /**
     * Reads a JSON string field leniently.
     *
     * @receiver The raw JSON entry.
     * @param key The field name.
     * @return The string content (also for numbers/booleans), or "" when absent, null, or not a
     *         primitive.
     */
    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull ?: ""
}
