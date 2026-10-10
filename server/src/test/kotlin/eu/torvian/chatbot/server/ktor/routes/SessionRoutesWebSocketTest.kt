package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.auth.authenticateWithWebSocketSubprotocol
import eu.torvian.chatbot.server.testutils.auth.offerWebSocketAuthSubprotocolMarker
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Session WebSocket integration tests.
 *
 * Covers the message-processing socket and the conversation-compaction socket, including their
 * authentication, protocol-violation, error and ownership behaviour.
 */
class SessionRoutesWebSocketTest : SessionRoutesTestBase() {

    // --- WS /api/v1/sessions/{sessionId}/messages Tests ---

    @Test
    fun `WS session message should process new message successfully and emit WebSocket events`() =
        sessionTestApplication {
            // Arrange
            testDataManager.insertChatSession(testNonStreamingSession)
            testDataManager.insertSessionOwnership(testNonStreamingSession.id, authHelper.defaultTestUser.id)
            // Turn preparation resolves the agent role owner; the role must have an ownership row.
            testDataManager.insertAgentRoleOwnership(
                testNonStreamingAgentRole.id,
                authHelper.defaultTestUser.id
            )
            val messageContent = "Test message content"
            val processRequest = ProcessNewMessageRequest(content = messageContent, isStreaming = false)

            // Act
            val receivedEvents = mutableListOf<ChatEvent>()
            client.webSocket(
                urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testNonStreamingSession.id))),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                // Send initial message
                val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
                send(Frame.Text(json.encodeToString(initialEvent)))

                // Collect incoming events
                for (frame in incoming) {
                    val textFrame = frame as? Frame.Text ?: continue
                    val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                    receivedEvents.add(chatEvent)
                }
            }

            // Assert - WebSocket events
            assertTrue(receivedEvents.isNotEmpty(), "Should have received WebSocket events")

            // Should have user_message_saved event
            val userMessageEvent = receivedEvents.filterIsInstance<ChatEvent.UserMessageSaved>().firstOrNull()
            assertNotNull(userMessageEvent, "Should receive user_message_saved event")
            assertEquals(messageContent, userMessageEvent.userMessage.content)

            // Should have assistant_message_saved event
            val assistantMessageEvent = receivedEvents.filterIsInstance<ChatEvent.AssistantMessageSaved>().firstOrNull()
            assertNotNull(assistantMessageEvent, "Should receive assistant_message_saved event")
            assertNotNull(assistantMessageEvent.assistantMessage)

            // Should end with done event
            val doneEvent = receivedEvents.filterIsInstance<ChatEvent.StreamCompleted>().firstOrNull()
            assertNotNull(doneEvent, "Should receive done event")

            // Verify the messages were actually created in the database
            val messages = testDataManager.getChatMessagesForSession(testNonStreamingSession.id)
            assertEquals(2, messages.size, "Should create 2 messages (user and assistant)")

            val userMessage = messages.find { it is ChatMessage.UserMessage } as? ChatMessage.UserMessage
            assertNotNull(userMessage, "Should have created user message")
            assertEquals(messageContent, userMessage.content)
            assertEquals(testNonStreamingSession.id, userMessage.sessionId)
            assertNull(userMessage.parentMessageId)

            val assistantMessage = messages.find { it is ChatMessage.AssistantMessage } as? ChatMessage.AssistantMessage
            assertNotNull(assistantMessage, "Should have created assistant message")
            assertEquals(testNonStreamingSession.id, assistantMessage.sessionId)
            assertEquals(userMessage.id, assistantMessage.parentMessageId)
            assertEquals(testModel.id, assistantMessage.modelId)
            assertEquals(testNonStreamingSettings.id, assistantMessage.settingsId)

            // Verify the session leaf message ID was actually updated
            val leaf = testDataManager.getSessionCurrentLeaf(testNonStreamingSession.id)
            assertNotNull(leaf, "Expected leaf message to be created")
            assertEquals(assistantMessage.id, leaf.messageId)
        }

    @Test
    fun `WS session message should authenticate via Sec-WebSocket-Protocol`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testNonStreamingSession)
        testDataManager.insertSessionOwnership(testNonStreamingSession.id, authHelper.defaultTestUser.id)
        // Turn preparation resolves the agent role owner; the role must have an ownership row.
        testDataManager.insertAgentRoleOwnership(
            testNonStreamingAgentRole.id,
            authHelper.defaultTestUser.id
        )
        val processRequest = ProcessNewMessageRequest(content = "Subprotocol auth message", isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testNonStreamingSession.id))),
            request = { authenticateWithWebSocketSubprotocol(authToken) }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert
        val doneEvent = receivedEvents.filterIsInstance<ChatEvent.StreamCompleted>().firstOrNull()
        assertNotNull(doneEvent, "Should receive done event when authenticating via subprotocol")
    }

    @Test
    fun `WS session message should emit error event for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L
        val processRequest = ProcessNewMessageRequest(content = "Test message", isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = nonExistentId))),
            request = {
                authenticate(authToken)
                offerWebSocketAuthSubprotocolMarker()
            }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, errorEvent.error.code)
    }

    @Test
    fun `WS session message should emit error event for missing agent role`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession.copy(agentRoleId = null))
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        val processRequest = ProcessNewMessageRequest(content = "Test message", isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testSession.id))),
            request = {
                authenticate(authToken)
                offerWebSocketAuthSubprotocolMarker()
            }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertTrue(
            errorEvent.error.message.contains("LLM configuration error", ignoreCase = true) ||
                    errorEvent.error.code == ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR.code,
            "Error should indicate missing model"
        )

        // Verify no messages were created
        val messages = testDataManager.getChatMessagesForSession(testSession.id)
        assertEquals(0, messages.size, "No messages should be created on error")
    }

    /**
     * Verifies that an enabled compaction preference without a compactor rejects the turn during
     * preparation: the production resolver reads the stored row inside the turn's transaction, so the
     * socket reports a model-configuration error and nothing is persisted.
     */

    @Test
    fun `WS session message should emit a model-configuration error when the enabled compaction preference has no model`() =
        sessionTestApplication {
            // Arrange: an enabled preference that names no model/settings, the shape only a legacy or
            // hand-edited row can have because the write path refuses to store it.
            testDataManager.insertChatSession(testNonStreamingSession)
            testDataManager.insertSessionOwnership(testNonStreamingSession.id, authHelper.defaultTestUser.id)
            // Turn preparation resolves the agent role owner; the role must have an ownership row.
            testDataManager.insertAgentRoleOwnership(
                testNonStreamingAgentRole.id,
                authHelper.defaultTestUser.id
            )
            userPreferenceDao.upsertPreference(
                userId = authHelper.defaultTestUser.id,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION,
                value = """{"modelId":null,"settingsId":null,"instruction":"Summarize","thresholdTokens":50000}"""
            )
            val processRequest = ProcessNewMessageRequest(content = "Test message", isStreaming = false)

            // Act
            val receivedEvents = mutableListOf<ChatEvent>()
            client.webSocket(
                urlString = href(
                    SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testNonStreamingSession.id))
                ),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
                send(Frame.Text(json.encodeToString(initialEvent)))

                for (frame in incoming) {
                    val textFrame = frame as? Frame.Text ?: continue
                    val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                    receivedEvents.add(chatEvent)
                }
            }

            // Assert - the resolver's rejection is the model-configuration error of the validation stage.
            val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
            assertNotNull(errorEvent, "Should receive error event")
            assertEquals(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR.code, errorEvent.error.code)
            assertEquals("Compaction modelId is not set", errorEvent.error.details?.get("details"))
            // Rejected before persistence: no user message and no session leaf were written.
            assertEquals(0, testDataManager.getChatMessagesForSession(testNonStreamingSession.id).size)
            assertNull(testDataManager.getSessionCurrentLeaf(testNonStreamingSession.id))
        }

    /**
     * Verifies that the session-messages WebSocket still rejects any non-start event as the first frame.
     */

    @Test
    fun `WS session message should close with violated policy when first event is not ProcessNewMessage`() =
        sessionTestApplication {
            client.webSocket(
                urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = 999L))),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                val invalidInitialEvent: ChatClientEvent = ChatClientEvent.OperatorToolCallApproval(
                    toolCallId = 1L,
                    approved = true
                )
                send(Frame.Text(json.encodeToString(invalidInitialEvent)))

                val reason = withTimeout(5_000.milliseconds) {
                    closeReason.await() ?: error("Expected close reason for invalid initial event")
                }

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
                assertEquals("First message must be ProcessNewMessage", reason.message)
            }
        }

    /**
     * Verifies that the session-messages WebSocket still requires a text frame for the initial request.
     */

    @Test
    fun `WS session message should close with violated policy when initial frame is not text`() =
        sessionTestApplication {
            client.webSocket(
                urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = 999L))),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))

                val reason = withTimeout(5_000.milliseconds) {
                    closeReason.await() ?: error("Expected close reason for invalid initial frame")
                }

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
                assertEquals("Invalid frame type for initial request", reason.message)
            }
        }

    @Test
    fun `WS session message as non-owner should emit error event for forbidden access`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 992L, email = "otheruser8@example.com", username = "otheruser8")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val processRequest = ProcessNewMessageRequest(content = "Hi", parentMessageId = null, isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testSession.id))),
            request = {
                authenticate(authToken)
                offerWebSocketAuthSubprotocolMarker()
            }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, errorEvent.error.code)
    }

    // --- WS /api/v1/sessions/{sessionId}/compaction Tests ---

    @Test
    fun `WS session compaction should persist a chunk and emit the outcome then the terminal marker`() =
        sessionTestApplication {
            // Arrange
            seedCompactableSession()

            // Act: connecting is the request, so no frame is sent.
            val receivedEvents = mutableListOf<CompactionEvent>()
            client.webSocket(
                urlString = href(
                    SessionResource.ById.Compaction(parent = SessionResource.ById(sessionId = testSession.id))
                ),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                for (frame in incoming) {
                    val textFrame = frame as? Frame.Text ?: continue
                    receivedEvents.add(json.decodeFromString(CompactionEvent.serializer(), textFrame.readText()))
                }
            }

            // Assert: exactly one terminal outcome followed by the terminal marker, on a closed socket.
            assertEquals(2, receivedEvents.size, "Expected the outcome frame and the terminal marker")
            val completed = assertIs<CompactionEvent.Completed>(receivedEvents[0])
            assertEquals(testSession.id, completed.payload.sessionId)
            assertEquals(listOf(MESSAGE_1_ID, MESSAGE_2_ID), completed.payload.coveredMessageIds)
            assertEquals(CompactionEvent.StreamCompleted, receivedEvents[1])

            // The chunk really is in the database, with the cumulative root-to-leaf coverage.
            val chunks = container.get<ConversationCompactionChunkDao>().getChunksBySessionId(testSession.id)
            assertEquals(1, chunks.size)
            assertEquals(listOf(MESSAGE_1_ID, MESSAGE_2_ID), chunks.single().coverage.map { it.messageId })
        }

    @Test
    fun `WS session compaction as non-owner should emit a permission-denied frame and persist nothing`() =
        sessionTestApplication {
            // Arrange: the session is owned by another user.
            val otherUser = authHelper.createTestUser(id = 991L, email = "otheruser9@example.com", username = "otheruser9")
            testDataManager.insertUser(otherUser)
            testDataManager.insertChatSession(testSession)
            testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

            // Act
            val receivedEvents = mutableListOf<CompactionEvent>()
            client.webSocket(
                urlString = href(
                    SessionResource.ById.Compaction(parent = SessionResource.ById(sessionId = testSession.id))
                ),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                for (frame in incoming) {
                    val textFrame = frame as? Frame.Text ?: continue
                    receivedEvents.add(json.decodeFromString(CompactionEvent.serializer(), textFrame.readText()))
                }
            }

            // Assert: the denial is the first frame and the operation never ran. (Only the error frame is
            // sent on this path; whether it must be followed by the terminal marker is a separate, open
            // consistency question, so this case pins what a client must not see: an outcome.)
            val denied = assertIs<CompactionEvent.ErrorOccurred>(receivedEvents.firstOrNull())
            assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, denied.error.code)
            assertEquals(0, receivedEvents.count { event -> event is CompactionEvent.Completed })
            assertEquals(0, receivedEvents.count { event -> event is CompactionEvent.Skipped })
            assertTrue(
                container.get<ConversationCompactionChunkDao>().getChunksBySessionId(testSession.id).isEmpty(),
                "A denied compaction must not persist a chunk"
            )
        }

    /**
     * Seeds a compaction-ready session: ownership, the role's preset ownership, a long two-message
     * thread ending at the session leaf, and the global compaction preference the forced path resolves.
     *
     * The messages are deliberately long so the stub summary is smaller than the thread it replaces,
     * which the shared reduction rule requires before a chunk may be persisted.
     */
    private suspend fun seedCompactableSession() {
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        // Turn preparation resolves the agent role owner; the role must have an ownership row.
        testDataManager.insertAgentRoleOwnership(testAgentRole.id, authHelper.defaultTestUser.id)
        val first = TestDefaults.chatMessage1.copy(
            id = MESSAGE_1_ID,
            sessionId = testSession.id,
            content = "First long message. ".repeat(60),
            childrenMessageIds = listOf(MESSAGE_2_ID)
        )
        val second = TestDefaults.chatMessage2.copy(
            id = MESSAGE_2_ID,
            sessionId = testSession.id,
            parentMessageId = MESSAGE_1_ID,
            content = "Second long message. ".repeat(60),
            childrenMessageIds = emptyList()
        )
        testDataManager.insertChatMessage(first)
        testDataManager.insertChatMessage(second)
        testDataManager.insertSessionCurrentLeaf(
            SessionCurrentLeafEntity(sessionId = testSession.id, messageId = MESSAGE_2_ID)
        )
        userPreferenceDao.upsertPreference(
            userId = authHelper.defaultTestUser.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.CONVERSATION_COMPACTION,
            value = json.encodeToString(
                ConversationCompactionPreference.serializer(),
                ConversationCompactionPreference(
                    modelId = testModel.id,
                    settingsId = testSettings.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 50_000L
                )
            )
        )
    }

    private companion object {
        /** Thread messages of the compaction fixtures, kept clear of the shared fixture ids. */
        const val MESSAGE_1_ID = 21L

        /** Leaf message of the compaction fixtures. */
        const val MESSAGE_2_ID = 22L
    }
}
