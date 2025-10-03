package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.RoleRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.RoleApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.admin.CreateRoleRequest
import eu.torvian.chatbot.common.models.admin.UpdateRoleRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [RoleRepository] providing role management with reactive StateFlow updates.
 *
 * This implementation maintains an in-memory cache of roles using StateFlow, automatically
 * refreshing the cache after successful CRUD operations. It follows the same patterns
 * as other repositories in the application for consistency.
 */
class DefaultRoleRepository(
    private val roleApi: RoleApi
) : RoleRepository {

    companion object {
        private val logger = kmpLogger<DefaultRoleRepository>()
    }

    private val _roles = MutableStateFlow<DataState<RepositoryError, List<Role>>>(DataState.Idle)
    override val roles: StateFlow<DataState<RepositoryError, List<Role>>> = _roles.asStateFlow()

    override suspend fun loadRoles(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_roles.value.isLoading) return Unit.right()

        _roles.update { DataState.Loading }

        return roleApi.getAllRoles().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load roles")
                logger.warn("Failed to load roles: ${repoError.message}")
                _roles.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { roleList ->
                _roles.update { DataState.Success(roleList) }
                logger.debug("Successfully loaded ${roleList.size} roles")
                Unit.right()
            }
        )
    }

    override suspend fun loadRoleDetails(roleId: Long): Either<RepositoryError, Role> {
        logger.info("Loading details for role ID: $roleId")
        return roleApi.getRoleById(roleId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load details for role ID: $roleId")
                logger.warn("Failed to load details for role ID: $roleId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { role ->
                logger.info("Successfully loaded details for role ID: $roleId")
                // Update or insert the role in the main list
                updateRolesState { list ->
                    if (list.any { it.id == role.id })
                        list.map { if (it.id == role.id) role else it }
                    else list + role
                }
                role.right()
            }
        )
    }

    override suspend fun createRole(name: String, description: String?): Either<RepositoryError, Role> {
        logger.info("Creating new role: $name")

        val request = CreateRoleRequest(name = name, description = description)
        return roleApi.createRole(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to create role '$name'")
                logger.warn("Failed to create role '$name': ${repoError.message}")
                repoError.left()
            },
            ifRight = { newRole ->
                logger.info("Successfully created role: ${newRole.name} with ID: ${newRole.id}")
                updateRolesState { list -> list + newRole }
                newRole.right()
            }
        )
    }

    override suspend fun updateRole(id: Long, name: String, description: String?): Either<RepositoryError, Role> {
        logger.info("Updating role ID: $id with name: $name")

        val request = UpdateRoleRequest(name = name, description = description)
        return roleApi.updateRole(id, request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update role ID: $id")
                logger.warn("Failed to update role ID: $id: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedRole ->
                logger.info("Successfully updated role ID: $id")
                updateRolesState { list ->
                    list.map { if (it.id == updatedRole.id) updatedRole else it }
                }
                updatedRole.right()
            }
        )
    }

    override suspend fun deleteRole(id: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting role ID: $id")

        return roleApi.deleteRole(id).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to delete role ID: $id")
                logger.warn("Failed to delete role ID: $id: ${repoError.message}")
                repoError.left()
            },
            ifRight = {
                logger.info("Successfully deleted role ID: $id")
                updateRolesState { list -> list.filterNot { it.id == id } }
                Unit.right()
            }
        )
    }

    /**
     * Helper function to update the roles state when it's in Success state.
     */
    private fun updateRolesState(transform: (List<Role>) -> List<Role>) {
        _roles.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> currentState
            }
        }
    }
}
