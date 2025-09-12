package eu.torvian.chatbot.app.utils.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LruCacheTest {

    @Test
    fun `constructor should require positive max size`() {
        assertFailsWith<IllegalArgumentException> {
            LruCache<String, String>(0)
        }
        assertFailsWith<IllegalArgumentException> {
            LruCache<String, String>(-1)
        }
        // Should not throw
        LruCache<String, String>(1)
    }

    @Test
    fun `get should return null for non-existent key`() {
        val cache = LruCache<String, String>(5)
        assertNull(cache["nonexistent"])
    }

    @Test
    fun `put and get should work for basic operations`() {
        val cache = LruCache<String, String>(5)

        assertNull(cache.put("key1", "value1"))
        assertEquals("value1", cache["key1"])

        // Update existing key
        assertEquals("value1", cache.put("key1", "newValue1"))
        assertEquals("newValue1", cache["key1"])
    }

    @Test
    fun `size and isEmpty should work correctly`() {
        val cache = LruCache<String, String>(5)

        assertTrue(cache.isEmpty())
        assertEquals(0, cache.size)

        cache.put("key1", "value1")
        assertFalse(cache.isEmpty())
        assertEquals(1, cache.size)

        cache.put("key2", "value2")
        assertEquals(2, cache.size)

        cache.remove("key1")
        assertEquals(1, cache.size)

        cache.clear()
        assertTrue(cache.isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun `containsKey should work correctly`() {
        val cache = LruCache<String, String>(5)

        assertFalse(cache.containsKey("key1"))

        cache.put("key1", "value1")
        assertTrue(cache.containsKey("key1"))

        cache.remove("key1")
        assertFalse(cache.containsKey("key1"))
    }

    @Test
    fun `remove should return correct value and remove entry`() {
        val cache = LruCache<String, String>(5)

        assertNull(cache.remove("nonexistent"))

        cache.put("key1", "value1")
        assertEquals("value1", cache.remove("key1"))
        assertNull(cache["key1"])
        assertEquals(0, cache.size)
    }

    @Test
    fun `eviction should occur when cache exceeds max size`() {
        val cache = LruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(2, cache.size)

        // Adding third item should evict the first (least recently used)
        cache.put("key3", "value3")
        assertEquals(2, cache.size)
        assertNull(cache["key1"]) // Should be evicted
        assertEquals("value2", cache["key2"])
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun `access should update LRU order`() {
        val cache = LruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")

        // Access key1 to make it most recently used
        cache["key1"]

        // Add key3, which should evict key2 (now least recently used)
        cache.put("key3", "value3")

        assertEquals("value1", cache["key1"]) // Should still be there
        assertNull(cache["key2"]) // Should be evicted
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun `update should move item to most recently used position`() {
        val cache = LruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")

        // Update key1, making it most recently used
        cache.put("key1", "updatedValue1")

        // Add key3, which should evict key2 (now least recently used)
        cache.put("key3", "value3")

        assertEquals("updatedValue1", cache["key1"]) // Should still be there
        assertNull(cache["key2"]) // Should be evicted
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun `cache with max size 1 should work correctly`() {
        val cache = LruCache<String, String>(1)

        cache.put("key1", "value1")
        assertEquals("value1", cache["key1"])
        assertEquals(1, cache.size)

        // Adding second item should evict the first
        cache.put("key2", "value2")
        assertEquals(1, cache.size)
        assertNull(cache["key1"])
        assertEquals("value2", cache["key2"])
    }

    @Test
    fun `clear should remove all entries`() {
        val cache = LruCache<String, String>(5)

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")
        assertEquals(3, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertTrue(cache.isEmpty())
        assertNull(cache["key1"])
        assertNull(cache["key2"])
        assertNull(cache["key3"])
    }

    @Test
    fun `multiple operations should maintain correct LRU order`() {
        val cache = LruCache<Int, String>(3)

        cache.put(1, "one")
        cache.put(2, "two")
        cache.put(3, "three")

        // Access 1 to make it most recently used
        cache[1]

        // Update 2 to make it most recently used
        cache.put(2, "TWO")

        // Current order (most to least recent): 2, 1, 3
        // Adding 4 should evict 3
        cache.put(4, "four")

        assertEquals("TWO", cache[2])
        assertEquals("one", cache[1])
        assertNull(cache[3]) // Should be evicted
        assertEquals("four", cache[4])
    }

    @Test
    fun `operator get should work the same as regular get`() {
        val cache = LruCache<String, String>(5)

        cache.put("key1", "value1")

        // Both should work and return the same result
        assertEquals(cache["key1"], cache["key1"])
        assertEquals("value1", cache["key1"])
        assertNull(cache["nonexistent"])
    }

    @Test
    fun `getOrPut should return existing value when key exists and not call lambda`() {
        val cache = LruCache<String, String>(5)
        cache.put("key1", "existingValue")

        var lambdaCalled = false
        val result = cache.getOrPut("key1") {
            lambdaCalled = true
            "newValue"
        }

        assertEquals("existingValue", result)
        assertFalse(lambdaCalled, "Lambda should not be called when key exists")
        assertEquals(1, cache.size)
    }

    @Test
    fun `getOrPut should call lambda and insert new value when key does not exist`() {
        val cache = LruCache<String, String>(5)

        var lambdaCalled = false
        val result = cache.getOrPut("key1") {
            lambdaCalled = true
            "newValue"
        }

        assertEquals("newValue", result)
        assertTrue(lambdaCalled, "Lambda should be called when key does not exist")
        assertEquals(1, cache.size)
        assertEquals("newValue", cache["key1"])
    }

    @Test
    fun `getOrPut should maintain LRU order when accessing existing key`() {
        val cache = LruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")

        // Access key1 via getOrPut to make it most recently used
        val result = cache.getOrPut("key1") { "shouldNotBeCalled" }
        assertEquals("value1", result)

        // Add key3, which should evict key2 (now least recently used)
        cache.put("key3", "value3")

        assertEquals("value1", cache["key1"]) // Should still be there
        assertNull(cache["key2"]) // Should be evicted
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun `getOrPut should maintain LRU order when inserting new key`() {
        val cache = LruCache<String, String>(2)

        cache.put("key1", "value1")
        cache.put("key2", "value2")

        // Use getOrPut to add key3, which should evict key1 (least recently used)
        val result = cache.getOrPut("key3") { "value3" }
        assertEquals("value3", result)

        assertEquals(2, cache.size)
        assertNull(cache["key1"]) // Should be evicted
        assertEquals("value2", cache["key2"])
        assertEquals("value3", cache["key3"])
    }

    @Test
    fun `getOrPut should work correctly with cache size 1`() {
        val cache = LruCache<String, String>(1)

        // First insertion
        val result1 = cache.getOrPut("key1") { "value1" }
        assertEquals("value1", result1)
        assertEquals(1, cache.size)

        // Second insertion should evict the first
        val result2 = cache.getOrPut("key2") { "value2" }
        assertEquals("value2", result2)
        assertEquals(1, cache.size)
        assertNull(cache["key1"])
        assertEquals("value2", cache["key2"])
    }

    @Test
    fun `getOrPut should work with null values from lambda`() {
        val cache = LruCache<String, String?>(5)

        val result = cache.getOrPut("key1") { null }
        assertNull(result)
        assertEquals(1, cache.size)
        assertTrue(cache.containsKey("key1"))
        assertNull(cache["key1"])
    }
}