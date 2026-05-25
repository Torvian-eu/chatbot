package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Appearance settings tab for managing theme and preference scope.
 *
 * This tab allows users to:
 * - Select a theme (System Default, Light, or Dark)
 * - Choose whether preferences apply to this device only or globally
 * - See the inheritance chain showing global and device-specific values
 *
 * @param state The current state of the appearance tab.
 * @param actions The action callbacks for the appearance tab.
 * @param modifier Modifier applied to the tab.
 */
@Composable
fun AppearanceTab(
    state: AppearanceTabState,
    actions: AppearanceTabActions,
    modifier: Modifier = Modifier
) {
    val (globalTheme, deviceTheme, effectiveTheme) = remember(state) {
        val currentTheme = state.detailedPreferences["current_theme"]
        Triple(
            currentTheme?.globalValue,
            currentTheme?.deviceValue,
            currentTheme?.deviceValue ?: currentTheme?.globalValue
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme section header
        item {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Theme options
        items(
            items = listOf(
                ThemeOption(
                    id = null,
                    label = "System Default"
                ),
                ThemeOption(
                    id = "light",
                    label = "Light"
                ),
                ThemeOption(
                    id = "dark",
                    label = "Dark"
                ),
                ThemeOption(
                    id = "deep_cobalt_light",
                    label = "Deep Cobalt Light"
                ),
                ThemeOption(
                    id = "deep_cobalt_dark",
                    label = "Deep Cobalt Dark"
                ),
                ThemeOption(
                    id = "modern_neutral_light",
                    label = "Modern Neutral Light"
                ),
                ThemeOption(
                    id = "modern_neutral_dark",
                    label = "Modern Neutral Dark"
                ),
                ThemeOption(
                    id = "tech_innovation_light",
                    label = "Tech Innovation Light"
                ),
                ThemeOption(
                    id = "tech_innovation_dark",
                    label = "Tech Innovation Dark"
                )
            ),
            key = { it.id ?: "system" }
        ) { option ->
            ThemeOptionItem(
                option = option,
                isSelected = effectiveTheme == option.id,
                globalTheme = globalTheme,
                deviceTheme = deviceTheme,
                onSelect = { actions.onSetTheme(option.id) }
            )
        }

        // Scope section header
        item {
            Text(
                text = "Scope",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Scope switch
        item {
            ScopeSwitchItem(
                isDeviceScoped = state.isDeviceScoped,
                onCheckedChange = { actions.onSetDeviceScoped(it) }
            )
        }

        // Description text
        item {
            Text(
                text = "Device settings override Global settings. When 'Apply to this device only' is enabled, your preferences will only affect this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Represents a theme option for display.
 */
private data class ThemeOption(
    val id: String?,
    val label: String
)

/**
 * List item for a theme option with a radio button.
 *
 * @param option The theme option to display.
 * @param isSelected Whether this option is currently selected.
 * @param globalTheme The global theme value, if any.
 * @param deviceTheme The device-specific theme value, if any.
 * @param onSelect Callback when the option is selected.
 * @param modifier Modifier applied to the item.
 */
@Composable
private fun ThemeOptionItem(
    option: ThemeOption,
    isSelected: Boolean,
    globalTheme: String?,
    deviceTheme: String?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Build supporting content based on the option and state
    val supportingText = when {
        // For System Default option, show what the global value is
        option.id == null && globalTheme != null -> {
            "Currently: Global ${globalTheme.replaceFirstChar { it.titlecase() }}"
        }
        // For Light/Dark options, show if this is a device override
        option.id != null && globalTheme != null && deviceTheme != null && isSelected -> {
            "Overriding Global value"
        }
        else -> null
    }

    ListItem(
        headlineContent = { Text(option.label) },
        supportingContent = if (supportingText != null) {
            {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (option.id == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        } else null,
        trailingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
        },
        modifier = modifier
            .clickable(onClick = onSelect)
            .fillMaxWidth()
    )
}

/**
 * List item for the scope switch.
 */
@Composable
private fun ScopeSwitchItem(
    isDeviceScoped: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text("Apply to this device only") },
        supportingContent = {
            Text(
                text = if (isDeviceScoped) {
                    "Preferences will only apply to this device"
                } else {
                    "Preferences will apply to all your devices"
                },
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            Switch(
                checked = isDeviceScoped,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = modifier
            .clickable { onCheckedChange(!isDeviceScoped) }
            .fillMaxWidth()
    )
}
