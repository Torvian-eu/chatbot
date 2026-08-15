package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.AgentRoleApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [AgentRoleRepository] backed by [AgentRoleApi].
 *
 * Maintains an in-memory [StateFlow] cache of the user's agent roles and refreshes it after every
 * successful CRUD operation so chat state and the management tab stay in sync automatically.
 *
 * @property agentRoleApi The API client used for all agent-role requests.
 */
class DefaultAgentRoleRepository(
    private val agentRoleApi: AgentRoleApi
) : AgentRoleRepository {

    companion object {
        private val logger = kmpLogger<DefaultAgentRoleRepository>()
    }

    private val _roles = MutableStateFlow<DataState<RepositoryError, List<AgentRoleDto>>>(DataState.Idle)
    override val roles: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> = _roles.asStateFlow()

    override suspend fun loadRoles(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_roles.value.isLoading) return Unit.right()

        _roles.update { DataState.Loading }

        return agentRoleApi.getAllRoles().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load agent roles")
                logger.warn("Failed to load agent roles: ${repoError.message}")
                _roles.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { roleList ->
                _roles.update { DataState.Success(roleList) }
                logger.debug("Successfully loaded ${roleList.size} agent roles")
                Unit.right()
            }
        )
    }

    override suspend fun loadRoleDetails(roleId: Long): Either<RepositoryError, AgentRoleDto> {
        logger.info("Loading details for agent role ID: $roleId")
        return agentRoleApi.getRoleById(roleId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load details for agent role ID: $roleId")
                logger.warn("Failed to load details for agent role ID: $roleId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { role ->
                logger.info("Successfully loaded details for agent role ID: $roleId")
                updateRolesState { list ->
                    if (list.any { it.id == role.id }) list.map { if (it.id == role.id) role else it } else list + role
                }
                role.right()
            }
        )
    }

    override suspend fun createRole(request: CreateAgentRoleRequest): Either<RepositoryError, AgentRoleDto> {
        logger.info("Creating new agent role: ${request.name}")

        return agentRoleApi.createRole(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to create agent role '${request.name}'")
                logger.warn("Failed to create agent role '${request.name}': ${repoError.message}")
                repoError.left()
            },
            ifRight = { newRole ->
                logger.info("Successfully created agent role: ${newRole.name} with ID: ${newRole.id}")
                updateRolesState { list -> list + newRole }
                newRole.right()
            }
        )
    }

    override suspend fun updateRole(roleId: Long, request: UpdateAgentRoleRequest): Either<RepositoryError, AgentRoleDto> {
        logger.info("Updating agent role ID: $roleId")

        return agentRoleApi.updateRole(roleId, request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update agent role ID: $roleId")
                logger.warn("Failed to update agent role ID: $roleId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedRole ->
                logger.info("Successfully updated agent role ID: $roleId")
                updateRolesState { list ->
                    list.map { if (it.id == updatedRole.id) updatedRole else it }
                }
                updatedRole.right()
            }
        )
    }

    override suspend fun deleteRole(roleId: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting agent role ID: $roleId")

        return agentRoleApi.deleteRole(roleId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to delete agent role ID: $roleId")
                logger.warn("Failed to delete agent role ID: $roleId: ${repoError.message}")
                repoError.left()
            },
            ifRight = {
                logger.info("Successfully deleted agent role ID: $roleId")
                updateRolesState { list -> list.filterNot { it.id == roleId } }
                Unit.right()
            }
        )
    }

    /**
     * Helper to update the roles state when it's in Success or Idle state.
     */
    private fun updateRolesState(transform: (List<AgentRoleDto>) -> List<AgentRoleDto>) {
        _roles.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> currentState
            }
        }
    }
}
