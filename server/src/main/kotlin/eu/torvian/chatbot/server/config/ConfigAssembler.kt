package eu.torvian.chatbot.server.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.*
import eu.torvian.chatbot.server.domain.security.JwtConfig

/**
 * Deep-merge and assembly helpers.
 *
 * This file provides:
 * - extension merge functions that combine two nullable DTOs (overlay wins),
 * - the strict `toDomain()` assembler that validates required fields and converts
 *   the merged DTOs into domain configuration objects.
 */

/**
 * Merge two [AppConfigDto] values producing a new DTO where values from [other]
 * have precedence over values from the receiver.
 *
 * Example usage:
 * val merged = baseDto.merge(secretsDto).merge(setupDto).merge(envDto)
 *
 * @param other DTO to overlay on top of this one (may be null).
 * @return new [AppConfigDto] containing merged values.
 */
fun AppConfigDto.merge(other: AppConfigDto?): AppConfigDto = AppConfigDto(
    setup = SetupConfigDto(other?.setup?.required ?: setup?.required),
    storage = mergeStorage(storage, other?.storage),
    network = mergeNetwork(network, other?.network),
    ssl = mergeSsl(ssl, other?.ssl),
    database = mergeDatabase(database, other?.database),
    encryption = mergeEncryption(encryption, other?.encryption),
    jwt = mergeJwt(jwt, other?.jwt)
)

/**
 * Merge helper for the Storage block.
 *
 * @param base Base storage DTO
 * @param overlay Overlay storage DTO (takes precedence)
 * @return merged [StorageConfigDto]
 */
private fun mergeStorage(base: StorageConfigDto?, overlay: StorageConfigDto?) = StorageConfigDto(
    dataDir = overlay?.dataDir ?: base?.dataDir,
    databaseFilename = overlay?.databaseFilename ?: base?.databaseFilename,
    keystoreFilename = overlay?.keystoreFilename ?: base?.keystoreFilename,
    logsDir = overlay?.logsDir ?: base?.logsDir
)

/**
 * Merge helper for the Network block.
 *
 * @param base Base network DTO
 * @param overlay Overlay network DTO (takes precedence)
 * @return merged [NetworkConfigDto]
 */
private fun mergeNetwork(base: NetworkConfigDto?, overlay: NetworkConfigDto?) = NetworkConfigDto(
    host = overlay?.host ?: base?.host,
    port = overlay?.port ?: base?.port,
    path = overlay?.path ?: base?.path,
    connectorType = overlay?.connectorType ?: base?.connectorType
)

/**
 * Merge helper for SSL DTOs. Overlay values win, nulls are ignored.
 *
 * @param base Base SSL DTO
 * @param overlay Overlay SSL DTO (takes precedence)
 * @return merged [SslConfigDto]
 */
private fun mergeSsl(base: SslConfigDto?, overlay: SslConfigDto?) = SslConfigDto(
    port = overlay?.port ?: base?.port,
    keystorePassword = overlay?.keystorePassword ?: base?.keystorePassword,
    keyAlias = overlay?.keyAlias ?: base?.keyAlias,
    keyPassword = overlay?.keyPassword ?: base?.keyPassword,
    generateSelfSigned = overlay?.generateSelfSigned ?: base?.generateSelfSigned,
    sanDomains = overlay?.sanDomains ?: base?.sanDomains,
    sanIpAddresses = overlay?.sanIpAddresses ?: base?.sanIpAddresses
)

/**
 * Merge helper for database DTOs.
 *
 * @param base Base database DTO
 * @param overlay Overlay database DTO (takes precedence)
 * @return merged [DatabaseConfigDto]
 */
private fun mergeDatabase(base: DatabaseConfigDto?, overlay: DatabaseConfigDto?) = DatabaseConfigDto(
    vendor = overlay?.vendor ?: base?.vendor,
    type = overlay?.type ?: base?.type,
    user = overlay?.user ?: base?.user,
    password = overlay?.password ?: base?.password
)

/**
 * Merge helper for encryption DTOs. Master keys are merged with overlay keys overwriting base keys.
 *
 * @param base Base encryption DTO
 * @param overlay Overlay encryption DTO (takes precedence)
 * @return merged [EncryptionConfigDto]
 */
