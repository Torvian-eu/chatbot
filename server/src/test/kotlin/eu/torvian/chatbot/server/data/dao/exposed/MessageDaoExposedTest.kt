package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests for [MessageDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [MessageDao]:
 * - Getting messages by session ID
 * - Getting a message by ID
 * - Inserting messages (multi-purpose insertMessage)
 * - Updating message content
 * - Deleting messages (including recursive deletion of children)
 * - Managing parent-child relationships between messages
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class MessageDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var messageDao: MessageDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testSession1 = TestDefaults.chatSession1
    private val testSession2 = TestDefaults.chatSession2
    private val testUserMessage1 = TestDefaults.chatMessage1
    private val testAssistantMessage1 = TestDefaults.chatMessage2
    private val testUserMessage2 = TestDefaults.chatMessage3
    private val testAssistantMessage2 = TestDefaults.chatMessage4
    private val testModel1 = TestDefaults.llmModel1
    private val testModel2 = TestDefaults.llmModel2
    private val testProvider1 = TestDefaults.llmProvider1
    private val testProvider2 = TestDefaults.llmProvider2
    private val testSettings1 = TestDefaults.modelSettings1
    private val testSettings2 = TestDefaults.modelSettings2
    private val testGroup1 = TestDefaults.chatGroup1
    private val testGroup2 = TestDefaults.chatGroup2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        messageDao = container.get()
        testDataManager = container.get()

        // Create necessary tables for message testing
        testDataManager.createTables(
            setOf(
                Table.CHAT_GROUPS,
                Table.LLM_MODELS,
                Table.LLM_PROVIDERS,
                Table.MODEL_SETTINGS,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getMessagesBySessionId should return empty list when no messages exist`() = runTest {
        // Setup session but no messages
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1)
            )
        )

        // Get messages
        val messages = messageDao.getMessagesBySessionId(testSession1.id)

        // Verify
        assertTrue(messages.isEmpty(), "Expected empty list when no messages exist")
    }

    @Test
    fun `getMessagesBySessionId should return messages for specified session only`() = runTest {
        // Setup test data with messages in multiple sessions
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1, testGroup2),
                llmModels = listOf(testModel1, testModel2),
                llmProviders = listOf(testProvider1, testProvider2),
                modelSettings = listOf(testSettings1, testSettings2),
                chatSessions = listOf(testSession1, testSession2),
                chatMessages = listOf(testUserMessage1, testAssistantMessage1, testUserMessage2, testAssistantMessage2)
            )
        )

        // Get messages for session 1
        val messagesSession1 = messageDao.getMessagesBySessionId(testSession1.id)

        // Verify session 1 messages
        assertEquals(2, messagesSession1.size, "Expected 2 messages for session 1")
        assertTrue(messagesSession1.any { it.id == testUserMessage1.id }, "Expected to find user message in session 1")
        assertTrue(
            messagesSession1.any { it.id == testAssistantMessage1.id },
            "Expected to find assistant message in session 1"
        )

        // Get messages for session 2
        val messagesSession2 = messageDao.getMessagesBySessionId(testSession2.id)

        // Verify session 2 messages
        assertEquals(2, messagesSession2.size, "Expected 2 messages for session 2")
        assertTrue(messagesSession2.any { it.id == testUserMessage2.id }, "Expected to find user message in session 2")
        assertTrue(
            messagesSession2.any { it.id == testAssistantMessage2.id },
            "Expected to find assistant message in session 2"
        )
    }

    @Test
    fun `getMessageById should return message when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(testUserMessage1, testAssistantMessage1)
            )
        )

        // Get user message by ID
        val userMessageResult = messageDao.getMessageById(testUserMessage1.id)

        // Verify user message
        assertTrue(userMessageResult.isRight(), "Expected Right result for existing user message")
        val userMessage = userMessageResult.getOrNull()
        assertNotNull(userMessage, "Expected non-null user message")
        assertEquals(testUserMessage1.id, userMessage.id, "Expected matching ID")
        assertEquals(testUserMessage1.sessionId, userMessage.sessionId, "Expected matching sessionId")
        assertEquals(testUserMessage1.content, userMessage.content, "Expected matching content")
        assertEquals(ChatMessage.Role.USER, userMessage.role, "Expected USER role")
        assertTrue(userMessage is ChatMessage.UserMessage, "Expected UserMessage type")

        // Get assistant message by ID
        val assistantMessageResult = messageDao.getMessageById(testAssistantMessage1.id)

        // Verify assistant message
        assertTrue(assistantMessageResult.isRight(), "Expected Right result for existing assistant message")
        val assistantMessage = assistantMessageResult.getOrNull()
        assertNotNull(assistantMessage, "Expected non-null assistant message")
        assertEquals(testAssistantMessage1.id, assistantMessage.id, "Expected matching ID")
        assertEquals(testAssistantMessage1.sessionId, assistantMessage.sessionId, "Expected matching sessionId")
        assertEquals(testAssistantMessage1.content, assistantMessage.content, "Expected matching content")
        assertEquals(ChatMessage.Role.ASSISTANT, assistantMessage.role, "Expected ASSISTANT role")
        assertTrue(assistantMessage is ChatMessage.AssistantMessage, "Expected AssistantMessage type")
        assertEquals(testAssistantMessage1.modelId, assistantMessage.modelId, "Expected matching modelId")
        assertEquals(testAssistantMessage1.settingsId, assistantMessage.settingsId, "Expected matching settingsId")
    }

    @Test
    fun `getMessageById should return MessageNotFound when message does not exist`() = runTest {
        // Get a non-existent message
        val result = messageDao.getMessageById(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent message")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `insertMessage should insert a new USER root message when targetMessageId is null`() = runTest {
        // Setup session
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1)
            )
        )

        val content = "This is a test message"
        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = content,
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isRight(), "Expected Right result for successful insertion")
        val message = result.getOrNull()
        assertNotNull(message, "Expected non-null message")
        assertEquals(content, message.content, "Expected matching content")
        assertEquals(testSession1.id, message.sessionId, "Expected matching sessionId")
        assertNull(message.parentMessageId, "Expected null parentMessageId")
        assertTrue(message.childrenMessageIds.isEmpty(), "Expected empty childrenMessageIds")
        assertEquals(ChatMessage.Role.USER, message.role, "Expected USER role")
        assertTrue(message is ChatMessage.UserMessage, "Expected UserMessage type")

        // Verify message was actually inserted in the database
        val retrievedMessage = testDataManager.getChatMessage(message.id)
        assertNotNull(retrievedMessage, "Expected to find the newly inserted message")
        assertEquals(message.id, retrievedMessage.id, "Expected matching ID")
    }

    @Test
    fun `insertMessage APPEND should add a new child to target and keep existing children`() = runTest {
        // Setup session with a parent message (ensure parent has no pre-populated children)
        val parentWithoutChildren = testUserMessage1.copy(childrenMessageIds = emptyList())
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(parentWithoutChildren)
            )
        )

        val content = "This is a reply to the first message"
        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = testUserMessage1.id,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = content,
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isRight(), "Expected Right result for successful insertion")
        val message = result.getOrNull()
        assertNotNull(message, "Expected non-null message")
        assertEquals(testUserMessage1.id, message.parentMessageId, "Expected correct parentMessageId")
        assertTrue(message.childrenMessageIds.isEmpty(), "Expected empty childrenMessageIds")

        // Verify parent-child relationship was established
        val parentMessage = testDataManager.getChatMessage(testUserMessage1.id)
        assertNotNull(parentMessage, "Expected to find the parent message")
        assertEquals(listOf(message.id), parentMessage.childrenMessageIds, "Expected the new child appended")
    }

    @Test
    fun `insertMessage should insert an ASSISTANT message and persist assistant metadata`() = runTest {
        // Setup session and parent message (ensure parent has no pre-populated children)
        val parentWithoutChildren = testUserMessage1.copy(childrenMessageIds = emptyList())
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(parentWithoutChildren)
            )
        )

        val content = "This is an assistant response"
        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = testUserMessage1.id,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            modelId = testModel1.id,
            settingsId = testSettings1.id
        )

        assertTrue(result.isRight(), "Expected Right result for successful insertion")
        val message = result.getOrNull()
        assertNotNull(message, "Expected non-null message")
        assertTrue(message is ChatMessage.AssistantMessage, "Expected AssistantMessage type")
        val assistant = message as ChatMessage.AssistantMessage
        assertEquals(content, assistant.content, "Expected matching content")
        assertEquals(testSession1.id, assistant.sessionId, "Expected matching sessionId")
        assertEquals(testUserMessage1.id, assistant.parentMessageId, "Expected matching parentMessageId")
        assertTrue(assistant.childrenMessageIds.isEmpty(), "Expected empty childrenMessageIds")
        assertEquals(ChatMessage.Role.ASSISTANT, assistant.role, "Expected ASSISTANT role")
        assertEquals(testModel1.id, assistant.modelId, "Expected matching modelId")
        assertEquals(testSettings1.id, assistant.settingsId, "Expected matching settingsId")

        // Verify parent-child relationship was established
        val parentMessage = testDataManager.getChatMessage(testUserMessage1.id)
        assertNotNull(parentMessage, "Expected to find the parent message")
        assertEquals(listOf(assistant.id), parentMessage.childrenMessageIds, "Expected the new child appended")
    }

    @Test
    fun `insertMessage ABOVE should insert between target and its parent and rewire links`() = runTest {
        // Arrange: grandparent -> target
        val grandParentId = testUserMessage2.id
        val targetId = testUserMessage1.id
        val grandParent = testUserMessage2.copy(parentMessageId = null, childrenMessageIds = listOf(targetId))
        val target = testUserMessage1.copy(parentMessageId = grandParentId, childrenMessageIds = emptyList())

        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1, testGroup2),
                llmModels = listOf(testModel1, testModel2),
                llmProviders = listOf(testProvider1, testProvider2),
                modelSettings = listOf(testSettings1, testSettings2),
                chatSessions = listOf(testSession1, testSession2),
                chatMessages = listOf(grandParent, target)
            )
        )

        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = targetId,
            position = MessageInsertPosition.ABOVE,
            role = ChatMessage.Role.USER,
            content = "Inserted above",
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isRight())
        val inserted = result.getOrNull()
        assertNotNull(inserted)

        // New message should be child of grandparent, and parent of target
        assertEquals(grandParentId, inserted.parentMessageId)
        assertEquals(listOf(targetId), inserted.childrenMessageIds)

        val reloadedTarget = testDataManager.getChatMessage(targetId)
        assertNotNull(reloadedTarget)
        assertEquals(inserted.id, reloadedTarget.parentMessageId)

        val reloadedGrandParent = testDataManager.getChatMessage(grandParentId)
        assertNotNull(reloadedGrandParent)
        assertEquals(listOf(inserted.id), reloadedGrandParent.childrenMessageIds)
    }

    @Test
    fun `insertMessage BELOW should insert between target and its children and rewire links`() = runTest {
        // Arrange: target -> [child1]
        val targetId = testUserMessage1.id
        val childId = testAssistantMessage1.id

        val target = testUserMessage1.copy(parentMessageId = null, childrenMessageIds = listOf(childId))
        val child = testAssistantMessage1.copy(parentMessageId = targetId, childrenMessageIds = emptyList())

        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(target, child)
            )
        )

        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = targetId,
            position = MessageInsertPosition.BELOW,
            role = ChatMessage.Role.USER,
            content = "Inserted below",
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isRight())
        val inserted = result.getOrNull()
        assertNotNull(inserted)

        // New message should become only child of target, and parent of the old child
        assertEquals(targetId, inserted.parentMessageId)
        assertEquals(listOf(childId), inserted.childrenMessageIds)

        val reloadedTarget = testDataManager.getChatMessage(targetId)
        assertNotNull(reloadedTarget)
        assertEquals(listOf(inserted.id), reloadedTarget.childrenMessageIds)

        val reloadedChild = testDataManager.getChatMessage(childId)
        assertNotNull(reloadedChild)
        assertEquals(inserted.id, reloadedChild.parentMessageId)
    }

    @Test
    fun `insertMessage should return ParentNotFound when targetMessageId does not exist`() = runTest {
        // Setup session exists
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1)
            )
        )

        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = 999,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = "Should fail",
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertTrue(error is InsertMessageError.ParentNotFound)
        assertEquals(999, (error as InsertMessageError.ParentNotFound).parentId)
    }

    @Test
    fun `insertMessage should return ParentNotInSession when target is in different session`() = runTest {
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1, testGroup2),
                llmModels = listOf(testModel1, testModel2),
                llmProviders = listOf(testProvider1, testProvider2),
                modelSettings = listOf(testSettings1, testSettings2),
                chatSessions = listOf(testSession1, testSession2),
                chatMessages = listOf(testUserMessage1, testUserMessage2)
            )
        )

        val result = messageDao.insertMessage(
            sessionId = testSession1.id,
            targetMessageId = testUserMessage2.id,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = "This parent is in a different session",
            modelId = null,
            settingsId = null
        )

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertTrue(error is InsertMessageError.ParentNotInSession)
        assertEquals(testUserMessage2.id, (error as InsertMessageError.ParentNotInSession).parentId)
        assertEquals(testSession1.id, error.sessionId)
    }

    @Test
    fun `updateMessageContent should update existing message`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(testUserMessage1)
            )
        )

        // Update message content
        val updatedContent = "Updated content"
        val result = messageDao.updateMessageContent(testUserMessage1.id, updatedContent)

        // Verify update was successful
        assertTrue(result.isRight(), "Expected Right result for successful update")
        val updatedMessage = result.getOrNull()
        assertNotNull(updatedMessage, "Expected non-null message")
        assertEquals(updatedContent, updatedMessage.content, "Expected content to be updated")

        // Verify the message was actually updated in the database
        val retrievedMessage = testDataManager.getChatMessage(testUserMessage1.id)
        assertNotNull(retrievedMessage, "Expected to find the updated message")
        assertEquals(updatedContent, retrievedMessage.content, "Expected retrieved message to have updated content")
    }

    @Test
    fun `updateMessageContent should return MessageNotFound when message does not exist`() = runTest {
        // Try to update non-existent message
        val result = messageDao.updateMessageContent(999, "Updated content")

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent message")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `deleteMessage should delete a message without children`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(testUserMessage1, testAssistantMessage1)
            )
        )

        // Delete the message
        val result = messageDao.deleteMessageRecursively(testAssistantMessage1.id)

        // Verify deletion was successful
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the message was actually deleted
        val retrievedMessage = testDataManager.getChatMessage(testAssistantMessage1.id)
        assertNull(retrievedMessage, "Expected message to be deleted")
    }

    @Test
    fun `deleteMessage should recursively delete child messages`() = runTest {
        // Setup test data with parent-child relationship
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(testUserMessage1, testAssistantMessage1)
            )
        )

        // Delete the parent message
        val result = messageDao.deleteMessageRecursively(testUserMessage1.id)

        // Verify deletion was successful
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the parent message was deleted
        val parentMessage = testDataManager.getChatMessage(testUserMessage1.id)
        assertNull(parentMessage, "Expected parent message to be deleted")

        // Verify the child message was also deleted
        val childMessage = testDataManager.getChatMessage(testAssistantMessage1.id)
        assertNull(childMessage, "Expected child message to be deleted")
    }

    @Test
    fun `deleteMessage should remove child reference from parent`() = runTest {
        // Setup test data with parent-child message
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1),
                llmModels = listOf(testModel1),
                llmProviders = listOf(testProvider1),
                modelSettings = listOf(testSettings1),
                chatSessions = listOf(testSession1),
                chatMessages = listOf(testUserMessage1, testAssistantMessage1)
            )
        )

        // Delete the child message
        val result = messageDao.deleteMessageRecursively(testAssistantMessage1.id)

        // Verify deletion was successful
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the child message was deleted
        val childMessage = testDataManager.getChatMessage(testAssistantMessage1.id)
        assertNull(childMessage, "Expected child message to be deleted")

        // Verify the parent message no longer references the deleted child
        val parentMessage = testDataManager.getChatMessage(testUserMessage1.id)
        assertNotNull(parentMessage, "Expected parent message to still exist")
        assertFalse(
            parentMessage.childrenMessageIds.contains(testAssistantMessage1.id),
            "Expected parent's childrenMessageIds to no longer contain deleted child ID"
        )
    }
}
