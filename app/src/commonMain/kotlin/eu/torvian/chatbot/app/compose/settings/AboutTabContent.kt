package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.settings.AboutViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the About settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together.
 * It obtains the [LocalUriHandler] and provides the click handler to the presentational component.
 *
 * @param viewModel The About ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param onBreadcrumbsChanged Callback used by the settings shell to update breadcrumbs.
 */
@Composable
fun AboutTabRoute(
    viewModel: AboutViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Update breadcrumbs
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.About.displayLabel))
    }

    // Hoist URI handling to the route level
    val uriHandler = LocalUriHandler.current

    AboutTabContent(
        appName = viewModel.appName,
        appVersion = viewModel.appVersion,
        tagline = viewModel.tagline,
        links = listOf(
            "Website" to viewModel.websiteUrl,
            "GitHub" to viewModel.githubUrl,
            "Reddit" to viewModel.redditUrl
        ),
        license = viewModel.license,
        onLinkClick = { uriHandler.openUri(it) },
        modifier = modifier
    )
}

/**
 * About settings tab displaying application information and external links.
 *
 * This is a presentational component that receives all data as parameters,
 * making it easily testable in isolation without dependencies on ViewModel or UriHandler.
 *
 * This tab shows:
 * - The application name and version in the header
 * - A tagline describing the application
 * - Clickable links to external resources
 * - License information in the footer
 *
 * @param appName The application name to display.
 * @param appVersion The version string to display.
 * @param tagline A brief description of the application.
 * @param links List of label-URL pairs for external links.
 * @param license The license information string.
 * @param onLinkClick Callback invoked when a link is clicked, receiving the URL.
 * @param modifier Modifier applied to the tab.
 */
@Composable
fun AboutTabContent(
    appName: String,
    appVersion: String,
    tagline: String,
    links: List<Pair<String, String>>,
    license: String,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = spacedBy(16.dp)
    ) {
        // Header: App name and version
        Column(
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Version $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Body: Tagline
        Text(
            text = tagline,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Links section
        Column(
            verticalArrangement = spacedBy(8.dp)
        ) {
            Text(
                text = "Links",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            links.forEach { (label, url) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onLinkClick(url) }
                )
            }
        }

        // Footer: License
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
