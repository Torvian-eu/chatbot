package eu.torvian.chatbot.app.service.turnnotification

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Bridge between the runtime notification-permission prompt and the request waiting for its answer.
 *
 * Only an activity can raise a permission prompt, and the answer arrives on that activity's result
 * callback, so the platform service cannot observe it on its own. The hosting activity installs its
 * launcher here and forwards the answer; [request] then suspends until the user has decided, which
 * lets the service report the state the platform is actually in afterwards instead of the state from
 * before the prompt.
 *
 * The prompt is modal, so at most one request waits at a time in practice; a second concurrent
 * request simply receives the same answer.
 */
object AndroidTurnNotificationPermissionBus {

    private val answers = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 1)

    /**
     * Launcher installed by the hosting activity, or `null` while no activity can raise a prompt.
     *
     * Read by [request()] and changed only through [installPromptLauncher] and
     * [withdrawPromptLauncher], so a finishing activity cannot withdraw the launcher of a newer one.
     */
    @Volatile
    var promptLauncher: ((String) -> Unit)? = null
        private set

    /**
     * Offers [launcher] for permission prompts until it is replaced or withdrawn.
     *
     * @param launcher Launcher that raises the runtime prompt for the calling activity.
     */
    fun installPromptLauncher(launcher: (String) -> Unit) {
        promptLauncher = launcher
    }

    /**
     * Withdraws [launcher], keeping a launcher a newer activity installed in the meantime.
     *
     * A newer activity can install its launcher before an older, finishing one is destroyed, so the
     * withdrawal is by identity: clearing unconditionally would silently remove the only way left to
     * raise a prompt.
     *
     * @param launcher Launcher whose owner cannot raise a prompt any more.
     */
    fun withdrawPromptLauncher(launcher: (String) -> Unit) {
        if (promptLauncher === launcher) promptLauncher = null
    }

    /**
     * Launches the prompt for [permission] and suspends until the user answers it.
     *
     * @param permission Runtime permission to ask for.
     * @return `true` when the user granted the permission, `false` when they refused, or `null` when
     *         no hosting activity can raise a prompt.
     */
    suspend fun request(permission: String): Boolean? {
        val launcher = promptLauncher ?: return null
        return coroutineScope {
            // Subscribing before launching is a safety margin rather than a race that can be lost:
            // the dialog cannot be answered before the user sees it.
            val answer = async(start = CoroutineStart.UNDISPATCHED) { answers.first() }
            launcher(permission)
            answer.await()
        }
    }

    /**
     * Delivers the user's answer to the waiting request, if any.
     *
     * An answer arriving with nobody waiting is discarded: the requester reads the permission again
     * on its next call, and the state it already read stays authoritative until then.
     *
     * @param granted Whether the permission was granted.
     */
    fun publishAnswer(granted: Boolean) {
        answers.tryEmit(granted)
    }
}
