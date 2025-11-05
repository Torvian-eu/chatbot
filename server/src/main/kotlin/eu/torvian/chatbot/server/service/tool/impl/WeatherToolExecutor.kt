package eu.torvian.chatbot.server.service.tool.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Executor for weather tools with mocked data.
 * Provides weather information for major cities for testing LLM tool calling.
 *
 * This is a mock implementation for testing purposes only.
 */
class WeatherToolExecutor : ToolExecutor {

    private val logger: Logger = LogManager.getLogger(WeatherToolExecutor::class.java)

    companion object {
        // Mock weather data for major cities
        private val MOCK_WEATHER_DATA = mapOf(
            "london" to WeatherData(
                city = "London",
                country = "United Kingdom",
                temperature = 12.5,
                condition = "Cloudy",
                humidity = 78,
                windSpeed = 15.3,
                description = "Overcast with a chance of light rain"
            ),
            "paris" to WeatherData(
                city = "Paris",
                country = "France",
                temperature = 14.2,
                condition = "Partly Cloudy",
                humidity = 65,
                windSpeed = 12.1,
                description = "Partly cloudy with sunny intervals"
            ),
            "amsterdam" to WeatherData(
                city = "Amsterdam",
                country = "Netherlands",
                temperature = 11.8,
                condition = "Rainy",
                humidity = 82,
                windSpeed = 18.5,
                description = "Light rain with occasional showers"
            ),
            "berlin" to WeatherData(
                city = "Berlin",
                country = "Germany",
                temperature = 13.0,
                condition = "Sunny",
                humidity = 55,
                windSpeed = 8.7,
                description = "Clear skies with plenty of sunshine"
            ),
            "rome" to WeatherData(
                city = "Rome",
                country = "Italy",
                temperature = 18.5,
                condition = "Sunny",
                humidity = 60,
                windSpeed = 6.2,
                description = "Clear and warm with light breeze"
            ),
            "madrid" to WeatherData(
                city = "Madrid",
                country = "Spain",
                temperature = 16.8,
                condition = "Partly Cloudy",
                humidity = 52,
                windSpeed = 10.4,
                description = "Mild temperature with scattered clouds"
            ),
            "barcelona" to WeatherData(
                city = "Barcelona",
                country = "Spain",
                temperature = 17.5,
                condition = "Sunny",
                humidity = 68,
                windSpeed = 11.2,
                description = "Sunny weather with sea breeze"
            ),
            "brussels" to WeatherData(
                city = "Brussels",
                country = "Belgium",
                temperature = 11.2,
                condition = "Cloudy",
                humidity = 75,
                windSpeed = 14.8,
                description = "Overcast with cool temperatures"
            ),
            "vienna" to WeatherData(
                city = "Vienna",
                country = "Austria",
                temperature = 12.8,
                condition = "Partly Cloudy",
                humidity = 62,
                windSpeed = 9.5,
                description = "Pleasant with partial cloud cover"
            ),
            "lisbon" to WeatherData(
                city = "Lisbon",
                country = "Portugal",
                temperature = 19.2,
                condition = "Sunny",
                humidity = 58,
                windSpeed = 13.6,
                description = "Warm and sunny coastal weather"
            )
        )
    }

    override suspend fun executeTool(
        toolDefinition: ToolDefinition,
        inputJson: String?
    ): Either<ToolExecutionError, String> = either {
        // Validate tool type
        ensure(toolDefinition.type == ToolType.WEATHER) {
            ToolExecutionError.InvalidConfiguration(
                "WeatherToolExecutor can only handle WEATHER tools"
            )
        }

        // Validate configuration
        validateConfiguration(toolDefinition).bind()

        // Parse input JSON
        val input = try {
            ensure(!inputJson.isNullOrBlank()) { ToolExecutionError.InvalidInput("Missing or empty input JSON") }
            Json.parseToJsonElement(inputJson).jsonObject
        } catch (e: Exception) {
            raise(ToolExecutionError.InvalidInput(
                "Failed to parse input JSON: ${e.message ?: "Unknown error"}"
            ))
        }

        // Extract city from input
        val city = input["city"]?.jsonPrimitive?.contentOrNull
        ensure(!city.isNullOrBlank()) {
            ToolExecutionError.InvalidInput(
                "Missing or empty required parameter 'city'"
            )
        }

        logger.info("Executing weather lookup: city='$city'")

        // Get weather data (case-insensitive lookup)
        val weatherData = MOCK_WEATHER_DATA[city.lowercase()]

        if (weatherData == null) {
            logger.warn("Weather data not available for city: $city")
            raise(ToolExecutionError.InvalidInput(
                "Weather data not available for city: $city. Available cities: ${MOCK_WEATHER_DATA.keys.joinToString(", ")}"
            ))
        }

        logger.info("Weather lookup completed for: ${weatherData.city}")

        // Format result as JSON
        buildJsonObject {
            put("city", weatherData.city)
            put("country", weatherData.country)
            put("temperature", weatherData.temperature)
            put("temperature_unit", "celsius")
            put("condition", weatherData.condition)
            put("humidity", weatherData.humidity)
            put("wind_speed", weatherData.windSpeed)
            put("wind_speed_unit", "km/h")
            put("description", weatherData.description)
        }.toString()
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

    /**
     * Data class representing weather information.
     */
    private data class WeatherData(
        val city: String,
        val country: String,
        val temperature: Double,
        val condition: String,
        val humidity: Int,
        val windSpeed: Double,
        val description: String
    )
}

