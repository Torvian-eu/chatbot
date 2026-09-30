package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Web [TurnAlertSoundPlayer] playing the bundled WAV through an `HTMLAudioElement`.
 *
 * The resource URI is handed to the element instead of raw bytes because the browser already serves
 * the bundled file, which avoids copying the audio into JS memory on every alert. The element is
 * local to the call, so nothing is retained and playback never loops.
 *
 * @property playSound Starts playback of the given resource URI; returns without waiting, because an
 *           autoplay-refused promise must not delay the turn.
 */
class TurnAlertSoundPlayerWasmJs(
    private val playSound: (String) -> Unit = ::playResourceUri
) : TurnAlertSoundPlayer {

    private companion object {
        /** Classpath path of the bundled alert sound inside the Compose Resources file tree. */
        const val SOUND_RESOURCE_PATH = "files/notification/turn-complete.wav"

        val logger = kmpLogger<TurnAlertSoundPlayerWasmJs>()

        /**
         * Creates a detached audio element for the resource and starts it.
         *
         * @param uri Browser-usable URI of the bundled sound.
         */
        @OptIn(ExperimentalWasmJsInterop::class)
        fun playResourceUri(uri: String) {
            val audio = document.createElement("audio") as HTMLAudioElement
            audio.src = uri
            // The returned promise is deliberately not awaited: a refused autoplay (a hidden tab or a
            // missing prior user gesture) is an expected, silent no-play on the web.
            audio.play()
        }
    }

    override suspend fun playNotificationSound() {
        runCatching { playSound(Res.getUri(SOUND_RESOURCE_PATH)) }
            .onFailure { failure ->
                logger.warn("Failed to play the turn notification sound: ${failure.message}", failure)
            }
    }
}
