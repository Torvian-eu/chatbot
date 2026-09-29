package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.InstructionApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [DefaultInstructionRepository]: the library stream must reflect every load and successful
 * write (the role form's picker reads it), authoring calls must pass the written row through unchanged,
 * and an API failure must surface as a [RepositoryError] but must not invent a library entry.
 */
class DefaultInstructionRepositoryTest {

    /** Records the calls the repository makes and replays canned API results. */
    private class StubInstructionApi(
        private val listResult: Either<ApiResourceError, List<AgentInstructionDto>> =
            emptyList<AgentInstructionDto>().right(),
        private val createResult: Either<ApiResourceError, AgentInstructionDto>,
        private val updateResult: Either<ApiResourceError, AgentInstructionDto>,
        private val getResult: Either<ApiResourceError, AgentInstructionDto>,
        private val deleteResult: Either<ApiResourceError, Unit> = Unit.right(),
        private val assignResult: Either<ApiResourceError, AgentRoleDto> = placeholderRole.right(),
        private val unassignResult: Either<ApiResourceError, AgentRoleDto> = placeholderRole.right()
    ) : InstructionApi {
        companion object {
            /** The role a link call answers with when the test does not care about its content. */
            private val placeholderRole = AgentRoleDto(
                id = 1L,
                name = "role",
                modelId = null,
                modelSettingsId = null
            )
        }
        var listCalls: Int = 0
        var createdRequest: CreateInstructionRequest? = null
        var updatedRequest: UpdateInstructionRequest? = null
        var deletedInstructionId: Long? = null
        var assignedPair: Pair<Long, Long>? = null
        var unassignedPair: Pair<Long, Long>? = null

        override suspend fun listInstructions(): Either<ApiResourceError, List<AgentInstructionDto>> {
            listCalls++
            return listResult
        }

        override suspend fun getInstruction(instructionId: Long): Either<ApiResourceError, AgentInstructionDto> =
            getResult

        override suspend fun createInstruction(
            request: CreateInstructionRequest
        ): Either<ApiResourceError, AgentInstructionDto> {
            createdRequest = request
            return createResult
        }

        override suspend fun updateInstruction(
            request: UpdateInstructionRequest
        ): Either<ApiResourceError, AgentInstructionDto> {
            updatedRequest = request
            return updateResult
        }

        override suspend fun deleteInstruction(instructionId: Long): Either<ApiResourceError, Unit> {
            deletedInstructionId = instructionId
            return deleteResult
        }

        override suspend fun assignInstruction(
            roleId: Long,
            instructionId: Long
        ): Either<ApiResourceError, AgentRoleDto> {
            assignedPair = roleId to instructionId
            return assignResult
        }

        override suspend fun unassignInstruction(
            roleId: Long,
            instructionId: Long
        ): Either<ApiResourceError, AgentRoleDto> {
            unassignedPair = roleId to instructionId
            return unassignResult
        }
    }

    private fun instruction(
        id: Long,
        name: String,
        message: String = "Be concise",
        type: String = AgentInstructionTypes.CUSTOM,
        linkedRoleIds: Set<Long> = emptySet()
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        linkedRoleIds = linkedRoleIds
    )

    /**
     * Builds a stub API whose unused directions succeed with a placeholder row.
     *
     * @param list The result of `listInstructions`.
     * @param create The result of `createInstruction`.
     * @param update The result of `updateInstruction`.
     * @param get The result of `getInstruction`.
     * @return The stub API.
     */
    private fun stubApi(
        list: Either<ApiResourceError, List<AgentInstructionDto>> =
            emptyList<AgentInstructionDto>().right(),
        create: Either<ApiResourceError, AgentInstructionDto> = instruction(1L, "Placeholder").right(),
        update: Either<ApiResourceError, AgentInstructionDto> = instruction(1L, "Placeholder").right(),
        get: Either<ApiResourceError, AgentInstructionDto> = instruction(1L, "Placeholder").right()
    ) = StubInstructionApi(listResult = list, createResult = create, updateResult = update, getResult = get)

    private val failure = ApiResourceError.ServerError(
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid instruction")
    )

    @Test
    fun `loadInstructions publishes the library in name-ascending order`() = runTest {
        val api = stubApi(
            list = listOf(
                instruction(3L, "Tone", linkedRoleIds = setOf(1L, 2L)),
                instruction(1L, "Available agents", message = ""),
                instruction(2L, "Code style")
            ).right()
        )
        val repository = DefaultInstructionRepository(api)

        val result = repository.loadInstructions()

        assertEquals(Unit.right(), result)
        assertEquals(listOf(1L, 2L, 3L), repository.instructions.value.dataOrNull?.map { it.id })
        // The rows keep the linking roles the server reported, which is what marks shared content.
        assertEquals(
            setOf(1L, 2L),
            repository.instructions.value.dataOrNull?.single { it.id == 3L }?.linkedRoleIds
        )
        assertEquals(1, api.listCalls)
    }

    @Test
    fun `loadInstructions reports a failure through the result and the stream`() = runTest {
        val repository = DefaultInstructionRepository(stubApi(list = Either.Left(failure)))

        val result = repository.loadInstructions()

        val error = assertIs<RepositoryError.DataFetchError>(result.leftOrNull())
        assertEquals(failure, error.apiResourceError)
        assertEquals("Failed to load instructions", error.contextMessage)
        val stateError = assertIs<DataState.Error<RepositoryError>>(repository.instructions.value)
        assertEquals(failure, assertIs<RepositoryError.DataFetchError>(stateError.error).apiResourceError)
    }

    @Test
    fun `createInstruction returns the created row, forwards the request and appends it`() = runTest {
        val created = instruction(10L, "Tone")
        val api = stubApi(
            list = listOf(instruction(1L, "Code style")).right(),
            create = created.right()
        )
        val repository = DefaultInstructionRepository(api)
        val request = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )
        repository.loadInstructions()

        val result = repository.createInstruction(request)

        assertEquals(created, result.getOrNull())
        assertEquals(request, api.createdRequest)
        // The new row is linkable straight away, so the picker can offer it without a reload.
        assertEquals(listOf(1L, 10L), repository.instructions.value.dataOrNull?.map { it.id })
    }

    @Test
    fun `createInstruction wraps a failure in the operation context and leaves the library alone`() = runTest {
        val repository = DefaultInstructionRepository(stubApi(create = Either.Left(failure)))

        val result = repository.createInstruction(
            CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Text")
        )

        val error = assertIs<RepositoryError.DataFetchError>(result.leftOrNull())
        assertEquals(failure, error.apiResourceError)
        assertEquals("Failed to create instruction", error.contextMessage)
        assertNull(repository.instructions.value.dataOrNull?.find { it.name == "Tone" })
    }

    @Test
    fun `updateInstruction returns the updated row, forwards the request and replaces the entry`() = runTest {
        val updated = instruction(5L, "Style", message = "Be formal")
        val api = stubApi(
            list = listOf(instruction(1L, "Code style"), instruction(5L, "Tone")).right(),
            update = updated.right()
        )
        val repository = DefaultInstructionRepository(api)
        val request = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Style",
            message = "Be formal"
        )
        repository.loadInstructions()

        val result = repository.updateInstruction(request)

        assertEquals(updated, result.getOrNull())
        assertEquals(request, api.updatedRequest)
        // The rewritten row keeps its identity and is re-sorted under its new label.
        assertEquals(listOf(1L, 5L), repository.instructions.value.dataOrNull?.map { it.id })
    }

    @Test
    fun `updateInstruction wraps a failure in the operation context`() = runTest {
        val repository = DefaultInstructionRepository(stubApi(update = Either.Left(failure)))

        val result = repository.updateInstruction(
            UpdateInstructionRequest(id = 5L, type = AgentInstructionTypes.CUSTOM, name = "Style", message = "Text")
        )

        val error = assertIs<RepositoryError.DataFetchError>(result.leftOrNull())
        assertEquals(failure, error.apiResourceError)
        assertEquals("Failed to update instruction ID: 5", error.contextMessage)
    }

    @Test
    fun `deleteInstruction removes the row from the stream and forwards the id`() = runTest {
        val api = stubApi(list = listOf(instruction(1L, "Code style"), instruction(5L, "Tone")).right())
        val repository = DefaultInstructionRepository(api)
        repository.loadInstructions()

        val result = repository.deleteInstruction(5L)

        assertEquals(Unit.right(), result)
        assertEquals(5L, api.deletedInstructionId)
        // The deleted row can no longer be offered as a link target or as an unassigned entry.
        assertEquals(listOf(1L), repository.instructions.value.dataOrNull?.map { it.id })
    }

    @Test
    fun `deleteInstruction wraps a failure in the operation context and keeps the row`() = runTest {
        val api = StubInstructionApi(
            listResult = listOf(instruction(5L, "Tone")).right(),
            createResult = instruction(1L, "Placeholder").right(),
            updateResult = instruction(1L, "Placeholder").right(),
            getResult = instruction(1L, "Placeholder").right(),
            deleteResult = Either.Left(failure)
        )
        val repository = DefaultInstructionRepository(api)
        repository.loadInstructions()

        val result = repository.deleteInstruction(5L)

        val error = assertIs<RepositoryError.DataFetchError>(result.leftOrNull())
        assertEquals(failure, error.apiResourceError)
        assertEquals("Failed to delete instruction ID: 5", error.contextMessage)
        assertEquals(listOf(5L), repository.instructions.value.dataOrNull?.map { it.id })
    }

    @Test
    fun `a write before any load starts the library from the written row`() = runTest {
        val created = instruction(10L, "Tone")
        val repository = DefaultInstructionRepository(stubApi(create = created.right()))

        assertEquals(DataState.Idle, repository.instructions.value)
        repository.createInstruction(
            CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Text")
        )

        assertEquals(listOf(created), repository.instructions.value.dataOrNull)
    }
}
