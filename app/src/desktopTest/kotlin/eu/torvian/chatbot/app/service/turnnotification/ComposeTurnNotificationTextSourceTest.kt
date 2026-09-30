package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_awaiting_approval
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_turn_completed_failure
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_turn_completed_success
import eu.torvian.chatbot.app.generated.resources.turn_notification_title_unknown_session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [ComposeTurnNotificationTextSource] against the real bundled strings.
 *
 * Every body assertion compares the resolved text with the lookup of the resource that state is
 * supposed to use, so the test pins the state-to-string mapping without depending on the machine's
 * locale, while the distinctness checks keep success, failure and awaiting-approval tellable apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeTurnNotificationTextSourceTest {

    private val source = ComposeTurnNotificationTextSource()

    /** Session used by the fixtures. */
    private val sessionId = 7L

    @Test
    fun `the session name is the title for every state`() = runTest {
        val sessionName = "Release planning"

        assertEquals(sessionName, source.content(completed(TurnOutcome.SUCCESS), sessionName).title)
        assertEquals(sessionName, source.content(completed(TurnOutcome.FAILURE), sessionName).title)
        assertEquals(sessionName, source.content(awaiting(), sessionName).title)
    }

    @Test
    fun `an unknown session falls back to the generic title`() = runTest {
        val expected = getString(Res.string.turn_notification_title_unknown_session)
        val content = source.content(completed(TurnOutcome.SUCCESS), sessionName = null)

        assertEquals(expected, content.title)
        assertTrue(content.title.isNotBlank())
    }

    @Test
    fun `every state resolves its own body`() = runTest {
        val success = source.content(completed(TurnOutcome.SUCCESS), sessionName = null).body
        val failure = source.content(completed(TurnOutcome.FAILURE), sessionName = null).body
        val awaiting = source.content(awaiting(), sessionName = null).body

        assertEquals(getString(Res.string.turn_notification_body_turn_completed_success), success)
        assertEquals(getString(Res.string.turn_notification_body_turn_completed_failure), failure)
        assertEquals(getString(Res.string.turn_notification_body_awaiting_approval), awaiting)
        assertTrue(listOf(success, failure, awaiting).all { it.isNotBlank() })
        assertNotEquals(success, failure)
        assertNotEquals(success, awaiting)
        assertNotEquals(failure, awaiting)
    }

    /**
     * Builds a finished-turn trigger with the given outcome.
     *
     * @param outcome Terminal classification the notification must describe.
     * @return Trigger for the fixture session.
     */
    private fun completed(outcome: TurnOutcome) = TurnLifecycleTrigger.TurnCompleted(sessionId, outcome)

    /**
     * Builds an awaiting-approval trigger for the fixture session.
     *
     * @return Trigger for the fixture session.
     */
    private fun awaiting() = TurnLifecycleTrigger.AwaitingApproval(sessionId)
}
