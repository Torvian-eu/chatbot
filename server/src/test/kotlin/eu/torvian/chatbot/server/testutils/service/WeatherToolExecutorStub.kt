package eu.torvian.chatbot.server.testutils.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import kotlinx.serialization.json.*

/**
 * Stub implementation of [ToolExecutor] for testing weather tool functionality.
 *
 * This stub provides mock weather data for testing purposes without making actual API calls.
 */
class WeatherToolExecutorStub : ToolExecutor {

    override suspend fun executeTool(
        toolDefinition: ToolDefinition,
        inputJson: String?
    ): Either<ToolExecutionError, String> {
        if (toolDefinition.type != ToolType.WEATHER) {
            return ToolExecutionError.InvalidConfiguration(
                "WeatherToolExecutorStub can only handle WEATHER tools"
            ).left()
        }

        if (inputJson.isNullOrBlank()) {
            return ToolExecutionError.InvalidInput("Missing or empty input JSON").left()
        }

        val input = try {
            Json.parseToJsonElement(inputJson).jsonObject
        } catch (e: Exception) {
            return ToolExecutionError.InvalidInput(
                "Failed to parse input JSON: ${e.message ?: "Unknown error"}"
            ).left()
        }

        val city = input["city"]?.jsonPrimitive?.contentOrNull
        if (city.isNullOrBlank()) {
            return ToolExecutionError.InvalidInput(
                "Missing or empty required parameter 'city'"
            ).left()
        }

        // Return mock weather data for London (regardless of input city)
        val result = buildJsonObject {
            put("city", "London")
            put("country", "United Kingdom")
            put("temperature", 12.5)
            put("temperature_unit", "celsius")
            put("condition", "Cloudy")
            put("humidity", 78)
            put("wind_speed", 15.3)
            put("wind_speed_unit", "km/h")
            put("description", "Overcast with a chance of light rain (mock data)")
        }.toString()

        return result.right()
    }

    override fun validateConfiguration(
        toolDefinition: ToolDefinition
    ): Either<ToolExecutionError.InvalidConfiguration, Unit> {
        if (toolDefinition.type != ToolType.WEATHER) {
            return ToolExecutionError.InvalidConfiguration(
                "Tool type must be WEATHER"
            ).left()
        }
        return Unit.right()
    }
}

