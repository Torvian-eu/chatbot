package eu.torvian.chatbot.app.viewmodel

import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.GroupRepository
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionIndicator
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatus
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.app.viewmodel.sessionstatus.TurnOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the session-indicator derivation of [SessionListViewModel]: registry entries map to
 * their highest-priority [SessionIndicator], and entries without an indicator are dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelIndicatorTest {

    /** Registry status map the view model derives its indicators from. */
    private val registryStatuses = MutableStateFlow<Map<Long, SessionTurnStatus>>(emptyMap())

    /** Real selection controller shared with the view model construction. */
    private val sessionSelectionController = DefaultSessionSelectionController()

    /**
     * Builds a view model whose repositories hold empty data and whose registry is a mock exposing
     * [registryStatuses].
     *
     * @receiver The surrounding test scope providing the scheduler for the UI dispatcher.
     * @return View model ready for indicator assertions.
     */
    private fun TestScope.buildViewModel(): SessionListViewModel {
        val sessionRepository = mockk<SessionRepository>()
        every { sessionRepository.sessions } returns MutableStateFlow(DataState.Success(emptyList()))
        coEvery { sessionRepository.loadSessions() } returns Unit.right()

        val groupRepository = mockk<GroupRepository>()
        every { groupRepository.groups } returns MutableStateFlow(DataState.Success(emptyList()))
        coEvery { groupRepository.loadGroups() } returns Unit.right()

        val registry = mockk<SessionTurnStatusRegistry>(relaxed = true)
        every { registry.statuses } returns registryStatuses

        return SessionListViewModel(
            sessionRepository = sessionRepository,
            groupRepository = groupRepository,
            eventBus = mockk<EventBus>(relaxed = true),
            sessionSelectionController = sessionSelectionController,
            notificationService = mockk<NotificationService>(relaxed = true),
            sessionTurnStatusRegistry = registry,
            uiDispatcher = StandardTestDispatcher(testScheduler)
        )
    }

    @Test
    fun `registry entries map to their highest-priority indicator`() = runTest {
        // The view model collects on viewModelScope, which needs a Main dispatcher in tests; it must
        // share the test scheduler so advances drive both scopes.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = buildViewModel()
            // WhileSubscribed only collects upstream while the UI observes the flow.
            val collectJob = backgroundScope.launch { viewModel.sessionIndicators.collect {} }
            advanceUntilIdle()

            registryStatuses.value = mapOf(
                1L to SessionTurnStatus(isTurnActive = true, isAwaitingInput = true),
                2L to SessionTurnStatus(isTurnActive = true),
                3L to SessionTurnStatus(lastOutcome = TurnOutcome.SUCCESS),
                4L to SessionTurnStatus(lastOutcome = TurnOutcome.FAILURE),
                5L to SessionTurnStatus()
            )
            advanceUntilIdle()

            assertEquals(
                mapOf(
                    1L to SessionIndicator.REQUESTING_INPUT,
                    2L to SessionIndicator.BUSY,
                    3L to SessionIndicator.COMPLETED_SUCCESS,
                    4L to SessionIndicator.COMPLETED_FAILURE
                ),
                viewModel.sessionIndicators.value
            )
            collectJob.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
