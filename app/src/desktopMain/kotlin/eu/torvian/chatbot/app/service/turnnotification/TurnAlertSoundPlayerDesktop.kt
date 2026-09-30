package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/**
 * Desktop [TurnAlertSoundPlayer] playing the bundled WAV through the JDK audio system.
 *
 * The decoded bytes are cached after the first read so repeated alerts do not touch the classpath
 * again. Playback happens on [Dispatchers.IO] and returns as soon as the clip starts, so a slow or
 * missing audio device never delays the turn that produced the alert.
 *
 * @property loadSoundBytes Supplier of the raw sound file. Defaults to the bundled Compose Resource;
 *           tests inject invalid or empty bytes to exercise the best-effort contract.
 */
class TurnAlertSoundPlayerDesktop(
    private val loadSoundBytes: suspend () -> ByteArray = { Res.readBytes(SOUND_RESOURCE_PATH) }
) : TurnAlertSoundPlayer {

    companion object {
        /** Classpath path of the bundled alert sound inside the Compose Resources file tree. */
        private const val SOUND_RESOURCE_PATH = "files/notification/turn-complete.wav"

        private val logger = kmpLogger<TurnAlertSoundPlayerDesktop>()
    }

    /** Raw sound bytes read on the first successful load; `null` until then. */
    private var cachedSoundBytes: ByteArray? = null

    override suspend fun playNotificationSound() {
        val soundBytes = cachedSoundBytes ?: runCatching { loadSoundBytes() }
            .onFailure { failure ->
                logger.warn("Failed to read the bundled turn notification sound: ${failure.message}", failure)
            }
            .getOrNull()
        ?: return
        cachedSoundBytes = soundBytes

        withContext(Dispatchers.IO) {
            runCatching { playOnce(soundBytes) }.onFailure { failure ->
                // A missing audio device, an unsupported encoding, or a headless session must not
                // affect the turn; the OS notification channel is attempted regardless.
                logger.warn("Failed to play the turn notification sound: ${failure.message}", failure)
            }
        }
    }

    /**
     * Opens and starts one clip, closing it when playback stops.
     *
     * @param soundBytes Raw audio data in a format the JDK audio system can decode.
     */
    private fun playOnce(soundBytes: ByteArray) {
        AudioSystem.getAudioInputStream(ByteArrayInputStream(soundBytes)).use { stream ->
            val clip = AudioSystem.getClip()
            try {
                // Closing the clip on STOP releases the audio line; without it every alert would keep a
                // line occupied, which exhausts the mixer after a handful of turns.
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) {
                        runCatching { clip.close() }
                    }
                }
                clip.open(stream)
                clip.start()
            } catch (failure: Throwable) {
                // A line that never starts never emits STOP, so it has to be released here or a
                // transient open/start failure leaks a mixer line permanently.
                runCatching { clip.close() }
                throw failure
            }
        }
    }
}
