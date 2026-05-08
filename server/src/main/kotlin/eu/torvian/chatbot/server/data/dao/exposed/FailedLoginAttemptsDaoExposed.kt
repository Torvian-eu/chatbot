package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.FailedLoginAttemptDao
import eu.torvian.chatbot.server.data.tables.FailedLoginAttemptsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Exposed implementation of [FailedLoginAttemptDao].
 *
 * This implementation uses the Exposed ORM to persist and query failed login attempts,
 * enabling the sliding-window lockout policy for authentication.
 */
class FailedLoginAttemptsDaoExposed(
    private val transactionScope: TransactionScope
) : FailedLoginAttemptDao {

    override suspend fun recordFailure(username: String, ipAddress: String, deviceId: String) =
        transactionScope.transaction {
            FailedLoginAttemptsTable.insert {
                it[FailedLoginAttemptsTable.username] = username
                it[FailedLoginAttemptsTable.ipAddress] = ipAddress
                it[FailedLoginAttemptsTable.deviceId] = deviceId
                it[FailedLoginAttemptsTable.attemptTimestamp] = System.currentTimeMillis()
            }
            Unit
        }

    override suspend fun countFailuresByUsername(username: String, sinceMillis: Long): Int =
        transactionScope.transaction {
            FailedLoginAttemptsTable.selectAll()
                .where {
                    (FailedLoginAttemptsTable.username eq username) and
                        (FailedLoginAttemptsTable.attemptTimestamp greaterEq sinceMillis)
                }
                .count()
                .toInt()
        }

    override suspend fun countFailuresByIp(ipAddress: String, sinceMillis: Long): Int =
        transactionScope.transaction {
            FailedLoginAttemptsTable.selectAll()
                .where {
                    (FailedLoginAttemptsTable.ipAddress eq ipAddress) and
                        (FailedLoginAttemptsTable.attemptTimestamp greaterEq sinceMillis)
                }
                .count()
                .toInt()
        }

    override suspend fun clearFailures(username: String) =
        transactionScope.transaction {
            // Only clear username-based failures, NOT IP-based failures
            // This prevents reset attacks where an attacker could clear IP-based lockouts
            FailedLoginAttemptsTable.deleteWhere {
                FailedLoginAttemptsTable.username eq username
            }
            Unit
        }

    override suspend fun cleanupOldRecords(thresholdMillis: Long) =
        transactionScope.transaction {
            FailedLoginAttemptsTable.deleteWhere {
                FailedLoginAttemptsTable.attemptTimestamp less thresholdMillis
            }
            Unit
        }
}
