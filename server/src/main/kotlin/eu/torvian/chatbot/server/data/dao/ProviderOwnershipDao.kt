package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between LLM providers and users.
 *
 * The DAO operates on the table `llm_provider_owners` (provider_id, user_id).
 * Implementation notes:
 *  - getOwner returns ResourceNotFound when the provider row or owner row does not exist.
 *  - setOwner attempts to insert a (provider_id, user_id) row. Constraint violations are mapped to SetOwnerError.
 */
interface ProviderOwnershipDao {
    /**
     * Returns the user id owning the given provider.
     *
     * @param providerId ID of the provider.
     * @return Either [GetOwnerError.ResourceNotFound] if no such provider/owner exists, or the owner's user id.
     */
    suspend fun getOwner(providerId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the provider and a user.
     *
     * This performs an insert into `llm_provider_owners`. Implementations should map DB constraint
     * violations into [SetOwnerError].
     *
     * @param providerId ID of the provider to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(providerId: Long, userId: Long): Either<SetOwnerError, Unit>
}

