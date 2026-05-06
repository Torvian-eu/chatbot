package eu.torvian.chatbot.server.service.security.error

/**
 * Error types for trusted device revocation operations.
 */
sealed class RevokeTrustedDeviceError {
    /**
     * Raised when a restricted session attempts to revoke a trusted device.
     *
     * @property reason Human-readable description of why the action was denied
     */
    data class InsufficientPermissions(val reason: String = "Action requires a trusted session") : RevokeTrustedDeviceError()

    /**
     * Raised when the device to revoke is not found in the user's trusted devices list.
     *
     * @property deviceId The device identifier that was not found
     */
    data class DeviceNotFound(val deviceId: String) : RevokeTrustedDeviceError()
}
