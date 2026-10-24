package eu.torvian.chatbot.common.models.api.credential

/**
 * Single authority for credential-profile field rules, shared by the server, the worker and the app.
 *
 * Every rule is validated when the operator saves a profile and again when the worker receives one, so
 * a rule cannot pass on one side and fail on the other. Violations are human-readable sentences that
 * name a field or a variable but never echo a value, because the same text reaches the app, the model
 * and the logs.
 */
object CredentialProfileRules {

    /** Maximum number of entries a profile may add to its own process environment. */
    const val MAX_ENVIRONMENT_ENTRIES: Int = 64

    /** Maximum length of one profile environment value, in characters. */
    const val MAX_ENVIRONMENT_VALUE_LENGTH: Int = 4096

    /** Maximum length of a profile name and of a secret reference, in characters. */
    const val MAX_NAME_LENGTH: Int = 64

    /**
     * Maximum length of the `file` channel's file name, in characters.
     *
     * Chosen as the smallest per-component limit of the filesystems the worker runs on, so a name is
     * rejected when the operator saves it rather than when the helper tries to create it at `attach`.
     */
    const val MAX_FILE_NAME_LENGTH: Int = 255

    /** Pattern every variable name must match, for both environment entries and askpass names. */
    val ENV_NAME_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Names a profile may not set, because their value is not the operator's to decide.
     *
     * Covers the identity trio the isolation allowlist writes last, the two inputs of the worker's own
     * askpass script, the worker decryption key that must never reach a child, and the variables the
     * terminal layer sets, so terminal behaviour cannot be redirected per profile.
     */
    val RESERVED_ENVIRONMENT_NAMES: Set<String> = setOf(
        "HOME",
        "USER",
        "LOGNAME",
        "TORVIAN_ASKPASS_FILE",
        "TORVIAN_ASKPASS_USERNAME",
        "CHATBOT_WORKER_PRIVATE_KEY_PEM",
        "TERM",
        "NO_COLOR",
        "CLICOLOR",
        "PAGER",
        "GIT_PAGER",
        "SYSTEMD_PAGER",
        "DEBIAN_FRONTEND",
        "PS1",
        "PROMPT_COMMAND",
    )

    /**
     * Validates the operator-authored fields of a profile.
     *
     * @param fields Profile fields exactly as they will be persisted and signed.
     * @param deploymentEnvironmentKeys Keys of the deployment-wide child environment map, used to keep
     *   a profile from silently overriding a value the deployment owns.
     * @return Human-readable violations; an empty list means the profile is acceptable. No message
     *   contains a value from [fields].
     */
    fun validate(
        fields: CredentialProfileFields,
        deploymentEnvironmentKeys: Set<String> = emptySet(),
    ): List<String> {
        val violations = mutableListOf<String>()

        validateName(fields.name, "name", violations)
        validateName(fields.secretRef, "secretRef", violations)
        validateCommand(fields.command, violations)
        fields.args.forEach { argument ->
            if (argument.any { it == NUL }) {
                violations += "args must not contain a NUL character"
            }
        }
        if (fields.askpassUsername != null && fields.askpassUsername.isBlank()) {
            violations += "askpassUsername must not be blank when it is set"
        }
        validateChannelFields(fields, violations)
        validateEnvironmentEntries(fields, deploymentEnvironmentKeys, violations)
        validateAskpassVariables(fields, violations)
        return violations
    }

    /**
     * Validates a secret reference with the same rule a profile's `secretRef` must satisfy.
     *
     * A reference is read by the server, the worker and the model-visible error text, so the name is
     * checked where it is authored rather than only where a profile names it.
     *
     * @param reference Candidate reference, as it will be persisted.
     * @return A violation message, or null when the reference is acceptable.
     */
    fun validateReference(reference: String): String? {
        val violations = mutableListOf<String>()
        validateName(reference, "reference", violations)
        return violations.firstOrNull()
    }

