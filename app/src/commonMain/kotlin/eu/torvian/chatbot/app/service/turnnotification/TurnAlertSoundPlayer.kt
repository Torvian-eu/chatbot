package eu.torvian.chatbot.app.service.turnnotification

/**
 * Plays the bundled turn-alert sound.
 *
 * The contract is deliberately best effort: an implementation that cannot play (no audio device, no
 * codec, headless session) logs the failure and returns, because alerting must never interfere with
 * the turn that produced the trigger.
 */
interface TurnAlertSoundPlayer {

    /**
     * Plays the bundled notification sound once.
     *
     * Implementations must return without blocking on playback completion; repeated calls may overlap
     * or be ignored when the platform cannot mix them.
     */
    suspend fun playNotificationSound()

    /**
     * Releases the platform resources held open for playback, where the platform holds any.
     *
     * Called when alerting stops, so a channel that will not alert again keeps no audio resources;
     * a player stays usable afterwards, because alerting restarts when the user signs in again.
     * Implementations must not throw: releasing is best effort like playing.
     */
    fun release() = Unit
}
