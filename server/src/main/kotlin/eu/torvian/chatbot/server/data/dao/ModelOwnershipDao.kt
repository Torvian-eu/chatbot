package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between LLM models and users.
 *
 * The DAO operates on the table `llm_model_owners` (model_id, user_id).
 * Implementation notes:
 *  - getOwner returns ResourceNotFound when the model row or owner row does not exist.
 *  - setOwner attempts to insert a (model_id, user_id) row. Constraint violations are mapped to SetOwnerError.
 */
interface ModelOwnershipDao {
    /**
     * Returns the user id owning the given model.
     *
     * @param modelId ID of the model.
     * @return Either [GetOwnerError.ResourceNotFound] if no such model/owner exists, or the owner's user id.
     */
    suspend fun getOwner(modelId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the model and a user.
     *
     * This performs an insert into `llm_model_owners`. Implementations should map DB constraint
     * violations into [SetOwnerError].
     *
     * @param modelId ID of the model to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(modelId: Long, userId: Long): Either<SetOwnerError, Unit>
}