    /**
     * Validates a variable name against the shared pattern and the reserved set.
     *
     * @param name Variable name to check.
     * @param fieldName Field the name came from, used in the message.
     * @return A violation message, or null when the name is acceptable.
     */
    fun validateVariableName(name: String, fieldName: String): String? = when {
        !ENV_NAME_PATTERN.matches(name) ->
            "$fieldName '$name' must match ${ENV_NAME_PATTERN.pattern}"
        name in RESERVED_ENVIRONMENT_NAMES ->
            "$fieldName '$name' is reserved and cannot be set by a credential profile"
        else -> null
    }

    /**
     * Appends a name and length violation when [value] is unusable as a profile name or reference.
     *
     * @param value Candidate name or reference.
     * @param fieldName Field name used in the message.
     * @param violations Collector the violation is appended to.
     */
    private fun validateName(value: String, fieldName: String, violations: MutableList<String>) {
        when {
            value.isBlank() -> violations += "$fieldName must not be blank"
            value.length > MAX_NAME_LENGTH ->
                violations += "$fieldName must be at most $MAX_NAME_LENGTH characters"
            value.any { it.isISOControl() } ->
                violations += "$fieldName must not contain control characters"
        }
    }

    /**
     * Appends a violation when [command] cannot be executed as a single argv element.
     *
     * @param command Executable the profile would start.
     * @param violations Collector the violation is appended to.
     */
    private fun validateCommand(command: String, violations: MutableList<String>) {
        when {
            command.isBlank() -> violations += "command must not be blank"
            // A fixed single argv element is the injection defence; whitespace would imply the command
            // is parsed as a shell line, which the worker never does.
            command.any { it.isWhitespace() } ->
                violations += "command must be a single executable without whitespace; use args instead"
            command.any { it == NUL } -> violations += "command must not contain a NUL character"
        }
    }

    /**
     * Appends violations for fields that are required, forbidden or exclusive per channel.
     *
     * @param fields Profile fields being validated.
     * @param violations Collector the violations are appended to.
     */
    private fun validateChannelFields(
        fields: CredentialProfileFields,
        violations: MutableList<String>,
    ) {
        when (fields.channel) {
            CredentialChannel.ASKPASS -> {
                if (fields.envName != null) {
                    violations += "envName must be omitted for the askpass channel"
                }
                if (fields.fileName != null) {
                    violations += "fileName must be omitted for the askpass channel"
                }
            }

            CredentialChannel.ENV -> {
                validateEnvironmentVariableField(fields.envName, "envName", violations)
                if (fields.fileName != null) {
                    violations += "fileName must be omitted for the env channel"
                }
                if (fields.askpassUsername != null) {
                    violations += "askpassUsername must be omitted for the env channel"
                }
            }

            CredentialChannel.FILE -> {
                validateEnvironmentVariableField(fields.envName, "envName", violations)
                validateFileName(fields.fileName, violations)
                if (fields.askpassUsername != null) {
                    violations += "askpassUsername must be omitted for the file channel"
                }
            }
        }
    }

    /**
     * Appends a violation when a required environment variable name is missing or malformed.
     *
     * @param name Candidate variable name, null when the operator left it out.
     * @param fieldName Field name used in the message.
     * @param violations Collector the violations are appended to.
     */
    private fun validateEnvironmentVariableField(
        name: String?,
        fieldName: String,
        violations: MutableList<String>,
    ) {
        if (name == null) {
            violations += "$fieldName is required for this channel"
            return
        }
        validateVariableName(name, fieldName)?.let { violations += it }
    }

