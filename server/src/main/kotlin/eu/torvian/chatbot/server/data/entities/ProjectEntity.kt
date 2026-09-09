package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the `projects` database table.
 *
 * The project's member-role ids are NOT stored in this table — they live in the
 * `agent_roles.project_id` column and are loaded separately (batch-wise for list endpoints). The
 * owner lives in `project_owners` and is likewise resolved separately.
 *
 * @property id Unique identifier for the project.
 * @property name Unique (per owner user) machine-readable project name.
 * @property description Free-form description of the project.
 * @property createdAt Timestamp when the project was created.
 * @property updatedAt Timestamp when the project was last updated.
 */
data class ProjectEntity(
    val id: Long,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant
)