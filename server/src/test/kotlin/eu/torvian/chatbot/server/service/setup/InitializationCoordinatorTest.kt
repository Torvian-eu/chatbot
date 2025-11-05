package eu.torvian.chatbot.server.service.setup

import arrow.core.left
import arrow.core.right
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [InitializationCoordinator].
 *
 * This test suite verifies the coordination logic:
 * - Running multiple initializers in sequence
 * - Handling errors from initializers
 * - Skipping already-initialized components
 * - Checking overall initialization status
 *
 * Uses MockK to isolate coordination logic from actual initialization implementations.
 */
class InitializationCoordinatorTest {

    @Test
    fun `runAllInitializers should call all initializers in order`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.name } returns "Initializer 1"
        coEvery { initializer1.isInitialized() } returns false
        coEvery { initializer1.initialize() } returns Unit.right()

        coEvery { initializer2.name } returns "Initializer 2"
        coEvery { initializer2.isInitialized() } returns false
        coEvery { initializer2.initialize() } returns Unit.right()

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.runAllInitializers()

        // Then
        assertTrue(result.isRight(), "Expected successful initialization")
        coVerify(exactly = 1) { initializer1.isInitialized() }
        coVerify(exactly = 1) { initializer1.initialize() }
        coVerify(exactly = 1) { initializer2.isInitialized() }
        coVerify(exactly = 1) { initializer2.initialize() }
    }

    @Test
    fun `runAllInitializers should skip already initialized components`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.name } returns "Initializer 1"
        coEvery { initializer1.isInitialized() } returns true // Already initialized

        coEvery { initializer2.name } returns "Initializer 2"
        coEvery { initializer2.isInitialized() } returns false
        coEvery { initializer2.initialize() } returns Unit.right()

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.runAllInitializers()

        // Then
        assertTrue(result.isRight(), "Expected successful initialization")
        coVerify(exactly = 1) { initializer1.isInitialized() }
        coVerify(exactly = 0) { initializer1.initialize() } // Should NOT be called
        coVerify(exactly = 1) { initializer2.isInitialized() }
        coVerify(exactly = 1) { initializer2.initialize() }
    }

    @Test
    fun `runAllInitializers should stop and return error if an initializer fails`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.name } returns "Initializer 1"
        coEvery { initializer1.isInitialized() } returns false
        coEvery { initializer1.initialize() } returns "Something went wrong".left()

        coEvery { initializer2.name } returns "Initializer 2"

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.runAllInitializers()
        val error = result.leftOrNull()

        // Then
        assertNotNull(error, "Expected failure")
        assertTrue(error.contains("Initializer 1"), "Error should mention the failed initializer")
        assertTrue(error.contains("Something went wrong"), "Error should include the original message")


        coVerify(exactly = 1) { initializer1.initialize() }
        coVerify(exactly = 0) { initializer2.isInitialized() } // Should NOT be called after failure
        coVerify(exactly = 0) { initializer2.initialize() }
    }

    @Test
    fun `runAllInitializers should handle empty initializer list`() = runTest {
        // Given
        val coordinator = InitializationCoordinator(emptyList())

        // When
        val result = coordinator.runAllInitializers()

        // Then
        assertTrue(result.isRight(), "Expected successful initialization with empty list")
    }

    @Test
    fun `isFullyInitialized should return true when all initializers are initialized`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.isInitialized() } returns true
        coEvery { initializer2.isInitialized() } returns true

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.isFullyInitialized()

        // Then
        assertTrue(result, "Expected fully initialized when all initializers are done")
    }

    @Test
    fun `isFullyInitialized should return false when any initializer is not initialized`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.isInitialized() } returns true
        coEvery { initializer2.isInitialized() } returns false // Not initialized

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.isFullyInitialized()

        // Then
        assertFalse(result, "Expected not fully initialized when any initializer is not done")
    }

    @Test
    fun `isFullyInitialized should return true for empty initializer list`() = runTest {
        // Given
        val coordinator = InitializationCoordinator(emptyList())

        // When
        val result = coordinator.isFullyInitialized()

        // Then
        assertTrue(result, "Expected fully initialized with empty list")
    }

    @Test
    fun `runAllInitializers should be idempotent when all are already initialized`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.name } returns "Initializer 1"
        coEvery { initializer1.isInitialized() } returns true

        coEvery { initializer2.name } returns "Initializer 2"
        coEvery { initializer2.isInitialized() } returns true

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.runAllInitializers()

        // Then
        assertTrue(result.isRight(), "Expected successful run")
        coVerify(exactly = 0) { initializer1.initialize() }
        coVerify(exactly = 0) { initializer2.initialize() }
    }

    @Test
    fun `runAllInitializers should initialize only the second when first is done`() = runTest {
        // Given
        val initializer1 = mockk<DataInitializer>()
        val initializer2 = mockk<DataInitializer>()

        coEvery { initializer1.name } returns "Initializer 1"
        coEvery { initializer1.isInitialized() } returns true

        coEvery { initializer2.name } returns "Initializer 2"
        coEvery { initializer2.isInitialized() } returns false
        coEvery { initializer2.initialize() } returns Unit.right()

        val coordinator = InitializationCoordinator(listOf(initializer1, initializer2))

        // When
        val result = coordinator.runAllInitializers()

        // Then
        assertTrue(result.isRight(), "Expected successful initialization")
        coVerify(exactly = 0) { initializer1.initialize() }
        coVerify(exactly = 1) { initializer2.initialize() }
    }
}

