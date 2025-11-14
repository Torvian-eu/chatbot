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
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Dialog for adding new models or editing existing ones.
 * Implements forms for E4.S1 (Add Model) and E4.S3 (Edit Model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFormDialog(
    title: String,
    form: ModelFormState,
    providers: List<LLMProvider>,
    onFormUpdate: ((ModelFormState) -> ModelFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onCancel) {
        Card(
            modifier = modifier.widthIn(min = 500.dp, max = 600.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Dialog Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Form Content
                ModelFormContent(
                    form = form,
                    providers = providers,
                    onFormUpdate = onFormUpdate
                )

                // Error message
                form.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onSave,
                        enabled = form.name.isNotBlank() && form.providerId != null
                    ) {
                        Text(if (form.mode == FormMode.NEW) "Add Model" else "Save Changes")
                    }
                }
            }
        }
    }
}

/**
 * Form content for model configuration.
 */
@Composable
private fun ModelFormContent(
    form: ModelFormState,
    providers: List<LLMProvider>,
    onFormUpdate: ((ModelFormState) -> ModelFormState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Model Name (required)
        ConfigTextField(
            value = form.name,
            onValueChange = { value ->
                onFormUpdate { it.copy(name = value) }
            },
            label = "Model Name *",
            placeholder = "e.g., gpt-4, claude-3-sonnet",
            isError = form.name.isBlank(),
            errorMessage = if (form.name.isBlank()) "Model name is required" else null
        )

        // Display Name (optional)
        ConfigTextField(
            value = form.displayName,
            onValueChange = { value ->
                onFormUpdate { it.copy(displayName = value) }
            },
            label = "Display Name",
            placeholder = "Optional friendly name for the UI"
        )

        // Provider Selection (required)
        val selectedProvider = providers.find { it.id == form.providerId }
        ConfigDropdown(
            selectedItem = selectedProvider,
            onItemSelected = { provider -> onFormUpdate { it.copy(providerId = provider.id) } },
            items = providers,
            label = "Provider *",
            itemText = { it.name },
            isError = form.providerId == null,
            errorMessage = if (form.providerId == null) "Provider selection is required" else null
        )

        // Model Type Selection
        ConfigDropdown(
            selectedItem = form.type,
            onItemSelected = { type -> onFormUpdate { it.copy(type = type) } },
            items = LLMModelType.entries,
            label = "Model Type",
            itemText = { it.name }
        )

        // Active Status
        ConfigCheckbox(
            checked = form.active,
            onCheckedChange = { checked -> onFormUpdate { it.copy(active = checked) } },
            label = "Active"
        )

        // Capabilities Section
        Text(
            text = "Capabilities",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Well-known capabilities with convenient toggles
                CapabilityCheckbox(
                    label = "Tool Calling",
                    description = "Model can interpret and use tool/function definitions",
                    isChecked = form.capabilities[LLMModelCapabilities.TOOL_CALLING]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.TOOL_CALLING, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Image Input",
                    description = "Model can process images as part of input",
                    isChecked = form.capabilities[LLMModelCapabilities.MULTIMODAL_IMAGE_INPUT]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.MULTIMODAL_IMAGE_INPUT, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Audio Input",
                    description = "Model can process audio as part of input",
                    isChecked = form.capabilities[LLMModelCapabilities.MULTIMODAL_AUDIO_INPUT]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.MULTIMODAL_AUDIO_INPUT, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Video Input",
                    description = "Model can process video as part of input",
                    isChecked = form.capabilities[LLMModelCapabilities.MULTIMODAL_VIDEO_INPUT]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.MULTIMODAL_VIDEO_INPUT, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "JSON Output",
                    description = "Model can output structured JSON",
                    isChecked = form.capabilities[LLMModelCapabilities.JSON_OUTPUT]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.JSON_OUTPUT, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Streaming Output",
                    description = "Model supports streaming responses",
                    isChecked = form.capabilities[LLMModelCapabilities.STREAMING_OUTPUT]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.STREAMING_OUTPUT, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Thinking Process",
                    description = "Model can generate responses with explicit reasoning steps",
                    isChecked = form.capabilities[LLMModelCapabilities.THINKING_PROCESS]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.THINKING_PROCESS, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )

                CapabilityCheckbox(
                    label = "Safety Controls",
                    description = "Model incorporates content filtering or safety features",
                    isChecked = form.capabilities[LLMModelCapabilities.SAFETY_CONTROLS]?.let {
                        it.toString().toBooleanStrictOrNull() ?: false
                    } ?: false,
                    onCheckedChange = { checked ->
                        onFormUpdate { formState ->
                            val updatedCaps = updateCapability(formState.capabilities, LLMModelCapabilities.SAFETY_CONTROLS, checked)
                            formState.copy(capabilities = updatedCaps)
                        }
                    }
                )
            }
        }

        // Helper text
        Text(
            text = "* Required fields",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Composable for displaying a capability checkbox with label and description.
 */
@Composable
private fun CapabilityCheckbox(
    label: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Helper function to update a capability in the JsonObject.
 * Adds or removes a capability while preserving other properties.
 */
private fun updateCapability(
    capabilities: JsonObject,
    key: String,
    enabled: Boolean
): JsonObject {
    return if (enabled) {
        buildJsonObject {
            capabilities.forEach { (k, v) -> put(k, v) }
            put(key, JsonPrimitive(true))
        }
    } else {
        buildJsonObject {
            capabilities.forEach { (k, v) ->
                if (k != key) put(k, v)
            }
        }
    }
}
