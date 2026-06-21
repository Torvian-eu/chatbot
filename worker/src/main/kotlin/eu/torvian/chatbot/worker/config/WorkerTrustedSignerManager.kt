package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.either
import java.nio.file.Path

/**
 * Mutates only `worker.trustedSigners` inside the worker `application.json` layer.
 *
 * This service intentionally loads and saves the application layer DTO directly instead of using
 * the fully merged config so unrelated setup or environment overlays are not flattened back into
 * persisted storage.
 *
 * The class is open so startup orchestration tests can substitute a focused test double without
 * introducing a broader abstraction for this narrow admin-mode concern.
 *
 * @property configLoader Loader used to read and write worker config layers.
 */
open class WorkerTrustedSignerManager(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader()
) {
    /**
     * Adds a new trusted signer or replaces an existing one with the same signer identifier.
     *
     * @param configDir Worker config directory that owns `application.json`.
     * @param signerId Raw signer identifier supplied by the operator.
     * @param publicKeyBase64 Raw Base64 public key supplied by the operator.
     * @param permissionsCsv Optional comma-separated permission list supplied by the operator.
     * @return Either a logical mutation error or metadata about the successful save.
     */
    open fun addOrUpdateTrustedSigner(
        configDir: Path,
        signerId: String,
        publicKeyBase64: String,
        permissionsCsv: String?
    ): Either<WorkerTrustedSignerManagerError, TrustedSignerUpdateResult> = either {
        val trustedSigner = trustedSignerDtoFromInput(
            signerId = signerId,
            publicKeyBase64 = publicKeyBase64,
            permissionsCsv = permissionsCsv
        ).mapLeft(::mapInputError).bind()

        val normalizedConfigDir = configDir.toAbsolutePath().normalize()
        val applicationConfig = configLoader.loadLayerDto(
            normalizedConfigDir,
            DefaultWorkerConfigLoader.APPLICATION_FILE_NAME,
            optional = true
        ).mapLeft(::mapConfigError).bind()

        val (updatedApplicationConfig, replacedExisting) = patchApplicationConfig(applicationConfig, trustedSigner)
        val applicationConfigPath = configLoader.resolveLayerPath(
            normalizedConfigDir,
            DefaultWorkerConfigLoader.APPLICATION_FILE_NAME
        )

        configLoader.saveLayerDto(
            normalizedConfigDir,
            DefaultWorkerConfigLoader.APPLICATION_FILE_NAME,
            updatedApplicationConfig
        ).mapLeft(::mapConfigError).bind()

        TrustedSignerUpdateResult(
            signerId = trustedSigner.signerId,
            applicationConfigPath = applicationConfigPath,
            replacedExisting = replacedExisting
        )
    }

    /**
     * Updates only the trusted signer list while preserving every unrelated application-layer field.
     *
     * If the worker section does not exist yet, a minimal structure is created so the trusted signer
     * list can be persisted without inventing unrelated runtime values.
     *
     * @param existingConfig Existing application-layer DTO.
     * @param trustedSigner Normalized signer DTO to persist.
     * @return Pair of the patched DTO and a flag indicating whether an entry was replaced.
     */
    private fun patchApplicationConfig(
        existingConfig: AppConfigDto,
        trustedSigner: TrustedSignerDto
    ): Pair<AppConfigDto, Boolean> {
        val existingWorker = existingConfig.worker ?: RuntimeConfigDto()
        val existingTrustedSigners = existingWorker.trustedSigners.orEmpty().toMutableList()
        val existingSignerIndex = existingTrustedSigners.indexOfFirst { it.signerId == trustedSigner.signerId }

        val replacedExisting = existingSignerIndex >= 0
        if (replacedExisting) {
            // Replace in place so operator-authored ordering stays stable for the rest of the list.
            existingTrustedSigners[existingSignerIndex] = trustedSigner
        } else {
            existingTrustedSigners += trustedSigner
        }

        return existingConfig.copy(
            worker = existingWorker.copy(
                trustedSigners = existingTrustedSigners
            )
        ) to replacedExisting
    }

    /**
     * Maps shared input-validation errors into the manager error model.
     *
     * @param error Shared config validation error.
     * @return Manager error appropriate for the failure type.
     */
    private fun mapInputError(error: WorkerConfigError): WorkerTrustedSignerManagerError {
        return when (error) {
            is WorkerConfigError.ConfigInvalid -> WorkerTrustedSignerManagerError.InvalidInput(error)
            is WorkerConfigError.ConfigMissing,
            is WorkerConfigError.ConfigReadFailed -> WorkerTrustedSignerManagerError.Config(error)
        }
    }

    /**
     * Wraps config layer persistence errors in the manager error model.
     *
     * @param error Underlying configuration error.
     * @return Wrapped manager config error.
     */
    private fun mapConfigError(error: WorkerConfigError): WorkerTrustedSignerManagerError.Config {
        return WorkerTrustedSignerManagerError.Config(error)
    }
}