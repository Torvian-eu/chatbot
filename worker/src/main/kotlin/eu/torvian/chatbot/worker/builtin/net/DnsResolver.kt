package eu.torvian.chatbot.worker.builtin.net

import java.net.InetAddress

/**
 * Abstraction over hostname-to-address resolution.
 *
 * Decoupling DNS lookup behind an interface keeps the public-URL validation and web-fetch logic
 * testable: tests can supply a fake resolver that returns predetermined addresses (including
 * private or loopback ones) without touching the real network stack, and production code uses the
 * JVM's normal resolution.
 */
interface DnsResolver {

    /**
     * Resolves [host] to all addresses known to the system.
     *
     * @param host A hostname (not an IP literal). IP literals are inspected directly by the caller
     *   and never passed here.
     * @return All resolved [InetAddress]es.
     * @throws java.net.UnknownHostException If the host cannot be resolved.
     */
    fun resolve(host: String): List<InetAddress>
}

/**
 * Production [DnsResolver] backed by the JVM's default name service.
 */
class JvmDnsResolver : DnsResolver {
    override fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()
}

