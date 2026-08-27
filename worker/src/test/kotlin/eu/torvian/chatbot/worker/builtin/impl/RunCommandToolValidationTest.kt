package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers malformed run-command inputs and accumulation of logical validation errors.
 *
 * Each test owns its tool fixture and temporary workspace so the class is safe to execute in
 * parallel with the other run-command test classes.
 */
class RunCommandToolValidationTest {
    /** Tool instance kept local to this test class to avoid shared mutable fixtures. */
    private val tool = RunCommandTool()

    /**
     * Verifies the run-command behavior described by the scenario name: single string args are rejected with invalid input error and helpful message.
     */
    @Test
    fun `single string args are rejected with invalid input error and helpful message`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Keep `args` as a JsonPrimitive string which should now be rejected.
            val input = RunCommandTestSupport.singleStringArgsInput("hello")
            val result = tool.execute(input, RunCommandTestSupport.context(dir))

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertTrue(
                errorText.contains("array of strings"),
                "error message should mention array syntax; got: $errorText"
            )
            assertTrue(
                errorText.contains("args"),
                "error message should mention the args field; got: $errorText"
            )
            assertTrue(
                errorText.contains("Use array syntax"),
                "error message should suggest array syntax; got: $errorText"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: command field containing spaces returns invalid input error with guidance.
     */
    @Test
    fun `command field containing spaces returns invalid input error with guidance`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Simulate LLM misuse: full command line placed in the `command` field.
            val result =
                tool.execute(RunCommandTestSupport.buildInput("echo hello world"), RunCommandTestSupport.context(dir))

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertEquals(
                true,
                errorText.contains("args"),
                "error message should mention the 'args' field; got: $errorText"
            )
            assertEquals(
                true,
                errorText.contains("echo hello world"),
                "error message should echo back the received command; got: $errorText"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: multiple validation errors are accumulated instead of failing on first.
     */
    @Test
    fun `multiple validation errors are accumulated instead of failing on first`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Both command has spaces AND timeout is invalid (<= 0).
            val result = tool.execute(
                RunCommandTestSupport.buildInput("echo hello world", timeout = 0),
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("command") && it.contains("spaces") },
                "should contain command whitespace error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("timeout") },
                "should contain timeout error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: malformed command and args values are rejected as invalid input.
     */
    @Test
    fun `malformed command and args values are rejected as invalid input`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("command", buildJsonObject { put("nested", "value") })
                    put("args", buildJsonObject { put("nested", "value") })
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(errorTexts.any { it.contains("command") && it.contains("string") })
            assertTrue(errorTexts.any { it.contains("args") && it.contains("array of strings") })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: unknown parameter returns invalid input error.
     */
    @Test
    fun `unknown parameter returns invalid input error`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with an unknown parameter "unknownParam"
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo")
                    put("unknownParam", "someValue")
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertTrue(
                errorText.contains("unknownParam"),
                "error message should mention the unknown parameter; got: $errorText"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: multiple unknown parameters are accumulated in validation errors.
     */
    @Test
    fun `multiple unknown parameters are accumulated in validation errors`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with multiple unknown parameters
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo")
                    put("unknownParam1", "value1")
                    put("unknownParam2", "value2")
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 unknown parameter errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("unknownParam1") },
                "should contain unknownParam1 error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("unknownParam2") },
                "should contain unknownParam2 error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: unknown parameter combined with other validation errors are all accumulated.
     */
    @Test
    fun `unknown parameter combined with other validation errors are all accumulated`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with unknown parameter, command with spaces, and invalid timeout
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo hello world")
                    put("timeout", 0)
                    put("unknownParam", "someValue")
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(3, errorsArray.size, "should have accumulated 3 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("unknownParam") },
                "should contain unknownParam error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("spaces") },
                "should contain command whitespace error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("timeout") },
                "should contain timeout error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: run_command with invalid maxLines or maxBytes returns invalid input error.
     */
    @Test
    fun `run_command with invalid maxLines or maxBytes returns invalid input error`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = RunCommandTestSupport.echoCommand("hello")
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                    put("maxLines", 0)
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
