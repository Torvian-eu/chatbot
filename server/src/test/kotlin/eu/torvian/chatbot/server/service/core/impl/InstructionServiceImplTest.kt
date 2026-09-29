package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.service.core.error.instruction.CreateInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.DeleteInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.UpdateInstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [InstructionServiceImpl].
 *
 * Verifies the owner-scoped library contract: a created row is owned by the caller, an update
 * rewrites exactly the content of the owned row its request names, a foreign or missing row collapses
 * to the same not-found error, listing reports the owner's rows, and the generated-message kind never
 * stores text. Validation must reject illegal content before any write, so a shared row that linked
 * roles read can never hold it. Every reported row names the roles that link it, resolved by one
 * batched read per call. Deletion is refused while any role links the row and succeeds once no link
 * remains.
 */
class InstructionServiceImplTest {

    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao

    private lateinit var service: InstructionServiceImpl

    private val userId = 7L

    /** Codec used to read the stored `custom` text back, mirroring the runtime instance. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Pass-through transaction scope: the service's atomicity wrapper is not under test here. */
    private val transactionScope = object : TransactionScope {
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override suspend fun <T> execute(block: suspend () -> T): T = block()
    }

    @BeforeEach
    fun setUp() {
        instructionDao = mockk()
        instructionOwnershipDao = mockk()
        agentRoleInstructionDao = mockk()

        service = InstructionServiceImpl(
            instructionDao = instructionDao,
            instructionOwnershipDao = instructionOwnershipDao,
            agentRoleInstructionDao = agentRoleInstructionDao,
            transactionScope = transactionScope,
            json = json
        )
    }

    /**
     * Builds a stored instruction row as the DAO would return it.
     *
     * @param id The row id.
     * @param type The instruction kind key.
     * @param name The instruction label.
     * @param message The stored text, or null for a generated-message kind.
     * @param custom Raw JSON text of type-specific fields, or null.
     * @return The entity fixture.
     */
    private fun row(
        id: Long,
        type: String = AgentInstructionTypes.CUSTOM,
        name: String = "Tone",
        message: String? = "Be concise",
        custom: String? = null
    ) = InstructionEntity(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = custom,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    )