private fun mergeEncryption(base: EncryptionConfigDto?, overlay: EncryptionConfigDto?) = EncryptionConfigDto(
    keyVersion = overlay?.keyVersion ?: base?.keyVersion,
    masterKeys = (base?.masterKeys ?: emptyMap()) + (overlay?.masterKeys ?: emptyMap()),
    algorithm = overlay?.algorithm ?: base?.algorithm,
    transformation = overlay?.transformation ?: base?.transformation,
    keySizeBits = overlay?.keySizeBits ?: base?.keySizeBits
)

/**
 * Merge helper for JWT DTOs.
 *
 * @param base Base JWT DTO
 * @param overlay Overlay JWT DTO (takes precedence)
 * @return merged [JwtConfigDto]
 */
private fun mergeJwt(base: JwtConfigDto?, overlay: JwtConfigDto?) = JwtConfigDto(
    issuer = overlay?.issuer ?: base?.issuer,
    audience = overlay?.audience ?: base?.audience,
    realm = overlay?.realm ?: base?.realm,
    secret = overlay?.secret ?: base?.secret,
    tokenExpirationMs = overlay?.tokenExpirationMs ?: base?.tokenExpirationMs,
    refreshExpirationMs = overlay?.refreshExpirationMs ?: base?.refreshExpirationMs
)

/**
 * Convert the merged [AppConfigDto] to a strict domain [AppConfiguration].
 *
 * @param baseApplicationPath The parent directory of the config directory, used as the base for
 *                            resolving the data directory. Config and data are always siblings.
 * @return Either a [ConfigError.ValidationError] on failure or the valid [AppConfiguration] on success.
 */
fun AppConfigDto.toDomain(baseApplicationPath: String): Either<ConfigError.ValidationError, AppConfiguration> = either {
    val storageConfig = parseStorage(storage, baseApplicationPath)

    AppConfiguration(
        setupRequired = required("setup.required", setup?.required),
        storage = storageConfig,
        network = parseNetwork(network),
        ssl = parseSsl(ssl, network?.connectorType, storageConfig),
        database = parseDatabase(database, storageConfig),
        encryption = parseEncryption(encryption),
        jwt = parseJwt(jwt)
    )
}

/**
 * Parse and validate [StorageConfigDto] into [StorageConfig].
 *
 * @param dto Nullable DTO for storage.
 * @param baseApplicationPath The parent directory of the config directory.
 * @return The parsed storage configuration.
 */
private fun Raise<ConfigError.ValidationError>.parseStorage(
    dto: StorageConfigDto?,
    baseApplicationPath: String
) = StorageConfig(
    baseApplicationPath = baseApplicationPath,
    dataDir = required("storage.dataDir", dto?.dataDir),
    databaseFilename = required("storage.databaseFilename", dto?.databaseFilename),
    keystoreFilename = required("storage.keystoreFilename", dto?.keystoreFilename),
    logsDir = required("storage.logsDir", dto?.logsDir)
)

/**
 * Parse and validate [NetworkConfigDto] into [NetworkConfig].
 *
 * Ensures connectorType is one of the allowed values and that numeric values are in valid ranges.
 *
 * @param dto Nullable DTO for network; a MissingKey error is raised if null.
 * @return The parsed network configuration.
 */
private fun Raise<ConfigError.ValidationError>.parseNetwork(dto: NetworkConfigDto?): NetworkConfig {
    val d = dto ?: raise(ConfigError.ValidationError.MissingKey("network"))

    val typeStr = required("network.connectorType", d.connectorType)
    val connectorType = ServerConnectorType.fromString(typeStr)
        ?: raise(ConfigError.ValidationError.InvalidValue("network.connectorType", "Allowed: HTTP, HTTPS, HTTP_AND_HTTPS"))

    val port = required("network.port", d.port)
    ensure(port in 1..65535) {
        ConfigError.ValidationError.InvalidValue("network.port", "Port must be between 1 and 65535")
    }

    return NetworkConfig(
        host = required("network.host", d.host),
        port = port,
        path = required("network.path", d.path),
        connectorType = connectorType
    )
}

