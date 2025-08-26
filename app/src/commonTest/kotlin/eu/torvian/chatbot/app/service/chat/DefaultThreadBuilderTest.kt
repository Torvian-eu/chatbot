package eu.torvian.chatbot.app.service.chat

import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.common.models.ChatMessage
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultThreadBuilderTest {
    
    private val threadBuilder = DefaultThreadBuilder()
    private val now = Clock.System.now()
    
    private fun createUserMessage(
        id: Long,
        sessionId: Long = 1L,
        content: String = "Test message $id",
        parentId: Long? = null,
        children: List<Long> = emptyList()
    ) = ChatMessage.UserMessage(
        id = id,
        sessionId = sessionId,
        content = content,
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId,
        childrenMessageIds = children
    )
    
    private fun createAssistantMessage(
        id: Long,
        sessionId: Long = 1L,
        content: String = "Assistant response $id",
        parentId: Long? = null,
        children: List<Long> = emptyList(),
        modelId: Long? = 1L,
        settingsId: Long? = 1L
    ) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = sessionId,
        content = content,
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId,
        childrenMessageIds = children,
        modelId = modelId,
        settingsId = settingsId
    )
    
    @Test
    fun `buildThreadBranch returns empty list when leafId is null`() {
        val messages = listOf(createUserMessage(1))
        val result = threadBuilder.buildThreadBranch(messages, null)
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `buildThreadBranch returns empty list when messages are empty`() {
        val result = threadBuilder.buildThreadBranch(emptyList(), 1L)
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `buildThreadBranch builds simple linear thread correctly`() {
        val msg1 = createUserMessage(1, children = listOf(2))
        val msg2 = createAssistantMessage(2, parentId = 1, children = listOf(3))
        val msg3 = createUserMessage(3, parentId = 2)
        val messages = listOf(msg1, msg2, msg3)
        
        val result = threadBuilder.buildThreadBranch(messages, 3L)
        
        assertEquals(3, result.size)
        assertEquals(1L, result[0].id)
        assertEquals(2L, result[1].id)
        assertEquals(3L, result[2].id)
    }
    
    @Test
    fun `buildThreadBranch handles single message thread`() {
        val msg1 = createUserMessage(1)
        val messages = listOf(msg1)
        
        val result = threadBuilder.buildThreadBranch(messages, 1L)
        
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
    }
    
    @Test
    fun `findLeafOfBranch returns null for non-existent start message`() {
        val msg1 = createUserMessage(1)
        val messageMap = mapOf(1L to msg1)
        
        val result = threadBuilder.findLeafOfBranch(999L, messageMap)
        
        assertNull(result)
    }
    
    @Test
    fun `findLeafOfBranch returns start message ID when it has no children`() {
        val msg1 = createUserMessage(1)
        val messageMap = mapOf(1L to msg1)
        
        val result = threadBuilder.findLeafOfBranch(1L, messageMap)
        
        assertEquals(1L, result)
    }
    
    @Test
    fun `findLeafOfBranch traverses to leaf correctly`() {
        val msg1 = createUserMessage(1, children = listOf(2))
        val msg2 = createAssistantMessage(2, parentId = 1, children = listOf(3))
        val msg3 = createUserMessage(3, parentId = 2)
        val messageMap = mapOf(1L to msg1, 2L to msg2, 3L to msg3)
        
        val result = threadBuilder.findLeafOfBranch(1L, messageMap)
        
        assertEquals(3L, result)
    }
    
    @Test
    fun `findLeafOfBranch handles missing child message gracefully`() {
        val msg1 = createUserMessage(1, children = listOf(999)) // Child doesn't exist
        val messageMap = mapOf(1L to msg1)
        
        val result = threadBuilder.findLeafOfBranch(1L, messageMap)
        
        assertEquals(1L, result) // Should return the last valid message
    }
}
