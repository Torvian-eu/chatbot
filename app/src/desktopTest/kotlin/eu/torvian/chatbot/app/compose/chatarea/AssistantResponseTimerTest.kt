package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.send_message_button_description
import eu.torvian.chatbot.app.viewmodel.chat.state.AssistantResponseTimerState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies the composer's elapsed-time indicator renders nothing before a turn, advances once per second while
 * the turn is active, and keeps its final value after the turn ends.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantResponseTimerTest {

    private val base = Instant.fromEpochSeconds(1_000_000L)

    /** Localized send-button description, resolved instead of hardcoded so the test does not depend on the locale. */
    private val sendButtonDescription: String =
        runBlocking { getString(Res.string.send_message_button_description) }

    /** Verifies a session without a turn renders no timer while the rest of the row still renders. */
    @Test
    fun hidden_rendersNothing() = runComposeUiTest {
        setContent {
            Column {
                Text("sentinel")
                AssistantResponseTimer(state = AssistantResponseTimerState.Hidden)
            }
        }

        onNodeWithText("sentinel").assertIsDisplayed()
        onNodeWithText("00:00").assertDoesNotExist()
    }

    /** Verifies the live value starts at zero and is recomputed from the start instant on each tick. */
    @Test
    fun running_showsZeroThenAdvancesOnTick() = runComposeUiTest {
        var fakeNow = base
        setContent {
            AssistantResponseTimer(
                state = AssistantResponseTimerState.Running(base),
                nowProvider = { fakeNow }
            )
        }

        onNodeWithText("00:00").assertIsDisplayed()

        fakeNow = base + 5.seconds
        mainClock.advanceTimeBy(1_000L)

        onNodeWithText("00:05").assertIsDisplayed()
    }

    /** Verifies a frozen value replaces the live one and no further tick changes it. */
    @Test
    fun frozen_showsFinalValueAndStopsTicking() = runComposeUiTest {
        var fakeNow = base
        val timerState = mutableStateOf<AssistantResponseTimerState>(
            AssistantResponseTimerState.Running(base)
        )
        setContent {
            AssistantResponseTimer(state = timerState.value, nowProvider = { fakeNow })
        }

        onNodeWithText("00:00").assertIsDisplayed()

        timerState.value = AssistantResponseTimerState.Frozen(12.seconds)

        onNodeWithText("00:12").assertIsDisplayed()
        onNodeWithText("00:00").assertDoesNotExist()

        fakeNow = base + 100.seconds
        mainClock.advanceTimeBy(10_000L)

        onNodeWithText("00:12").assertIsDisplayed()
    }

    /** Verifies a frozen value is rendered on the first frame without waiting for a tick. */
    @Test
    fun frozen_rendersImmediately() = runComposeUiTest {
        setContent {
            AssistantResponseTimer(state = AssistantResponseTimerState.Frozen(4_530.seconds))
        }

        onNodeWithText("75:30").assertIsDisplayed()
    }

    /** Verifies the composer shows a frozen value left of the action button. */
    @Test
    fun inputArea_showsFrozenValueNextToActionButton() = runComposeUiTest {
        setContent {
            InputArea(
                actions = noOpInputAreaActions(),
                replyTargetMessage = null,
                turnExecutionState = TurnExecutionState.IDLE,
                assistantResponseTimer = AssistantResponseTimerState.Frozen(12.seconds)
            )
        }

        onNodeWithText("00:12").assertIsDisplayed()
        onNodeWithContentDescription(sendButtonDescription).assertIsDisplayed()
    }

    /** Verifies the composer renders no timer in a session that has had no turn. */
    @Test
    fun inputArea_hidesTimerBeforeAnyTurn() = runComposeUiTest {
        setContent {
            InputArea(
                actions = noOpInputAreaActions(),
                replyTargetMessage = null,
                turnExecutionState = TurnExecutionState.IDLE
            )
        }

        onNodeWithText("00:12").assertDoesNotExist()
        onNodeWithContentDescription(sendButtonDescription).assertIsDisplayed()
    }
}

/**
 * Builds a composer action set that ignores every interaction, so tests can focus on rendering.
 *
 * @return Actions whose callbacks do nothing.
 */
private fun noOpInputAreaActions() = InputAreaActions(
    onUpdateInput = {},
    onSendMessage = {},
    onCancelSendMessage = {},
    onPauseSendMessage = {},
    onCancelReply = {},
    onAddFileReferences = {},
    onRemoveFileReference = {},
    onShowFileReferenceDetails = {},
    onManageFileReferences = {}
)
