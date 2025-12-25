package eu.torvian.chatbot.app.compose

import androidx.compose.ui.test.*
import eu.torvian.chatbot.app.compose.chatarea.ChatArea
import eu.torvian.chatbot.app.compose.common.LOADING_OVERLAY_TAG
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaActions
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.toApiResourceError
import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.chatSession
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the ChatArea composable, focusing on retry functionality.
 */
@OptIn(ExperimentalTestApi::class)
class ChatAreaTest {

    private lateinit var mockActions: ChatAreaActions

    @BeforeEach
    fun setUp() {
        mockActions = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        // Clear only the specific mock used in these tests to avoid cross-test interference
        clearMocks(mockActions)
    }

    @Test
    fun errorState_showsRetryButton() {
        // Arrange
        val testError = apiError(CommonApiErrorCodes.INTERNAL, "Test error message")
        val repositoryError = testError.toApiResourceError().toRepositoryError()
        val errorState = ChatAreaState(
            sessionUiState = DataState.Error(repositoryError),
            displayedMessages = emptyList(),
            inputContent = "",
            replyTargetMessage = null,
            editingMessage = null,
            editingContent = ""
        )

        // Act & Assert - Using runComposeUiTest for Compose Multiplatform
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = errorState,
                    actions = mockActions
                )
            }

            // Assert - Check that error message is displayed
            onNodeWithText("Failed to load chat session").assertIsDisplayed()

            // Assert - Check that retry button is displayed and clickable
            val retryButton = onNodeWithText("Retry")
            retryButton.assertIsDisplayed()
            retryButton.assertHasClickAction()

            // Act - Click retry button
            retryButton.performClick()

            // Assert - Verify retry action was called using mockk's verify
            verify(exactly = 1) { mockActions.onRetryLoadingSession() }
        }
    }

    @Test
    fun loadingState_showsLoadingOverlay() {
        // Arrange
        val loadingState = ChatAreaState(
            sessionUiState = DataState.Loading
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = loadingState,
                    actions = mockActions
                )
            }

            // Assert - Check that loading indicator is displayed
            onNodeWithTag(LOADING_OVERLAY_TAG).assertExists()
        }
    }

    @Test
    fun successState_displaysMessages() {
        // Arrange
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = "Hello",
                childrenMessageIds = listOf(2L)
            ),
            assistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "Hi there!"
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Test Session",
            currentLeafMessageId = 2L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testSession.messages
        )

        // Using mockk for ChatAreaActions
        val mockActions = mockk<ChatAreaActions>(relaxed = true)

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Messages should be displayed
            onNodeWithText("Hello").assertIsDisplayed()
            onNodeWithText("Hi there!").assertIsDisplayed()
        }
    }

    @Test
    fun idleState_showsIdleMessage() {
        // Arrange
        val idleState = ChatAreaState(
            sessionUiState = DataState.Idle
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = idleState,
                    actions = mockActions
                )
            }

            // Assert - Idle message should be displayed
            onNodeWithText("Select a session from the left or create a new one.").assertIsDisplayed()
            onNodeWithText("Messages will appear here.").assertIsDisplayed()
        }
    }

    @Test
    fun successState_displaysMessageRoleLabels() {
        // Arrange
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = "User message content"
            ),
            assistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "Assistant message content"
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Test Session",
            currentLeafMessageId = 2L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testSession.messages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Role labels should be displayed
            onNodeWithText("You:").assertIsDisplayed()
            onNodeWithText("AI:").assertIsDisplayed()
            onNodeWithText("User message content").assertIsDisplayed()
            onNodeWithText("Assistant message content").assertIsDisplayed()
        }
    }

    @Test
    fun successState_emptyMessageList_displaysNoMessages() {
        // Arrange
        val testSession = chatSession(
            id = 1L,
            name = "Empty Session",
            currentLeafMessageId = null,
            messages = emptyList()
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = emptyList()
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - No messages should be displayed, but the LazyColumn should exist
            onNodeWithText("Hello").assertDoesNotExist()
            onNodeWithText("You:").assertDoesNotExist()
            onNodeWithText("AI:").assertDoesNotExist()
        }
    }

    @Test
    fun successState_multipleMessagesWithDifferentRoles() {
        // Arrange - Test multiple messages with different roles
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = "First user message"
            ),
            assistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "First assistant response"
            ),
            userMessage(
                id = 3L,
                sessionId = 1L,
                content = "Second user message"
            ),
            assistantMessage(
                id = 4L,
                sessionId = 1L,
                content = "Second assistant response"
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Multi-Message Session",
            currentLeafMessageId = 4L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testMessages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - All messages should be displayed
            onNodeWithText("First user message").assertIsDisplayed()
            onNodeWithText("First assistant response").assertIsDisplayed()
            onNodeWithText("Second user message").assertIsDisplayed()
            onNodeWithText("Second assistant response").assertIsDisplayed()

            // Assert - All role labels should be displayed (2 of each)
            onAllNodesWithText("You:").assertCountEquals(2)
            onAllNodesWithText("AI:").assertCountEquals(2)
        }
    }

    @Test
    fun successState_messageContent_displaysCorrectly() {
        // Arrange - Test that messages display their content correctly
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = "User question here"
            ),
            assistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "Assistant response here"
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Content Test Session",
            currentLeafMessageId = 2L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testMessages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Messages should display their content
            onNodeWithText("User question here").assertIsDisplayed()
            onNodeWithText("Assistant response here").assertIsDisplayed()

            // Assert - Role labels should be displayed
            onNodeWithText("You:").assertIsDisplayed()
            onNodeWithText("AI:").assertIsDisplayed()
        }
    }

    @Test
    fun successState_singleBranch_noBranchNavigation() {
        // Arrange - Create a message with only one child (no branching)
        val parentMessage = userMessage(
            id = 1L,
            sessionId = 1L,
            content = "Parent message",
            childrenMessageIds = listOf(2L) // Only one branch
        )
        val childMessage = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Single response",
            parentMessageId = 1L
        )

        val testMessages = listOf(parentMessage, childMessage)
        val testSession = chatSession(
            id = 1L,
            name = "Single Branch Session",
            currentLeafMessageId = 2L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testMessages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - No branch navigation should be visible for single branch
            onNodeWithText("1 / 1").assertDoesNotExist()
            onNodeWithContentDescription("Previous branch").assertDoesNotExist()
            onNodeWithContentDescription("Next branch").assertDoesNotExist()
        }
    }

    @Test
    fun successState_complexThreading_displaysCorrectBranch() {
        // Arrange - Create complex threading scenario from test data
        val m1 = userMessage(
            id = 1,
            sessionId = 100,
            content = "Hello",
            parentMessageId = null,
            childrenMessageIds = listOf(2, 4)
        )
        val m2 = assistantMessage(
            id = 2,
            sessionId = 100,
            content = "Hi user 1",
            parentMessageId = 1,
            childrenMessageIds = listOf(3)
        )
        val m3 = userMessage(
            id = 3,
            sessionId = 100,
            content = "How are you?",
            parentMessageId = 2,
            childrenMessageIds = emptyList()
        )
        val m4 = assistantMessage(
            id = 4,
            sessionId = 100,
            content = "Hi user 2",
            parentMessageId = 1,
            childrenMessageIds = listOf(5)
        )
        val m5 = userMessage(
            id = 5,
            sessionId = 100,
            content = "Tell me about cats",
            parentMessageId = 4,
            childrenMessageIds = emptyList()
        )

        val allMessages = listOf(m1, m2, m3, m4, m5)
        val testSession = chatSession(
            id = 100L,
            name = "Complex Threading",
            currentLeafMessageId = 3L, // On first branch: m1->m2->m3
            messages = allMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = listOf(m1, m2, m3) // Display first branch
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Should display the correct branch messages
            onNodeWithText("Hello").assertIsDisplayed()
            onNodeWithText("Hi user 1").assertIsDisplayed()
            onNodeWithText("How are you?").assertIsDisplayed()

            // Should not display messages from other branch
            onNodeWithText("Hi user 2").assertDoesNotExist()
            onNodeWithText("Tell me about cats").assertDoesNotExist()
        }
    }

    @Test
    fun errorState_differentErrorTypes_showsCorrectMessages() {
        // Test different error types
        val notFoundError = apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")
        val repositoryError = notFoundError.toApiResourceError().toRepositoryError()
        val errorState = ChatAreaState(
            sessionUiState = DataState.Error(repositoryError)
        )

        runComposeUiTest {
            setContent {
                ChatArea(
                    state = errorState,
                    actions = mockActions
                )
            }

            // Assert - Error message should be displayed
            onNodeWithText("Failed to load chat session").assertIsDisplayed()
            onNodeWithText("Retry").assertIsDisplayed()
        }
    }

    @Test
    fun successState_longMessageContent_displaysCorrectly() {
        // Arrange - Create messages with long content
        val longContent =
            "This is a very long message content that should be displayed correctly in the UI. ".repeat(10)
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = longContent
            ),
            assistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "Short response"
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Long Content Session",
            currentLeafMessageId = 2L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testMessages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Long content should be initially collapsed (truncated)
            // MessageContent truncates at 200 chars and adds "..."
            val expectedTruncatedContent = longContent.take(200) + "..."
            val messageNode = onNodeWithText(expectedTruncatedContent)
            messageNode.assertIsDisplayed()

            // Act - Click to expand
            messageNode.performClick()

            // Assert - Full content should be displayed after expansion
            onNodeWithText(longContent).assertIsDisplayed()

            onNodeWithText("Short response").assertIsDisplayed()
        }
    }

    @Test
    fun successState_specialCharacters_displaysCorrectly() {
        // Arrange - Create messages with special characters
        val specialContent = "Special chars: @#$%^&*()_+{}|:<>?[]\\;'\",./ 🚀 ñáéíóú"
        val testMessages = listOf(
            userMessage(
                id = 1L,
                sessionId = 1L,
                content = specialContent
            )
        )
        val testSession = chatSession(
            id = 1L,
            name = "Special Chars Session",
            currentLeafMessageId = 1L,
            messages = testMessages
        )

        val successState = ChatAreaState(
            sessionUiState = DataState.Success(testSession),
            displayedMessages = testMessages
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = successState,
                    actions = mockActions
                )
            }

            // Assert - Special characters should be displayed correctly
            onNodeWithText(specialContent).assertIsDisplayed()
        }
    }

    @Test
    fun successState_multipleRetryClicks_callsRetryMultipleTimes() {
        // Arrange
        val testError = apiError(CommonApiErrorCodes.INTERNAL, "Server error")
        val repositoryError = testError.toApiResourceError().toRepositoryError()
        val errorState = ChatAreaState(
            sessionUiState = DataState.Error(repositoryError)
        )

        // Act & Assert
        runComposeUiTest {
            setContent {
                ChatArea(
                    state = errorState,
                    actions = mockActions
                )
            }

            val retryButton = onNodeWithText("Retry")

            // Click retry button multiple times
            retryButton.performClick()
            retryButton.performClick()
            retryButton.performClick()

            // Verify retry action was called multiple times
            verify(exactly = 3) { mockActions.onRetryLoadingSession() }
        }
    }

    @Test
    fun successState_stateTransitions_handlesCorrectly() {
        // Test that the component handles state transitions correctly
        val testMessages = listOf(
            userMessage(id = 1L, sessionId = 1L, content = "Hello")
        )
        val testSession = chatSession(
            id = 1L,
            name = "Test Session",
            currentLeafMessageId = 1L,
            messages = testMessages
        )

        runComposeUiTest {
            // Start with loading state
            setContent {
                ChatArea(
                    state = ChatAreaState(sessionUiState = DataState.Loading),
                    actions = mockActions
                )
            }

            // Assert loading state
            onNodeWithTag(LOADING_OVERLAY_TAG).assertExists()

            // Transition to success state
            setContent {
                ChatArea(
                    state = ChatAreaState(
                        sessionUiState = DataState.Success(testSession),
                        displayedMessages = testMessages
                    ),
                    actions = mockActions
                )
            }

            // Assert success state
            onNodeWithTag(LOADING_OVERLAY_TAG).assertDoesNotExist()
            onNodeWithText("Hello").assertIsDisplayed()
        }
    }
}
