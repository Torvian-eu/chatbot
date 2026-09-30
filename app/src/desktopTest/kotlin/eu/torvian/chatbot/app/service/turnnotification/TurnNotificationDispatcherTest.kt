package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.events.AppEvent
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [TurnNotificationDispatcher]: the master and channel toggles, the focus gate, the session
 * name resolution, the per-trigger alert count, and the notification click handling.
 *
 * The platform channel services are hand-written fakes so every test can assert exactly which channel
 * was used for which trigger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnNotificationDispatcherTest {

    /** Records sound plays requested by the dispatcher, and the release of the channel. */
    private class FakeSoundPlayer : TurnAlertSoundPlayer {
        var playCount: Int = 0
        var releaseCount: Int = 0

        override suspend fun playNotificationSound() {
            playCount++
        }

        override fun release() {
            releaseCount++
        }
    }

    /**
     * Records OS notifications and app raises, and exposes a controllable click stream.
     *
     * @property permission Permission reported by [permissionState] and [requestPermission].
     */
    private class FakeOsNotificationService(
        var permission: TurnOsNotificationPermission = TurnOsNotificationPermission.GRANTED
    ) : TurnOsNotificationService {
        val shown = mutableListOf<TurnOsNotificationRequest>()
        var bringToFrontCount: Int = 0
        var requestPermissionCount: Int = 0
        var permissionStateCount: Int = 0
        private val mutableClicks = MutableSharedFlow<Long>(extraBufferCapacity = 1)
        override val clickedSessionIds: Flow<Long> = mutableClicks

        override suspend fun permissionState(): TurnOsNotificationPermission {
            permissionStateCount++
            return permission
        }

        override suspend fun requestPermission(): TurnOsNotificationPermission {
            requestPermissionCount++
            return permission
        }

        override suspend fun showNotification(request: TurnOsNotificationRequest) {
            shown.add(request)
        }

        override suspend fun bringAppToFront() {
            bringToFrontCount++
        }

        /**
         * Emits one notification click.
         *
         * @param sessionId Session the clicked notification belonged to.
         */
        suspend fun click(sessionId: Long) {
            mutableClicks.emit(sessionId)
        }
    }

    /** Text source whose output makes the resolved session name and the trigger kind observable. */
    private class FakeTextSource : TurnNotificationTextSource {
        override suspend fun content(
            trigger: TurnLifecycleTrigger,
            sessionName: String?
        ): TurnNotificationContent = TurnNotificationContent(
            title = sessionName ?: "fallback-title",
            body = when (trigger) {
                is TurnLifecycleTrigger.TurnCompleted -> when (trigger.outcome) {
                    TurnOutcome.SUCCESS -> "success-body"
                    TurnOutcome.FAILURE -> "failure-body"
                }

                is TurnLifecycleTrigger.AwaitingApproval -> "awaiting-body"
            }
        )
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val sessionId = 42L

    /** Success trigger for the fixture session. */
    private fun successTrigger() = TurnLifecycleTrigger.TurnCompleted(sessionId, TurnOutcome.SUCCESS)

    /**
     * Dispatcher under test plus the fakes a test asserts on.
     *
     * @property dispatcher Dispatcher to drive.
     * @property eventBus Bus the test publishes triggers on, as the turn source would.
     * @property soundPlayer Sound channel fake.
     * @property osNotifications OS notification channel fake.
     */
    private data class Trio(
        val dispatcher: TurnNotificationDispatcher,
        val eventBus: EventBus,
        val soundPlayer: FakeSoundPlayer,
        val osNotifications: FakeOsNotificationService
    )

    /**
     * Wires a dispatcher with fakes whose instances are handed back to the test.
     *
     * @param storedPreference Preference the repository reports, or `null` for "never configured".
     * @param focused Whether the app reports focus.
     * @param permission OS-notification permission reported by the platform service.
     * @param sessions Session list the name lookup searches.
     * @return Dispatcher plus the fakes to assert on.
     */
    private fun dispatcherWith(
        storedPreference: TurnNotificationPreference? = TurnNotificationPreference.DEFAULT,
        focused: Boolean = false,
        permission: TurnOsNotificationPermission = TurnOsNotificationPermission.GRANTED,
        sessions: List<ChatSessionSummary> = listOf(
            ChatSessionSummary(sessionId, "Release planning", now, now, groupId = null)
        )
    ): Trio {
        val preferences = mockk<UserPreferenceRepository>(relaxed = true)
        every { preferences.turnNotificationPreference } returns MutableStateFlow(storedPreference)
        val sessionRepository = mockk<SessionRepository>()
        val sessionsState: DataState<RepositoryError, List<ChatSessionSummary>> = DataState.Success(sessions)
        every { sessionRepository.sessions } returns MutableStateFlow(sessionsState)

        val soundPlayer = FakeSoundPlayer()
        val osNotifications = FakeOsNotificationService(permission)
        val eventBus = EventBus()
        return Trio(
            dispatcher = TurnNotificationDispatcher(
                eventBus = eventBus,
                preferences = preferences,
                focusState = DefaultAppFocusState().apply { setFocused(focused) },
                sessionRepository = sessionRepository,
                soundPlayer = soundPlayer,
                osNotifications = osNotifications,
                textSource = FakeTextSource()
            ),
            eventBus = eventBus,
            soundPlayer = soundPlayer,
            osNotifications = osNotifications
        )
    }

    @Test
    fun `successful turn plays the sound and shows one success notification`() = runTest {
        val trio = dispatcherWith()
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertEquals(1, trio.osNotifications.shown.size)
        assertEquals("Release planning", trio.osNotifications.shown.single().title)
        assertEquals("success-body", trio.osNotifications.shown.single().body)
        assertEquals(sessionId, trio.osNotifications.shown.single().sessionId)
    }

    @Test
    fun `failed turn shows the failure text`() = runTest {
        val trio = dispatcherWith()
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(TurnLifecycleTrigger.TurnCompleted(sessionId, TurnOutcome.FAILURE))
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertEquals("failure-body", trio.osNotifications.shown.single().body)
    }

    @Test
    fun `awaiting approval shows the awaiting text`() = runTest {
        val trio = dispatcherWith()
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertEquals("awaiting-body", trio.osNotifications.shown.single().body)
    }

    @Test
    fun `focused app produces no alert`() = runTest {
        val trio = dispatcherWith(focused = true)
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(0, trio.soundPlayer.playCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `master toggle off produces no alert`() = runTest {
        val trio = dispatcherWith(
            storedPreference = TurnNotificationPreference(
                enabled = false,
                soundEnabled = true,
                osNotificationEnabled = true
            )
        )
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(0, trio.soundPlayer.playCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `absent preference row uses the enabled defaults`() = runTest {
        val trio = dispatcherWith(storedPreference = null)
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertEquals(1, trio.osNotifications.shown.size)
    }

    @Test
    fun `sound only when the OS channel is disabled`() = runTest {
        val trio = dispatcherWith(
            storedPreference = TurnNotificationPreference(
                enabled = true,
                soundEnabled = true,
                osNotificationEnabled = false
            )
        )
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `OS notification only when the sound is disabled`() = runTest {
        val trio = dispatcherWith(
            storedPreference = TurnNotificationPreference(
                enabled = true,
                soundEnabled = false,
                osNotificationEnabled = true
            )
        )
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(0, trio.soundPlayer.playCount)
        assertEquals(1, trio.osNotifications.shown.size)
    }

    @Test
    fun `sound still plays without notification permission`() = runTest {
        val trio = dispatcherWith(permission = TurnOsNotificationPermission.DENIED)
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `unknown session falls back to the generic title`() = runTest {
        val trio = dispatcherWith(sessions = emptyList())
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals("fallback-title", trio.osNotifications.shown.single().title)
    }

    @Test
    fun `a platform failure does not stop later alerts`() = runTest {
        val failingSoundPlayer = object : TurnAlertSoundPlayer {
            var calls: Int = 0
            override suspend fun playNotificationSound() {
                calls++
                // Mirrors an implementation that logs an internal failure and returns normally.
            }
        }
        val preferences = mockk<UserPreferenceRepository>(relaxed = true)
        every { preferences.turnNotificationPreference } returns MutableStateFlow(TurnNotificationPreference.DEFAULT)
        val sessionRepository = mockk<SessionRepository>()
        every { sessionRepository.sessions } returns MutableStateFlow(DataState.Success(emptyList()))
        val osNotifications = FakeOsNotificationService(permission = TurnOsNotificationPermission.UNSUPPORTED)
        val eventBus = EventBus()
        val dispatcher = TurnNotificationDispatcher(
            eventBus = eventBus,
            preferences = preferences,
            focusState = DefaultAppFocusState(),
            sessionRepository = sessionRepository,
            soundPlayer = failingSoundPlayer,
            osNotifications = osNotifications,
            textSource = FakeTextSource()
        )

        val collector = backgroundScope.launch { dispatcher.run { } }
        runCurrent()
        eventBus.tryEmitEvent(successTrigger())
        eventBus.tryEmitEvent(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        runCurrent()

        assertEquals(2, failingSoundPlayer.calls)
        assertTrue(osNotifications.shown.isEmpty())
        collector.cancel()
    }

    /**
     * Wires a dispatcher whose channel services the test supplies.
     *
     * @property dispatcher Dispatcher to drive.
     * @property eventBus Bus the test publishes triggers on.
     */
    private data class ChannelWiring(
        val dispatcher: TurnNotificationDispatcher,
        val eventBus: EventBus
    )

    /**
     * Builds a dispatcher on the enabled defaults with the supplied platform channels.
     *
     * @param soundPlayer Sound channel to drive.
     * @param osNotifications OS notification channel to drive.
     * @param textSource Text source to resolve the alert content with.
     * @return Dispatcher plus the bus to publish triggers on.
     */
    private fun channelWiring(
        soundPlayer: TurnAlertSoundPlayer,
        osNotifications: TurnOsNotificationService,
        textSource: TurnNotificationTextSource = FakeTextSource()
    ): ChannelWiring {
        val preferences = mockk<UserPreferenceRepository>(relaxed = true)
        every { preferences.turnNotificationPreference } returns MutableStateFlow(TurnNotificationPreference.DEFAULT)
        val sessionRepository = mockk<SessionRepository>()
        every { sessionRepository.sessions } returns MutableStateFlow(DataState.Success(emptyList()))
        val eventBus = EventBus()
        return ChannelWiring(
            dispatcher = TurnNotificationDispatcher(
                eventBus = eventBus,
                preferences = preferences,
                focusState = DefaultAppFocusState(),
                sessionRepository = sessionRepository,
                soundPlayer = soundPlayer,
                osNotifications = osNotifications,
                textSource = textSource
            ),
            eventBus = eventBus
        )
    }

    @Test
    fun `a throwing sound player does not stop later alerts`() = runTest {
        val soundPlayer = object : TurnAlertSoundPlayer {
            var calls: Int = 0
            override suspend fun playNotificationSound() {
                calls++
                // A platform service that violates its best-effort contract must not disable alerting.
                error("no audio line available")
            }
        }
        val osNotifications = FakeOsNotificationService(permission = TurnOsNotificationPermission.GRANTED)
        val wiring = channelWiring(soundPlayer = soundPlayer, osNotifications = osNotifications)

        backgroundScope.launch { wiring.dispatcher.run { } }
        runCurrent()
        wiring.eventBus.tryEmitEvent(successTrigger())
        runCurrent()
        wiring.eventBus.tryEmitEvent(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        runCurrent()

        assertEquals(2, soundPlayer.calls)
        // The channels are independent, so a sound failure still leaves the OS notification.
        assertEquals(2, osNotifications.shown.size)
    }

    @Test
    fun `a throwing OS notification service does not stop later alerts`() = runTest {
        val soundPlayer = FakeSoundPlayer()
        val osNotifications = object : TurnOsNotificationService {
            var shown: Int = 0
            override suspend fun permissionState(): TurnOsNotificationPermission = TurnOsNotificationPermission.GRANTED
            override suspend fun requestPermission(): TurnOsNotificationPermission = TurnOsNotificationPermission.GRANTED
            override suspend fun showNotification(request: TurnOsNotificationRequest) {
                shown++
                error("no notification manager")
            }

            override suspend fun bringAppToFront() = Unit
            override val clickedSessionIds: Flow<Long> = MutableSharedFlow()
        }
        val wiring = channelWiring(soundPlayer = soundPlayer, osNotifications = osNotifications)

        backgroundScope.launch { wiring.dispatcher.run { } }
        runCurrent()
        wiring.eventBus.tryEmitEvent(successTrigger())
        runCurrent()
        wiring.eventBus.tryEmitEvent(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        runCurrent()

        assertEquals(2, osNotifications.shown)
        assertEquals(2, soundPlayer.playCount)
    }

    @Test
    fun `a failing text source degrades to sound only`() = runTest {
        val soundPlayer = FakeSoundPlayer()
        val osNotifications = FakeOsNotificationService(permission = TurnOsNotificationPermission.GRANTED)
        val failingTextSource = object : TurnNotificationTextSource {
            override suspend fun content(
                trigger: TurnLifecycleTrigger,
                sessionName: String?
            ): TurnNotificationContent = error("no resource environment")
        }
        val wiring = channelWiring(
            soundPlayer = soundPlayer,
            osNotifications = osNotifications,
            textSource = failingTextSource
        )

        backgroundScope.launch { wiring.dispatcher.run { } }
        runCurrent()
        wiring.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        // The guaranteed channel still fires; texting the alert is the only thing that is lost.
        assertEquals(1, soundPlayer.playCount)
        assertTrue(osNotifications.shown.isEmpty())
    }

    @Test
    fun `a disabled OS channel never probes the platform`() = runTest {
        val trio = dispatcherWith(
            storedPreference = TurnNotificationPreference(
                enabled = true,
                soundEnabled = true,
                osNotificationEnabled = false
            )
        )
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        assertEquals(1, trio.soundPlayer.playCount)
        // Probing is not free, because desktop installs its tray icon on the first call.
        assertEquals(0, trio.osNotifications.permissionStateCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `the alert path never prompts for permission`() = runTest {
        val trio = dispatcherWith(permission = TurnOsNotificationPermission.NOT_DETERMINED)
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        // A prompt is only effective from a user gesture, and the app is unattended by definition
        // when an alert fires; the settings surface owns every request.
        assertEquals(0, trio.osNotifications.requestPermissionCount)
        assertEquals(1, trio.soundPlayer.playCount)
        assertTrue(trio.osNotifications.shown.isEmpty())
    }

    @Test
    fun `a blocked alert cannot make the shared bus drop later events`() = runTest {
        val blockingSoundPlayer = object : TurnAlertSoundPlayer {
            override suspend fun playNotificationSound() {
                // Never returns, mimicking a platform call that stalls for as long as it wants.
                awaitCancellation()
            }
        }
        val osNotifications = FakeOsNotificationService(permission = TurnOsNotificationPermission.UNSUPPORTED)
        val wiring = channelWiring(soundPlayer = blockingSoundPlayer, osNotifications = osNotifications)
        val unrelatedEvents = mutableListOf<UnrelatedEvent>()

        backgroundScope.launch { wiring.dispatcher.run { } }
        backgroundScope.launch {
            wiring.eventBus.events.filterIsInstance<UnrelatedEvent>().collect { unrelatedEvents.add(it) }
        }
        runCurrent()

        // The alert gets stuck in the sound channel, so handling it inline would keep the bus
        // subscriber from consuming anything else until it finishes.
        wiring.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        // More events than the bus can buffer must all be accepted: the alert must not occupy the
        // buffer and make the newest event a silent drop for everyone.
        val accepted = (0 until bufferOverflowProbeSize).count {
            val acceptedEvent = wiring.eventBus.tryEmitEvent(UnrelatedEvent)
            runCurrent()
            acceptedEvent
        }

        assertEquals(bufferOverflowProbeSize, accepted)
        assertEquals(bufferOverflowProbeSize, unrelatedEvents.size)
    }

    @Test
    fun `click raises the app and selects the session once`() = runTest {
        val trio = dispatcherWith()
        val opened = mutableListOf<Long>()

        backgroundScope.launch { trio.dispatcher.run { sessionId -> opened.add(sessionId) } }
        runCurrent()
        trio.osNotifications.click(sessionId)
        runCurrent()

        assertEquals(listOf(sessionId), opened.toList())
        assertEquals(1, trio.osNotifications.bringToFrontCount)
    }

    @Test
    fun `a throwing click callback does not stop alerting`() = runTest {
        val trio = dispatcherWith()
        val opened = mutableListOf<Long>()

        backgroundScope.launch {
            trio.dispatcher.run { sessionId ->
                opened.add(sessionId)
                // Navigation is real code on the click path; its failure must not escape into the
                // host's LaunchedEffect, nor cancel the sibling alert collectors.
                error("navigation failed")
            }
        }
        runCurrent()
        trio.osNotifications.click(sessionId)
        runCurrent()

        assertEquals(listOf(sessionId), opened.toList())

        // The click failure did not tear down alerting: a later trigger still raises both channels.
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()
        assertEquals(1, trio.soundPlayer.playCount)
        assertEquals(1, trio.osNotifications.shown.size)

        // The click channel is still collected, so a second click is handled as well.
        trio.osNotifications.click(sessionId)
        runCurrent()
        assertEquals(listOf(sessionId, sessionId), opened.toList())
    }

    @Test
    fun `the sound channel is released when alerting stops`() = runTest {
        val trio = dispatcherWith()
        val alerting = backgroundScope.launch { trio.dispatcher.run { } }
        runCurrent()
        trio.eventBus.tryEmitEvent(successTrigger())
        runCurrent()

        alerting.cancelAndJoin()

        // The audio resources belong to the alerting lifetime, not to the process.
        assertEquals(1, trio.soundPlayer.releaseCount)
    }

    @Test
    fun `two triggers produce two alerts`() = runTest {
        val trio = dispatcherWith()
        startDispatcher(trio)
        trio.eventBus.tryEmitEvent(successTrigger())
        trio.eventBus.tryEmitEvent(TurnLifecycleTrigger.AwaitingApproval(sessionId))
        runCurrent()

        assertEquals(2, trio.soundPlayer.playCount)
        assertEquals(2, trio.osNotifications.shown.size)
    }

    /** Unrelated bus payload used to prove that alert handling does not hold up other consumers. */
    private data object UnrelatedEvent : AppEvent()

    /**
     * Number of unrelated events published against a blocked alert.
     *
     * Derived from the shared bus's own buffer depth, plus a margin: the probe has to exceed what the
     * bus can hold so that a subscriber stuck inside alert handling forces the bus to reject an event,
     * which is exactly the drop this dispatcher must not cause. A fixed number would stop proving
     * anything as soon as the buffer depth grew past it.
     */
    private val bufferOverflowProbeSize = EventBus.EVENT_BUFFER_CAPACITY + 8

    /**
     * Starts the dispatcher on the test scope and lets it subscribe.
     *
     * @receiver Test scope the dispatcher coroutine runs in.
     * @param trio Bundle whose dispatcher is started.
     */
    private fun TestScope.startDispatcher(trio: Trio) {
        backgroundScope.launch { trio.dispatcher.run { } }
        runCurrent()
    }
}
