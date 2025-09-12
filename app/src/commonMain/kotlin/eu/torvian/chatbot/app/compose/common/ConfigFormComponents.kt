package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Reusable text field component for configuration forms with validation support.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes
 * @param label Label text for the field
 * @param modifier Modifier for styling
 * @param placeholder Placeholder text
 * @param isError Whether the field is in error state
 * @param errorMessage Error message to display
 * @param singleLine Whether this is a single line field
 * @param keyboardType Type of keyboard to show
 */
@Composable
fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder) }
            } else null,
            isError = isError,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Reusable number field component for numeric configuration values.
 *
 * @param value Current text value (as string for UI input)
 * @param onValueChange Callback when text changes
 * @param label Label text for the field
 * @param modifier Modifier for styling
 * @param placeholder Placeholder text
 * @param isError Whether the field is in error state
 * @param errorMessage Error message to display
 * @param isDecimal Whether to allow decimal numbers
 */
@Composable
fun ConfigNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    errorMessage: String? = null,
    isDecimal: Boolean = false
) {
    ConfigTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        isError = isError,
        errorMessage = errorMessage,
        keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
    )
}

/**
 * Reusable dropdown component for configuration forms.
 *
 * @param T The type of items in the dropdown
 * @param selectedItem Currently selected item
 * @param onItemSelected Callback when item is selected
 * @param items List of items to display
 * @param label Label text for the dropdown
 * @param modifier Modifier for styling
 * @param itemText Function to get display text for an item
 * @param isError Whether the dropdown is in error state
 * @param errorMessage Error message to display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ConfigDropdown(
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    items: List<T>,
    label: String,
    modifier: Modifier = Modifier,
    itemText: (T) -> String = { it.toString() },
    isError: Boolean = false,
    errorMessage: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedItem?.let(itemText) ?: "",
                onValueChange = { },
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                isError = isError,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(type = androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemText(item)) },
                        onClick = {
                            onItemSelected(item)
                            expanded = false
                        }
                    )
                }
            }
        }
        
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Reusable checkbox component for configuration forms.
 *
 * @param checked Current checked state
 * @param onCheckedChange Callback when checked state changes
 * @param label Label text for the checkbox
 * @param modifier Modifier for styling
 */
@Composable
fun ConfigCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Specialized text field for credential input with masking support.
 *
 * @param value Current credential value
 * @param onValueChange Callback when value changes
 * @param label Label text for the field
 * @param modifier Modifier for styling
 * @param placeholder Placeholder text
 * @param isError Whether the field is in error state
 * @param errorMessage Error message to display
 */
@Composable
fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Enter API key...",
    isError: Boolean = false,
    errorMessage: String? = null
) {
    var isVisible by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            isError = isError,
            singleLine = true,
            visualTransformation = if (isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { isVisible = !isVisible }) {
                    Icon(
                        imageVector = if (isVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (isVisible) "Hide credential" else "Show credential"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
