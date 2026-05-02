package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.WorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.tables.WorkerAuthChallengesTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Converts an Exposed row from `worker_auth_challenges` into a [WorkerAuthChallengeEntity].
 *
 * @receiver Result row that includes `WorkerAuthChallengesTable` columns.
 * @return Mapped [WorkerAuthChallengeEntity].
 */
fun ResultRow.toWorkerAuthChallengeEntity(): WorkerAuthChallengeEntity = WorkerAuthChallengeEntity(
    challengeId = this[WorkerAuthChallengesTable.challengeId],
    workerId = this[WorkerAuthChallengesTable.workerId].value,
    challenge = this[WorkerAuthChallengesTable.challenge],
    expiresAt = Instant.fromEpochMilliseconds(this[WorkerAuthChallengesTable.expiresAt]),
    consumedAt = this[WorkerAuthChallengesTable.consumedAt]?.let(Instant::fromEpochMilliseconds),
    createdAt = Instant.fromEpochMilliseconds(this[WorkerAuthChallengesTable.createdAt])
)

