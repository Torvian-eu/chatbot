package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.*
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * Dialog for adding new models or editing existing ones.
 * Implements forms for E4.S1 (Add Model) and E4.S3 (Edit Model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFormDialog(
    title: String,
    form: ModelFormState,
    providersForSelection: UiState<ApiError, List<LLMProvider>>,
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
                when {
                    providersForSelection.isLoading -> {
                        LoadingStateDisplay(
                            message = "Loading providers...",
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                    }

                    providersForSelection.isError -> {
                        val error = providersForSelection.errorOrNull
                        if (error != null) {
                            ErrorStateDisplay(
                                title = "Failed to load providers",
                                error = error,
                                onRetry = { /* Could add retry logic here */ },
                                modifier = Modifier.fillMaxWidth().height(200.dp)
                            )
                        }
                    }

                    providersForSelection.isSuccess -> {
                        val providers = providersForSelection.dataOrNull ?: emptyList()
                        ModelFormContent(
                            form = form,
                            providers = providers,
                            onFormUpdate = onFormUpdate
                        )
                    }
                }

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
@OptIn(ExperimentalMaterial3Api::class)
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

        // Helper text
        Text(
            text = "* Required fields",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
