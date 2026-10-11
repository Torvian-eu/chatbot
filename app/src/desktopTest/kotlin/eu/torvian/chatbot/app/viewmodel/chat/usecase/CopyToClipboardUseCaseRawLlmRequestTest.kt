package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Verifies the clipboard action of the request viewer: it copies the currently displayed request and reports
 * the outcome, and never touches the clipboard when there is nothing to copy.
 */
class CopyToClipboardUseCaseRawLlmRequestTest {

    private val clipboardService = mockk<ClipboardService>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)

    /** Builds the use case over a state exposing exactly [request]. */
    private fun createUseCase(request: RawLlmRequestPayload?): CopyToClipboardUseCase {
        val state = mockk<ChatState>()
        every { state.rawLlmRequest } returns MutableStateFlow(request)
        return CopyToClipboardUseCase(state, clipboardService, notificationService)
    }

    /** Verifies an existing capture is copied once and the success is reported. */
    @Test
    fun `an existing request is copied and reported`() = runTest {
        val request = RawLlmRequestPayload(
            method = "POST",
            url = "https://api.example.com/v1/chat/completions",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"model":"gpt-4"}"""
        )

        createUseCase(request).copyRawLlmRequest()

        coVerify(exactly = 1) { clipboardService.copyToClipboard(request.requestJson()) }
        coVerify(exactly = 1) { notificationService.genericSuccess(any()) }
    }

    /** Verifies the null case warns instead of writing an empty document to the clipboard. */
    @Test
    fun `a missing request warns and copies nothing`() = runTest {
        createUseCase(null).copyRawLlmRequest()

        coVerify(exactly = 0) { clipboardService.copyToClipboard(any()) }
        coVerify(exactly = 1) { notificationService.genericWarning(any<String>(), any()) }
        coVerify(exactly = 0) { notificationService.genericSuccess(any()) }
    }
}
