package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat

/**
 * Helper function to generate a resource URL.
 *
 * This function uses the `href` function from the `Resources` plugin to generate a URL for a given resource.
 * It is useful for testing Ktor applications that use the `Resources` plugin for defining API endpoints.
 *
 * @param T The type of the resource.
 * @param resource The resource for which to generate the URL.
 * @return The generated URL as a string.
 */
inline fun <reified T> href(resource: T): String {
    return href(resourcesFormat, resource)
}
val resourcesFormat = ResourcesFormat()