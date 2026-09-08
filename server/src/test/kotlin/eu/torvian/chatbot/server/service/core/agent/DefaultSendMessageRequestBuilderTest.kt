package eu.torvian.chatbot.server.service.core.agent

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.core.error.agent.SendMessageRequestBuildError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultSendMessageRequestBuilder].
 *
 * Covers the happy path (parsing + same-user ownership validation), typed errors for malformed or
 * missing input, invalid `mode` values, and the target-session existence / not-owned failures.
 */
class DefaultSendMessageRequestBuilderTest {

    private val sessionOwnershipDao = mockk<SessionOwnershipDao>()
    private val json = Json

    private val builder = DefaultSendMessageRequestBuilder(sessionOwnershipDao, json)

    /**
     * Creates a persisted send-message tool call with valid chat_session_id and message arguments
     * by default.
     *
     * @param id Identifier copied to the eventual send request.
     * @param input Raw JSON arguments to place on the tool call.
     * @return A tool call suitable for request-builder tests.
     */
    private fun toolCall(
        id: Long = 42L,
        input: String? = """{"chat_session_id":7,"message":"Continue please"}"""
    ): ToolCall = ToolCall(
        id = id,
        messageId = 100L,
        toolDefinitionId = 9L,
        toolName = "send_message",
        input = input,
        status = ToolCallStatus.PENDING,
        executedAt = Instant.fromEpochMilliseconds(1L)
    )

    /**
     * Verifies that valid arguments validate ownership and assemble the request with the default
     * wait-for-response mode.
     */
    @Test
    fun `build validates ownership and assembles the request`() = runTest {
        coEvery { sessionOwnershipDao.getOwner(7L) } returns 1L.right()

        val result = builder.build(1L, toolCall())

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        val request = result.getOrNull()!!
        assertEquals(7L, request.chatSessionId)
        assertEquals("Continue please", request.message)
        assertEquals(OperatorToolMode.WAIT_FOR_RESPONSE, request.mode)
        assertEquals(42L, request.toolCallId)
    }

    /**
     * Verifies that an explicit `mode: fire_and_forget` is parsed and carried through unchanged.
     */
    @Test
    fun `build carries fire and forget mode into the request`() = runTest {
        coEvery { sessionOwnershipDao.getOwner(7L) } returns 1L.right()

        val result = builder.build(
            1L,
            toolCall(input = """{"chat_session_id":7,"message":"Continue","mode":"fire_and_forget"}""")
        )

        assertTrue(result.isRight(), "expected success but got ${result.leftOrNull()}")
        assertEquals(OperatorToolMode.FIRE_AND_FORGET, result.getOrNull()!!.mode)
    }

    /**
     * Verifies that missing or blank messages are rejected before any ownership I/O.
     */
    @Test
    fun `build rejects missing or blank message before ownership lookup`() = runTest {
        assertIs<SendMessageRequestBuildError.InvalidInput>(
            builder.build(1L, toolCall(input = """{"chat_session_id":7}""")).leftOrNull()
        )
        assertIs<SendMessageRequestBuildError.InvalidInput>(
            builder.build(1L, toolCall(input = """{"chat_session_id":7,"message":"   "}""")).leftOrNull()
        )
        // Parsing precedes I/O: the ownership DAO must not be consulted for malformed input.
        coVerify(exactly = 0) { sessionOwnershipDao.getOwner(any()) }
    }

    /**
     * Verifies that absent, non-integral, or structured `chat_session_id` values are rejected as
     * [SendMessageRequestBuildError.InvalidInput] before any ownership I/O.
     */
    @Test
    fun `build rejects invalid chat_session_id before ownership lookup`() = runTest {
        val invalidInputs = listOf(
            // Absent.
            """{"message":"hi"}""",
            // JSON string ids are deliberately rejected — only integral JSON numbers are accepted.
            """{"chat_session_id":"7","message":"hi"}""",
            // Non-integral number.
            """{"chat_session_id":7.5,"message":"hi"}""",
            // Structured values.
            """{"chat_session_id":true,"message":"hi"}""",
            """{"chat_session_id":[7],"message":"hi"}""",
            """{"chat_session_id":{"id":7},"message":"hi"}""",
            """{"chat_session_id":null,"message":"hi"}"""
        )

        invalidInputs.forEach { input ->
            val result = builder.build(1L, toolCall(input = input))
            assertIs<SendMessageRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        coVerify(exactly = 0) { sessionOwnershipDao.getOwner(any()) }
    }

    /**
     * Verifies that a malformed `mode` value is rejected before any ownership I/O.
     */
    @Test
    fun `build rejects an invalid mode value before ownership lookup`() = runTest {
        val invalidModes = listOf(
            """{"chat_session_id":7,"message":"hi","mode":"yes"}""",
            """{"chat_session_id":7,"message":"hi","mode":1}""",
            """{"chat_session_id":7,"message":"hi","mode":null}""",
            """{"chat_session_id":7,"message":"hi","mode":[true]}""",
            """{"chat_session_id":7,"message":"hi","mode":{"x":1}}"""
        )

        invalidModes.forEach { input ->
            val result = builder.build(1L, toolCall(input = input))
            assertIs<SendMessageRequestBuildError.InvalidInput>(result.leftOrNull())
        }
        coVerify(exactly = 0) { sessionOwnershipDao.getOwner(any()) }
    }

    /**
     * Verifies that a missing target session (no ownership row) maps to a typed
     * [SendMessageRequestBuildError.SessionNotFound].
     */
    @Test
    fun `build maps a missing target session to SessionNotFound`() = runTest {
        coEvery { sessionOwnershipDao.getOwner(7L) } returns
            GetOwnerError.ResourceNotFound("7").left()

        val result = builder.build(1L, toolCall())

        val error = assertIs<SendMessageRequestBuildError.SessionNotFound>(result.leftOrNull())
        assertEquals(7L, error.sessionId)
    }

    /**
     * Verifies that a foreign-owned target session maps to a typed
     * [SendMessageRequestBuildError.SessionNotAccessible] (reported identically to not-found at the
     * message layer so the server does not leak whether a foreign session exists).
     */
    @Test
    fun `build maps a foreign owned target session to SessionNotAccessible`() = runTest {
        coEvery { sessionOwnershipDao.getOwner(7L) } returns 2L.right()

        val result = builder.build(1L, toolCall())

        val error = assertIs<SendMessageRequestBuildError.SessionNotAccessible>(result.leftOrNull())
        assertEquals(7L, error.sessionId)
    }

    /**
     * Verifies that malformed argument JSON is reported as a logical error rather than thrown.
     */
    @Test
    fun `build rejects malformed input JSON`() = runTest {
        val result = builder.build(1L, toolCall(input = "not json"))

        assertIs<SendMessageRequestBuildError.InvalidInput>(result.leftOrNull())
    }
}