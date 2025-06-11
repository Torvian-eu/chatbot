Okay, Mark. Here is the code for **PR 4: Server Backend - Core DAOs (Session, Message, Group Basic CRUD)**. This PR implements the interfaces and the initial Exposed concrete implementations for the core Data Access Objects based on the schema defined in PR 2. It covers the DAO-level logic for basic CRUD for sessions, messages, and groups, including handling the persistence of threading and grouping data within these entities.

Crucially, as discussed, these DAO methods are `suspend` but *do not* contain their own `transaction {}` blocks wrapped in `withContext(Dispatchers.IO)`. They rely on the `TransactionScope` provided by the Service layer (implemented in PR 5) to provide the transaction context and IO dispatcher.

It includes KDoc documentation for all public components and mapping functions.

* * *

**PR 4: Server Backend - Core DAOs (Session, Message, Group Basic CRUD)**

*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Implement the basic Create, Read (list/by ID), Update, and Delete operations in the Exposed DAOs for Sessions, Messages, and Groups. These implementations should use suspend functions and expect to be called within a `TransactionScope`. Include mappings from Exposed `ResultRow` to common DTOs. This PR includes the logic for handling message threading (`parentMessageId`, `childrenMessageIds` serialization/deserialization, `addChildToMessage`, `removeChildFromMessage`, and recursive `deleteMessage`) and session grouping (`groupId` updates, `ungroupSessions`).
*   **Stories Addressed:** E2.S1 (Session insert), E2.S3 (Session list), E2.S4 (Session by ID, Messages by Session ID), E2.S5 (Session update), E2.S6 (Session delete), E3.S3 (Message update), E3.S4 (Message delete logic), E1.S4 (Message insert, addChildToMessage), E1.S7 (addChildToMessage), E6.S1 (Session update for groupId), E6.S3 (Group insert), E6.S4 (Group list), E6.S5 (Group update), E6.S6 (Group delete logic - handled in Service using DAO methods), Partial Epic 4/5 (Model/Settings DAO interfaces/placeholders). Uses E7.S4 schema and E7.S6/TransactionScope (implicitly).
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SessionDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/SessionDaoExposed.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/MessageDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/MessageDaoExposed.kt`
  *   `server/src/main/kotlin/eu.torvian/chatbot/server/data/dao/GroupDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/GroupDaoExposed.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ModelDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/ModelDaoExposed.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SettingsDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/SettingsDaoExposed.kt`

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SessionDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
/**
*   Data Access Object for ChatSession entities.
*   Methods are suspend because they perform database operations
*   and are expected to be called within a coroutine context managed by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
*/
interface SessionDao {
    /**
     * Retrieves a list of all chat session summaries, ordered by update time.
     * Includes group name via a join if assigned to a group.
     * @return A list of [ChatSessionSummary] objects.
     */
    suspend fun getAllSessions(): List<ChatSessionSummary>
    
    /**
     * Retrieves the full details of a specific chat session, including all its messages.
     * Messages are loaded separately and attached.
     * @param id The ID of the session to retrieve.
     * @return The [ChatSession] object with messages, or null if not found.
     */
    suspend fun getSessionById(id: Long): ChatSession?
    
    /**
     * Inserts a new chat session record into the database.
     * @param name The name for the new session.
     * @param groupId Optional ID of the group to assign the session to.
     * @param currentModelId Optional ID of the model to set as current for the session.
     * @param currentSettingsId Optional ID of the settings to set as current for the session.
     * @return The newly created [ChatSession] object, fetched after insertion.
     */
    suspend fun insertSession(name: String, groupId: Long?, currentModelId: Long?, currentSettingsId: Long?): ChatSession
    
    /**
     * Updates the details of an existing chat session.
     * Updates fields like name, group, current model/settings, and leaf message.
     * @param session The [ChatSession] object with updated details.
     * @return The updated [ChatSession] object, fetched after update.
     */
    suspend fun updateSession(session: ChatSession): ChatSession
    
    /**
     * Deletes a chat session by ID.
     * Relies on the database's foreign key CASCADE constraint to delete associated messages.
     * @param id The ID of the session to delete.
     * @return True if a session was deleted, false otherwise.
     */
    suspend fun deleteSession(id: Long): Boolean
    
    /**
     * Updates the `groupId` for all sessions currently assigned to a specific group,
     * setting their `groupId` to null (ungrouping them).
     * Used by the [eu.torvian.chatbot.server.service.GroupService] when a group is deleted.
     * @param groupId The ID of the group whose sessions should be ungrouped.
     */
    suspend fun ungroupSessions(groupId: Long)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/SessionDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.models.ChatGroups
import eu.torvian.chatbot.server.data.models.ChatSessions
import org.jetbrains.exposed.sql.*
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateStatement
/**
*   Exposed implementation of the [SessionDao].
    
*   Methods execute within the ambient transaction provided by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
    
*/
class SessionDaoExposed : SessionDao {
    /**
     * Lazily injected MessageDao to avoid circular dependency issues if injected in constructor.
     * Alternatively, refactor to pass needed sub-DAOs in specific methods or have Service orchestrate.
     * For V1.1 simplicity, manual instantiation within a guaranteed transaction is acceptable here.
     */
    private val messageDao: MessageDaoExposed by lazy { MessageDaoExposed() } // Assuming DI setup allows this

    override suspend fun getAllSessions(): List<ChatSessionSummary> {
        return ChatSessions
            .join(ChatGroups, JoinType.LEFT, additionalConstraint = { ChatSessions.groupId eq ChatGroups.id })
            .selectAll()
            .orderBy(ChatSessions.updatedAt to SortOrder.DESC) // Order by recent activity (E2.S3 AC)
            .map { it.toChatSessionSummary() }
    }
    override suspend fun getSessionById(id: Long): ChatSession? {
        // Retrieve the session record
        val sessionRow = ChatSessions.select { ChatSessions.id eq id }.singleOrNull() ?: return null
        // Retrieve ALL messages for this session (needed by UI for thread tree building) (E2.S4)
        // Call MessageDao within the same transaction context
        val messages = messageDao.getMessagesBySessionId(id)
        // Map row and messages to ChatSession DTO
        return sessionRow.toChatSession(messages)
    }
    override suspend fun insertSession(name: String, groupId: Long?, currentModelId: Long?, currentSettingsId: Long?): ChatSession {
        val insertStatement = ChatSessions.insert {
            it[ChatSessions.name] = name
            it[ChatSessions.createdAt] = System.currentTimeMillis() // Use Long timestamp
            it[ChatSessions.updatedAt] = System.currentTimeMillis() // Use Long timestamp
            it[ChatSessions.groupId] = groupId // Save group ID if provided (E2.S1, E6.S1)
            it[ChatSessions.currentModelId] = currentModelId // Save model ID if provided (E4.S7)
            it[ChatSessions.currentSettingsId] = currentSettingsId // Save settings ID if provided (E4.S7)
            it[ChatSessions.currentLeafMessageId] = null // Initialize as null (E1.S4 backend)
        }
        // Fetch the full newly created session object including DB-generated ID
        return getSessionById(insertStatement[ChatSessions.id].value) // Fetching ensures all fields are populated correctly
            ?: throw IllegalStateException("Failed to retrieve newly inserted session") // Should not happen
    }
    override suspend fun updateSession(session: ChatSession): ChatSession {
        val updatedRowCount = ChatSessions.update({ ChatSessions.id eq session.id }) {
            it[name] = session.name
            it[updatedAt] = System.currentTimeMillis() // Always update timestamp on change
            it[groupId] = session.groupId // Handle nullable group ID (E6.S1)
            it[currentModelId] = session.currentModelId // (E4.S7)
            it[currentSettingsId] = session.currentSettingsId // (E4.S7)
            it[currentLeafMessageId] = session.currentLeafMessageId // (E1.S4 backend)
        }
        if (updatedRowCount == 0) throw IllegalArgumentException("Session with ID ${session.id} not found for update")
        return getSessionById(session.id) ?: throw IllegalStateException("Failed to retrieve updated session")
    }
    override suspend fun deleteSession(id: Long): Boolean {
        // ON DELETE CASCADE on ChatMessages.sessionId handles message deletion automatically (E2.S6 backend AC)
        val deletedCount = ChatSessions.deleteWhere { ChatSessions.id eq id }
        return deletedCount > 0
    }
    override suspend fun ungroupSessions(groupId: Long) {
        ChatSessions.update({ ChatSessions.groupId eq groupId }) {
            it[groupId] = null
        }
    }
    // --- Mapping Functions ---
    /**
     * Maps an Exposed ResultRow from a join of ChatSessions and ChatGroups to a ChatSessionSummary DTO.
     */
    private fun ResultRow.toChatSessionSummary() = ChatSessionSummary(
        id = this[ChatSessions.id].value,
        name = this[ChatSessions.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessions.createdAt]), // Convert Long to Instant
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessions.updatedAt]), // Convert Long to Instant
        groupId = this[ChatSessions.groupId]?.value, // Handle nullable Long?
        groupName = this.getOrNull(ChatGroups.name) // Group name is nullable from LEFT JOIN
    )
    /**
     * Maps an Exposed ResultRow from ChatSessions and a list of ChatMessages to a ChatSession DTO.
     */
    private fun ResultRow.toChatSession(messages: List<ChatMessage>) = ChatSession(
        id = this[ChatSessions.id].value,
        name = this[ChatSessions.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessions.createdAt]), // Convert Long to Instant
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessions.updatedAt]), // Convert Long to Instant
        groupId = this[ChatSessions.groupId]?.value, // Handle nullable Long?
        currentModelId = this[ChatSessions.currentModelId]?.value,
        currentSettingsId = this[ChatSessions.currentSettingsId]?.value,
        currentLeafMessageId = this[ChatSessions.currentLeafMessageId]?.value,
        messages = messages // Attach the pre-loaded messages
    )
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/MessageDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.common.models.ChatMessage
/**
*   Data Access Object for ChatMessage entities.
*   Methods are suspend because they perform database operations
*   and are expected to be called within a coroutine context managed by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
*/
interface MessageDao {
    /**
     * Retrieves all messages for a specific session, ordered by creation time.
     * The list is flat; the service/UI layer is responsible for reconstructing the thread tree.
     * Includes threading data (`parentMessageId`, `childrenMessageIds`).
     * @param sessionId The ID of the session whose messages to retrieve.
     * @return A list of all [ChatMessage] objects for the session.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage>
    /**
     * Retrieves a single message by its ID.
     * Used internally or by services needing a specific message object.
     * @param id The ID of the message to retrieve.
     * @return The [ChatMessage] object, or null if not found.
     */
    suspend fun getMessageById(id: Long): ChatMessage?
    /**
     * Inserts a new message record into the database.
     * Saves content, role, session ID, parent ID, and optional model/settings IDs.
     * Children list is initialized as empty.
     * @param sessionId The ID of the session the message belongs to.
     * @param role The role of the message sender ("user" or "assistant").
     * @param content The text content of the message.
     * @param parentMessageId Optional ID of the parent message (null for root messages).
     * @param modelId Optional ID of the model used (for assistant messages).
     * @param settingsId Optional ID of the settings profile used (for assistant messages).
     * @return The newly created [ChatMessage] object, fetched after insertion.
     */
    suspend fun insertMessage(
        sessionId: Long,
        role: String,
        content: String,
        parentMessageId: Long?,
        modelId: Long?,
        settingsId: Long?
    ): ChatMessage
    /**
     * Updates the content and updated timestamp of an existing message.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @return The updated [ChatMessage] object, fetched after update.
     */
    suspend fun updateMessageContent(id: Long, content: String): ChatMessage
    /**
     * Deletes a specific message and handles its impact on thread relationships.
     * The V1.1 strategy is to recursively delete all children of the deleted message.
     * Also removes the deleted message's ID from its parent's children list.
     * @param id The ID of the message to delete.
     * @return True if the message was found and deleted, false otherwise.
     */
    suspend fun deleteMessage(id: Long): Boolean
    /**
     * Adds a child message ID to the `childrenMessageIds` list of the parent message record.
     * Serializes the updated list back to the database.
     * Used when a new message is inserted as a reply.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the new child message to add to the parent's list.
     */
    suspend fun addChildToMessage(parentId: Long, childId: Long)
    /**
     * Removes a child message ID from the `childrenMessageIds` list of the parent message record.
     * Serializes the updated list back to the database.
     * Used when a child message is deleted.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the child message to remove from the parent's list.
     */
    suspend fun removeChildFromMessage(parentId: Long, childId: Long)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/MessageDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.models.ChatMessages
import org.jetbrains.exposed.sql.*
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.InsertStatement
/**
*   Exposed implementation of the [MessageDao].
    
*   Methods execute within the ambient transaction provided by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
    
*   Handles serialization/deserialization of `childrenMessageIds` to/from JSON array string.
    
*/
class MessageDaoExposed : MessageDao {
    // Json instance for serializing/deserializing childrenMessageIds list
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> {
        return ChatMessages
            .select { ChatMessages.sessionId eq sessionId }
            .orderBy(ChatMessages.createdAt to SortOrder.ASC) // Order by creation time (E2.S4 AC)
            .map { it.toChatMessage() }
    }
    override suspend fun getMessageById(id: Long): ChatMessage? {
        // Helper method used internally and potentially by services
        // Runs within the ambient transaction
        return ChatMessages.select { ChatMessages.id eq id }.singleOrNull()?.toChatMessage()
    }
    override suspend fun insertMessage(
        sessionId: Long,
        role: String,
        content: String,
        parentMessageId: Long?,
        modelId: Long?,
        settingsId: Long?
    ): ChatMessage {
        val insertStatement = ChatMessages.insert {
            it[ChatMessages.sessionId] = sessionId
            it[ChatMessages.role] = role
            it[ChatMessages.content] = content
            it[ChatMessages.createdAt] = System.currentTimeMillis() // Use Long timestamp
            it[ChatMessages.updatedAt] = System.currentTimeMillis() // Use Long timestamp
            it[ChatMessages.parentMessageId] = parentMessageId // Save nullable parent ID (E1.S1, E1.S7)
            it[ChatMessages.childrenMessageIds] = json.encodeToString(emptyList<Long>()) // Initialize as empty JSON array
            it[ChatMessages.modelId] = modelId // Save nullable model ID (E1.S4 backend)
            it[ChatMessages.settingsId] = settingsId // Save nullable settings ID (E1.S4 backend)
        }
        // Fetch the full newly created message object including DB-generated ID
        val insertedId = insertStatement[ChatMessages.id].value
        return getMessageById(insertedId) // Fetching ensures all fields are populated correctly and mapped
            ?: throw IllegalStateException("Failed to retrieve newly inserted message") // Should not happen
    }
    override suspend fun updateMessageContent(id: Long, content: String): ChatMessage {
        val updatedRowCount = ChatMessages.update({ ChatMessages.id eq id }) {
            it[ChatMessages.content] = content
            it[ChatMessages.updatedAt] = System.currentTimeMillis() // Update timestamp (E3.S3 AC)
        }
        if (updatedRowCount == 0) throw IllegalArgumentException("Message with ID $id not found for update")
        return getMessageById(id) ?: throw IllegalStateException("Failed to retrieve updated message")
    }
    /**
     * Deletes a message and its children recursively within the current transaction.
     * Removes the message's ID from its parent's children list if it had a parent.
     * Implements the recursive deletion strategy for V1.1 (E3.S4 backend AC).
     */
    override suspend fun deleteMessage(id: Long): Boolean {
        val message = getMessageById(id) // Need original message data BEFORE deleting
        if (message == null) {
            return false // Message not found
        }
        // Recursively delete children within the same transaction
        for (childId in message.childrenMessageIds) {
             deleteMessage(childId) // Recursive call
        }
        // Now delete the message itself
        val deletedCount = ChatMessages.deleteWhere { ChatMessages.id eq id }
        // If this message had a parent, update the parent's children list
        if (message.parentMessageId != null) {
             removeChildFromMessage(message.parentMessageId, id) // Update parent's list (E3.S4 backend AC)
        }
        return deletedCount > 0
    }
    /**
     * Adds a child message ID to the parent's `childrenMessageIds` JSON array string.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the new child message.
     */
    override suspend fun addChildToMessage(parentId: Long, childId: Long) {
        val parentRow = ChatMessages.select { ChatMessages.id eq parentId }.singleOrNull()
        if (parentRow != null) {
            val currentChildrenIdsString = parentRow[ChatMessages.childrenMessageIds]
            val childrenList = try { json.decodeFromString<MutableList<Long>>(currentChildrenIdsString) } catch (e: Exception) { mutableListOf() }
            if (childId !in childrenList) {
                childrenList.add(childId)
                val newChildrenIdsString = json.encodeToString(childrenList)
                ChatMessages.update({ ChatMessages.id eq parentId }) {
                    it[ChatMessages.childrenMessageIds] = newChildrenIdsString
                }
            }
        } else {
            // Log warning? Parent not found. Or throw exception?
            // For V1.1, logging a warning might be enough, depending on how strict we need to be.
        }
    }
    /**
     * Removes a child message ID from the parent's `childrenMessageIds` JSON array string.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the child message to remove.
     */
    override suspend fun removeChildFromMessage(parentId: Long, childId: Long) {
         val parentRow = ChatMessages.select { ChatMessages.id eq parentId }.singleOrNull()
        if (parentRow != null) {
            val currentChildrenIdsString = parentRow[ChatMessages.childrenMessageIds]
            val childrenList = try { json.decodeFromString<MutableList<Long>>(currentChildrenIdsString) } catch (e: Exception) { mutableListOf() }
            if (childrenList.remove(childId)) { // Attempt to remove
                val newChildrenIdsString = json.encodeToString(childrenList)
                ChatMessages.update({ ChatMessages.id eq parentId }) {
                    it[ChatMessages.childrenMessageIds] = newChildrenIdsString
                }
            }
        } else {
             // Log warning? Parent not found.
        }
    }
    // --- Mapping Function ---
    /**
     * Maps an Exposed ResultRow from ChatMessages table to a ChatMessage DTO.
     * Handles deserialization of `childrenMessageIds` from JSON array string.
     */
    private fun ResultRow.toChatMessage(): ChatMessage {
        val id = this[ChatMessages.id].value
        val sessionId = this[ChatMessages.sessionId].value
        val roleString = this[ChatMessages.role].name // Get enum name string
        val content = this[ChatMessages.content]
        val createdAt = Instant.fromEpochMilliseconds(this[ChatMessages.createdAt]) // Convert Long to Instant
        val updatedAt = Instant.fromEpochMilliseconds(this[ChatMessages.updatedAt]) // Convert Long to Instant
        val parentMessageId = this[ChatMessages.parentMessageId]?.value
        val childrenMessageIdsString = this[ChatMessages.childrenMessageIds]
        val childrenMessageIds = try { json.decodeFromString<List<Long>>(childrenMessageIdsString) } catch (e: Exception) { emptyList() } // Deserialize JSON array
        return when (ChatMessage.Role.valueOf(roleString)) {
            ChatMessage.Role.USER -> ChatMessage.UserMessage(
                id = id, sessionId = sessionId, content = content,
                createdAt = createdAt, updatedAt = updatedAt,
                parentMessageId = parentMessageId, childrenMessageIds = childrenMessageIds
            )
            ChatMessage.Role.ASSISTANT -> ChatMessage.AssistantMessage(
                id = id, sessionId = sessionId, content = content,
                createdAt = createdAt, updatedAt = updatedAt,
                parentMessageId = parentMessageId, childrenMessageIds = childrenMessageIds,
                modelId = this[ChatMessages.modelId]?.value,
                settingsId = this[ChatMessages.settingsId]?.value
            )
        }
    }
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/GroupDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.common.models.ChatGroup
/**
*   Data Access Object for ChatGroup entities.
*   Methods are suspend because they perform database operations
*   and are expected to be called within a coroutine context managed by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
*/
interface GroupDao {
    /**
     * Retrieves a list of all chat groups.
     * @return A list of [ChatGroup] objects.
     */
    suspend fun getAllGroups(): List<ChatGroup>
    /**
     * Retrieves a single chat group by its ID.
     * @param id The ID of the group to retrieve.
     * @return The [ChatGroup] object, or null if not found.
     */
    suspend fun getGroupById(id: Long): ChatGroup?
    /**
     * Inserts a new chat group record into the database.
     * @param name The name for the new group.
     * @return The newly created [ChatGroup] object, fetched after insertion.
     */
    suspend fun insertGroup(name: String): ChatGroup
    /**
     * Updates the details of an existing chat group (currently only name).
     * @param group The [ChatGroup] object with updated details.
     * @return The updated [ChatGroup] object, fetched after update.
     */
    suspend fun updateGroup(group: ChatGroup): ChatGroup
    /**
     * Deletes a chat group by ID.
     * @param id The ID of the group to delete.
     * @return True if a group was deleted, false otherwise.
     */
    suspend fun deleteGroup(id: Long): Boolean
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/GroupDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.models.ChatGroups
import org.jetbrains.exposed.sql.*
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
/**
*   Exposed implementation of the [GroupDao].
    
*   Methods execute within the ambient transaction provided by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
    
*/
class GroupDaoExposed : GroupDao {
    override suspend fun getAllGroups(): List<ChatGroup> {
        return ChatGroups.selectAll().map { it.toChatGroup() }
    }
    override suspend fun getGroupById(id: Long): ChatGroup? {
        return ChatGroups.select { ChatGroups.id eq id }.singleOrNull()?.toChatGroup()
    }
    override suspend fun insertGroup(name: String): ChatGroup {
        val insertStatement = ChatGroups.insert {
            it[ChatGroups.name] = name
            it[ChatGroups.createdAt] = System.currentTimeMillis() // Use Long timestamp
        }
        return getGroupById(insertStatement[ChatGroups.id].value) // Fetch to return full object
            ?: throw IllegalStateException("Failed to retrieve newly inserted group")
    }
    override suspend fun updateGroup(group: ChatGroup): ChatGroup {
        val updatedRowCount = ChatGroups.update({ ChatGroups.id eq group.id }) {
            it[name] = group.name
            // createdAt is not updated
        }
         if (updatedRowCount == 0) throw IllegalArgumentException("Group with ID ${group.id} not found for update")
         return getGroupById(group.id) ?: throw IllegalStateException("Failed to retrieve updated group")
    }
    override suspend fun deleteGroup(id: Long): Boolean {
        val deletedCount = ChatGroups.deleteWhere { ChatGroups.id eq id }
        // Note: SessionDao.ungroupSessions must be called by the Service layer BEFORE calling this delete method.
        return deletedCount > 0
    }
    // --- Mapping Function ---
    /**
     * Maps an Exposed ResultRow from ChatGroups table to a ChatGroup DTO.
     */
    private fun ResultRow.toChatGroup() = ChatGroup(
        id = this[ChatGroups.id].value,
        name = this[ChatGroups.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatGroups.createdAt]) // Convert Long to Instant
    )
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ModelDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.common.models.LLMModel
/**
*   Data Access Object for LLMModel entities.
*   Methods are suspend because they perform database operations.
*   Placeholder interface for V1.1 Sprint 1.
*/
interface ModelDao {
    /** Retrieves all LLM models. */
    suspend fun getAllModels(): List<LLMModel> // (E4.S2)
    /** Retrieves a single LLM model by ID. */
    suspend fun getModelById(id: Long): LLMModel? // (E1.S4 backend, E4.S3, E4.S4, E5.S4)
    /** Retrieves a single LLM model by API key ID (used for lookup). */
    suspend fun getModelByApiKeyId(apiKeyId: String): LLMModel? // (Internal utility)
    /** Inserts a new LLM model. */
    suspend fun insertModel(name: String, baseUrl: String, type: String, apiKeyId: String?): LLMModel // (E4.S1 backend)
    /** Updates an LLM model. */
    suspend fun updateModel(model: LLMModel) // (E4.S3 backend)
    /** Deletes an LLM model. */
    suspend fun deleteModel(id: Long): Boolean // (E4.S4 backend)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/ModelDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.models.LLMModels
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
/**
*   Exposed implementation of the [ModelDao].
    
*   Methods execute within the ambient transaction provided by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
    
*   Placeholder implementation for V1.1 Sprint 1.
    
*/
class ModelDaoExposed : ModelDao {
    override suspend fun getAllModels(): List<LLMModel> {
        // Placeholder: return empty list
        return emptyList()
    }
    override suspend fun getModelById(id: Long): LLMModel? {
        // Placeholder: return null
        return null
    }
    override suspend fun getModelByApiKeyId(apiKeyId: String): LLMModel? {
        // Placeholder: return null
        return null
    }
    override suspend fun insertModel(name: String, baseUrl: String, type: String, apiKeyId: String?): LLMModel {
        // Placeholder: simulate insert
        val id = LLMModels.insert {}.get(LLMModels.id).value // Simulate getting an ID
        return LLMModel(id, name, baseUrl, apiKeyId, type)
    }
    override suspend fun updateModel(model: LLMModel) {
        // Placeholder: do nothing
    }
    override suspend fun deleteModel(id: Long): Boolean {
        // Placeholder: simulate not found
        return false
    }
    // --- Mapping Function ---
    /**
     * Maps an Exposed ResultRow from LLMModels table to an LLMModel DTO.
     */
    private fun ResultRow.toLLMModel() = LLMModel(
        id = this[LLMModels.id].value,
        name = this[LLMModels.name],
        baseUrl = this[LLMModels.baseUrl],
        apiKeyId = this[LLMModels.apiKeyId],
        type = this[LLMModels.type]
    )
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SettingsDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.common.models.ModelSettings
/**
*   Data Access Object for ModelSettings entities.
*   Methods are suspend because they perform database operations.
*   Placeholder interface for V1.1 Sprint 1.
*/
interface SettingsDao {
    /** Retrieves a single settings profile by ID. */
    suspend fun getSettingsById(id: Long): ModelSettings? // (E1.S4 backend, E4.S6)
    /** Retrieves all settings profiles. */
    suspend fun getAllSettings(): List<ModelSettings> // (E4.S5 utility)
    /** Retrieves settings profiles for a specific model ID. */
    suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> // (E4.S5)
    /** Inserts a new settings profile. */
    suspend fun insertSettings(name: String, modelId: Long, systemMessage: String?, temperature: Float?, maxTokens: Int?, customParamsJson: String?): ModelSettings // (E4.S5)
    /** Updates a settings profile. */
    suspend fun updateSettings(settings: ModelSettings) // (E4.S6)
    /** Deletes a settings profile. */
    suspend fun deleteSettings(id: Long): Boolean // (E4.S5)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/SettingsDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.models.ModelSettings as ModelSettingsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
/**
*   Exposed implementation of the [SettingsDao].
    
*   Methods execute within the ambient transaction provided by [eu.torvian.chatbot.server.utils.transactions.TransactionScope].
    
*   Placeholder implementation for V1.1 Sprint 1.
    
*/
class SettingsDaoExposed : SettingsDao {
    override suspend fun getSettingsById(id: Long): ModelSettings? {
        // Placeholder: return null
        return null
    }
    override suspend fun getAllSettings(): List<ModelSettings> {
        // Placeholder: return empty list
        return emptyList()
    }
    override suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> {
        // Placeholder: return empty list
        return emptyList()
    }
    override suspend fun insertSettings(name: String, modelId: Long, systemMessage: String?, temperature: Float?, maxTokens: Int?, customParamsJson: String?): ModelSettings {
        // Placeholder: simulate insert
        val id = ModelSettingsTable.insert {}.get(ModelSettingsTable.id).value // Simulate getting an ID
        return ModelSettings(id, modelId, name, systemMessage, temperature, maxTokens, customParamsJson)
    }
    override suspend fun updateSettings(settings: ModelSettings) {
        // Placeholder: do nothing
    }
    override suspend fun deleteSettings(id: Long): Boolean {
        // Placeholder: simulate not found
        return false
    }
    // --- Mapping Function ---
    /**
     * Maps an Exposed ResultRow from ModelSettings table to a ModelSettings DTO.
     */
    private fun ResultRow.toModelSettings() = ModelSettings(
        id = this[ModelSettingsTable.id].value,
        modelId = this[ModelSettingsTable.modelId].value,
        name = this[ModelSettingsTable.name],
        systemMessage = this[ModelSettingsTable.systemMessage],
        temperature = this[ModelSettingsTable.temperature],
        maxTokens = this[ModelSettingsTable.maxTokens],
        customParamsJson = this[ModelSettingsTable.customParamsJson]
    )
}
```

This concludes the code for PR 4. Alex will create these files, add the necessary dependencies (kotlinx-serialization-json to server module for message children list, Exposed dependencies are assumed from PR 2), and submit the PR.