package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.service.turnnotification.TurnOsChannelState
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationPermission
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [NotificationsViewModel]: each toggle persists the whole preference object, enabling the
 * OS channel asks for permission, the exposed channel classification combines the stored toggle with
 * the platform permission, a channel the user did not ask for is never probed, and a failed write is
 * surfaced in-app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var osNotifications: TurnOsNotificationService
    private lateinit var notificationService: NotificationService
    private lateinit var stored: MutableStateFlow<TurnNotificationPreference?>
    private lateinit var viewModel: NotificationsViewModel

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        userPreferenceRepository = mockk(relaxed = true)
        osNotifications = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        stored = MutableStateFlow(TurnNotificationPreference.DEFAULT)

        every { userPreferenceRepository.turnNotificationPreference } returns stored
        coEvery { userPreferenceRepository.setTurnNotificationPreference(any()) } returns Either.Right(Unit)
        coEvery { osNotifications.permissionState() } returns TurnOsNotificationPermission.GRANTED
        coEvery { osNotifications.requestPermission() } returns TurnOsNotificationPermission.GRANTED

        viewModel = NotificationsViewModel(
            userPreferenceRepository = userPreferenceRepository,
            osNotifications = osNotifications,
            notificationService = notificationService,
            uiDispatcher = dispatcher
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel the viewModel scope so no coroutine leaks across tests.
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `turning the master toggle off persists the whole preference`() = runTest {
        viewModel.setEnabled(false)

        coVerify(exactly = 1) {
            userPreferenceRepository.setTurnNotificationPreference(
                TurnNotificationPreference(enabled = false, soundEnabled = true, osNotificationEnabled = true)
            )
        }
    }

    @Test
    fun `turning the sound off keeps the other toggles`() = runTest {
        viewModel.setSoundEnabled(false)

        coVerify(exactly = 1) {
            userPreferenceRepository.setTurnNotificationPreference(
                TurnNotificationPreference(enabled = true, soundEnabled = false, osNotificationEnabled = true)
            )
        }
    }

    @Test
    fun `enabling the OS channel requests permission and persists the change`() = runTest {
        viewModel.setOsNotificationEnabled(true)

        coVerify(exactly = 1) { osNotifications.requestPermission() }
        coVerify(exactly = 1) {
            userPreferenceRepository.setTurnNotificationPreference(
                TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = true)
            )
        }
    }

    @Test
    fun `disabling the OS channel does not request permission`() = runTest {
        stored.value = TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = true)

        viewModel.setOsNotificationEnabled(false)

        coVerify(exactly = 0) { osNotifications.requestPermission() }
        coVerify(exactly = 1) {
            userPreferenceRepository.setTurnNotificationPreference(
                TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = false)
            )
        }
    }

    @Test
    fun `a failed write is surfaced in-app and keeps the exposed state consistent`() = runTest {
        coEvery { userPreferenceRepository.setTurnNotificationPreference(any()) } returns
            Either.Left(RepositoryError.OtherError("offline"))

        viewModel.setSoundEnabled(false)

        coVerify(exactly = 1) {
            notificationService.repositoryError(error = any(), shortMessage = any(), isRetryable = any())
        }
        // The stored value is unchanged, so the next preference sync shows the authoritative state
        // instead of a value that was never persisted.
        assertEquals(TurnNotificationPreference.DEFAULT, stored.value)
    }

    @Test
    fun `the exposed preference is seeded from the stored value before the flow is collected`() = runTest {
        val storedToggles = TurnNotificationPreference(
            enabled = false,
            soundEnabled = false,
            osNotificationEnabled = false
        )
        stored.value = storedToggles

        // Rebuild the ViewModel once the repository already holds the stored value, mirroring the
        // moment the settings tab opens.
        viewModel.viewModelScope.cancel()
        viewModel = NotificationsViewModel(
            userPreferenceRepository = userPreferenceRepository,
            osNotifications = osNotifications,
            notificationService = notificationService,
            uiDispatcher = dispatcher
        )

        // No collector is started, so this is exactly the value the first frame renders; the stored
        // toggles must not flash as the enabled defaults.
        assertEquals(storedToggles, viewModel.preference.value)
    }

    @Test
    fun `requesting permission adopts the platform answer without touching the preference`() = runTest {
        // The answer of the request differs from a fresh read, so the test can tell which one is used.
        coEvery { osNotifications.requestPermission() } returns TurnOsNotificationPermission.NOT_DETERMINED
        coEvery { osNotifications.permissionState() } returns TurnOsNotificationPermission.DENIED
        collectOsChannel()

        viewModel.requestPermission()

        assertEquals(TurnOsChannelState.NEEDS_PERMISSION, viewModel.osChannel.value)
        coVerify(exactly = 0) { userPreferenceRepository.setTurnNotificationPreference(any()) }
    }

    @Test
    fun `the channel is active once the platform grants it`() = runTest {
        coEvery { osNotifications.permissionState() } returns TurnOsNotificationPermission.GRANTED
        collectOsChannel()

        viewModel.loadPermissionState()

        assertEquals(TurnOsChannelState.ACTIVE, viewModel.osChannel.value)
    }

    @Test
    fun `a refused permission classifies the channel as blocked`() = runTest {
        coEvery { osNotifications.permissionState() } returns TurnOsNotificationPermission.DENIED
        collectOsChannel()

        viewModel.loadPermissionState()

        assertEquals(TurnOsChannelState.BLOCKED, viewModel.osChannel.value)
    }

    @Test
    fun `a stored channel turned off stays disabled whatever the platform grants`() = runTest {
        stored.value = TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = false)
        coEvery { osNotifications.permissionState() } returns TurnOsNotificationPermission.GRANTED
        collectOsChannel()

        viewModel.loadPermissionState()

        assertEquals(TurnOsChannelState.DISABLED, viewModel.osChannel.value)
    }

    @Test
    fun `opening the category with the feature off never probes the platform`() = runTest {
        stored.value = TurnNotificationPreference(enabled = false, soundEnabled = true, osNotificationEnabled = true)
        collectOsChannel()

        viewModel.loadPermissionState()

        // The classification is disabled whatever the platform would answer, and on desktop the probe
        // installs a tray icon as a side effect.
        coVerify(exactly = 0) { osNotifications.permissionState() }
        assertEquals(TurnOsChannelState.DISABLED, viewModel.osChannel.value)
    }

    @Test
    fun `opening the category with the OS channel off never probes the platform`() = runTest {
        stored.value = TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = false)
        collectOsChannel()

        viewModel.loadPermissionState()

        coVerify(exactly = 0) { osNotifications.permissionState() }
        assertEquals(TurnOsChannelState.DISABLED, viewModel.osChannel.value)
    }

    @Test
    fun `enabling the master toggle reads the permission for a wanted OS channel`() = runTest {
        stored.value = TurnNotificationPreference(enabled = false, soundEnabled = true, osNotificationEnabled = true)
        // Applied like the real repository does, so the classification sees the written toggles.
        coEvery { userPreferenceRepository.setTurnNotificationPreference(any()) } coAnswers {
            stored.value = firstArg()
            Either.Right(Unit)
        }
        collectOsChannel()

        viewModel.setEnabled(true)

        // The channel can become wanted for the first time here, so the classification needs a read.
        coVerify(exactly = 1) { osNotifications.permissionState() }
        assertEquals(TurnOsChannelState.ACTIVE, viewModel.osChannel.value)
    }

    @Test
    fun `a wanted channel reports no verdict until the permission has been read`() = runTest {
        // The read is held open so the test can observe the state the surface renders first.
        val pending = CompletableDeferred<TurnOsNotificationPermission>()
        coEvery { osNotifications.permissionState() } coAnswers { pending.await() }
        collectOsChannel()

        viewModel.loadPermissionState()

        // An unread permission must not be rendered as a missing one, which would offer a request the
        // platform has not been asked about yet.
        assertNull(viewModel.osChannel.value)

        pending.complete(TurnOsNotificationPermission.NOT_DETERMINED)

        assertEquals(TurnOsChannelState.NEEDS_PERMISSION, viewModel.osChannel.value)
    }

    /**
     * Subscribes to [NotificationsViewModel.osChannel] so its combined state runs.
     *
     * @receiver Test scope the collector is started in.
     */
    private fun TestScope.collectOsChannel() {
        backgroundScope.launch(dispatcher) { viewModel.osChannel.collect {} }
    }
}
