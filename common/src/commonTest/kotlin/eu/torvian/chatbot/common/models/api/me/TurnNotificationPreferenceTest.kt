package eu.torvian.chatbot.common.models.api.me

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serialization tests for [TurnNotificationPreference].
 *
 * The preference is stored as JSON under a well-known key, so decoding must stay tolerant of rows
 * written by older clients that omit fields and of keys this client does not know about yet.
 */
class TurnNotificationPreferenceTest {

    /** Lenient codec mirroring the client repository's preference decoding setup. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Verifies the fallback used when the server holds no stored row enables every channel.
     */
    @Test
    fun `default enables the master switch and both channels`() {
        assertEquals(
            TurnNotificationPreference(enabled = true, soundEnabled = true, osNotificationEnabled = true),
            TurnNotificationPreference.DEFAULT
        )
    }

    /**
     * Verifies a partial object decodes the omitted fields to their enabled defaults.
     */
    @Test
    fun `partial object defaults the missing fields to true`() {
        val decoded = json.decodeFromString<TurnNotificationPreference>("""{"enabled":false}""")

        assertEquals(false, decoded.enabled)
        assertTrue(decoded.soundEnabled)
        assertTrue(decoded.osNotificationEnabled)
    }

    /**
     * Verifies unknown fields written by a newer client are ignored instead of failing the decode.
     */
    @Test
    fun `unknown keys are ignored`() {
        val decoded = json.decodeFromString<TurnNotificationPreference>(
            """{"enabled":true,"soundEnabled":false,"osNotificationEnabled":true,"futureField":42}"""
        )

        assertEquals(TurnNotificationPreference(enabled = true, soundEnabled = false, osNotificationEnabled = true), decoded)
    }

    /**
     * Verifies a full round trip preserves every toggle.
     */
    @Test
    fun `round trip preserves every toggle`() {
        val preference = TurnNotificationPreference(enabled = false, soundEnabled = false, osNotificationEnabled = true)

        val encoded = json.encodeToString(TurnNotificationPreference.serializer(), preference)
        val decoded = json.decodeFromString<TurnNotificationPreference>(encoded)

        assertEquals(preference, decoded)
    }
}
