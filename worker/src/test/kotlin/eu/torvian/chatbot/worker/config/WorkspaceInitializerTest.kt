package eu.torvian.chatbot.worker.config

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ensureWorkspaceDirectory], covering creation, idempotency, and failure cases.
 */
class WorkspaceInitializerTest {

    @Test
    fun `creates missing workspace directory`() {
        val base = createTempDirectory("workspace-init-test")
        val workspace = base.resolve("missing/workspace")

        val result = ensureWorkspaceDirectory(workspace)

        assertTrue(result.isRight(), "expected success: ${result}")
        assertTrue(workspace.exists(), "workspace should exist after init")
        assertTrue(workspace.isDirectory(), "workspace should be a directory")
    }

    @Test
    fun `is idempotent when directory already exists`() {
        val base = createTempDirectory("workspace-init-test")
        val workspace = base.resolve("existing").createDirectories()

        val result = ensureWorkspaceDirectory(workspace)

        assertTrue(result.isRight(), "expected success on existing directory: ${result}")
        assertTrue(workspace.isDirectory())
    }

    @Test
    fun `fails when path exists as a regular file`() {
        val base = createTempDirectory("workspace-init-test")
        val workspace = base.resolve("file-not-dir").let {
            createTempFile(it.parent, it.fileName.toString())
        }

        val result = ensureWorkspaceDirectory(workspace)

        assertTrue(result.isLeft(), "expected failure when path is a regular file")
    }

    @Test
    fun `fails when parent is a regular file`() {
        val base = createTempDirectory("workspace-init-test")
        val fileParent = base.resolve("file-parent").let {
            createTempFile(it.parent, it.fileName.toString())
        }
        val workspace = fileParent.resolve("child")

        val result = ensureWorkspaceDirectory(workspace)

        assertTrue(result.isLeft(), "expected failure when an ancestor is a regular file")
        assertFalse(workspace.exists())
    }
}
