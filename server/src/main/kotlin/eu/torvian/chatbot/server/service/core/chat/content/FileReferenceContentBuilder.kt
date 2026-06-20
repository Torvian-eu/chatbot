package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.core.FileReference

/**
 * Embeds file references into user-message content for LLM context.
 */
interface FileReferenceContentBuilder {
    /**
     * Builds message content with inline and attached file-reference formatting applied.
     *
     * @param content Original user-message content.
     * @param fileReferences File references attached to the user message.
     * @return Message content with file references embedded using the server's existing formatting contract.
     */
    fun build(content: String, fileReferences: List<FileReference>): String
}