package eu.torvian.chatbot.app.service.turnnotification

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android [TurnAlertSoundPlayer] playing the bundled WAV through [SoundPool].
 *
 * The sound is materialized once into the cache directory, because `SoundPool` loads from a file or
 * a descriptor, and is then preloaded so later alerts start instantly and can overlap. The pool is
 * held only while alerting lasts and is given back by [release], after which the next alert builds a
 * new one. Every failure is logged and swallowed: a missing asset or audio device must not affect the
 * turn.
 *
 * @property context Context providing the cache directory and the audio services.
 */
class TurnAlertSoundPlayerAndroid(
    private val context: Context
) : TurnAlertSoundPlayer {

    private companion object {
        /** Classpath path of the bundled alert sound inside the Compose Resources file tree. */
        const val SOUND_RESOURCE_PATH = "files/notification/turn-complete.wav"

        /** File name the bundled sound is cached under. */
        const val SOUND_FILE_NAME = "turn-complete.wav"

        val logger = kmpLogger<TurnAlertSoundPlayerAndroid>()
    }

    /** Pool created on first use, because constructing it costs audio resources. */
    private var soundPool: SoundPool? = null

    /** Sample id returned by [SoundPool.load], or `0` while the sample has not been requested. */
    @Volatile
    private var sampleId: Int = 0

    /** Whether the sample finished loading and may be played. */
    @Volatile
    private var sampleReady: Boolean = false

    /** A play request that arrived while the sample was still loading. */
    @Volatile
    private var playPending: Boolean = false

    override suspend fun playNotificationSound() {
        val pool = runCatching { ensureSoundPool() }.getOrElse { failure ->
            logger.warn("Failed to create the sound pool: ${failure.message}", failure)
            return
        }
        runCatching { play(pool) }.onFailure { failure ->
            logger.warn("Failed to play the turn notification sound: ${failure.message}", failure)
        }
    }

    /**
     * Loads the sample on first use and plays it, or defers the play until loading finishes.
     *
     * @param pool Pool the sound is played through.
     */
    private suspend fun play(pool: SoundPool) {
        if (sampleId == 0) {
            val soundFile = ensureSoundFile() ?: return
            sampleId = pool.load(soundFile.absolutePath, 1)
        }
        if (sampleReady) {
            pool.play(sampleId, 1f, 1f, 1, 0, 1f)
        } else {
            // The very first alert can arrive before the sample finished loading; remembering the
            // request keeps that alert audible instead of dropping it.
            playPending = true
        }
    }

    /**
     * Releases the pool and forgets everything loaded through it.
     *
     * The player stays usable: the next alert builds a new pool and loads the sample again.
     */
    override fun release() {
        val pool = soundPool ?: return
        soundPool = null
        // Cleared together with the pool so no stale load callback can mark a released sample as ready.
        sampleId = 0
        sampleReady = false
        playPending = false
        runCatching { pool.release() }.onFailure { failure ->
            logger.warn("Failed to release the sound pool: ${failure.message}", failure)
        }
    }

    /**
     * Creates the pool once, with attributes suitable for notification feedback.
     *
     * @return The shared pool instance.
     */
    private fun ensureSoundPool(): SoundPool {
        soundPool?.let { return it }
        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        // Loading completion is a pool callback: the builder cannot register one.
        pool.setOnLoadCompleteListener { _, loadedSampleId, status ->
            onSampleLoaded(loadedSampleId, status, pool)
        }
        soundPool = pool
        return pool
    }

    /**
     * Completes a deferred play once the sample is available, or re-arms a failed load.
     *
     * @param loadedSampleId Sample the pool finished loading, which the caller compares against the
     *           sample this player tracks so a callback left over from a failed attempt cannot mark a
     *           later attempt as ready.
     * @param status Load status reported by the pool; `0` means the sample is ready to play.
     * @param pool Pool that finished loading the sample.
     */
    private fun onSampleLoaded(loadedSampleId: Int, status: Int, pool: SoundPool) {
        if (loadedSampleId != sampleId) return
        if (status != 0) {
            // A load failure must stay recoverable: forgetting the sample id lets the next alert load
            // it again, rather than leaving the channel silent for the rest of the process.
            logger.warn("The turn notification sound failed to load (status $status); it will be retried")
            sampleId = 0
            playPending = false
            return
        }
        sampleReady = true
        if (playPending) {
            playPending = false
            pool.play(sampleId, 1f, 1f, 1, 0, 1f)
        }
    }

    /**
     * Copies the bundled sound into the cache directory once.
     *
     * @return The cached file, or `null` when the bundled resource could not be read or written.
     */
    private suspend fun ensureSoundFile(): File? {
        val soundFile = File(context.cacheDir, SOUND_FILE_NAME)
        if (soundFile.exists() && soundFile.length() > 0) return soundFile
        return runCatching {
            val bytes = Res.readBytes(SOUND_RESOURCE_PATH)
            withContext(Dispatchers.IO) { soundFile.writeBytes(bytes) }
            soundFile
        }.onFailure { failure ->
            logger.warn("Failed to prepare the turn notification sound: ${failure.message}", failure)
        }.getOrNull()
    }
}
