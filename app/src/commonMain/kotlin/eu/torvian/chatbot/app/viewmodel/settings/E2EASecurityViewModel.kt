package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.service.auth.DeviceIdentityService
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/**
 * ViewModel for E2EA (End-to-End Authorization) Security settings.
 *
 * This ViewModel manages the exposure of the client's cryptographic identity,
 * including the Signer ID and Base64-encoded public key. These are used for
 * worker authorization and secure request signing.
 *
 * The private key is never exposed. Only the public key and signer ID are
 * made available for copying to the clipboard and sharing with worker instances.
 *
 * @property deviceIdentityService Service providing access to the stable device ID and signing key pair.
 * @property clipboardService Service for copying content to the user's clipboard.
 * @property notificationService Service for notifying the user of success or error messages.
 */
class E2EASecurityViewModel(
    private val deviceIdentityService: DeviceIdentityService,
    private val clipboardService: ClipboardService,
    private val notificationService: NotificationService
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<E2EASecurityViewModel>()
    }

    /**
     * The unique signer (device) ID for this client.
     * Loaded on initialization and cached for the lifetime of the ViewModel.
     */
    private val _signerId: MutableStateFlow<String?> = MutableStateFlow(null)
    val signerId: StateFlow<String?> = _signerId.asStateFlow()

    /**
     * The Base64-encoded public key for this client.
     * Used by workers to verify signatures from this client.
     * Loaded on initialization and cached for the lifetime of the ViewModel.
     */
    private val _publicKeyBase64: MutableStateFlow<String?> = MutableStateFlow(null)
    val publicKeyBase64: StateFlow<String?> = _publicKeyBase64.asStateFlow()

    /**
     * Loading state indicating whether identity information is being fetched.
     */
    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Error state for user feedback if identity retrieval fails.
     */
    private val _error: MutableStateFlow<String?> = MutableStateFlow(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadIdentityInfo()
    }

    /**
     * Loads the signer ID and public key from DeviceIdentityService.
     *
     * Called on initialization and can be triggered manually via UI actions.
     * Retrieves the stable device ID and encodes the public key to Base64.
     */
    fun loadIdentityInfo() {
        viewModelScope.launch(Dispatchers.Default) {
            _isLoading.value = true
            _error.value = null

            try {
                // Retrieve stable device ID
                val deviceIdResult = deviceIdentityService.getOrCreateDeviceId()
                deviceIdResult.fold(
                    ifLeft = { error ->
                        _error.value = "Failed to retrieve signer ID: ${error.message}"
                        logger.error("Failed to retrieve signer ID: ${error.message}")
                    },
                    ifRight = { deviceId ->
                        _signerId.value = deviceId
                        logger.info("Successfully loaded signer ID")
                    }
                )

                // Retrieve signing key pair and encode public key to Base64
                val keyPairResult = deviceIdentityService.getOrCreateSigningKeyPair()
                keyPairResult.fold(
                    ifLeft = { error ->
                        _error.value = "Failed to retrieve public key: ${error.message}"
                        logger.error("Failed to retrieve public key: ${error.message}")
                    },
                    ifRight = { keyPair ->
                        val publicKeyBase64 = Base64.encode(keyPair.publicKey)
                        _publicKeyBase64.value = publicKeyBase64
                        logger.info("Successfully loaded public key")
                    }
                )
            } catch (e: Exception) {
                _error.value = "Unexpected error: ${e.message}"
                logger.error("Unexpected error loading identity info", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears any error messages (e.g., after user acknowledges an error).
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Copies the loaded signer ID to the clipboard and notifies the user.
     *
     * If the signer ID is not yet loaded, this method does nothing.
     */
    fun copySignerId() {
        val id = _signerId.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                clipboardService.copyToClipboard(id)
                notificationService.genericSuccess("Signer ID copied to clipboard")
                logger.info("Successfully copied signer ID to clipboard")
            } catch (e: Exception) {
                notificationService.genericError("Failed to copy to clipboard")
                logger.error("Failed to copy signer ID to clipboard", e)
            }
        }
    }

    /**
     * Copies the loaded Base64-encoded public key to the clipboard and notifies the user.
     *
     * If the public key is not yet loaded, this method does nothing.
     */
    fun copyPublicKey() {
        val key = _publicKeyBase64.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                clipboardService.copyToClipboard(key)
                notificationService.genericSuccess("Public key copied to clipboard")
                logger.info("Successfully copied public key to clipboard")
            } catch (e: Exception) {
                notificationService.genericError("Failed to copy to clipboard")
                logger.error("Failed to copy public key to clipboard", e)
            }
        }
    }

    /**
     * Formulates the CLI command for worker registration, copies it to the clipboard, and notifies the user.
     *
     * The command format is: `./start-worker.sh --add-trusted-signer --signer-id=<id> --public-key-base64=<key>`
     *
     * If either the signer ID or public key is not yet loaded, this method does nothing.
     */
    fun copyRegisterCommand() {
        val signerId = _signerId.value ?: return
        val publicKeyBase64 = _publicKeyBase64.value ?: return

        val command = "./start-worker.sh --add-trusted-signer --signer-id=$signerId --public-key-base64=$publicKeyBase64"

        viewModelScope.launch(Dispatchers.Default) {
            try {
                clipboardService.copyToClipboard(command)
                notificationService.genericSuccess("Command copied to clipboard")
                logger.info("Successfully copied worker registration command to clipboard")
            } catch (e: Exception) {
                notificationService.genericError("Failed to copy to clipboard")
                logger.error("Failed to copy worker registration command to clipboard", e)
            }
        }
    }
}