    /**
     * Appends a violation when the file channel's file name is not a single safe path component.
     *
     * @param fileName Candidate file name, null when the operator left it out.
     * @param violations Collector the violation is appended to.
     */
    private fun validateFileName(fileName: String?, violations: MutableList<String>) {
        when {
            fileName == null -> violations += "fileName is required for the file channel"
            fileName.isBlank() -> violations += "fileName must not be blank"
            '/' in fileName || fileName == "." || fileName == ".." ->
                violations += "fileName must be a single path component without '/'"
            // A control character becomes a directory entry nobody can inspect or name again, and NUL
            // additionally truncates the path the helper resolves.
            fileName.any { it.isISOControl() } ->
                violations += "fileName must not contain control characters"
            fileName.length > MAX_FILE_NAME_LENGTH ->
                violations += "fileName must be at most $MAX_FILE_NAME_LENGTH characters"
        }
    }

    /**
     * Appends violations for the profile's own environment map and its collisions.
     *
     * @param fields Profile fields being validated.
     * @param deploymentEnvironmentKeys Keys the deployment sets for every child.
     * @param violations Collector the violations are appended to.
     */
    private fun validateEnvironmentEntries(
        fields: CredentialProfileFields,
        deploymentEnvironmentKeys: Set<String>,
        violations: MutableList<String>,
    ) {
        if (fields.environmentVariables.size > MAX_ENVIRONMENT_ENTRIES) {
            violations +=
                "environmentVariables must contain at most $MAX_ENVIRONMENT_ENTRIES entries"
        }
        fields.environmentVariables.forEach { (name, value) ->
            validateVariableName(name, "environmentVariables")?.let { violations += it }
            // The deployment owns these keys for every child, and a profile entry is merged after the
            // deployment's, so an entry here would silently win.
            if (name in deploymentEnvironmentKeys) {
                violations +=
                    "environmentVariables '$name' collides with worker.commandIsolation.environment.set"
            }
            if (value.length > MAX_ENVIRONMENT_VALUE_LENGTH) {
                violations += "environmentVariables '$name' must be at most " +
                    "$MAX_ENVIRONMENT_VALUE_LENGTH characters"
            }
            if (value.any { it == NUL }) {
                violations += "environmentVariables '$name' must not contain a NUL character"
            }
        }

        val envName = fields.envName
        if (envName != null) {
            if (envName in fields.environmentVariables) {
                violations += "envName '$envName' must not also appear in environmentVariables"
            }
            if (envName in deploymentEnvironmentKeys) {
                // The deployment owns this value for every child; a profile redefining it would
                // silently change what other tools see.
                violations += "envName '$envName' collides with worker.commandIsolation.environment.set"
            }
        }
    }

    /**
     * Appends violations for the askpass variable names and their collisions.
     *
     * @param fields Profile fields being validated.
     * @param violations Collector the violations are appended to.
     */
    private fun validateAskpassVariables(
        fields: CredentialProfileFields,
        violations: MutableList<String>,
    ) {
        if (fields.channel != CredentialChannel.ASKPASS) {
            if (fields.askpassVariables.isNotEmpty()) {
                violations += "askpassVariables must be empty unless the channel is askpass"
            }
            return
        }

        if (fields.askpassVariables.isEmpty()) {
            violations += "askpassVariables must name at least one variable for the askpass channel"
        }
        if (fields.askpassVariables.size > MAX_ENVIRONMENT_ENTRIES) {
            violations += "askpassVariables must contain at most $MAX_ENVIRONMENT_ENTRIES entries"
        }
        if (fields.askpassVariables.size != fields.askpassVariables.toSet().size) {
            violations += "askpassVariables must not repeat a name"
        }
        fields.askpassVariables.forEach { name ->
            validateVariableName(name, "askpassVariables")?.let { violations += it }
            if (name == fields.envName) {
                violations += "askpassVariables '$name' must not also be used as envName"
            }
            if (name in fields.environmentVariables) {
                violations +=
                    "askpassVariables '$name' must not also appear in environmentVariables"
            }
        }
    }

    /** NUL character, which no environment value or argv element may carry. */
    private const val NUL: Char = '\u0000'
}
