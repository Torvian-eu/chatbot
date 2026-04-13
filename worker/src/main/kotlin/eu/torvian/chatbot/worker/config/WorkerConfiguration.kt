package eu.torvian.chatbot.worker.config

/**
 * Root worker configuration, analogous to the server's root app configuration model.
 *
 * @property setupRequired Whether worker setup flow is still required.
 * @property worker Runtime worker settings used by the process.
 */
data class WorkerConfiguration(
    val setupRequired: Boolean,
    val worker: WorkerRuntimeConfig
)

