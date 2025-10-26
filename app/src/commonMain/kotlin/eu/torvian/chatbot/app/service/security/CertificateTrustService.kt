package eu.torvian.chatbot.app.service.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mediator service used to bridge the synchronous network layer with the asynchronous UI
 * when a certificate trust decision is required.
 *
 * Flow of operation:
 * 1. The network thread (via the TrustManager) calls [promptUserForTrust], supplying a [CertificateDetails]
 *    and suspends until the user responds.
 * 2. The UI observes [trustRequestState] and shows a dialog when a non-null value is posted.
 * 3. When the user accepts/rejects the certificate the UI calls [onUserResponse], which completes
 *    the suspended call and allows the network thread to proceed.
 *
 * This object is intentionally simple and can be provided as a singleton via Koin.
 */
class CertificateTrustService {

    private val _trustRequestState = MutableStateFlow<CertificateDetails?>(null)
    val trustRequestState: StateFlow<CertificateDetails?> = _trustRequestState.asStateFlow()

    private var userResponse = CompletableDeferred<Boolean>()

    /**
     * Called by the TrustManager to post a trust request to the UI and suspend
     * until a user decision is made.
     *
     * @param details Human-friendly certificate information to show in the prompt.
     * @return true if the user accepted the certificate, false if they rejected it.
     */
    suspend fun promptUserForTrust(details: CertificateDetails): Boolean {
        // Ensure we have a fresh deferred for the new request
        if (userResponse.isCompleted) {
            userResponse = CompletableDeferred()
        }
        _trustRequestState.value = details
        return userResponse.await() // Suspend until onUserResponse is called
    }

    /**
     * Called by the UI when the user accepts or rejects the certificate.
     * This will hide the dialog and complete the suspended network call.
     *
     * @param accepted true if the user accepted the certificate, false otherwise.
     */
    fun onUserResponse(accepted: Boolean) {
        _trustRequestState.value = null // Hide the dialog
        userResponse.complete(accepted)
    }
}

