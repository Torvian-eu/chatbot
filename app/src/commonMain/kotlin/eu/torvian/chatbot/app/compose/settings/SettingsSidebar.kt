package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders the settings category navigation column.
 *
 * @param selectedCategory The currently selected top-level category.
 * @param onCategorySelected Callback invoked when the user switches categories.
 * @param modifier Modifier applied to the outer sidebar container.
 */
@Composable
fun SettingsSidebar(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 240.dp, max = 320.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Categories",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp)
            )

            SettingsCategory.entries.forEach { category ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = category.displayLabel,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    selected = category == selectedCategory,
                    onClick = { onCategorySelected(category) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = NavigationDrawerItemDefaults.colors()
                )
            }
        }
    }
}


