package eu.torvian.chatbot.app.compose

import androidx.compose.ui.test.*
import eu.torvian.chatbot.app.compose.common.LOADING_OVERLAY_TAG
import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.chatSession
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.app.domain.contracts.ChatAreaActions
import eu.torvian.chatbot.app.domain.contracts.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Tests for the ChatArea composable, focusing on retry functionality.
 */
@OptIn(ExperimentalTestApi::class)
class ChatAreaTest {

    @Test
    fun errorState_showsRetryButton() {
        // Arrange
        val testError = apiError(CommonApiErrorCodes.INTERNAL, "Test error message")
        val errorState = ChatAreaState(
            sessionUiState = UiState.Error(testError),
            currentBranchLeafId = null,
            displayedMessages = emptyList(),
            inputContent = "",
            replyTargetMessage = null,
            editingMessage = null,
            editingContent = ""
        )

        // Using mockk to create a mock for ChatAreaActions
        val mockActions = mockk<ChatAreaActions>(relaxed = true) // relaxed = true allows un-stubbed calls to do nothing

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
            onNodeWithText("Test error message").assertIsDisplayed()

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
            sessionUiState = UiState.Loading
        )

        // Using mockk for ChatAreaActions
        val mockActions = mockk<ChatAreaActions>(relaxed = true)

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
            sessionUiState = UiState.Success(testSession),
            currentBranchLeafId = 2L,
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
}
