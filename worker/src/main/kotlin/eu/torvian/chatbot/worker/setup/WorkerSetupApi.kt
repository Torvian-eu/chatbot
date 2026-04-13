package eu.torvian.chatbot.worker.setup

import arrow.core.Either

/**
 * HTTP API used by setup to login, register the worker, and logout.
 *
 * The setup manager uses this abstraction so the orchestration can be tested without
 * hitting a real server.
 */
interface WorkerSetupApi : AutoCloseable {
    /**
     * Logs in with the provided user credentials and returns an access token.
     *
     * @param username User name used for server authentication.
     * @param password Plaintext password used for server authentication.
     * @return Either a logical setup error or a bearer access token.
     */
    suspend fun login(username: String, password: String): Either<WorkerSetupError, String>

    /**
     * Registers the newly generated worker certificate with the server.
     *
     * @param accessToken Bearer access token obtained from [login].
     * @param workerUid Worker identifier to register.
     * @param certificatePem PEM-encoded public certificate for the worker identity.
     * @return Either a logical setup error or `Unit` on success.
     */
    suspend fun registerWorker(accessToken: String, workerUid: String, certificatePem: String): Either<WorkerSetupError, Unit>

    /**
     * Logs out the setup session that was used to register the worker.
     *
     * @param accessToken Bearer access token obtained from [login].
     * @return Either a logical setup error or `Unit` on success.
     */
    suspend fun logout(accessToken: String): Either<WorkerSetupError, Unit>

    /**
     * Releases any resources owned by the API implementation.
     */
    override fun close() {}
}

