package eu.torvian.chatbot.worker.builtin.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [PublicUrlValidator].
 *
 * These tests lock down the public-URL security policy: only http/https with public, routable
 * targets are allowed, while localhost, loopback, private, link-local, and unspecified addresses
 * (whether given as literals or resolved from a hostname via a fake [DnsResolver]) are rejected.
 */
class PublicUrlValidatorTest {

    /** A [DnsResolver] that returns a fixed set of addresses for any hostname. */
    private class FakeDnsResolver(private val addresses: List<InetAddress>) : DnsResolver {
        override fun resolve(host: String): List<InetAddress> = addresses
    }

    private fun validatorFor(addresses: List<InetAddress>): PublicUrlValidator =
        PublicUrlValidator(FakeDnsResolver(addresses))

    private fun ipv4(bytes: IntArray): Inet4Address =
        InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray()) as Inet4Address

    private fun ipv6(words: IntArray): Inet6Address {
        val bytes = words.flatMap { w ->
            listOf((w ushr 8).toByte(), (w and 0xFF).toByte())
        }.toByteArray()
        return InetAddress.getByAddress(bytes) as Inet6Address
    }

    // -----------------------------------------------------------------------------------------
    // Valid public targets
    // -----------------------------------------------------------------------------------------

    @Test
    fun `public https URL with hostname is accepted`() {
        // A hostname that resolves to a public IPv4 address must pass.
        val validator = validatorFor(listOf(ipv4(intArrayOf(93, 184, 216, 34)))) // example.com
        val result = validator.validate("https://example.com/page")
        assertTrue(result.isValid, "expected valid but got: ${result.reason}")
    }

    @Test
    fun `public http URL with IP literal is accepted`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://93.184.216.34/")
        assertTrue(result.isValid, "expected valid but got: ${result.reason}")
    }

    @Test
    fun `public IPv6 literal is accepted`() {
        val validator = validatorFor(emptyList())
        // 2606:2800:220:1:248:1893:25c8:1946 is a documented public IPv6 address (example.com).
        val result = validator.validate("https://[2606:2800:220:1:248:1893:25c8:1946]/")
        assertTrue(result.isValid, "expected valid but got: ${result.reason}")
    }

    @Test
    fun `IPv6 literal is classified directly without DNS`() {
        // The fake resolver is empty; a bracketed IPv6 literal must still be classified
        // (and accepted) purely from the literal, proving no DNS round-trip occurs.
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://[2606:2800:220:1:248:1893:25c8:1946]/")
        assertTrue(result.isValid, "IPv6 literal must not require DNS: ${result.reason}")
    }

    // -----------------------------------------------------------------------------------------
    // Scheme / shape rejections
    // -----------------------------------------------------------------------------------------

    @Test
    fun `ftp scheme is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("ftp://example.com/file")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("scheme", ignoreCase = true))
    }

    @Test
    fun `file scheme is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("file:///etc/passwd")
        assertFalse(result.isValid)
    }

    @Test
    fun `malformed URL is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("not a url ::::")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("malformed", ignoreCase = true))
    }

    @Test
    fun `URL without host is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("https:///foo")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("host", ignoreCase = true))
    }

    // -----------------------------------------------------------------------------------------
    // Local / private / non-public rejections (literals)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `localhost hostname is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://localhost/")
        assertFalse(result.isValid)
    }

    @Test
    fun `loopback IPv4 literal is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://127.0.0.1/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("loopback", ignoreCase = true))
    }

    @Test
    fun `loopback IPv6 literal is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://[::1]/")
        assertFalse(result.isValid)
    }

    @Test
    fun `private IPv4 10-dot is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://10.0.0.5/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("private", ignoreCase = true))
    }

    @Test
    fun `private IPv4 172-16 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://172.16.5.4/")
        assertFalse(result.isValid)
    }

    @Test
    fun `private IPv4 192-168 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://192.168.1.1/")
        assertFalse(result.isValid)
    }

    @Test
    fun `link-local IPv4 169-254 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://169.254.1.1/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("link-local", ignoreCase = true))
    }

    @Test
    fun `unspecified IPv4 0-0-0-0 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://0.0.0.0/")
        assertFalse(result.isValid)
    }

    @Test
    fun `link-local IPv6 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://[fe80::1]/")
        assertFalse(result.isValid)
    }

    @Test
    fun `unique-local IPv6 is rejected`() {
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://[fc00::1]/")
        assertFalse(result.isValid)
    }

    // -----------------------------------------------------------------------------------------
    // DNS-resolved rejections (fake resolver)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `hostname resolved to private address is rejected`() {
        // The literal host looks public, but the resolver returns a private address.
        val validator = validatorFor(listOf(ipv4(intArrayOf(10, 0, 0, 1))))
        val result = validator.validate("http://public-looking.example/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("private", ignoreCase = true))
    }

    @Test
    fun `hostname resolved to loopback address is rejected`() {
        val validator = validatorFor(listOf(ipv4(intArrayOf(127, 0, 0, 1))))
        val result = validator.validate("http://cdn.example/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("loopback", ignoreCase = true))
    }

    @Test
    fun `hostname resolved to link-local address is rejected`() {
        val validator = validatorFor(listOf(ipv4(intArrayOf(169, 254, 0, 1))))
        val result = validator.validate("http://meta.example/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("link-local", ignoreCase = true))
    }

    @Test
    fun `hostname resolved to public address is accepted`() {
        val validator = validatorFor(listOf(ipv4(intArrayOf(203, 0, 113, 7)))) // TEST-NET-3, public space
        val result = validator.validate("http://api.example/")
        assertTrue(result.isValid, "expected valid but got: ${result.reason}")
    }

    @Test
    fun `resolved private hostname is flagged as a security rejection`() {
        // The rejection must be distinguishable from a shape/parse failure (InvalidUrl).
        val validator = validatorFor(listOf(ipv4(intArrayOf(10, 0, 0, 1))))
        val result = validator.validate("http://public-looking.example/")
        assertFalse(result.isValid)
        assertTrue(result.isSecurityRejection, "resolved-private host must be a SecurityRejection")
    }

    @Test
    fun `multicast address is rejected`() {
        // 224.0.0.1 is a well-known IPv4 multicast address.
        val validator = validatorFor(emptyList())
        val result = validator.validate("http://224.0.0.1/")
        assertFalse(result.isValid)
        assertTrue(result.reason!!.contains("multicast", ignoreCase = true))
    }
}
