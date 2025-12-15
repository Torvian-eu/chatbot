package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.ConfigCheckbox
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ModelSettingsFormState

/**
 * Dynamic form dialog for adding/editing settings profiles.
 * Adapts form content based on the settings type.
 */
@Composable
fun ModelSettingsFormDialog(
    title: String,
    formState: ModelSettingsFormState,
    onFormUpdate: ((ModelSettingsFormState) -> ModelSettingsFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.widthIn(min = 500.dp, max = 700.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Common Fields
                    ConfigTextField(
                        value = formState.name,
                        onValueChange = { value -> onFormUpdate { it.withUpdatedName(value) } },
                        label = "Profile Name *",
                        isError = formState.name.isBlank()
                    )

                    // Dynamic Form Content
                    when (formState) {
                        is ModelSettingsFormState.Chat -> ChatFormContent(formState, onFormUpdate)
                        is ModelSettingsFormState.Embedding -> EmbeddingFormContent(formState, onFormUpdate)
                    }

                    // Custom Params (Advanced)
                    ConfigTextField(
                        value = formState.customParamsJson,
                        onValueChange = { value -> onFormUpdate { it.withUpdatedCustomParams(value) } },
                        label = "Custom Parameters (JSON)",
                        singleLine = false,
                        modifier = Modifier.height(120.dp)
                    )

                    // Error Message
                    formState.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSave, enabled = formState.name.isNotBlank()) {
                        Text(if (formState.mode == FormMode.NEW) "Add Profile" else "Save Changes")
                    }
                }
            }
        }
    }
}

// Helper functions to update sealed class state
private fun ModelSettingsFormState.withUpdatedName(name: String): ModelSettingsFormState {
    return when(this) {
        is ModelSettingsFormState.Chat -> copy(name = name)
        is ModelSettingsFormState.Embedding -> copy(name = name)
    }
}

private fun ModelSettingsFormState.withUpdatedCustomParams(json: String): ModelSettingsFormState {
     return when(this) {
        is ModelSettingsFormState.Chat -> copy(customParamsJson = json)
        is ModelSettingsFormState.Embedding -> copy(customParamsJson = json)
    }
}

/**
 * Form content specific to Chat model settings.
 */
@Composable
private fun ChatFormContent(
    formState: ModelSettingsFormState.Chat,
    onFormUpdate: ((ModelSettingsFormState) -> ModelSettingsFormState) -> Unit
) {
    ConfigTextField(
        value = formState.systemMessage,
        onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(systemMessage = value) } },
        label = "System Message",
        singleLine = false,
        modifier = Modifier.height(120.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigTextField(
            value = formState.temperature,
            onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(temperature = value) } },
            label = "Temperature",
            modifier = Modifier.weight(1f)
        )
        ConfigTextField(
            value = formState.maxTokens,
            onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(maxTokens = value) } },
            label = "Max Tokens",
            modifier = Modifier.weight(1f)
        )
    }
    ConfigTextField(
        value = formState.topP,
        onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(topP = value) } },
        label = "Top P"
    )
    ConfigTextField(
        value = formState.stopSequences,
        onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(stopSequences = value) } },
        label = "Stop Sequences (comma-separated)"
    )
    ConfigCheckbox(
        checked = formState.stream,
        onCheckedChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Chat).copy(stream = value) } },
        label = "Enable Streaming"
    )
}

/**
 * Form content specific to Embedding model settings.
 */
@Composable
private fun EmbeddingFormContent(
    formState: ModelSettingsFormState.Embedding,
    onFormUpdate: ((ModelSettingsFormState) -> ModelSettingsFormState) -> Unit
) {
    ConfigTextField(
        value = formState.dimensions,
        onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Embedding).copy(dimensions = value) } },
        label = "Dimensions"
    )
    ConfigTextField(
        value = formState.encodingFormat,
        onValueChange = { value -> onFormUpdate { (it as ModelSettingsFormState.Embedding).copy(encodingFormat = value) } },
        label = "Encoding Format"
    )
}
