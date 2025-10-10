package eu.torvian.chatbot.common.api

import kotlin.jvm.JvmInline

/**
 * Type-safe, extensible access mode for resource authorization.
 *
 * Use AccessMode.of("custom") to create custom modes. Prefer the
 * predefined constants [AccessMode.READ] and [AccessMode.WRITE].
 */
@JvmInline
value class AccessMode private constructor(val key: String) {
    override fun toString(): String = key

    companion object {
        fun of(key: String): AccessMode = AccessMode(key)

        /**
         * Read access mode. Allows viewing and querying a resource.
         */
        val READ: AccessMode = of("read")

        /**
         * Write access mode. Allows modifying a resource.
         */
        val WRITE: AccessMode = of("write")

        /**
         * Manage access mode. Allows granting and revoking access to a resource.
         */
        val MANAGE: AccessMode = of("manage")
    }
}

