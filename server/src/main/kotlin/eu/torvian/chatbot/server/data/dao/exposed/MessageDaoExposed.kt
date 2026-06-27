package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.ChatSessionOwnersTable
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import eu.torvian.chatbot.server.data.tables.SessionCurrentLeafTable
import eu.torvian.chatbot.server.data.tables.mappers.toAssistantMessage
import eu.torvian.chatbot.server.data.tables.mappers.toUserMessage
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.ResultSet
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Instant

/**
 * Exposed implementation of the [MessageDao].
 */
class MessageDaoExposed(
    private val transactionScope: TransactionScope
) : MessageDao {

    companion object {
        /**
         * Logger used for DAO diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(MessageDaoExposed::class.java)

        /**
         * Escape character used to keep `LIKE` matching literal when user queries contain wildcard characters.
         */
        private const val LIKE_ESCAPE_CHAR: Char = '\\'

        /**
         * Number of surrounding characters preserved on each side of a detected match.
         */
        private const val SNIPPET_CONTEXT_CHAR_COUNT: Int = 120

        /**
         * Ellipsis inserted when the snippet is trimmed away from the full message boundaries.
         */
        private const val SNIPPET_ELLIPSIS: String = "…"

        /**
         * Alias used by the raw visible-thread query for the selected message identifier.
         */
        private const val SEARCH_RESULT_MESSAGE_ID_COLUMN: String = "message_id"

        /**
         * Alias used by the raw visible-thread query for the owning session identifier.
         */
        private const val SEARCH_RESULT_SESSION_ID_COLUMN: String = "session_id"

        /**
         * Alias used by the raw visible-thread query for the owning session name.
         */
        private const val SEARCH_RESULT_SESSION_NAME_COLUMN: String = "session_name"

        /**
         * Alias used by the raw visible-thread query for the message role.
         */
        private const val SEARCH_RESULT_MESSAGE_ROLE_COLUMN: String = "message_role"

        /**
         * Alias used by the raw visible-thread query for the full message content.
         */
        private const val SEARCH_RESULT_MESSAGE_CONTENT_COLUMN: String = "message_content"

        /**
         * Alias used by the raw visible-thread query for the message creation timestamp.
         */
        private const val SEARCH_RESULT_MESSAGE_CREATED_AT_COLUMN: String = "message_created_at"

        /**
         * Raw SQLite query used to restrict search candidates to each session's currently visible branch.
         *
         * The recursive CTE starts at `session_current_leaf.message_id` for every owned session and walks
         * upward through `parent_message_id`, which excludes hidden sibling branches before any `LIKE`
         * filtering or ordering is applied.
         */
        private val VISIBLE_THREADS_ONLY_SEARCH_SQL: String = """
            WITH RECURSIVE visible_branch_messages(${SessionCurrentLeafTable.sessionId.name}, ${SessionCurrentLeafTable.messageId.name}) AS (
                SELECT
                    current_leaf.${SessionCurrentLeafTable.sessionId.name},
                    current_leaf.${SessionCurrentLeafTable.messageId.name}
                FROM ${SessionCurrentLeafTable.tableName} AS current_leaf
                INNER JOIN ${ChatSessionOwnersTable.tableName} AS owners
                    ON owners.${ChatSessionOwnersTable.sessionId.name} = current_leaf.${SessionCurrentLeafTable.sessionId.name}
                WHERE owners.${ChatSessionOwnersTable.userId.name} = %USER_ID%

                UNION ALL

                SELECT
                    visible_branch_messages.${SessionCurrentLeafTable.sessionId.name},
                    message.${ChatMessageTable.parentMessageId.name}
                FROM visible_branch_messages
                INNER JOIN ${ChatMessageTable.tableName} AS message
                    ON message.${ChatMessageTable.id.name} = visible_branch_messages.${SessionCurrentLeafTable.messageId.name}
                WHERE message.${ChatMessageTable.parentMessageId.name} IS NOT NULL
            )
            SELECT
                message.${ChatMessageTable.id.name} AS $SEARCH_RESULT_MESSAGE_ID_COLUMN,
                message.${ChatMessageTable.sessionId.name} AS $SEARCH_RESULT_SESSION_ID_COLUMN,
                session.${ChatSessionTable.name.name} AS $SEARCH_RESULT_SESSION_NAME_COLUMN,
                message.${ChatMessageTable.role.name} AS $SEARCH_RESULT_MESSAGE_ROLE_COLUMN,
                message.${ChatMessageTable.content.name} AS $SEARCH_RESULT_MESSAGE_CONTENT_COLUMN,
                message.${ChatMessageTable.createdAt.name} AS $SEARCH_RESULT_MESSAGE_CREATED_AT_COLUMN
            FROM visible_branch_messages
            INNER JOIN ${ChatMessageTable.tableName} AS message
                ON message.${ChatMessageTable.id.name} = visible_branch_messages.${SessionCurrentLeafTable.messageId.name}
            INNER JOIN ${ChatSessionTable.tableName} AS session
                ON session.${ChatSessionTable.id.name} = message.${ChatMessageTable.sessionId.name}
            WHERE lower(message.${ChatMessageTable.content.name}) LIKE %LIKE_PATTERN% ESCAPE '$LIKE_ESCAPE_CHAR'
            ORDER BY message.${ChatMessageTable.createdAt.name} DESC
            LIMIT %LIMIT%
        """.trimIndent()
    }

    /**
     * Lightweight projection containing only the fields needed to build [MessageSearchResult].
     *
     * Snippet construction stays in Kotlin so both DSL-backed and raw-SQL-backed search paths share the
     * same highlighting and truncation behavior.
     *
     * @property sessionId Identifier of the session that owns the message.
     * @property sessionName Human-readable name of the owning session.
     * @property messageId Identifier of the matching message.
     * @property messageRole Role of the message author.
     * @property content Full message content used for snippet generation.
     * @property createdAtMillis Creation timestamp stored in the database row.
     */
    private data class SearchMessageRow(
        val sessionId: Long,
        val sessionName: String,
        val messageId: Long,
        val messageRole: ChatMessage.Role,
        val content: String,
        val createdAtMillis: Long,
    )

    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> =
        transactionScope.transaction {
            // Perform a single query with left join to get all messages with their assistant data if any
            val results = ChatMessageTable
                .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                .selectAll()
                .where { ChatMessageTable.sessionId eq sessionId }
                .orderBy(ChatMessageTable.createdAt to SortOrder.ASC)

            // Transform to appropriate message types based on role
            results.map { row ->
                when (row[ChatMessageTable.role]) {
                    ChatMessage.Role.USER -> row.toUserMessage()
                    ChatMessage.Role.ASSISTANT -> row.toAssistantMessage()
                }
            }
        }

    override suspend fun getMessageById(id: Long): Either<MessageError.MessageNotFound, ChatMessage> =
        transactionScope.transaction {
            either {
                // Perform a single query with left join to get all needed data
                val query = ChatMessageTable
                    .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                    .selectAll()
                    .where { ChatMessageTable.id eq id }
                    .singleOrNull()
                ensure(query != null) { MessageError.MessageNotFound(id) }

                // Convert to appropriate message type based on role
                when (query[ChatMessageTable.role]) {
                    ChatMessage.Role.USER -> query.toUserMessage()
                    ChatMessage.Role.ASSISTANT -> query.toAssistantMessage()
                }
            }
        }

    override suspend fun searchMessagesByUserId(
        userId: Long,
        query: String,
        scope: MessageSearchScope,
        limit: Int,
    ): List<MessageSearchResult> =
        transactionScope.transaction {
            val normalizedQuery = query.lowercase()
            val likePattern = buildContainsLikePattern(normalizedQuery)

            val matchedRows = when (scope) {
                MessageSearchScope.VISIBLE_THREADS_ONLY ->
                    searchVisibleThreadMessagesByUserId(userId = userId, likePattern = likePattern, limit = limit)

                MessageSearchScope.ALL_THREADS ->
                    searchAllThreadMessagesByUserId(userId = userId, likePattern = likePattern, limit = limit)
            }

            matchedRows.map { row -> row.toMessageSearchResult(query, normalizedQuery) }
        }

    override suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?,
        fileReferences: List<FileReference>,
        createdAt: Instant?,
        updatedAt: Instant?
    ): Either<InsertMessageError, ChatMessage> =
        transactionScope.transaction {
            either {
                val now = System.currentTimeMillis()
                val createdAtMillis = createdAt?.toEpochMilliseconds() ?: now
                val updatedAtMillis = updatedAt?.toEpochMilliseconds() ?: now

                // 1.Determine Parent and Children for New Message
                val (newParentId, newChildrenIds) = if (targetMessageId == null) {
                    // Root message case
                    null to emptyList()
                } else {
                    // Relative insert case
                    val targetRow = ChatMessageTable
                        .selectAll()
                        .where { ChatMessageTable.id eq targetMessageId }
                        .singleOrNull()
                    ensure(targetRow != null) { InsertMessageError.ParentNotFound(targetMessageId) }

                    val targetSessionId = targetRow[ChatMessageTable.sessionId].value
                    ensure(targetSessionId == sessionId) {
                        InsertMessageError.ParentNotInSession(
                            targetMessageId,
                            sessionId
                        )
                    }

                    when (position) {
                        MessageInsertPosition.ABOVE -> {
                            val targetParentId = targetRow[ChatMessageTable.parentMessageId]?.value
                            targetParentId to listOf(targetMessageId)
                        }

                        MessageInsertPosition.BELOW -> {
                            val targetChildrenJson = targetRow[ChatMessageTable.childrenMessageIds]
                            val targetChildren = Json.decodeFromString<List<Long>>(targetChildrenJson)
                            targetMessageId to targetChildren
                        }

                        MessageInsertPosition.APPEND -> {
                            // Append as a new child (leaf) to the target
                            targetMessageId to emptyList()
                        }
                    }
                }

                // 2. Insert the new message
                val insertedRow = insertChatMessageRow(
                    sessionId = sessionId,
                    content = content,
                    parentMessageId = newParentId,
                    role = role,
                    createdAt = createdAtMillis,
                    updatedAt = updatedAtMillis,
                    childrenMessageIds = newChildrenIds,
                    fileReferences = fileReferences
                )
                val newMessageId = insertedRow[ChatMessageTable.id].value

                // Insert Assistant Metadata if needed
                if (role == ChatMessage.Role.ASSISTANT) {
                    AssistantMessageTable.insert {
                        it[AssistantMessageTable.messageId] = newMessageId
                        it[AssistantMessageTable.modelId] = modelId
                        it[AssistantMessageTable.settingsId] = settingsId
                    }
                }

                // 3. Handle Re-linking of neighbors
                if (targetMessageId != null) {
                    when (position) {
                        MessageInsertPosition.ABOVE -> {
                            // Update Target's Parent -> New Message
                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[parentMessageId] = newMessageId
                            }

                            // Update Old Parent's Children (if exists) -> Replace Target with New Message
                            // newParentId is the old parent of the target
                            if (newParentId != null) {
                                val parentRow = ChatMessageTable
                                    .selectAll().where { ChatMessageTable.id eq newParentId }.singleOrNull()
                                    ?: throw IllegalStateException("Parent $newParentId not found")

                                val parentChildren =
                                    Json.decodeFromString<MutableList<Long>>(parentRow[ChatMessageTable.childrenMessageIds])
                                val idx = parentChildren.indexOf(targetMessageId)
                                if (idx != -1) {
                                    parentChildren[idx] = newMessageId
                                    ChatMessageTable.update({ ChatMessageTable.id eq newParentId }) {
                                        it[childrenMessageIds] = Json.encodeToString(parentChildren)
                                    }
                                }
                            }
                        }

                        MessageInsertPosition.BELOW -> {
                            // Update Target's Children -> [New Message]
                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[childrenMessageIds] = Json.encodeToString(listOf(newMessageId))
                            }

                            // Update all old children's Parent -> New Message
                            // newChildrenIds contains the old children of the target
                            if (newChildrenIds.isNotEmpty()) {
                                ChatMessageTable.update({ ChatMessageTable.id inList newChildrenIds }) {
                                    it[parentMessageId] = newMessageId
                                }
                            }
                        }

                        MessageInsertPosition.APPEND -> {
                            // Append as a new child to the target
                            // We need to add the new message ID to the target's children list
                            val targetRow = ChatMessageTable
                                .selectAll().where { ChatMessageTable.id eq targetMessageId }.singleOrNull()
                                ?: throw IllegalStateException("Target $targetMessageId not found")

                            val targetChildren =
                                Json.decodeFromString<MutableList<Long>>(targetRow[ChatMessageTable.childrenMessageIds])
                            targetChildren.add(newMessageId)

                            ChatMessageTable.update({ ChatMessageTable.id eq targetMessageId }) {
                                it[childrenMessageIds] = Json.encodeToString(targetChildren)
                            }
                        }
                    }
                }

                // 4. Return the new message
                if (role == ChatMessage.Role.ASSISTANT) {
                    ChatMessage.AssistantMessage(
                        id = newMessageId,
                        sessionId = sessionId,
                        content = content,
                        createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
                        updatedAt = Instant.fromEpochMilliseconds(updatedAtMillis),
                        parentMessageId = newParentId,
                        childrenMessageIds = newChildrenIds,
                        fileReferences = fileReferences,
                        modelId = modelId,
                        settingsId = settingsId
                    )
                } else {
                    ChatMessage.UserMessage(
                        id = newMessageId,
                        sessionId = sessionId,
                        content = content,
                        createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
                        updatedAt = Instant.fromEpochMilliseconds(updatedAtMillis),
                        parentMessageId = newParentId,
                        childrenMessageIds = newChildrenIds,
                        fileReferences = fileReferences
                    )
                }
            }
        }

    override suspend fun updateMessageContent(
        id: Long,
        content: String,
        fileReferences: List<FileReference>?
    ): Either<MessageError.MessageNotFound, ChatMessage> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq id }) {
                    it[ChatMessageTable.content] = content
                    it[ChatMessageTable.updatedAt] = System.currentTimeMillis()
                    if (fileReferences != null) {
                        it[ChatMessageTable.fileReferences] = Json.encodeToString(fileReferences)
                    }
                }
                ensure(updatedRowCount != 0) { MessageError.MessageNotFound(id) }

                // Retrieve the updated message
                getMessageById(id).getOrElse { throw IllegalStateException("Failed to retrieve updated message") }
            }
        }

    override suspend fun deleteMessageRecursively(id: Long): Either<MessageError.MessageNotFound, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Deleting message with ID $id")

                // Get the full message to access its children IDs
                val message = getMessageById(id).bind()

                // Recursively delete children
                for (childId in message.childrenMessageIds) {
                    deleteRecursively(childId)
                }

                // Delete the base message
                val deletedCount = ChatMessageTable.deleteWhere { ChatMessageTable.id eq id }
                if (deletedCount == 0)
                    throw IllegalStateException("Failed to delete message with ID $id")

                // If this message had a parent, update the parent's children list
                val parentId = message.parentMessageId
                if (parentId != null) {
                    val parentRow = ChatMessageTable
                        .selectAll().where { ChatMessageTable.id eq parentId }.singleOrNull()
                    if (parentRow == null) {
                        throw IllegalStateException("Parent message with ID $parentId not found when deleting child $id")
                    }
                    val currentChildrenIdsString = parentRow[ChatMessageTable.childrenMessageIds]
                    val childrenList = Json.decodeFromString<MutableList<Long>>(currentChildrenIdsString)

                    if (!childrenList.remove(id)) {
                        throw IllegalStateException("Child ID $id not found in parent ID $parentId children list")
                    }
                    val newChildrenIdsString = Json.encodeToString(childrenList)
                    val updatedRowCount = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                        it[ChatMessageTable.childrenMessageIds] = newChildrenIdsString
                    }
                    if (updatedRowCount == 0) {
                        throw IllegalStateException("Failed to update parent message with ID $parentId when deleting child $id")
                    }
                }
                logger.debug("DAO: Successfully deleted message with ID $id")
            }
        }

    override suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("DAO: Deleting single message with ID $id (non-recursive)")

                // Load the message to delete
                val message = getMessageById(id).bind()
                val parentId = message.parentMessageId
                val sessionId = message.sessionId
                val children: List<Long> = message.childrenMessageIds

                // If there is a parent, update parent's children list by replacing this id with the children sequence
                if (parentId != null) {
                    val parentRow = ChatMessageTable
                        .selectAll().where { ChatMessageTable.id eq parentId }.singleOrNull()
                        ?: throw IllegalStateException("Parent message with ID $parentId not found when deleting child $id")

                    val parentSessionId = parentRow[ChatMessageTable.sessionId].value
                    if (parentSessionId != sessionId) {
                        throw IllegalStateException("Session mismatch between parent $parentId and child $id")
                    }

                    val parentChildrenJson = parentRow[ChatMessageTable.childrenMessageIds]
                    val parentChildren = Json.decodeFromString<MutableList<Long>>(parentChildrenJson)

                    val idx = parentChildren.indexOf(id)
                    if (idx == -1) {
                        throw IllegalStateException("Child ID $id not found in parent ID $parentId children list")
                    }
                    // Replace the deleted node with its children in place
                    parentChildren.removeAt(idx)
                    if (children.isNotEmpty()) {
                        parentChildren.addAll(idx, children)
                    }
                    val newParentChildrenJson = Json.encodeToString(parentChildren)
                    val updated = ChatMessageTable.update({ ChatMessageTable.id eq parentId }) {
                        it[childrenMessageIds] = newParentChildrenJson
                    }
                    if (updated == 0) {
                        throw IllegalStateException("Failed to update parent message with ID $parentId when deleting single $id")
                    }
                }

                // Reparent all direct children to the grandparent (or root)
                for (childId in children) {
                    val count = ChatMessageTable.update({ ChatMessageTable.id eq childId }) {
                        it[ChatMessageTable.parentMessageId] = parentId
                    }
                    if (count == 0) {
                        throw IllegalStateException("Failed to reparent child $childId when deleting single $id")
                    }
                }

                // Remove assistant metadata if any
                AssistantMessageTable.deleteWhere { AssistantMessageTable.messageId eq id }

                // Finally, delete only this message row
                val deleted = ChatMessageTable.deleteWhere { ChatMessageTable.id eq id }
                if (deleted == 0) {
                    throw IllegalStateException("Failed to delete message with ID $id")
                }

                logger.debug("DAO: Successfully deleted single message with ID $id")
            }
        }

    /**
     * Helper method to recursively delete a message and all its children.
     * Should only be called from within a transaction block.
     */
    private suspend fun deleteRecursively(messageId: Long) {
        // Get the full message to access its children IDs
        val message = getMessageById(messageId).getOrElse {
            throw IllegalStateException("Failed to retrieve message with ID $messageId")
        }
        // First recursively delete all children
        for (childId in message.childrenMessageIds) {
            deleteRecursively(childId)
        }
        // Then delete this message
        val deletedCount = ChatMessageTable.deleteWhere { ChatMessageTable.id eq messageId }
        if (deletedCount == 0) {
            throw IllegalStateException("Failed to delete message with ID $messageId")
        }
    }

    /**
     * Executes the existing all-thread search path using the Exposed DSL.
     *
     * This branch intentionally preserves the pre-scope behavior so the new `ALL_THREADS` mode remains
     * equivalent to the old implementation.
     *
     * @param userId Owner whose accessible sessions should be searched.
     * @param likePattern Escaped `LIKE` pattern built from the validated query.
     * @param limit Maximum number of rows to return.
     * @return Matching rows ordered by recency across all owned threads.
     */
    private fun searchAllThreadMessagesByUserId(
        userId: Long,
        likePattern: String,
        limit: Int,
    ): List<SearchMessageRow> {
        return ChatMessageTable
            .join(
                ChatSessionTable,
                JoinType.INNER,
                additionalConstraint = { ChatMessageTable.sessionId eq ChatSessionTable.id }
            )
            .join(
                ChatSessionOwnersTable,
                JoinType.INNER,
                additionalConstraint = { ChatSessionTable.id eq ChatSessionOwnersTable.sessionId }
            )
            .selectAll()
            .where {
                // SQLite has no portable ILIKE, so match via LOWER(column) LIKE LOWER(query) instead.
                (ChatSessionOwnersTable.userId eq userId) and
                    (ChatMessageTable.content.lowerCase() like LikePattern(likePattern, LIKE_ESCAPE_CHAR))
            }
            .orderBy(ChatMessageTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row -> row.toSearchMessageRow() }
            .toList()
    }

    /**
     * Executes the visible-thread-only search path using a recursive SQLite CTE.
     *
     * Exposed's DSL does not currently provide an ergonomic way to express this recursive branch walk, so the
     * raw SQL is localized here in the DAO. The query first computes the visible branch per owned session and
     * only then applies the text search predicate, ordering, and limit.
     *
     * @param userId Owner whose visible branches should be searched.
     * @param likePattern Escaped `LIKE` pattern built from the validated query.
     * @param limit Maximum number of rows to return.
     * @return Matching rows ordered by recency across only the visible branches.
     */
    private fun searchVisibleThreadMessagesByUserId(
        userId: Long,
        likePattern: String,
        limit: Int,
    ): List<SearchMessageRow> {
        // The query is assembled from trusted numeric values plus an explicitly quoted SQL literal so the raw CTE
        // remains injection-safe while staying localized to this DAO.
        val sql = VISIBLE_THREADS_ONLY_SEARCH_SQL
            .replace("%USER_ID%", userId.toString())
            .replace("%LIKE_PATTERN%", quoteSqlLiteral(likePattern))
            .replace("%LIMIT%", limit.toString())

        return TransactionManager.current().exec(sql, explicitStatementType = StatementType.SELECT) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toSearchMessageRow())
                }
            }
        } ?: emptyList()
    }

    /**
     * Maps a DSL-selected row into the lightweight search projection shared by both search scopes.
     *
     * @return Search projection containing only the data needed for snippet generation and response ordering.
     */
    private fun ResultRow.toSearchMessageRow(): SearchMessageRow {
        return SearchMessageRow(
            sessionId = this[ChatMessageTable.sessionId].value,
            sessionName = this[ChatSessionTable.name],
            messageId = this[ChatMessageTable.id].value,
            messageRole = this[ChatMessageTable.role],
            content = this[ChatMessageTable.content],
            createdAtMillis = this[ChatMessageTable.createdAt],
        )
    }

    /**
     * Maps a raw-SQL result row into the lightweight search projection shared by both search scopes.
     *
     * @return Search projection containing only the data needed for snippet generation and response ordering.
     */
    private fun ResultSet.toSearchMessageRow(): SearchMessageRow {
        return SearchMessageRow(
            sessionId = getLong(SEARCH_RESULT_SESSION_ID_COLUMN),
            sessionName = getString(SEARCH_RESULT_SESSION_NAME_COLUMN),
            messageId = getLong(SEARCH_RESULT_MESSAGE_ID_COLUMN),
            messageRole = ChatMessage.Role.valueOf(getString(SEARCH_RESULT_MESSAGE_ROLE_COLUMN)),
            content = getString(SEARCH_RESULT_MESSAGE_CONTENT_COLUMN),
            createdAtMillis = getLong(SEARCH_RESULT_MESSAGE_CREATED_AT_COLUMN),
        )
    }

    /**
     * Converts a projected search row into a [MessageSearchResult].
     *
     * Filtering happens in SQL before rows are materialized. This mapper only derives snippet metadata for rows
     * already known to match.
     *
     * @param query Search string already validated by the service layer.
     * @param normalizedQuery Lower-cased copy of [query] reused for the rare defensive fallback.
     * @return A populated search result for the matched row.
     */
    private fun SearchMessageRow.toMessageSearchResult(query: String, normalizedQuery: String): MessageSearchResult {
        val fullContent = content
        val firstMatchIndex = findSnippetMatchIndex(fullContent, query, normalizedQuery)

        val matchLength = query.length
        val (snippet, matchStartIndex, matchEndExclusive) = buildSnippet(fullContent, firstMatchIndex, matchLength)

        return MessageSearchResult(
            sessionId = sessionId,
            sessionName = sessionName,
            messageId = messageId,
            messageRole = messageRole,
            snippet = snippet,
            matchStartIndex = matchStartIndex,
            matchEndExclusive = matchEndExclusive,
            createdAt = Instant.fromEpochMilliseconds(createdAtMillis)
        )
    }

    /**
     * Builds a `contains` pattern for `LIKE` while escaping wildcard characters found in user input.
     *
     * @param normalizedQuery Lower-cased query text that should be matched literally.
     * @return Pattern equivalent to `%query%` with `%`, `_`, and the escape character itself escaped.
     */
    private fun buildContainsLikePattern(normalizedQuery: String): String {
        val escapedQuery = buildString(normalizedQuery.length) {
            normalizedQuery.forEach { character ->
                if (character == LIKE_ESCAPE_CHAR || character == '%' || character == '_') {
                    append(LIKE_ESCAPE_CHAR)
                }
                append(character)
            }
        }
        return "%$escapedQuery%"
    }

    /**
     * Quotes a string for safe inclusion as a SQL literal in the localized raw CTE query.
     *
     * @param value Literal value that should be embedded in raw SQL.
     * @return SQL string literal with embedded single quotes doubled.
     */
    private fun quoteSqlLiteral(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    /**
     * Locates the first match to highlight inside the original message content.
     *
     * The primary path uses Kotlin's case-insensitive substring search so snippet offsets are calculated against
     * the original string. A secondary normalized lookup is kept as a narrow fallback in case the SQL
     * `LOWER(...) LIKE ...` predicate and Kotlin's case-insensitive matching disagree on edge-case casing.
     *
     * @param content Full message content returned by SQL.
     * @param query Search string already validated by the service layer.
     * @param normalizedQuery Lower-cased copy of [query].
     * @return Inclusive start index of the first match inside [content].
     * @throws IllegalStateException When SQL returned a row that can no longer be highlighted consistently.
     */
    private fun findSnippetMatchIndex(content: String, query: String, normalizedQuery: String): Int {
        return content.indexOf(query, ignoreCase = true)
            .takeIf { it >= 0 }
            ?: content.lowercase().indexOf(normalizedQuery).takeIf { it >= 0 }
            ?: throw IllegalStateException("Search query matched in SQL but not during snippet generation.")
    }

    /**
     * Extracts a highlighted snippet around a single match.
     *
     * The returned offsets are relative to the snippet itself. Ellipses are added only when the snippet no
     * longer includes the full message boundaries, and the offsets are adjusted accordingly.
     *
     * @param content Full message content.
     * @param matchIndex Inclusive match start inside [content].
     * @param matchLength Character length of the matched query.
     * @return Triple containing snippet text, snippet-relative match start, and snippet-relative match end.
     */
    private fun buildSnippet(content: String, matchIndex: Int, matchLength: Int): Triple<String, Int, Int> {
        val windowStart = max(matchIndex - SNIPPET_CONTEXT_CHAR_COUNT, 0)
        val windowEnd = min(matchIndex + matchLength + SNIPPET_CONTEXT_CHAR_COUNT, content.length)

        val hasTrimmedPrefix = windowStart > 0
        val hasTrimmedSuffix = windowEnd < content.length

        val prefix = if (hasTrimmedPrefix) SNIPPET_ELLIPSIS else ""
        val suffix = if (hasTrimmedSuffix) SNIPPET_ELLIPSIS else ""
        val snippetBody = content.substring(windowStart, windowEnd)
        val snippet = prefix + snippetBody + suffix

        val matchStartIndex = prefix.length + (matchIndex - windowStart)
        val matchEndExclusive = matchStartIndex + matchLength

        return Triple(snippet, matchStartIndex, matchEndExclusive)
    }

    /**
     * Helper method to insert a new chat message row into the database.
     * Should only be called from within a transaction block.
     */
    private fun insertChatMessageRow(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        role: ChatMessage.Role,
        createdAt: Long,
        updatedAt: Long,
        childrenMessageIds: List<Long> = emptyList(),
        fileReferences: List<FileReference> = emptyList()
    ): ResultRow {
        val insertStatement = ChatMessageTable.insert {
            it[ChatMessageTable.sessionId] = sessionId
            it[ChatMessageTable.role] = role
            it[ChatMessageTable.content] = content
            it[ChatMessageTable.createdAt] = createdAt
            it[ChatMessageTable.updatedAt] = updatedAt
            it[ChatMessageTable.parentMessageId] = parentMessageId
            it[ChatMessageTable.childrenMessageIds] = Json.encodeToString(childrenMessageIds)
            it[ChatMessageTable.fileReferences] = Json.encodeToString(fileReferences)
        }
        return insertStatement.resultedValues?.first()
            ?: throw IllegalStateException("Failed to retrieve newly inserted message (role=$role)")
    }
}
