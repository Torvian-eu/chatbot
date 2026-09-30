package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Decides which turn alerts to raise and hands them to the platform channel services.
 *
 * Runs for as long as the authenticated app shell is composed: triggers are picked off the shared
 * application event bus by type (they are published by the turn-status registry) and notification
 * clicks come from [TurnOsNotificationService]. Both the toggle state and the focus state are read as
 * plain snapshots at trigger time, so alerting adds no subscription, polling, or timer.
 *
 * @property eventBus Shared application event bus carrying the turn triggers.
 * @property preferences Repository holding the user's stored toggles.
 * @property focusState Current window/tab attention state, used to suppress alerts while the user
 *           is already looking at the app.
 * @property sessionRepository Repository used to resolve the triggering session's display name
 *           without an extra network call.
 * @property soundPlayer Channel that plays the bundled alert sound.
 * @property osNotifications Channel that shows OS notifications and reports clicks.
 * @property textSource Builds the localized alert text.
 */
class TurnNotificationDispatcher(
    private val eventBus: EventBus,
    private val preferences: UserPreferenceRepository,
    private val focusState: AppFocusState,
    private val sessionRepository: SessionRepository,
    private val soundPlayer: TurnAlertSoundPlayer,
    private val osNotifications: TurnOsNotificationService,
    private val textSource: TurnNotificationTextSource
) {

    private val logger = kmpLogger<TurnNotificationDispatcher>()

    /**
     * Collects turn triggers and notification clicks until the calling scope is cancelled.
     *
     * The returned signal is the end of alerting, which is also where the sound channel gives back
     * the resources it held for playback.
     *
     * @param onOpenSession Called with the triggering session id after a notification click; the
     *           caller selects the session and shows the chat screen.
     */
    suspend fun run(onOpenSession: (Long) -> Unit) {
        try {
            coroutineScope {
                // Alerts are handled on this component's own coroutine, fed by a queue, rather than
                // inline in the bus collector: showing a sound or a notification suspends (EDT/tray
                // setup, audio, activity launches), and doing that on the shared collector would delay
                // every unrelated event consumer and can make the bus reject the newest event.
                val alerts = Channel<TurnLifecycleTrigger>(capacity = Channel.UNLIMITED)
                launch {
                    // The bus carries every application event, so only the alert triggers are handled.
                    eventBus.events.filterIsInstance<TurnLifecycleTrigger>().collect { trigger ->
                        if (!alerts.trySend(trigger).isSuccess) {
                            // Queuing cannot realistically fail while the queue is unbounded; log it so a
                            // lost alert stays diagnosable.
                            logger.warn("Turn alert dropped: the dispatcher queue rejected the trigger")
                        }
                    }
                }
                launch {
                    for (trigger in alerts) {
                        // A platform failure must not tear down this loop: losing it would silently
                        // disable every later alert for the rest of the session.
                        runCatching { handle(trigger) }.onFailure { failure ->
                            logger.warn("Turn notification handling failed: ${failure.message}", failure)
                        }
                    }
                }
                launch {
                    osNotifications.clickedSessionIds.collect { sessionId ->
                        logger.info("Turn notification clicked for session $sessionId")
                        // The click runs real platform and navigation code on the same coroutine as
                        // the alert collectors; a failure there must cost at most the click, never
                        // the collectors whose cancellation would silence every later alert.
                        runCatching {
                            osNotifications.bringAppToFront()
                            onOpenSession(sessionId)
                        }.onFailure { failure ->
                            logger.warn("Turn notification click handling failed: ${failure.message}", failure)
                        }
                    }
                }
            }
        } finally {
            // Releasing is part of the best-effort contract: it must not add a failure to a shutdown.
            runCatching { soundPlayer.release() }.onFailure { failure ->
                logger.warn("Releasing the turn alert sound failed: ${failure.message}", failure)
            }
        }
    }

    /**
     * Raises the alert channels enabled for one trigger while the app is unattended.
     *
     * @param trigger Turn event to alert on.
     */
    private suspend fun handle(trigger: TurnLifecycleTrigger) {
        // An absent row means the user never configured the feature; the defaults are opt-out
        // because the focus gate already prevents alerts while the app is attended.
        val preference = preferences.turnNotificationPreference.value ?: TurnNotificationPreference.DEFAULT
        if (!preference.enabled) {
            logger.debug("Turn notification skipped: disabled for ${trigger.sessionId}")
            return
        }
        if (focusState.isFocused.value) {
            logger.debug("Turn notification skipped: app focused for ${trigger.sessionId}")
            return
        }

        if (preference.soundEnabled) {
            // Sound is the channel that survives every platform limitation and needs no text, so it
            // goes first and on its own: neither a failure of the player nor a text/localisation
            // failure below may turn the alert into silence for both channels.
            runCatching { soundPlayer.playNotificationSound() }.onFailure { failure ->
                logger.warn("Turn alert sound failed: ${failure.message}", failure)
            }
        }

        if (!preference.osNotificationEnabled) return

        // The platform is only asked when the channel is wanted: the probe is not free, because
        // desktop installs its tray icon on the first call.
        val osChannel = preference.osChannelState(osNotifications.permissionState())
        if (osChannel != TurnOsChannelState.ACTIVE) {
            logger.debug("OS notification skipped: channel $osChannel for ${trigger.sessionId}")
            return
        }

        val sessionName = sessionRepository.sessions.value.dataOrNull
            ?.firstOrNull { it.id == trigger.sessionId }
            ?.name
        val content = runCatching { textSource.content(trigger, sessionName) }.getOrElse { failure ->
            // Without localized text there is nothing to show, and inventing text would break the
            // "session name and state only" rule; the sound already carried the alert.
            logger.warn(
                "Turn notification text unavailable, skipping the OS notification for ${trigger.sessionId}: " +
                    (failure.message ?: failure::class.simpleName)
            )
            return
        }

        osNotifications.showNotification(
            TurnOsNotificationRequest(
                sessionId = trigger.sessionId,
                title = content.title,
                body = content.body
            )
        )
    }
}