/**
 * Parse and validate [SslConfigDto] into [SslConfig].
 *
 * SSL is required when connectorType is HTTPS or HTTP_AND_HTTPS.
 * The keystorePath is resolved from [StorageConfig], not from the DTO.
 *
 * @param dto Nullable DTO for SSL.
 * @param connectorType The connector type string from network config.
 * @param storageConfig The resolved storage configuration, used to derive keystorePath.
 * @return The parsed SSL configuration, or null if not required.
 */
private fun Raise<ConfigError.ValidationError>.parseSsl(
    dto: SslConfigDto?,
    connectorType: String?,
    storageConfig: StorageConfig
): SslConfig? {
    val typeStr = connectorType ?: "HTTP"
    val connType = ServerConnectorType.fromString(typeStr)

    if (connType == ServerConnectorType.HTTP) return null

    // For HTTPS or HTTP_AND_HTTPS, SSL configuration is mandatory
    val s = dto ?: raise(ConfigError.ValidationError.MissingKey("ssl (required for $typeStr)"))

    val port = required("ssl.port", s.port)
    ensure(port in 1..65535) {
        ConfigError.ValidationError.InvalidValue("ssl.port", "Port must be between 1 and 65535")
    }

    return SslConfig(
        port = port,
        keystorePath = storageConfig.keystorePath,
        keystorePassword = required("ssl.keystorePassword", s.keystorePassword),
        keyAlias = required("ssl.keyAlias", s.keyAlias),
        keyPassword = required("ssl.keyPassword", s.keyPassword),
        generateSelfSigned = s.generateSelfSigned ?: true,
        sanDomains = parseSanDomains(s.sanDomains).bind(),
        sanIpAddresses = parseSanIpAddresses(s.sanIpAddresses).bind()
    )
}

/**
 * Parse and validate database DTO into domain [DatabaseConfig].
 *
 * Required fields: vendor, type.
 * The filepath (full path to the db file) is resolved from [StorageConfig.databasePath].
 *
 * @param dto Nullable DTO for the database.
 * @param storageConfig The resolved storage configuration, used to derive the full filepath.
 * @return The parsed database configuration.
 */
private fun Raise<ConfigError.ValidationError>.parseDatabase(
    dto: DatabaseConfigDto?,
    storageConfig: StorageConfig
) = DatabaseConfig(
    vendor = required("database.vendor", dto?.vendor),
    type = required("database.type", dto?.type),
    filepath = storageConfig.databasePath,
    user = dto?.user,
    password = dto?.password
)

/**
 * Parse and validate encryption DTO into domain [EncryptionConfig].
 *
 * @param dto Nullable DTO for encryption.
 * @return The parsed encryption configuration.
 */
private fun Raise<ConfigError.ValidationError>.parseEncryption(dto: EncryptionConfigDto?) = EncryptionConfig(
    keyVersion = required("encryption.keyVersion", dto?.keyVersion),
    masterKeys = required("encryption.masterKeys", dto?.masterKeys),
    algorithm = dto?.algorithm,
    transformation = dto?.transformation,
    keySizeBits = dto?.keySizeBits
)

/**
 * Parse and validate JWT DTO into domain [JwtConfig].
 *
 * @param dto Nullable DTO for JWT.
 * @return The parsed JWT configuration.
 */
private fun Raise<ConfigError.ValidationError>.parseJwt(dto: JwtConfigDto?) = JwtConfig(
    issuer = required("jwt.issuer", dto?.issuer),
    audience = required("jwt.audience", dto?.audience),
    realm = required("jwt.realm", dto?.realm),
    secret = required("jwt.secret", dto?.secret),
    tokenExpirationMs = required("jwt.tokenExpirationMs", dto?.tokenExpirationMs),
    refreshExpirationMs = required("jwt.refreshExpirationMs", dto?.refreshExpirationMs)
)

/**
 * Helper that raises a [ConfigError.ValidationError.MissingKey] when the given [value] is null.
 *
 * @param path Dotted path used for error messaging.
 * @param value The value to assert non-null.
 * @return The non-null [T].
 * @throws ConfigError.ValidationError.MissingKey if [value] is null.
 */
private fun <T : Any> Raise<ConfigError.ValidationError>.required(path: String, value: T?): T =
    value ?: raise(ConfigError.ValidationError.MissingKey(path))