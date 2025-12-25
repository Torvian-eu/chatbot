package eu.torvian.chatbot.common.models.core

/**
 * Enumeration of possible positions for inserting a new message relative to a target message.
 */
enum class MessageInsertPosition {
    /** Insert as a new parent before the target message. Target message's parent becomes the new message's parent */
    ABOVE,

    /** Insert as a single child after the target message. The target message's children become the new message's children */
    BELOW,

    /** Insert as a new child after the target message. The child always becomes a leaf.
     * If the target message was not a leaf before, this operation creates a new branch. */
    APPEND
}