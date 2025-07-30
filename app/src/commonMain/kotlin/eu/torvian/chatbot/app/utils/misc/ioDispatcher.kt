package eu.torvian.chatbot.app.utils.misc

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A multiplatform CoroutineDispatcher for I/O-bound operations.
 * On JVM, this will be Dispatchers.IO.
 * On other platforms (like WASM), this will typically be Dispatchers.Default
 * as dedicated I/O threads are not applicable or managed differently.
 */
expect val ioDispatcher: CoroutineDispatcher