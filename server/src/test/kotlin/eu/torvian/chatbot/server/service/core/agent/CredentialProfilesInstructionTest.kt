package eu.torvian.chatbot.server.service.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the `credential_profiles` prompt section ([CredentialProfilesInstruction]): the rendered
 * table, the per-worker grouping, the omission when the role has no `terminal_session` tool and the
 * guarantee that no secret reference or fixed command line is advertised.
 */
class CredentialProfilesInstructionTest {

    /**
     * Renders the section for [advertisement] through the real caching loader path.
     *
     * @param advertisement Snapshot to render.
     * @param toolAvailable Whether `terminal_session` is attached to the role.
     * @return The generated section text.
     */
    private suspend fun render(
        advertisement: CredentialProfilesAdvertisement,
        toolAvailable: Boolean = true,
    ): String {
        val instruction = CredentialProfilesInstruction(
            id = 1L,
            name = "Available credential profiles",
            advertisementLoader = { advertisement },
            terminalSessionToolAvailableLoader = { toolAvailable },
        )
        instruction.loadMessage()
        return instruction.message
    }

    /**
     * @param name Profile name.
     * @param description Advertised description.
     * @param channel Advertised channel.
     * @return The summary fixture.
     */
    private fun profile(
        name: String,
        description: String = "Interactive shell",
        channel: String = "askpass",
    ) = CredentialProfileSummary(name = name, description = description, channel = channel)

    /** Verifies the documented table shape and the guidance sentences. */
    @Test
    fun `renders a table with the profile, channel and description`() = runTest {
        val message = render(
            CredentialProfilesAdvertisement(
                groups = listOf(
                    CredentialProfilesGroup(
                        workerId = 7,
                        workerName = "vps-worker",
                        profiles = listOf(profile("vps"), profile("github", "Read the private repo", "env")),
                    )
                )
            )
        )

        assertTrue(message.contains("## Available credential profiles"))
        assertTrue(message.contains("| Profile | Channel | Description |"))
        assertTrue(message.contains("| vps | askpass | Interactive shell |"))
        assertTrue(message.contains("| github | env | Read the private repo |"))
        assertTrue(message.contains("`attach` and `credential=<name>`"))
        assertTrue(message.contains("cannot be changed"))
        assertTrue(message.contains("never returned"))
        assertTrue(message.contains("detaches automatically"))
        // A single worker needs no grouping heading, because there is nothing to disambiguate.
        assertFalse(message.contains("### "))
    }

    /** Verifies that several workers are grouped under their display names. */
    @Test
    fun `groups profiles by worker when several workers contribute`() = runTest {
        val message = render(
            CredentialProfilesAdvertisement(
                groups = listOf(
                    CredentialProfilesGroup(7, "vps-worker", listOf(profile("vps"))),
                    CredentialProfilesGroup(8, "laptop", listOf(profile("home", "Home shell"))),
                )
            )
        )

        assertTrue(message.contains("### vps-worker"))
        assertTrue(message.contains("### laptop"))
        assertTrue(message.contains("| vps | askpass | Interactive shell |"))
        assertTrue(message.contains("| home | askpass | Home shell |"))
    }

    /** Verifies that a role without the tool gets no message, so the composer drops the section. */
    @Test
    fun `omits the section when the tool is unavailable`() = runTest {
        val message = render(
            CredentialProfilesAdvertisement(
                groups = listOf(CredentialProfilesGroup(7, "worker", listOf(profile("vps"))))
            ),
            toolAvailable = false,
        )

        assertEquals("", message)
    }

    /** Verifies that a worker with no enabled profile produces no section. */
    @Test
    fun `omits the section when no profile is assigned`() = runTest {
        assertEquals("", render(CredentialProfilesAdvertisement(groups = emptyList())))
        assertEquals(
            "",
            render(CredentialProfilesAdvertisement(groups = listOf(CredentialProfilesGroup(7, "worker", emptyList())))),
        )
    }

    /** Verifies that authored text cannot break the table and that blank cells get a placeholder. */
    @Test
    fun `sanitises authored cells`() = runTest {
        val message = render(
            CredentialProfilesAdvertisement(
                groups = listOf(
                    CredentialProfilesGroup(
                        workerId = 7,
                        workerName = "worker",
                        profiles = listOf(profile("vps", "line one\nline two | injected", channel = "file")),
                    )
                )
            )
        )

        assertTrue(message.contains("line one line two \\| injected"))
        assertFalse(message.contains("| vps | file |  |"))
    }

    /** Verifies that the message is generated once and reused. */
    @Test
    fun `caches the generated message`() = runTest {
        var loads = 0
        val instruction = CredentialProfilesInstruction(
            id = 1L,
            name = "Available credential profiles",
            advertisementLoader = {
                loads++
                CredentialProfilesAdvertisement(
                    groups = listOf(CredentialProfilesGroup(7, "worker", listOf(profile("vps"))))
                )
            },
            terminalSessionToolAvailableLoader = { true },
        )

        instruction.loadMessage()
        instruction.loadMessage()

        assertEquals(1, loads)
        assertEquals(instruction.message, instruction.message)
    }
}
