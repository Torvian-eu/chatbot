package eu.torvian.chatbot.server.service.tool.impl

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [WeatherToolExecutor].
 *
 * This test suite verifies:
 * - Weather data retrieval for supported cities
 * - Mock data validation
 * - Input validation
 * - Configuration validation
 */
class WeatherToolExecutorTest {

    private val weatherExecutor = WeatherToolExecutor()

    private fun createWeatherToolDefinition(): MiscToolDefinition {
        val now = Clock.System.now()
        return MiscToolDefinition(
            id = 1L,
            name = "get_weather",
            description = "Get weather information for major cities",
            type = ToolType.WEATHER,
            config = buildJsonObject {},
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("city", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("city"))
                })
            },
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `executeTool should return weather data for London`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = buildJsonObject {
            put("city", JsonPrimitive("London"))
        }.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isRight(), "Expected successful execution")
        val output = result.getOrElse { fail("Expected Right but got Left") }

        // Parse and verify output
        val outputJson = Json.parseToJsonElement(output).jsonObject
        assertEquals("London", outputJson["city"]?.jsonPrimitive?.content)
        assertEquals("United Kingdom", outputJson["country"]?.jsonPrimitive?.content)
        assertEquals("celsius", outputJson["temperature_unit"]?.jsonPrimitive?.content)
        assertNotNull(outputJson["temperature"]?.jsonPrimitive?.doubleOrNull)
        assertNotNull(outputJson["condition"]?.jsonPrimitive?.content)
        assertNotNull(outputJson["humidity"]?.jsonPrimitive?.intOrNull)
        assertNotNull(outputJson["wind_speed"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("km/h", outputJson["wind_speed_unit"]?.jsonPrimitive?.content)
        assertNotNull(outputJson["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `executeTool should return weather data for Paris`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = buildJsonObject {
            put("city", JsonPrimitive("Paris"))
        }.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isRight(), "Expected successful execution")
        val output = result.getOrElse { fail("Expected Right but got Left") }

        val outputJson = Json.parseToJsonElement(output).jsonObject
        assertEquals("Paris", outputJson["city"]?.jsonPrimitive?.content)
        assertEquals("France", outputJson["country"]?.jsonPrimitive?.content)
    }

    @Test
    fun `executeTool should be case-insensitive for city names`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = buildJsonObject {
            put("city", JsonPrimitive("AMSTERDAM"))
        }.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isRight(), "Expected successful execution")
        val output = result.getOrElse { fail("Expected Right but got Left") }

        val outputJson = Json.parseToJsonElement(output).jsonObject
        assertEquals("Amsterdam", outputJson["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `executeTool should return error for unsupported city`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = buildJsonObject {
            put("city", JsonPrimitive("Tokyo"))
        }.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isLeft(), "Expected error for unsupported city")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error is ToolExecutionError.InvalidInput)
        assertTrue(error.message.contains("Weather data not available"))
    }

    @Test
    fun `executeTool should return error for missing city parameter`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = buildJsonObject {}.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isLeft(), "Expected error for missing city")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error is ToolExecutionError.InvalidInput)
        assertTrue(error.message.contains("Missing or empty required parameter 'city'"))
    }

    @Test
    fun `executeTool should return error for null input`() = runTest {
        val toolDef = createWeatherToolDefinition()

        val result = weatherExecutor.executeTool(toolDef, null)

        assertTrue(result.isLeft(), "Expected error for null input")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error is ToolExecutionError.InvalidInput)
    }

    @Test
    fun `executeTool should return error for invalid JSON`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val inputJson = "not valid json"

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isLeft(), "Expected error for invalid JSON")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error is ToolExecutionError.InvalidInput)
        assertTrue(error.message.contains("Failed to parse input JSON"))
    }

    @Test
    fun `executeTool should return error for wrong tool type`() = runTest {
        val toolDef = createWeatherToolDefinition().copy(type = ToolType.WEB_SEARCH)
        val inputJson = buildJsonObject {
            put("city", JsonPrimitive("London"))
        }.toString()

        val result = weatherExecutor.executeTool(toolDef, inputJson)

        assertTrue(result.isLeft(), "Expected error for wrong tool type")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error is ToolExecutionError.InvalidConfiguration)
    }

    @Test
    fun `validateConfiguration should accept WEATHER tool type`() {
        val toolDef = createWeatherToolDefinition()

        val result = weatherExecutor.validateConfiguration(toolDef)

        assertTrue(result.isRight(), "Expected valid configuration")
    }

    @Test
    fun `validateConfiguration should reject non-WEATHER tool type`() {
        val toolDef = createWeatherToolDefinition().copy(type = ToolType.WEB_SEARCH)

        val result = weatherExecutor.validateConfiguration(toolDef)

        assertTrue(result.isLeft(), "Expected invalid configuration")
        val error = result.swap().getOrElse { fail("Expected Left but got Right") }
        assertTrue(error.message.contains("Tool type must be WEATHER"))
    }

    @Test
    fun `executeTool should support all defined cities`() = runTest {
        val toolDef = createWeatherToolDefinition()
        val cities = listOf(
            "London", "Paris", "Amsterdam", "Berlin", "Rome",
            "Madrid", "Barcelona", "Brussels", "Vienna", "Lisbon"
        )

        for (city in cities) {
            val inputJson = buildJsonObject {
                put("city", JsonPrimitive(city))
            }.toString()

            val result = weatherExecutor.executeTool(toolDef, inputJson)

            assertTrue(
                result.isRight(),
                "Expected successful execution for city: $city"
            )

            val output = result.getOrElse { fail("Expected Right but got Left for city: $city") }
            val outputJson = Json.parseToJsonElement(output).jsonObject

            assertEquals(
                city,
                outputJson["city"]?.jsonPrimitive?.content,
                "Expected correct city name in output"
            )
            assertNotNull(
                outputJson["temperature"]?.jsonPrimitive?.doubleOrNull,
                "Expected valid temperature for $city"
            )
        }
    }
}

