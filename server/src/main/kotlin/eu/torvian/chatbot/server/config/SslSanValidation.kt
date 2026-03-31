package eu.torvian.chatbot.server.config

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.server.domain.config.SslConfig
import java.net.IDN
import java.net.InetAddress

/**
 * Parses and validates SSL DNS Subject Alternative Name entries.
 *
 * When [sanDomains] is null, defaults from [SslConfig.DEFAULT_SAN_DOMAINS] are used.
 * Values are trimmed, validated, and deduplicated while preserving first-seen order.
 *
 * @return [Either.Right] with normalized domain entries, or [Either.Left] with a validation error.
 */
internal fun parseSanDomains(sanDomains: List<String>?): Either<ConfigError.ValidationError, List<String>> = either {
    val configured = sanDomains ?: SslConfig.DEFAULT_SAN_DOMAINS
//    ensure(configured.isNotEmpty()) {
//        ConfigError.ValidationError.InvalidValue("ssl.sanDomains", "At least one domain is required")
//    }

    configured.mapIndexed { index, value ->
        val domain = value.trim()
        ensure(domain.isNotEmpty()) {
            ConfigError.ValidationError.InvalidValue("ssl.sanDomains[$index]", "Domain must not be blank")
        }
        ensure(isValidSanDomain(domain)) {
            ConfigError.ValidationError.InvalidValue("ssl.sanDomains[$index]", "Invalid domain '$domain'")
        }
        domain
    }.distinct()
}

/**
 * Parses and validates SSL IP Subject Alternative Name entries.
 *
 * When [sanIpAddresses] is null, defaults from [SslConfig.DEFAULT_SAN_IP_ADDRESSES] are used.
 * Values are trimmed, validated, and deduplicated while preserving first-seen order.
 *
 * @return [Either.Right] with normalized IP entries, or [Either.Left] with a validation error.
 */
internal fun parseSanIpAddresses(sanIpAddresses: List<String>?): Either<ConfigError.ValidationError, List<String>> = either {
    val configured = sanIpAddresses ?: SslConfig.DEFAULT_SAN_IP_ADDRESSES
//    ensure(configured.isNotEmpty()) {
//        ConfigError.ValidationError.InvalidValue("ssl.sanIpAddresses", "At least one IP address is required")
//    }

    configured.mapIndexed { index, value ->
        val ipAddress = value.trim()
        ensure(ipAddress.isNotEmpty()) {
            ConfigError.ValidationError.InvalidValue("ssl.sanIpAddresses[$index]", "IP address must not be blank")
        }
        ensure(isValidIpAddress(ipAddress)) {
            ConfigError.ValidationError.InvalidValue("ssl.sanIpAddresses[$index]", "Invalid IP address '$ipAddress'")
        }
        ipAddress
    }.distinct()
}

/**
 * Validates whether the provided value is a valid DNS name for SAN usage.
 *
 * A wildcard prefix (`*.`) is accepted and validated against the remaining label.
 */
private fun isValidSanDomain(domain: String): Boolean {
    val value = if (domain.startsWith("*.")) domain.removePrefix("*.") else domain
    return runCatching {
        val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
        ascii.isNotBlank() && !ascii.startsWith('.') && !ascii.endsWith('.')
    }.getOrDefault(false)
}

/**
 * Validates whether the provided value is a valid IPv4 or IPv6 address for SAN usage.
 */
private fun isValidIpAddress(value: String): Boolean {
    if (':' in value) {
        return runCatching { InetAddress.getByName(value) }.isSuccess
    }
    val ipv4Regex = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    return ipv4Regex.matches(value)
}