    @Test
    fun `getAllInstructionsForUser reports the owner's rows with their stored custom data`() = runTest {
        coEvery { instructionDao.getAllInstructionsForUser(userId) } returns listOf(
            row(id = 1L, name = "Tone"),
            row(
                id = 2L,
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Swift",
                message = "Write Swift",
                custom = """{"modelId":5}"""
            ),
            row(id = 3L, type = AgentInstructionTypes.SPAWNABLE_AGENTS, name = "Available agents", message = null)
        )
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(1L, 2L, 3L)) } returns
            mapOf(1L to setOf(4L, 9L), 2L to setOf(4L))

        val library = service.getAllInstructionsForUser(userId)

        assertEquals(listOf(1L, 2L, 3L), library.map { it.id })
        assertEquals(buildJsonObject { put("modelId", 5L) }, library[1].custom)
        // The generated-message kind stores no text and has no role context to generate one from, so
        // its library row reports an empty message.
        assertEquals("", library[2].message)
        // The linking roles let a consumer tell shared content from role-specific content.
        assertEquals(setOf(4L, 9L), library[0].linkedRoleIds)
        assertEquals(setOf(4L), library[1].linkedRoleIds)
        assertEquals(emptySet(), library[2].linkedRoleIds)
    }

    @Test
    fun `getAllInstructionsForUser resolves every row's linking roles with one batched read`() = runTest {
        coEvery { instructionDao.getAllInstructionsForUser(userId) } returns
            listOf(row(id = 1L), row(id = 2L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } returns emptyMap()

        service.getAllInstructionsForUser(userId)

        // A library of any size costs exactly one reverse-link query.
        coVerify(exactly = 1) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(1L, 2L)) }
    }

    @Test
    fun `getAllInstructionsForUser degrades unparseable stored custom data to null`() = runTest {
        coEvery { instructionDao.getAllInstructionsForUser(userId) } returns
            listOf(row(id = 1L, custom = "not json"))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(1L)) } returns emptyMap()

        val library = service.getAllInstructionsForUser(userId)

        assertNull(library.single().custom)
    }

    @Test
    fun `getAllInstructionsForUser reports an empty library as an empty list`() = runTest {
        coEvery { instructionDao.getAllInstructionsForUser(userId) } returns emptyList()

        assertTrue(service.getAllInstructionsForUser(userId).isEmpty())
        // Nothing to resolve, so an empty library costs no reverse-link read at all.
        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
    }

    @Test
    fun `createInstruction stores the authored content and makes the caller its owner`() = runTest {
        coEvery {
            instructionDao.insertInstruction(type = "custom", name = "Tone", message = "Be concise", custom = null)
        } returns row(id = 100L)
        coEvery { instructionOwnershipDao.setOwner(100L, userId) } returns Unit.right()

        val created = assertNotNull(
            service.createInstruction(
                userId,
                CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Be concise")
            ).getOrNull()
        )

        assertEquals(100L, created.id)
        assertEquals(AgentInstructionTypes.CUSTOM, created.type)
        assertEquals("Tone", created.name)
        assertEquals("Be concise", created.message)
        assertNull(created.custom)
        // A brand-new row is linked to nothing yet.
        assertEquals(emptySet(), created.linkedRoleIds)
        coVerify(exactly = 1) { instructionOwnershipDao.setOwner(100L, userId) }
        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
    }

    @Test
    fun `createInstruction stores the model target of a model specific instruction as JSON text`() = runTest {
        val custom = buildJsonObject { put("modelId", 5L) }
        coEvery {
            instructionDao.insertInstruction(
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Swift",
                message = "Write idiomatic Swift",
                custom = """{"modelId":5}"""
            )
        } returns row(
            id = 101L,
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            name = "Swift",
            message = "Write idiomatic Swift",
            custom = """{"modelId":5}"""
        )
        coEvery { instructionOwnershipDao.setOwner(101L, userId) } returns Unit.right()

        val created = assertNotNull(
            service.createInstruction(
                userId,
                CreateInstructionRequest(
                    type = AgentInstructionTypes.MODEL_SPECIFIC,
                    name = "Swift",
                    message = "Write idiomatic Swift",
                    custom = custom
                )
            ).getOrNull()
        )

        assertEquals(custom, created.custom)
    }

    @Test
    fun `createInstruction stores no message for the generated-message kind`() = runTest {
        coEvery {
            instructionDao.insertInstruction(
                type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                name = "Available agents",
                message = null,
                custom = null
            )
        } returns row(
            id = 102L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents",
            message = null
        )
        coEvery { instructionOwnershipDao.setOwner(102L, userId) } returns Unit.right()

        val created = assertNotNull(
            service.createInstruction(
                userId,
                CreateInstructionRequest(
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    name = "Available agents"
                )
            ).getOrNull()
        )

        // The row stores no text, so the created library row reports an empty message rather than a
        // null that would break the non-null wire contract.
        assertEquals("", created.message)
        assertEquals(emptySet(), created.linkedRoleIds)
    }

    @Test
    fun `createInstruction rejects content that is not a legal instruction without writing anything`() = runTest {
        val invalidRequests = listOf(
            CreateInstructionRequest(type = "nonsense", name = "Tone", message = "Text"),
            CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "   ", message = "Text"),
            CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "n".repeat(256), message = "Text"),
            CreateInstructionRequest(type = AgentInstructionTypes.MODEL_SPECIFIC, name = "Swift", message = "Text"),
            CreateInstructionRequest(
                type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                name = "Available agents",
                message = "supplied text"
            )
        )

        invalidRequests.forEach { request ->
            val result = service.createInstruction(userId, request)

            assertIs<CreateInstructionError.ValidationFailed>(result.leftOrNull())
        }
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { instructionOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `createInstruction reports a failed ownership write instead of returning an unowned row`() = runTest {
        coEvery {
            instructionDao.insertInstruction(type = "custom", name = "Tone", message = "Be concise", custom = null)
        } returns row(id = 103L)
        coEvery { instructionOwnershipDao.setOwner(103L, userId) } returns
            SetOwnerError.ForeignKeyViolation("103", userId).left()

        val result = service.createInstruction(
            userId,
            CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Be concise")
        )

        assertIs<CreateInstructionError.OwnerInsertFailed>(result.leftOrNull())
    }

    @Test
    fun `getInstructionById reports the stored row with the roles that link it`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(3L, 8L))

        val read = assertNotNull(service.getInstructionById(userId, 5L).getOrNull())

        assertEquals(5L, read.id)
        assertEquals("Tone", read.name)
        assertEquals("Be concise", read.message)
        // The usage projection travels with the row: a consumer learns which roles use it inline.
        assertEquals(setOf(3L, 8L), read.linkedRoleIds)
    }

    @Test
    fun `getInstructionById reads the stored custom text of a model specific row`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns
            listOf(row(id = 5L, type = AgentInstructionTypes.MODEL_SPECIFIC, custom = """{"modelId":5}"""))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()

        val read = assertNotNull(service.getInstructionById(userId, 5L).getOrNull())

        assertEquals(buildJsonObject { put("modelId", 5L) }, read.custom)
        assertEquals(emptySet(), read.linkedRoleIds)
    }

    @Test
    fun `getInstructionById collapses a missing or foreign row into not-found`() = runTest {
        // The owner-scoped read omits both a missing id and one owned by somebody else.
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.getInstructionById(userId, 5L)

        assertEquals(GetInstructionError.NotFound(5L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
    }

    @Test
    fun `deleteInstruction deletes an owned row no role links`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()
        coEvery { instructionDao.deleteInstruction(5L) } returns Unit.right()

        val result = service.deleteInstruction(userId, 5L)

        assertEquals(Unit.right(), result)
        // The row itself is removed; only an unlinked row reaches the delete.
        coVerify(exactly = 1) { instructionDao.deleteInstruction(5L) }
    }

    @Test
    fun `deleteInstruction refuses a row that any agent role still links`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(9L, 3L))

        val result = service.deleteInstruction(userId, 5L)

        // The refusal names the blocking roles ascending, so the caller knows what to unlink first.
        assertEquals(DeleteInstructionError.LinkedToRoles(5L, listOf(3L, 9L)), result.leftOrNull())
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
    }

    @Test
    fun `deleteInstruction reports a storage refusal as the linked-roles error`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        // The check sees no link, so the delete runs and storage refuses it; the second read is the
        // one that resolves the roles the refusal is reported with.
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returnsMany
            listOf(emptyMap(), mapOf(5L to setOf(3L)))
        coEvery { instructionDao.deleteInstruction(5L) } returns
            InstructionError.ForeignKeyViolation("FOREIGN KEY constraint failed").left()

        val result = service.deleteInstruction(userId, 5L)

        assertEquals(DeleteInstructionError.LinkedToRoles(5L, listOf(3L)), result.leftOrNull())
    }

    @Test
    fun `deleteInstruction checks ownership before the links so a foreign row never leaks its roles`() = runTest {
        // The owner-scoped read omits a row owned by somebody else, even a linked one.
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.deleteInstruction(userId, 5L)

        assertEquals(DeleteInstructionError.NotFound(5L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
    }

    @Test
    fun `deleteInstruction refuses a foreign row without deleting anything`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.deleteInstruction(userId, 5L)

        assertEquals(DeleteInstructionError.NotFound(5L), result.leftOrNull())
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
    }

    @Test
    fun `deleteInstruction maps a row that vanished between the check and the delete to not-found`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()
        coEvery { instructionDao.deleteInstruction(5L) } returns InstructionError.NotFound(5L).left()

        val result = service.deleteInstruction(userId, 5L)

        assertEquals(DeleteInstructionError.NotFound(5L), result.leftOrNull())
    }

    @Test
    fun `updateInstruction rewrites every writable field of an owned row`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { instructionDao.updateInstruction(any()) } returns row(
            id = 5L,
            name = "Style",
            message = "Be formal"
        ).right()
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()

        val updated = assertNotNull(
            service.updateInstruction(
                userId,
                UpdateInstructionRequest(
                    id = 5L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Style",
                    message = "Be formal"
                )
            ).getOrNull()
        )

        assertEquals("Style", updated.name)
        assertEquals("Be formal", updated.message)
        coVerify(exactly = 1) {
            instructionDao.updateInstruction(
                match { rewritten ->
                    rewritten.id == 5L &&
                        rewritten.type == AgentInstructionTypes.CUSTOM &&
                        rewritten.name == "Style" &&
                        rewritten.message == "Be formal" &&
                        rewritten.custom == null
                }
            )
        }
    }

    @Test
    fun `updateInstruction reports the roles that link the rewritten row`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { instructionDao.updateInstruction(any()) } returns row(id = 5L, message = "Be formal").right()
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(3L, 8L))

        val updated = assertNotNull(
            service.updateInstruction(
                userId,
                UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Be formal")
            ).getOrNull()
        )

        // The content write reaches every linking role, so the response names them for the caller.
        assertEquals(setOf(3L, 8L), updated.linkedRoleIds)
    }

    @Test
    fun `updateInstruction refuses a row the caller does not own`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns emptyList()

        val result = service.updateInstruction(
            userId,
            UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Text")
        )

        assertEquals(UpdateInstructionError.NotFound(5L), result.leftOrNull())
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
    }

    @Test
    fun `updateInstruction rejects content that is not a legal instruction without writing anything`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))

        val result = service.updateInstruction(
            userId,
            UpdateInstructionRequest(
                id = 5L,
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Swift",
                message = "Write Swift"
            )
        )

        assertIs<UpdateInstructionError.ValidationFailed>(result.leftOrNull())
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }

    @Test
    fun `updateInstruction drops the message of the generated-message kind`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(8L)) } returns
            listOf(row(id = 8L, type = AgentInstructionTypes.SPAWNABLE_AGENTS, message = null))
        coEvery { instructionDao.updateInstruction(any()) } returns row(
            id = 8L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents",
            message = null
        ).right()
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(8L)) } returns emptyMap()

        val updated = assertNotNull(
            service.updateInstruction(
                userId,
                UpdateInstructionRequest(
                    id = 8L,
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    name = "Available agents"
                )
            ).getOrNull()
        )

        // Nothing is stored for the kind, so the reported row carries an empty message.
        assertEquals("", updated.message)
        coVerify(exactly = 1) { instructionDao.updateInstruction(match { it.message == null }) }
    }

    @Test
    fun `updateInstruction maps a vanished row to the not-found error`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()
        coEvery { instructionDao.updateInstruction(any()) } returns InstructionError.NotFound(5L).left()

        val result = service.updateInstruction(
            userId,
            UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Text")
        )

        assertEquals(UpdateInstructionError.NotFound(5L), result.leftOrNull())
    }

    /**
     * Verifies that the instruction endpoint refuses a kind change that would break a linking role's list.
     */
    @Test
    fun `updateInstruction refuses a kind change that would invalidate a linking role's list`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(3L))
        // Role 3 already holds a 'main' row, so retyping row 5 to 'main' would give it two.
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(3L)) } returns
            mapOf(3L to listOf(InstructionRef(5L, 0), InstructionRef(7L, 1)))
        coEvery { instructionDao.getInstructionsByIds(listOf(7L)) } returns
            listOf(row(id = 7L, type = AgentInstructionTypes.MAIN, name = "Main"))

        val result = service.updateInstruction(
            userId,
            UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.MAIN, name = "Main", message = "Text")
        )

        // The endpoint is not role-scoped, so it judges every linking role's resulting list itself and
        // refuses the write, naming the roles that would become invalid.
        val error = assertIs<UpdateInstructionError.LinkedRoleInstructionListInvalid>(result.leftOrNull())
        assertEquals(5L, error.instructionId)
        assertEquals(listOf(3L), error.linkedRoleIds)
        assertEquals("At most one 'main' instruction is allowed", error.reason)
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }

    /**
     * Verifies that a kind change is accepted when every linking role's resulting list stays valid.
     */
    @Test
    fun `updateInstruction allows a kind change when every linking role's list stays valid`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns
            mapOf(5L to setOf(3L))
        // Role 3 links only row 5, so its resulting list is a single 'main' row.
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(3L)) } returns
            mapOf(3L to listOf(InstructionRef(5L, 0)))
        coEvery { instructionDao.updateInstruction(any()) } returns
            row(id = 5L, type = AgentInstructionTypes.MAIN, name = "Main", message = "Text").right()

        val updated = assertNotNull(
            service.updateInstruction(
                userId,
                UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.MAIN, name = "Main", message = "Text")
            ).getOrNull()
        )

        assertEquals(AgentInstructionTypes.MAIN, updated.type)
        coVerify(exactly = 1) { instructionDao.updateInstruction(match { it.type == AgentInstructionTypes.MAIN }) }
    }

    /**
     * Verifies that an unlinked row may still change kind through the instruction endpoint.
     */
    @Test
    fun `updateInstruction allows a kind change when no role links the row`() = runTest {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(5L)) } returns listOf(row(id = 5L))
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(5L)) } returns emptyMap()
        coEvery { instructionDao.updateInstruction(any()) } returns
            row(id = 5L, type = AgentInstructionTypes.MAIN, name = "Main", message = "Text").right()

        val updated = assertNotNull(
            service.updateInstruction(
                userId,
                UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.MAIN, name = "Main", message = "Text")
            ).getOrNull()
        )

        assertEquals(AgentInstructionTypes.MAIN, updated.type)
        coVerify(exactly = 1) { instructionDao.updateInstruction(match { it.type == AgentInstructionTypes.MAIN }) }
    }
}
