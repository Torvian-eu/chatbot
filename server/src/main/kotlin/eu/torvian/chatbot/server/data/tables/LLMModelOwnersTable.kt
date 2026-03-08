package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.LLMModelOwnersTable.modelId
import eu.torvian.chatbot.server.data.tables.LLMModelOwnersTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Links an LLM model to its owning user.
 * 
 * This table establishes a one-to-one relationship between LLM models
 * and their owners. Each model has exactly one owner, and ownership
 * determines who can access, modify, or delete the model configuration.
 * 
 * @property modelId Reference to the LLM model being owned
 * @property userId Reference to the user who owns the model
 */
object LLMModelOwnersTable : Table("llm_model_owners") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // model_id is primary key, ensuring 1 owner per model
    override val primaryKey = PrimaryKey(modelId)
}
