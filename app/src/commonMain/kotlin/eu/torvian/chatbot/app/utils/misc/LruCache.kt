package eu.torvian.chatbot.app.utils.misc

/**
 * A high-performance, generic, non-thread-safe, KMP-compatible LRU (Least Recently Used) cache.
 *
 * This implementation uses a HashMap for O(1) lookups and a custom doubly linked list
 * to maintain the access order in O(1) time.
 *
 * - When an element is accessed (`get`) or updated (`put`), its corresponding node is moved
 *   to the head of the linked list.
 * - When a new element is added and the cache is full, the node at the tail of the list
 *   (the least recently used) is evicted.
 *
 * @param K The type of the keys.
 * @param V The type of the values.
 * @property maxSize The maximum number of items this cache can hold.
 */
class LruCache<K, V>(private val maxSize: Int) {

    private class Node<K, V>(val key: K, var value: V) {
        var prev: Node<K, V>? = null
        var next: Node<K, V>? = null
    }

    private val map = HashMap<K, Node<K, V>>()
    private var head: Node<K, V>? = null
    private var tail: Node<K, V>? = null

    init {
        require(maxSize > 0) { "Cache max size must be positive" }
    }

    /**
     * Retrieves a value from the cache. This is an O(1) operation.
     *
     * @param key The key to look up
     * @return The value associated with the key, or null if not found
     */
    operator fun get(key: K): V? {
        val node = map[key] ?: return null
        moveToHead(node)
        return node.value
    }

    /**
     * Inserts or updates a value in the cache. This is an O(1) operation.
     *
     * @param key The key to insert or update
     * @param value The value to associate with the key
     * @return The previous value associated with the key, or null if there was no previous value
     */
    fun put(key: K, value: V): V? {
        val existingNode = map[key]
        return if (existingNode != null) {
            val oldValue = existingNode.value
            existingNode.value = value
            moveToHead(existingNode)
            oldValue
        } else {
            val newNode = Node(key, value)
            map[key] = newNode
            addNodeToHead(newNode)

            if (map.size > maxSize) {
                evictTail()
            }
            null
        }
    }

    /**
     * Removes an entry from the cache. This is an O(1) operation.
     *
     * @param key The key to remove
     * @return The value that was associated with the key, or null if the key was not found
     */
    fun remove(key: K): V? {
        val node = map.remove(key) ?: return null
        removeNode(node)
        return node.value
    }

    /**
     * Clears all entries from the cache.
     */
    fun clear() {
        map.clear()
        head = null
        tail = null
    }

    /**
     * Returns the current size of the cache.
     */
    val size: Int get() = map.size

    /**
     * Returns true if the cache is empty.
     */
    fun isEmpty(): Boolean = map.isEmpty()

    /**
     * Returns true if the cache contains the specified key.
     */
    fun containsKey(key: K): Boolean = map.containsKey(key)

    /**
     * Returns the value for the given key. If the key is not found in the cache,
     * calls the `defaultValue` function, puts its result into the cache, and returns it.
     *
     * This function is atomic in the sense that the cache is guaranteed to be unchanged
     * between the moment the key is not found and the moment the new value is inserted.
     * This atomicity is handled by the external lock (e.g., Mutex) that must wrap this call.
     *
     * @param key The key to look up.
     * @param defaultValue A lambda that returns the value to be inserted if the key is not present.
     * @return The value from the cache or the newly inserted value.
     */
    inline fun getOrPut(key: K, defaultValue: () -> V): V {
        // Calling our own get() is crucial as it handles moving the node to the head.
        val value = get(key)
        return if (value == null) {
            val newValue = defaultValue()
            // Our put() handles adding the new node to the head and evicting the tail if necessary.
            put(key, newValue)
            newValue
        } else {
            value
        }
    }

    // --- Internal Doubly Linked List Operations ---

    private fun addNodeToHead(node: Node<K, V>) {
        node.next = head
        node.prev = null
        head?.prev = node
        head = node
        if (tail == null) {
            tail = node
        }
    }

    private fun removeNode(node: Node<K, V>) {
        val prev = node.prev
        val next = node.next

        if (prev != null) {
            prev.next = next
        } else {
            head = next
        }

        if (next != null) {
            next.prev = prev
        } else {
            tail = prev
        }
    }

    private fun moveToHead(node: Node<K, V>) {
        if (node == head) return
        removeNode(node)
        addNodeToHead(node)
    }

    private fun evictTail() {
        val currentTail = tail ?: return
        map.remove(currentTail.key)
        removeNode(currentTail)
    }
}