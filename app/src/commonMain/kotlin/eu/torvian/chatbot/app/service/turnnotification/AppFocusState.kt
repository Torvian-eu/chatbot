package eu.torvian.chatbot.app.service.turnnotification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the app window or browser tab currently has the user's attention.
 *
 * The dispatcher reads the state at trigger time instead of collecting it, which keeps alerting free
 * of extra subscriptions and timers.
 */
interface AppFocusState {

    /** Whether the app is currently focused; `false` while unknown or unattended. */
    val isFocused: StateFlow<Boolean>

    /**
     * Records the latest focus report from whichever platform feeder observes it.
     *
     * Feeds write without arbitration, so the last report wins and no source is authoritative on its
     * own. On Android the composed shell's window-focus feed reports focus while the activity draws
     * and the lifecycle callbacks report the loss of it once drawing stops, so the two agree in the
     * attended and unattended states the dispatcher gates on.
     *
     * @param focused `true` while the app has the user's attention.
     */
    fun setFocused(focused: Boolean)
}

/**
 * Default [AppFocusState] holding the last reported focus in memory.
 *
 * Starts unfocused: a platform that never reports focus must alert rather than stay silent, because
 * staying silent would silently disable the whole feature.
 */
class DefaultAppFocusState : AppFocusState {

    private val mutableFocused = MutableStateFlow(false)
    override val isFocused: StateFlow<Boolean> = mutableFocused.asStateFlow()

    override fun setFocused(focused: Boolean) {
        mutableFocused.value = focused
    }
}
