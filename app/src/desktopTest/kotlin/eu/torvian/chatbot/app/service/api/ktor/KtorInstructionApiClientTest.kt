package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.InstructionApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.InstructionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [KtorInstructionApiClient] covering the instruction library endpoints and the mapping of
 * server failures to [ApiResourceError].
 */
class KtorInstructionApiClientTest {

    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): InstructionApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorInstructionApiClient(httpClient)
    }

    private fun instruction(
        id: Long,
        name: String,
        type: String = AgentInstructionTypes.CUSTOM,
        message: String = "Be concise"
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = null
    )

    @Test
    fun `createInstruction - success`() = runTest {
        val request = CreateInstructionRequest(
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            name = "Swift mode",
            message = "Write idiomatic Swift",
            custom = buildJsonObject { put("modelId", 5L) }
        )
        val created = instruction(
            id = 10L,
            name = "Swift mode",
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            message = "Write idiomatic Swift"
        )
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Post, engineRequest.method)
            assertEquals(href(InstructionResource()), engineRequest.url.fullPath)
            val body = engineRequest.body.toByteArray().decodeToString()
            assertTrue(body.contains("model_specific"), "Request body should contain the instruction type")
            assertTrue(body.contains("Swift mode"), "Request body should contain the instruction name")
            assertTrue(body.contains("modelId"), "Request body should contain the custom model target")
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createInstruction(request)) {
            is Either.Right -> {
                assertEquals(10L, result.value.id)
                assertEquals(AgentInstructionTypes.MODEL_SPECIFIC, result.value.type)
                assertEquals("Swift mode", result.value.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createInstruction - failure - 400 Bad Request`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid instruction: name cannot be blank")
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val request = CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "", message = "Text")
        when (val result = apiClient.createInstruction(request)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
            }
        }
    }

    @Test
    fun `listInstructions - success`() = runTest {
        val library = listOf(instruction(id = 1L, name = "Tone"), instruction(id = 2L, name = "Style"))
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Get, engineRequest.method)
            assertEquals(href(InstructionResource()), engineRequest.url.fullPath)
            respond(
                content = json.encodeToString(library),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.listInstructions()) {
            is Either.Right -> assertEquals(listOf(1L, 2L), result.value.map { it.id })
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `listInstructions - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Boom")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.listInstructions()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
            }
        }
    }

    @Test
    fun `updateInstruction - success`() = runTest {
        val request = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be formal"
        )
        val updated = instruction(id = 5L, name = "Tone", message = "Be formal")
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Put, engineRequest.method)
            assertEquals(href(InstructionResource()), engineRequest.url.fullPath)
            val body = engineRequest.body.toByteArray().decodeToString()
            assertTrue(body.contains("Be formal"), "Request body should contain the replacement message")
            // The test codec pretty-prints, so the whitespace is stripped before matching the id field.
            assertTrue(
                body.replace(" ", "").contains("\"id\":5"),
                "Request body should name the row to replace"
            )
            respond(
                content = json.encodeToString(updated),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateInstruction(request)) {
            is Either.Right -> {
                assertEquals(5L, result.value.id)
                assertEquals("Be formal", result.value.message)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateInstruction - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Instruction not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val request = UpdateInstructionRequest(
            id = 999L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Text"
        )
        when (val result = apiClient.updateInstruction(request)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    @Test
    fun `getInstruction - success carries the linking roles`() = runTest {
        val row = instruction(id = 5L, name = "Tone").copy(linkedRoleIds = setOf(2L, 7L))
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Get, engineRequest.method)
            assertEquals(
                href(InstructionResource.ById(instructionId = 5L)),
                engineRequest.url.fullPath
            )
            respond(
                content = json.encodeToString(row),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getInstruction(5L)) {
            is Either.Right -> assertEquals(setOf(2L, 7L), result.value.linkedRoleIds)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getInstruction - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Instruction not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getInstruction(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> assertEquals(404, (result.value as ApiResourceError.ServerError).apiError.statusCode)
        }
    }

    @Test
    fun `deleteInstruction - success`() = runTest {
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Delete, engineRequest.method)
            assertEquals(
                href(InstructionResource.ById(instructionId = 5L)),
                engineRequest.url.fullPath
            )
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteInstruction(5L)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `assignInstruction - posts to the nested link resource and returns the updated role`() = runTest {
        val updatedRole = AgentRoleDto(id = 3L, name = "writer", modelId = null, modelSettingsId = null)
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Post, engineRequest.method)
            assertEquals(expectedLinkPath(roleId = 3L, instructionId = 5L), engineRequest.url.fullPath)
            respond(
                content = json.encodeToString(updatedRole),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.assignInstruction(roleId = 3L, instructionId = 5L)) {
            is Either.Right -> assertEquals(3L, result.value.id)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `unassignInstruction - deletes the nested link resource and returns the updated role`() = runTest {
        val updatedRole = AgentRoleDto(id = 3L, name = "writer", modelId = null, modelSettingsId = null)
        val mockEngine = MockEngine { engineRequest ->
            assertEquals(HttpMethod.Delete, engineRequest.method)
            assertEquals(expectedLinkPath(roleId = 3L, instructionId = 5L), engineRequest.url.fullPath)
            respond(
                content = json.encodeToString(updatedRole),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.unassignInstruction(roleId = 3L, instructionId = 5L)) {
            is Either.Right -> assertEquals(3L, result.value.id)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `assignInstruction - failure - 409 Conflict for an already linked row`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(
                    apiError(CommonApiErrorCodes.ALREADY_EXISTS, "The instruction is already linked to this agent role")
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.assignInstruction(roleId = 3L, instructionId = 5L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.apiError.code)
            }
        }
    }

    /**
     * The URL of the role↔instruction link endpoint the two link calls must address.
     *
     * @param roleId The role the link belongs to.
     * @param instructionId The linked instruction.
     * @return The expected path.
     */
    private fun expectedLinkPath(roleId: Long, instructionId: Long): String = href(
        AgentRoleResource.ById.Instructions.ByInstructionId(
            parent = AgentRoleResource.ById.Instructions(parent = AgentRoleResource.ById(roleId = roleId)),
            instructionId = instructionId
        )
    )
}
